package com.homelabhub.internal.status;

import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.config.DashboardConfig;
import com.homelabhub.internal.config.DashboardConfig.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Keeps a status light for every item/action that has a check (plan 7.5). One bounded pool, per-check timeouts, and
 * identical checks shared. A crashing or hanging check yields UNKNOWN for that check only.
 */
@Service
public class StatusService {

    private static final Logger log = LoggerFactory.getLogger(StatusService.class);

    public record Reading(State state, Integer latencyMs, String detail, Instant checkedAt, Instant since) {}

    /** id -> check key, and key -> check. Ids are "item:g0.1" or "action:wake-pve". */
    record Plan(Map<String, String> idToKey, Map<String, StatusCheck> checks, int intervalSeconds) {}

    private final Checker checker;
    private final ConfigStore store;
    private final Clock clock;
    private final StatusStream stream;
    private final boolean autoStart;
    private final int checkTimeoutSeconds;
    private final Map<String, Reading> results = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "status-check");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "status-scheduler");
        t.setDaemon(true);
        return t;
    });
    private volatile Plan plan = new Plan(Map.of(), Map.of(), 30);
    private volatile ScheduledFuture<?> periodic;
    private volatile long lastManualRefresh;

    public StatusService(Checker checker, ConfigStore store, Clock clock, StatusStream stream,
                         @Value("${hub.status.auto-start:true}") boolean autoStart,
                         @Value("${hub.status.check-timeout-seconds:8}") int checkTimeoutSeconds) {
        this.checkTimeoutSeconds = checkTimeoutSeconds;
        this.checker = checker;
        this.store = store;
        this.clock = clock;
        this.stream = stream;
        this.autoStart = autoStart;
    }

    @PostConstruct
    void start() {
        plan = buildPlan(store.state().config());
        if (autoStart) {
            schedule(plan.intervalSeconds());
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        pool.shutdownNow();
    }

    @EventListener
    void onConfigChanged(ConfigStore.ConfigChangedEvent e) {
        Plan old = plan;
        plan = buildPlan(e.state().config());
        results.keySet().removeIf(k -> !plan.checks().containsKey(k));
        if (autoStart) {
            if (old.intervalSeconds() != plan.intervalSeconds()) {
                schedule(plan.intervalSeconds());
            }
            scheduler.execute(this::runRound);
        }
    }

    private synchronized void schedule(int seconds) {
        if (periodic != null) {
            periodic.cancel(false);
        }
        periodic = scheduler.scheduleWithFixedDelay(this::runRoundSafely, 1, seconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(stream::heartbeat, 20, 20, TimeUnit.SECONDS);
    }

    private void runRoundSafely() {
        try {
            runRound();
        } catch (RuntimeException e) {
            log.warn("Status round failed: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- planning

    static Plan buildPlan(DashboardConfig cfg) {
        Map<String, String> idToKey = new LinkedHashMap<>();
        Map<String, StatusCheck> checks = new LinkedHashMap<>();
        for (Group g : cfg.groups()) {
            collect(g.entries(), cfg.defaults().autoStatus(), idToKey, checks);
        }
        for (ActionSpec a : cfg.actions()) {
            if (a.status() != null) {
                register("action:" + a.id(), a.status(), idToKey, checks);
            }
        }
        return new Plan(idToKey, checks, cfg.defaults().intervalSeconds());
    }

    private static void collect(List<Entry> entries, boolean auto, Map<String, String> idToKey, Map<String, StatusCheck> checks) {
        for (Entry e : entries) {
            if (e instanceof Group g) {
                collect(g.entries(), auto, idToKey, checks);
            } else if (e instanceof Item it) {
                StatusCheck c = it.status();
                if (c == null && auto && it.action() == null) {
                    c = derive(it.href());
                }
                if (c != null) {
                    register("item:" + it.id(), c, idToKey, checks);
                }
            }
        }
    }

    /** defaults.status: auto — a quick TCP connect to the link's host and port. */
    static StatusCheck derive(String href) {
        if (href == null || !(href.startsWith("http://") || href.startsWith("https://"))) {
            return null;
        }
        try {
            URI u = URI.create(href);
            int port = u.getPort() > 0 ? u.getPort() : (u.getScheme().equals("https") ? 443 : 80);
            return new StatusCheck(StatusCheck.Kind.TCP, u.getHost() + ":" + port, 200, 499, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void register(String id, StatusCheck c, Map<String, String> idToKey, Map<String, StatusCheck> checks) {
        String key = c.kind() + "|" + c.target() + "|" + c.expectFrom() + "-" + c.expectTo() + "|" + c.insecureTls();
        idToKey.put(id, key);
        checks.putIfAbsent(key, c);
    }

    // ---------------------------------------------------------------- running

    /** Runs every check once. Only one round runs at a time. */
    public void runRound() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Plan p = plan;
            Map<String, Future<CheckResult>> futures = new LinkedHashMap<>();
            for (var e : p.checks().entrySet()) {
                futures.put(e.getKey(), pool.submit(() -> safely(e.getValue())));
            }
            for (var e : futures.entrySet()) {
                CheckResult r;
                try {
                    r = e.getValue().get(checkTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException ex) {
                    e.getValue().cancel(true);
                    r = CheckResult.unknown("check did not finish");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    r = CheckResult.unknown("check failed");
                }
                record(p, e.getKey(), r);
            }
        } finally {
            running.set(false);
        }
    }

    private CheckResult safely(StatusCheck c) {
        try {
            CheckResult r = checker.run(c);
            return r == null ? CheckResult.unknown("no result") : r;
        } catch (Throwable t) {
            return CheckResult.unknown("check crashed: " + t.getClass().getSimpleName());
        }
    }

    private void record(Plan p, String key, CheckResult r) {
        Instant now = clock.instant();
        Reading old = results.get(key);
        boolean changed = old == null || old.state() != r.state();
        Reading fresh = new Reading(r.state(), r.latencyMs(), r.detail(), now, changed ? now : old.since());
        results.put(key, fresh);
        if (changed) {
            p.idToKey().forEach((id, k) -> {
                if (k.equals(key)) {
                    stream.broadcast("status", view(id, fresh));
                }
            });
        }
    }

    /** Manual "recheck now" with a 5 s cooldown so a button cannot be used to hammer the network. */
    public boolean refreshNow() {
        long now = System.currentTimeMillis();
        if (now - lastManualRefresh < 5000 || running.get()) {
            return false;
        }
        lastManualRefresh = now;
        scheduler.execute(this::runRoundSafely);
        return true;
    }

    // ---------------------------------------------------------------- reading

    public Map<String, Map<String, Object>> snapshot() {
        Plan p = plan;
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        p.idToKey().forEach((id, key) -> {
            Reading e = results.get(key);
            out.put(id, view(id, e == null ? new Reading(State.UNKNOWN, null, "not checked yet", null, null) : e));
        });
        return out;
    }

    public Map<String, Object> viewOf(String id) {
        return snapshot().get(id);
    }

    static Map<String, Object> view(String id, Reading e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("state", e.state().name().toLowerCase());
        m.put("latencyMs", e.latencyMs());
        m.put("detail", e.detail());
        m.put("checkedAt", e.checkedAt() == null ? null : e.checkedAt().toString());
        m.put("since", e.since() == null ? null : e.since().toString());
        return m;
    }

    /** True when the item/action with this id has a status light. */
    public boolean tracks(String id) {
        return plan.idToKey().containsKey(id);
    }

    List<String> ids() {
        return new ArrayList<>(plan.idToKey().keySet());
    }
}
