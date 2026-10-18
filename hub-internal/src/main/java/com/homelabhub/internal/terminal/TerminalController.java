package com.homelabhub.internal.terminal;

import com.homelabhub.auth.AuthProperties;
import com.homelabhub.auth.LoginRateLimiter;
import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST side of the Terminal page (plan 5.3). Everything is under /api/admin so the existing security rules require the
 * login in modes all and edit; the feature itself refuses to run in mode none.
 */
@RestController
@RequestMapping("/api/admin/terminal")
class TerminalController {

    /** bcrypt only looks at 72 bytes; hashing huge inputs is a cheap way to burn CPU. Same limit as the login. */
    private static final int MAX_PASSWORD_LENGTH = 200;

    private final TerminalFeature feature;
    private final TerminalTickets tickets;
    private final TerminalSessionManager sessions;
    private final TerminalAudit audit;
    private final LoginRateLimiter limiter;
    private final PasswordEncoder encoder;
    private final AuthProperties auth;

    TerminalController(TerminalFeature feature, TerminalTickets tickets, TerminalSessionManager sessions, TerminalAudit audit,
                       LoginRateLimiter limiter, PasswordEncoder encoder, AuthProperties auth) {
        this.feature = feature;
        this.tickets = tickets;
        this.sessions = sessions;
        this.audit = audit;
        this.limiter = limiter;
        this.encoder = encoder;
        this.auth = auth;
    }

    @GetMapping("/config")
    Map<String, Object> config() {
        TerminalProperties p = feature.props();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", feature.enabled());
        m.put("idleSeconds", p.idleSeconds());
        m.put("warnSeconds", p.warnSeconds());
        m.put("maxSeconds", p.maxSeconds());
        m.put("maxSessions", p.maxSessions());
        m.put("activeSessions", sessions.active());
        // Only what the page shows. The key file name and the pinned host key stay on the server.
        m.put("terminals", feature.enabled() ? feature.terminals().stream().map(TerminalController::view).toList() : List.of());
        return m;
    }

    private static Map<String, Object> view(TerminalSpec t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("label", t.label());
        m.put("user", t.user());
        m.put("host", t.host());
        m.put("port", t.port());
        return m;
    }

    record SessionRequest(String hostId, String password) {}

    /** Step-up: the dashboard password again, every time (decision D3). Returns a one-time ticket for the WebSocket. */
    @PostMapping("/sessions")
    ResponseEntity<Map<String, Object>> open(@RequestBody(required = false) SessionRequest body, HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        String ua = req.getHeader("User-Agent");
        String hostId = body == null ? null : body.hostId();
        if (!feature.enabled()) {
            audit.record(TerminalAudit.Event.denied, hostId, ip, ua, "reason=disabled");
            return error(HttpStatus.SERVICE_UNAVAILABLE, "disabled", "The terminal is turned off on this hub (HUB_TERMINAL_ENABLED).");
        }
        Optional<TerminalSpec> host = hostId == null ? Optional.empty() : feature.terminal(hostId);
        if (host.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "unknown_host", "There is no terminal with that id in dashboard.yaml.");
        }
        // Wrong step-up passwords count against the same lockout as wrong logins.
        LoginRateLimiter.Decision d = limiter.check(ip);
        if (!d.allowed()) {
            long secs = Math.max(1, (d.retryAfter().toMillis() + 999) / 1000);
            audit.record(TerminalAudit.Event.denied, hostId, ip, ua, "reason=rate_limited");
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("error", "too_many_attempts");
            b.put("message", "Too many wrong passwords. Try again in " + secs + " s.");
            b.put("retryAfterSeconds", secs);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(secs)).body(b);
        }
        String given = body.password() == null ? "" : body.password();
        boolean tooLong = given.length() > MAX_PASSWORD_LENGTH;
        boolean matches = encoder.matches(tooLong ? "x" : given, auth.auth().passwordHash());
        if (!matches || tooLong || given.isEmpty()) {
            limiter.recordFailure(ip);
            audit.record(TerminalAudit.Event.denied, hostId, ip, ua, "reason=wrong_password");
            return error(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Wrong password.");
        }
        limiter.recordSuccess(ip);
        if (sessions.active() >= feature.props().maxSessions()) {
            audit.record(TerminalAudit.Event.denied, hostId, ip, ua, "reason=limit");
            return error(HttpStatus.CONFLICT, "limit", limitMessage(feature.props().maxSessions()));
        }
        HttpSession login = req.getSession(false);
        String ticket = login == null ? null : tickets.issue(login.getId(), hostId);
        if (ticket == null) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "too_many_tickets", "Too many terminals are being opened at once. Try again shortly.");
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ticket", ticket);
        b.put("expiresInSeconds", TerminalTickets.TTL.toSeconds());
        return ResponseEntity.ok(b);
    }

    @GetMapping("/audit")
    List<TerminalAudit.Row> recent(@RequestParam(defaultValue = "20") int limit) {
        return audit.recent(limit);
    }

    static String limitMessage(int max) {
        return "Already " + max + " terminal" + (max == 1 ? "" : "s") + " open (the limit). Close one first.";
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", code);
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }
}
