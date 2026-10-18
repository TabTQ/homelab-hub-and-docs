package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The whole terminal path through the real security chain, a real WebSocket and a real SSH server (plan task 3.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class TerminalApiTest {

    static final Path DIR = TestSupport.tempDir();
    static final String PASSWORD = "correct horse battery staple";
    static final TestSshServer SSH = TestSshServer.forHub(DIR);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
        r.add("hub.terminal.enabled", () -> "true");
        r.add("hub.terminal.idle-seconds", () -> "4");
        r.add("hub.terminal.warn-seconds", () -> "2");
        r.add("hub.terminal.max-sessions", () -> "2");
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

    @AfterEach
    void allClosed() {
        await().atMost(Duration.ofSeconds(30)).until(() -> sessions.active() == 0);
    }

    TerminalClient browser() throws Exception {
        return new TerminalClient(port).login(PASSWORD);
    }

    List<String> audit(String event) {
        return jdbc.sql("SELECT coalesce(detail,'') FROM terminal_audit WHERE event = ? ORDER BY id").param(event).query(String.class).list();
    }

    // ---- REST ----------------------------------------------------------------------------------------------------

    @Test
    void everythingNeedsTheLogin() throws Exception {
        TerminalClient anon = new TerminalClient(port);
        assertThat(anon.call("GET", "/api/admin/terminal/config", null).statusCode()).isEqualTo(401);
        assertThat(anon.call("GET", "/api/admin/terminal/audit", null).statusCode()).isEqualTo(401);
        assertThat(anon.rawUpgrade("localhost:" + port, "http://localhost:" + port, false)).contains(" 401");
    }

    @Test
    void configListsHostsButNeverKeysOrHostKeys() throws Exception {
        HttpResponse<String> r = browser().call("GET", "/api/admin/terminal/config", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"enabled\":true").contains("\"id\":\"pi\"").contains("\"user\":\"hubshell\"")
                .contains("\"idleSeconds\":4").doesNotContain("hub_key").doesNotContain("SHA256");
        assertThat(browser().call("GET", "/api/dashboard", null).body()).contains("\"features\":{\"terminal\":true,\"docker\":false}")
                .doesNotContain("hub_key").doesNotContain("SHA256");
    }

    @Test
    void aWrongStepUpPasswordIsRefusedAndAudited() throws Exception {
        TerminalClient b = browser();
        HttpResponse<String> r = b.stepUp("pi", "wrong");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).contains("invalid_credentials").doesNotContain("ticket");
        assertThat(audit("denied")).anyMatch(d -> d.contains("reason=wrong_password"));
        assertThat(b.stepUp("nope", PASSWORD).statusCode()).isEqualTo(404);
        assertThat(b.call("POST", "/api/admin/terminal/sessions", "{\"hostId\":\"pi\",\"password\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(200);
    }

    @Test
    void stepUpNeedsCsrf() throws Exception {
        TerminalClient b = browser();
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/admin/terminal/sessions"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"hostId\":\"pi\",\"password\":\"" + PASSWORD + "\"}")).build();
        assertThat(b.http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    // ---- the happy path ------------------------------------------------------------------------------------------

    @Test
    void aFullSessionEchoesResizesExitsAndIsAuditedWithoutContent(CapturedOutput log) throws Exception {
        TerminalClient b = browser();
        String ticket = b.ticket(PASSWORD);
        TerminalClient.Ws ws = b.connect();
        ws.auth(ticket);
        assertThat(ws.await("ready")).contains("\"host\":\"pi\"").contains("\"user\":\"hubshell\"");
        ws.awaitOutput("ready TERM=xterm-256color SIZE=100x30");
        ws.input("top-secret-typed-text\\r");
        ws.awaitOutput("echo:top-secret-typed-text");
        ws.send("{\"type\":\"resize\",\"cols\":132,\"rows\":43}");
        ws.awaitOutput("winch 132x43");
        ws.send("{\"type\":\"resize\",\"cols\":99999,\"rows\":0}");
        ws.awaitOutput("winch 500x3");
        ws.input("utf8\\r");
        ws.awaitOutput("grüße ✓");
        ws.send("{\"type\":\"ping\"}");
        ws.await("pong");
        ws.input("exit 3\\r");
        String exit = ws.await("exit");
        assertThat(exit).contains("\"reason\":\"closed\"").contains("\"exitCode\":3");
        assertThat(ws.awaitClose()).isEqualTo(1000);

        await().until(() -> audit("close").stream().anyMatch(d -> d.contains("reason=closed")));
        assertThat(audit("open")).isNotEmpty();
        String close = audit("close").stream().filter(d -> d.contains("reason=closed")).reduce((a, c) -> c).orElseThrow();
        assertThat(close).contains("bytes_in=").contains("bytes_out=").contains("exit=3");
        assertThat(jdbc.sql("SELECT count(*) FROM terminal_audit WHERE detail LIKE '%secret%' OR user_agent LIKE '%secret%'")
                .query(Integer.class).single()).isZero();
        assertThat(log.getAll()).doesNotContain("top-secret-typed-text").doesNotContain(ticket).doesNotContain(PASSWORD);

        HttpResponse<String> recent = b.call("GET", "/api/admin/terminal/audit?limit=5", null);
        assertThat(recent.body()).contains("\"event\":\"close\"").doesNotContain("top-secret");
    }

    // ---- tickets -------------------------------------------------------------------------------------------------

    @Test
    void aTicketWorksOnceAndOnlyForItsOwnLogin() throws Exception {
        TerminalClient b = browser();
        String ticket = b.ticket(PASSWORD);
        TerminalClient.Ws other = browser().connect();
        other.auth(ticket);
        assertThat(other.await("exit")).contains("\"reason\":\"denied\"");
        assertThat(audit("denied")).anyMatch(d -> d.contains("ticket_wrong_session"));

        TerminalClient.Ws again = b.connect();
        again.auth(ticket);
        assertThat(again.await("exit")).contains("\"reason\":\"denied\"").contains("not valid");

        TerminalClient.Ws bogus = b.connect();
        bogus.auth("made-up");
        assertThat(bogus.await("exit")).contains("\"reason\":\"denied\"");
    }

    @Test
    void theFirstMessageMustBeTheTicketWithinFiveSeconds() throws Exception {
        TerminalClient b = browser();
        TerminalClient.Ws early = b.connect();
        early.input("ls\\r");
        assertThat(early.await("exit")).contains("\"reason\":\"error\"");

        TerminalClient.Ws silent = b.connect();
        long t0 = System.nanoTime();
        assertThat(silent.await("exit")).contains("\"reason\":\"denied\"").contains("in time");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(9));
        assertThat(audit("denied")).anyMatch(d -> d.contains("auth_timeout"));
    }

    @Test
    void malformedAndOversizedMessagesCloseTheSocket() throws Exception {
        TerminalClient b = browser();
        TerminalClient.Ws bad = b.connect();
        bad.send("not json");
        assertThat(bad.await("exit")).contains("Malformed");

        TerminalClient.Ws big = b.connect();
        big.auth(b.ticket(PASSWORD));
        big.await("ready");
        big.send("{\"type\":\"input\",\"data\":\"" + "x".repeat(70_000) + "\"}");
        assertThat(big.awaitClose()).isEqualTo(1009);
        await().until(() -> sessions.active() == 0);
    }

    // ---- handshake checks -----------------------------------------------------------------------------------------

    @Test
    void theUpgradeNeedsOurOriginAndHost() throws Exception {
        TerminalClient b = browser();
        String host = "localhost:" + port;
        assertThat(b.rawUpgrade(host, "https://evil.example", true)).contains(" 403");
        assertThat(b.rawUpgrade(host, null, true)).contains(" 403");
        assertThat(b.rawUpgrade("evil.example", "http://evil.example", true)).contains(" 400");
        assertThat(b.rawUpgrade(host, "http://" + host, true)).contains(" 101");
        assertThat(audit("denied")).anyMatch(d -> d.contains("bad_origin origin=https://evil.example"));
        assertThat(audit("denied")).anyMatch(d -> d.contains("bad_origin origin=none"));
    }

    // ---- limits and endings ---------------------------------------------------------------------------------------

    @Test
    void aThirdSessionIsRefused() throws Exception {
        TerminalClient b = browser();
        TerminalClient.Ws one = b.connect();
        one.auth(b.ticket(PASSWORD));
        one.await("ready");
        TerminalClient.Ws two = b.connect();
        two.auth(b.ticket(PASSWORD));
        two.await("ready");

        HttpResponse<String> r = b.stepUp("pi", PASSWORD);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("Already 2 terminals open");

        one.input("exit 0\\r");
        one.await("exit");
        two.input("exit 0\\r");
        two.await("exit");
    }

    @Test
    void signingOutClosesThatLoginsTerminal() throws Exception {
        TerminalClient b = browser();
        TerminalClient.Ws ws = b.connect();
        ws.auth(b.ticket(PASSWORD));
        ws.await("ready");
        assertThat(b.call("POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        // Either our listener (exit frame, reason logout) or Tomcat (1008, it closes sockets of ended logins) gets there first.
        int code = ws.awaitClose();
        assertThat(code == 1008 || ws.texts.stream().anyMatch(t -> t.contains("\"reason\":\"logout\""))).as("close code " + code).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> SSH.openShells.get() == 0 && sessions.active() == 0);
        assertThat(audit("close")).anyMatch(d -> d.contains("reason=logout"));
    }

    @Test
    void droppingTheSocketClosesTheSshSession() throws Exception {
        TerminalClient b = browser();
        TerminalClient.Ws ws = b.connect();
        ws.auth(b.ticket(PASSWORD));
        ws.await("ready");
        await().until(() -> SSH.openShells.get() == 1);
        ws.socket.abort();
        await().atMost(Duration.ofSeconds(10)).until(() -> SSH.openShells.get() == 0 && sessions.active() == 0);
        assertThat(audit("close")).anyMatch(d -> d.contains("reason=disconnected"));
    }

    @Test
    void anIdleSessionIsWarnedThenClosedAndKeysResetTheTimer() throws Exception {
        TerminalClient b = browser();
        TerminalClient.Ws ws = b.connect();
        ws.auth(b.ticket(PASSWORD));
        ws.await("ready");
        assertThat(ws.await("warn")).contains("secondsLeft");
        ws.input("still here\\r"); // resets the timer
        ws.awaitOutput("echo:still here");
        long t0 = System.nanoTime();
        ws.await("warn");
        assertThat(ws.await("exit")).contains("\"reason\":\"idle\"");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void aHostKeyMismatchIsExplainedAndNothingIsSent() throws Exception {
        TerminalClient b = browser();
        HttpResponse<String> r = b.stepUp("wrongkey", PASSWORD);
        String ticket = r.body().replaceAll(".*\"ticket\":\"([^\"]+)\".*", "$1");
        int authBefore = SSH.authAttempts.get();
        TerminalClient.Ws ws = b.connect();
        ws.auth(ticket);
        assertThat(ws.await("exit")).contains("\"reason\":\"error\"").contains("Host key mismatch").contains(SSH.hostKeyFingerprint);
        assertThat(SSH.authAttempts.get()).isEqualTo(authBefore);
        assertThat(audit("close")).anyMatch(d -> d.contains("connect_failed code=host_key_mismatch"));
    }
}
