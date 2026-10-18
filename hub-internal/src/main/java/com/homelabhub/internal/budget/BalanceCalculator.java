package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.TransferPlanner.Transfer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The period statement (plan 5.3, D4). Balances are a running ledger: {@code net = paid - share + settlements made -
 * settlements received}. "Carried in" is the net just before the period; "to settle" is the net at its end.
 */
public final class BalanceCalculator {

    /** One person's sums over some set of expenses and settlements. */
    public record Totals(long paid, long share, long settledOut, long settledIn) {
        public static final Totals ZERO = new Totals(0, 0, 0, 0);

        public long net() {
            return paid - share + settledOut - settledIn;
        }

        public boolean isZero() {
            return paid == 0 && share == 0 && settledOut == 0 && settledIn == 0;
        }
    }

    public record Row(long personId, long paidPaise, long sharePaise, long periodNetPaise, long carriedInPaise,
                      long settledPaise, long toSettlePaise) {}

    public record Sums(long paidPaise, long sharePaise, long periodNetPaise, long carriedInPaise, long settledPaise,
                       long toSettlePaise) {}

    public record Statement(List<Row> rows, Sums totals, List<Transfer> transfers, List<Transfer> openingTransfers,
                            long expenseCount, long expenseTotalPaise, boolean hasSettlements) {}

    private BalanceCalculator() {}

    /**
     * @param people      every person, in id order, with whether they are archived
     * @param before      sums over everything dated before the period (the carried-in balance)
     * @param inPeriod    sums over everything dated inside the period
     */
    public static Statement statement(Map<Long, Boolean> people, Map<Long, Totals> before, Map<Long, Totals> inPeriod,
                                      long expenseCount, long expenseTotalPaise, boolean hasSettlements) {
        List<Row> rows = new ArrayList<>();
        Map<Long, Long> closing = new LinkedHashMap<>();
        Map<Long, Long> opening = new LinkedHashMap<>();
        long paid = 0, share = 0, periodNet = 0, carried = 0, settled = 0, toSettle = 0;
        Set<Long> ids = people.keySet();
        for (long id : ids) {
            Totals b = before.getOrDefault(id, Totals.ZERO);
            Totals p = inPeriod.getOrDefault(id, Totals.ZERO);
            long open = b.net();
            long net = p.paid() - p.share();
            long settledEffect = p.settledOut() - p.settledIn();
            long end = open + net + settledEffect;
            boolean archived = people.get(id);
            // Archived people only appear while they still matter for this period.
            if (archived && p.isZero() && open == 0 && end == 0) {
                continue;
            }
            rows.add(new Row(id, p.paid(), p.share(), net, open, settledEffect, end));
            opening.put(id, open);
            closing.put(id, end);
            paid += p.paid();
            share += p.share();
            periodNet += net;
            carried += open;
            settled += settledEffect;
            toSettle += end;
        }
        return new Statement(rows, new Sums(paid, share, periodNet, carried, settled, toSettle),
                TransferPlanner.plan(closing), TransferPlanner.plan(opening), expenseCount, expenseTotalPaise, hasSettlements);
    }
}
