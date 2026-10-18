package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.Person;
import com.homelabhub.internal.budget.BudgetModels.Recurring;
import com.homelabhub.internal.budget.BudgetModels.Settlement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writing side of the budget: people, expenses, recurring expenses and settlements (plan 5.3). Validation is
 * server-side and field by field (422); edits use optimistic locking on {@code version} (409), like the portfolio editor.
 */
@Service
public class BudgetService {

    public static final class ValidationException extends RuntimeException {
        private final transient Map<String, String> errors;

        public ValidationException(Map<String, String> errors) {
            super("Invalid input");
            this.errors = errors;
        }

        public Map<String, String> errors() {
            return errors;
        }
    }

    /** 409: someone else changed the row, or a name is already taken. */
    public static final class ConflictException extends RuntimeException {
        private final transient Object current;
        private final transient Map<String, String> errors;

        public ConflictException(String message, Object current, Map<String, String> errors) {
            super(message);
            this.current = current;
            this.errors = errors;
        }

        public Object current() {
            return current;
        }

        public Map<String, String> errors() {
            return errors;
        }
    }

    private static final String STALE = "Someone else changed this while you were editing it.";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final BudgetLedger ledger;

    public BudgetService(JdbcClient jdbc, TransactionTemplate tx, BudgetLedger ledger) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledger = ledger;
    }

    // ---------------------------------------------------------------- people

    public List<Person> people() {
        return jdbc.sql("""
                SELECT p.id, p.name, p.archived, p.version,
                  (SELECT COUNT(*) FROM budget_expenses e WHERE e.paid_by = p.id
                     OR EXISTS (SELECT 1 FROM budget_expense_shares s WHERE s.expense_id = e.id AND s.person_id = p.id)),
                  (SELECT COUNT(*) FROM budget_recurring r WHERE r.paid_by = p.id
                     OR EXISTS (SELECT 1 FROM budget_recurring_people rp WHERE rp.recurring_id = r.id AND rp.person_id = p.id))
                FROM budget_people p ORDER BY p.archived, p.id""")
                .query((rs, n) -> new Person(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1, rs.getInt(4), rs.getLong(5), rs.getLong(6)))
                .list();
    }

    Person person(long id) {
        return people().stream().filter(p -> p.id() == id).findFirst().orElseThrow(() -> new NoSuchElementException("No such person"));
    }

    public Person addPerson(Map<String, Object> body) {
        Check c = new Check(body);
        String name = c.text("name", 60, true, "Name");
        c.done();
        long count = jdbc.sql("SELECT COUNT(*) FROM budget_people").query(Long.class).single();
        if (count >= Check.MAX_PEOPLE) {
            throw new ValidationException(Map.of("name", "At most " + Check.MAX_PEOPLE + " people can be added."));
        }
        requireUniqueName(name, null);
        long id = tx.execute(st -> {
            jdbc.sql("INSERT INTO budget_people (name) VALUES (?)").param(name).update();
            return jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
        });
        return person(id);
    }

    /** Rename, archive or restore. Archiving is refused while the person is on a recurring expense that has not ended (D10). */
    public Person updatePerson(long id, Map<String, Object> body) {
        Check c = new Check(body);
        String name = c.text("name", 60, true, "Name");
        Boolean archived = c.bool("archived");
        Integer version = c.version();
        c.done();
        Person current = person(id);
        boolean archive = archived != null ? archived : current.archived();
        if (archive && !current.archived()) {
            List<String> active = jdbc.sql("""
                    SELECT DISTINCT r.name FROM budget_recurring r
                    WHERE (r.end_month IS NULL OR r.end_month >= ?)
                      AND (r.paid_by = ? OR EXISTS (SELECT 1 FROM budget_recurring_people rp WHERE rp.recurring_id = r.id AND rp.person_id = ?))
                    ORDER BY r.name""")
                    .params(YearMonth.from(ledger.today()).toString(), id, id).query(String.class).list();
            if (!active.isEmpty()) {
                throw new ValidationException(Map.of("archived", current.name() + " is on recurring expenses that have not ended ("
                        + String.join(", ", active) + "). End them or take " + current.name() + " off them first."));
            }
        }
        requireUniqueName(name, id);
        int n = jdbc.sql("UPDATE budget_people SET name = ?, archived = ?, version = version + 1 WHERE id = ? AND version = ?")
                .params(name, archive ? 1 : 0, id, version).update();
        if (n == 0) {
            throw new ConflictException(STALE, person(id), null);
        }
        return person(id);
    }

    private void requireUniqueName(String name, Long except) {
        boolean taken = jdbc.sql("SELECT COUNT(*) FROM budget_people WHERE name = ? COLLATE NOCASE AND id <> ?")
                .params(name, except == null ? -1 : except).query(Long.class).single() > 0;
        if (taken) {
            throw new ConflictException("A person with that name already exists.", null, Map.of("name", "A person with that name already exists."));
        }
    }

    /** id -> archived, for the people a form refers to; unknown ids are reported on {@code field}. */
    private Map<Long, Boolean> known(Check c, List<Long> ids, String field) {
        Map<Long, Boolean> all = new HashMap<>();
        jdbc.sql("SELECT id, archived FROM budget_people").query((rs, n) -> all.put(rs.getLong(1), rs.getInt(2) == 1)).list();
        for (Long id : ids) {
            if (id != null && !all.containsKey(id)) {
                c.fail(field, "Unknown person.");
            }
        }
        return all;
    }

    // ---------------------------------------------------------------- expenses

    private record ExpenseInput(String name, long amount, long paidBy, LocalDate date, boolean daySet, List<Long> split) {}

    /** Validates an expense body. People already on {@code existing} may stay even if they were archived since. */
    private ExpenseInput expenseInput(Map<String, Object> body, Expense existing, Check c) {
        String name = c.text("name", 100, true, "Payment name");
        Long amount = c.paise("amountPaise");
        Long paidBy = c.id("paidBy", "Choose who paid.");
        YearMonth month = c.month("month", true, "Month");
        Integer day = null;
        if (month != null) {
            day = c.optionalInt("day", 1, month.lengthOfMonth(), "Pick a day between 1 and " + month.lengthOfMonth() + ".");
        }
        List<Long> split = c.ids("splitIds");
        List<Long> refs = new ArrayList<>();
        refs.add(paidBy);
        Map<Long, Boolean> people = known(c, refs, "paidBy");
        if (split != null) {
            known(c, split, "splitIds");
        }
        Set<Long> allowedArchived = new HashSet<>();
        if (existing != null) {
            allowedArchived.add(existing.paidBy());
            existing.shares().forEach(s -> allowedArchived.add(s.personId()));
        }
        if (paidBy != null && Boolean.TRUE.equals(people.get(paidBy)) && !allowedArchived.contains(paidBy)) {
            c.fail("paidBy", "That person is archived. Restore them first.");
        }
        if (split != null) {
            for (Long id : split) {
                if (Boolean.TRUE.equals(people.get(id)) && !allowedArchived.contains(id)) {
                    c.fail("splitIds", "An archived person cannot be added. Restore them first.");
                }
            }
        }
        c.done();
        RecurringPlanner.EntryDate when = RecurringPlanner.dateIn(month, day);
        return new ExpenseInput(name, amount, paidBy, when.date(), when.daySet(), split);
    }

    public Expense addExpense(Map<String, Object> body) {
        ExpenseInput in = expenseInput(body, null, new Check(body));
        long id = tx.execute(st -> {
            jdbc.sql("INSERT INTO budget_expenses (name, amount_paise, paid_by, expense_date, day_set) VALUES (?, ?, ?, ?, ?)")
                    .params(in.name(), in.amount(), in.paidBy(), in.date().toString(), in.daySet() ? 1 : 0).update();
            long newId = jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
            ledger.insertShares(newId, Splitter.split(in.amount(), in.split()));
            return newId;
        });
        return ledger.expense(id);
    }

    /** Edits one entry only; a generated entry keeps its recurring link, so it is not created again (D8). */
    public Expense updateExpense(long id, Map<String, Object> body) {
        Expense existing = ledger.expense(id);
        Check c = new Check(body);
        Integer version = c.version();
        ExpenseInput in = expenseInput(body, existing, c);
        tx.executeWithoutResult(st -> {
            int n = jdbc.sql("UPDATE budget_expenses SET name = ?, amount_paise = ?, paid_by = ?, expense_date = ?, day_set = ?, "
                            + "version = version + 1 WHERE id = ? AND version = ?")
                    .params(in.name(), in.amount(), in.paidBy(), in.date().toString(), in.daySet() ? 1 : 0, id, version).update();
            if (n == 0) {
                throw new ConflictException(STALE, ledger.expense(id), null);
            }
            jdbc.sql("DELETE FROM budget_expense_shares WHERE expense_id = ?").param(id).update();
            ledger.insertShares(id, Splitter.split(in.amount(), in.split()));
        });
        return ledger.expense(id);
    }

    /** Deleting a generated entry leaves a skip row so opening the month again does not bring it back. */
    public void deleteExpense(long id) {
        Expense existing = ledger.expense(id);
        tx.executeWithoutResult(st -> {
            if (existing.recurringId() != null && existing.recurringMonth() != null) {
                jdbc.sql("INSERT OR IGNORE INTO budget_recurring_skips (recurring_id, month) VALUES (?, ?)")
                        .params(existing.recurringId(), existing.recurringMonth()).update();
            }
            jdbc.sql("DELETE FROM budget_expenses WHERE id = ?").param(id).update();
        });
    }

    // ---------------------------------------------------------------- recurring

    public List<Recurring> recurring() {
        Map<Long, List<Long>> people = new HashMap<>();
        jdbc.sql("SELECT recurring_id, person_id FROM budget_recurring_people ORDER BY recurring_id, person_id").query((rs, n) -> {
            people.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
            return null;
        }).list();
        String now = YearMonth.from(ledger.today()).toString();
        return jdbc.sql("SELECT id, name, amount_paise, paid_by, start_month, end_month, day_of_month, version FROM budget_recurring ORDER BY name COLLATE NOCASE, id")
                .query((rs, n) -> new Recurring(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        people.getOrDefault(rs.getLong(1), List.of()), rs.getString(5), rs.getString(6),
                        rs.getObject(7) == null ? null : rs.getInt(7), rs.getInt(8),
                        rs.getString(6) != null && rs.getString(6).compareTo(now) < 0))
                .list();
    }

    Recurring recurring(long id) {
        return recurring().stream().filter(r -> r.id() == id).findFirst().orElseThrow(() -> new NoSuchElementException("No such recurring expense"));
    }

    private record RecurringInput(String name, long amount, long paidBy, List<Long> split, YearMonth start, YearMonth end, Integer day) {}

    private RecurringInput recurringInput(Map<String, Object> body, Recurring existing, Check c) {
        String name = c.text("name", 100, true, "Payment name");
        Long amount = c.paise("amountPaise");
        Long paidBy = c.id("paidBy", "Choose who pays.");
        List<Long> split = c.ids("splitIds");
        YearMonth start = c.month("startMonth", true, "Start month");
        YearMonth end = c.month("endMonth", false, "End month");
        Integer day = c.optionalInt("day", 1, 31, "The day must be between 1 and 31 (or empty for the 15th).");
        if (start != null && end != null && end.isBefore(start)) {
            c.fail("endMonth", "The end month cannot be before the start month.");
        }
        Map<Long, Boolean> people = known(c, paidBy == null ? List.of() : List.of(paidBy), "paidBy");
        if (split != null) {
            known(c, split, "splitIds");
        }
        Set<Long> allowedArchived = new HashSet<>();
        if (existing != null) {
            allowedArchived.add(existing.paidBy());
            allowedArchived.addAll(existing.splitIds());
        }
        if (paidBy != null && Boolean.TRUE.equals(people.get(paidBy)) && !allowedArchived.contains(paidBy)) {
            c.fail("paidBy", "That person is archived. Restore them first.");
        }
        if (split != null && split.stream().anyMatch(id -> Boolean.TRUE.equals(people.get(id)) && !allowedArchived.contains(id))) {
            c.fail("splitIds", "An archived person cannot be added. Restore them first.");
        }
        c.done();
        return new RecurringInput(name, amount, paidBy, split, start, end, day);
    }

    public Recurring addRecurring(Map<String, Object> body) {
        RecurringInput in = recurringInput(body, null, new Check(body));
        long id = tx.execute(st -> {
            jdbc.sql("INSERT INTO budget_recurring (name, amount_paise, paid_by, start_month, end_month, day_of_month) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(in.name(), in.amount(), in.paidBy(), in.start().toString(), in.end() == null ? null : in.end().toString(), in.day())
                    .update();
            long newId = jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
            setRecurringPeople(newId, in.split());
            return newId;
        });
        return recurring(id);
    }

    /** Changes only months whose entries do not exist yet; entries already created stay as they are (D8). */
    public Recurring updateRecurring(long id, Map<String, Object> body) {
        Recurring existing = recurring(id);
        Check c = new Check(body);
        Integer version = c.version();
        RecurringInput in = recurringInput(body, existing, c);
        tx.executeWithoutResult(st -> {
            int n = jdbc.sql("UPDATE budget_recurring SET name = ?, amount_paise = ?, paid_by = ?, start_month = ?, end_month = ?, "
                            + "day_of_month = ?, version = version + 1 WHERE id = ? AND version = ?")
                    .params(in.name(), in.amount(), in.paidBy(), in.start().toString(), in.end() == null ? null : in.end().toString(),
                            in.day(), id, version).update();
            if (n == 0) {
                throw new ConflictException(STALE, recurring(id), null);
            }
            jdbc.sql("DELETE FROM budget_recurring_people WHERE recurring_id = ?").param(id).update();
            setRecurringPeople(id, in.split());
        });
        return recurring(id);
    }

    private void setRecurringPeople(long id, List<Long> people) {
        for (Long p : people) {
            jdbc.sql("INSERT INTO budget_recurring_people (recurring_id, person_id) VALUES (?, ?)").params(id, p).update();
        }
    }

    /** Entries it already created are kept (their link is cleared by the foreign key). */
    public void deleteRecurring(long id) {
        recurring(id);
        jdbc.sql("DELETE FROM budget_recurring WHERE id = ?").param(id).update();
    }

    // ---------------------------------------------------------------- settlements

    /**
     * Records a real payment, also a part payment or an overpayment (D6). No date = today in the budget zone (D5), unless
     * the page says which month it was marked paid from ({@code month}) and that is not the current one: then the payment
     * counts on that month's last day, so marking a past month paid settles that month instead of the current one.
     */
    public Settlement addSettlement(Map<String, Object> body) {
        Check c = new Check(body);
        Long from = c.id("fromId", "Choose who paid.");
        Long to = c.id("toId", "Choose who was paid.");
        Long amount = c.paise("amountPaise");
        LocalDate date = c.date("date", "The date");
        String note = c.text("note", 200, false, "The note");
        YearMonth viewed = c.month("month", false, "The month");
        if (from != null && from.equals(to)) {
            c.fail("toId", "A person cannot pay themselves.");
        }
        List<Long> ids = new ArrayList<>();
        if (from != null) {
            ids.add(from);
        }
        known(c, ids, "fromId");
        known(c, to == null ? List.of() : List.of(to), "toId");
        c.done();
        LocalDate today = ledger.today();
        LocalDate on = date != null ? date : viewed != null && !viewed.equals(YearMonth.from(today)) ? viewed.atEndOfMonth() : today;
        long id = tx.execute(st -> {
            jdbc.sql("INSERT INTO budget_settlements (from_person, to_person, amount_paise, settled_on, note) VALUES (?, ?, ?, ?, ?)")
                    .params(from, to, amount, on.toString(), note).update();
            return jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
        });
        return jdbc.sql("SELECT id, from_person, to_person, amount_paise, settled_on, note FROM budget_settlements WHERE id = ?").param(id)
                .query((rs, n) -> new Settlement(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6)))
                .single();
    }

    public void deleteSettlement(long id) {
        if (jdbc.sql("DELETE FROM budget_settlements WHERE id = ?").param(id).update() == 0) {
            throw new NoSuchElementException("No such settlement");
        }
    }

    // ---------------------------------------------------------------- ranges

    /** Validates a statement range: from <= to, at most 5 years, inside 2000-01-01..2100-12-31. */
    public static LocalDate[] range(String fromText, String toText) {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate from = Check.parseDate(fromText);
        LocalDate to = Check.parseDate(toText);
        if (from == null) {
            errors.put("from", "The start date must be a date between 2000-01-01 and 2100-12-31.");
        }
        if (to == null) {
            errors.put("to", "The end date must be a date between 2000-01-01 and 2100-12-31.");
        }
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                errors.put("to", "The end date cannot be before the start date.");
            } else if (to.isAfter(from.plusYears(5))) {
                errors.put("to", "A statement can cover at most 5 years.");
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new LocalDate[] {from, to};
    }
}
