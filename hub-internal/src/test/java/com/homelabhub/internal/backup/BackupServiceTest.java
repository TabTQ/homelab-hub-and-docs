package com.homelabhub.internal.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class BackupServiceTest {

    @TempDir Path dir;
    JdbcClient jdbc;
    AtomicLong clockSeconds = new AtomicLong(1_790_000_000L);
    Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(clockSeconds.get()); }
    };

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("live.db") + "?foreign_keys=on&journal_mode=WAL", true);
        ds.setSuppressClose(true);
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO profile (id, name, title) VALUES (1, 'Backed up', 'T')").update();
    }

    private BackupService service(int keep) {
        return new BackupService(jdbc, clock, dir.toString(), dir.resolve("backups").toString(), keep);
    }

    @Test
    void producesAConsistentCopyThatOpensAndContainsTheData() throws Exception {
        Path b = service(14).backupNow();
        assertThat(b.getFileName().toString()).matches("hub-\\d{8}-\\d{6}\\.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + b); var rs = c.createStatement().executeQuery("SELECT name FROM profile")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Backed up");
        }
    }

    @Test
    void theLiveDatabaseKeepsWorkingAndTheBackupIsASnapshotInTime() throws Exception {
        Path b = service(14).backupNow();
        jdbc.sql("UPDATE profile SET name = 'Changed later' WHERE id = 1").update();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + b); var rs = c.createStatement().executeQuery("SELECT name FROM profile")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("Backed up");
        }
    }

    @Test
    void onlyTheNewestFewAreKept() throws Exception {
        BackupService s = service(3);
        for (int i = 0; i < 6; i++) {
            clockSeconds.addAndGet(86_400);
            s.backupNow();
        }
        assertThat(s.list()).hasSize(3);
        var names = s.list().stream().map(p -> p.getFileName().toString()).toList();
        assertThat(names).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void twoBackupsInTheSameSecondDoNotClash() throws Exception {
        BackupService s = service(14);
        Path a = s.backupNow();
        Path b = s.backupNow();
        assertThat(a).isNotEqualTo(b);
        assertThat(s.list()).hasSize(2);
    }

    @Test
    void aPathWithAQuoteInItIsHandledLiterally() throws Exception {
        Path odd = dir.resolve("it's here");
        Path b = new BackupService(jdbc, clock, dir.toString(), odd.toString(), 5).backupNow();
        assertThat(b).exists().hasParent(odd);
    }

    @Test
    void listIgnoresFilesThatAreNotBackups() throws Exception {
        BackupService s = service(14);
        s.backupNow();
        Files.writeString(dir.resolve("backups").resolve("notes.txt"), "x");
        Files.writeString(dir.resolve("backups").resolve("hub-evil.db"), "x");
        assertThat(s.list()).hasSize(1);
    }
}
