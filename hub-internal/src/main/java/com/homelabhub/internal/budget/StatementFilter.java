package com.homelabhub.internal.budget;

import com.homelabhub.internal.budget.BudgetModels.Expense;
import com.homelabhub.internal.budget.BudgetModels.PersonRef;
import com.homelabhub.internal.budget.BudgetModels.StatementView;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The view filter of the export: "split only" and/or a set of people. Balances and "who pays whom" are never filtered,
 * only the list of entries. Split means the entry has more than one share row, counted as stored, whoever paid it.
 */
record StatementFilter(boolean split, Set<Long> people) {

    static final StatementFilter NONE = new StatementFilter(false, Set.of());

    boolean active() {
        return split || !people.isEmpty();
    }

    /** Parses the query parameters; {@code people} is a comma separated list of person ids. */
    static StatementFilter parse(String splitText, String peopleText) {
        boolean split = splitText != null && (splitText.equalsIgnoreCase("true") || splitText.equals("1"));
        Set<Long> ids = new LinkedHashSet<>();
        if (peopleText != null && !peopleText.isBlank()) {
            for (String part : peopleText.split(",")) {
                try {
                    long id = Long.parseLong(part.trim());
                    if (id <= 0) {
                        throw new NumberFormatException();
                    }
                    ids.add(id);
                } catch (NumberFormatException e) {
                    throw new BudgetService.ValidationException(Map.of("people", "Give person ids separated by commas, for example 1,2."));
                }
            }
        }
        return new StatementFilter(split, ids);
    }

    boolean matches(Expense e) {
        if (split && e.shares().size() <= 1) {
            return false;
        }
        if (people.isEmpty()) {
            return true;
        }
        return people.contains(e.paidBy()) || e.shares().stream().anyMatch(s -> people.contains(s.personId()));
    }

    StatementView apply(StatementView v) {
        if (!active()) {
            return v;
        }
        return new StatementView(v.from(), v.to(), v.people(), v.statement(), v.expenses().stream().filter(this::matches).toList(), v.settlements());
    }

    /** One line for the PDF header, for example "Split entries only; paid by or shared with Asha, Ravi". */
    String describe(List<PersonRef> all) {
        Map<Long, String> names = new LinkedHashMap<>();
        all.forEach(p -> names.put(p.id(), p.name()));
        List<String> parts = new java.util.ArrayList<>();
        if (split) {
            parts.add("split entries only");
        }
        if (!people.isEmpty()) {
            parts.add("paid by or shared with " + people.stream().map(id -> names.getOrDefault(id, "#" + id)).collect(Collectors.joining(", ")));
        }
        String s = String.join("; ", parts);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
