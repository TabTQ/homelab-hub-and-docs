package com.homelabhub.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The portfolio moved to the Docs app (portfolio-on-docs-lxc plan). The hub must not serve or edit it any more, must not write a
 * snapshot, and its database schema must not change (the old tables stay as the rollback copy).
 */
@SpringBootTest(properties = "hub.status.auto-start=false")
@AutoConfigureMockMvc
class PortfolioMovedTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @MockitoBean ProxmoxClient proxmox;

    @Test
    void thePortfolioApiIsGoneFromTheHub() throws Exception {
        for (String path : List.of("/api/portfolio", "/api/admin/portfolio/schema", "/api/admin/portfolio/publish-status",
                "/api/admin/portfolio/experiences", "/api/admin/portfolio/config")) {
            mvc.perform(get(path).with(user("admin"))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/admin/portfolio/republish").with(user("admin")).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void theHubWritesNoSnapshot() throws Exception {
        assertThat(DIR.resolve("snapshot")).doesNotExist();
        try (Stream<Path> files = Files.walk(DIR)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).doesNotContain("snapshot.json");
        }
    }

    @Test
    void hubMigrationsAreUnchangedSoNoNewSchemaVersion() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly("V1__initial_schema.sql",
                    "V2__homelab_skills_and_versions.sql", "V3__terminal_audit.sql", "V4__budget.sql", "V5__docker.sql");
        }
    }

    @Test
    void thePortfolioTablesStayInHubDbAsTheRollbackCopy() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + DIR.resolve("hub.db"));
             var rs = c.createStatement().executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('profile','experiences','projects','skills','homelab_devices')")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(5);
        }
    }
}
