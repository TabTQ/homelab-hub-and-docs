package com.homelabhub.docs.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * `java -jar docs-internal.jar import --export F --decisions F [--extras F] [--replace] [--dry-run]`
 * Runs without the web server: a minimal database connection, migrations, then the importer.
 */
public final class ImportCli {

    private ImportCli() {}

    public static int run(String[] args, String dataDir) {
        Path export = null, decisions = null, extras = null;
        boolean replace = false, dry = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--export" -> export = Path.of(args[++i]);
                case "--decisions" -> decisions = Path.of(args[++i]);
                case "--extras" -> extras = Path.of(args[++i]);
                case "--replace" -> replace = true;
                case "--dry-run" -> dry = true;
                default -> {
                    System.err.println("Unknown option " + args[i]);
                    return 2;
                }
            }
        }
        if (export == null || decisions == null) {
            System.err.println("Usage: import --export portfolio-export.v1.json --decisions decisions.csv [--extras import-extras.json] [--replace] [--dry-run]");
            return 2;
        }
        try {
            Files.createDirectories(Path.of(dataDir));
            String url = "jdbc:sqlite:" + Path.of(dataDir, "docs.db") + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL";
            SingleConnectionDataSource ds = new SingleConnectionDataSource(url, true);
            ds.setSuppressClose(true);
            Flyway.configure().dataSource(ds).load().migrate();
            ObjectMapper json = new ObjectMapper();
            JsonNode exportJson = json.readTree(Files.readString(export));
            var decs = PortfolioImporter.parseDecisions(Files.readString(decisions));
            PortfolioImporter.Extras ex = extras == null ? PortfolioImporter.Extras.none() : readExtras(json, Files.readString(extras));
            var importer = new PortfolioImporter(JdbcClient.create(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
            var report = importer.run(exportJson, decs, ex, replace, dry);
            report.imported().forEach((t, n) -> System.out.printf("  %-28s %d (source %d)%n", t, n, report.source().get(t)));
            System.out.printf("New skills: %d, new groups: %d, skill links: %d, free tags: %d%n", report.newSkills(), report.newGroups(), report.skillLinks(), report.unlinkedTags());
            report.warnings().forEach(w -> System.out.println("WARNING: " + w));
            System.out.println(dry ? "DRY RUN: nothing was saved." : "Import complete. Row counts match the export.");
            ds.destroy();
            return 0;
        } catch (PortfolioImporter.ImportException e) {
            System.err.println("ERROR: " + e.getMessage());
            e.problems().forEach(p -> System.err.println("  - " + p));
            return 1;
        } catch (IOException | RuntimeException e) {
            System.err.println("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    static PortfolioImporter.Extras readExtras(ObjectMapper json, String text) throws IOException {
        Map<String, Object> m = json.readValue(text, Map.class);
        Map<String, Map<String, List<String>>> links = (Map<String, Map<String, List<String>>>) m.getOrDefault("homelabSkillLinks", Map.of());
        Map<String, String> aliases = (Map<String, String>) m.getOrDefault("casePlatformAliases", Map.of());
        return new PortfolioImporter.Extras(links, aliases);
    }
}
