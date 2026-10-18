package com.homelabhub.internal.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homelabhub.internal.budget.BalanceCalculator.Row;
import com.homelabhub.internal.budget.BalanceCalculator.Statement;
import com.homelabhub.internal.budget.BalanceCalculator.Totals;
import com.homelabhub.internal.budget.TransferPlanner.Transfer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Pure budget logic (plan phase 1): the reference data of plan 5.6 and property checks over random input. */
class BudgetDomainTest {

    static final long ANU = 1, RAVI = 2, MEERA = 3;

    // ---------------------------------------------------------------- money

    @Test
    void formatsWithIndianGrouping() {
        assertThat(Money.format(0)).isEqualTo("₹0.00");
        assertThat(Money.format(5)).isEqualTo("₹0.05");
        assertThat(Money.format(99)).isEqualTo("₹0.99");
        assertThat(Money.format(100000)).isEqualTo("₹1,000.00");
        assertThat(Money.format(12500050)).isEqualTo("₹1,25,000.50");
        assertThat(Money.format(123456789)).isEqualTo("₹12,34,567.89");
        assertThat(Money.format(10_000_000_000L)).isEqualTo("₹10,00,00,000.00");
        assertThat(Money.format(-30000)).isEqualTo("−₹300.00");
        assertThat(Money.plainSigned(-821750)).isEqualTo("−8,217.50");
        assertThat(Money.withSign(1638349)).isEqualTo("+16,383.49");
        assertThat(Money.withSign(0)).isEqualTo("0.00");
        assertThat(Money.group("1234567")).isEqualTo("12,34,567");
        assertThat(Money.group("123")).isEqualTo("123");
        assertThat(Money.group("1234")).isEqualTo("1,234");
    }

    @Test
    void parsesRupeeTextExactly() {
        assertThat(Money.parse("1850")).isEqualTo(185000);
        assertThat(Money.parse("1850.5")).isEqualTo(185050);
        assertThat(Money.parse("1,850.50")).isEqualTo(185050);
        assertThat(Money.parse("1,25,000.50")).isEqualTo(12500050);
        assertThat(Money.parse(" 12 ")).isEqualTo(1200);
        assertThat(Money.parse("12.")).isEqualTo(1200);
        assertThat(Money.parse(".5")).isEqualTo(50);
        assertThat(Money.parse("0.01")).isEqualTo(1);
        assertThat(Money.parse("100000000")).isEqualTo(Money.MAX_PAISE);
        for (String bad : new String[] {"", " ", ".", "0", "0.00", "1e3", "-5", "1.234", "abc", "12a", "99999999999",
                "100000000.01", "1,,000", ",100", "1.2.3", "+5", null}) {
            assertThat(Money.parse(bad)).as("'%s'", bad).isNull();
        }
    }

    // ---------------------------------------------------------------- split

