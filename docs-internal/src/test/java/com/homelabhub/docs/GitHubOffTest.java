package com.homelabhub.docs;

import static com.homelabhub.docs.DocsClient.M;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With GitHub switched off (the default) the Docs app works as before, and the new routes say so clearly. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitHubOffTest {

    static final Path DIR = DocsClient.tempDir();
    static final String PASSWORD = "docs-test-password";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        DocsClient.useTemp(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
        r.add("docs.domains", () -> "example.test");
    }

    @LocalServerPort int port;

    @Test
    void theGithubOptionIsHiddenAndRefused() throws Exception {
        DocsClient c = new DocsClient(port).login(PASSWORD);
        JsonNode cfg = c.json("GET", "/api/admin/docs/config", null, 200).path("github");
        assertThat(cfg.path("enabled").asBoolean()).isFalse();
        assertThat(cfg.path("siteUrl").isNull()).isTrue();
        assertThat(cfg.path("dryRun").asBoolean()).isFalse();

        JsonNode st = c.json("GET", "/api/admin/docs/github/status", null, 200);
        assertThat(st.path("state").asText()).isEqualTo("off");
        assertThat(st.path("enabled").asBoolean()).isFalse();
        assertThat(c.json("POST", "/api/admin/docs/github/retry", null, 200).path("state").asText()).isEqualTo("off");
        assertThat(c.json("GET", "/api/admin/docs/github/check?slug=anything", null, 200).path("ok").asBoolean()).isFalse();

        long id = c.json("POST", "/api/admin/docs/pages", Map.of("title", "Plain", "kind", "rich"), 201).path("id").asLong();
        long v = c.json("GET", "/api/admin/docs/pages/" + id, null, 200).path("version").asLong();
        Map<String, Object> gh = new HashMap<>();
        gh.put("publicHost", null);
        gh.put("githubSlug", "plain");
        gh.put("version", v);
        assertThat(c.json("POST", "/api/admin/docs/pages/" + id + "/publish", gh, 400).path("message").asText()).contains("not turned on");

        // The old request still works and reports no GitHub address.
        JsonNode ok = c.json("POST", "/api/admin/docs/pages/" + id + "/publish", Map.of("publicHost", "plain.example.test", "version", v), 200);
        assertThat(ok.path("githubUrl").isNull()).isTrue();
        assertThat(ok.path("github").path("state").asText()).isEqualTo("off");
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id, null, 200).path("state").asText()).isEqualTo("public");

        // Portfolio: deploy and remove are refused, status and the download still work.
        assertThat(c.json("POST", "/api/admin/portfolio/github/deploy", null, 400).path("error").asText()).isEqualTo("github_off");
        assertThat(c.json("POST", "/api/admin/portfolio/github/remove", null, 400).path("error").asText()).isEqualTo("github_off");
        JsonNode ps = c.json("GET", "/api/admin/portfolio/github/status", null, 200);
        assertThat(ps.path("enabled").asBoolean()).isFalse();
        assertThat(ps.path("deployed").asBoolean()).isFalse();
        assertThat(ps.path("url").isNull()).isTrue();
        HttpResponse<byte[]> zip = c.getBytes("/api/admin/portfolio/export.zip");
        assertThat(zip.statusCode()).isEqualTo(200);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            assertThat(in.getNextEntry()).isNotNull();
        }
        assertThat(M.readTree("{}").size()).isZero();
    }
}
