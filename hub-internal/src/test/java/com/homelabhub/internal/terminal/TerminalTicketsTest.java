package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TerminalTicketsTest {

    final MutableClock clock = new MutableClock();
    final TerminalTickets tickets = new TerminalTickets(clock);

    @Test
    void redeemsOnceForTheRightSessionAndHost() {
        String t = tickets.issue("login-1", "pi");
        TerminalTickets.Result r = tickets.redeem(t, "login-1");
        assertThat(r.outcome()).isEqualTo(TerminalTickets.Outcome.OK);
        assertThat(r.hostId()).isEqualTo("pi");
        assertThat(tickets.redeem(t, "login-1").outcome()).isEqualTo(TerminalTickets.Outcome.UNKNOWN);
    }

    @Test
    void anotherLoginCannotUseItAndBurnsIt() {
        String t = tickets.issue("login-1", "pi");
        assertThat(tickets.redeem(t, "login-2").outcome()).isEqualTo(TerminalTickets.Outcome.WRONG_SESSION);
        assertThat(tickets.redeem(t, "login-1").outcome()).isEqualTo(TerminalTickets.Outcome.UNKNOWN);
        assertThat(tickets.redeem(tickets.issue("login-1", "pi"), null).outcome()).isEqualTo(TerminalTickets.Outcome.WRONG_SESSION);
    }

    @Test
    void expiresAfterThirtySeconds() {
        String ok = tickets.issue("s", "pi");
        String late = tickets.issue("s", "pi");
        clock.advance(Duration.ofSeconds(29));
        assertThat(tickets.redeem(ok, "s").outcome()).isEqualTo(TerminalTickets.Outcome.OK);
        clock.advance(Duration.ofSeconds(1));
        assertThat(tickets.redeem(late, "s").outcome()).isEqualTo(TerminalTickets.Outcome.EXPIRED);
    }

    @Test
    void unknownAndNullTicketsAreRefused() {
        assertThat(tickets.redeem("nope", "s").outcome()).isEqualTo(TerminalTickets.Outcome.UNKNOWN);
        assertThat(tickets.redeem(null, "s").outcome()).isEqualTo(TerminalTickets.Outcome.UNKNOWN);
    }

    @Test
    void ticketsAreLongRandomAndSweptLazily() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String t = tickets.issue("s", "pi");
            assertThat(t).matches("[A-Za-z0-9_-]{43}");
            assertThat(seen.add(t)).isTrue();
        }
        assertThat(tickets.issue("s", "pi")).isNotNull();
        for (int i = 0; i < 20; i++) {
            tickets.issue("s", "pi");
        }
        assertThat(tickets.issue("s", "pi")).as("capped").isNull();
        clock.advance(Duration.ofMinutes(2));
        assertThat(tickets.issue("s", "pi")).as("expired ones are swept on the next issue").isNotNull();
        assertThat(tickets.outstanding()).isEqualTo(1);
    }
}
