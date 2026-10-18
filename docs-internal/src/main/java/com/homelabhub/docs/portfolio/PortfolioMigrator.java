package com.homelabhub.docs.portfolio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * `java -jar docs-internal.jar migrate-portfolio --from hub-backup.db [--replace] [--dry-run]`
 *
 * One-time copy of the portfolio from a hub database (use a backup file, not the live one) into docs.db. The source is opened
 * read-only and never written. Every portfolio table is copied in foreign-key order inside ONE transaction on the target, with ids,
 * versions and every column kept; the row counts are then compared table by table, and any difference rolls everything back.
 * A target that already has portfolio rows is refused unless `--replace` is given.
 */
public final class PortfolioMigrator {

    private PortfolioMigrator() {}

    /** table -> rows copied */
    public record Report(Map<String, Integer> source, Map<String, Integer> copied, boolean dryRun) {}

    public static final class MigrateException extends RuntimeException {
        MigrateException(String message) {
            super(message);
        }
    }

    public static int run(String[] args, String dataDir) {
        Path from = null;
        boolean replace = false, dry = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--from needs a file");
                        return 2;
                    }
                    from = Path.of(args[++i]);
                }
                case "--replace" -> replace = true;
                case "--dry-run" -> dry = true;
                default -> {
                    System.err.println("Unknown option " + args[i]);
                    return 2;
                }
            }
        }
        if (from == null) {
            System.err.println("Usage: migrate-portfolio --from <hub-backup.db> [--replace] [--dry-run]");
            return 2;
        }
        try {
            Files.createDirectories(Path.of(dataDir));
            String url = "jdbc:sqlite:" + Path.of(dataDir, "docs.db") + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL";
            var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(url, true);
            ds.setSuppressClose(true);
            try {
                Flyway.configure().dataSource(ds).load().migrate();
                Report r = migrate(from, ds.getConnection(), replace, dry);
                r.copied().forEach((t, n) -> System.out.printf("  %-28s %d (source %d)%n", t, n, r.source().get(t)));
                System.out.println(dry ? "DRY RUN: nothing was saved."
                        : "Migration complete. Row counts match the source, table by table. The source file was not modified.");
            } finally {
                ds.destroy();
            }
            return 0;
        } catch (MigrateException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 1;
        } catch (IOException | SQLException | RuntimeException e) {
            System.err.println("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    /** Copies into {@code target} (already migrated). The caller owns the target connection. */
    public static Report migrate(Path from, Connection target, boolean replace, boolean dryRun) throws SQLException {
        if (!Files.isRegularFile(from)) {
            throw new MigrateException("The source file " + from + " does not exist.");
        }
        Map<String, Integer> source = new LinkedHashMap<>(), copied = new LinkedHashMap<>();
        boolean autoCommit = target.getAutoCommit();
        // mode=ro: SQLite itself refuses any write, so the source cannot be changed by this tool.
        try (Connection src = DriverManager.getConnection("jdbc:sqlite:file:" + from.toAbsolutePath() + "?mode=ro")) {
            checkSource(src);
            target.setAutoCommit(false);
            try {
                if (!replace) {
                    for (String t : PortfolioImporter.tablesInInsertOrder()) {
                        if (count(target, t) > 0) {
                            throw new MigrateException("The Docs portfolio already has rows (" + t + "). Nothing was copied. "
                                    + "Re-run with --replace to overwrite it with the source.");
                        }
                    }
                } else {
                    for (String t : PortfolioImporter.tablesInWipeOrder()) {
                        try (var st = target.createStatement()) {
                            st.executeUpdate("DELETE FROM " + t);
                        }
                    }
                }
                for (String t : PortfolioImporter.tablesInInsertOrder()) {
                    source.put(t, count(src, t));
                    copied.put(t, copyTable(src, target, t));
                }
                for (String t : copied.keySet()) {
                    int actual = count(target, t);
                    if (actual != source.get(t) || copied.get(t) != source.get(t)) {
                        throw new MigrateException("Row count mismatch in " + t + ": source " + source.get(t) + ", target " + actual + ". Nothing was saved.");
                    }
                }
                try (var rs = target.createStatement().executeQuery("PRAGMA foreign_key_check")) {
                    if (rs.next()) {
                        throw new MigrateException("The copied data breaks a foreign key (" + rs.getString(1) + "). Nothing was saved.");
                    }
                }
                if (dryRun) {
                    target.rollback();
                } else {
                    target.commit();
                }
            } catch (SQLException | RuntimeException e) {
                target.rollback();
                throw e;
            } finally {
                target.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            if (e instanceof java.sql.SQLException && e.getMessage() != null && e.getMessage().contains("not a database")) {
                throw new MigrateException("The source file is not a valid SQLite database.");
            }
            throw e;
        }
        return new Report(source, copied, dryRun);
    }

    private static void checkSource(Connection src) throws SQLException {
        try (var st = src.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            if (!rs.next() || !"ok".equals(rs.getString(1))) {
                throw new MigrateException("The source database failed its integrity check; use a good hub backup.");
            }
        } catch (SQLException e) {
            throw new MigrateException("The source file is not a readable SQLite database (" + e.getMessage() + ").");
        }
        for (String t : PortfolioImporter.tablesInInsertOrder()) {
            try (var rs = src.createStatement().executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + t + "'")) {
                if (!rs.next()) {
                    throw new MigrateException("The source has no table " + t + ": this does not look like a Homelab Hub database.");
                }
            }
        }
    }

    private static int count(Connection c, String table) throws SQLException {
        try (var rs = c.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (var rs = c.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private static int copyTable(Connection src, Connection target, String table) throws SQLException {
        List<String> cols = columns(target, table);
        List<String> have = columns(src, table);
        for (String c : cols) {
            if (!have.contains(c)) {
                throw new MigrateException("The source table " + table + " has no column " + c + ": start the hub once on its latest version first.");
            }
        }
        String list = String.join(", ", cols);
        String marks = String.join(", ", cols.stream().map(c -> "?").toList());
        int n = 0;
        try (var rs = src.createStatement().executeQuery("SELECT " + list + " FROM " + table);
             PreparedStatement ins = target.prepareStatement("INSERT INTO " + table + " (" + list + ") VALUES (" + marks + ")")) {
            while (rs.next()) {
                for (int i = 1; i <= cols.size(); i++) {
                    ins.setObject(i, rs.getObject(i));
                }
                ins.addBatch();
                n++;
            }
            ins.executeBatch();
        }
        return n;
    }
}
