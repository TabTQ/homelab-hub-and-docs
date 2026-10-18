package com.homelabhub.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Single-password login (decision D12). No usernames: there is one admin. */
@RestController
@RequestMapping("/api")
public class AuthController {

    /** bcrypt only uses the first 72 bytes, and hashing huge inputs is a cheap way to burn CPU. */
    private static final int MAX_PASSWORD_LENGTH = 200;

    private final AuthProperties props;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final SecurityContextRepository repo;
    private final LoginAudit audit;
    /** A real bcrypt hash of a random string, compared against when no password is configured so timing stays equal. */
    private final String dummyHash;

    public AuthController(AuthProperties props, PasswordEncoder encoder, LoginRateLimiter limiter,
                          SecurityContextRepository repo, LoginAudit audit) {
        this.props = props;
        this.encoder = encoder;
        this.limiter = limiter;
        this.repo = repo;
        this.audit = audit;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    record LoginRequest(String password) {}

    @GetMapping("/auth/status")
    Map<String, Object> status(Authentication auth) {
        boolean in = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
        return Map.of("authenticated", in, "mode", props.auth().mode().name().toLowerCase(),
                "passwordConfigured", props.auth().passwordConfigured());
    }

    @PostMapping("/auth/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body,
                                              HttpServletRequest req, HttpServletResponse res) {
        String ip = req.getRemoteAddr();
        LoginRateLimiter.Decision d = limiter.check(ip);
        if (!d.allowed()) {
            long secs = Math.max(1, (d.retryAfter().toMillis() + 999) / 1000);
            audit.record(LoginAudit.Result.denied, "rate limited", ip, req.getHeader("User-Agent"));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(secs))
                    .body(Map.of("error", "too_many_attempts", "retryAfterSeconds", secs));
        }

        String given = body == null || body.password() == null ? "" : body.password();
        boolean configured = props.auth().passwordConfigured();
        boolean tooLong = given.length() > MAX_PASSWORD_LENGTH;
        // Always do one bcrypt comparison so response time does not depend on which branch we are in.
        boolean matches = encoder.matches(tooLong ? "x" : given, configured ? props.auth().passwordHash() : dummyHash);

        if (!configured) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "password_not_configured"));
        }
        if (!matches || tooLong || given.isEmpty()) {
            limiter.recordFailure(ip);
            audit.record(LoginAudit.Result.failed, "wrong password", ip, req.getHeader("User-Agent"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_credentials"));
        }

        limiter.recordSuccess(ip);
        req.getSession(true);
        req.changeSessionId(); // fresh id at the privilege change (session fixation)
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(ctx);
        repo.saveContext(ctx, req, res);
        audit.record(LoginAudit.Result.ok, null, ip, req.getHeader("User-Agent"));
        return ResponseEntity.ok(Map.of("authenticated", true));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /** Smallest protected endpoint: lets the SPA (and tests) check that a session is really accepted. */
    @GetMapping("/me")
    Map<String, String> me() {
        return Map.of("user", "admin");
    }
}
