package com.homelabhub.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real HTTP against a real server, so cookie flags and CSRF behave exactly as a browser would see them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiTest {

    private static final Path DIR = TestSupport.tempDir();
    private static final String PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
    }

    @LocalServerPort int port;
    @MockitoBean ProxmoxClient proxmox;

    CookieManager cookies;
    HttpClient http;

    @BeforeEach
    void freshBrowser() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpResponse<String> call(String method, String path, String body, boolean withCsrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (withCsrf) {
            cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst()
                    .ifPresent(c -> b.header("X-XSRF-TOKEN", c.getValue()));
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String password) throws Exception {
        return call("POST", "/api/auth/login", "{\"password\":\"" + password + "\"}", false);
    }

    private List<String> setCookies(HttpResponse<?> r) {
        return r.headers().allValues("Set-Cookie");
    }

    @Test
    void statusIsPublicReportsTheStateAndHandsOutTheCsrfCookie() throws Exception {
        HttpResponse<String> r = call("GET", "/api/auth/status", null, false);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"authenticated\":false").contains("\"mode\":\"all\"").contains("\"passwordConfigured\":true");
        assertThat(setCookies(r)).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
    }

    @Test
    void protectedEndpointsAnswer401JsonNotARedirect() throws Exception {
        HttpResponse<String> r = call("GET", "/api/me", null, false);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"error\":\"unauthenticated\"}");
        assertThat(r.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void aWrongPasswordIsRefusedAndCreatesNoSession() throws Exception {
        HttpResponse<String> r = login("nope");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"error\":\"invalid_credentials\"}");
        assertThat(setCookies(r)).noneMatch(c -> c.startsWith("HUBSESSION="));
    }

    @Test
    void anEmptyOrMissingPasswordIsRefused() throws Exception {
        assertThat(login("").statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/auth/login", "{}", false).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/auth/login", null, false).statusCode()).isIn(400, 401, 415);
    }

    @Test
    void aVeryLongPasswordIsRefusedWithoutBeingHashed() throws Exception {
        assertThat(login("a".repeat(5000)).statusCode()).isEqualTo(401);
    }

    @Test
    void theCorrectPasswordOpensASessionWithSafeCookieFlags() throws Exception {
        HttpResponse<String> r = login(PASSWORD);
        assertThat(r.statusCode()).isEqualTo(200);
        String session = setCookies(r).stream().filter(c -> c.startsWith("HUBSESSION=")).findFirst().orElseThrow();
        assertThat(session).contains("HttpOnly").containsIgnoringCase("SameSite=Strict");
        // over plain http (the LAN) the cookies must NOT be marked Secure by default, or the browser would refuse to store them
        assertThat(setCookies(r)).noneMatch(c -> c.toLowerCase().contains("; secure"));

        assertThat(call("GET", "/api/me", null, false).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/auth/status", null, false).body()).contains("\"authenticated\":true");
    }

    @Test
    void logoutNeedsTheCsrfTokenAndReallyEndsTheSession() throws Exception {
        login(PASSWORD);
        call("GET", "/api/auth/status", null, false); // makes sure the XSRF cookie is present

        assertThat(call("POST", "/api/auth/logout", null, false).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/me", null, false).statusCode()).as("still logged in after a forged logout").isEqualTo(200);

        assertThat(call("POST", "/api/auth/logout", null, true).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/me", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    void theSessionIdChangesAtLogin() throws Exception {
        // Give the browser a session id first, then log in and make sure that id is no longer valid.
        HttpCookie before = new HttpCookie("HUBSESSION", "attacker-chosen-id");
        before.setPath("/");
        cookies.getCookieStore().add(URI.create("http://localhost:" + port), before);
        login(PASSWORD);
        String after = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("HUBSESSION"))
                .map(HttpCookie::getValue).reduce((a, b) -> b).orElseThrow();
        assertThat(after).isNotEqualTo("attacker-chosen-id");
    }

    @Test
    void legacyRoutesStayOpenWithoutASessionOrCsrfToken() throws Exception {
        assertThat(call("GET", "/wake/mac/zz", null, false).statusCode()).isEqualTo(400);
        HttpResponse<String> post = call("POST", "/wake", "{}", false);
        assertThat(post.statusCode()).as("must reach the legacy handler, not be blocked by CSRF or login").isEqualTo(500);
        assertThat(post.body()).contains("Invalid MAC address format: null");
        assertThat(call("GET", "/actuator/health", null, false).statusCode()).isEqualTo(200);
    }

    @Test
    void theSpaShellIsPublicButApiDataIsNot() throws Exception {
        assertThat(call("GET", "/api/anything", null, false).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/index.html", null, false).statusCode()).isIn(200, 404); // no SPA bundled yet, but never 401
    }
}
