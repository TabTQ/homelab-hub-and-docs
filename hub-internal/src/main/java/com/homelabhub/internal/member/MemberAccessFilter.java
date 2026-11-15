package com.homelabhub.internal.member;

import com.homelabhub.auth.AuthSecurity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The single rule for member sessions, on every port: a member reaches only what {@link MemberAllowlist} lists, and
 * nothing else under /api, the legacy routes or the terminal WebSocket. Everything outside answers 403. It sits in the
 * Spring Security chain right before the authorization step and is not a servlet-container bean.
 *
 * <p>It also keeps a session honest: a login that was reset, disabled, archived or removed ends at its next request.
 */
public final class MemberAccessFilter extends OncePerRequestFilter {

    private final MemberAccounts accounts;

    public MemberAccessFilter(MemberAccounts accounts) {
        this.accounts = accounts;
    }

    static boolean isMember(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        if (auth.getPrincipal() instanceof MemberPrincipal) {
            return true;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_MEMBER".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!isMember(auth)) {
            chain.doFilter(req, res);
            return;
        }
        String path = MemberAllowlist.cleanPath(req);
        if (path != null && MemberAllowlist.isOpenMemberRoute(path)) {
            chain.doFilter(req, res); // these three look at the session themselves
            return;
        }
        MemberAllowlist.Match m = MemberAllowlist.match(req.getMethod(), path);
        if (!m.allowed()) {
            deny(res, HttpStatus.FORBIDDEN, "forbidden");
            return;
        }
        if (m.kind() == MemberAllowlist.Kind.STATIC) {
            chain.doFilter(req, res);
            return;
        }
        if (!(auth.getPrincipal() instanceof MemberPrincipal p)) {
            deny(res, HttpStatus.FORBIDDEN, "forbidden");
            return;
        }
        Optional<MemberAccounts.Account> account = accounts.valid(p);
        if (account.isEmpty()) {
            var s = req.getSession(false);
            if (s != null) {
                s.invalidate();
            }
            SecurityContextHolder.clearContext();
            deny(res, HttpStatus.UNAUTHORIZED, "unauthenticated");
            return;
        }
        MemberAccounts.Account a = account.get();
        if (m.kind() == MemberAllowlist.Kind.MEMBER_API) {
            chain.doFilter(req, res);
            return;
        }
        if (a.mustChange()) {
            deny(res, HttpStatus.FORBIDDEN, "password_change_required");
            return;
        }
        switch (m.kind()) {
            case BUDGET_READ -> chain.doFilter(req, res);
            case EXPENSE_ADD -> {
                if (a.canAdd()) {
                    chain.doFilter(req, res);
                } else {
                    deny(res, HttpStatus.FORBIDDEN, "forbidden");
                }
            }
            case EXPENSE_EDIT -> {
                // An entry that does not exist falls through to the controller's 404; one that is not theirs is refused.
                Optional<Long> creator = accounts.creatorOf(m.expenseId());
                if (a.canAdd() && (creator.isEmpty() || creator.get() == a.personId())) {
                    chain.doFilter(req, res);
                } else {
                    deny(res, HttpStatus.FORBIDDEN, "forbidden");
                }
            }
            default -> deny(res, HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    private static void deny(HttpServletResponse res, HttpStatus status, String error) throws IOException {
        AuthSecurity.json(res, status, "{\"error\":\"" + error + "\"}");
    }
}
