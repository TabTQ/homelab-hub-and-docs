package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The SSH layer against a real (in-process) SSH server: plan task 2.2. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SshConnectorTest {

    @TempDir static Path dir;
    static TestKeys client;
    static TestSshServer server;
    static KeyStore keys;

    @BeforeAll
    static void start() throws Exception {
        client = TestKeys.ed25519();
        client.writeTo(dir.resolve("ssh/hub_key"));
        TestKeys.ed25519().writeTo(dir.resolve("ssh/other_key")); // valid key, not authorised
        server = new TestSshServer(dir.resolve("hostkey.ser"), client.publicBlob);
        keys = new KeyStore(dir.resolve("ssh"));
    }

    @AfterAll
    static void stop() throws IOException {
        server.close();
    }

    static TerminalSpec spec(String key, String hostKey, int port) {
        return new TerminalSpec("pi", "Test Pi", "127.0.0.1", port, "hubshell", key, hostKey);
    }

    private SshConnector connector() {
        return new SshConnector(keys, 5000);
    }

    /** Reads until {@code expected} appears (or fails after a few seconds). */
    static String readUntil(InputStream in, String expected) throws IOException {
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        byte[] buf = new byte[4096];
        while (System.nanoTime() < deadline) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            seen.write(buf, 0, n);
            if (seen.toString(StandardCharsets.UTF_8).contains(expected)) {
                return seen.toString(StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("did not see '" + expected + "' in: " + seen.toString(StandardCharsets.UTF_8));
    }

    @Test
    void opensAPtyShellThatEchoesResizesAndReportsTheExitCode() throws Exception {
        try (TerminalConnection c = connector().open(spec("hub_key", server.hostKeyFingerprint, server.port()), 120, 40)) {
            readUntil(c.output(), "ready TERM=xterm-256color SIZE=120x40");
            c.write("hello\r".getBytes(StandardCharsets.UTF_8));
            readUntil(c.output(), "echo:hello");
            c.resize(100, 30);
            readUntil(c.output(), "winch 100x30");
            c.write("exit 7\r".getBytes(StandardCharsets.UTF_8));
            byte[] buf = new byte[1024];
            while (c.output().read(buf) >= 0) {
                // drain to end of stream
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (c.exitStatus() == null && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(c.exitStatus()).isEqualTo(7);
        }
    }

    @Test
    void refusesAWrongHostKeyBeforeAuthenticating() {
        int before = server.authAttempts.get();
        String wrong = "SHA256:" + "A".repeat(43);
        assertThatThrownBy(() -> connector().open(spec("hub_key", wrong, server.port()), 80, 24))
                .isInstanceOf(TerminalException.class)
                .satisfies(e -> {
                    assertThat(((TerminalException) e).code()).isEqualTo(TerminalException.Code.host_key_mismatch);
                    assertThat(e.getMessage()).contains(wrong).contains(server.hostKeyFingerprint);
                });
        assertThat(server.authAttempts.get()).as("no authentication after a host key mismatch").isEqualTo(before);
    }

    @Test
    void anUnauthorisedKeyIsRejectedWithoutFallingBackToPasswords() {
        int pw = server.passwordAttempts.get();
        assertThatThrownBy(() -> connector().open(spec("other_key", server.hostKeyFingerprint, server.port()), 80, 24))
                .isInstanceOf(TerminalException.class)
                .extracting(e -> ((TerminalException) e).code()).isEqualTo(TerminalException.Code.auth_rejected);
        assertThat(server.passwordAttempts.get()).isEqualTo(pw);
    }

    @Test
    void aClosedPortIsReportedAsUnreachable() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> connector().open(spec("hub_key", server.hostKeyFingerprint, free), 80, 24))
                .isInstanceOf(TerminalException.class)
                .extracting(e -> ((TerminalException) e).code()).isEqualTo(TerminalException.Code.unreachable);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void aServerThatNeverAnswersTimesOut() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) { // accepts (backlog) but never speaks SSH
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> new SshConnector(keys, 1000).open(spec("hub_key", server.hostKeyFingerprint, silent.getLocalPort()), 80, 24))
                    .isInstanceOf(TerminalException.class)
                    .extracting(e -> ((TerminalException) e).code()).isEqualTo(TerminalException.Code.unreachable);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void aMissingKeyFileIsReportedClearly() {
        assertThatThrownBy(() -> connector().open(spec("nope", server.hostKeyFingerprint, server.port()), 80, 24))
                .isInstanceOf(TerminalException.class)
                .satisfies(e -> {
                    assertThat(((TerminalException) e).code()).isEqualTo(TerminalException.Code.key_missing);
                    assertThat(e.getMessage()).contains("'nope'").doesNotContain(dir.toString());
                });
    }

    @Test
    void aFileThatIsNotAKeyIsReportedClearly() throws Exception {
        Files.writeString(dir.resolve("ssh/garbage"), "not a key");
        assertThatThrownBy(() -> connector().open(spec("garbage", server.hostKeyFingerprint, server.port()), 80, 24))
                .isInstanceOf(TerminalException.class)
                .extracting(e -> ((TerminalException) e).code()).isEqualTo(TerminalException.Code.key_invalid);
    }

    @Test
    void theHostKeyCliPrintsTheSameFingerprintAsTheServer() {
        var out = new ByteArrayOutputStream();
        var old = System.out;
        System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = HostKeyCli.run(new String[] {"terminal-hostkey", "127.0.0.1:" + server.port()});
        } finally {
            System.setOut(old);
        }
        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Key type:    ecdsa-sha2-nistp").contains(server.hostKeyFingerprint);
    }

    @Test
    void theHostKeyCliFailsOnAClosedPortAndOnBadArguments() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        assertThat(HostKeyCli.run(new String[] {"terminal-hostkey", "127.0.0.1:" + free})).isEqualTo(1);
        assertThat(HostKeyCli.run(new String[] {"terminal-hostkey"})).isEqualTo(2);
        assertThat(HostKeyCli.run(new String[] {"terminal-hostkey", "-oProxyCommand=x"})).isEqualTo(2);
    }
}
