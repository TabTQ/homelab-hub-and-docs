package com.homelabhub.internal.member;

import com.homelabhub.auth.LoginRateLimiter;
import com.homelabhub.internal.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in for the people who share the ledger (members). Passwords are checked with bcrypt, one comparison per attempt
 * whether or not the name exists. Nothing here ever writes a password to a log or to the audit table.
 */
@RestController
@RequestMapping("/api/member")
class MemberController {

    /** bcrypt only uses the first 72 bytes, and hashing huge inputs is a cheap way to burn CPU (same cap as the admin login). */
    private static final int MAX_PASSWORD_LENGTH = 200;
    private static final int MIN_NEW_PASSWORD_LENGTH = 8;

    private final MemberAccounts accounts;
    private final MemberPort port;
    private final LoginRateLimiter limiter;
    /** Its own limiter, so a flood of "forgot" requests cannot lock the real logins (the limiters share a global failure count). */
    private final LoginRateLimiter forgotLimiter;
    private final SecurityContextRepository repo;
    /** A real bcrypt hash of a random string, compared against when no usable account matches so timing stays equal. */
    private final String dummyHash;

    MemberController(MemberAccounts accounts, MemberPort port, LoginRateLimiter limiter, SecurityContextRepository repo, Clock clock) {
        this.accounts = accounts;
        this.port = port;
        this.limiter = limiter;
        this.forgotLimiter = new LoginRateLimiter(clock);
        this.repo = repo;
        this.dummyHash = accounts.dummyHash();
    }

    record LoginRequest(String name, String password) {}

    record ForgotRequest(String name) {}

    record PasswordRequest(String current, String next) {}

    // ---------------------------------------------------------------- open routes

