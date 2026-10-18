package com.homelabhub.docs.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** The portfolio tables in docs.db must stay identical to the hub's, because migrate-portfolio copies like for like (P-D5). */
class PortfolioSchemaParityTest {

    @TempDir Path dir;

    /** columns (name, type, notnull, default, pk), foreign keys and indexes of one table, in a comparable form */
    private static Map<String, Object> shape(JdbcClient j, String table) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", j.sql("SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('" + table + "') ORDER BY cid")
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getInt(3), String.valueOf(rs.getString(4)), rs.getInt(5))).list());
        m.put("fks", j.sql("SELECT \"table\", \"from\", \"to\", on_delete, on_update FROM pragma_foreign_key_list('" + table + "') ORDER BY id, seq")
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), String.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5))).list());
        List<Object> idx = new ArrayList<>();
        for (String name : j.sql("SELECT name FROM pragma_index_list('" + table + "') WHERE name NOT LIKE 'sqlite_autoindex%' ORDER BY name").query(String.class).list()) {
            idx.add(name + ":" + j.sql("SELECT name FROM pragma_index_info('" + name + "') ORDER BY seqno").query(String.class).list());
        }
        m.put("indexes", idx);
        m.put("uniques", j.sql("SELECT count(*) FROM pragma_index_list('" + table + "') WHERE \"unique\" = 1").query(Integer.class).single());
        return m;
    }

    @Test
    void everyPortfolioTableIsIdenticalInTheHubAndInDocs() {
        var hub = HubDb.open(dir.resolve("hub.db"));
        var docs = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("docs.db") + "?foreign_keys=on", true);
        docs.setSuppressClose(true);
        Flyway.configure().dataSource(docs).load().migrate();
        var h = JdbcClient.create(hub);
        var d = JdbcClient.create(docs);
        try {
            assertThat(PortfolioImporter.tablesInInsertOrder()).hasSize(19);
            for (String t : PortfolioImporter.tablesInInsertOrder()) {
                assertThat(shape(d, t)).as("table %s in docs.db", t).isEqualTo(shape(h, t));
            }
        } finally {
            hub.destroy();
            docs.destroy();
        }
    }

    @Test
    void docsHasNoPortfolioLookingTableThatTheMigratorDoesNotKnow() {
        var docs = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("docs2.db"), true);
        docs.setSuppressClose(true);
        Flyway.configure().dataSource(docs).load().migrate();
        try {
            List<String> known = PortfolioImporter.tablesInInsertOrder();
            List<String> tables = JdbcClient.create(docs).sql("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'flyway%' AND name NOT LIKE 'doc_%' AND name NOT IN ('kv','audit_log')")
                    .query(String.class).list();
            assertThat(tables).as("every non-Docs table must be one the migrator copies").containsExactlyInAnyOrderElementsOf(known);
        } finally {
            docs.destroy();
        }
    }
}
