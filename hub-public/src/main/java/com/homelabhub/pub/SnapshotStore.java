package com.homelabhub.pub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.snapshot.SnapshotEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    private static final String FILE_NAME = "snapshot.json";
    static final int MAX_BYTES = 5 * 1024 * 1024;

    public record Snapshot(byte[] bytes, String etag) {}

    private final Path file;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private final Set<String> warned = new HashSet<>();
    private CachedSnapshot cache;

    private record CachedSnapshot(long lastModified, long size, byte[] bytes, String etag) {}

    public SnapshotStore(@Value("${hub.snapshot.dir:./snapshot}") String dir) {
        this.file = Path.of(dir, FILE_NAME);
        this.mapper = new ObjectMapper();
    }

    public Optional<Snapshot> current() {
        long lastModified;
        long size;
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                warnOnce("snapshot file is not a regular file");
                return cached();
            }
            lastModified = attrs.lastModifiedTime().to(TimeUnit.MILLISECONDS);
            size = attrs.size();
        } catch (IOException e) {
            warnOnce("snapshot file is missing or unreadable");
            return cached();
        }

        if (isCurrent(lastModified, size)) {
            return cached();
        }

        if (size > MAX_BYTES) {
            warnOnce("snapshot file exceeds " + MAX_BYTES + " bytes");
            return cached();
        }
        reload(lastModified, size);
        return cached();
    }

    private Optional<Snapshot> cached() {
        synchronized (lock) {
            if (cache == null) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(cache.bytes(), cache.etag()));
        }
    }

    private boolean isCurrent(long lastModified, long size) {
        synchronized (lock) {
            return cache != null && cache.lastModified() == lastModified && cache.size() == size;
        }
    }

    private void reload(long lastModified, long size) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            warnOnce("failed to read snapshot file");
            return;
        }

        SnapshotEnvelope envelope;
        try {
            envelope = mapper.readValue(bytes, SnapshotEnvelope.class);
        } catch (Exception e) {
            warnOnce("snapshot file is not valid JSON");
            return;
        }

        if (envelope == null) {
            warnOnce("snapshot file is empty or a JSON null");
            return;
        }
        if (envelope.schemaVersion() != SnapshotEnvelope.CURRENT_SCHEMA_VERSION) {
            warnOnce("snapshot schemaVersion " + envelope.schemaVersion()
                    + " does not match supported " + SnapshotEnvelope.CURRENT_SCHEMA_VERSION);
            return;
        }
        if (envelope.data() == null) {
            warnOnce("snapshot data is null");
            return;
        }

        String etag = computeEtag(bytes);
        synchronized (lock) {
            cache = new CachedSnapshot(lastModified, size, bytes, etag);
            warned.clear(); // a later, different failure should be logged again
        }
    }

    private static String computeEtag(byte[] bytes) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        String hex = HexFormat.of().formatHex(sha256.digest(bytes));
        return "\"" + hex.substring(0, 16) + "\"";
    }

    private void warnOnce(String message) {
        synchronized (lock) {
            if (warned.add(message)) {
                log.warn("SnapshotStore({}): {}", file, message);
            }
        }
    }

    void reset() {
        synchronized (lock) {
            cache = null;
            warned.clear();
        }
    }
}