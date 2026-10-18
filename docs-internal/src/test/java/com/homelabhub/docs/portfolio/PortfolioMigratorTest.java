package com.homelabhub.docs.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortfolioMigratorTest {

    @TempDir Path dir;
    Path hubDb;
    Path data;

    @BeforeEach
    void setUp() throws Exception {
        hubDb = HubDb.withPortfolio(dir.resolve("hub-backup.db"));
        data = dir.resolve("data");
        Files.createDirectories(data);
    }

    private Connection docs() throws Exception {
        Path f = data.resolve("docs.db");
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + f + "?foreign_keys=on");
        Flyway.configure().dataSource(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true)).load().migrate();
        return c;
    }

    private static int count(Connection c, String t) throws Exception {
        try (var rs = c.createStatement().executeQuery("SELECT count(*) FROM " + t)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /** every row of a table as text, ordered by all columns, to compare source and target exactly */
    private static List<String> rows(Connection c, String t) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (var rs = c.createStatement().executeQuery("SELECT * FROM " + t + " ORDER BY 1, 2")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(rs.getMetaData().getColumnName(i)).append('=').append(rs.getObject(i)).append('|');
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    @Test
    void copiesEveryTableAndColumnWithIdsAndVersionsKept() throws Exception {
        try (Connection target = docs(); Connection src = DriverManager.getConnection("jdbc:sqlite:" + hubDb)) {
            var report = PortfolioMigrator.migrate(hubDb, target, false, false);
            assertThat(report.copied()).isEqualTo(report.source());
            for (String t : PortfolioImporter.tablesInInsertOrder()) {
                assertThat(rows(target, t)).as(t).isEqualTo(rows(src, t));
            }
            assertThat(count(target, "experiences")).isPositive();
            assertThat(count(target, "device_skills") + count(target, "container_skills") + count(target, "service_skills")).isPositive();
            try (var rs = target.createStatement().executeQuery("SELECT max(version) FROM skills")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(7);
            }
        }
    }

    @Test
    void theSourceFileIsNeverChanged() throws Exception {
        String before = sha(hubDb);
        try (Connection target = docs()) {
            PortfolioMigrator.migrate(hubDb, target, false, false);
            PortfolioMigrator.migrate(hubDb, target, true, true);
        }
        assertThat(sha(hubDb)).isEqualTo(before);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).as("no -wal/-shm/-journal left beside the source").doesNotContain("hub-backup.db-journal");
        }
    }

    @Test
    void aTargetThatAlreadyHasPortfolioRowsIsRefusedUnlessReplaceIsGiven() throws Exception {
        try (Connection target = docs()) {
            PortfolioMigrator.migrate(hubDb, target, false, false);
            int before = count(target, "experiences");
            assertThatThrownBy(() -> PortfolioMigrator.migrate(hubDb, target, false, false))
                    .isInstanceOf(PortfolioMigrator.MigrateException.class).hasMessageContaining("already has rows").hasMessageContaining("--replace");
            assertThat(count(target, "experiences")).isEqualTo(before);
            target.createStatement().executeUpdate("UPDATE experiences SET role = 'edited in Docs'");
            PortfolioMigrator.migrate(hubDb, target, true, false);
            try (var rs = target.createStatement().executeQuery("SELECT count(*) FROM experiences WHERE role = 'edited in Docs'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("--replace puts the source back").isZero();
            }
            assertThat(count(target, "experiences")).isEqualTo(before);
        }
    }

    @Test
    void aDryRunReportsButSavesNothing() throws Exception {
        try (Connection target = docs()) {
            var report = PortfolioMigrator.migrate(hubDb, target, false, true);
            assertThat(report.dryRun()).isTrue();
            assertThat(report.copied().get("experiences")).isPositive();
            for (String t : PortfolioImporter.tablesInInsertOrder()) {
                assertThat(count(target, t)).as(t).isZero();
            }
            assertThat(target.getAutoCommit()).isTrue();
        }
    }

    @Test
    void aMissingOrCorruptOrForeignSourceFailsCleanlyAndLeavesTheTargetEmpty() throws Exception {
        Path corrupt = dir.resolve("corrupt.db");
        Files.write(corrupt, "this is not a database at all, just text padding".repeat(40).getBytes());
        Path foreign = dir.resolve("foreign.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + foreign)) {
            c.createStatement().executeUpdate("CREATE TABLE other (a INTEGER)");
        }
        try (Connection target = docs()) {
            for (Path bad : List.of(dir.resolve("missing.db"), corrupt, foreign)) {
                assertThatThrownBy(() -> PortfolioMigrator.migrate(bad, target, false, false)).as(bad.toString())
                        .isInstanceOf(PortfolioMigrator.MigrateException.class);
            }
            assertThat(count(target, "profile")).isZero();
            assertThat(count(target, "skills")).isZero();
        }
    }

    // ---------------------------------------------------------------- the command line

    private record Out(int code, String out, String err) {}

    private Out cli(String... args) {
        PrintStream so = System.out, se = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o));
        System.setErr(new PrintStream(e));
        try {
            return new Out(PortfolioMigrator.run(args, data.toString()), o.toString(), e.toString());
        } finally {
            System.setOut(so);
            System.setErr(se);
        }
    }

    @Test
    void theCommandMigratesOnceThenRefusesThenReplaces() throws Exception {
        Out dry = cli("migrate-portfolio", "--from", hubDb.toString(), "--dry-run");
        assertThat(dry.code()).as(dry.err()).isZero();
        assertThat(dry.out()).contains("DRY RUN").contains("experiences");

        Out first = cli("migrate-portfolio", "--from", hubDb.toString());
        assertThat(first.code()).as(first.err()).isZero();
        assertThat(first.out()).contains("Migration complete").contains("skills");

        Out again = cli("migrate-portfolio", "--from", hubDb.toString());
        assertThat(again.code()).isEqualTo(1);
        assertThat(again.err()).contains("already has rows");

        assertThat(cli("migrate-portfolio", "--from", hubDb.toString(), "--replace").code()).isZero();
        assertThat(cli("migrate-portfolio").code()).isEqualTo(2);
        assertThat(cli("migrate-portfolio", "--from", dir.resolve("nope.db").toString()).code()).isEqualTo(1);
        assertThat(cli("migrate-portfolio", "--bogus").code()).isEqualTo(2);
    }
}
