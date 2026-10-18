package com.homelabhub.internal.terminal;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the open terminal sessions (plan 5.3): the concurrency limit, the idle warning and idle/max-time closes, closing a
 * login's sessions when that login ends, and closing everything on shutdown. Nothing runs while no terminal is open: the
 * one-second watchdog thread is created with the first session (or pending handshake) and shut down after the last.
 */
@Component
public class TerminalSessionManager {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionManager.class);

    /** Implemented by the WebSocket side. Called outside any lock. */
    public interface Endpoint {
        void warn(int secondsLeft);

        void closed(String reason, String message, Integer exitCode);
    }

    public final class Session {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final String loginSession;
        final String hostId;
        final String clientIp;
        final String userAgent;
        final Endpoint endpoint;
        final Instant started;
        volatile Instant lastActivity;
        volatile boolean warned;
        volatile TerminalConnection connection;
        final AtomicLong bytesIn = new AtomicLong();
        final AtomicLong bytesOut = new AtomicLong();
        final AtomicBoolean ended = new AtomicBoolean();

        Session(String loginSession, String hostId, String clientIp, String userAgent, Endpoint endpoint) {
            this.loginSession = loginSession;
            this.hostId = hostId;
            this.clientIp = clientIp;
            this.userAgent = userAgent;
            this.endpoint = endpoint;
            this.started = clock.instant();
            this.lastActivity = started;
        }

        public String hostId() {
            return hostId;
        }

        public boolean ended() {
            return ended.get();
        }

        public TerminalConnection connection() {
            return connection;
        }

        public void countIn(int n) {
            bytesIn.addAndGet(n);
        }

        public void countOut(int n) {
            bytesOut.addAndGet(n);
        }
    }

    /** A WebSocket that has not yet presented its ticket. */
    private record Pending(Instant deadline, Runnable onTimeout) {}

    private final TerminalProperties props;
    private final TerminalAudit audit;
    private final Clock clock;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Map<Object, Pending> pending = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private ScheduledExecutorService watchdog;

    public TerminalSessionManager(TerminalProperties props, TerminalAudit audit, Clock clock) {
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- pending handshakes ---------------------------------------------------------------------------------------

    /** Runs {@code onTimeout} unless {@link #authenticated(Object)} is called for {@code key} within {@code within}. */
    public void awaitAuth(Object key, Duration within, Runnable onTimeout) {
        synchronized (lock) {
            pending.put(key, new Pending(clock.instant().plus(within), onTimeout));
            ensureWatchdog();
        }
    }

    public void authenticated(Object key) {
        synchronized (lock) {
            pending.remove(key);
            stopWatchdogIfIdle();
        }
    }

    // ---- sessions -------------------------------------------------------------------------------------------------

    public int active() {
        return sessions.size();
    }

    /** Takes a slot before the (slow) SSH connect so two tabs cannot race past the limit. Null when full. */
    public Session reserve(String loginSession, String hostId, String clientIp, String userAgent, Endpoint endpoint) {
        synchronized (lock) {
            if (sessions.size() >= props.maxSessions()) {
                return null;
            }
            Session s = new Session(loginSession, hostId, clientIp, userAgent, endpoint);
            sessions.add(s);
            ensureWatchdog();
            return s;
        }
    }

    /**
     * The shell is open. Returns false (and closes the connection) when the session was ended meanwhile, for example by a
     * logout during the connect.
     */
    public boolean attach(Session s, TerminalConnection c) {
        s.connection = c;
        s.lastActivity = clock.instant();
        if (s.ended.get()) {
            c.close();
            return false;
        }
        audit.record(TerminalAudit.Event.open, s.hostId, s.clientIp, s.userAgent, "session=" + s.id);
        return true;
    }

    /** Input or resize from the user. Pings and output do not count (decision D10). */
    public void activity(Session s) {
        s.lastActivity = clock.instant();
        s.warned = false;
    }

    /** Ends the session once; later calls do nothing. */
    public void end(Session s, String reason, String message, Integer exitCode) {
        if (!s.ended.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            sessions.remove(s);
            stopWatchdogIfIdle();
        }
        TerminalConnection c = s.connection;
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException e) {
                log.debug("closing terminal connection: {}", e.toString());
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("session", s.id);
            d.put("reason", reason);
            d.put("duration_s", Duration.between(s.started, clock.instant()).toSeconds());
            d.put("bytes_in", s.bytesIn.get());
            d.put("bytes_out", s.bytesOut.get());
            d.put("exit", exitCode);
            audit.record(TerminalAudit.Event.close, s.hostId, s.clientIp, s.userAgent, TerminalAudit.detail(d));
        }
        try {
            s.endpoint.closed(reason, message, exitCode);
        } catch (RuntimeException e) {
            log.debug("notifying terminal endpoint: {}", e.toString());
        }
    }

    /** The dashboard login ended (logout or expiry): its terminals end now (plan R9). */
    public void closeForLoginSession(String loginSession) {
        for (Session s : List.copyOf(sessions)) {
            if (s.loginSession.equals(loginSession)) {
                end(s, "logout", "You signed out of the dashboard, so the terminal was closed.", null);
            }
        }
    }

    @PreDestroy
    public void closeAll() {
        for (Session s : List.copyOf(sessions)) {
            end(s, "error", "The hub is shutting down.", null);
        }
        synchronized (lock) {
            pending.clear();
            if (watchdog != null) {
                watchdog.shutdownNow();
                watchdog = null;
            }
        }
    }

    // ---- watchdog -------------------------------------------------------------------------------------------------

    /** One pass of the idle/max/handshake checks. Public for tests; normally called by the watchdog every second. */
    public void tick() {
        Instant now = clock.instant();
        List<Runnable> timedOut = new ArrayList<>();
        synchronized (lock) {
            pending.entrySet().removeIf(e -> {
                if (!now.isBefore(e.getValue().deadline())) {
                    timedOut.add(e.getValue().onTimeout());
                    return true;
                }
                return false;
            });
        }
        timedOut.forEach(TerminalSessionManager::runQuietly);
        for (Session s : List.copyOf(sessions)) {
            if (s.connection == null) {
                continue; // still connecting; the connect timeout bounds this
            }
            if (!now.isBefore(s.started.plusSeconds(props.maxSeconds()))) {
                end(s, "max", "The terminal reached its maximum length of " + human(props.maxSeconds()) + ".", null);
                continue;
            }
            long idle = Duration.between(s.lastActivity, now).toSeconds();
            if (idle >= props.idleSeconds()) {
                end(s, "idle", "The terminal was idle for " + human(props.idleSeconds()) + " and was closed.", null);
            } else if (idle >= props.idleSeconds() - props.warnSeconds() && !s.warned) {
                s.warned = true;
                int left = (int) (props.idleSeconds() - idle);
                runQuietly(() -> s.endpoint.warn(left));
            }
        }
        synchronized (lock) {
            stopWatchdogIfIdle();
        }
    }

    public boolean watchdogRunning() {
        synchronized (lock) {
            return watchdog != null;
        }
    }

    private void ensureWatchdog() {
        if (watchdog == null) {
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "terminal-watchdog");
                t.setDaemon(true);
                return t;
            });
            watchdog.scheduleWithFixedDelay(() -> runQuietly(this::tick), 1, 1, TimeUnit.SECONDS);
        }
    }

    private void stopWatchdogIfIdle() {
        if (watchdog != null && sessions.isEmpty() && pending.isEmpty()) {
            watchdog.shutdown();
            watchdog = null;
        }
    }

    private static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("terminal watchdog: {}", e.toString());
        }
    }

    static String human(int seconds) {
        if (seconds % 3600 == 0) {
            return seconds / 3600 + (seconds == 3600 ? " hour" : " hours");
        }
        if (seconds % 60 == 0) {
            return seconds / 60 + (seconds == 60 ? " minute" : " minutes");
        }
        return seconds + " seconds";
    }
}
