package com.homelabhub.internal.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The V4 budget migration (plan 2.1) and the migration numbering it relies on. */
@SpringBootTest(properties = {"hub.status.auto-start=false"})
class BudgetSchemaTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProxmoxClient proxmox;

    @Test
    void tablesAndTheOccurrenceIndexExist() {
        List<String> tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertThat(tables).contains("budget_people", "budget_recurring", "budget_recurring_people", "budget_recurring_skips",
                "budget_expenses", "budget_expense_shares", "budget_settlements");
        assertThat(jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='index'", String.class)).contains("uq_budget_expense_occurrence");
        assertThat(jdbc.queryForObject("SELECT description FROM flyway_schema_history WHERE version = '4' AND success = 1", String.class))
                .isEqualTo("budget");
    }

    @Test
    void constraintsHold() {
        jdbc.update("INSERT INTO budget_people (id, name) VALUES (901, 'Constraint A'), (902, 'Constraint B')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_people (name) VALUES ('constraint a')")).hasMessageContaining("UNIQUE");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_expenses (name, amount_paise, paid_by, expense_date, day_set) VALUES ('x', 0, 901, '2026-09-01', 1)"))
                .hasMessageContaining("CHECK");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_expenses (name, amount_paise, paid_by, expense_date, day_set) VALUES ('x', 10000000001, 901, '2026-09-01', 1)"))
                .hasMessageContaining("CHECK");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_expenses (name, amount_paise, paid_by, expense_date, day_set) VALUES ('x', 5, 999, '2026-09-01', 1)"))
                .hasMessageContaining("FOREIGN KEY");
        jdbc.update("INSERT INTO budget_expenses (id, name, amount_paise, paid_by, expense_date, day_set) VALUES (901, 'x', 5, 901, '2026-09-01', 1)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_expense_shares (expense_id, person_id, share_paise) VALUES (901, 999, 5)"))
                .hasMessageContaining("FOREIGN KEY");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_settlements (from_person, to_person, amount_paise, settled_on) VALUES (901, 901, 5, '2026-09-01')"))
                .hasMessageContaining("CHECK");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_recurring (name, amount_paise, paid_by, start_month, end_month) VALUES ('r', 5, 901, '2026-09', '2026-08')"))
                .hasMessageContaining("CHECK");
        jdbc.update("INSERT INTO budget_recurring (id, name, amount_paise, paid_by, start_month) VALUES (901, 'r', 5, 901, '2026-09')");
        jdbc.update("INSERT INTO budget_expenses (name, amount_paise, paid_by, expense_date, day_set, recurring_id, recurring_month) VALUES ('r', 5, 901, '2026-09-15', 0, 901, '2026-09')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_expenses (name, amount_paise, paid_by, expense_date, day_set, recurring_id, recurring_month) VALUES ('r', 5, 901, '2026-09-15', 0, 901, '2026-09')"))
                .hasMessageContaining("UNIQUE");
        // deleting a definition keeps its entries and unlinks them
        jdbc.update("DELETE FROM budget_recurring WHERE id = 901");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_expenses WHERE name = 'r' AND recurring_id IS NULL", Long.class)).isEqualTo(1);
        jdbc.update("DELETE FROM budget_expense_shares");
        jdbc.update("DELETE FROM budget_expenses");
        jdbc.update("DELETE FROM budget_people");
    }
}
