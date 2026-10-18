package com.homelabhub.internal.docker;

import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;
import com.homelabhub.internal.terminal.SshConnector;
import com.homelabhub.internal.terminal.TerminalException;
import com.jcraft.jsch.Session;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * One SSH session per Docker host, shared by all calls (each command gets its own exec channel) and closed after a short
 * idle time. The sweeper thread exists only while at least one session is open: nothing runs while the page is unused.
 */
@Component
class DockerSessionPool {

    private record Entry(TerminalSpec spec, Session session, long lastUsed) {}

    private final SshConnector connector;
    private final long idleMillis;
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private ScheduledExecutorService sweeper;

    DockerSessionPool(SshConnector connector, DockerProperties props) {
        this.connector = connector;
        this.idleMillis = props.idleSessionSeconds() * 1000L;
    }

    Session get(TerminalSpec spec) throws TerminalException {
        synchronized (lock) {
            Entry e = sessions.get(spec.id());
            if (e != null && e.spec().equals(spec) && e.session().isConnected()) {
                sessions.put(spec.id(), new Entry(spec, e.session(), System.currentTimeMillis()));
                return e.session();
            }
            if (e != null) {
                e.session().disconnect();
                sessions.remove(spec.id());
            }
        }
        // Connect outside the lock: one slow host must not hold up the others.
        Session s = connector.connect(spec);
        synchronized (lock) {
            Entry raced = sessions.get(spec.id());
            if (raced != null && raced.spec().equals(spec) && raced.session().isConnected()) {
                s.disconnect();
                return raced.session();
            }
            sessions.put(spec.id(), new Entry(spec, s, System.currentTimeMillis()));
            if (sweeper == null) {
                sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "docker-ssh-sweeper");
                    t.setDaemon(true);
                    return t;
                });
                long period = Math.max(1000, idleMillis / 2);
                sweeper.scheduleWithFixedDelay(this::sweep, period, period, TimeUnit.MILLISECONDS);
            }
            return s;
        }
    }

    /** Drop a session that failed (it will be re-opened on the next call). */
    void evict(String hostId) {
        synchronized (lock) {
            Entry e = sessions.remove(hostId);
            if (e != null) {
                e.session().disconnect();
            }
        }
    }

    void sweep() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            sessions.values().removeIf(e -> {
                if (now - e.lastUsed() > idleMillis || !e.session().isConnected()) {
                    e.session().disconnect();
                    return true;
                }
                return false;
            });
            if (sessions.isEmpty() && sweeper != null) {
                sweeper.shutdown();
                sweeper = null;
            }
        }
    }

    int open() {
        return sessions.size();
    }

    boolean sweeperRunning() {
        synchronized (lock) {
            return sweeper != null;
        }
    }

    @PreDestroy
    void closeAll() {
        synchronized (lock) {
            sessions.values().forEach(e -> e.session().disconnect());
            sessions.clear();
            if (sweeper != null) {
                sweeper.shutdownNow();
                sweeper = null;
            }
        }
    }
}
