package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.BalanceCalculator.Statement;
import com.homelabhub.internal.budget.BalanceCalculator.Totals;
import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.HistoryMonth;
import com.homelabhub.internal.budget.BudgetModels.MonthView;
import com.homelabhub.internal.budget.BudgetModels.PersonRef;
import com.homelabhub.internal.budget.BudgetModels.Settlement;
import com.homelabhub.internal.budget.BudgetModels.Share;
import com.homelabhub.internal.budget.BudgetModels.StatementView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading side of the budget: recurring entries materialised on demand (plan D8/D9), period statements (D4) and the
 * month view. Nothing here runs unless a request asks for it: no scheduler, no cache, no thread (N5).
 */
@Service
public class BudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(BudgetLedger.class);
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    /** At most this many months are materialised by one call (plan 5.3). */
    static final int MAX_MONTHS = 240;
    /** Months further ahead than this cannot be opened (materialising far-future months is almost certainly a typo). */
    static final int MAX_MONTHS_AHEAD = 24;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    public BudgetLedger(JdbcClient jdbc, TransactionTemplate tx, Clock clock, @Value("${hub.budget.zone:Asia/Kolkata}") String zone) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.zone = zoneOf(zone);
    }

    static ZoneId zoneOf(String name) {
        try {
            return ZoneId.of(name == null || name.isBlank() ? DEFAULT_ZONE.getId() : name.trim());
        } catch (RuntimeException e) {
            log.error("hub.budget.zone '{}' is not a valid time zone; using {}", name, DEFAULT_ZONE);
            return DEFAULT_ZONE;
        }
    }

    /** "Today" in the budget's zone. The container runs in UTC, so the JVM default zone is never used (plan 8). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    public ZoneId zone() {
        return zone;
    }

    YearMonth latestOpenable() {
        return YearMonth.from(today()).plusMonths(MAX_MONTHS_AHEAD);
    }

    // ---------------------------------------------------------------- recurring materialisation

    /**
     * Creates the entries of every recurring expense for every month from the earliest start month up to {@code upTo}
     * (so carried-in balances include months that were never opened). Idempotent: an entry that exists, or one the
     * owner deleted (a skip row), is left alone. Returns how many entries were created.
     */
    public int materialise(YearMonth upTo) {
        if (upTo.isAfter(latestOpenable())) {
            upTo = latestOpenable();
        }
        record Def(long id, long amount, YearMonth start, YearMonth end, Integer day) {}
        YearMonth last = upTo;
        List<Def> defs = jdbc.sql("SELECT id, amount_paise, start_month, end_month, day_of_month FROM budget_recurring WHERE start_month <= ?")
                .param(last.toString())
                .query((rs, n) -> new Def(rs.getLong(1), rs.getLong(2), YearMonth.parse(rs.getString(3)),
                        rs.getString(4) == null ? null : YearMonth.parse(rs.getString(4)), rs.getObject(5) == null ? null : rs.getInt(5)))
                .list();
        if (defs.isEmpty()) {
            return 0;
        }
        YearMonth first = defs.stream().map(Def::start).min(YearMonth::compareTo).orElseThrow();
        if (first.isBefore(Check.FIRST_MONTH)) {
            first = Check.FIRST_MONTH;
        }
        if (first.isBefore(last.minusMonths(MAX_MONTHS - 1))) {
            first = last.minusMonths(MAX_MONTHS - 1);
        }
        Set<String> done = new HashSet<>(jdbc.sql("SELECT recurring_id || ':' || recurring_month FROM budget_expenses WHERE recurring_id IS NOT NULL AND recurring_month >= ?")
                .param(first.toString()).query(String.class).list());
        done.addAll(jdbc.sql("SELECT recurring_id || ':' || month FROM budget_recurring_skips").query(String.class).list());
        List<Object[]> missing = new ArrayList<>();
        for (Def d : defs) {
            for (YearMonth m : RecurringPlanner.months(first.isAfter(d.start()) ? first : d.start(), last)) {
                if (RecurringPlanner.isActive(d.start(), d.end(), m) && !done.contains(d.id() + ":" + m)) {
                    missing.add(new Object[] {d, m});
                }
            }
        }
        if (missing.isEmpty()) {
            return 0; // the common case: a read with no write transaction at all
        }
        Integer created = tx.execute(st -> {
            int n = 0;
            for (Object[] x : missing) {
                Def d = (Def) x[0];
                YearMonth m = (YearMonth) x[1];
                RecurringPlanner.EntryDate when = RecurringPlanner.dateIn(m, d.day());
                // The insert re-checks everything inside the write transaction, so two tabs opening a month at the same
                // time cannot create an entry twice (the unique index is the last line of defence).
                int inserted = jdbc.sql("""
                        INSERT INTO budget_expenses (name, amount_paise, paid_by, expense_date, day_set, recurring_id, recurring_month)
                        SELECT r.name, r.amount_paise, r.paid_by, ?, ?, r.id, ? FROM budget_recurring r
                        WHERE r.id = ?
                          AND NOT EXISTS (SELECT 1 FROM budget_expenses e WHERE e.recurring_id = r.id AND e.recurring_month = ?)
                          AND NOT EXISTS (SELECT 1 FROM budget_recurring_skips k WHERE k.recurring_id = r.id AND k.month = ?)
                          AND EXISTS (SELECT 1 FROM budget_recurring_people p WHERE p.recurring_id = r.id)""")
                        .params(when.date().toString(), when.daySet() ? 1 : 0, m.toString(), d.id(), m.toString(), m.toString())
                        .update();
                if (inserted == 0) {
                    continue;
                }
                long expenseId = jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
                long amount = jdbc.sql("SELECT amount_paise FROM budget_expenses WHERE id = ?").param(expenseId).query(Long.class).single();
                List<Long> people = jdbc.sql("SELECT person_id FROM budget_recurring_people WHERE recurring_id = ? ORDER BY person_id")
                        .param(d.id()).query(Long.class).list();
                insertShares(expenseId, Splitter.split(amount, people));
                n++;
            }
            return n;
        });
        return created == null ? 0 : created;
    }

    void insertShares(long expenseId, Map<Long, Long> shares) {
        for (Map.Entry<Long, Long> s : shares.entrySet()) {
            jdbc.sql("INSERT INTO budget_expense_shares (expense_id, person_id, share_paise) VALUES (?, ?, ?)")
                    .params(expenseId, s.getKey(), s.getValue()).update();
        }
    }

    // ---------------------------------------------------------------- statements

    /** The month screen: materialises recurring entries through that month, then returns everything the page shows. */
    public MonthView openMonth(YearMonth month) {
        if (month.isAfter(latestOpenable())) {
            throw new BudgetService.ValidationException(Map.of("month",
                    "Months more than " + MAX_MONTHS_AHEAD + " months ahead cannot be opened."));
        }
        materialise(month);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        StatementView s = statement(from, to);
        return new MonthView(month.toString(), from.toString(), to.toString(), today().toString(), s.people(), s.expenses(), s.statement(),
                s.settlements(), history(month, 6));
    }

    /** A statement for [from, to] (both inclusive). Callers materialise first when they want recurring entries. */
    public StatementView statement(LocalDate from, LocalDate to) {
        List<PersonRef> people = people();
        Map<Long, Boolean> archived = new LinkedHashMap<>();
        for (PersonRef p : people) {
            archived.put(p.id(), p.archived());
        }
        List<Expense> expenses = expenses(from, to);
        List<Settlement> settlements = settlements(from, to);
        long total = expenses.stream().mapToLong(Expense::amountPaise).sum();
        Statement st = BalanceCalculator.statement(archived, totals(null, from.minusDays(1)), totals(from, to),
                expenses.size(), total, !settlements.isEmpty());
        return new StatementView(from.toString(), to.toString(), people, st, expenses, settlements);
    }

    public List<PersonRef> people() {
        return jdbc.sql("SELECT id, name, archived FROM budget_people ORDER BY id")
                .query((rs, n) -> new PersonRef(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1)).list();
    }

    /** Sums per person over everything dated in [from, to]; a null {@code from} means "since the beginning". */
    Map<Long, Totals> totals(LocalDate from, LocalDate to) {
        String lo = from == null ? "0000-00-00" : from.toString();
        String hi = to.toString();
        Map<Long, long[]> t = new HashMap<>();
        collect(t, 0, "SELECT paid_by, SUM(amount_paise) FROM budget_expenses WHERE expense_date BETWEEN ? AND ? GROUP BY paid_by", lo, hi);
        collect(t, 1, "SELECT s.person_id, SUM(s.share_paise) FROM budget_expense_shares s JOIN budget_expenses e ON e.id = s.expense_id "
                + "WHERE e.expense_date BETWEEN ? AND ? GROUP BY s.person_id", lo, hi);
        collect(t, 2, "SELECT from_person, SUM(amount_paise) FROM budget_settlements WHERE settled_on BETWEEN ? AND ? GROUP BY from_person", lo, hi);
        collect(t, 3, "SELECT to_person, SUM(amount_paise) FROM budget_settlements WHERE settled_on BETWEEN ? AND ? GROUP BY to_person", lo, hi);
        Map<Long, Totals> out = new HashMap<>();
        t.forEach((id, v) -> out.put(id, new Totals(v[0], v[1], v[2], v[3])));
        return out;
    }

    private void collect(Map<Long, long[]> t, int slot, String sql, String lo, String hi) {
        jdbc.sql(sql).params(lo, hi).query((rs, n) -> {
            t.computeIfAbsent(rs.getLong(1), k -> new long[4])[slot] = rs.getLong(2);
            return null;
        }).list();
    }

    List<Expense> expenses(LocalDate from, LocalDate to) {
        Map<Long, List<Share>> shares = new HashMap<>();
        jdbc.sql("SELECT s.expense_id, s.person_id, s.share_paise FROM budget_expense_shares s JOIN budget_expenses e ON e.id = s.expense_id "
                        + "WHERE e.expense_date BETWEEN ? AND ? ORDER BY s.expense_id, s.person_id")
                .params(from.toString(), to.toString()).query((rs, n) -> {
                    shares.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Share(rs.getLong(2), rs.getLong(3)));
                    return null;
                }).list();
        return jdbc.sql("SELECT id, name, amount_paise, paid_by, expense_date, day_set, recurring_id, recurring_month, version "
                        + "FROM budget_expenses WHERE expense_date BETWEEN ? AND ? ORDER BY expense_date, id")
                .params(from.toString(), to.toString())
                .query((rs, n) -> new Expense(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                        rs.getInt(6) == 1, (Long) longOrNull(rs.getObject(7)), rs.getString(8), rs.getInt(9),
                        shares.getOrDefault(rs.getLong(1), List.of())))
                .list();
    }

    Expense expense(long id) {
        List<Share> shares = jdbc.sql("SELECT person_id, share_paise FROM budget_expense_shares WHERE expense_id = ? ORDER BY person_id")
                .param(id).query((rs, n) -> new Share(rs.getLong(1), rs.getLong(2))).list();
        return jdbc.sql("SELECT id, name, amount_paise, paid_by, expense_date, day_set, recurring_id, recurring_month, version "
                        + "FROM budget_expenses WHERE id = ?")
                .param(id)
                .query((rs, n) -> new Expense(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                        rs.getInt(6) == 1, (Long) longOrNull(rs.getObject(7)), rs.getString(8), rs.getInt(9), shares))
                .optional().orElseThrow(() -> new java.util.NoSuchElementException("No such expense"));
    }

    List<Settlement> settlements(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT id, from_person, to_person, amount_paise, settled_on, note FROM budget_settlements "
                        + "WHERE settled_on BETWEEN ? AND ? ORDER BY settled_on, id")
                .params(from.toString(), to.toString())
                .query((rs, n) -> new Settlement(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6)))
                .list();
    }

    /** What everyone paid in each of the {@code months} months ending with {@code to} (months with no expenses are left out, except {@code to}). */
    public List<HistoryMonth> history(YearMonth to, int months) {
        YearMonth from = to.minusMonths(Math.max(1, Math.min(months, 24)) - 1L);
        Map<String, Map<String, Long>> byMonth = new LinkedHashMap<>();
        for (YearMonth m : RecurringPlanner.months(from, to)) {
            byMonth.put(m.toString(), new LinkedHashMap<>());
        }
        jdbc.sql("SELECT substr(expense_date, 1, 7), paid_by, SUM(amount_paise) FROM budget_expenses WHERE expense_date BETWEEN ? AND ? "
                        + "GROUP BY 1, 2 ORDER BY 1, 2")
                .params(from.atDay(1).toString(), to.atEndOfMonth().toString()).query((rs, n) -> {
                    byMonth.get(rs.getString(1)).put(Long.toString(rs.getLong(2)), rs.getLong(3));
                    return null;
                }).list();
        List<HistoryMonth> out = new ArrayList<>();
        byMonth.forEach((m, paid) -> {
            long total = paid.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0 || m.equals(to.toString())) {
                out.add(new HistoryMonth(m, paid, total));
            }
        });
        return out;
    }

    private static Object longOrNull(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
