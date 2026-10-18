package com.homelabhub.internal.actions;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One-time confirmation tokens (plan 9.1). A token is bound to ONE action and ONE browser session, expires after 30 s,
 * is consumed when used, and a wrong typed answer may be retried only a few times. Nothing here can be triggered by a
 * plain GET or by a page on another site, because the token is only ever returned to the session that asked.
 */
@Component
public class ConfirmationTokens {

    public static final Duration TTL = Duration.ofSeconds(30);
    static final int MAX_OUTSTANDING = 200;
    static final int MAX_WRONG_ANSWERS = 3;

    public record Pending(String actionId, String sessionKey, String desired, String word, Instant expires) {}

    private static final class Slot {
        final Pending pending;
        int wrong;

        Slot(Pending pending) {
            this.pending = pending;
        }
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ConfirmationTokens(Clock clock) {
        this.clock = clock;
    }

    /** @return the token, or null when too many are outstanding */
    public String issue(String actionId, String sessionKey, String desired, String word) {
        purge();
        if (slots.size() >= MAX_OUTSTANDING) {
            return null;
        }
        byte[] b = new byte[16];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        slots.put(token, new Slot(new Pending(actionId, sessionKey, desired, word, clock.instant().plus(TTL))));
        return token;
    }

    public enum Outcome { OK, UNKNOWN, EXPIRED, WRONG_SESSION, WRONG_ANSWER, TOO_MANY_WRONG }

    public record Result(Outcome outcome, Pending pending) {}

    /** Consumes the token on success. A wrong typed answer keeps it (up to a limit) so a typo does not force a new prompt. */
    public Result consume(String token, String sessionKey, String typed) {
        Slot s = token == null ? null : slots.get(token);
        if (s == null) {
            return new Result(Outcome.UNKNOWN, null);
        }
        if (!clock.instant().isBefore(s.pending.expires())) {
            slots.remove(token);
            return new Result(Outcome.EXPIRED, null);
        }
        if (!s.pending.sessionKey().equals(sessionKey)) {
            return new Result(Outcome.WRONG_SESSION, null); // not consumed: it belongs to someone else
        }
        if (s.pending.word() != null && (typed == null || !typed.strip().equals(s.pending.word()))) {
            synchronized (s) {
                if (++s.wrong >= MAX_WRONG_ANSWERS) {
                    slots.remove(token);
                    return new Result(Outcome.TOO_MANY_WRONG, null);
                }
            }
            return new Result(Outcome.WRONG_ANSWER, null);
        }
        if (slots.remove(token, s)) {
            return new Result(Outcome.OK, s.pending);
        }
        return new Result(Outcome.UNKNOWN, null); // lost a race with another use of the same token
    }

    int outstanding() {
        purge();
        return slots.size();
    }

    private void purge() {
        Instant now = clock.instant();
        slots.values().removeIf(s -> !now.isBefore(s.pending.expires()));
    }
}
