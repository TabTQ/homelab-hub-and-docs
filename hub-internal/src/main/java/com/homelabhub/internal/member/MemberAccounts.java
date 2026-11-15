package com.homelabhub.internal.member;

import com.homelabhub.internal.audit.AuditLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Logins for people on the Budget page. Only bcrypt hashes are stored. A generated password is handed back once to the
 * caller and goes nowhere else: not into the audit log, not into any log line.
 */
@Service
public class MemberAccounts {

    /** A login row joined with its person. */
    public record Account(long personId, String name, boolean archived, String passwordHash, boolean mustChange, boolean canAdd,
                          boolean disabled, String resetRequestedAt, String lastLoginAt) {

        /** Can this account sign in and keep a session? */
        public boolean active() {
            return !disabled && !archived;
        }
    }

    /** 409: the person already has a login. */
    public static final class ExistsException extends RuntimeException {
        public ExistsException() {
            super("That person already has a login.");
        }
    }

    /** 422: the request cannot be done in the current state (for example a login for an archived person). */
    public static final class RefusedException extends RuntimeException {
        private final String field;

        public RefusedException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private static final String SELECT = """
            SELECT a.person_id, p.name, p.archived, a.password_hash, a.must_change, a.can_add, a.disabled, a.reset_requested_at, a.last_login_at
            FROM budget_accounts a JOIN budget_people p ON p.id = a.person_id""";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final Clock clock;

    public MemberAccounts(JdbcClient jdbc, PasswordEncoder encoder, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    private static Account row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Account(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1, rs.getString(4), rs.getInt(5) == 1, rs.getInt(6) == 1,
                rs.getInt(7) == 1, rs.getString(8), rs.getString(9));
    }

    public Optional<Account> find(long personId) {
        return jdbc.sql(SELECT + " WHERE a.person_id = ?").param(personId).query((rs, n) -> row(rs)).optional();
    }

    public Optional<Account> findByName(String name) {
        return jdbc.sql(SELECT + " WHERE p.name = ? COLLATE NOCASE").param(name).query((rs, n) -> row(rs)).optional();
    }

    /** A short digest of the hash; it changes whenever the password does. */
    public static String stamp(String passwordHash) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The account behind a member session, or empty when the session must end (reset, disabled, archived, removed). */
    Optional<Account> valid(MemberPrincipal p) {
        return find(p.personId()).filter(a -> a.active() && stamp(a.passwordHash()).equals(p.stamp()));
    }

    /** Who created an expense (NULL = the admin or a generated entry); empty when there is no such expense. */
    Optional<Long> creatorOf(long expenseId) {
        return jdbc.sql("SELECT created_by FROM budget_expenses WHERE id = ?").param(expenseId)
                .query((rs, n) -> rs.getObject(1) == null ? -1L : rs.getLong(1)).optional();
    }

    private String now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // ---------------------------------------------------------------- admin side

    /** Hash for a fresh generated password. Done before any write transaction: bcrypt is slow on a Pi. */
    public record Generated(String password, String hash) {}

    public Generated generate() {
        String password = PasswordGenerator.generate();
        return new Generated(password, encoder.encode(password));
    }

    /** Inserts the login row (no audit, no checks beyond the key). Call inside the transaction that creates the person. */
    public void insert(long personId, String hash) {
        jdbc.sql("INSERT INTO budget_accounts (person_id, password_hash, must_change, can_add, disabled) VALUES (?, ?, 1, 0, 0)")
                .params(personId, hash).update();
    }

    /** Gives an existing person a login. Returns the one-time password. */
    public String create(long personId, String clientIp, String userAgent) {
        boolean archived = person(personId);
        if (find(personId).isPresent()) {
            throw new ExistsException();
        }
        if (archived) {
            throw new RefusedException("account", "That person is archived. Restore them first.");
        }
        Generated g = generate();
        insert(personId, g.hash());
        audit("member-account", personId, AuditLog.Result.ok, "created", clientIp, userAgent);
        return g.password();
    }

