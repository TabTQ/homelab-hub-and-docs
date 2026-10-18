package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Plan N7: a browser that stops reading output must not make the hub buffer without limit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalSlowClientTest {

    static final Path DIR = TestSupport.tempDir();
    static final String PASSWORD = "slow-client-pw";
    static final TestSshServer SSH = TestSshServer.forHub(DIR);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.terminal.enabled", () -> "true");
        r.add("hub.terminal.max-queued-output-bytes", () -> "65536");
        r.add("hub.status.auto-start", () -> "false");
    }

    @AfterAll
    static void stopSsh() throws IOException {
        SSH.close();
    }

    @LocalServerPort int port;
    @MockitoBean ProxmoxClient proxmox;
    @Autowired TerminalSessionManager sessions;
    @Autowired JdbcClient jdbc;

    @Test
    void aBrowserThatStopsReadingIsDisconnectedAndTheShellClosed() throws Exception {
        TerminalClient b = new TerminalClient(port).login(PASSWORD);
        TerminalClient.Ws ws = b.connect();
        ws.auth(b.ticket(PASSWORD));
        ws.await("ready");
        ws.paused = true;
        ws.input("flood\\r"); // 64 MB, far more than the socket buffers hold
        await().atMost(Duration.ofSeconds(45)).until(() -> sessions.active() == 0);
        assertThat(jdbc.sql("SELECT detail FROM terminal_audit WHERE event='close'").query(String.class).list())
                .anyMatch(d -> d.contains("reason=too_slow"));
        await().atMost(Duration.ofSeconds(15)).until(() -> SSH.openShells.get() == 0);
    }
}
