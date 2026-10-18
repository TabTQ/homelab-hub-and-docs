package com.homelabhub.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifies plan assumption A4: Flyway + sqlite-jdbc + Hikari behave, and pragmas really apply per connection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchemaAndPragmasTest {

    private static final Path DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("hub-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DIR.resolve("hub.db")
                + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
    }

    @AfterAll
    static void cleanup() throws IOException {
        try (var s = Files.walk(DIR)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @Test
    void migrationCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", String.class);
        assertThat(tables).contains("profile", "experiences", "projects", "skills", "experience_skills",
                "project_skills", "unlinked_tags", "homelab_devices", "audit_log", "config_versions", "kv",
                "flyway_schema_history");
        assertThat(tables).doesNotContain("diagrams", "admin_users");
    }

    @Test
    void walModeIsActive() {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
    }

    @Test
    void foreignKeysAreEnforcedOnEveryPooledConnection() {
        // Several calls so different pooled connections are exercised.
        for (int i = 0; i < 8; i++) {
            assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        }
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO skills (group_id, label) VALUES (9999, 'orphan')"))
                .hasMessageContaining("FOREIGN KEY");
    }

    @Test
    void invalidJsonIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO profile (id, name, title, bio) VALUES (1, 'n', 't', 'not json')"))
                .hasMessageContaining("CHECK");
    }

    @Test
    void profileIsSingleton() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO profile (id, name, title) VALUES (2, 'n', 't')"))
                .hasMessageContaining("CHECK");
    }

    @Test
    void cascadeDeleteRemovesLinks() {
        jdbc.update("INSERT INTO skill_groups (id, category, icon) VALUES (500, 'g', 'i')");
        jdbc.update("INSERT INTO skills (id, group_id, label) VALUES (500, 500, 'Cascade Skill')");
        jdbc.update("INSERT INTO experiences (id, slug, role, company, duration_label, summary)"
                + " VALUES (500, 'cascade-x', 'r', 'c', 'd', 's')");
        jdbc.update("INSERT INTO experience_skills VALUES (500, 500)");
        jdbc.update("DELETE FROM skills WHERE id = 500");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM experience_skills WHERE skill_id = 500", Integer.class))
                .isZero();
    }

    @Test
    void skillLabelIsCaseInsensitiveUnique() {
        jdbc.update("INSERT INTO skill_groups (id, category, icon) VALUES (501, 'g', 'i')");
        jdbc.update("INSERT INTO skills (group_id, label) VALUES (501, 'Airflow')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO skills (group_id, label) VALUES (501, 'airflow')"))
                .hasMessageContaining("UNIQUE");
    }

    @Test
    void healthEndpointIsUp() {
        var resp = http.getForEntity("/actuator/health", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("UP");
    }
}
