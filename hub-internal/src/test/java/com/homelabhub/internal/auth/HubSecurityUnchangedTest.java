package com.homelabhub.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Docs plan P1: the login moved into hub-auth. The hub must still send byte-for-byte the same headers, cookie names and
 * error bodies as before the move. The literals here are copied from the pre-move SecurityConfig on purpose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HubSecurityUnchangedTest {

    private static final Path DIR = TestSupport.tempDir();
    private static final String PASSWORD = "unchanged-pass";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
    }

    @LocalServerPort int port;
    @MockitoBean ProxmoxClient proxmox;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    @Test
    void headersAreTheSameAsBeforeTheMove() throws Exception {
        HttpResponse<String> r = send(req("/api/auth/status"));
        assertThat(r.headers().firstValue("Content-Security-Policy")).contains(
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
        assertThat(r.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(r.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(r.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
    }

    @Test
    void sessionCookieIsStillHubSession() throws Exception {
        HttpResponse<String> r = send(req("/api/auth/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + PASSWORD + "\"}")));
        assertThat(r.statusCode()).isEqualTo(200);
        List<String> cookies = r.headers().allValues("Set-Cookie");
        assertThat(cookies).anyMatch(c -> c.startsWith("HUBSESSION=") && c.contains("HttpOnly") && c.contains("SameSite=Strict"));
        assertThat(cookies).noneMatch(c -> c.startsWith("DOCSSESSION=") || c.startsWith("JSESSIONID="));
    }

    @Test
    void errorBodiesAreTheSame() throws Exception {
        assertThat(send(req("/api/me")).body()).isEqualTo("{\"error\":\"unauthenticated\"}");
        String wrongHost = raw("GET /api/auth/status HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
        assertThat(wrongHost).startsWith("HTTP/1.1 400");
        assertThat(wrongHost).contains("{\"error\":\"Invalid Host header. Add the name to HUB_ALLOWED_HOSTS if it is yours.\"}");
        HttpResponse<String> wrong = send(req("/api/auth/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"nope\"}")));
        assertThat(wrong.body()).isEqualTo("{\"error\":\"invalid_credentials\"}");
    }

    /** java.net.http refuses to set Host, so a foreign Host goes over a plain socket. */
    private String raw(String request) throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
            s.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void legacyRoutesStayOpenWithoutCsrf() throws Exception {
        // Not 401 and not 403: the route is reached (it answers on its own terms, the guest is not configured here).
        HttpResponse<String> r = send(req("/node/status"));
        assertThat(r.statusCode()).isNotIn(401, 403);
    }
}
