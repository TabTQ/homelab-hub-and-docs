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

/** HUB_SECURE_COOKIES=true (dashboard served over https, for example through `tailscale serve`) marks both cookies Secure. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecureCookiesTest {

    private static final Path DIR = TestSupport.tempDir();
    private static final String PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
        r.add("hub.security.secure-cookies", () -> "true");
    }

    @LocalServerPort int port;
    @MockitoBean ProxmoxClient proxmox;

    private List<String> cookiesOf(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).headers().allValues("Set-Cookie");
    }

    @Test
    void theCsrfCookieIsSecure() throws Exception {
        List<String> c = cookiesOf("GET", "/api/auth/status", null);
        assertThat(c).anyMatch(x -> x.startsWith("XSRF-TOKEN=") && x.toLowerCase().contains("; secure"));
    }

    @Test
    void theSessionCookieIsSecureAndKeepsItsOtherFlags() throws Exception {
        List<String> c = cookiesOf("POST", "/api/auth/login", "{\"password\":\"" + PASSWORD + "\"}");
        String session = c.stream().filter(x -> x.startsWith("HUBSESSION=")).findFirst().orElseThrow();
        assertThat(session).containsIgnoringCase("; Secure").contains("HttpOnly").containsIgnoringCase("SameSite=Strict");
    }
}