    /** New password, shown once. Ends the member's sessions (their stamp no longer matches) and asks for a change at the next login. */
    public String reset(long personId, String clientIp, String userAgent) {
        person(personId);
        Account a = find(personId).orElseThrow(() -> new NoSuchElementException("That person has no login."));
        if (a.archived()) {
            throw new RefusedException("account", "That person is archived. Restore them first.");
        }
        Generated g = generate();
        jdbc.sql("UPDATE budget_accounts SET password_hash = ?, must_change = 1, reset_requested_at = NULL WHERE person_id = ?")
                .params(g.hash(), personId).update();
        audit("member-reset", personId, AuditLog.Result.ok, "password reset by the admin", clientIp, userAgent);
        return g.password();
    }

    public void update(long personId, Boolean canAdd, Boolean disabled, String clientIp, String userAgent) {
        Account a = find(personId).orElseThrow(() -> new NoSuchElementException("That person has no login."));
        if (Boolean.FALSE.equals(disabled) && a.archived()) {
            throw new RefusedException("disabled", "That person is archived. Restore them first.");
        }
        jdbc.sql("UPDATE budget_accounts SET can_add = ?, disabled = ? WHERE person_id = ?")
                .params((canAdd == null ? a.canAdd() : canAdd) ? 1 : 0, (disabled == null ? a.disabled() : disabled) ? 1 : 0, personId).update();
        audit("member-account", personId, AuditLog.Result.ok, "changed canAdd=" + (canAdd == null ? "-" : canAdd)
                + " disabled=" + (disabled == null ? "-" : disabled), clientIp, userAgent);
    }

    /** Archiving a person turns their login off. */
    public void disableOnArchive(long personId, String clientIp, String userAgent) {
        if (jdbc.sql("UPDATE budget_accounts SET disabled = 1 WHERE person_id = ? AND disabled = 0").param(personId).update() > 0) {
            audit("member-account", personId, AuditLog.Result.ok, "disabled because the person was archived", clientIp, userAgent);
        }
    }

    private boolean person(long id) {
        return jdbc.sql("SELECT archived FROM budget_people WHERE id = ?").param(id).query((rs, n) -> rs.getInt(1) == 1).optional()
                .orElseThrow(() -> new NoSuchElementException("No such person"));
    }

    // ---------------------------------------------------------------- member side

    void touchLogin(long personId) {
        jdbc.sql("UPDATE budget_accounts SET last_login_at = ? WHERE person_id = ?").params(now(), personId).update();
    }

    void requestReset(long personId) {
        jdbc.sql("UPDATE budget_accounts SET reset_requested_at = ? WHERE person_id = ? AND disabled = 0").params(now(), personId).update();
    }

    /** Stores a password the member chose. Returns the new hash (the caller needs its stamp). */
    String setPassword(long personId, String clear) {
        String hash = encoder.encode(clear);
        jdbc.sql("UPDATE budget_accounts SET password_hash = ?, must_change = 0 WHERE person_id = ?").params(hash, personId).update();
        return hash;
    }

    boolean matches(String clear, String hash) {
        return encoder.matches(clear, hash);
    }

    String dummyHash() {
        return encoder.encode(java.util.UUID.randomUUID().toString());
    }

    /** The audit detail never carries a password: only fixed words and ids. */
    public void audit(String action, long personId, AuditLog.Result result, String detail, String ip, String userAgent) {
        audit.record(AuditLog.Source.ui, action, "person:" + personId, result, detail, ip, userAgent);
    }

    /** For attempts where no person is known (unknown name): the target is a fixed word, the name is never stored. */
    void auditUnknown(String action, AuditLog.Result result, String detail, String ip, String userAgent) {
        audit.record(AuditLog.Source.ui, action, "member", result, detail, ip, userAgent);
    }
}
