package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.BalanceCalculator.Statement;
import java.util.List;
import java.util.Map;

/** JSON shapes of the budget API (plan 5.4). All amounts are integer paise, dates ISO strings. */
public final class BudgetModels {

    private BudgetModels() {}

    public record Person(long id, String name, boolean archived, int version, long expenseCount, long recurringCount) {}

    /** The short form used inside month views and statements. */
    public record PersonRef(long id, String name, boolean archived) {}

    public record Share(long personId, long paise) {}

    public record Expense(long id, String name, long amountPaise, long paidBy, String date, boolean daySet,
                          Long recurringId, String recurringMonth, int version, List<Share> shares) {}

    public record Recurring(long id, String name, long amountPaise, long paidBy, List<Long> splitIds, String startMonth,
                            String endMonth, Integer day, int version, boolean ended) {}

    public record Settlement(long id, long fromId, long toId, long paise, String date, String note) {}

    public record HistoryMonth(String month, Map<String, Long> paidPaise, long totalPaise) {}

    /** {@code today} is the date in the budget's zone (used for "This month" and the default settlement date). */
    public record MonthView(String month, String from, String to, String today, List<PersonRef> people, List<Expense> expenses,
                            Statement statement, List<Settlement> settlements, List<HistoryMonth> history) {}

    /** A statement for any date range (the export preview and the PDF use the same object). */
    public record StatementView(String from, String to, List<PersonRef> people, Statement statement,
                                List<Expense> expenses, List<Settlement> settlements) {}
}
