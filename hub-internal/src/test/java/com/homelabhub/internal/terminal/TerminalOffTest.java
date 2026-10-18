package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Fails closed: off by default, and refuses to turn on without a login. */
class TerminalOffTest {

    static final String PASSWORD = "pw-for-off-test";
    static final String YAML = """
            version: 1
            groups: []
            terminals:
              - {id: pi, label: Pi, host: 127.0.0.1, user: hubshell, key: k, hostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
            """;

    static void base(DynamicPropertyRegistry r, Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir.resolve("config"));
            java.nio.file.Files.writeString(dir.resolve("config/dashboard.yaml"), YAML);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        TestSupport.useTempDatabase(r, dir);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.status.auto-start", () -> "false");
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class DisabledByDefault {
        static final Path DIR = TestSupport.tempDir();

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            base(r, DIR);
        }

        @LocalServerPort int port;
        @MockitoBean ProxmoxClient proxmox;

        @Test
        void nothingWorksAndTheNavEntryIsHidden() throws Exception {
            TerminalClient b = new TerminalClient(port).login(PASSWORD);
            assertThat(b.call("GET", "/api/dashboard", null).body()).contains("\"features\":{\"terminal\":false,\"docker\":false}");
            assertThat(b.call("GET", "/api/admin/terminal/config", null).body()).contains("\"enabled\":false").contains("\"terminals\":[]");
            assertThat(b.stepUp("pi", PASSWORD).statusCode()).isEqualTo(503);
            assertThat(b.rawUpgrade("localhost:" + port, "http://localhost:" + port, true)).contains(" 403");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class RefusedWithoutALogin {
        static final Path DIR = TestSupport.tempDir();

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            base(r, DIR);
            r.add("hub.auth.mode", () -> "none");
            r.add("hub.terminal.enabled", () -> "true");
        }

        @LocalServerPort int port;
        @MockitoBean ProxmoxClient proxmox;

        @Test
        void modeNoneKeepsItOff() throws Exception {
            TerminalClient anon = new TerminalClient(port);
            anon.call("GET", "/api/auth/status", null);
            assertThat(anon.call("GET", "/api/dashboard", null).body()).contains("\"features\":{\"terminal\":false,\"docker\":false}");
            assertThat(anon.stepUp("pi", PASSWORD).statusCode()).isEqualTo(503);
            assertThat(anon.rawUpgrade("localhost:" + port, "http://localhost:" + port, false)).contains(" 403");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class EditMode {
        static final Path DIR = TestSupport.tempDir();

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            base(r, DIR);
            r.add("hub.auth.mode", () -> "edit");
            r.add("hub.terminal.enabled", () -> "true");
        }

        @LocalServerPort int port;
        @MockitoBean ProxmoxClient proxmox;

        @Test
        void theDashboardIsOpenButTheTerminalStillNeedsTheLogin() throws Exception {
            TerminalClient anon = new TerminalClient(port);
            assertThat(anon.call("GET", "/api/dashboard", null).statusCode()).isEqualTo(200);
            assertThat(anon.call("GET", "/api/admin/terminal/config", null).statusCode()).isEqualTo(401);
            assertThat(anon.rawUpgrade("localhost:" + port, "http://localhost:" + port, false)).contains(" 401");
            TerminalClient b = new TerminalClient(port).login(PASSWORD);
            assertThat(b.call("GET", "/api/admin/terminal/config", null).body()).contains("\"enabled\":true");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class BruteForce {
        static final Path DIR = TestSupport.tempDir();

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            base(r, DIR);
            r.add("hub.auth.mode", () -> "all");
            r.add("hub.terminal.enabled", () -> "true");
        }

        @LocalServerPort int port;
        @MockitoBean ProxmoxClient proxmox;

        @Test
        void wrongStepUpPasswordsShareTheLoginLockout() throws Exception {
            TerminalClient b = new TerminalClient(port).login(PASSWORD);
            for (int i = 0; i < 5; i++) {
                assertThat(b.stepUp("pi", "guess-" + i).statusCode()).isEqualTo(401);
            }
            var locked = b.stepUp("pi", PASSWORD);
            assertThat(locked.statusCode()).isEqualTo(429);
            assertThat(locked.headers().firstValue("Retry-After")).isPresent();
            TerminalClient other = new TerminalClient(port);
            other.call("GET", "/api/auth/status", null);
            assertThat(other.call("POST", "/api/auth/login", "{\"password\":\"" + PASSWORD + "\"}").statusCode())
                    .as("the login shares the same budget").isEqualTo(429);
        }
    }
}
