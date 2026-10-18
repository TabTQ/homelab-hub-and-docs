package com.homelabhub.internal.budget;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Which months a recurring expense applies to, and the date its entry gets in a month (plan 5.3). */
public final class RecurringPlanner {

    /** A month-only entry counts as this day (plan D16). */
    public static final int NO_DAY = 15;

    /** The effective date of an entry, and whether the owner gave a day (false = shown as "Sep (no day)"). */
    public record EntryDate(LocalDate date, boolean daySet) {}

    private RecurringPlanner() {}

    /** Active in {@code month} when it started on or before it and has not ended before it (the end month is inclusive). */
    public static boolean isActive(YearMonth start, YearMonth end, YearMonth month) {
        return !start.isAfter(month) && (end == null || !end.isBefore(month));
    }

    /** No day -> the 15th; a day past the month's end (31 in April, 30 in February) -> the month's last day. */
    public static EntryDate dateIn(YearMonth month, Integer dayOfMonth) {
        if (dayOfMonth == null) {
            return new EntryDate(month.atDay(NO_DAY), false);
        }
        return new EntryDate(month.atDay(Math.min(dayOfMonth, month.lengthOfMonth())), true);
    }

    /** Months {@code from..to} inclusive; empty when from is after to. */
    public static List<YearMonth> months(YearMonth from, YearMonth to) {
        List<YearMonth> out = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            out.add(m);
        }
        return out;
    }
}
