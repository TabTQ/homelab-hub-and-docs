package com.homelabhub.docs.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Builds the whole portfolio as one JSON document: the shape both the public snapshot and the internal editor page use
 * (the same shape the design mockups were built against). Every entity also carries a lower-cased search text `t`, so
 * the browser's filter is a plain substring test.
 */
@Service
public class PortfolioReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public PortfolioReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasProfile() {
        return jdbc.sql("SELECT count(*) FROM profile").query(Integer.class).single() > 0;
    }

    public Map<String, Object> read() {
        Map<Long, String> skillLabel = new HashMap<>();
        jdbc.sql("SELECT id, label FROM skills").query((rs, n) -> {
            skillLabel.put(rs.getLong(1), rs.getString(2));
            return null;
        }).list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", profile());
        out.put("experience", experiences(skillLabel));
        out.put("projects", projects(skillLabel));
        out.put("education", education());
        List<Map<String, Object>> homelab = homelab(skillLabel);
        out.put("homelab", homelab);
        out.put("skills", skillGroups(skillLabel, out, homelab));
        return out;
    }

    // ---------------------------------------------------------------- pieces

    private Map<String, Object> profile() {
        Optional<Map<String, Object>> row = jdbc.sql("SELECT * FROM profile WHERE id = 1").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String c : List.of("name", "title", "tagline", "location", "status", "brand", "summary", "email", "phone")) {
                m.put(c, rs.getString(c));
            }
            m.put("bio", jsonList(rs.getString("bio")));
            m.put("socials", jsonMap(rs.getString("socials")));
            m.put("stats", jsonList(rs.getString("stats")));
            m.put("skills", List.of());
            m.put("t", text(m.get("name"), m.get("title"), m.get("tagline"), m.get("location"), m.get("brand"), m.get("summary"), m.get("bio")));
            return m;
        }).optional();
        return row.orElseGet(() -> {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("name", "");
            empty.put("title", "");
            empty.put("bio", List.of());
            empty.put("socials", Map.of());
            empty.put("stats", List.of());
            empty.put("skills", List.of());
            empty.put("t", "");
            return empty;
        });
    }

    private List<Map<String, Object>> experiences(Map<Long, String> skillLabel) {
        Map<Long, List<String>> links = links("SELECT experience_id, skill_id FROM experience_skills", skillLabel);
        Map<Long, List<String>> tags = tags("experience");
        return jdbc.sql("SELECT * FROM experiences ORDER BY sort_order, id").query((rs, n) -> {
            long id = rs.getLong("id");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "e-" + rs.getString("slug"));
            m.put("dbId", id);
            m.put("role", rs.getString("role"));
            m.put("company", rs.getString("company"));
            m.put("client", rs.getString("client"));
            m.put("duration", rs.getString("duration_label"));
            m.put("location", rs.getString("location"));
            m.put("summary", rs.getString("summary"));
            List<Object> hl = jsonList(rs.getString("highlights"));
            m.put("highlights", hl);
            List<String> sk = links.getOrDefault(id, List.of());
            m.put("skills", sk);
            m.put("tags", tags.getOrDefault(id, List.of()));
            m.put("t", text(m.get("role"), m.get("company"), m.get("client"), m.get("summary"), hl, sk, m.get("tags")));
            return m;
        }).list();
    }

    private List<Map<String, Object>> projects(Map<Long, String> skillLabel) {
        Map<Long, List<String>> links = links("SELECT project_id, skill_id FROM project_skills", skillLabel);
        Map<Long, List<String>> tags = tags("project");
        Map<Long, Map<String, Object>> cases = new HashMap<>();
        jdbc.sql("SELECT * FROM project_case_studies").query((rs, n) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("client", rs.getString("client"));
            c.put("role", rs.getString("role"));
            c.put("duration", rs.getString("duration"));
            c.put("context", rs.getString("context"));
            c.put("metrics", jsonList(rs.getString("metrics")));
            c.put("platform", jsonList(rs.getString("platform")));
            cases.put(rs.getLong("project_id"), c);
            return null;
        }).list();
        Map<Long, List<Map<String, Object>>> dlv = new HashMap<>();
        jdbc.sql("SELECT * FROM project_deliverables ORDER BY sort_order, id").query((rs, n) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("key", rs.getString("deliverable_key"));
            d.put("title", rs.getString("title"));
            d.put("tag", rs.getString("tag"));
            d.put("body", rs.getString("body"));
            dlv.computeIfAbsent(rs.getLong("project_id"), k -> new ArrayList<>()).add(d);
            return null;
        }).list();
        return jdbc.sql("SELECT * FROM projects ORDER BY sort_order, id").query((rs, n) -> {
            long id = rs.getLong("id");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "p-" + rs.getString("slug"));
            m.put("dbId", id);
            m.put("kind", rs.getString("kind"));
            m.put("title", rs.getString("title"));
            m.put("description", rs.getString("description"));
            m.put("repo", rs.getString("repo_url"));
            m.put("live", rs.getString("live_url"));
            m.put("type", rs.getString("type"));
            List<String> sk = links.getOrDefault(id, List.of());
            m.put("skills", sk);
            m.put("tags", tags.getOrDefault(id, List.of()));
            List<Object> parts = new ArrayList<>(List.of(m.get("title"), m.get("description"), m.get("type") == null ? "" : m.get("type"), sk, m.get("tags")));
            if (cases.containsKey(id)) {
                m.put("case", cases.get(id));
                parts.add(cases.get(id).get("context"));
            }
            if (dlv.containsKey(id)) {
                m.put("deliverables", dlv.get(id));
                dlv.get(id).forEach(d -> parts.add(List.of(d.get("title"), d.get("tag"), d.get("body"))));
            }
            m.put("t", text(parts.toArray()));
            return m;
        }).list();
    }

    private List<Map<String, Object>> education() {
        return jdbc.sql("SELECT * FROM academic_items ORDER BY sort_order, id").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "a-" + rs.getLong("id"));
            m.put("dbId", rs.getLong("id"));
            m.put("type", rs.getString("type"));
            m.put("title", rs.getString("title"));
            m.put("inst", rs.getString("institution"));
            m.put("date", rs.getString("date_label"));
            m.put("desc", rs.getString("description"));
            m.put("url", rs.getString("url"));
            m.put("skills", List.of());
            m.put("t", text(m.get("title"), m.get("inst"), m.get("desc"), m.get("type")));
            return m;
        }).list();
    }

    private List<Map<String, Object>> homelab(Map<Long, String> skillLabel) {
        Map<Long, List<String>> devSk = links("SELECT device_id, skill_id FROM device_skills", skillLabel);
        Map<Long, List<String>> ctSk = links("SELECT container_id, skill_id FROM container_skills", skillLabel);
        Map<Long, List<String>> svSk = links("SELECT service_id, skill_id FROM service_skills", skillLabel);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : jdbc.sql("SELECT * FROM homelab_devices ORDER BY sort_order, id").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dbId", rs.getLong("id"));
            m.put("key", rs.getString("device_key"));
            m.put("id", "h-" + rs.getString("device_key"));
            m.put("cat", rs.getString("category"));
            m.put("name", rs.getString("name"));
            m.put("ip", rs.getString("ip_address"));
            m.put("summary", rs.getString("summary"));
            m.put("url", deviceUrl(jsonMap(rs.getString("extra"))));
            m.put("hw", jsonList(rs.getString("hardware")));
            return m;
        }).list()) {
            long id = (Long) d.get("dbId");
            List<String> sk = devSk.getOrDefault(id, List.of());
            d.put("skills", sk);
            List<Map<String, Object>> cts = jdbc.sql("SELECT * FROM homelab_containers WHERE device_id = ? ORDER BY sort_order, id").param(id).query((rs, n) -> {
                Map<String, Object> c = new LinkedHashMap<>();
                long cid = rs.getLong("id");
                c.put("dbId", cid);
                c.put("id", rs.getString("container_key"));
                c.put("name", rs.getString("name"));
                c.put("ip", rs.getString("ip_address"));
                c.put("purpose", rs.getString("purpose"));
                c.put("res", rs.getString("resources"));
                c.put("status", rs.getString("status"));
                c.put("skills", ctSk.getOrDefault(cid, List.of()));
                c.put("t", text(c.get("name"), c.get("purpose"), c.get("ip"), c.get("skills")));
                return c;
            }).list();
            List<Map<String, Object>> svs = jdbc.sql("SELECT * FROM homelab_services WHERE device_id = ? ORDER BY sort_order, id").param(id).query((rs, n) -> {
                Map<String, Object> s = new LinkedHashMap<>();
                long sid = rs.getLong("id");
                s.put("dbId", sid);
                s.put("id", "s-" + rs.getString("service_name").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
                s.put("name", rs.getString("service_name"));
                s.put("ct", rs.getString("container_name"));
                s.put("port", rs.getString("host_port"));
                s.put("fn", rs.getString("function"));
                s.put("skills", svSk.getOrDefault(sid, List.of()));
                s.put("t", text(s.get("name"), s.get("fn"), s.get("ct"), s.get("skills")));
                return s;
            }).list();
            List<Map<String, Object>> nets = jdbc.sql("SELECT * FROM homelab_network_entities WHERE device_id = ? ORDER BY sort_order, id").param(id).query((rs, n) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("dbId", rs.getLong("id"));
                e.put("id", "n-" + rs.getLong("id"));
                e.put("type", rs.getString("entity_type"));
                e.put("name", rs.getString("name"));
                e.put("spec", jsonMap(rs.getString("spec")));
                e.put("skills", List.of());
                e.put("t", text(e.get("name"), rs.getString("spec")));
                return e;
            }).list();
            d.put("containers", cts);
            d.put("services", svs);
            d.put("network", nets);
            d.put("t", text(d.get("name"), d.get("summary"), d.get("hw"), sk));
            out.add(d);
        }
        return out;
    }

    private List<Map<String, Object>> skillGroups(Map<Long, String> skillLabel, Map<String, Object> doc, List<Map<String, Object>> homelab) {
        Map<String, Integer> counts = new HashMap<>();
        java.util.function.Consumer<Object> bump = o -> {
            if (o instanceof List<?> l) {
                l.forEach(x -> counts.merge(String.valueOf(x), 1, Integer::sum));
            }
        };
        ((List<Map<String, Object>>) doc.get("experience")).forEach(e -> bump.accept(e.get("skills")));
        ((List<Map<String, Object>>) doc.get("projects")).forEach(e -> bump.accept(e.get("skills")));
        for (Map<String, Object> d : homelab) {
            bump.accept(d.get("skills"));
            ((List<Map<String, Object>>) d.get("containers")).forEach(c -> bump.accept(c.get("skills")));
            ((List<Map<String, Object>>) d.get("services")).forEach(s -> bump.accept(s.get("skills")));
        }
        Map<Long, Map<String, Object>> groups = new LinkedHashMap<>();
        jdbc.sql("SELECT id, category, icon FROM skill_groups ORDER BY sort_order, id").query((rs, n) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", rs.getLong("id"));
            g.put("name", rs.getString("category"));
            g.put("icon", rs.getString("icon"));
            g.put("skills", new ArrayList<Map<String, Object>>());
            groups.put(rs.getLong("id"), g);
            return null;
        }).list();
        jdbc.sql("SELECT id, group_id, label FROM skills ORDER BY sort_order, id").query((rs, n) -> {
            Map<String, Object> g = groups.get(rs.getLong("group_id"));
            if (g != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", rs.getLong("id"));
                s.put("l", rs.getString("label"));
                s.put("n", counts.getOrDefault(rs.getString("label"), 0));
                ((List<Map<String, Object>>) g.get("skills")).add(s);
            }
            return null;
        }).list();
        return new ArrayList<>(groups.values());
    }

    // ---------------------------------------------------------------- helpers

    private Map<Long, List<String>> links(String sql, Map<Long, String> skillLabel) {
        Map<Long, List<String>> m = new HashMap<>();
        jdbc.sql(sql + " ORDER BY 1, 2").query((rs, n) -> {
            String label = skillLabel.get(rs.getLong(2));
            if (label != null) {
                m.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(label);
            }
            return null;
        }).list();
        return m;
    }

    private Map<Long, List<String>> tags(String ownerType) {
        Map<Long, List<String>> m = new HashMap<>();
        jdbc.sql("SELECT owner_id, text FROM unlinked_tags WHERE owner_type = ? ORDER BY id").param(ownerType).query((rs, n) -> {
            m.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
            return null;
        }).list();
        return m;
    }

    static String text(Object... parts) {
        StringBuilder sb = new StringBuilder();
        append(sb, parts);
        return sb.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static void append(StringBuilder sb, Object o) {
        if (o == null) {
            return;
        }
        if (o instanceof Object[] arr) {
            for (Object x : arr) {
                append(sb, x);
            }
        } else if (o instanceof Iterable<?> it) {
            it.forEach(x -> append(sb, x));
        } else if (o instanceof Map<?, ?> m) {
            m.values().forEach(x -> append(sb, x));
        } else {
            sb.append(o).append(' ');
        }
    }

    static List<Object> jsonList(String s) {
        try {
            return s == null ? List.of() : JSON.readValue(s, new TypeReference<List<Object>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** The public page a homelab device links to: extra.url, and only when it is an http(s) address. */
    static String deviceUrl(Map<String, Object> extra) {
        return extra.get("url") instanceof String u && (u.startsWith("https://") || u.startsWith("http://")) ? u : null;
    }

    static Map<String, Object> jsonMap(String s) {
        try {
            return s == null ? Map.of() : JSON.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public static JsonNode toTree(Object o) {
        return JSON.valueToTree(o);
    }
}
