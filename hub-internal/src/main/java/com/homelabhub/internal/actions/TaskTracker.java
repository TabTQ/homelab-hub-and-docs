package com.homelabhub.internal.actions;

import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.proxmox.ProxmoxException;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Follows a Proxmox task to its real outcome, instead of predicting it (wol-server reported the predicted state, plan 11).
 * Polls once a second for up to 90 s; a task that outlives that is reported as "timeout" (it may still finish).
 */
@Component
public class TaskTracker {

    private static final Logger log = LoggerFactory.getLogger(TaskTracker.class);
    static final Duration MAX_WATCH = Duration.ofSeconds(90);
    static final int MAX_TRACKED = 100;

    public enum State { RUNNING, OK, FAILED, TIMEOUT, UNKNOWN }

    public record Status(String id, String label, State state, String detail, Instant startedAt, Instant finishedAt) {}

    private final ProxmoxClient proxmox;
    private final Clock clock;
    private final long pollMillis;
    private final Map<String, Status> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "task-tracker");
        t.setDaemon(true);
        return t;
    });

    public TaskTracker(ProxmoxClient proxmox, Clock clock, @Value("${hub.actions.task-poll-millis:1000}") long pollMillis) {
        this.proxmox = proxmox;
        this.clock = clock;
        this.pollMillis = pollMillis;
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    public void track(String node, String upid, String label) {
        if (upid == null || upid.isEmpty()) {
            return;
        }
        evictOld();
        Instant started = clock.instant();
        tasks.put(upid, new Status(upid, label, State.RUNNING, null, started, null));
        pool.schedule(() -> poll(node, upid, label, started), pollMillis, TimeUnit.MILLISECONDS);
    }

    private void poll(String node, String upid, String label, Instant started) {
        try {
            ProxmoxClient.TaskStatus s = proxmox.taskStatus(node, upid);
            if (!s.running()) {
                boolean ok = "OK".equals(s.exitStatus());
                tasks.put(upid, new Status(upid, label, ok ? State.OK : State.FAILED, ok ? null : safe(s.exitStatus()), started, clock.instant()));
                return;
            }
        } catch (ProxmoxException | IllegalArgumentException e) {
            log.debug("Task {} poll failed: {}", upid, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Task {} poll crashed: {}", upid, e.toString());
        }
        if (Duration.between(started, clock.instant()).compareTo(MAX_WATCH) >= 0) {
            tasks.put(upid, new Status(upid, label, State.TIMEOUT, "Still running after " + MAX_WATCH.toSeconds() + " s. It may finish later.", started, clock.instant()));
            return;
        }
        pool.schedule(() -> poll(node, upid, label, started), pollMillis, TimeUnit.MILLISECONDS);
    }

    public Optional<Status> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    private static String safe(String s) {
        if (s == null) {
            return "The task failed";
        }
        String flat = s.replaceAll("[\\r\\n]+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }

    private void evictOld() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(15));
        tasks.values().removeIf(t -> t.state() != State.RUNNING && t.finishedAt() != null && t.finishedAt().isBefore(cutoff));
        while (tasks.size() >= MAX_TRACKED) {
            tasks.keySet().stream().findFirst().ifPresent(tasks::remove);
        }
    }

    Map<String, Status> all() {
        return new LinkedHashMap<>(tasks);
    }
}
