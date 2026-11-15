package com.homelabhub.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homelabhub.docs.cloudflare.CloudflareRoutes;
import com.homelabhub.docs.github.GitHubSync;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Writes the docs snapshot that docs-public reads (plan 5.4), modelled on the hub's SnapshotPublisher. Every run rebuilds
 * the public side from the database, so a hostname change or unpublish automatically re-renders the pages linking to it.
 *
 * <p>Write order, so a reader never sees a page that references a missing file:
 * <ol>
 *   <li>copy the missing blobs (content-addressed: writing one twice is harmless);</li>
 *   <li>write pages/&lt;id&gt;.json (temp file + fsync + atomic rename);</li>
 *   <li>write hosts.json last: the switch that makes pages live or takes them offline;</li>
 *   <li>delete page files and blobs nothing refers to any more, but only once they have been garbage for at least
 *       {@code docs.gc-delay-seconds}, so requests in flight can finish.</li>
 * </ol>
 * On failure the database change stands, the status says the public site is out of date, and a retry runs every 30 s.
 */
@Service
public class DocsPublisher {

    private static final Logger log = LoggerFactory.getLogger(DocsPublisher.class);
    public static final int SCHEMA_VERSION = PageRenderer.SCHEMA_VERSION;

    public record Status(boolean upToDate, Instant lastPublished, String lastError, Instant lastAttempt) {}

    private final ObjectMapper json;
    private final Clock clock;
    private final BlobStore blobs;
    private final Path dir;
    private final Duration gcDelay;
    private final CloudflareRoutes routes;
    private final PageRenderer renderer;
    private final GitHubSync github;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Path, Instant> garbageSince = new HashMap<>();
    private volatile Status status = new Status(false, null, "not published yet", null);

    /** Test hook: throw after this step (1 = blobs, 2 = page files, 3 = hosts.json) to simulate a crash. */
    volatile int failAfterStep = 0;

    public DocsPublisher(PageRenderer renderer, ObjectMapper json, Clock clock, BlobStore blobs, DocsProperties props,
                          CloudflareRoutes routes, GitHubSync github) {
        this.renderer = renderer;
        this.github = github;
        this.json = json;
        this.clock = clock;
        this.blobs = blobs;
        this.dir = Path.of(props.snapshotDir());
        this.gcDelay = Duration.ofSeconds(props.gcDelaySeconds());
        this.routes = routes;
    }

    public Path dir() {
        return dir;
    }

