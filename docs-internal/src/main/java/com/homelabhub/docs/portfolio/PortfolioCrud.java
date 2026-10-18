package com.homelabhub.docs.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.docs.portfolio.ResourceSpec.Field;
import com.homelabhub.docs.portfolio.ResourceSpec.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generic create/read/update/delete for the portfolio content types (plan 8.3). Validation is server-side and strict;
 * updates use optimistic locking (`version`), so two open tabs cannot silently overwrite each other.
 */
@Service
public class PortfolioCrud {

    public static final class ValidationException extends RuntimeException {
        private final transient Map<String, String> errors;

        public ValidationException(Map<String, String> errors) {
            super("Invalid input");
            this.errors = errors;
        }

        public Map<String, String> errors() {
            return errors;
        }
    }

    public static final class ConflictException extends RuntimeException {
        private final transient Map<String, Object> current;

        public ConflictException(Map<String, Object> current) {
            super("Changed by someone else");
            this.current = current;
        }

        public Map<String, Object> current() {
            return current;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern UNSAFE_SCHEME = Pattern.compile("(?i)^\\s*(javascript|data|vbscript|file):");
    private static final int MAX_TAGS = 50;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PortfolioCrud(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public ResourceSpec spec(String path) {
        ResourceSpec s = PortfolioResources.ALL.get(path);
        if (s == null) {
            throw new NoSuchElementException("No such resource");
        }
        return s;
    }

    // ---------------------------------------------------------------- read

    public List<Map<String, Object>> list(ResourceSpec s) {
        Map<Long, List<Long>> skills = s.skillLinkTable() == null ? Map.of() : skillIds(s, null);
        Map<Long, List<String>> tags = s.tagsOwnerType() == null ? Map.of() : tags(s, null);
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM " + s.table() + " ORDER BY " + s.orderBy()).query((rs, n) -> row(s, rs)).list();
        for (Map<String, Object> r : rows) {
            attach(s, r, skills, tags);
        }
        return rows;
    }

    public Map<String, Object> get(ResourceSpec s, long id) {
        Map<String, Object> r = jdbc.sql("SELECT * FROM " + s.table() + " WHERE id = ?").param(id).query((rs, n) -> row(s, rs)).optional()
                .orElseThrow(() -> new NoSuchElementException("Not found"));
        attach(s, r, s.skillLinkTable() == null ? Map.of() : skillIds(s, id), s.tagsOwnerType() == null ? Map.of() : tags(s, id));
        return r;
    }

    private void attach(ResourceSpec s, Map<String, Object> r, Map<Long, List<Long>> skills, Map<Long, List<String>> tags) {
        long id = (Long) r.get("id");
        if (s.skillLinkTable() != null) {
            r.put("skillIds", skills.getOrDefault(id, List.of()));
        }
        if (s.tagsOwnerType() != null) {
            r.put("tags", tags.getOrDefault(id, List.of()));
        }
    }

    private Map<Long, List<Long>> skillIds(ResourceSpec s, Long only) {
        Map<Long, List<Long>> m = new HashMap<>();
        String sql = "SELECT " + s.skillLinkOwnerColumn() + ", skill_id FROM " + s.skillLinkTable() + (only == null ? "" : " WHERE " + s.skillLinkOwnerColumn() + " = " + only) + " ORDER BY 1, 2";
        jdbc.sql(sql).query((rs, n) -> {
            m.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
            return null;
        }).list();
        return m;
    }

    private Map<Long, List<String>> tags(ResourceSpec s, Long only) {
        Map<Long, List<String>> m = new HashMap<>();
        jdbc.sql("SELECT owner_id, text FROM unlinked_tags WHERE owner_type = ?" + (only == null ? "" : " AND owner_id = " + only) + " ORDER BY id")
                .param(s.tagsOwnerType()).query((rs, n) -> {
                    m.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                    return null;
                }).list();
        return m;
    }

    private Map<String, Object> row(ResourceSpec s, java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("version", rs.getInt("version"));
        if (s.hasParent()) {
            m.put(s.parentApi(), rs.getLong(s.parentColumn()));
        }
        for (Field f : s.fields()) {
            switch (f.type()) {
                case TEXT_LIST, JSON_LIST -> m.put(f.api(), PortfolioReader.jsonList(rs.getString(f.column())));
                case JSON_OBJECT -> m.put(f.api(), PortfolioReader.jsonMap(rs.getString(f.column())));
                case INT -> m.put(f.api(), rs.getInt(f.column()));
                default -> m.put(f.api(), rs.getString(f.column()));
            }
        }
        return m;
    }

    public Map<String, Long> usage(ResourceSpec s, long id) {
        get(s, id);
        Map<String, Long> out = new LinkedHashMap<>();
        for (ResourceSpec.Usage u : s.usage()) {
            out.put(u.label(), jdbc.sql(u.countSql()).param(id).query(Long.class).single());
        }
        return out;
    }

    // ---------------------------------------------------------------- write

    public Map<String, Object> create(ResourceSpec s, Map<String, Object> body) {
        if (s.singleton()) {
            throw new ValidationException(Map.of("_", "There is only one " + s.title().toLowerCase() + ". Save it instead."));
        }
        List<Object> values = new ArrayList<>();
        List<String> cols = validated(s, body, values);
        String sql = "INSERT INTO " + s.table() + " (" + String.join(", ", cols) + ") VALUES (" + "?,".repeat(cols.size()).replaceAll(",$", "") + ")";
        long id = tx.execute(st -> {
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            jdbc.sql(sql).params(values).update(kh);
            return kh.getKey().longValue();
        });
        return get(s, id);
    }

    /** Full replace of the editable fields. Requires the row's current `version`. */
    public Map<String, Object> update(ResourceSpec s, long id, Map<String, Object> body) {
        Integer version = version(body);
        List<Object> values = new ArrayList<>();
        List<String> cols = validated(s, body, values);
        String set = String.join(", ", cols.stream().map(c -> c + " = ?").toList()) + ", version = version + 1"
                + (s.hasUpdatedAt() ? ", updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')" : "");
        values.add(id);
        values.add(version);
        int n = tx.execute(st -> jdbc.sql("UPDATE " + s.table() + " SET " + set + " WHERE id = ? AND version = ?").params(values).update());
        if (n == 0) {
            throw new ConflictException(get(s, id)); // NoSuchElementException (404) when the row is gone
        }
        return get(s, id);
    }

    /** PUT on a singleton (the profile): creates it the first time, then updates with a version check. */
    public Map<String, Object> saveSingleton(ResourceSpec s, Map<String, Object> body) {
        boolean exists = jdbc.sql("SELECT count(*) FROM " + s.table() + " WHERE id = 1").query(Integer.class).single() > 0;
        if (exists) {
            return update(s, 1, body);
        }
        List<Object> values = new ArrayList<>();
        List<String> cols = validated(s, body, values);
        cols.add(0, "id");
        values.add(0, 1);
        tx.executeWithoutResult(st -> jdbc.sql("INSERT INTO " + s.table() + " (" + String.join(", ", cols) + ") VALUES (" + "?,".repeat(cols.size()).replaceAll(",$", "") + ")").params(values).update());
        return get(s, 1);
    }

    public void delete(ResourceSpec s, long id) {
        int n = tx.execute(st -> {
            if (s.tagsOwnerType() != null) {
                jdbc.sql("DELETE FROM unlinked_tags WHERE owner_type = ? AND owner_id = ?").params(s.tagsOwnerType(), id).update();
            }
            return jdbc.sql("DELETE FROM " + s.table() + " WHERE id = ?").param(id).update();
        });
        if (n == 0) {
            throw new NoSuchElementException("Not found");
        }
    }

    public Map<String, Object> setSkills(ResourceSpec s, long id, Object skillIds) {
        if (s.skillLinkTable() == null) {
            throw new NoSuchElementException("No skills on this content type");
        }
        get(s, id);
        Set<Long> ids = new LinkedHashSet<>();
        if (!(skillIds instanceof List<?> list) || list.size() > 200) {
            throw new ValidationException(Map.of("skillIds", "A list of at most 200 skill ids is required."));
        }
        for (Object o : list) {
            if (!(o instanceof Number n) || n.longValue() <= 0) {
                throw new ValidationException(Map.of("skillIds", "Skill ids are positive whole numbers."));
            }
            ids.add(n.longValue());
        }
        for (Long sid : ids) {
            if (jdbc.sql("SELECT count(*) FROM skills WHERE id = ?").param(sid).query(Integer.class).single() == 0) {
                throw new ValidationException(Map.of("skillIds", "There is no skill with id " + sid + "."));
            }
        }
        tx.executeWithoutResult(st -> {
            jdbc.sql("DELETE FROM " + s.skillLinkTable() + " WHERE " + s.skillLinkOwnerColumn() + " = ?").param(id).update();
            for (Long sid : ids) {
                jdbc.sql("INSERT INTO " + s.skillLinkTable() + " (" + s.skillLinkOwnerColumn() + ", skill_id) VALUES (?, ?)").params(id, sid).update();
            }
        });
        return get(s, id);
    }

    public Map<String, Object> setTags(ResourceSpec s, long id, Object tags) {
        if (s.tagsOwnerType() == null) {
            throw new NoSuchElementException("No tags on this content type");
        }
        get(s, id);
        Set<String> clean = new LinkedHashSet<>();
        if (!(tags instanceof List<?> list) || list.size() > MAX_TAGS) {
            throw new ValidationException(Map.of("tags", "A list of at most " + MAX_TAGS + " tags is required."));
        }
        for (Object o : list) {
            if (!(o instanceof String t) || t.isBlank() || t.strip().length() > 80 || hasControl(t, false)) {
                throw new ValidationException(Map.of("tags", "Each tag is text of 1 to 80 characters."));
            }
            clean.add(t.strip());
        }
        tx.executeWithoutResult(st -> {
            jdbc.sql("DELETE FROM unlinked_tags WHERE owner_type = ? AND owner_id = ?").params(s.tagsOwnerType(), id).update();
            for (String t : clean) {
                jdbc.sql("INSERT INTO unlinked_tags (owner_type, owner_id, text) VALUES (?, ?, ?)").params(s.tagsOwnerType(), id, t).update();
            }
        });
        return get(s, id);
    }

    // ---------------------------------------------------------------- validation

    private static Integer version(Map<String, Object> body) {
        Object v = body.get("version");
        if (!(v instanceof Number n) || n.intValue() < 0) {
            throw new ValidationException(Map.of("version", "The row's current version is required, so edits from another tab are not lost."));
        }
        return n.intValue();
    }

    /** @return column names, with the matching values appended to {@code values} */
    private List<String> validated(ResourceSpec s, Map<String, Object> body, List<Object> values) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<String> cols = new ArrayList<>();
        if (s.hasParent()) {
            Object p = body.get(s.parentApi());
            if (!(p instanceof Number n) || n.longValue() <= 0) {
                errors.put(s.parentApi(), "Choose which " + s.parentPath().replace('-', ' ') + " this belongs to.");
            } else {
                ResourceSpec parent = PortfolioResources.ALL.get(s.parentPath());
                if (jdbc.sql("SELECT count(*) FROM " + parent.table() + " WHERE id = ?").param(n.longValue()).query(Integer.class).single() == 0) {
                    errors.put(s.parentApi(), "That parent does not exist.");
                } else {
                    cols.add(s.parentColumn());
                    values.add(n.longValue());
                }
            }
        }
        for (Field f : s.fields()) {
            Object raw = body.get(f.api());
            try {
                Object v = convert(f, raw);
                cols.add(f.column());
                values.add(v);
            } catch (IllegalArgumentException e) {
                errors.put(f.api(), e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return cols;
    }

    private Object convert(Field f, Object raw) {
        boolean missing = raw == null || (raw instanceof String str && str.isBlank());
        switch (f.type()) {
            case INT -> {
                if (raw == null) {
                    return 0;
                }
                if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < 0 || n.longValue() > f.max()) {
                    throw new IllegalArgumentException("Must be a whole number from 0 to " + f.max() + ".");
                }
                return n.intValue();
            }
            case TEXT_LIST -> {
                if (raw == null) {
                    return "[]";
                }
                if (!(raw instanceof List<?> l) || l.size() > 100) {
                    throw new IllegalArgumentException("Must be a list of at most 100 entries.");
                }
                List<String> out = new ArrayList<>();
                for (Object o : l) {
                    if (!(o instanceof String t)) {
                        throw new IllegalArgumentException("Every entry must be text.");
                    }
                    String x = t.strip();
                    if (x.isEmpty()) {
                        continue;
                    }
                    if (x.length() > f.max() || hasControl(x, true)) {
                        throw new IllegalArgumentException("An entry is longer than " + f.max() + " characters or has control characters.");
                    }
                    out.add(x);
                }
                return toJson(out);
            }
            case JSON_LIST, JSON_OBJECT -> {
                if (raw == null) {
                    return f.type() == Type.JSON_LIST ? "[]" : "{}";
                }
                if (f.type() == Type.JSON_LIST ? !(raw instanceof List<?>) : !(raw instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(f.type() == Type.JSON_LIST ? "Must be a list." : "Must be an object.");
                }
                checkJson(raw, 0);
                String s = toJson(raw);
                if (s.length() > f.max()) {
                    throw new IllegalArgumentException("Too large (over " + f.max() / 1024 + " KiB).");
                }
                return s;
            }
            default -> {
                if (missing) {
                    if (f.required()) {
                        throw new IllegalArgumentException("Required.");
                    }
                    return f.nullable() ? null : "";
                }
                if (!(raw instanceof String str)) {
                    throw new IllegalArgumentException("Must be text.");
                }
                String v = str.strip();
                if (v.length() > f.max()) {
                    throw new IllegalArgumentException("Must be at most " + f.max() + " characters.");
                }
                if (hasControl(v, f.type() == Type.LONGTEXT)) {
                    throw new IllegalArgumentException("Contains control characters.");
                }
                switch (f.type()) {
                    case SLUG -> {
                        if (!SLUG.matcher(v).matches()) {
                            throw new IllegalArgumentException("Use lower-case letters, digits and - (starting with a letter or digit).");
                        }
                    }
                    case ENUM -> {
                        if (!f.allowed().contains(v)) {
                            throw new IllegalArgumentException("Must be one of: " + String.join(", ", f.allowed()) + ".");
                        }
                    }
                    case URL -> checkUrl(v);
                    default -> { }
                }
                return v;
            }
        }
    }

    static void checkUrl(String v) {
        try {
            URI u = new URI(v);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getUserInfo() != null) {
                throw new IllegalArgumentException("Must be a web address starting with http:// or https://.");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid web address.");
        }
    }

    /** Rejects dangerous link schemes anywhere inside nested JSON (the UI renders some of these values as links). */
    private static void checkJson(Object o, int depth) {
        if (depth > 6) {
            throw new IllegalArgumentException("Nested too deeply.");
        }
        if (o instanceof Map<?, ?> m) {
            if (m.size() > 200) {
                throw new IllegalArgumentException("Too many keys.");
            }
            m.forEach((k, v) -> {
                if (!(k instanceof String)) {
                    throw new IllegalArgumentException("Keys must be text.");
                }
                checkJson(v, depth + 1);
            });
        } else if (o instanceof List<?> l) {
            if (l.size() > 500) {
                throw new IllegalArgumentException("Too many entries.");
            }
            l.forEach(x -> checkJson(x, depth + 1));
        } else if (o instanceof String s) {
            if (UNSAFE_SCHEME.matcher(s).find()) {
                throw new IllegalArgumentException("Links must use http:// or https://.");
            }
            if (s.length() > 20_000 || hasControl(s, true)) {
                throw new IllegalArgumentException("A value is too long or has control characters.");
            }
        } else if (!(o == null || o instanceof Number || o instanceof Boolean)) {
            throw new IllegalArgumentException("Unsupported value.");
        }
    }

    private static boolean hasControl(String s, boolean allowNewlines) {
        return s.chars().anyMatch(c -> (c < 0x20 && !(allowNewlines && (c == '\n' || c == '\r' || c == '\t'))) || c == 0x7F);
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not be stored.");
        }
    }

    // ---------------------------------------------------------------- schema for the UI

    public List<Map<String, Object>> schema() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResourceSpec s : PortfolioResources.ALL.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", s.path());
            m.put("title", s.title());
            m.put("singleton", s.singleton());
            m.put("parent", s.hasParent() ? Map.of("api", s.parentApi(), "path", s.parentPath()) : null);
            m.put("skills", s.skillLinkTable() != null);
            m.put("tags", s.tagsOwnerType() != null);
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Field f : s.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("api", f.api());
                fm.put("label", f.label());
                fm.put("type", f.type().name().toLowerCase());
                fm.put("required", f.required());
                fm.put("max", f.max());
                fm.put("allowed", f.allowed());
                fm.put("help", f.help());
                fields.add(fm);
            }
            m.put("fields", fields);
            out.add(m);
        }
        return out;
    }

    Optional<String> uniqueColumnApi(ResourceSpec s, String sqliteMessage) {
        if (sqliteMessage == null) {
            return Optional.empty();
        }
        for (Field f : s.fields()) {
            if (sqliteMessage.contains(s.table() + "." + f.column())) {
                return Optional.of(f.api());
            }
        }
        return Optional.empty();
    }
}
