package com.homelabhub.docs.portfolio;

import com.homelabhub.docs.portfolio.PortfolioImporter.Extras;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** A database built with the hub's REAL migrations (read from the hub module), for the parity and migration tests. */
final class HubDb {

    static final String HUB_MIGRATIONS = "filesystem:../hub-internal/src/main/resources/db/migration";

    private HubDb() {}

    static SingleConnectionDataSource open(Path file) {
        var ds = new SingleConnectionDataSource("jdbc:sqlite:" + file + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000", true);
        ds.setSuppressClose(true);
        Flyway.configure().dataSource(ds).locations(HUB_MIGRATIONS).load().migrate();
        return ds;
    }

    /** A hub database with the importer's test portfolio in it, so every kind of table has rows. */
    static Path withPortfolio(Path file) throws Exception {
        var ds = open(file);
        try {
            var jdbc = JdbcClient.create(ds);
            var importer = new PortfolioImporter(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
            Extras extras = new Extras(
                    Map.of("devices", Map.of("raspberry-pi", List.of("Docker / Compose", "Python 3")), "containers", Map.of("lxc-1", List.of("Python 3")),
                            "services", Map.of("Grafana", List.of("Python 3"))),
                    Map.of());
            importer.run(PortfolioImporterTest.JSON.readTree(PortfolioImporterTest.EXPORT), PortfolioImporter.parseDecisions(PortfolioImporterTest.DECISIONS), extras, false, false);
            // distinctive versions and a non-trivial alias so a copy that resets them is caught
            jdbc.sql("UPDATE skills SET version = 7 WHERE id = (SELECT min(id) FROM skills)").update();
            jdbc.sql("UPDATE experiences SET version = 3").update();
        } finally {
            ds.destroy();
        }
        return file;
    }
}
