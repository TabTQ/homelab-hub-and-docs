package com.homelabhub.internal.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.actions.ConfirmationTokens.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfirmationTokensTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-24T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    TestClock clock = new TestClock();
    ConfirmationTokens tokens = new ConfirmationTokens(clock);

    @Test
    void aTokenWorksOnceForTheSameSessionAndAction() {
        String t = tokens.issue("a1", "sess", "stopped", null);
        var r = tokens.consume(t, "sess", null);
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.pending().actionId()).isEqualTo("a1");
        assertThat(r.pending().desired()).isEqualTo("stopped");
        assertThat(tokens.consume(t, "sess", null).outcome()).as("single use").isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void itExpiresAfterThirtySeconds() {
        String t = tokens.issue("a1", "sess", null, null);
        clock.now = clock.now.plusSeconds(29);
        assertThat(tokens.consume(t, "sess", null).outcome()).isEqualTo(Outcome.OK);
        String t2 = tokens.issue("a1", "sess", null, null);
        clock.now = clock.now.plusSeconds(30);
        assertThat(tokens.consume(t2, "sess", null).outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(tokens.consume(t2, "sess", null).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void anotherSessionCannotUseItAndDoesNotBurnIt() {
        String t = tokens.issue("a1", "alice", null, null);
        assertThat(tokens.consume(t, "mallory", null).outcome()).isEqualTo(Outcome.WRONG_SESSION);
        assertThat(tokens.consume(t, "alice", null).outcome()).as("still valid for its owner").isEqualTo(Outcome.OK);
    }

    @Test
    void aTypedAnswerMustMatchExactlyBeforeTheTokenIsSpent() {
        String t = tokens.issue("a1", "s", "stopped", "pve");
        assertThat(tokens.consume(t, "s", null).outcome()).isEqualTo(Outcome.WRONG_ANSWER);
        assertThat(tokens.consume(t, "s", "PVE").outcome()).as("case sensitive").isEqualTo(Outcome.WRONG_ANSWER);
        assertThat(tokens.consume(t, "s", " pve ").outcome()).as("surrounding spaces are forgiven").isEqualTo(Outcome.OK);
    }

    @Test
    void threeWrongAnswersKillTheToken() {
        String t = tokens.issue("a1", "s", null, "pve");
        assertThat(tokens.consume(t, "s", "x").outcome()).isEqualTo(Outcome.WRONG_ANSWER);
        assertThat(tokens.consume(t, "s", "y").outcome()).isEqualTo(Outcome.WRONG_ANSWER);
        assertThat(tokens.consume(t, "s", "z").outcome()).isEqualTo(Outcome.TOO_MANY_WRONG);
        assertThat(tokens.consume(t, "s", "pve").outcome()).as("even the right answer is too late").isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void unknownNullAndGarbageTokensAreRejected() {
        assertThat(tokens.consume(null, "s", null).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(tokens.consume("", "s", null).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(tokens.consume("nope", "s", null).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void tokensAreLongRandomAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 150; i++) {
            String t = tokens.issue("a", "s", null, null);
            assertThat(t).matches("[A-Za-z0-9_-]{22}");
            assertThat(seen.add(t)).isTrue();
        }
    }

    @Test
    void theNumberOfOutstandingTokensIsCappedAndExpiredOnesFreeSpace() {
        for (int i = 0; i < ConfirmationTokens.MAX_OUTSTANDING; i++) {
            assertThat(tokens.issue("a", "s", null, null)).isNotNull();
        }
        assertThat(tokens.issue("a", "s", null, null)).isNull();
        clock.now = clock.now.plus(Duration.ofSeconds(31));
        assertThat(tokens.issue("a", "s", null, null)).isNotNull();
        assertThat(tokens.outstanding()).isEqualTo(1);
    }

    @Test
    void concurrentUseOfOneTokenSucceedsExactlyOnce() throws Exception {
        String t = tokens.issue("a", "s", null, null);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Outcome>>();
            for (int i = 0; i < 8; i++) {
                jobs.add(() -> tokens.consume(t, "s", null).outcome());
            }
            long ok = 0;
            for (var f : pool.invokeAll(jobs)) {
                if (f.get() == Outcome.OK) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
