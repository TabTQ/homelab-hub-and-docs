package com.homelabhub.internal.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/**
 * In-process SSH server for the terminal tests (plan D12). One authorised ed25519 client key, a fixed host key, and a
 * tiny line-based "shell":
 * <pre>
 *   on start      prints "ready TERM=&lt;term&gt; SIZE=&lt;cols&gt;x&lt;rows&gt;"
 *   any line      echoed back as "echo:&lt;line&gt;"
 *   "exit N"      exits with code N
 *   "flood"       writes 64 MB of output
 *   "utf8"        writes a multi-byte string split across two writes
 *   window-change prints "winch &lt;cols&gt;x&lt;rows&gt;"
 * </pre>
 */
final class TestSshServer implements AutoCloseable {

    final SshServer sshd;
    final String hostKeyFingerprint;
    final AtomicInteger openShells = new AtomicInteger();
    final AtomicInteger authAttempts = new AtomicInteger();
    final AtomicInteger passwordAttempts = new AtomicInteger();

    TestSshServer(Path hostKeyFile, byte[] authorisedBlob) throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(hostKeyFile);
        keys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        sshd.setKeyPairProvider(keys);
        sshd.setPublickeyAuthenticator((user, key, session) -> {
            authAttempts.incrementAndGet();
            return user.equals("hubshell") && Arrays.equals(blob(key), authorisedBlob);
        });
        sshd.setPasswordAuthenticator((user, pw, session) -> {
            passwordAttempts.incrementAndGet();
            return false;
        });
        sshd.setKeyboardInteractiveAuthenticator(null);
        sshd.setShellFactory(channel -> new EchoShell());
        sshd.start();
        PublicKey hostKey = keys.loadKeys(null).iterator().next().getPublic();
        hostKeyFingerprint = KeyUtils.getFingerPrint(hostKey);
    }

    /** A server plus a hub data folder whose dashboard.yaml declares it as terminal "pi" (and an imposter "wrongkey"). */
    static TestSshServer forHub(Path dataDir) {
        try {
            TestKeys client = TestKeys.ed25519();
            client.writeTo(dataDir.resolve("ssh/hub_key"));
            TestSshServer ssh = new TestSshServer(java.nio.file.Files.createTempDirectory("hub-sshd").resolve("hostkey.ser"), client.publicBlob);
            java.nio.file.Files.createDirectories(dataDir.resolve("config"));
            java.nio.file.Files.writeString(dataDir.resolve("config/dashboard.yaml"), """
                    version: 1
                    groups: []
                    terminals:
                      - id: pi
                        label: Test Pi
                        host: 127.0.0.1
                        port: %d
                        user: hubshell
                        key: hub_key
                        hostKey: "%s"
                      - id: wrongkey
                        label: Imposter
                        host: 127.0.0.1
                        port: %d
                        user: hubshell
                        key: hub_key
                        hostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    """.formatted(ssh.port(), ssh.hostKeyFingerprint, ssh.port()));
            return ssh;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    int port() {
        return sshd.getPort();
    }

    static byte[] blob(PublicKey key) {
        ByteArrayBuffer b = new ByteArrayBuffer();
        b.putRawPublicKey(key);
        return b.getCompactData();
    }

    @Override
    public void close() throws IOException {
        sshd.stop(true);
    }

    private final class EchoShell implements Command {
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;
        private volatile Thread thread;

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {}

        @Override
        public void setExitCallback(ExitCallback exit) {
            this.exit = exit;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            openShells.incrementAndGet();
            env.addSignalListener((ch, sig) -> write("winch " + env.getEnv().get(Environment.ENV_COLUMNS) + "x"
                    + env.getEnv().get(Environment.ENV_LINES) + "\r\n"), Signal.WINCH);
            thread = new Thread(() -> loop(env), "test-shell");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop(Environment env) {
            int code = 0;
            try {
                write("ready TERM=" + env.getEnv().get(Environment.ENV_TERM) + " SIZE=" + env.getEnv().get(Environment.ENV_COLUMNS)
                        + "x" + env.getEnv().get(Environment.ENV_LINES) + "\r\n");
                StringBuilder line = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                outer:
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    for (char c : chunk.toCharArray()) {
                        if (c != '\r' && c != '\n') {
                            line.append(c);
                            continue;
                        }
                        String l = line.toString();
                        line.setLength(0);
                        if (l.isEmpty()) {
                            continue;
                        }
                        if (l.startsWith("exit")) {
                            code = l.length() > 5 ? Integer.parseInt(l.substring(5).trim()) : 0;
                            break outer;
                        } else if (l.equals("flood")) {
                            byte[] junk = new byte[64 * 1024];
                            Arrays.fill(junk, (byte) 'x');
                            for (int i = 0; i < 1024; i++) {
                                out.write(junk);
                                out.flush();
                            }
                        } else if (l.equals("utf8")) {
                            byte[] b = "grüße ✓\r\n".getBytes(StandardCharsets.UTF_8);
                            out.write(b, 0, 3); // splits the ü
                            out.flush();
                            out.write(b, 3, b.length - 3);
                            out.flush();
                        } else {
                            write("echo:" + l + "\r\n");
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                code = 255;
            } finally {
                openShells.decrementAndGet();
                exit.onExit(code);
            }
        }

        private void write(String s) {
            try {
                synchronized (this) {
                    out.write(s.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException e) {
                // the channel is gone
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }
}
