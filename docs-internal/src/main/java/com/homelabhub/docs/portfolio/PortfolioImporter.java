package com.homelabhub.docs.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-off import of the old Postgres portfolio (exported to JSON by tools/export-portfolio) into SQLite (plan 10.3).
 * All-or-nothing in one transaction, refuses to overwrite existing content unless told to, and reconciles row counts
 * afterwards. Free-text skills are resolved through the reviewed decisions file, never guessed.
 */
public class PortfolioImporter {

    public record Decision(String ownerType, String ownerSlug, String sourceText, String status, String proposed, String decision) {}

    public record Extras(Map<String, Map<String, List<String>>> homelabSkillLinks, Map<String, String> casePlatformAliases) {
        public static Extras none() {
            return new Extras(Map.of(), Map.of());
        }
    }

    public static final class ImportException extends RuntimeException {
        private final List<String> problems;

        public ImportException(String message, List<String> problems) {
            super(message);
            this.problems = problems;
        }

        public List<String> problems() {
            return problems;
        }
    }

    public record Report(Map<String, Integer> imported, Map<String, Integer> source, int newSkills, int newGroups, int skillLinks,
                         int unlinkedTags, List<String> warnings, boolean dryRun) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> WIPE_ORDER = List.of("unlinked_tags", "experience_skills", "project_skills", "device_skills", "container_skills",
            "service_skills", "skill_aliases", "project_deliverables", "project_case_studies", "homelab_network_entities", "homelab_services",
            "homelab_containers", "homelab_devices", "academic_items", "experiences", "projects", "skills", "skill_groups", "profile");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PortfolioImporter(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Report run(JsonNode export, List<Decision> decisions, Extras extras, boolean replace, boolean dryRun) {
        if (export == null || export.path("schemaVersion").asInt() != 1 || !export.path("tables").isObject()) {
            throw new ImportException("This is not a portfolio export (schemaVersion 1).", List.of());
        }
        return tx.execute(status -> {
            Run r = new Run(export.path("tables"), decisions, extras);
            Report report = r.execute(replace, dryRun);
            if (dryRun) {
                status.setRollbackOnly();
            }
            return report;
        });
    }

    // ---------------------------------------------------------------------------------------------------

    private final class Run {
        final JsonNode t;
        final Map<String, Decision> decisions = new HashMap<>();
        final Extras extras;
        final List<String> problems = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, Long> skillByLabel = new HashMap<>();   // lower-case label -> id
        final Map<String, Long> groupByName = new HashMap<>();
        final Map<String, Integer> imported = new LinkedHashMap<>();
        int newSkills, newGroups, links, tagsCreated;

        Run(JsonNode tables, List<Decision> ds, Extras extras) {
            this.t = tables;
            this.extras = extras == null ? Extras.none() : extras;
            for (Decision d : ds) {
                decisions.put(key(d.ownerType(), d.ownerSlug(), d.sourceText()), d);
            }
        }

        Report execute(boolean replace, boolean dryRun) {
            boolean has = jdbc.sql("SELECT (SELECT count(*) FROM profile) + (SELECT count(*) FROM experiences) + (SELECT count(*) FROM projects)").query(Integer.class).single() > 0;
            if (has && !replace) {
                throw new ImportException("The database already has portfolio content. Use --replace to overwrite it.", List.of());
            }
            if (replace) {
                WIPE_ORDER.forEach(tbl -> jdbc.sql("DELETE FROM " + tbl).update());
            }
            Map<Long, Long> groupIds = importGroups();
            Map<Long, Long> skillIds = importSkills(groupIds);
            applyNewSkillDecisions();
            importProfile();
            Map<Long, Long> experienceIds = importExperiences();
            Map<Long, Long> projectIds = importProjects();
            importCaseStudies(projectIds);
            importDeliverables(projectIds);
            importEducation();
            importHomelab();

            if (!problems.isEmpty()) {
                throw new ImportException("The import was stopped, nothing was saved: " + problems.size() + " problem(s).", problems);
            }
            Map<String, Integer> source = new LinkedHashMap<>();
            for (String table : imported.keySet()) {
                source.put(table, t.path(table).size());
            }
            reconcile(source);
            if (!problems.isEmpty()) {
                throw new ImportException("Row counts do not match, nothing was saved.", problems);
            }
            return new Report(imported, source, newSkills, newGroups, links, tagsCreated, warnings, dryRun);
        }

        // ---- skills

        Map<Long, Long> importGroups() {
            Map<Long, Long> map = new HashMap<>();
            int n = 0;
            for (JsonNode g : t.path("skill_groups")) {
                long id = insert("INSERT INTO skill_groups (category, icon, sort_order) VALUES (?,?,?)", g.path("category").asText(), g.path("icon").asText(""), g.path("sort_order").asInt());
                map.put(g.path("id").asLong(), id);
                groupByName.put(g.path("category").asText().toLowerCase(Locale.ROOT), id);
                n++;
            }
            imported.put("skill_groups", n);
            return map;
        }

        Map<Long, Long> importSkills(Map<Long, Long> groupIds) {
            Map<Long, Long> map = new HashMap<>();
            int n = 0;
            for (JsonNode s : t.path("skills")) {
                Long gid = groupIds.get(s.path("group_id").asLong());
                if (gid == null) {
                    problems.add("Skill '" + s.path("label").asText() + "' belongs to an unknown group.");
                    continue;
                }
                long id = insert("INSERT INTO skills (group_id, label, sort_order) VALUES (?,?,?)", gid, s.path("label").asText(), s.path("sort_order").asInt());
                map.put(s.path("id").asLong(), id);
                skillByLabel.put(s.path("label").asText().toLowerCase(Locale.ROOT), id);
                n++;
            }
            imported.put("skills", n);
            return map;
        }

        /** decision `new:Label|Group` creates the skill (and the group when it is new). */
        void applyNewSkillDecisions() {
            for (Decision d : decisions.values()) {
                if (d.decision() != null && d.decision().startsWith("new:")) {
                    String spec = d.decision().substring(4);
                    int bar = spec.lastIndexOf('|');
                    if (bar <= 0) {
                        problems.add("Decision for '" + d.sourceText() + "' is malformed: " + d.decision());
                        continue;
                    }
                    ensureSkill(spec.substring(0, bar).strip(), spec.substring(bar + 1).strip());
                }
            }
        }

        long ensureSkill(String label, String group) {
            Long existing = skillByLabel.get(label.toLowerCase(Locale.ROOT));
            if (existing != null) {
                return existing;
            }
            Long gid = groupByName.get(group.toLowerCase(Locale.ROOT));
            if (gid == null) {
                int order = jdbc.sql("SELECT coalesce(max(sort_order), 0) + 1 FROM skill_groups").query(Integer.class).single();
                gid = insert("INSERT INTO skill_groups (category, icon, sort_order) VALUES (?,?,?)", group, "Tag", order);
                groupByName.put(group.toLowerCase(Locale.ROOT), gid);
                newGroups++;
            }
            int order = jdbc.sql("SELECT coalesce(max(sort_order), 0) + 1 FROM skills WHERE group_id = ?").param(gid).query(Integer.class).single();
            long id = insert("INSERT INTO skills (group_id, label, sort_order) VALUES (?,?,?)", gid, label, order);
            skillByLabel.put(label.toLowerCase(Locale.ROOT), id);
            newSkills++;
            return id;
        }

        /** Resolves one free-text string from an experience or project to skill ids (or a free tag). */
        void resolve(String ownerType, String slug, String text, long ownerId, String linkTable, String ownerCol) {
            Decision d = decisions.get(key(ownerType, slug, text));
            if (d == null) {
                Long exact = skillByLabel.get(text.strip().toLowerCase(Locale.ROOT));
                if (exact != null) {
                    link(linkTable, ownerCol, ownerId, exact);
                } else {
                    problems.add(ownerType + " '" + slug + "': '" + text + "' has no decision and matches no skill.");
                }
                return;
            }
            String dec = d.decision() == null ? "" : d.decision().strip();
            if (dec.equals("accept")) {
                String proposed = d.proposed() == null ? "" : d.proposed();
                if (proposed.isBlank()) {
                    problems.add(ownerType + " '" + slug + "': '" + text + "' was accepted but has no proposed skill.");
                }
                for (String label : proposed.split(";")) {
                    if (!label.isBlank()) {
                        linkLabel(ownerType, slug, text, label.strip(), ownerId, linkTable, ownerCol);
                    }
                }
            } else if (dec.startsWith("map:")) {
                for (String label : dec.substring(4).split(";")) {
                    linkLabel(ownerType, slug, text, label.strip(), ownerId, linkTable, ownerCol);
                }
            } else if (dec.startsWith("new:")) {
                String spec = dec.substring(4);
                int bar = spec.lastIndexOf('|');
                if (bar > 0) {
                    link(linkTable, ownerCol, ownerId, ensureSkill(spec.substring(0, bar).strip(), spec.substring(bar + 1).strip()));
                }
            } else if (dec.equals("free")) {
                jdbc.sql("INSERT OR IGNORE INTO unlinked_tags (owner_type, owner_id, text) VALUES (?,?,?)").params(ownerType, ownerId, text.strip()).update();
                tagsCreated++;
            } else {
                problems.add(ownerType + " '" + slug + "': '" + text + "' needs a decision (accept, map:, new:, free).");
            }
        }

        void linkLabel(String ownerType, String slug, String text, String label, long ownerId, String linkTable, String ownerCol) {
            Long id = skillByLabel.get(label.toLowerCase(Locale.ROOT));
            if (id == null) {
                problems.add(ownerType + " '" + slug + "': '" + text + "' maps to '" + label + "', which is not a skill.");
            } else {
                link(linkTable, ownerCol, ownerId, id);
            }
        }

        void link(String table, String ownerCol, long ownerId, long skillId) {
            int n = jdbc.sql("INSERT OR IGNORE INTO " + table + " (" + ownerCol + ", skill_id) VALUES (?,?)").params(ownerId, skillId).update();
            links += n;
        }

        // ---- content

        void importProfile() {
            JsonNode rows = t.path("profile");
            if (rows.size() != 1) {
                problems.add("Expected exactly one profile row, found " + rows.size() + ".");
                return;
            }
            JsonNode p = rows.get(0);
            jdbc.sql("INSERT INTO profile (id, name, title, tagline, location, email, phone, brand, status, summary, bio, socials, stats) VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?)")
                    .params(p.path("name").asText(), p.path("title").asText(), p.path("tagline").asText(""), p.path("location").asText(""), p.path("email").asText(""),
                            p.path("phone").asText(""), p.path("brand").asText(""), p.path("status").asText(""), p.path("summary").asText(""),
                            json(p.path("bio"), "[]"), json(p.path("socials"), "{}"), json(p.path("stats"), "[]")).update();
            imported.put("profile", 1);
        }

        Map<Long, Long> importExperiences() {
            Map<Long, Long> map = new HashMap<>();
            for (JsonNode e : t.path("experiences")) {
                long id = insert("INSERT INTO experiences (slug, role, company, client, duration_label, location, summary, highlights, sort_order) VALUES (?,?,?,?,?,?,?,?,?)",
                        e.path("slug").asText(), e.path("role").asText(), e.path("company").asText(), text(e, "client"), e.path("duration").asText(), text(e, "location"),
                        e.path("summary").asText(""), json(e.path("highlights"), "[]"), e.path("sort_order").asInt());
                map.put(e.path("id").asLong(), id);
                for (JsonNode s : e.path("skills")) {
                    resolve("experience", e.path("slug").asText(), s.asText(), id, "experience_skills", "experience_id");
                }
            }
            imported.put("experiences", map.size());
            return map;
        }

        Map<Long, Long> importProjects() {
            Map<Long, Long> map = new HashMap<>();
            for (JsonNode p : t.path("projects")) {
                long id = insert("INSERT INTO projects (slug, kind, title, description, repo_url, live_url, type, sort_order) VALUES (?,?,?,?,?,?,?,?)",
                        p.path("slug").asText(), p.path("kind").asText(), p.path("title").asText(), p.path("description").asText(""), text(p, "repo_url"), text(p, "live_url"),
                        text(p, "type"), p.path("sort_order").asInt());
                map.put(p.path("id").asLong(), id);
                for (JsonNode s : p.path("tags")) {
                    resolve("project", p.path("slug").asText(), s.asText(), id, "project_skills", "project_id");
                }
            }
            imported.put("projects", map.size());
            return map;
        }

        void importCaseStudies(Map<Long, Long> projectIds) {
            int n = 0;
            for (JsonNode c : t.path("project_case_studies")) {
                Long pid = projectIds.get(c.path("project_id").asLong());
                if (pid == null) {
                    problems.add("A case study belongs to an unknown project.");
                    continue;
                }
                insert("INSERT INTO project_case_studies (project_id, client, role, duration, context, platform, metrics, layers, tech_stack) VALUES (?,?,?,?,?,?,?,?,?)",
                        pid, text(c, "client"), text(c, "role"), text(c, "duration"), text(c, "context"), json(c.path("platform"), "[]"), json(c.path("metrics"), "[]"),
                        json(c.path("layers"), "[]"), json(c.path("tech_stack"), "[]"));
                n++;
                // The case study lists platform skills: link the project to them (the design mockup showed this).
                for (JsonNode plat : c.path("platform")) {
                    String label = extras.casePlatformAliases().getOrDefault(plat.asText(), plat.asText());
                    Long sid = skillByLabel.get(label.toLowerCase(Locale.ROOT));
                    if (sid == null) {
                        warnings.add("Case study platform '" + plat.asText() + "' matches no skill and was not linked.");
                    } else {
                        link("project_skills", "project_id", pid, sid);
                    }
                }
            }
            imported.put("project_case_studies", n);
        }

        void importDeliverables(Map<Long, Long> projectIds) {
            int n = 0;
            for (JsonNode d : t.path("project_deliverables")) {
                Long pid = projectIds.get(d.path("project_id").asLong());
                if (pid == null) {
                    problems.add("A deliverable belongs to an unknown project.");
                    continue;
                }
                insert("INSERT INTO project_deliverables (project_id, deliverable_key, title, tag, body, sort_order) VALUES (?,?,?,?,?,?)",
                        pid, d.path("deliverable_key").asText(), d.path("title").asText(), d.path("tag").asText(""), d.path("body").asText(""), d.path("sort_order").asInt());
                n++;
            }
            imported.put("project_deliverables", n);
        }

        void importEducation() {
            int n = 0;
            for (JsonNode a : t.path("academic_items")) {
                insert("INSERT INTO academic_items (type, title, institution, date_label, description, url, sort_order) VALUES (?,?,?,?,?,?,?)",
                        a.path("type").asText(), a.path("title").asText(), a.path("institution").asText(""), a.path("date").asText(""), text(a, "description"), text(a, "url"),
                        a.path("sort_order").asInt());
                n++;
            }
            imported.put("academic_items", n);
        }

        void importHomelab() {
            Map<Long, Long> devices = new HashMap<>();
            for (JsonNode d : t.path("homelab_devices")) {
                long id = insert("INSERT INTO homelab_devices (device_key, category, name, summary, ip_address, status, hardware, extra, sort_order) VALUES (?,?,?,?,?,?,?,?,?)",
                        d.path("device_key").asText(), d.path("category").asText(), d.path("name").asText(), text(d, "summary"), text(d, "ip_address"), text(d, "status"),
                        json(d.path("hardware"), "[]"), json(d.path("extra"), "{}"), d.path("sort_order").asInt());
                devices.put(d.path("id").asLong(), id);
                linkExtras("devices", d.path("device_key").asText(), id, "device_skills", "device_id");
            }
            imported.put("homelab_devices", devices.size());

            int containers = 0;
            for (JsonNode c : t.path("homelab_containers")) {
                Long did = devices.get(c.path("device_id").asLong());
                if (did == null) {
                    problems.add("Container '" + c.path("name").asText() + "' belongs to an unknown device.");
                    continue;
                }
                long id = insert("INSERT INTO homelab_containers (container_key, device_id, name, ip_address, purpose, resources, status, services, sort_order) VALUES (?,?,?,?,?,?,?,?,?)",
                        c.path("container_key").asText(), did, c.path("name").asText(), text(c, "ip_address"), text(c, "purpose"), text(c, "resources"),
                        c.path("status").asText("Active"), json(c.path("services"), "[]"), c.path("sort_order").asInt());
                containers++;
                linkExtras("containers", c.path("container_key").asText(), id, "container_skills", "container_id");
            }
            imported.put("homelab_containers", containers);

            int services = 0;
            for (JsonNode s : t.path("homelab_services")) {
                Long did = devices.get(s.path("device_id").asLong());
                if (did == null) {
                    problems.add("Service '" + s.path("service_name").asText() + "' belongs to an unknown device.");
                    continue;
                }
                long id = insert("INSERT INTO homelab_services (device_id, service_name, container_name, host_port, function, sort_order) VALUES (?,?,?,?,?,?)",
                        did, s.path("service_name").asText(), text(s, "container_name"), text(s, "host_port"), text(s, "function"), s.path("sort_order").asInt());
                services++;
                linkExtras("services", s.path("service_name").asText(), id, "service_skills", "service_id");
            }
            imported.put("homelab_services", services);

            int nets = 0;
            for (JsonNode n : t.path("homelab_network_entities")) {
                Long did = devices.get(n.path("device_id").asLong());
                if (did == null) {
                    problems.add("Network entry '" + n.path("name").asText() + "' belongs to an unknown device.");
                    continue;
                }
                insert("INSERT INTO homelab_network_entities (device_id, entity_type, name, spec, sort_order) VALUES (?,?,?,?,?)",
                        did, n.path("entity_type").asText(), n.path("name").asText(), json(n.path("spec"), "{}"), n.path("sort_order").asInt());
                nets++;
            }
            imported.put("homelab_network_entities", nets);
        }

        void linkExtras(String kind, String key, long ownerId, String table, String ownerCol) {
            for (String label : extras.homelabSkillLinks().getOrDefault(kind, Map.of()).getOrDefault(key, List.of())) {
                Long sid = skillByLabel.get(label.toLowerCase(Locale.ROOT));
                if (sid == null) {
                    warnings.add("Homelab " + kind + " '" + key + "' is mapped to unknown skill '" + label + "'.");
                } else {
                    link(table, ownerCol, ownerId, sid);
                }
            }
        }

        void reconcile(Map<String, Integer> source) {
            for (var e : source.entrySet()) {
                int expected = e.getValue();
                Integer actual = jdbc.sql("SELECT count(*) FROM " + e.getKey()).query(Integer.class).single();
                if (e.getKey().equals("skills")) {
                    expected += newSkills;
                } else if (e.getKey().equals("skill_groups")) {
                    expected += newGroups;
                }
                if (actual != expected) {
                    problems.add(e.getKey() + ": expected " + expected + " rows but found " + actual + ".");
                }
            }
        }

        long insert(String sql, Object... params) {
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            jdbc.sql(sql).params(params).update(kh);
            return kh.getKey().longValue();
        }
    }

    // ---------------------------------------------------------------------------------------------------

    static String key(String type, String slug, String text) {
        return type + "\u0000" + slug + "\u0000" + text;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String json(JsonNode n, String dflt) {
        try {
            return n == null || n.isMissingNode() || n.isNull() ? dflt : JSON.writeValueAsString(n);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return dflt;
        }
    }

    /** Reads the reviewed CSV (owner_type, owner_slug, source_text, status, proposed_skills, candidates, decision). */
    public static List<Decision> parseDecisions(String csv) {
        List<Decision> out = new ArrayList<>();
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            return out;
        }
        List<String> header = rows.get(0);
        int oi = header.indexOf("owner_type"), si = header.indexOf("owner_slug"), ti = header.indexOf("source_text"), sti = header.indexOf("status"),
                pi = header.indexOf("proposed_skills"), di = header.indexOf("decision");
        if (oi < 0 || si < 0 || ti < 0 || di < 0) {
            throw new ImportException("The decisions file needs the columns owner_type, owner_slug, source_text and decision.", List.of());
        }
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() <= di) {
                continue;
            }
            out.add(new Decision(r.get(oi), r.get(si), r.get(ti), sti < 0 ? "" : r.get(sti), pi < 0 ? "" : r.get(pi), r.get(di)));
        }
        return out;
    }

    /** RFC 4180 CSV with quoted fields, embedded commas, doubled quotes and newlines. */
    static List<List<String>> parseCsv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(f.toString());
                f.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Every portfolio table, parents before children (the order rows must be inserted in). */
    static List<String> tablesInInsertOrder() {
        var l = new java.util.ArrayList<>(WIPE_ORDER);
        java.util.Collections.reverse(l);
        return List.copyOf(l);
    }

    /** Children before parents (the order rows must be deleted in). */
    static List<String> tablesInWipeOrder() {
        return WIPE_ORDER;
    }

    static Set<String> tableNames() {
        return Set.copyOf(WIPE_ORDER);
    }
}
