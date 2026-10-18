package com.homelabhub.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    public static final int SCHEMA_VERSION = 1;

    public record Status(boolean upToDate, Instant lastPublished, String lastError, Instant lastAttempt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final BlobStore blobs;
    private final Path dir;
    private final Duration gcDelay;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Path, Instant> garbageSince = new HashMap<>();
    private volatile Status status = new Status(false, null, "not published yet", null);

    /** Test hook: throw after this step (1 = blobs, 2 = page files, 3 = hosts.json) to simulate a crash. */
    volatile int failAfterStep = 0;

    public DocsPublisher(JdbcClient jdbc, ObjectMapper json, Clock clock, BlobStore blobs, DocsProperties props) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.blobs = blobs;
        this.dir = Path.of(props.snapshotDir());
        this.gcDelay = Duration.ofSeconds(props.gcDelaySeconds());
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
                write(build(), now);
                status = new Status(true, now, null, now);
                return true;
            } catch (IOException | RuntimeException e) {
                log.error("Could not update the public docs in {}: {}", dir, e.toString());
                status = new Status(false, status.lastPublished(), "The public site could not be updated: " + e.getClass().getSimpleName(), now);
                return false;
            }
        } finally {
            lock.unlock();
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

    private record Live(long id, String kind, String title, String host, String body, Long bundleId, String publishedAt) {}

    private record Bundle(String sha, JsonNode manifest, JsonNode settings) {}

    Build build() {
        List<Live> live = jdbc.sql("""
                SELECT p.id, p.kind, v.title, p.public_host AS host, v.body_json AS body, v.bundle_id, p.published_at
                FROM doc_pages p JOIN doc_page_versions v ON v.page_id = p.id AND v.version = p.published_version
                WHERE p.deleted_at IS NULL AND p.public_host IS NOT NULL AND p.published_version IS NOT NULL
                """).query(Live.class).list();
        Map<Long, String> hostOf = new HashMap<>();
        live.forEach(l -> hostOf.put(l.id(), l.host()));
        Map<Long, ObjectNode> pages = new LinkedHashMap<>();
        Map<String, Long> hosts = new LinkedHashMap<>();
        Set<String> needed = new HashSet<>();
        for (Live l : live) {
            ObjectNode page = json.createObjectNode();
            page.put("schemaVersion", SCHEMA_VERSION);
            page.put("kind", l.kind());
            page.put("title", l.title());
            page.put("updatedAt", l.publishedAt());
            ObjectNode embeds = json.createObjectNode();
            ObjectNode assets = json.createObjectNode();
            JsonNode body = read(l.body());
            if (l.kind().equals("rich")) {
                page.set("body", resolve(body, hostOf, embeds, assets, needed));
            } else {
                ObjectNode settings = ((ObjectNode) body.deepCopy());
                JsonNode intro = settings.remove("intro");
                page.set("settings", settings);
                page.set("intro", intro == null || intro.isNull() ? null : resolve(intro, hostOf, embeds, assets, needed));
                Optional<Bundle> b = bundleById(l.bundleId());
                if (b.isEmpty()) {
                    continue; // a webapp without its app cannot be shown
                }
                page.set("bundle", bundleJson(b.get(), needed));
            }
            page.set("embeds", embeds);
            page.set("assets", assets);
            pages.put(l.id(), page);
            hosts.put(l.host(), l.id());
        }
        return new Build(pages, hosts, needed);
    }

    /** Copies a rich body for the public: links resolved (D6), embeds resolved to bundles, other page ids removed. */
    private JsonNode resolve(JsonNode node, Map<Long, String> hostOf, ObjectNode embeds, ObjectNode assets, Set<String> needed) {
        if (node.isArray()) {
            ArrayNode out = json.createArrayNode();
            for (JsonNode c : node) {
                JsonNode r = resolve(c, hostOf, embeds, assets, needed);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        }
        if (!node.isObject()) {
            return node.deepCopy();
        }
        String type = node.path("type").asText();
        JsonNode a = node.path("attrs");
        switch (type) {
            case "pageLink" -> {
                ObjectNode out = json.createObjectNode().put("type", "pageLink");
                ObjectNode attrs = out.putObject("attrs").put("label", a.path("label").asText());
                String host = hostOf.get(a.path("pageId").asLong());
                if (host != null) {
                    attrs.put("href", "https://" + host + "/");
                }
                return out;
            }
            case "webappEmbed" -> {
                Optional<Bundle> b = embedBundle(a.path("pageId").asLong());
                if (b.isEmpty()) {
                    return null;
                }
                if (!embeds.has(b.get().sha())) {
                    ObjectNode e = json.createObjectNode();
                    e.set("settings", publicSettings(b.get().settings()));
                    e.set("bundle", bundleJson(b.get(), needed));
                    embeds.set(b.get().sha(), e);
                }
                ObjectNode out = json.createObjectNode().put("type", "webappEmbed");
                ObjectNode attrs = out.putObject("attrs").put("bundle", b.get().sha());
                if (a.has("height")) {
                    attrs.put("height", a.get("height").asInt());
                }
                return out;
            }
            default -> {
                ObjectNode out = json.createObjectNode();
                node.fields().forEachRemaining(f -> {
                    if (f.getKey().equals("content")) {
                        out.set("content", resolve(f.getValue(), hostOf, embeds, assets, needed));
                    } else {
                        out.set(f.getKey(), f.getValue().deepCopy());
                    }
                });
                for (String key : List.of("sha", "lightSvg", "darkSvg")) {
                    String sha = a.path(key).asText(null);
                    if (sha != null && BlobStore.isSha(sha)) {
                        addAsset(sha, assets, needed);
                    }
                }
                return out;
            }
        }
    }

    private void addAsset(String sha, ObjectNode assets, Set<String> needed) {
        if (assets.has(sha)) {
            return;
        }
        jdbc.sql("SELECT mime, size, original_name FROM doc_assets WHERE sha256 = ?").param(sha).query((rs, i) -> {
            assets.putObject(sha).put("mime", rs.getString(1)).put("size", rs.getLong(2)).put("name", rs.getString(3));
            needed.add(sha);
            return 0;
        }).list();
    }

    private ObjectNode bundleJson(Bundle b, Set<String> needed) {
        ObjectNode out = json.createObjectNode().put("sha", b.sha());
        ObjectNode files = out.putObject("files");
        for (JsonNode f : b.manifest()) {
            files.putObject(f.path("path").asText()).put("sha", f.path("sha256").asText()).put("mime", f.path("mime").asText())
                    .put("size", f.path("size").asLong());
            needed.add(f.path("sha256").asText());
        }
        return out;
    }

    /** An embed only carries what the frame needs; the intro of the embedded page is not part of the embedding page. */
    private ObjectNode publicSettings(JsonNode settings) {
        ObjectNode s = settings.deepCopy();
        s.remove("intro");
        return s;
    }

    private Optional<Bundle> bundleById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT b.sha256, b.manifest_json, p.draft_json FROM doc_bundles b JOIN doc_pages p ON p.id = b.page_id WHERE b.id = ?")
                .param(id).query((rs, i) -> new Bundle(rs.getString(1), read(rs.getString(2)), read(rs.getString(3)))).optional();
    }

    /** The embedded page's published bundle (with its published settings), else its draft bundle; never from the Trash. */
    private Optional<Bundle> embedBundle(long pageId) {
        Optional<Bundle> published = jdbc.sql("""
                SELECT b.sha256, b.manifest_json, v.body_json FROM doc_pages p
                JOIN doc_page_versions v ON v.page_id = p.id AND v.version = p.published_version
                JOIN doc_bundles b ON b.id = v.bundle_id
                WHERE p.id = ? AND p.kind = 'webapp' AND p.deleted_at IS NULL
                """).param(pageId).query((rs, i) -> new Bundle(rs.getString(1), read(rs.getString(2)), read(rs.getString(3)))).optional();
        if (published.isPresent()) {
            return published;
        }
        return jdbc.sql("""
                SELECT b.sha256, b.manifest_json, p.draft_json FROM doc_pages p JOIN doc_bundles b ON b.id = p.draft_bundle
                WHERE p.id = ? AND p.kind = 'webapp' AND p.deleted_at IS NULL
                """).param(pageId).query((rs, i) -> new Bundle(rs.getString(1), read(rs.getString(2)), read(rs.getString(3)))).optional();
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException("corrupt JSON in the database", e);
        }
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
