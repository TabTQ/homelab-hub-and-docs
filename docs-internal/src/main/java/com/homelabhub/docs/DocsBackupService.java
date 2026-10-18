package com.homelabhub.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly online backup of the SQLite database with `VACUUM INTO` (a consistent copy while the app keeps running),
 * keeping the newest {@code docs.backup.keep} files (docs plan 5.2). The target path is built here, never from user input.
 * Only the database: the blobs folder is not in this copy, so include DOCS_DATA in the Proxmox backup of the LXC.
 */
@Service
public class DocsBackupService {

    private static final Logger log = LoggerFactory.getLogger(DocsBackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Path dir;
    private final int keep;

    public DocsBackupService(JdbcClient jdbc, Clock clock, @Value("${docs.data-dir:./data}") String dataDir,
                         @Value("${docs.backup.dir:}") String dir, @Value("${docs.backup.keep:14}") int keep) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.dir = dir == null || dir.isBlank() ? Path.of(dataDir, "backups") : Path.of(dir);
        this.keep = Math.max(1, keep);
    }

    @Scheduled(cron = "${docs.backup.cron:0 40 3 * * *}")
    void nightly() {
        try {
            backupNow();
        } catch (RuntimeException | IOException e) {
            log.error("Nightly backup failed: {}", e.toString());
        }
    }

    public synchronized Path backupNow() throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve("docs-" + STAMP.format(clock.instant()) + ".db");
        int n = 1;
        while (Files.exists(target)) { // two backups in one second
            target = dir.resolve("docs-" + STAMP.format(clock.instant()) + "-" + n++ + ".db");
        }
        jdbc.sql("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'").update();
        prune();
        log.info("Database backed up to {}", target);
        return target;
    }

    public List<Path> list() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("docs-\\d{8}-\\d{6}(-\\d+)?\\.db"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
    }

    private void prune() throws IOException {
        List<Path> all = list();
        for (int i = keep; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i));
        }
    }
}
