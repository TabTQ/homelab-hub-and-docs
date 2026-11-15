package com.homelabhub.internal.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real HTTP against the real second connector: ports, the CSRF cookie and what exists on the member port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "hub.auth.mode=all")
class MemberPortConnectorTest {

    private static final Path DIR = TestSupport.tempDir();
    private static final int MEMBER_PORT = MemberAccountsApiTest.freePort();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.member.port", () -> MEMBER_PORT);
    }

    @LocalServerPort int mainPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean ProxmoxClient proxmox;

    private HttpResponse<String> call(HttpClient http, CookieManager cookies, String method, int port, String path, String body, boolean csrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (csrf) {
            cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst()
                    .ifPresent(c -> b.header("X-XSRF-TOKEN", c.getValue()));
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theSecondConnectorServesOnlyTheMemberSurfaceAndHandsOutTheCsrfCookie() throws Exception {
        jdbc.update("INSERT INTO budget_people (name) VALUES ('Asha')");
        long id = jdbc.queryForObject("SELECT id FROM budget_people WHERE name = 'Asha'", Long.class);
        jdbc.update("INSERT INTO budget_accounts (person_id, password_hash, must_change, can_add) VALUES (?, ?, 0, 0)", id, encoder.encode("a-fine-password"));

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        // The CSRF cookie comes with the context call on both ports.
        for (int port : new int[] {mainPort, MEMBER_PORT}) {
            CookieManager fresh = new CookieManager(null, CookiePolicy.ACCEPT_ALL); // cookies ignore the port, so a new browser per port
            HttpResponse<String> ctx = call(HttpClient.newBuilder().cookieHandler(fresh).build(), fresh, "GET", port, "/api/member/context", null, false);
            assertThat(ctx.statusCode()).isEqualTo(200);
            assertThat(ctx.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
            assertThat(ctx.body()).contains("\"memberPort\":" + (port == MEMBER_PORT));
        }
        HttpResponse<String> ctx = call(http, cookies, "GET", MEMBER_PORT, "/api/member/context", null, false);
        assertThat(ctx.statusCode()).isEqualTo(200);

        HttpResponse<String> in = call(http, cookies, "POST", MEMBER_PORT, "/api/member/login", "{\"name\":\"Asha\",\"password\":\"a-fine-password\"}", false);
        assertThat(in.statusCode()).isEqualTo(200);
        assertThat(in.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("HUBSESSION="));
        assertThat(cookies.getCookieStore().getCookies()).anyMatch(c -> c.getName().equals("XSRF-TOKEN"));
        assertThat(call(http, cookies, "GET", MEMBER_PORT, "/api/admin/budget/people", null, false).statusCode()).isEqualTo(200);

        // Not on the list = not there, whoever asks. The same session on the main port is refused with 403 instead.
        for (String path : List.of("/api/auth/status", "/api/dashboard", "/api/admin/config", "/actuator/health", "/wake/mac/00:00:5E:00:53:01", "/node/pve/shutdown")) {
            HttpResponse<String> r = call(http, cookies, "GET", MEMBER_PORT, path, null, false);
            assertThat(r.statusCode()).as(path).isEqualTo(404);
            assertThat(r.body()).as(path).isEqualTo("{\"error\":\"not_found\"}");
        }
        // Encoded characters make no difference: the path must be exactly an allowlisted one.
        assertThat(call(http, cookies, "GET", mainPort, "/api/admin/budget/%70eople", null, false).statusCode()).isEqualTo(403);
        assertThat(call(http, cookies, "GET", MEMBER_PORT, "/api/admin/budget/%70eople", null, false).statusCode()).isEqualTo(404);
        assertThat(call(http, cookies, "POST", MEMBER_PORT, "/api/auth/login", "{\"password\":\"x\"}", false).statusCode()).isEqualTo(404);
        assertThat(call(http, cookies, "GET", mainPort, "/api/dashboard", null, false).statusCode()).isEqualTo(403);
        assertThat(call(http, cookies, "GET", mainPort, "/api/auth/status", null, false).statusCode()).isEqualTo(403); // a member session reaches nothing else

        // Logout needs the token from the cookie.
        assertThat(call(http, cookies, "POST", MEMBER_PORT, "/api/member/logout", null, false).statusCode()).isEqualTo(403);
        assertThat(call(http, cookies, "POST", MEMBER_PORT, "/api/member/logout", null, true).statusCode()).isEqualTo(204);
        assertThat(call(http, cookies, "GET", MEMBER_PORT, "/api/admin/budget/people", null, false).statusCode()).isEqualTo(401);
    }
}
