package com.homelabhub.internal.docker;

import com.homelabhub.internal.terminal.TestKeys;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Arrays;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/**
 * An SSH server whose exec requests really run through /bin/sh (so the hub's quoting is tested for real) in a throwaway
 * home directory, with a fake `docker` first on the PATH. The fake records each call's arguments (one per line) and stdin
 * in {@code calls}, and answers from files in {@code state} (ps.out, stats.out) so a test can change what the host shows.
 */
final class DockerTestServer implements AutoCloseable {

    final SshServer sshd;
    final String hostKeyFingerprint;
    final Path home;
    final Path state;
    final Path calls;

    static final String FAKE_DOCKER = """
            #!/bin/sh
            S="$FAKE_DOCKER_STATE"
            n=$(ls "$S/calls" | wc -l)
            f="$S/calls/$(printf '%04d' "$n")"
            printf '%s\\n' "$@" > "$f.args"
            case "$1" in
              ps) case "$*" in *-aq*) printf 'aaa\\nccc\\n' ;; *) cat "$S/ps.out" ;; esac ;;
              stats) cat "$S/stats.out" ;;
              image) cat "$S/images.out" ;;
              inspect) cat "$S/inspect.out" ;;
              rmi|network)
                case "$*" in
                  *dangling=true*) cat "$S/networks.out" ;;
                  "network ls"*) printf 'bridge\\nhost\\nproxy\\n' ;;
                  *)
                    if [ -f "$S/fail-rm" ] && grep -qF -- "$(tail -n 1 "$f.args")" "$S/fail-rm"; then
                      echo "Error response from daemon: conflict: unable to remove $(tail -n 1 "$f.args")"; exit 1
                    fi
                    echo "Deleted: $*" ;;
                esac ;;
              logs)
                case "$*" in
                  *--follow*) i=0; while [ $i -lt 50 ]; do echo "2026-10-02T10:00:0${i}Z live line $i"; i=$((i+1)); sleep 0.1; done ;;
                  *) echo "2026-10-02T10:00:00Z first"; echo "2026-10-02T10:00:01Z second"; echo "2026-10-02T10:00:02Z third" ;;
                esac ;;
              start|stop|restart|rm) [ -f "$S/fail" ] && { cat "$S/fail"; exit 1; }; echo "$2" ;;
              run) cat > "$f.stdin"; echo "deadbeef"; ;;
              compose)
                case "$*" in
                  *" config -q"*)
                    file=""; prev=""
                    for a in "$@"; do [ "$prev" = "-f" ] && file="$a"; prev="$a"; done
                    if grep -q INVALID "$file"; then echo "services.web Additional property nope is not allowed"; exit 1; fi ;;
                  *) echo "compose ok: $*" ;;
                esac ;;
              *) echo "unknown docker call: $*"; exit 2 ;;
            esac
            """;

    /**
     * image ls: "1…" is used by a running container, "2…" only by a stopped one (it must still not be offered), "3…" has
     * two tags and nothing uses it, "4…" is dangling.
     */
    static final String IMAGES = String.join("\n",
            "{\"ID\":\"sha256:" + "1".repeat(64) + "\",\"Repository\":\"jellyfin/jellyfin\",\"Tag\":\"10.11\",\"Size\":\"1.2GB\",\"CreatedSince\":\"3 weeks ago\"}",
            "{\"ID\":\"sha256:" + "2".repeat(64) + "\",\"Repository\":\"alpine\",\"Tag\":\"3.20\",\"Size\":\"7.8MB\",\"CreatedSince\":\"2 months ago\"}",
            "{\"ID\":\"sha256:" + "3".repeat(64) + "\",\"Repository\":\"nginx\",\"Tag\":\"1.27\",\"Size\":\"187MB\",\"CreatedSince\":\"5 weeks ago\"}",
            "{\"ID\":\"sha256:" + "3".repeat(64) + "\",\"Repository\":\"nginx\",\"Tag\":\"latest\",\"Size\":\"187MB\",\"CreatedSince\":\"5 weeks ago\"}",
            "{\"ID\":\"sha256:" + "4".repeat(64) + "\",\"Repository\":\"<none>\",\"Tag\":\"<none>\",\"Size\":\"52MB\",\"CreatedSince\":\"2 days ago\"}") + "\n";

    static final String PIHOLE = "{\"id\":\"ddd0000000000000000000000000000000000000000000000000000000000004\",\"name\":\"pihole\","
            + "\"image\":\"pihole/pihole\",\"state\":\"running\",\"status\":\"Up 1 hour\",\"ports\":\"53/udp\",\"project\":\"\",\"self\":\"\"}\n";