    @Test
    void splitHandsLeftoverPaiseToLowestIdsFirst() {
        assertThat(Splitter.split(185050, List.of(MEERA, ANU, RAVI))).containsExactly(
                Map.entry(ANU, 61684L), Map.entry(RAVI, 61683L), Map.entry(MEERA, 61683L));
        assertThat(Splitter.split(119900, List.of(ANU, RAVI, MEERA)).values()).containsExactly(39967L, 39967L, 39966L);
        assertThat(Splitter.split(7, List.of(5L))).containsExactly(Map.entry(5L, 7L));
        assertThat(Splitter.split(1, List.of(1L, 2L, 3L)).values()).containsExactly(1L, 0L, 0L);
        assertThatThrownBy(() -> Splitter.split(100, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Splitter.split(100, List.of(1L, 1L))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitPropertiesHoldForRandomInput() {
        Random r = new Random(42);
        for (int i = 0; i < 5000; i++) {
            int n = 1 + r.nextInt(50);
            long amount = 1 + (long) (r.nextDouble() * Money.MAX_PAISE);
            List<Long> ids = new ArrayList<>();
            while (ids.size() < n) {
                long id = 1 + r.nextInt(500);
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
            Map<Long, Long> s = Splitter.split(amount, ids);
            assertThat(s.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(amount);
            long min = s.values().stream().mapToLong(Long::longValue).min().orElseThrow();
            long max = s.values().stream().mapToLong(Long::longValue).max().orElseThrow();
            assertThat(max - min).isLessThanOrEqualTo(1);
            // the larger shares are always the lowest ids
            List<Long> vals = new ArrayList<>(s.values());
            for (int k = 1; k < vals.size(); k++) {
                assertThat(vals.get(k)).isLessThanOrEqualTo(vals.get(k - 1));
            }
        }
    }

    // ---------------------------------------------------------------- reference statement (plan 5.6)

    record Exp(LocalDate date, long paidBy, long amount, List<Long> split) {}

    record Set(LocalDate date, long from, long to, long amount) {}

    static List<Exp> reference() {
        List<Exp> e = new ArrayList<>();
        e.add(new Exp(LocalDate.parse("2026-08-10"), MEERA, 60000, List.of(RAVI, MEERA))); // leaves Ravi owing Meera 300.00
        e.add(new Exp(LocalDate.parse("2026-09-01"), ANU, 3000000, List.of(ANU, RAVI, MEERA)));
        e.add(new Exp(LocalDate.parse("2026-09-05"), RAVI, 119900, List.of(ANU, RAVI, MEERA)));
        e.add(new Exp(LocalDate.parse("2026-09-12"), RAVI, 240000, List.of(ANU, RAVI, MEERA)));
        e.add(new Exp(LocalDate.parse("2026-09-15"), MEERA, 185050, List.of(ANU, RAVI, MEERA)));
        e.add(new Exp(LocalDate.parse("2026-09-20"), MEERA, 360000, List.of(ANU, MEERA)));
        return e;
    }

    static Statement statementFor(List<Exp> exps, List<Set> sets, LocalDate from, LocalDate to) {
        Map<Long, long[]> before = new HashMap<>();
        Map<Long, long[]> in = new HashMap<>();
        long count = 0, total = 0;
        boolean anySettlement = false;
        for (Exp e : exps) {
            Map<Long, long[]> t = e.date.isBefore(from) ? before : !e.date.isAfter(to) ? in : null;
            if (t == null) {
                continue;
            }
            if (t == in) {
                count++;
                total += e.amount;
            }
            t.computeIfAbsent(e.paidBy, k -> new long[4])[0] += e.amount;
            Splitter.split(e.amount, e.split).forEach((p, s) -> t.computeIfAbsent(p, k -> new long[4])[1] += s);
        }
        for (Set s : sets) {
            Map<Long, long[]> t = s.date.isBefore(from) ? before : !s.date.isAfter(to) ? in : null;
            if (t == null) {
                continue;
            }
            anySettlement |= t == in;
            t.computeIfAbsent(s.from, k -> new long[4])[2] += s.amount;
            t.computeIfAbsent(s.to, k -> new long[4])[3] += s.amount;
        }
        Map<Long, Boolean> people = new LinkedHashMap<>();
        people.put(ANU, false);
        people.put(RAVI, false);
        people.put(MEERA, false);
        return BalanceCalculator.statement(people, totals(before), totals(in), count, total, anySettlement);
    }

    static Map<Long, Totals> totals(Map<Long, long[]> m) {
        Map<Long, Totals> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, new Totals(v[0], v[1], v[2], v[3])));
        return out;
    }

    @Test
    void septemberReferenceStatementMatchesThePlan() {
        Statement s = statementFor(reference(), List.of(), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
        assertThat(s.rows()).containsExactly(
                new Row(ANU, 3000000, 1361651, 1638349, 0, 0, 1638349),
                new Row(RAVI, 359900, 1181650, -821750, -30000, 0, -851750),
                new Row(MEERA, 545050, 1361649, -816599, 30000, 0, -786599));
        assertThat(s.totals()).isEqualTo(new BalanceCalculator.Sums(3904950, 3904950, 0, 0, 0, 0));
        assertThat(s.transfers()).containsExactly(new Transfer(RAVI, ANU, 851750), new Transfer(MEERA, ANU, 786599));
        assertThat(s.openingTransfers()).containsExactly(new Transfer(RAVI, MEERA, 30000));
        assertThat(s.expenseCount()).isEqualTo(5);
        assertThat(s.expenseTotalPaise()).isEqualTo(3904950);
        assertThat(s.hasSettlements()).isFalse();
    }

    @Test
    void aPartPaymentLeavesASmallerRemainder() {
        List<Set> sets = List.of(new Set(LocalDate.parse("2026-09-25"), RAVI, ANU, 500000));
        Statement s = statementFor(reference(), sets, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
        assertThat(s.rows().get(1).toSettlePaise()).isEqualTo(-351750);
        assertThat(s.rows().get(1).settledPaise()).isEqualTo(500000);
        assertThat(s.rows().get(0).settledPaise()).isEqualTo(-500000);
        // Meera now owes more than Ravi, so her transfer comes first (largest debtor first)
        assertThat(s.transfers()).containsExactly(new Transfer(MEERA, ANU, 786599), new Transfer(RAVI, ANU, 351750));
        assertThat(s.hasSettlements()).isTrue();
        // October starts with what is left
        Statement oct = statementFor(reference(), sets, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
        assertThat(oct.rows().get(1).carriedInPaise()).isEqualTo(-351750);
        assertThat(oct.transfers()).containsExactly(new Transfer(MEERA, ANU, 786599), new Transfer(RAVI, ANU, 351750));
    }

    @Test
    void aRangeCountsMonthOnlyEntriesAsThe15th() {
        Statement s = statementFor(reference(), List.of(), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10"));
        assertThat(s.expenseCount()).isEqualTo(2); // rent and internet
        Statement s2 = statementFor(reference(), List.of(), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-15"));
        assertThat(s2.expenseCount()).isEqualTo(4);
    }

    @Test
    void archivedPeopleAppearOnlyWhileTheyMatter() {
        Map<Long, Boolean> people = new LinkedHashMap<>();
        people.put(1L, false);
        people.put(2L, true);
        people.put(3L, true);
        Map<Long, Totals> before = Map.of(3L, new Totals(100, 0, 0, 0), 1L, new Totals(0, 100, 0, 0));
        Statement s = BalanceCalculator.statement(people, before, Map.of(), 0, 0, false);
        assertThat(s.rows()).extracting(Row::personId).containsExactly(1L, 3L);
        assertThat(s.transfers()).containsExactly(new Transfer(1, 3, 100));
    }

    // ---------------------------------------------------------------- transfers

    @Test
    void plannerIsDeterministicAndSmall() {
        Map<Long, Long> nets = new LinkedHashMap<>();
        nets.put(1L, 500L);
        nets.put(2L, -500L);
        nets.put(3L, 500L);
        nets.put(4L, -500L);
        // equal amounts: ties go by person id
        assertThat(TransferPlanner.plan(nets)).containsExactly(new Transfer(2, 1, 500), new Transfer(4, 3, 500));
        assertThat(TransferPlanner.plan(Map.of())).isEmpty();
        assertThat(TransferPlanner.plan(Map.of(1L, 0L, 2L, 0L))).isEmpty();
    }

    @Test
    void plannedTransfersZeroEveryBalance() {
        Random r = new Random(7);
        for (int i = 0; i < 3000; i++) {
            int n = 1 + r.nextInt(12);
            Map<Long, Long> nets = new LinkedHashMap<>();
            long sum = 0;
            for (long id = 1; id < n; id++) {
                long v = (long) ((r.nextDouble() - 0.5) * 2_000_000_00L);
                nets.put(id, v);
                sum += v;
            }
            nets.put((long) n, -sum);
            List<Transfer> plan = TransferPlanner.plan(nets);
            long nonZero = nets.values().stream().filter(v -> v != 0).count();
            assertThat(plan.size()).isLessThanOrEqualTo((int) Math.max(0, nonZero - 1));
            Map<Long, Long> after = new HashMap<>(nets);
            for (Transfer t : plan) {
                assertThat(t.paise()).isPositive();
                after.merge(t.fromId(), t.paise(), Long::sum);
                after.merge(t.toId(), -t.paise(), Long::sum);
            }
            assertThat(after.values()).allMatch(v -> v == 0);
            assertThat(TransferPlanner.plan(new LinkedHashMap<>(nets))).isEqualTo(plan);
        }
    }

    @Test
    void randomLedgersAlwaysBalance() {
        Random r = new Random(99);
        for (int i = 0; i < 500; i++) {
            List<Exp> exps = new ArrayList<>();
            for (int k = 0; k < 20; k++) {
                List<Long> split = new ArrayList<>(List.of(ANU, RAVI, MEERA));
                split.remove(r.nextInt(3));
                if (r.nextBoolean()) {
                    split.add(k % 2 == 0 ? ANU : RAVI);
                    split = split.stream().distinct().toList();
                }
                exps.add(new Exp(LocalDate.parse("2026-01-01").plusDays(r.nextInt(365)), 1 + r.nextInt(3),
                        1 + r.nextInt(10_000_000), split));
            }
            Statement s = statementFor(exps, List.of(), LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
            assertThat(s.totals().paidPaise()).isEqualTo(s.totals().sharePaise());
            assertThat(s.totals().toSettlePaise()).isZero();
            assertThat(s.totals().carriedInPaise()).isZero();
            for (Row row : s.rows()) {
                assertThat(row.carriedInPaise() + row.periodNetPaise() + row.settledPaise()).isEqualTo(row.toSettlePaise());
            }
        }
    }

    // ---------------------------------------------------------------- recurring

    @Test
    void recurringDatesClampToTheMonth() {
        assertThat(RecurringPlanner.dateIn(YearMonth.of(2026, 2), 31).date()).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(RecurringPlanner.dateIn(YearMonth.of(2028, 2), 31).date()).isEqualTo(LocalDate.parse("2028-02-29"));
        assertThat(RecurringPlanner.dateIn(YearMonth.of(2026, 2), 30).date()).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(RecurringPlanner.dateIn(YearMonth.of(2026, 4), 31).date()).isEqualTo(LocalDate.parse("2026-04-30"));
        assertThat(RecurringPlanner.dateIn(YearMonth.of(2026, 9), 1)).isEqualTo(new RecurringPlanner.EntryDate(LocalDate.parse("2026-09-01"), true));
        assertThat(RecurringPlanner.dateIn(YearMonth.of(2026, 9), null)).isEqualTo(new RecurringPlanner.EntryDate(LocalDate.parse("2026-09-15"), false));
    }

    @Test
    void recurringActiveMonthsIncludeTheEndMonth() {
        YearMonth start = YearMonth.of(2026, 3), end = YearMonth.of(2027, 2);
        assertThat(RecurringPlanner.isActive(start, end, YearMonth.of(2026, 2))).isFalse();
        assertThat(RecurringPlanner.isActive(start, end, YearMonth.of(2026, 3))).isTrue();
        assertThat(RecurringPlanner.isActive(start, end, YearMonth.of(2027, 2))).isTrue();
        assertThat(RecurringPlanner.isActive(start, end, YearMonth.of(2027, 3))).isFalse();
        assertThat(RecurringPlanner.isActive(start, start, start)).isTrue();
        assertThat(RecurringPlanner.isActive(start, null, YearMonth.of(2099, 1))).isTrue();
        assertThat(RecurringPlanner.months(YearMonth.of(2026, 11), YearMonth.of(2027, 2))).hasSize(4);
        assertThat(RecurringPlanner.months(YearMonth.of(2026, 11), YearMonth.of(2026, 10))).isEmpty();
    }
}
