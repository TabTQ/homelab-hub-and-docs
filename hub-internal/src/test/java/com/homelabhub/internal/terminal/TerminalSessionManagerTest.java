package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TerminalSessionManagerTest {

    final MutableClock clock = new MutableClock();
    final TerminalAudit audit = mock(TerminalAudit.class);
    final TerminalProperties props = new TerminalProperties(true, 900, 60, 7200, 2, 10, 65536, 524288);
    final TerminalSessionManager mgr = new TerminalSessionManager(props, audit, clock);

    @AfterEach
    void stop() {
        mgr.closeAll();
    }

    static final class FakeEndpoint implements TerminalSessionManager.Endpoint {
        final List<Integer> warnings = new ArrayList<>();
        String reason;
        String message;

        @Override
        public void warn(int secondsLeft) {
            warnings.add(secondsLeft);
        }

        @Override
        public void closed(String reason, String message, Integer exitCode) {
            this.reason = reason;
            this.message = message;
        }
    }

    static final class FakeConnection implements TerminalConnection {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public InputStream output() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void write(byte[] data) {}

        @Override
        public void resize(int cols, int rows) {}

        @Override
        public Integer exitStatus() {
            return null;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private TerminalSessionManager.Session open(String login, FakeEndpoint ep, FakeConnection c) {
        TerminalSessionManager.Session s = mgr.reserve(login, "pi", "127.0.0.1", "test", ep);
        assertThat(s).isNotNull();
        assertThat(mgr.attach(s, c)).isTrue();
        return s;
    }

    @Test
    void warnsBeforeTheIdleCloseAndClosesWhenStillIdle() {
        FakeEndpoint ep = new FakeEndpoint();
        FakeConnection c = new FakeConnection();
        open("l", ep, c);
        clock.advance(Duration.ofSeconds(839));
        mgr.tick();
        assertThat(ep.warnings).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        mgr.tick();
        mgr.tick();
        assertThat(ep.warnings).containsExactly(60);
        clock.advance(Duration.ofSeconds(60));
        mgr.tick();
        assertThat(ep.reason).isEqualTo("idle");
        assertThat(c.closed).isTrue();
        assertThat(mgr.active()).isZero();
        verify(audit).record(eq(TerminalAudit.Event.close), eq("pi"), anyString(), anyString(), contains("reason=idle"));
    }

    @Test
    void inputAndResizeResetTheIdleTimerButOnlyActivityCountsNotTime() {
        FakeEndpoint ep = new FakeEndpoint();
        TerminalSessionManager.Session s = open("l", ep, new FakeConnection());
        clock.advance(Duration.ofSeconds(850));
        mgr.tick();
        assertThat(ep.warnings).hasSize(1);
        mgr.activity(s); // e.g. a key press after the warning
        clock.advance(Duration.ofSeconds(100));
        mgr.tick();
        assertThat(ep.reason).isNull();
        clock.advance(Duration.ofSeconds(740));
        mgr.tick();
        assertThat(ep.warnings).as("warned again after a new idle period").hasSize(2);
        clock.advance(Duration.ofSeconds(60));
        mgr.tick();
        assertThat(ep.reason).isEqualTo("idle");
    }

    @Test
    void closesAtTheMaximumLengthEvenWhenActive() {
        FakeEndpoint ep = new FakeEndpoint();
        TerminalSessionManager.Session s = open("l", ep, new FakeConnection());
        for (int i = 0; i < 24; i++) {
            clock.advance(Duration.ofMinutes(5));
            mgr.activity(s);
            mgr.tick();
        }
        assertThat(ep.reason).isEqualTo("max");
        assertThat(ep.message).contains("2 hours");
    }

    @Test
    void refusesMoreThanTheLimitAndFreesTheSlotWhenOneEnds() {
        TerminalSessionManager.Session a = open("l", new FakeEndpoint(), new FakeConnection());
        open("l", new FakeEndpoint(), new FakeConnection());
        assertThat(mgr.reserve("l", "pi", "ip", "ua", new FakeEndpoint())).isNull();
        mgr.end(a, "closed", "bye", 0);
        assertThat(mgr.reserve("l", "pi", "ip", "ua", new FakeEndpoint())).isNotNull();
    }

    @Test
    void endingIsIdempotentAndAuditedOnce() {
        FakeEndpoint ep = new FakeEndpoint();
        TerminalSessionManager.Session s = open("l", ep, new FakeConnection());
        s.countIn(5);
        s.countOut(1234);
        mgr.end(s, "closed", "one", 0);
        mgr.end(s, "error", "two", null);
        assertThat(ep.message).isEqualTo("one");
        verify(audit).record(eq(TerminalAudit.Event.close), eq("pi"), anyString(), anyString(),
                contains("bytes_in=5 bytes_out=1234 exit=0"));
    }

    @Test
    void aLogoutClosesOnlyThatLoginsSessions() {
        FakeEndpoint mine = new FakeEndpoint();
        FakeEndpoint other = new FakeEndpoint();
        FakeConnection c = new FakeConnection();
        open("login-a", mine, c);
        open("login-b", other, new FakeConnection());
        mgr.closeForLoginSession("login-a");
        assertThat(mine.reason).isEqualTo("logout");
        assertThat(c.closed).isTrue();
        assertThat(other.reason).isNull();
        assertThat(mgr.active()).isEqualTo(1);
    }

    @Test
    void aSessionEndedWhileConnectingClosesTheLateConnection() {
        FakeEndpoint ep = new FakeEndpoint();
        TerminalSessionManager.Session s = mgr.reserve("l", "pi", "ip", "ua", ep);
        mgr.closeForLoginSession("l");
        FakeConnection late = new FakeConnection();
        assertThat(mgr.attach(s, late)).isFalse();
        assertThat(late.closed).isTrue();
        verify(audit, org.mockito.Mockito.never()).record(eq(TerminalAudit.Event.open), any(), any(), any(), any());
    }

    @Test
    void theWatchdogOnlyExistsWhileSomethingIsOpen() {
        assertThat(mgr.watchdogRunning()).isFalse();
        TerminalSessionManager.Session s = open("l", new FakeEndpoint(), new FakeConnection());
        assertThat(mgr.watchdogRunning()).isTrue();
        mgr.end(s, "closed", "bye", 0);
        assertThat(mgr.watchdogRunning()).isFalse();

        Object key = new Object();
        AtomicBoolean timedOut = new AtomicBoolean();
        mgr.awaitAuth(key, Duration.ofSeconds(5), () -> timedOut.set(true));
        assertThat(mgr.watchdogRunning()).isTrue();
        clock.advance(Duration.ofSeconds(5));
        mgr.tick();
        assertThat(timedOut).isTrue();
        assertThat(mgr.watchdogRunning()).isFalse();

        mgr.awaitAuth(key, Duration.ofSeconds(5), () -> timedOut.set(false));
        mgr.authenticated(key);
        assertThat(mgr.watchdogRunning()).isFalse();
        clock.advance(Duration.ofSeconds(10));
        mgr.tick();
        assertThat(timedOut).as("no timeout after authenticating").isTrue();
    }

    @Test
    void shutdownClosesEverything() {
        FakeEndpoint ep = new FakeEndpoint();
        FakeConnection c = new FakeConnection();
        open("l", ep, c);
        mgr.closeAll();
        assertThat(c.closed).isTrue();
        assertThat(ep.reason).isEqualTo("error");
        assertThat(mgr.watchdogRunning()).isFalse();
    }

    @Test
    void humanDurations() {
        assertThat(TerminalSessionManager.human(900)).isEqualTo("15 minutes");
        assertThat(TerminalSessionManager.human(7200)).isEqualTo("2 hours");
        assertThat(TerminalSessionManager.human(3600)).isEqualTo("1 hour");
        assertThat(TerminalSessionManager.human(12)).isEqualTo("12 seconds");
    }
}