    DockerTestServer(byte[] authorisedBlob) throws Exception {
        home = Files.createTempDirectory("docker-home");
        state = Files.createTempDirectory("docker-state");
        calls = Files.createDirectories(state.resolve("calls"));
        Path bin = Files.createDirectories(state.resolve("bin"));
        Files.writeString(bin.resolve("docker"), FAKE_DOCKER);
        bin.resolve("docker").toFile().setExecutable(true);
        Files.writeString(state.resolve("ps.out"), DockerParserTest.PS + PIHOLE);
        Files.writeString(state.resolve("images.out"), IMAGES);
        Files.writeString(state.resolve("inspect.out"), "sha256:" + "1".repeat(64) + "\n" + "sha256:" + "2".repeat(64) + "\n");
        Files.writeString(state.resolve("networks.out"), "{\"Name\":\"old-net\",\"Driver\":\"bridge\",\"CreatedAt\":\"2026-01-01\"}\n"
                + "{\"Name\":\"bridge\",\"Driver\":\"bridge\"}\n");
        Files.writeString(state.resolve("stats.out"), "{\"ID\":\"bbb\",\"Name\":\"jellyfin\",\"CPUPerc\":\"1.5%\",\"MemPerc\":\"2.0%\",\"MemUsage\":\"1MiB / 1GiB\"}\n");
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(state.resolve("hostkey.ser"));
        keys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        sshd.setKeyPairProvider(keys);
        sshd.setPublickeyAuthenticator((user, key, session) -> user.equals("hubdocker") && Arrays.equals(blob(key), authorisedBlob));
        sshd.setPasswordAuthenticator((user, pw, session) -> false);
        sshd.setKeyboardInteractiveAuthenticator(null);
        sshd.setCommandFactory((channel, command) -> new ShellCommand(command));
        sshd.start();
        PublicKey hostKey = keys.loadKeys(null).iterator().next().getPublic();
        hostKeyFingerprint = KeyUtils.getFingerPrint(hostKey);
    }

    /** A server plus a hub data folder whose dashboard.yaml declares it as Docker host "pi" (and "down", which is unreachable). */
    static DockerTestServer forHub(Path dataDir) {
        try {
            TestKeys client = TestKeys.ed25519();
            client.writeTo(dataDir.resolve("ssh/hub_docker"));
            DockerTestServer s = new DockerTestServer(client.publicBlob);
            Files.createDirectories(dataDir.resolve("config"));
            Files.writeString(dataDir.resolve("config/dashboard.yaml"), """
                    version: 1
                    groups: []
                    dockerHosts:
                      - id: pi
                        label: Test Pi
                        host: 127.0.0.1
                        port: %d
                        user: hubdocker
                        key: hub_docker
                        hostKey: "%s"
                        protect: [pihole]
                      - id: down
                        label: Down host
                        host: 127.0.0.1
                        port: 1
                        user: hubdocker
                        key: hub_docker
                        hostKey: "%s"
                    """.formatted(s.port(), s.hostKeyFingerprint, s.hostKeyFingerprint));
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    int port() {
        return sshd.getPort();
    }

    /** The arguments of every docker call so far, oldest first. */
    java.util.List<java.util.List<String>> dockerCalls() throws IOException {
        try (var files = Files.list(calls)) {
            return files.filter(p -> p.toString().endsWith(".args")).sorted().map(p -> {
                try {
                    return Files.readAllLines(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }

    String lastStdin() throws IOException {
        try (var files = Files.list(calls)) {
            Path p = files.filter(f -> f.toString().endsWith(".stdin")).sorted().reduce((a, b) -> b).orElse(null);
            return p == null ? null : Files.readString(p);
        }
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

    private final class ShellCommand implements Command {
        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exit;
        private volatile Process process;

        ShellCommand(String command) {
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback exit) {
            this.exit = exit;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command).directory(home.toFile());
            pb.environment().put("PATH", state.resolve("bin") + ":" + System.getenv("PATH"));
            pb.environment().put("HOME", home.toString());
            pb.environment().put("FAKE_DOCKER_STATE", state.toString());
            Process p = pb.start();
            process = p;
            Thread t = new Thread(() -> {
                int code = 255;
                try {
                    Thread pumpIn = new Thread(() -> {
                        try (OutputStream pin = p.getOutputStream()) {
                            in.transferTo(pin);
                        } catch (IOException e) {
                            // process ended first
                        }
                    });
                    pumpIn.setDaemon(true);
                    pumpIn.start();
                    Thread pumpErr = new Thread(() -> pump(p.getErrorStream(), err));
                    pumpErr.setDaemon(true);
                    pumpErr.start();
                    pump(p.getInputStream(), out);
                    code = p.waitFor();
                    pumpErr.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exit.onExit(code);
                }
            }, "test-exec");
            t.setDaemon(true);
            t.start();
        }

        private void pump(InputStream from, OutputStream to) {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = from.read(buf)) >= 0) {
                    to.write(buf, 0, n);
                    to.flush();
                }
            } catch (IOException e) {
                // channel closed
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            Process p = process;
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
            }
        }
    }
}
