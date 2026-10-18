package com.homelabhub.internal.budget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * "Who pays whom" (plan D7): repeatedly match the largest debtor with the largest creditor. Deterministic (ties by
 * person id) and at most n-1 transfers. It is not guaranteed to be the absolute minimum number of transfers in every
 * case (that problem is NP-hard); for a handful of people it is simple and easy to follow.
 */
public final class TransferPlanner {

    public record Transfer(long fromId, long toId, long paise) {}

    private TransferPlanner() {}

    /** Input: person id -> net balance (positive = is owed, negative = owes). The balances must sum to zero. */
    public static List<Transfer> plan(Map<Long, Long> nets) {
        List<long[]> debtors = new ArrayList<>();   // {id, amount owed (positive)}
        List<long[]> creditors = new ArrayList<>(); // {id, amount owed to them}
        for (Map.Entry<Long, Long> e : nets.entrySet()) {
            long v = e.getValue();
            if (v < 0) {
                debtors.add(new long[] {e.getKey(), -v});
            } else if (v > 0) {
                creditors.add(new long[] {e.getKey(), v});
            }
        }
        Comparator<long[]> order = Comparator.<long[]>comparingLong(a -> -a[1]).thenComparingLong(a -> a[0]);
        List<Transfer> out = new ArrayList<>();
        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            debtors.sort(order);
            creditors.sort(order);
            long[] d = debtors.get(0);
            long[] c = creditors.get(0);
            long t = Math.min(d[1], c[1]);
            out.add(new Transfer(d[0], c[0], t));
            d[1] -= t;
            c[1] -= t;
            if (d[1] == 0) {
                debtors.remove(0);
            }
            if (c[1] == 0) {
                creditors.remove(0);
            }
        }
        return out;
    }
}
