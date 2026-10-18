package com.homelabhub.internal.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.backup.BackupService;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.widgets.HostMetrics;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The budget API end to end on a real SQLite database (plan phases 2 and 3), with a fixed clock. */
@SpringBootTest(properties = {"hub.status.auto-start=false"})
@AutoConfigureMockMvc
@Import(BudgetApiTest.Clocks.class)
class BudgetApiTest {

    /** A clock the test can move; zoned views keep the instant, so "today in Asia/Kolkata" is computed for real. */
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-26T06:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return Clock.fixed(now, z); }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock budgetTestClock() {
            return new MutableClock();
        }
    }

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.backup.dir", () -> DIR.resolve("backups").toString());
    }

    static final String B = "/api/admin/budget";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired BackupService backups;
    @MockitoBean ProxmoxClient proxmox;
    @MockitoBean HostMetrics metrics;

    @BeforeEach
    void clean() {
        for (String t : List.of("budget_settlements", "budget_expense_shares", "budget_expenses", "budget_recurring_skips",
                "budget_recurring_people", "budget_recurring", "budget_people")) {
            jdbc.update("DELETE FROM " + t);
        }
        clock.now = Instant.parse("2026-09-26T06:00:00Z");
    }

    // ---------------------------------------------------------------- helpers

    record Res(int status, JsonNode body, MockHttpServletResponse raw) {}

    private Res send(MockHttpServletRequestBuilder b, Object body) throws Exception {
        b = b.with(user("admin")).with(csrf());
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
        String text = r.getContentType() != null && r.getContentType().contains("json") ? r.getContentAsString() : "";
        return new Res(r.getStatus(), text.isEmpty() ? null : json.readTree(text), r);
    }

    private JsonNode ok(MockHttpServletRequestBuilder b, Object body) throws Exception {
        Res r = send(b, body);
        assertThat(r.status()).as("%s", r.body()).isBetween(200, 201);
        return r.body();
    }

    private long person(String name) throws Exception {
        return ok(post(B + "/people"), Map.of("name", name)).get("id").asLong();
    }

    private static Map<String, Object> expense(String name, long paise, long paidBy, String month, Integer day, List<Long> split) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("amountPaise", paise);
        m.put("paidBy", paidBy);
        m.put("month", month);
        if (day != null) {
            m.put("day", day);
        }
        m.put("splitIds", split);
        return m;
    }

    private static Map<String, Object> recurring(String name, long paise, long paidBy, List<Long> split, String start, String end, Integer day) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("amountPaise", paise);
        m.put("paidBy", paidBy);
        m.put("splitIds", split);
        m.put("startMonth", start);
        m.put("endMonth", end);
        m.put("day", day);
        return m;
    }

    private JsonNode open(String month) throws Exception {
        return ok(post(B + "/months/" + month + "/open"), null);
    }

    /** Reference data of plan 5.6. Returns {anu, ravi, meera}. */
    private long[] reference() throws Exception {
        long anu = person("Anu"), ravi = person("Ravi"), meera = person("Meera");
        List<Long> all = List.of(anu, ravi, meera);
        ok(post(B + "/recurring"), recurring("Rent", 3000000, anu, all, "2026-09", null, 1));
        ok(post(B + "/recurring"), recurring("Internet", 119900, ravi, all, "2026-09", "2027-02", 5));
        ok(post(B + "/expenses"), expense("August dinner", 60000, meera, "2026-08", 10, List.of(ravi, meera)));
        ok(post(B + "/expenses"), expense("Groceries", 240000, ravi, "2026-09", 12, all));
        ok(post(B + "/expenses"), expense("Electricity bill", 185050, meera, "2026-09", null, all));
        ok(post(B + "/expenses"), expense("Dinner out", 360000, meera, "2026-09", 20, List.of(anu, meera)));
        return new long[] {anu, ravi, meera};
    }

    private static List<String> transfers(JsonNode statement) {
        List<String> out = new ArrayList<>();
        statement.get("transfers").forEach(t -> out.add(t.get("fromId").asLong() + ">" + t.get("toId").asLong() + ":" + t.get("paise").asLong()));
        return out;
    }

    // ---------------------------------------------------------------- the reference month

    @Test
    void septemberMatchesTheReferenceStatement() throws Exception {
        long[] p = reference();
        JsonNode m = open("2026-09");
        assertThat(m.get("month").asText()).isEqualTo("2026-09");
        assertThat(m.get("from").asText()).isEqualTo("2026-09-01");
        assertThat(m.get("to").asText()).isEqualTo("2026-09-30");
        assertThat(m.get("today").asText()).isEqualTo("2026-09-26");
        JsonNode s = m.get("statement");
        assertThat(s.get("expenseCount").asLong()).isEqualTo(5);
        assertThat(s.get("expenseTotalPaise").asLong()).isEqualTo(3904950);
        assertThat(s.get("hasSettlements").asBoolean()).isFalse();
        JsonNode rows = s.get("rows");
        assertThat(rows.get(0).get("paidPaise").asLong()).isEqualTo(3000000);
        assertThat(rows.get(0).get("sharePaise").asLong()).isEqualTo(1361651);
        assertThat(rows.get(0).get("toSettlePaise").asLong()).isEqualTo(1638349);
        assertThat(rows.get(1).get("carriedInPaise").asLong()).isEqualTo(-30000);
        assertThat(rows.get(1).get("toSettlePaise").asLong()).isEqualTo(-851750);
        assertThat(rows.get(2).get("periodNetPaise").asLong()).isEqualTo(-816599);
        assertThat(rows.get(2).get("toSettlePaise").asLong()).isEqualTo(-786599);
        assertThat(s.get("totals").get("paidPaise").asLong()).isEqualTo(3904950);
        assertThat(s.get("totals").get("toSettlePaise").asLong()).isZero();
        assertThat(transfers(s)).containsExactly(p[1] + ">" + p[0] + ":851750", p[2] + ">" + p[0] + ":786599");
        assertThat(s.get("openingTransfers").get(0).get("fromId").asLong()).isEqualTo(p[1]);
        assertThat(s.get("openingTransfers").get(0).get("paise").asLong()).isEqualTo(30000);

        // expenses: sorted by date, generated rows marked, the month-only entry on the 15th
        JsonNode ex = m.get("expenses");
        assertThat(ex).hasSize(5);
        assertThat(ex.get(0).get("name").asText()).isEqualTo("Rent");
        assertThat(ex.get(0).get("recurringId").isNull()).isFalse();
        assertThat(ex.get(3).get("name").asText()).isEqualTo("Electricity bill");
        assertThat(ex.get(3).get("date").asText()).isEqualTo("2026-09-15");
        assertThat(ex.get(3).get("daySet").asBoolean()).isFalse();
        JsonNode shares = ex.get(3).get("shares");
        assertThat(shares.get(0).get("paise").asLong()).isEqualTo(61684);
        assertThat(shares.get(1).get("paise").asLong()).isEqualTo(61683);
        assertThat(m.get("history")).isNotEmpty();
        JsonNode sep = m.get("history").get(m.get("history").size() - 1);
        assertThat(sep.get("month").asText()).isEqualTo("2026-09");
        assertThat(sep.get("totalPaise").asLong()).isEqualTo(3904950);
        assertThat(sep.get("paidPaise").get(Long.toString(p[0])).asLong()).isEqualTo(3000000);
    }

    @Test
    void aPartPaymentShrinksTheTransferAndCanBeUndone() throws Exception {
        long[] p = reference();
        JsonNode set = ok(post(B + "/settlements"), Map.of("fromId", p[1], "toId", p[0], "amountPaise", 500000, "date", "2026-09-25"));
        JsonNode s = open("2026-09").get("statement");
        assertThat(s.get("hasSettlements").asBoolean()).isTrue();
        assertThat(s.get("rows").get(1).get("toSettlePaise").asLong()).isEqualTo(-351750);
        assertThat(s.get("rows").get(1).get("settledPaise").asLong()).isEqualTo(500000);
        assertThat(transfers(s)).contains(p[1] + ">" + p[0] + ":351750");
        assertThat(open("2026-09").get("settlements")).hasSize(1);
        // the next month carries in what is left
        JsonNode oct = open("2026-10").get("statement");
        assertThat(oct.get("rows").get(1).get("carriedInPaise").asLong()).isEqualTo(-351750);
        // undo
        ok(delete(B + "/settlements/" + set.get("id").asLong()), null);
        assertThat(transfers(open("2026-09").get("statement"))).contains(p[1] + ">" + p[0] + ":851750");
        assertThat(send(delete(B + "/settlements/" + set.get("id").asLong()), null).status()).isEqualTo(404);
    }

    @Test
    void aSettlementWithoutADateIsDatedTodayInIndia() throws Exception {
        long a = person("Anu"), b = person("Ravi");
        clock.now = Instant.parse("2026-09-30T23:30:00Z"); // already 1 October in Asia/Kolkata
        JsonNode s = ok(post(B + "/settlements"), Map.of("fromId", a, "toId", b, "amountPaise", 100));
        assertThat(s.get("date").asText()).isEqualTo("2026-10-01");
        assertThat(open("2026-10").get("settlements")).hasSize(1);
        assertThat(open("2026-09").get("settlements")).isEmpty();
    }

    /**
     * The reported bug: "Mark paid" while looking at a past month, no date chosen, used to record the payment today (next
     * month), so the past month still showed the transfer and offered "Mark paid" again.
     */
    @Test
    void markingAPastMonthPaidWithoutADateSettlesThatMonthOnce() throws Exception {
        long[] p = reference();
        assertThat(transfers(open("2026-08").get("statement"))).containsExactly(p[1] + ">" + p[2] + ":30000");
        JsonNode s = ok(post(B + "/settlements"), Map.of("fromId", p[1], "toId", p[2], "amountPaise", 30000, "month", "2026-08"));
        assertThat(s.get("date").asText()).isEqualTo("2026-08-31");
        JsonNode aug = open("2026-08");
        assertThat(transfers(aug.get("statement"))).isEmpty();
        assertThat(aug.get("settlements")).hasSize(1);
        JsonNode sep = open("2026-09");
        assertThat(sep.get("settlements")).isEmpty();
        assertThat(sep.get("statement").get("rows").get(1).get("carriedInPaise").asLong()).isZero();
        // nothing from August is offered again in September
        assertThat(transfers(sep.get("statement"))).doesNotContain(p[1] + ">" + p[2] + ":30000");
        // the current month with no date is still today; an explicit date always wins
        assertThat(ok(post(B + "/settlements"), Map.of("fromId", p[1], "toId", p[0], "amountPaise", 100, "month", "2026-09")).get("date").asText())
                .isEqualTo("2026-09-26");
        assertThat(ok(post(B + "/settlements"), Map.of("fromId", p[1], "toId", p[0], "amountPaise", 100, "month", "2026-08", "date", "2026-09-02"))
                .get("date").asText()).isEqualTo("2026-09-02");
        assertThat(send(post(B + "/settlements"), Map.of("fromId", p[1], "toId", p[0], "amountPaise", 100, "month", "26-8")).body()
                .get("errors").has("month")).isTrue();
    }

    @Test
    void theStatementEndpointMatchesAnyRange() throws Exception {
        long[] p = reference();
        JsonNode r = ok(get(B + "/statement?from=2026-09-01&to=2026-09-10"), null);
        assertThat(r.get("statement").get("expenseCount").asLong()).isEqualTo(2); // rent, internet: the 15th and the 20th drop out
        assertThat(r.get("statement").get("openingTransfers").get(0).get("fromId").asLong()).isEqualTo(p[1]);
        JsonNode r2 = ok(get(B + "/statement?from=2026-08-10&to=2026-09-20"), null);
        assertThat(r2.get("statement").get("expenseCount").asLong()).isEqualTo(6);
        assertThat(r2.get("statement").get("openingTransfers")).isEmpty();
        assertThat(send(get(B + "/statement?from=2026-09-10&to=2026-09-01"), null).status()).isEqualTo(422);
        assertThat(send(get(B + "/statement?from=2020-01-01&to=2026-01-02"), null).status()).isEqualTo(422);
        assertThat(send(get(B + "/statement?from=nope&to=2026-01-02"), null).body().get("errors").has("from")).isTrue();
        assertThat(send(get(B + "/statement"), null).status()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- recurring

    @Test
    void openingAMonthTwiceCreatesEntriesOnce() throws Exception {
        long a = person("Anu");
        ok(post(B + "/recurring"), recurring("Rent", 1000, a, List.of(a), "2026-09", null, null));
        assertThat(open("2026-09").get("expenses")).hasSize(1);
        assertThat(open("2026-09").get("expenses")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_expenses", Long.class)).isEqualTo(1);
        JsonNode e = open("2026-09").get("expenses").get(0);
        assertThat(e.get("date").asText()).isEqualTo("2026-09-15");
        assertThat(e.get("daySet").asBoolean()).isFalse();
    }

    @Test
    void aDeletedGeneratedEntryIsNotCreatedAgain() throws Exception {
        long a = person("Anu");
        ok(post(B + "/recurring"), recurring("Rent", 1000, a, List.of(a), "2026-09", null, 1));
        long id = open("2026-09").get("expenses").get(0).get("id").asLong();
        ok(delete(B + "/expenses/" + id), null);
        assertThat(open("2026-09").get("expenses")).isEmpty();
        assertThat(open("2026-10").get("expenses")).hasSize(1);
    }

    @Test
    void editingADefinitionLeavesOpenedMonthsAlone() throws Exception {
        long a = person("Anu");
        JsonNode r = ok(post(B + "/recurring"), recurring("Rent", 1000, a, List.of(a), "2026-09", null, 1));
        open("2026-09");
        Map<String, Object> changed = recurring("Rent", 2000, a, List.of(a), "2026-09", null, 1);
        changed.put("version", r.get("version").asInt());
        ok(put(B + "/recurring/" + r.get("id").asLong()), changed);
        assertThat(open("2026-09").get("expenses").get(0).get("amountPaise").asLong()).isEqualTo(1000);
        assertThat(open("2026-10").get("expenses").get(0).get("amountPaise").asLong()).isEqualTo(2000);
        // a stale version is refused
        assertThat(send(put(B + "/recurring/" + r.get("id").asLong()), changed).status()).isEqualTo(409);
    }

    @Test
    void openingALaterMonthFillsInEarlierMonthsSoCarryInIsRight() throws Exception {
        long a = person("Anu"), b = person("Ravi");
        ok(post(B + "/recurring"), recurring("Rent", 1000, a, List.of(a, b), "2026-10", "2026-11", 31));
        JsonNode dec = open("2026-12");
        assertThat(dec.get("expenses")).isEmpty(); // ended in November (inclusive)
        assertThat(dec.get("statement").get("rows").get(1).get("carriedInPaise").asLong()).isEqualTo(-1000);
        List<String> dates = jdbc.queryForList("SELECT expense_date FROM budget_expenses ORDER BY 1", String.class);
        assertThat(dates).containsExactly("2026-10-31", "2026-11-30");
    }

    @Test
    void deletingADefinitionKeepsItsEntries() throws Exception {
        long a = person("Anu");
        JsonNode r = ok(post(B + "/recurring"), recurring("Rent", 1000, a, List.of(a), "2026-09", null, 1));
        open("2026-09");
        ok(delete(B + "/recurring/" + r.get("id").asLong()), null);
        JsonNode e = open("2026-09").get("expenses");
        assertThat(e).hasSize(1);
        assertThat(e.get(0).get("recurringId").isNull()).isTrue();
        assertThat(ok(get(B + "/recurring"), null)).isEmpty();
    }

    @Test
    void farFutureMonthsCannotBeOpened() throws Exception {
        assertThat(send(post(B + "/months/2029-01/open"), null).status()).isEqualTo(422);
        assertThat(send(post(B + "/months/1999-12/open"), null).status()).isEqualTo(422);
        assertThat(send(post(B + "/months/2026-13/open"), null).status()).isEqualTo(422);
        assertThat(send(post(B + "/months/2028-09/open"), null).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- people

    @Test
    void peopleAreUniqueIgnoringCaseAndCanBeRenamedAndArchived() throws Exception {
        long a = person("Anu");
        Res dup = send(post(B + "/people"), Map.of("name", "  anu "));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("errors").get("name").asText()).contains("already exists");
        JsonNode renamed = ok(put(B + "/people/" + a), Map.of("name", "Anu K", "archived", false, "version", 0));
        assertThat(renamed.get("name").asText()).isEqualTo("Anu K");
        assertThat(send(put(B + "/people/" + a), Map.of("name", "Anu", "archived", false, "version", 0)).status()).isEqualTo(409);
        JsonNode archived = ok(put(B + "/people/" + a), Map.of("name", "Anu K", "archived", true, "version", 1));
        assertThat(archived.get("archived").asBoolean()).isTrue();
        assertThat(send(put(B + "/people/999"), Map.of("name", "X", "archived", false, "version", 0)).status()).isEqualTo(404);
        assertThat(send(post(B + "/people"), Map.of("name", "")).body().get("errors").has("name")).isTrue();
        assertThat(send(post(B + "/people"), Map.of("name", "x".repeat(61))).status()).isEqualTo(422);
    }

    @Test
    void archivingIsRefusedWhileOnAnActiveRecurringExpense() throws Exception {
        long a = person("Anu"), b = person("Ravi");
        JsonNode r = ok(post(B + "/recurring"), recurring("Rent", 1000, a, List.of(a, b), "2026-01", null, null));
        Res refused = send(put(B + "/people/" + b), Map.of("name", "Ravi", "archived", true, "version", 0));
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body().get("errors").get("archived").asText()).contains("Rent");
        // once it has ended, archiving works, and the archived person keeps their history and balance
        Map<String, Object> ended = recurring("Rent", 1000, a, List.of(a, b), "2026-01", "2026-08", null);
        ended.put("version", r.get("version").asInt());
        ok(put(B + "/recurring/" + r.get("id").asLong()), ended);
        ok(put(B + "/people/" + b), Map.of("name", "Ravi", "archived", true, "version", 0));
        JsonNode sep = open("2026-09");
        assertThat(sep.get("statement").get("rows")).hasSize(2); // Ravi still owes, so is still shown
        // a new expense may not use an archived person
        Res bad = send(post(B + "/expenses"), expense("Tea", 100, b, "2026-09", null, List.of(a)));
        assertThat(bad.body().get("errors").has("paidBy")).isTrue();
        Res bad2 = send(post(B + "/expenses"), expense("Tea", 100, a, "2026-09", null, List.of(a, b)));
        assertThat(bad2.body().get("errors").has("splitIds")).isTrue();
        // but an old expense that has them can still be edited
        JsonNode old = open("2026-01").get("expenses").get(0);
        Map<String, Object> edit = expense("Rent (Jan)", 1000, a, "2026-01", null, List.of(a, b));
        edit.put("version", old.get("version").asInt());
        ok(put(B + "/expenses/" + old.get("id").asLong()), edit);
    }

    @Test
    void atMostFiftyPeople() throws Exception {
        for (int i = 0; i < 50; i++) {
            person("P" + i);
        }
        assertThat(send(post(B + "/people"), Map.of("name", "One too many")).status()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- expenses

    @Test
    void expenseValidationIsFieldByField() throws Exception {
        long a = person("Anu");
        Map<String, Object> base = expense("Tea", 100, a, "2026-09", null, List.of(a));
        Object[][] bad = {
                {"name", ""}, {"name", "x".repeat(101)}, {"name", "tab\there"}, {"amountPaise", 0}, {"amountPaise", -5},
                {"amountPaise", 10_000_000_001L}, {"amountPaise", 18.5}, {"amountPaise", "100"}, {"paidBy", 999}, {"paidBy", null},
                {"splitIds", List.of()}, {"splitIds", List.of(999)}, {"splitIds", List.of(a, a)}, {"splitIds", "all"},
                {"month", "2026-9"}, {"month", "1999-12"}, {"month", null}, {"day", 31}, {"day", 0},
        };
        for (Object[] c : bad) {
            Map<String, Object> body = new HashMap<>(base);
            body.put((String) c[0], c[1]);
            Res r = send(post(B + "/expenses"), body);
            assertThat(r.status()).as("%s=%s", c[0], c[1]).isEqualTo(422);
            String field = (String) c[0];
            assertThat(r.body().get("errors").has(field)).as("%s=%s -> %s", c[0], c[1], r.body()).isTrue();
        }
        // April has 30 days
        Map<String, Object> april = expense("Tea", 100, a, "2026-04", 31, List.of(a));
        assertThat(send(post(B + "/expenses"), april).body().get("errors").get("day").asText()).contains("30");
        assertThat(send(post(B + "/expenses"), "{not json").status()).isEqualTo(400);
        // the cap itself is fine
        ok(post(B + "/expenses"), expense("Big", Money.MAX_PAISE, a, "2026-09", 30, List.of(a)));
    }

    @Test
    void editingAnExpenseRecomputesSharesAndChecksTheVersion() throws Exception {
        long a = person("Anu"), b = person("Ravi");
        JsonNode e = ok(post(B + "/expenses"), expense("Tea", 101, a, "2026-09", 3, List.of(a, b)));
        assertThat(e.get("shares").get(0).get("paise").asLong()).isEqualTo(51);
        Map<String, Object> edit = expense("Tea", 300, b, "2026-09", null, List.of(b));
        edit.put("version", 0);
        JsonNode after = ok(put(B + "/expenses/" + e.get("id").asLong()), edit);
        assertThat(after.get("shares")).hasSize(1);
        assertThat(after.get("daySet").asBoolean()).isFalse();
        assertThat(after.get("version").asInt()).isEqualTo(1);
        Res stale = send(put(B + "/expenses/" + e.get("id").asLong()), edit);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("current").get("version").asInt()).isEqualTo(1);
        edit.remove("version");
        assertThat(send(put(B + "/expenses/" + e.get("id").asLong()), edit).body().get("errors").has("version")).isTrue();
        assertThat(send(put(B + "/expenses/12345"), Map.of("version", 0)).status()).isEqualTo(404);
        assertThat(send(delete(B + "/expenses/12345"), null).status()).isEqualTo(404);
    }

    @Test
    void settlementValidation() throws Exception {
        long a = person("Anu"), b = person("Ravi");
        assertThat(send(post(B + "/settlements"), Map.of("fromId", a, "toId", a, "amountPaise", 100)).body().get("errors").has("toId")).isTrue();
        assertThat(send(post(B + "/settlements"), Map.of("fromId", a, "toId", 999, "amountPaise", 100)).body().get("errors").has("toId")).isTrue();
        assertThat(send(post(B + "/settlements"), Map.of("fromId", a, "toId", b, "amountPaise", 0)).body().get("errors").has("amountPaise")).isTrue();
        assertThat(send(post(B + "/settlements"), Map.of("fromId", a, "toId", b, "amountPaise", 1, "date", "2026-02-30")).body().get("errors").has("date")).isTrue();
        assertThat(send(post(B + "/settlements"), Map.of("fromId", a, "toId", b, "amountPaise", 1, "note", "n".repeat(201))).body().get("errors").has("note")).isTrue();
        JsonNode ok = ok(post(B + "/settlements"), Map.of("fromId", a, "toId", b, "amountPaise", 1, "note", "  cash  "));
        assertThat(ok.get("note").asText()).isEqualTo("cash");
    }

    // ---------------------------------------------------------------- PDF

    @Test
    void thePdfExportHasTheRightHeadersAndFigures() throws Exception {
        reference();
        Res r = send(get(B + "/export.pdf?from=2026-09-01&to=2026-09-30"), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.raw().getContentType()).isEqualTo("application/pdf");
        assertThat(r.raw().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"budget-2026-09.pdf\"");
        assertThat(r.raw().getHeader("Cache-Control")).isEqualTo("no-store");
        byte[] pdf = r.raw().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Budget summary · September 2026", "₹8,517.50", "₹7,865.99", "Ravi", "Anu",
                    "Sep (no day)", "Page 1 of 1", "Carried in from before this period: Ravi owes Meera ₹300.00.", "39,049.50");
        }
        Res range = send(get(B + "/export.pdf?from=2026-08-10&to=2026-09-20"), null);
        assertThat(range.raw().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"budget-2026-08-10_to_2026-09-20.pdf\"");
        assertThat(send(get(B + "/export.pdf?from=2026-09-20&to=2026-08-10"), null).status()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- security and housekeeping

    @Test
    void everyRouteNeedsTheLoginAndWritesNeedCsrf() throws Exception {
        for (String path : List.of(B + "/people", B + "/recurring", B + "/history?to=2026-09", B + "/statement?from=2026-09-01&to=2026-09-30",
                B + "/export.pdf?from=2026-09-01&to=2026-09-30")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
        for (String path : List.of(B + "/people", B + "/expenses", B + "/recurring", B + "/settlements", B + "/months/2026-09/open")) {
            assertThat(mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus())
                    .as(path).isEqualTo(401);
            assertThat(mvc.perform(post(path).with(user("admin")).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus())
                    .as(path + " without CSRF").isEqualTo(403);
        }
        assertThat(mvc.perform(delete(B + "/expenses/1").with(user("admin"))).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void nothingInTheBudgetPackageIsScheduled() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new RegexPatternTypeFilter(java.util.regex.Pattern.compile(".*")));
        int classes = 0;
        for (var bd : scan.findCandidateComponents("com.homelabhub.internal.budget")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.getName().contains("Test")) {
                continue;
            }
            classes++;
            for (Method m : c.getDeclaredMethods()) {
                assertThat(m.isAnnotationPresent(Scheduled.class)).as(c.getName() + "#" + m.getName()).isFalse();
            }
        }
        assertThat(classes).isGreaterThanOrEqualTo(5);
    }

    @Test
    void theNightlyBackupContainsTheBudget() throws Exception {
        reference();
        open("2026-09");
        Path copy = backups.backupNow();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copy);
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM budget_expenses")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(6);
        }
    }
}
