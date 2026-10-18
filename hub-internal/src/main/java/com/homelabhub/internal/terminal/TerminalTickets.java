package com.homelabhub.internal.terminal;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One-time tickets that let a WebSocket open a shell (plan 5.3), modelled on ConfirmationTokens: issued only after the
 * step-up password, bound to ONE login session and ONE host, valid 30 s, consumed on first use, swept lazily (no
 * scheduler). Tickets are never logged.
 */
@Component
public class TerminalTickets {

    public static final Duration TTL = Duration.ofSeconds(30);
    static final int MAX_OUTSTANDING = 50;

    private record Ticket(String loginSession, String hostId, Instant expires) {}

    public enum Outcome { OK, UNKNOWN, EXPIRED, WRONG_SESSION }

    public record Result(Outcome outcome, String hostId) {}

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public TerminalTickets(Clock clock) {
        this.clock = clock;
    }

    /** @return the ticket, or null when too many are outstanding */
    public String issue(String loginSession, String hostId) {
        purge();
        if (tickets.size() >= MAX_OUTSTANDING) {
            return null;
        }
        byte[] b = new byte[32];
        random.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tickets.put(t, new Ticket(loginSession, hostId, clock.instant().plus(TTL)));
        return t;
    }

    /** Consumes the ticket whatever the outcome (except UNKNOWN): a ticket shown to the wrong session is burnt. */
    public Result redeem(String ticket, String loginSession) {
        purge();
        Ticket t = ticket == null ? null : tickets.remove(ticket);
        if (t == null) {
            return new Result(Outcome.UNKNOWN, null);
        }
        if (!clock.instant().isBefore(t.expires())) {
            return new Result(Outcome.EXPIRED, null);
        }
        if (loginSession == null || !t.loginSession().equals(loginSession)) {
            return new Result(Outcome.WRONG_SESSION, null);
        }
        return new Result(Outcome.OK, t.hostId());
    }

    int outstanding() {
        return tickets.size();
    }

    private void purge() {
        Instant now = clock.instant();
        // Expired tickets are kept for one extra TTL so a late redeem reports EXPIRED rather than UNKNOWN.
        Instant cutoff = now.minus(TTL);
        tickets.values().removeIf(t -> t.expires().isBefore(cutoff));
    }
}