    @GetMapping("/context")
    Map<String, Object> context(HttpServletRequest req) {
        boolean onMemberPort = port.isMemberPort(req.getLocalPort());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memberPort", onMemberPort);
        String role = null;
        String name = null;
        Long personId = null;
        boolean canAdd = false;
        boolean mustChange = false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth.getPrincipal() instanceof String s && s.equals("anonymousUser"))) {
            if (auth.getPrincipal() instanceof MemberPrincipal p) {
                Optional<MemberAccounts.Account> a = accounts.valid(p);
                if (a.isPresent()) {
                    role = "member";
                    name = a.get().name();
                    personId = a.get().personId();
                    canAdd = a.get().canAdd();
                    mustChange = a.get().mustChange();
                } else {
                    endSession(req);
                }
            } else if (!onMemberPort) {
                // The admin cannot sign in on the member port, so a leftover admin cookie there counts as signed out.
                role = "admin";
                canAdd = true;
            }
        }
        out.put("authenticated", role != null);
        out.put("role", role);
        out.put("name", name);
        out.put("personId", personId);
        out.put("canAdd", canAdd);
        out.put("mustChange", mustChange);
        out.put("memberPortNumber", "admin".equals(role) && port.port() > 0 ? (Object) port.port() : null);
        return out;
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String ip = req.getRemoteAddr();
        String ua = req.getHeader("User-Agent");
        String key = "member:" + ip;
        LoginRateLimiter.Decision d = limiter.check(key);
        if (!d.allowed()) {
            long secs = Math.max(1, (d.retryAfter().toMillis() + 999) / 1000);
            accounts.auditUnknown("member-login", AuditLog.Result.denied, "rate limited", ip, ua);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(secs))
                    .body(Map.of("error", "too_many_attempts", "retryAfterSeconds", secs));
        }
        String name = body == null || body.name() == null ? "" : body.name().trim();
        String given = body == null || body.password() == null ? "" : body.password();
        boolean tooLong = given.length() > MAX_PASSWORD_LENGTH;
        Optional<MemberAccounts.Account> found = name.isEmpty() || name.length() > 60 ? Optional.empty() : accounts.findByName(name);
        // Always exactly one bcrypt comparison, so the time does not tell which names have a login.
        boolean matches = accounts.matches(tooLong ? "x" : given, found.map(MemberAccounts.Account::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || !found.get().active() || !matches || tooLong || given.isEmpty()) {
            limiter.recordFailure(key);
            if (found.isPresent()) {
                accounts.audit("member-login", found.get().personId(), AuditLog.Result.failed,
                        !found.get().active() ? "account off" : "wrong password", ip, ua);
            } else {
                accounts.auditUnknown("member-login", AuditLog.Result.failed, "unknown name", ip, ua);
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_credentials"));
        }
        MemberAccounts.Account a = found.get();
        limiter.recordSuccess(key);
        openSession(a, req, res);
        accounts.touchLogin(a.personId());
        accounts.audit("member-login", a.personId(), AuditLog.Result.ok, null, ip, ua);
        return ResponseEntity.ok(Map.of("authenticated", true, "mustChange", a.mustChange()));
    }

    @PostMapping("/forgot")
    ResponseEntity<Map<String, Object>> forgot(@RequestBody(required = false) ForgotRequest body, HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        String ua = req.getHeader("User-Agent");
        // Every request counts against the caller, so this cannot be used to fill the audit log or to probe names quickly.
        String key = "member-forgot:" + ip;
        LoginRateLimiter.Decision d = forgotLimiter.check(key);
        if (!d.allowed()) {
            long secs = Math.max(1, (d.retryAfter().toMillis() + 999) / 1000);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(secs))
                    .body(Map.of("error", "too_many_attempts", "retryAfterSeconds", secs));
        }
        forgotLimiter.recordFailure(key);
        String name = body == null || body.name() == null ? "" : body.name().trim();
        Optional<MemberAccounts.Account> found = name.isEmpty() || name.length() > 60 ? Optional.empty() : accounts.findByName(name);
        if (found.isPresent() && found.get().active()) {
            accounts.requestReset(found.get().personId());
            accounts.audit("member-forgot", found.get().personId(), AuditLog.Result.ok, "reset requested", ip, ua);
        } else {
            accounts.auditUnknown("member-forgot", AuditLog.Result.ok, "no usable login", ip, ua);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---------------------------------------------------------------- member session

    @PostMapping("/password")
    ResponseEntity<Map<String, Object>> password(@RequestBody(required = false) PasswordRequest body, HttpServletRequest req, HttpServletResponse res) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal p)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthenticated"));
        }
        Optional<MemberAccounts.Account> a = accounts.valid(p);
        if (a.isEmpty()) {
            endSession(req);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthenticated"));
        }
        String ip = req.getRemoteAddr();
        String ua = req.getHeader("User-Agent");
        String key = "member:" + ip;
        LoginRateLimiter.Decision d = limiter.check(key);
        if (!d.allowed()) {
            long secs = Math.max(1, (d.retryAfter().toMillis() + 999) / 1000);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(secs))
                    .body(Map.of("error", "too_many_attempts", "retryAfterSeconds", secs));
        }
        String current = body == null || body.current() == null ? "" : body.current();
        String next = body == null || body.next() == null ? "" : body.next();
        boolean currentTooLong = current.length() > MAX_PASSWORD_LENGTH;
        boolean currentOk = accounts.matches(currentTooLong ? "x" : current, a.get().passwordHash()) && !currentTooLong && !current.isEmpty();
        if (!currentOk) {
            limiter.recordFailure(key);
            accounts.audit("member-password", p.personId(), AuditLog.Result.failed, "wrong current password", ip, ua);
            return invalid("current", "That is not your current password.");
        }
        if (next.length() < MIN_NEW_PASSWORD_LENGTH || next.length() > MAX_PASSWORD_LENGTH) {
            return invalid("next", "Use " + MIN_NEW_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH + " characters.");
        }
        if (next.equals(current)) {
            return invalid("next", "Choose a password different from the current one.");
        }
        limiter.recordSuccess(key);
        String hash = accounts.setPassword(p.personId(), next);
        // The session continues under the new password; every other session of this member now has an old stamp and ends.
        MemberPrincipal renewed = new MemberPrincipal(p.personId(), p.name(), MemberAccounts.stamp(hash));
        req.changeSessionId();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(renewed, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        SecurityContextHolder.setContext(ctx);
        repo.saveContext(ctx, req, res);
        accounts.audit("member-password", p.personId(), AuditLog.Result.ok, "changed by the member", ip, ua);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest req) {
        endSession(req);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- helpers

    private static ResponseEntity<Map<String, Object>> invalid(String field, String message) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid", "errors", Map.of(field, message)));
    }

    private void openSession(MemberAccounts.Account a, HttpServletRequest req, HttpServletResponse res) {
        req.getSession(true);
        req.changeSessionId(); // fresh id at the privilege change (session fixation)
        MemberPrincipal principal = new MemberPrincipal(a.personId(), a.name(), MemberAccounts.stamp(a.passwordHash()));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        SecurityContextHolder.setContext(ctx);
        repo.saveContext(ctx, req, res);
    }

    private static void endSession(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
