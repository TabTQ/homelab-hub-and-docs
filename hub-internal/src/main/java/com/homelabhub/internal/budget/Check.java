package com.homelabhub.internal.budget;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Field-by-field validation of a JSON body (plan 5.3). Collects one message per field; {@link #done()} throws a
 * {@link BudgetService.ValidationException} (422) when anything failed. Numbers must be JSON integers: a float such as
 * 18.5 is refused rather than silently truncated.
 */
final class Check {

    static final YearMonth FIRST_MONTH = YearMonth.of(2000, 1);
    static final YearMonth LAST_MONTH = YearMonth.of(2100, 12);
    static final int MAX_PEOPLE = 50;

    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private final Map<String, Object> body;
    private final Map<String, String> errors = new LinkedHashMap<>();

    Check(Map<String, Object> body) {
        this.body = body == null ? Map.of() : body;
    }

    boolean ok(String field) {
        return !errors.containsKey(field);
    }

    void fail(String field, String message) {
        errors.putIfAbsent(field, message);
    }

    void done() {
        if (!errors.isEmpty()) {
            throw new BudgetService.ValidationException(errors);
        }
    }

    boolean has(String field) {
        Object v = body.get(field);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    /** Trimmed text of 1..max characters with no control characters; optional text returns null when empty. */
    String text(String field, int max, boolean required, String label) {
        Object v = body.get(field);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                fail(field, label + " is required.");
            }
            return null;
        }
        if (!(v instanceof String s)) {
            fail(field, label + " must be text.");
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            fail(field, label + " can be at most " + max + " characters.");
            return null;
        }
        if (CONTROL.matcher(t).find()) {
            fail(field, label + " cannot contain control characters.");
            return null;
        }
        return t;
    }

    /** An amount in paise: a JSON integer from 1 paisa up to the cap. */
    Long paise(String field) {
        Object v = body.get(field);
        if (v == null) {
            fail(field, "Enter an amount.");
            return null;
        }
        Long n = integer(v);
        if (n == null) {
            fail(field, "The amount must be a whole number of paise.");
            return null;
        }
        if (n <= 0) {
            fail(field, "The amount must be more than zero.");
            return null;
        }
        if (n > Money.MAX_PAISE) {
            fail(field, "The amount can be at most " + Money.format(Money.MAX_PAISE) + ".");
            return null;
        }
        return n;
    }

    Long id(String field, String missing) {
        Object v = body.get(field);
        Long n = v == null ? null : integer(v);
        if (n == null || n <= 0) {
            fail(field, missing);
            return null;
        }
        return n;
    }

    Integer version() {
        Object v = body.get("version");
        Long n = v == null ? null : integer(v);
        if (n == null || n < 0 || n > Integer.MAX_VALUE) {
            fail("version", "The row's current version is required, so edits from another tab are not lost.");
            return null;
        }
        return n.intValue();
    }

    /** Optional whole number in [min, max]. */
    Integer optionalInt(String field, int min, int max, String message) {
        Object v = body.get(field);
        if (v == null || (v instanceof String s && s.isBlank())) {
            return null;
        }
        Long n = integer(v);
        if (n == null || n < min || n > max) {
            fail(field, message);
            return null;
        }
        return n.intValue();
    }

    Boolean bool(String field) {
        Object v = body.get(field);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Boolean b)) {
            fail(field, "Must be true or false.");
            return null;
        }
        return b;
    }

    /** A list of 1..50 distinct person ids. */
    List<Long> ids(String field) {
        Object v = body.get(field);
        if (!(v instanceof List<?> list)) {
            fail(field, "Choose at least one person to split with.");
            return null;
        }
        if (list.isEmpty()) {
            fail(field, "Choose at least one person to split with.");
            return null;
        }
        if (list.size() > MAX_PEOPLE) {
            fail(field, "At most " + MAX_PEOPLE + " people.");
            return null;
        }
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (Object o : list) {
            Long n = o == null ? null : integer(o);
            if (n == null || n <= 0) {
                fail(field, "Unknown person.");
                return null;
            }
            if (!out.add(n)) {
                fail(field, "A person is listed twice.");
                return null;
            }
        }
        return new ArrayList<>(out);
    }

    YearMonth month(String field, boolean required, String label) {
        Object v = body.get(field);
        if (v == null || (v instanceof String s && s.isBlank())) {
            if (required) {
                fail(field, label + " is required.");
            }
            return null;
        }
        YearMonth m = v instanceof String s ? parseMonth(s) : null;
        if (m == null) {
            fail(field, label + " must be a month between 2000-01 and 2100-12 (YYYY-MM).");
        }
        return m;
    }

    LocalDate date(String field, String label) {
        Object v = body.get(field);
        if (v == null || (v instanceof String s && s.isBlank())) {
            return null;
        }
        LocalDate d = v instanceof String s ? parseDate(s) : null;
        if (d == null) {
            fail(field, label + " must be a date between 2000-01-01 and 2100-12-31.");
        }
        return d;
    }

    static YearMonth parseMonth(String s) {
        if (s == null || !MONTH.matcher(s.trim()).matches()) {
            return null;
        }
        try {
            YearMonth m = YearMonth.parse(s.trim());
            return m.isBefore(FIRST_MONTH) || m.isAfter(LAST_MONTH) ? null : m;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalDate parseDate(String s) {
        if (s == null || !DATE.matcher(s.trim()).matches()) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(s.trim());
            YearMonth m = YearMonth.from(d);
            return m.isBefore(FIRST_MONTH) || m.isAfter(LAST_MONTH) ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Long integer(Object v) {
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof java.math.BigInteger b && b.bitLength() < 63) {
            return b.longValue();
        }
        return null;
    }
}
