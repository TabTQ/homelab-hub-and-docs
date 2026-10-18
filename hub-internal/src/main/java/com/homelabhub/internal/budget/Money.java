package com.homelabhub.internal.budget;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money is integer paise everywhere (plan D1). This formats paise as rupees with Indian digit grouping
 * ({@code ₹1,25,000.50}) and parses rupee text exactly, without floating point. Hand-written on purpose: a slim JRE may
 * not carry the en-IN locale data. The TypeScript twin is {@code web/src/internal/budget/money.ts}; both are tested with
 * the same vectors.
 */
public final class Money {

    /** ₹10,00,00,000.00, the largest amount one expense, recurring item or settlement may have (plan D19). */
    public static final long MAX_PAISE = 10_000_000_000L;
    public static final String RUPEE = "₹";
    public static final String MINUS = "−";

    private static final Pattern RUPEES = Pattern.compile("(\\d{1,3}(?:,\\d{2,3})*|\\d+)?(?:\\.(\\d{0,2}))?");

    private Money() {}

    /** {@code 12345678} -> {@code ₹1,23,456.78}; negative amounts get a real minus sign. */
    public static String format(long paise) {
        return (paise < 0 ? MINUS : "") + RUPEE + plain(Math.abs(paise));
    }

    /** Like {@link #format} without the ₹, for table cells ({@code 1,23,456.78}, {@code −300.00}). */
    public static String plainSigned(long paise) {
        return (paise < 0 ? MINUS : "") + plain(Math.abs(paise));
    }

    /** Like {@link #plainSigned} but positive amounts get a "+" (zero stays unsigned), for the net columns. */
    public static String withSign(long paise) {
        return (paise > 0 ? "+" : "") + plainSigned(paise);
    }

    private static String plain(long paise) {
        long rupees = paise / 100;
        long p = paise % 100;
        return group(Long.toString(rupees)) + "." + (p < 10 ? "0" : "") + p;
    }

    /** Indian grouping: the last three digits, then groups of two ("12345678" -> "1,23,45,678"). */
    static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String head = digits.substring(0, digits.length() - 3);
        StringBuilder sb = new StringBuilder();
        int first = head.length() % 2;
        if (first == 1) {
            sb.append(head.charAt(0));
        }
        for (int i = first; i < head.length(); i += 2) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(head, i, i + 2);
        }
        return sb.append(',').append(digits.substring(digits.length() - 3)).toString();
    }

    /**
     * Parses rupee text ("1850", "1850.5", "1,850.50", " 12 ") to paise. Returns null for anything else: empty, negative,
     * letters, exponents, more than two decimals, zero, or more than {@link #MAX_PAISE}. Commas are accepted anywhere
     * between digits groups, since people type both Indian and Western grouping.
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || t.equals(".")) {
            return null;
        }
        Matcher m = RUPEES.matcher(t);
        if (!m.matches()) {
            return null;
        }
        String whole = m.group(1) == null ? "0" : m.group(1).replace(",", "");
        String frac = m.group(2) == null ? "" : m.group(2);
        if (whole.length() > 9) {
            return null;
        }
        long paise = Long.parseLong(whole) * 100 + (frac.isEmpty() ? 0 : Long.parseLong((frac + "0").substring(0, 2)));
        return paise <= 0 || paise > MAX_PAISE ? null : paise;
    }
}
