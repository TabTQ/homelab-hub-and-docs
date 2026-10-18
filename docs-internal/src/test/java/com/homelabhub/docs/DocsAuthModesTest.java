package com.homelabhub.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** DOCS_AUTH_MODE edit and none, plus a server without a password (the login is hub-auth's, so it behaves like the hub's). */
class DocsAuthModesTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class EditMode {
        static final Path DIR = DocsClient.tempDir();

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            DocsClient.useTemp(r, DIR);
            r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode("pw"));
            r.add("hub.auth.mode", () -> "edit");
        }

        @LocalServerPort int port;

        @Test
        void theEditorApiStillNeedsTheLogin() throws Exception {
            DocsClient anon = new DocsClient(port);
            anon.call("GET", "/api/auth/status", null);
            assertThat(anon.call("GET", "/api/admin/docs/tree", null).statusCode()).isEqualTo(401);
            assertThat(anon.call("GET", "/api/auth/status", null).body()).contains("\"mode\":\"edit\"");
            DocsClient in = new DocsClient(port).login("pw");
            assertThat(in.call("GET", "/api/admin/docs/tree", null).statusCode()).isEqualTo(200);
        }

        @Test
        void thePortfolioEditorIsProtectedLikeTheDocsEditor() throws Exception {
            DocsClient anon = new DocsClient(port);
            anon.call("GET", "/api/auth/status", null);
            assertThat(anon.call("GET", "/api/admin/portfolio/schema", null).statusCode()).isEqualTo(401);
            assertThat(anon.call("GET", "/api/admin/portfolio/publish-status", null).statusCode()).isEqualTo(401);
            assertThat(anon.call("POST", "/api/admin/portfolio/republish", null).statusCode()).isIn(401, 403);
            assertThat(anon.call("POST", "/api/admin/portfolio/experiences", "{}").statusCode()).isIn(401, 403);
            DocsClient in = new DocsClient(port).login("pw");
            assertThat(in.call("GET", "/api/admin/portfolio/schema", null).statusCode()).isEqualTo(200);
            assertThat(in.call("GET", "/api/portfolio", null).statusCode()).isEqualTo(200);
            in.csrf = false;
            assertThat(in.call("POST", "/api/admin/portfolio/experiences", "{}").statusCode()).as("a login without the CSRF header").isEqualTo(403);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class NoneMode {
        static final Path DIR = DocsClient.tempDir();

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            DocsClient.useTemp(r, DIR);
            r.add("hub.auth.mode", () -> "none");
        }

        @LocalServerPort int port;

        @Test
        void noLoginButCsrfStillApplies() throws Exception {
            DocsClient anon = new DocsClient(port);
            anon.call("GET", "/api/auth/status", null);
            assertThat(anon.call("GET", "/api/admin/docs/tree", null).statusCode()).isEqualTo(200);
            assertThat(anon.call("GET", "/api/admin/portfolio/schema", null).statusCode()).isEqualTo(200);
            anon.csrf = false;
            assertThat(anon.call("POST", "/api/admin/portfolio/experiences", "{}").statusCode()).as("portfolio writes need CSRF too").isEqualTo(403);
            anon.csrf = true;
            assertThat(anon.call("POST", "/api/admin/docs/pages", Map.of("title", "x", "kind", "rich")).statusCode()).isEqualTo(201);
            anon.csrf = false;
            assertThat(anon.call("POST", "/api/admin/docs/pages", Map.of("title", "y", "kind", "rich")).statusCode()).isEqualTo(403);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class NoPassword {
        static final Path DIR = DocsClient.tempDir();

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            DocsClient.useTemp(r, DIR);
            r.add("hub.auth.mode", () -> "all");
            r.add("hub.auth.password-hash", () -> "");
        }

        @LocalServerPort int port;

        @Test
        void loginSaysThePasswordIsNotConfigured() throws Exception {
            DocsClient anon = new DocsClient(port);
            anon.call("GET", "/api/auth/status", null);
            var r = anon.call("POST", "/api/auth/login", "{\"password\":\"x\"}");
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.body()).contains("password_not_configured");
        }
    }
}
