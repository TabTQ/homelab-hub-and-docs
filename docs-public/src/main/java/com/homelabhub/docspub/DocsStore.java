package com.homelabhub.docspub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the docs snapshot that docs-internal writes (modelled on hub-public's SnapshotStore). hosts.json (at most 1 MB) and
 * the page files (at most 2 MB each, the 50 most recently used kept in memory) are reloaded when their modification time
 * or size changes. A file that is missing, too large or not valid JSON never replaces the last good copy, and each
 * problem is logged once.
 */
@Component
public class DocsStore {

    private static final Logger log = LoggerFactory.getLogger(DocsStore.class);
    static final int MAX_HOSTS_BYTES = 1024 * 1024;
    static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    static final int CACHED_PAGES = 50;
    static final int SCHEMA_VERSION = 1;

    /** A published page: its bytes as served, its ETag, and the parsed tree for the asset and bundle checks. */
    public record Page(long id, byte[] bytes, String etag, JsonNode tree) {}

    private record Cached<T>(long modified, long size, T value) {}

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();
    private final Set<String> warned = new HashSet<>();
    private Cached<Map<String, Long>> hosts;
    private final Map<Long, Cached<Page>> pages = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Cached<Page>> eldest) {
            return size() > CACHED_PAGES;
        }
    };

    public DocsStore(@Value("${docs.snapshot.dir:./snapshot}") String dir) {
        this.dir = Path.of(dir);
    }

    public Path blobs() {
        return dir.resolve("blobs");
    }

    /** Lowercase, no port, no trailing dot. Null when the header is not a plausible hostname. */
    public static String normalizeHost(String header) {
        if (header == null || header.isEmpty() || header.length() > 260) {
            return null;
        }
        String h = header.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            return null; // IPv6 literal: never a page host
        }
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.')) {
                return null;
            }
        }
        return h.isEmpty() ? null : h;
    }

    public synchronized boolean hasSnapshot() {
        return currentHosts() != null;
    }

    public synchronized Optional<Page> pageFor(String host) {
        if (host == null) {
            return Optional.empty();
        }
        Map<String, Long> map = currentHosts();
        Long id = map == null ? null : map.get(host);
        return id == null ? Optional.empty() : Optional.ofNullable(page(id));
    }

    private Map<String, Long> currentHosts() {
        Path f = dir.resolve("hosts.json");
        BasicFileAttributes a = attrs(f);
        if (a == null) {
            warnOnce("hosts.json is missing or unreadable");
            return hosts == null ? null : hosts.value();
        }
        long modified = a.lastModifiedTime().to(TimeUnit.MILLISECONDS);
        if (hosts != null && hosts.modified() == modified && hosts.size() == a.size()) {
            return hosts.value();
        }
        if (a.size() > MAX_HOSTS_BYTES) {
            warnOnce("hosts.json is larger than " + MAX_HOSTS_BYTES + " bytes");
            return hosts == null ? null : hosts.value();
        }
        try {
            JsonNode root = json.readTree(Files.readAllBytes(f));
            if (root == null || root.path("schemaVersion").asInt() != SCHEMA_VERSION || !root.path("hosts").isObject()) {
                warnOnce("hosts.json has an unsupported format");
                return hosts == null ? null : hosts.value();
            }
            Map<String, Long> map = new LinkedHashMap<>();
            root.path("hosts").fields().forEachRemaining(e -> {
                long id = e.getValue().path("page").asLong(-1);
                String host = normalizeHost(e.getKey());
                if (id > 0 && host != null && host.equals(e.getKey())) {
                    map.put(host, id);
                }
            });
            hosts = new Cached<>(modified, a.size(), Map.copyOf(map));
            warned.clear();
            return hosts.value();
        } catch (IOException e) {
            warnOnce("hosts.json is not valid JSON");
            return hosts == null ? null : hosts.value();
        }
    }

    private Page page(long id) {
        Path f = dir.resolve("pages").resolve(id + ".json");
        Cached<Page> cached = pages.get(id);
        BasicFileAttributes a = attrs(f);
        if (a == null) {
            warnOnce("page file " + id + " is missing");
            return cached == null ? null : cached.value();
        }
        long modified = a.lastModifiedTime().to(TimeUnit.MILLISECONDS);
        if (cached != null && cached.modified() == modified && cached.size() == a.size()) {
            return cached.value();
        }
        if (a.size() > MAX_PAGE_BYTES) {
            warnOnce("page file " + id + " is larger than " + MAX_PAGE_BYTES + " bytes");
            return cached == null ? null : cached.value();
        }
        try {
            byte[] bytes = Files.readAllBytes(f);
            JsonNode tree = json.readTree(bytes);
            if (tree == null || tree.path("schemaVersion").asInt() != SCHEMA_VERSION) {
                warnOnce("page file " + id + " has an unsupported format");
                return cached == null ? null : cached.value();
            }
            Page p = new Page(id, bytes, etag(bytes), tree);
            pages.put(id, new Cached<>(modified, a.size(), p));
            return p;
        } catch (IOException e) {
            warnOnce("page file " + id + " is not valid JSON");
            return cached == null ? null : cached.value();
        }
    }

    private static BasicFileAttributes attrs(Path f) {
        try {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
            return a.isRegularFile() ? a : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String etag(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + java.util.HexFormat.of().formatHex(d).substring(0, 16) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void warnOnce(String message) {
        if (warned.add(message)) {
            log.warn("DocsStore({}): {}", dir, message);
        }
    }
}