    public Status status() {
        return status;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startup() {
        publish();
    }

    /** @return true when the public side now matches the database */
    public boolean publish() {
        lock.lock();
        try {
            Instant now = clock.instant();
            try {
                Build b = build();
                write(b, now);
                status = new Status(true, now, null, now);
                routes.requestSync(b.hosts().keySet()); // queued: a Cloudflare problem never fails the snapshot
                return true;
            } catch (IOException | RuntimeException e) {
                log.error("Could not update the public docs in {}: {}", dir, e.toString());
                status = new Status(false, status.lastPublished(), "The public site could not be updated: " + e.getClass().getSimpleName(), now);
                return false;
            }
        } finally {
            lock.unlock();
            github.requestSync(); // queued, never throws: a GitHub problem never fails or delays the local publish
        }
    }

    /** Retries a failed publish, and finishes delayed clean-up once its waiting time is over. */
    @Scheduled(fixedDelayString = "${docs.publish-retry-millis:30000}", initialDelayString = "${docs.publish-retry-millis:30000}")
    void retryIfStale() {
        if (!status.upToDate() && status.lastAttempt() != null) {
            publish();
        } else if (!garbageSince.isEmpty()) {
            lock.lock();
            try {
                collect(currentReferences(), clock.instant());
            } catch (IOException | RuntimeException e) {
                log.warn("Docs snapshot clean-up failed: {}", e.toString());
            } finally {
                lock.unlock();
            }
        }
    }

    // ---------------------------------------------------------------- build

    /** Everything that should be public: page id → page JSON, host → page id, and the blobs they need. */
    record Build(Map<Long, ObjectNode> pages, Map<String, Long> hosts, Set<String> blobs) {}

    Build build() {
        Map<Long, ObjectNode> pages = new LinkedHashMap<>();
        Map<String, Long> hosts = new LinkedHashMap<>();
        Set<String> needed = new HashSet<>();
        for (PageRenderer.Rendered r : renderer.render(PageRenderer.Mode.LOCAL)) {
            pages.put(r.id(), r.page());
            hosts.put(r.host(), r.id());
            needed.addAll(r.blobs());
        }
        return new Build(pages, hosts, needed);
    }

    // ---------------------------------------------------------------- write

    void write(Build b, Instant now) throws IOException {
        Files.createDirectories(dir.resolve("pages"));
        Path blobDir = dir.resolve("blobs");
        // 1. blobs
        for (String sha : b.blobs()) {
            if (!blobs.exists(sha)) {
                throw new IOException("a file the public page needs is missing from the store: " + sha);
            }
            blobs.copyTo(sha, blobDir);
        }
        step(1);
        // 2. page files (rewritten only when their content changed)
        Map<String, Object> hostEntries = new LinkedHashMap<>();
        for (Map.Entry<Long, ObjectNode> e : b.pages().entrySet()) {
            byte[] bytes = json.writeValueAsBytes(e.getValue());
            Path f = dir.resolve("pages").resolve(e.getKey() + ".json");
            if (!Files.exists(f) || !Arrays.equals(Files.readAllBytes(f), bytes)) {
                writeAtomically(f, bytes);
            }
            hostEntries.put("p" + e.getKey(), BlobStore.sha256Hex(bytes).substring(0, 16));
        }
        step(2);
        // 3. hosts.json: the switch
        ObjectNode hosts = json.createObjectNode();
        hosts.put("schemaVersion", SCHEMA_VERSION);
        ObjectNode map = hosts.putObject("hosts");
        for (Map.Entry<String, Long> h : b.hosts().entrySet()) {
            map.putObject(h.getKey()).put("page", h.getValue()).put("etag", (String) hostEntries.get("p" + h.getValue()));
        }
        byte[] hostBytes = json.writeValueAsBytes(hosts);
        Path hostsFile = dir.resolve("hosts.json");
        if (!Files.exists(hostsFile) || !Arrays.equals(Files.readAllBytes(hostsFile), hostBytes)) {
            writeAtomically(hostsFile, hostBytes);
        }
        step(3);
        // 4. delayed clean-up
        collect(new Refs(b.pages().keySet(), b.blobs()), now);
    }

    private void step(int n) throws IOException {
        if (failAfterStep == n) {
            throw new IOException("simulated crash after step " + n);
        }
    }

    record Refs(Set<Long> pages, Set<String> blobs) {}

    /** What the current hosts.json refers to (used by the clean-up between publishes). */
    private Refs currentReferences() throws IOException {
        Build b = build();
        return new Refs(b.pages().keySet(), b.blobs());
    }

    private void collect(Refs refs, Instant now) throws IOException {
        Set<Path> garbage = new HashSet<>();
        Path pagesDir = dir.resolve("pages");
        if (Files.isDirectory(pagesDir)) {
            try (Stream<Path> s = Files.list(pagesDir)) {
                s.filter(p -> p.getFileName().toString().matches("\\d+\\.json"))
                        .filter(p -> !refs.pages().contains(Long.parseLong(p.getFileName().toString().replace(".json", ""))))
                        .forEach(garbage::add);
            }
        }
        Path blobDir = dir.resolve("blobs");
        if (Files.isDirectory(blobDir)) {
            try (Stream<Path> s = Files.walk(blobDir, 2)) {
                s.filter(Files::isRegularFile).filter(p -> BlobStore.isSha(p.getFileName().toString()))
                        .filter(p -> !refs.blobs().contains(p.getFileName().toString())).forEach(garbage::add);
            }
        }
        garbageSince.keySet().retainAll(garbage);
        for (Path g : garbage) {
            Instant since = garbageSince.computeIfAbsent(g, k -> now);
            if (!since.plus(gcDelay).isAfter(now)) {
                Files.deleteIfExists(g);
                garbageSince.remove(g);
            }
        }
    }

    /** Files waiting for their clean-up delay (for tests and the status line). */
    int pendingGarbage() {
        return garbageSince.size();
    }

    static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".w-", ".tmp");
        try {
            BlobStore.worldReadable(tmp);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(bytes));
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
    }
}
