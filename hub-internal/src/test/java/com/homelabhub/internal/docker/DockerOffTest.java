package com.homelabhub.internal.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.terminal.TerminalClient;
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
class DockerOffTest {

    static final String PASSWORD = "pw-for-docker-off-test";
    static final String ID = "a".repeat(64);
    static final String YAML = """
            version: 1
            groups: []
            dockerHosts:
              - {id: pi, label: Pi, host: 127.0.0.1, user: hubdocker, key: k, hostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
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
            assertThat(b.call("GET", "/api/dashboard", null).body()).contains("\"docker\":false");
            assertThat(b.call("GET", "/api/admin/docker/config", null).body()).contains("\"enabled\":false").contains("\"hosts\":[]");
            assertThat(b.call("GET", "/api/admin/docker/hosts/pi/containers", null).statusCode()).isEqualTo(503);
            assertThat(b.call("POST", "/api/admin/docker/hosts/pi/containers/" + ID + "/stop/prepare", null).statusCode()).isEqualTo(503);
            assertThat(b.call("POST", "/api/admin/docker/confirm", "{\"token\":\"x\"}").statusCode()).isEqualTo(503);
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
            r.add("hub.docker.enabled", () -> "true");
        }

        @LocalServerPort int port;
        @MockitoBean ProxmoxClient proxmox;

        @Test
        void modeNoneKeepsItOff() throws Exception {
            TerminalClient anon = new TerminalClient(port);
            anon.call("GET", "/api/auth/status", null);
            assertThat(anon.call("GET", "/api/dashboard", null).body()).contains("\"docker\":false");
            assertThat(anon.call("GET", "/api/admin/docker/hosts/pi/containers", null).statusCode()).isEqualTo(503);
        }
    }
}
