package com.homelabhub.docs.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class PortfolioCrudApiTest {

    private static final Path DIR = tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("docs.data-dir", () -> DIR.resolve("data").toString());
        r.add("docs.snapshot-dir", () -> DIR.resolve("snapshot").toString());
        r.add("docs.portfolio.snapshot-dir", () -> DIR.resolve("portfolio-snapshot").toString());
        r.add("spring.datasource.url", () -> {
            try {
                Files.createDirectories(DIR.resolve("data"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return "jdbc:sqlite:" + DIR.resolve("data/docs.db") + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL";
        });
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SnapshotPublisher publisher;

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("docs-portfolio-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final String BASE = "/api/admin/portfolio/";

    @BeforeEach
    void clean() {
        for (String t : PortfolioImporterTables.WIPE) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Object body) throws Exception {
        b = b.with(user("admin")).with(csrf());
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(b);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode create(String res, Map<String, Object> payload) throws Exception {
        return body(send(post(BASE + res), payload).andExpect(status().isCreated()));
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private long group() throws Exception {
        return create("skill-groups", m("category", "Languages", "icon", "Code", "sortOrder", 1)).get("id").asLong();
    }

    private long skill(String label) throws Exception {
        Long gid = jdbc.query("SELECT id FROM skill_groups LIMIT 1", (rs, n) -> rs.getLong(1)).stream().findFirst().orElse(null);
        if (gid == null) {
            gid = group();
        }
        return create("skills", m("groupId", gid, "label", label)).get("id").asLong();
    }

    private static Map<String, Object> experience(String slug) {
        return m("slug", slug, "role", "Data Engineer", "company", "Acme", "duration", "2024 - now", "summary", "Did things", "highlights", List.of("one", "two"));
    }

    // ---------------------------------------------------------------- access

    @Test
    void everythingNeedsALoginAndWritesNeedCsrf() throws Exception {
        mvc.perform(get(BASE + "experiences")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "experiences").with(user("admin")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/portfolio")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSchemaDescribesEveryResourceForTheGeneratedForms() throws Exception {
        JsonNode s = body(mvc.perform(get(BASE + "schema").with(user("admin"))).andExpect(status().isOk()));
        List<String> paths = new ArrayList<>();
        s.forEach(r -> paths.add(r.get("path").asText()));
        assertThat(paths).containsExactly("profile", "experiences", "projects", "project-case-studies", "project-deliverables", "education",
                "skill-groups", "skills", "homelab-devices", "homelab-containers", "homelab-services", "homelab-network");
        JsonNode exp = s.get(1);
        assertThat(exp.get("skills").asBoolean()).isTrue();
        assertThat(exp.get("tags").asBoolean()).isTrue();
        assertThat(exp.get("fields").get(0).get("api").asText()).isEqualTo("slug");
        assertThat(s.get(3).get("parent").get("api").asText()).isEqualTo("projectId");
    }

    // ---------------------------------------------------------------- CRUD round trips

    @Test
    void experienceCanBeCreatedReadUpdatedAndDeleted() throws Exception {
        JsonNode created = create("experiences", experience("acme"));
        long id = created.get("id").asLong();
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("highlights").get(1).asText()).isEqualTo("two");

        send(get(BASE + "experiences/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("acme"));

        Map<String, Object> edit = experience("acme");
        edit.put("role", "Lead");
        edit.put("version", 0);
        send(put(BASE + "experiences/" + id), edit).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("Lead")).andExpect(jsonPath("$.version").value(1));

        send(delete(BASE + "experiences/" + id), null).andExpect(status().isOk());
        send(get(BASE + "experiences/" + id), null).andExpect(status().isNotFound());
        send(delete(BASE + "experiences/" + id), null).andExpect(status().isNotFound());
    }

    @Test
    void theProfileIsASingletonCreatedOnFirstSaveThenVersioned() throws Exception {
        Map<String, Object> p = m("name", "Tabish", "title", "Data Engineer", "bio", List.of("Para 1", "Para 2"),
                "socials", m("github", "https://github.com/x", "githubLabel", "github.com/x"), "stats", List.of(m("value", "4+", "label", "Years", "sub", "x")));
        send(put(BASE + "profile"), p).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.version").value(0));
        send(put(BASE + "profile"), p).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.version").exists());
        p.put("version", 0);
        p.put("tagline", "Hello");
        send(put(BASE + "profile"), p).andExpect(status().isOk()).andExpect(jsonPath("$.tagline").value("Hello")).andExpect(jsonPath("$.version").value(1));
        send(post(BASE + "profile"), p).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theWholeContentTreeCanBeBuiltThroughTheApiAndAppearsInThePortfolioJson() throws Exception {
        long sk = skill("Python 3");
        JsonNode exp = create("experiences", experience("acme"));
        send(put(BASE + "experiences/" + exp.get("id").asLong() + "/skills"), m("skillIds", List.of(sk))).andExpect(status().isOk()).andExpect(jsonPath("$.skillIds[0]").value(sk));
        send(put(BASE + "experiences/" + exp.get("id").asLong() + "/tags"), m("tags", List.of("Binance API", "Binance API", " X "))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(2));

        JsonNode proj = create("projects", m("slug", "redshift", "kind", "flagship", "title", "Pipeline", "description", "desc", "repoUrl", "https://github.com/x/y"));
        long pid = proj.get("id").asLong();
        create("project-case-studies", m("projectId", pid, "client", "GE", "context", "ctx", "platform", List.of("Python 3"),
                "metrics", List.of(m("label", "Speed", "before", "days", "after", "hours", "emphasis", "accent"))));
        create("project-deliverables", m("projectId", pid, "key", "d1", "title", "Deliverable", "tag", "PL/pgSQL", "body", "Body text about deadlocks"));
        create("education", m("type", "Degree", "title", "M.Tech", "institution", "JMI", "date", "2019 - 2023", "url", "https://example.edu"));
        long dev = create("homelab-devices", m("key", "proxmox-host", "category", "proxmox", "name", "Proxmox", "hardware", List.of(m("component", "CPU", "spec", "Ryzen")))).get("id").asLong();
        long ct = create("homelab-containers", m("deviceId", dev, "key", "lxc-100", "name", "UbuntuCT", "status", "Active")).get("id").asLong();
        long sv = create("homelab-services", m("deviceId", dev, "serviceName", "Grafana", "hostPort", "3000")).get("id").asLong();
        create("homelab-network", m("deviceId", dev, "entityType", "vlan", "name", "VLAN 10", "spec", m("subnet", "192.168.10.0/24")));
        send(put(BASE + "homelab-services/" + sv + "/skills"), m("skillIds", List.of(sk))).andExpect(status().isOk());
        send(put(BASE + "homelab-containers/" + ct + "/skills"), m("skillIds", List.of(sk))).andExpect(status().isOk());
        send(put(BASE + "homelab-devices/" + dev + "/skills"), m("skillIds", List.of(sk))).andExpect(status().isOk());

        JsonNode p = body(mvc.perform(get("/api/portfolio").with(user("admin"))).andExpect(status().isOk()));
        assertThat(p.get("experience").get(0).get("skills").get(0).asText()).isEqualTo("Python 3");
        assertThat(p.get("experience").get(0).get("tags").toString()).contains("Binance API").contains("X");
        assertThat(p.get("experience").get(0).get("t").asText()).contains("data engineer").contains("python 3");
        JsonNode pr = p.get("projects").get(0);
        assertThat(pr.get("case").get("metrics").get(0).get("after").asText()).isEqualTo("hours");
        assertThat(pr.get("deliverables").get(0).get("title").asText()).isEqualTo("Deliverable");
        assertThat(pr.get("t").asText()).contains("deadlocks");
        assertThat(p.get("education").get(0).get("inst").asText()).isEqualTo("JMI");
        JsonNode h = p.get("homelab").get(0);
        assertThat(h.get("containers").get(0).get("skills").get(0).asText()).isEqualTo("Python 3");
        assertThat(h.get("services").get(0).get("port").asText()).isEqualTo("3000");
        assertThat(h.get("network").get(0).get("spec").get("subnet").asText()).isEqualTo("192.168.10.0/24");
        assertThat(p.get("skills").get(0).get("skills").get(0).get("n").asInt()).as("linked by 1 role, 1 device, 1 container, 1 service").isEqualTo(4);
    }

    // ---------------------------------------------------------------- validation

    @Test
    void requiredFieldsSlugsEnumsAndLengthsAreChecked() throws Exception {
        send(post(BASE + "experiences"), m()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.slug").exists()).andExpect(jsonPath("$.errors.role").value("Required."))
                .andExpect(jsonPath("$.errors.company").exists()).andExpect(jsonPath("$.errors.duration").exists()).andExpect(jsonPath("$.errors.summary").exists());
        for (String bad : new String[] {"Has Caps", "-lead", "a/b", "a b", "x".repeat(65), "../x"}) {
            Map<String, Object> e = experience(bad);
            send(post(BASE + "experiences"), e).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.slug").exists());
        }
        Map<String, Object> p = m("slug", "p", "kind", "medium", "title", "T", "description", "D");
        send(post(BASE + "projects"), p).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.kind").value(containsString("flagship")));
        Map<String, Object> longRole = experience("x");
        longRole.put("role", "r".repeat(201));
        send(post(BASE + "experiences"), longRole).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.role").exists());
    }

    @Test
    void linksMustBeWebAddressesAndDangerousSchemesAreRefusedEverywhere() throws Exception {
        for (String bad : new String[] {"javascript:alert(1)", "data:text/html,x", "ftp://x/y", "//evil.com", "https://user:pw@host/x", "not a url", "file:///etc/passwd"}) {
            Map<String, Object> p = m("slug", "p", "kind", "small", "title", "T", "description", "D", "repoUrl", bad);
            send(post(BASE + "projects"), p).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.repoUrl").exists());
        }
        Map<String, Object> profile = m("name", "N", "title", "T", "socials", m("github", "javascript:alert(1)"));
        send(put(BASE + "profile"), profile).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.socials").value(containsString("http")));
        Map<String, Object> nested = m("name", "N", "title", "T", "stats", List.of(m("value", "1", "label", " JavaScript:evil()")));
        send(put(BASE + "profile"), nested).andExpect(status().isUnprocessableEntity());
        Map<String, Object> ok = m("slug", "p", "kind", "small", "title", "T", "description", "D", "repoUrl", "https://github.com/x/y", "liveUrl", "");
        send(post(BASE + "projects"), ok).andExpect(status().isCreated()).andExpect(jsonPath("$.liveUrl").doesNotExist());
    }

    @Test
    void controlCharactersWrongTypesAndOversizedJsonAreRefused() throws Exception {
        Map<String, Object> e = experience("x");
        e.put("role", "bad\u0007role");
        send(post(BASE + "experiences"), e).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.role").exists());
        e = experience("x");
        e.put("role", 5);
        send(post(BASE + "experiences"), e).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.role").value("Must be text."));
        e = experience("x");
        e.put("highlights", "not a list");
        send(post(BASE + "experiences"), e).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.highlights").exists());
        e = experience("x");
        e.put("sortOrder", -1);
        send(post(BASE + "experiences"), e).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.sortOrder").exists());
        e = experience("x");
        e.put("sortOrder", 1.5);
        send(post(BASE + "experiences"), e).andExpect(status().isUnprocessableEntity());
        Map<String, Object> dev = m("key", "d", "category", "proxmox", "name", "N", "hardware", List.of(m("spec", "x".repeat(70_000))));
        send(post(BASE + "homelab-devices"), dev).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.hardware").exists());
        e = experience("x");
        e.put("highlights", java.util.Collections.nCopies(101, "h"));
        send(post(BASE + "experiences"), e).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void malformedJsonIsA400AndSqlLookalikesAreStoredAsPlainText() throws Exception {
        send(post(BASE + "experiences"), "{not json").andExpect(status().isBadRequest());
        Map<String, Object> e = experience("inj");
        e.put("role", "'; DROP TABLE skills; --");
        send(post(BASE + "experiences"), e).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("'; DROP TABLE skills; --"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skills", Integer.class)).isNotNull();
    }

    @Test
    void unknownResourcesAndInjectedResourceNamesAre404() throws Exception {
        for (String res : new String[] {"nope", "experiences;drop", "experiences%27--", "..", "sqlite_master"}) {
            // 404 from us, or 400 from Spring Security's URL firewall (which rejects ';' and friends before we ever see them)
            int code = send(get(BASE + res), null).andReturn().getResponse().getStatus();
            assertThat(code).as(res).isIn(400, 404);
        }
    }

    @Test
    void duplicateSlugsAndLabelsAreFieldErrorsNotServerErrors() throws Exception {
        create("experiences", experience("dup"));
        send(post(BASE + "experiences"), experience("dup")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.slug").value("That value is already used."));
        long g = group();
        create("skills", m("groupId", g, "label", "Airflow"));
        send(post(BASE + "skills"), m("groupId", g, "label", "airflow")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.label").exists());
    }

    @Test
    void aChildNeedsAnExistingParent() throws Exception {
        send(post(BASE + "project-deliverables"), m("projectId", 999, "key", "k", "title", "T", "tag", "t", "body", "b")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.projectId").value("That parent does not exist."));
        send(post(BASE + "project-deliverables"), m("key", "k", "title", "T", "tag", "t", "body", "b")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.projectId").exists());
        send(post(BASE + "skills"), m("groupId", "x", "label", "L")).andExpect(status().isUnprocessableEntity());
    }

    // ---------------------------------------------------------------- concurrency and cascades

    @Test
    void twoTabsEditingTheSameRowCannotSilentlyOverwriteEachOther() throws Exception {
        long id = create("experiences", experience("race")).get("id").asLong();
        Map<String, Object> tabA = experience("race");
        tabA.put("role", "From tab A");
        tabA.put("version", 0);
        Map<String, Object> tabB = experience("race");
        tabB.put("role", "From tab B");
        tabB.put("version", 0);
        send(put(BASE + "experiences/" + id), tabA).andExpect(status().isOk());
        send(put(BASE + "experiences/" + id), tabB).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.current.role").value("From tab A")).andExpect(jsonPath("$.current.version").value(1));
        assertThat(jdbc.queryForObject("SELECT role FROM experiences WHERE id = ?", String.class, id)).isEqualTo("From tab A");
        send(put(BASE + "experiences/" + id), experience("race")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.version").exists());
        send(put(BASE + "experiences/99999"), tabA).andExpect(status().isNotFound());
    }

    @Test
    void deletingAParentRemovesItsChildrenAndDeletingASkillUnlinksIt() throws Exception {
        JsonNode proj = create("projects", m("slug", "p", "kind", "flagship", "title", "T", "description", "D"));
        long pid = proj.get("id").asLong();
        create("project-deliverables", m("projectId", pid, "key", "k", "title", "T", "tag", "t", "body", "b"));
        long sk = skill("Docker");
        send(put(BASE + "projects/" + pid + "/skills"), m("skillIds", List.of(sk))).andExpect(status().isOk());
        send(put(BASE + "projects/" + pid + "/tags"), m("tags", List.of("free"))).andExpect(status().isOk());

        send(get(BASE + "skills/" + sk + "/usage"), null).andExpect(jsonPath("$.projects").value(1)).andExpect(jsonPath("$.roles").value(0));
        send(get(BASE + "projects/" + pid + "/usage"), null).andExpect(jsonPath("$.deliverables").value(1));

        send(delete(BASE + "skills/" + sk), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_skills", Integer.class)).isZero();
        send(delete(BASE + "projects/" + pid), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_deliverables", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM unlinked_tags", Integer.class)).as("free tags of a deleted project are removed too").isZero();
    }

    // ---------------------------------------------------------------- links and tags

    @Test
    void skillLinksAreReplacedNotAppendedAndBadInputIsRefused() throws Exception {
        long a = skill("A"), b = skill("B");
        long id = create("experiences", experience("links")).get("id").asLong();
        send(put(BASE + "experiences/" + id + "/skills"), m("skillIds", List.of(a, b))).andExpect(jsonPath("$.skillIds.length()").value(2));
        send(put(BASE + "experiences/" + id + "/skills"), m("skillIds", List.of(b, b))).andExpect(jsonPath("$.skillIds.length()").value(1)).andExpect(jsonPath("$.skillIds[0]").value(b));
        send(put(BASE + "experiences/" + id + "/skills"), m("skillIds", List.of(99999))).andExpect(status().isUnprocessableEntity());
        send(put(BASE + "experiences/" + id + "/skills"), m("skillIds", "x")).andExpect(status().isUnprocessableEntity());
        send(put(BASE + "experiences/" + id + "/skills"), m("skillIds", List.of("1"))).andExpect(status().isUnprocessableEntity());
        send(put(BASE + "experiences/" + id + "/skills"), m("skillIds", List.of())).andExpect(jsonPath("$.skillIds.length()").value(0));
        long edu = create("education", m("type", "Degree", "title", "T", "institution", "I", "date", "D")).get("id").asLong();
        send(put(BASE + "education/" + edu + "/skills"), m("skillIds", List.of(a))).andExpect(status().isNotFound());
        send(put(BASE + "experiences/99999/skills"), m("skillIds", List.of(a))).andExpect(status().isNotFound());
    }

    @Test
    void tagsAreValidated() throws Exception {
        long id = create("experiences", experience("tags")).get("id").asLong();
        send(put(BASE + "experiences/" + id + "/tags"), m("tags", List.of("x".repeat(81)))).andExpect(status().isUnprocessableEntity());
        send(put(BASE + "experiences/" + id + "/tags"), m("tags", List.of(""))).andExpect(status().isUnprocessableEntity());
        send(put(BASE + "experiences/" + id + "/tags"), m("tags", java.util.Collections.nCopies(51, "t"))).andExpect(status().isUnprocessableEntity());
        List<String> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fifty.add("t" + i);
        }
        send(put(BASE + "experiences/" + id + "/tags"), m("tags", fifty)).andExpect(status().isOk());
        send(put(BASE + "experiences/" + id + "/tags"), m("tags", List.of("a\u0001b"))).andExpect(status().isUnprocessableEntity());
        send(put(BASE + "experiences/" + id + "/tags"), m("tags", List.of(1))).andExpect(status().isUnprocessableEntity());
    }

    // ---------------------------------------------------------------- the public snapshot

    @Test
    void everyChangeRepublishesTheSnapshotAtomicallyAndTheHeaderSaysSo() throws Exception {
        send(post(BASE + "experiences"), experience("snap")).andExpect(status().isCreated()).andExpect(header().string("X-Public-Site", "ok"));
        JsonNode snap = json.readTree(Files.readString(publisher.file()));
        assertThat(snap.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(snap.get("data").get("experience").get(0).get("id").asText()).isEqualTo("e-snap");
        assertThat(java.nio.file.Files.getPosixFilePermissions(publisher.file()).toString()).contains("OTHERS_READ");
        try (var files = Files.list(publisher.file().getParent())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("snapshot.json");
        }
    }

    @Test
    void aFailingPublishKeepsTheEditReportsStaleAndHealsOnRetry() throws Exception {
        Path snapDir = publisher.file().getParent();
        Path aside = snapDir.resolveSibling("snapshot-aside");
        Files.move(snapDir, aside);
        Files.writeString(snapDir, "i am a file, not a directory"); // publishing must now fail
        try {
            send(post(BASE + "experiences"), experience("stale")).andExpect(status().isCreated()).andExpect(header().string("X-Public-Site", "stale"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM experiences WHERE slug = 'stale'", Integer.class)).as("the edit itself is saved").isEqualTo(1);
            send(get(BASE + "publish-status"), null).andExpect(jsonPath("$.upToDate").value(false)).andExpect(jsonPath("$.lastError").value(containsString("could not be updated")));

            Files.delete(snapDir);
            publisher.retryIfStale();
            send(get(BASE + "publish-status"), null).andExpect(jsonPath("$.upToDate").value(true));
            assertThat(Files.readString(publisher.file())).contains("e-stale");
        } finally {
            if (Files.isRegularFile(snapDir)) {
                Files.delete(snapDir);
            }
            if (!Files.exists(snapDir)) {
                Files.move(aside, snapDir);
            }
        }
    }

    @Test
    void republishRebuildsTheSnapshotOnDemand() throws Exception {
        create("experiences", experience("manual"));
        Files.delete(publisher.file());
        send(post(BASE + "republish"), null).andExpect(status().isOk()).andExpect(jsonPath("$.upToDate").value(true));
        assertThat(publisher.file()).exists();
    }

    /** Table order used to wipe between tests. */
    static final class PortfolioImporterTables {
        static final List<String> WIPE = List.of("unlinked_tags", "experience_skills", "project_skills", "device_skills", "container_skills", "service_skills",
                "skill_aliases", "project_deliverables", "project_case_studies", "homelab_network_entities", "homelab_services", "homelab_containers",
                "homelab_devices", "academic_items", "experiences", "projects", "skills", "skill_groups", "profile");
    }
}
