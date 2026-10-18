package com.homelabhub.internal.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owner of dashboard.yaml (plan 7.3). The file is the source of truth and is treated as opaque text plus validation,
 * so comments and formatting survive. Guarantees:
 *  - a save is validated first, written atomically (temp file + fsync + rename) and versioned;
 *  - a stale editor cannot overwrite a newer file (If-Match on the SHA-256);
 *  - a bad file on disk (edited over SSH) never takes the dashboard down: the last good config keeps being served
 *    and the problem is reported;
 *  - the last {@value #KEEP_VERSIONS} versions are kept and can be restored.
 */
@Service
public class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);
    static final int KEEP_VERSIONS = 50;

    public enum Health { OK, INVALID_ON_DISK, USING_DEFAULT }

    /** {@code text}/{@code sha256}/{@code issues} describe the file on disk; {@code config} is what is being served. */
    public record State(String text, String sha256, DashboardConfig config, List<Issue> issues, Health health, Instant loadedAt) {}

    public record VersionInfo(long id, String ts, String sha256, String source, String note, int sizeBytes) {}

    public record ConfigChangedEvent(State state) {}

    public static final class ConflictException extends RuntimeException {
        private final transient State current;

        public ConflictException(State current) {
            super("The file changed since it was loaded");
            this.current = current;
        }

        public State current() {
            return current;
        }
    }

    public static final class InvalidConfigException extends RuntimeException {
        private final transient List<Issue> issues;

        public InvalidConfigException(List<Issue> issues) {
            super("The configuration has errors");
            this.issues = issues;
        }

        public List<Issue> issues() {
            return issues;
        }
    }

    private final Path file;
    private final JdbcClient jdbc;
    private final ConfigParser parser;
    private final ApplicationEventPublisher events;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile State state;
    private volatile long lastSeenMtime = -1;
    private volatile long lastSeenSize = -1;
    private volatile WatchService watch;
    private volatile Thread watcher;

    public ConfigStore(JdbcClient jdbc, SecretStore secrets, ApplicationEventPublisher events,
                       @Value("${HUB_DATA_DIR:./data}") String dataDir,
                       @Value("${hub.config.file:}") String configured) {
        this.jdbc = jdbc;
        this.parser = new ConfigParser(secrets);
        this.events = events;
        this.file = configured == null || configured.isBlank() ? Path.of(dataDir, "config", "dashboard.yaml") : Path.of(configured);
    }

    public Path file() {
        return file;
    }

    public State state() {
        return state;
    }

    public ConfigParser.Result validate(String text) {
        return parser.parse(text);
    }

    // ---------------------------------------------------------------- startup

    @PostConstruct
    void start() throws IOException {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                String seed = defaultText();
                writeAtomically(seed);
                recordVersion(seed, "file", "initial default");
                log.info("Created a starter {}", file);
            }
            String text = readFile();
            ConfigParser.Result r = parser.parse(text);
            if (r.ok()) {
                state = new State(text, sha(text), r.config(), r.issues(), Health.OK, Instant.now());
                recordVersion(text, "file", "startup");
            } else {
                log.error("{} has errors and cannot be used. Serving the last good version. First problem: {}", file, firstError(r));
                state = fallbackState(text, r);
            }
        } finally {
            lock.unlock();
        }
        startWatcher();
    }

    private State fallbackState(String text, ConfigParser.Result bad) {
        for (var row : jdbc.sql("SELECT content FROM config_versions ORDER BY id DESC LIMIT " + KEEP_VERSIONS).query(String.class).list()) {
            ConfigParser.Result r = parser.parse(row);
            if (r.ok()) {
                return new State(text, sha(text), r.config(), bad.issues(), Health.INVALID_ON_DISK, Instant.now());
            }
        }
        ConfigParser.Result def = parser.parse(defaultText());
        return new State(text, sha(text), def.config(), bad.issues(), Health.USING_DEFAULT, Instant.now());
    }

    // ---------------------------------------------------------------- saving

    /** @param expectedSha the SHA-256 of the text the editor loaded (If-Match). */
    public State save(String text, String expectedSha, String source, String note) {
        String normalized = normalize(text);
        lock.lock();
        try {
            if (!state.sha256().equals(expectedSha)) {
                throw new ConflictException(state);
            }
            ConfigParser.Result r = parser.parse(normalized);
            if (!r.ok()) {
                throw new InvalidConfigException(r.issues());
            }
            if (sha(normalized).equals(state.sha256()) && state.health() == Health.OK) {
                return state;
            }
            try {
                writeAtomically(normalized);
            } catch (IOException e) {
                throw new IllegalStateException("Could not write " + file + ": " + e.getMessage(), e);
            }
            rememberFileStamp();
            recordVersion(normalized, source, note);
            state = new State(normalized, sha(normalized), r.config(), r.issues(), Health.OK, Instant.now());
            events.publishEvent(new ConfigChangedEvent(state));
            return state;
        } finally {
            lock.unlock();
        }
    }

    public State restore(long versionId, String expectedSha) {
        String content = jdbc.sql("SELECT content FROM config_versions WHERE id = ?").param(versionId).query(String.class).optional()
                .orElseThrow(() -> new java.util.NoSuchElementException("No such version"));
        return save(content, expectedSha, "restore", "restored version " + versionId);
    }

    public List<VersionInfo> versions() {
        return jdbc.sql("SELECT id, ts, sha256, source, note, length(CAST(content AS BLOB)) AS size FROM config_versions ORDER BY id DESC LIMIT " + KEEP_VERSIONS)
                .query((rs, n) -> new VersionInfo(rs.getLong("id"), rs.getString("ts"), rs.getString("sha256"), rs.getString("source"),
                        rs.getString("note"), rs.getInt("size")))
                .list();
    }

    public Optional<String> versionText(long id) {
        return jdbc.sql("SELECT content FROM config_versions WHERE id = ?").param(id).query(String.class).optional();
    }

    private void recordVersion(String text, String source, String note) {
        String sha = sha(text);
        Optional<String> latest = jdbc.sql("SELECT sha256 FROM config_versions ORDER BY id DESC LIMIT 1").query(String.class).optional();
        if (latest.isPresent() && latest.get().equals(sha)) {
            return;
        }
        jdbc.sql("INSERT INTO config_versions (sha256, content, source, note) VALUES (?,?,?,?)").params(sha, text, source, note).update();
        jdbc.sql("DELETE FROM config_versions WHERE id NOT IN (SELECT id FROM config_versions ORDER BY id DESC LIMIT " + KEEP_VERSIONS + ")").update();
    }

    // ---------------------------------------------------------------- following the file on disk

    /** Called by the watcher and by the polling fallback. Our own writes are recognised by their SHA and ignored. */
    void reloadFromDisk() {
        lock.lock();
        try {
            String text;
            try {
                text = readFile();
            } catch (IOException e) {
                log.warn("Could not read {}: {}", file, e.getMessage());
                return;
            }
            rememberFileStamp();
            String sha = sha(text);
            if (sha.equals(state.sha256())) {
                return;
            }
            ConfigParser.Result r = parser.parse(text);
            if (r.ok()) {
                recordVersion(text, "file", "changed on disk");
                state = new State(text, sha, r.config(), r.issues(), Health.OK, Instant.now());
                log.info("Reloaded {} after it changed on disk", file);
            } else {
                log.warn("{} was changed on disk but has errors; still serving the previous good version. First problem: {}", file, firstError(r));
                state = new State(text, sha, state.config(), r.issues(),
                        state.health() == Health.USING_DEFAULT ? Health.USING_DEFAULT : Health.INVALID_ON_DISK, Instant.now());
            }
            events.publishEvent(new ConfigChangedEvent(state));
        } finally {
            lock.unlock();
        }
    }

    /** Polling fallback: some bind mounts do not deliver inotify events (plan assumption A8). */
    @Scheduled(fixedDelayString = "${hub.config.poll-millis:10000}", initialDelayString = "${hub.config.poll-millis:10000}")
    void pollForChanges() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            long m = Files.getLastModifiedTime(file).toMillis();
            long s = Files.size(file);
            if (m != lastSeenMtime || s != lastSeenSize) {
                reloadFromDisk();
            }
        } catch (IOException e) {
            log.debug("poll failed: {}", e.getMessage());
        }
    }

    private void startWatcher() {
        try {
            watch = file.getFileSystem().newWatchService();
            file.getParent().register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("File watching is unavailable ({}); relying on polling every 10 s", e.getMessage());
            return;
        }
        Thread t = new Thread(this::watchLoop, "config-watcher");
        t.setDaemon(true);
        t.start();
        watcher = t;
    }

    private void watchLoop() {
        String name = file.getFileName().toString();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watch.take();
                boolean relevant = false;
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.context() instanceof Path p && p.toString().equals(name)) {
                        relevant = true;
                    }
                }
                key.reset();
                if (relevant) {
                    TimeUnit.MILLISECONDS.sleep(300); // let an editor finish its write-and-rename dance
                    WatchKey more;
                    while ((more = watch.poll()) != null) {
                        more.pollEvents();
                        more.reset();
                    }
                    try {
                        reloadFromDisk();
                    } catch (RuntimeException e) {
                        log.warn("Reload failed: {}", e.toString());
                    }
                }
            }
        } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
            // shutting down
        }
    }

    @PreDestroy
    void stop() throws IOException {
        if (watcher != null) {
            watcher.interrupt();
        }
        if (watch != null) {
            watch.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String readFile() throws IOException {
        long size = Files.size(file);
        if (size > 2L * YamlTree.MAX_CHARS) {
            return "# file too large: " + size + " bytes\n" + "x".repeat(YamlTree.MAX_CHARS + 1);
        }
        // Lenient decoding: invalid bytes become U+FFFD, which the parser then rejects with a normal error. Strict decoding
        // would throw, and a reload that throws leaves the dashboard claiming the file is healthy when it is not.
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private void rememberFileStamp() {
        try {
            lastSeenMtime = Files.getLastModifiedTime(file).toMillis();
            lastSeenSize = Files.size(file);
        } catch (IOException ignored) {
            // will simply trigger a reload on the next poll
        }
    }

    private void writeAtomically(String text) throws IOException {
        Path dir = file.getParent();
        Path tmp = Files.createTempFile(dir, ".dashboard-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not POSIX
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        rememberFileStamp();
    }

    private static String normalize(String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n");
        return t.endsWith("\n") ? t : t + "\n";
    }

    private static String defaultText() {
        try (InputStream in = ConfigStore.class.getResourceAsStream("/default-dashboard.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            return "version: 1\ngroups: []\n";
        }
    }

    private static String firstError(ConfigParser.Result r) {
        return r.errors().isEmpty() ? "unknown" : "line " + r.errors().get(0).line() + ": " + r.errors().get(0).message();
    }

    static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
