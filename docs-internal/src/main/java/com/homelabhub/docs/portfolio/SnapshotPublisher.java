package com.homelabhub.docs.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.snapshot.SnapshotEnvelope;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Writes the public snapshot (plan 5.2): a sanitized copy of the portfolio that the public container reads. The write is
 * temp file + fsync + atomic rename, so the reader can never see a half-written file. If it fails, the database change
 * still stands, the failure is remembered and retried, and the editor is told the public site is out of date.
 */
@Service
public class SnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPublisher.class);

    public record Status(boolean upToDate, Instant lastPublished, String lastError, Instant lastAttempt) {}

    private final PortfolioReader reader;
    private final Clock clock;
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Status status = new Status(false, null, "not published yet", null);

    public SnapshotPublisher(PortfolioReader reader, Clock clock, @Value("${docs.portfolio.snapshot-dir:./portfolio-snapshot}") String dir) {
        this.reader = reader;
        this.clock = clock;
        this.file = Path.of(dir == null || dir.isBlank() ? "./portfolio-snapshot" : dir).resolve("snapshot.json");
    }

    public Path file() {
        return file;
    }

    public Status status() {
        return status;
    }

    @PostConstruct
    void startup() {
        if (reader.hasProfile() || !Files.exists(file)) {
            publish();
        }
    }

    /** @return true when the snapshot on disk now matches the database */
    public boolean publish() {
        lock.lock();
        try {
            Instant now = clock.instant();
            try {
                Map<String, Object> data = reader.read();
                SnapshotEnvelope env = new SnapshotEnvelope(SnapshotEnvelope.CURRENT_SCHEMA_VERSION, now.toString(), PortfolioReader.toTree(data));
                byte[] bytes = json.writeValueAsBytes(env);
                writeAtomically(bytes);
                status = new Status(true, now, null, now);
                return true;
            } catch (IOException | RuntimeException e) {
                log.error("Could not publish the public snapshot to {}: {}", file, e.toString());
                status = new Status(false, status.lastPublished(), "The public site could not be updated: " + e.getClass().getSimpleName(), now);
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /** A failed publish is retried until it works, so an edit is never silently left off the public site. */
    @Scheduled(fixedDelayString = "${docs.portfolio.retry-millis:30000}", initialDelayString = "${docs.portfolio.retry-millis:30000}")
    void retryIfStale() {
        if (!status.upToDate() && status.lastAttempt() != null) {
            publish();
        }
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".snapshot-", ".tmp");
        try {
            try {
                // The public container may run as another user, so this file (not the config) is world-readable.
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // not POSIX
            }
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

    public String describe() {
        return status.upToDate() ? "Public site is up to date (" + status.lastPublished() + ")" : String.valueOf(status.lastError());
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
