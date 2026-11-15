package com.homelabhub.docs.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** V3 adds the display options to a database that already holds portfolio data, and rewrites nothing. */
class PortfolioDisplayOptionsMigrationTest {

    @TempDir Path dir;

    @Test
    void v3AddsTheColumnsAndLeavesExistingDataIntact() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("docs.db") + "?foreign_keys=on", true);
        ds.setSuppressClose(true);
        try {
            Flyway.configure().dataSource(ds).target("2").load().migrate();
            var j = JdbcClient.create(ds);
            j.sql("INSERT INTO skill_groups (category, icon, sort_order) VALUES ('Languages', 'Code', 3)").update();
            j.sql("INSERT INTO skills (group_id, label, sort_order, version) VALUES (1, 'Python 3', 2, 5)").update();
            j.sql("INSERT INTO projects (slug, kind, title, description, version) VALUES ('p', 'small', 'P', 'D', 4)").update();
            assertThat(j.sql("SELECT count(*) FROM pragma_table_info('skills') WHERE name IN ('icon','core')").query(Integer.class).single()).isZero();

            Flyway.configure().dataSource(ds).load().migrate();

            assertThat(j.sql("SELECT category || '|' || icon || '|' || sort_order FROM skill_groups").query(String.class).single()).isEqualTo("Languages|Code|3");
            assertThat(j.sql("SELECT label || '|' || sort_order || '|' || version FROM skills").query(String.class).single()).isEqualTo("Python 3|2|5");
            assertThat(j.sql("SELECT title || '|' || version FROM projects").query(String.class).single()).isEqualTo("P|4");
            assertThat(j.sql("SELECT icon, core FROM skills").query((rs, n) -> List.of(String.valueOf(rs.getString(1)), rs.getInt(2))).single()).containsExactly("null", 0);
            assertThat(j.sql("SELECT constellation FROM skill_groups").query(String.class).optional()).isEmpty();
            assertThat(j.sql("SELECT icon FROM projects").query(String.class).optional()).isEmpty();
            assertThat(j.sql("SELECT count(*) FROM pragma_table_info('skills') WHERE name = 'core' AND \"notnull\" = 1").query(Integer.class).single()).isEqualTo(1);
        } finally {
            ds.destroy();
        }
    }
}
