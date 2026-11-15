package com.homelabhub.internal.member;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that says what a member may reach. Used twice: by {@link MemberAccessFilter} for member sessions on any
 * port, and by {@link MemberPortFilter} to decide what exists on the member port.
 *
 * <p>The allowlist: /api/member/**, GET on the budget read routes (people, history, recurring, statement, export.pdf,
 * months/{month}; POST months/{month}/open stays admin only because it creates entries), and, only for
 * accounts with can_add, POST /expenses and PUT or DELETE of an expense the member created.
 */
final class MemberAllowlist {

    static final String BUDGET = "/api/admin/budget";
    private static final Pattern MONTH = Pattern.compile("/api/admin/budget/months/[^/]+");
    private static final Pattern EXPENSE = Pattern.compile("/api/admin/budget/expenses/(\\d{1,18})");

    enum Kind {
        /** Not on the allowlist. */
        NONE,
        /** SPA files (plain GET of a path outside /api and the legacy routes). */
        STATIC,
        /** /api/member/** */
        MEMBER_API,
        /** The budget reads. */
        BUDGET_READ,
        /** POST /api/admin/budget/expenses (needs can_add). */
        EXPENSE_ADD,
        /** PUT or DELETE of one expense (needs can_add and ownership). */
        EXPENSE_EDIT
    }

    record Match(Kind kind, long expenseId) {
        static final Match NONE = new Match(Kind.NONE, 0);
        boolean allowed() {
            return kind != Kind.NONE;
        }
    }

    private MemberAllowlist() {}

    /**
     * The path as the application sees it. A request whose raw URI differs from it (";" parameters, percent escapes, double
     * slashes, dot segments) matches nothing: no tricks are needed by the real SPA.
     */
    static String cleanPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null || uri.indexOf(';') >= 0 || uri.indexOf('%') >= 0 || uri.contains("//") || uri.contains("/./") || uri.contains("/../")) {
            return null;
        }
        String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        return path.equals(uri) ? path : null;
    }

    static Match match(String method, String path) {
        if (path == null) {
            return Match.NONE;
        }
        boolean read = method.equals("GET") || method.equals("HEAD");
        if (path.startsWith("/api/member/")) {
            return new Match(Kind.MEMBER_API, 0);
        }
        if (read && (path.equals(BUDGET + "/people") || path.equals(BUDGET + "/history") || path.equals(BUDGET + "/recurring")
                || path.equals(BUDGET + "/statement") || path.equals(BUDGET + "/export.pdf"))) {
            return new Match(Kind.BUDGET_READ, 0);
        }
        if (read && MONTH.matcher(path).matches()) {
            return new Match(Kind.BUDGET_READ, 0);
        }
        if (method.equals("POST") && path.equals(BUDGET + "/expenses")) {
            return new Match(Kind.EXPENSE_ADD, 0);
        }
        if (method.equals("PUT") || method.equals("DELETE")) {
            Matcher m = EXPENSE.matcher(path);
            if (m.matches()) {
                return new Match(Kind.EXPENSE_EDIT, Long.parseLong(m.group(1)));
            }
        }
        if (read && isStaticPath(path)) {
            return new Match(Kind.STATIC, 0);
        }
        return Match.NONE;
    }

    /** Everything that is not an API, not a legacy open route, not the actuator, not the user's own image folder. */
    static boolean isStaticPath(String path) {
        return !(path.equals("/api") || path.startsWith("/api/") || isLegacy(path) || path.equals("/actuator")
                || path.startsWith("/actuator/") || path.startsWith("/images/") || path.equals("/error"));
    }

    static boolean isLegacy(String path) {
        return path.equals("/wake") || path.startsWith("/wake/") || path.equals("/toggle") || path.startsWith("/toggle/")
                || path.equals("/node") || path.startsWith("/node/");
    }

    /** The three member routes that need no login (also CSRF-exempt). */
    static boolean isOpenMemberRoute(String path) {
        return "/api/member/context".equals(path) || "/api/member/login".equals(path) || "/api/member/forgot".equals(path);
    }
}
