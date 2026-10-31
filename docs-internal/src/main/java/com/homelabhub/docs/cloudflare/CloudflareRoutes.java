package com.homelabhub.docs.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.homelabhub.docs.DocsAudit;
import com.homelabhub.docs.cloudflare.CloudflareClient.ApiException;
import com.homelabhub.docs.cloudflare.CloudflareClient.DnsRecord;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps Cloudflare in step with the published docs: every hostname in the snapshot's hosts.json gets a tunnel ingress rule to
 * docs-public and a proxied CNAME to the tunnel; hostnames that left the snapshot lose both. The one reconcile routine serves a
 * publish, an unpublish, a delete, a rename, the start-up check and a manual retry, so they cannot disagree.
 *
 * <p>Safety: the tunnel configuration is read, merged and written back under one lock ({@link IngressMerge} keeps every rule it does
 * not own, the catch-all last); only DNS records carrying {@link CloudflareProperties#MARKER} are deleted; reserved or invalid names are
 * never routed. A Cloudflare failure only changes the host's status here (pending / live / error) and is retried; it never touches
 * the docs themselves. With the feature off nothing in this class runs.
 */
@Service
public class CloudflareRoutes {

    private static final Logger log = LoggerFactory.getLogger(CloudflareRoutes.class);

    public enum State { PENDING, LIVE, ERROR, PLANNED }

    public record HostStatus(String host, State state, String message, Instant at) {}

    private final CloudflareProperties props;
    private final DocsAudit audit;
    private final CloudflareClient client;
    private final RouteHosts routeHosts;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, HostStatus> status = new LinkedHashMap<>();
    private ExecutorService executor;
    private volatile Set<String> desired = null;
    /** The hostname set last confirmed in Cloudflare without a single error; an identical publish then skips the API calls. */
    private volatile Set<String> reconciled = null;

    @Autowired
    public CloudflareRoutes(CloudflareProperties props, DocsAudit audit) {
        this(props, audit, props.enabled() && props.configError() == null ? new CloudflareClient(props) : null);
    }

    public CloudflareRoutes(CloudflareProperties props, DocsAudit audit, CloudflareClient client) {
        this.props = props;
        this.audit = audit;
        this.client = client;
        this.routeHosts = new RouteHosts(props.zoneName(), props.extraReserved());
    }

    public boolean active() {
        return props.enabled();
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        if (!active()) {
            return;
        }
        String err = props.configError();
        if (err != null) {
            log.error("Cloudflare route automation is enabled but not configured: {}", err);
        } else {
            log.info("Cloudflare route automation is ON ({}, zone {}); the start-up check follows the first snapshot", props.dryRun() ? "DRY RUN" : "live", props.zoneName());
        }
    }

    /** @return why {@code host} may not be routed, or null (also null when the feature is off) */
    public String hostError(String host) {
        return active() ? routeHosts.error(host) : null;
    }

    // ---------------------------------------------------------------- triggers

    /** Called after a snapshot was written: {@code published} is the host set of the new hosts.json. Returns at once; the work is queued. */
    public void requestSync(Set<String> published) {
        if (!active()) {
            return;
        }
        Set<String> d = Set.copyOf(published);
        desired = d;
        if (d.equals(reconciled)) {
            return; // an edit of a page that is already routed: nothing to do
        }
        markPending(d);
        enqueue("publish");
    }

    /** The retry button: checks and repairs everything again, even if nothing seems to have changed. */
    public void retry() {
        if (!active() || desired == null) {
            return;
        }
        reconciled = null;
        markPending(desired);
        enqueue("retry");
    }

    @Scheduled(fixedDelayString = "${docs.cloudflare.retry-millis:300000}", initialDelayString = "${docs.cloudflare.retry-millis:300000}")
    void retryErrors() {
        if (active() && desired != null && reconciled == null && !props.dryRun() && props.configError() == null) {
            enqueue("timer");
        }
    }

    private synchronized void enqueue(String trigger) {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cf-routes");
                t.setDaemon(true);
                return t;
            });
        }
        executor.execute(() -> {
            Set<String> d = desired;
            if (d != null) {
                reconcile(d, trigger);
            }
        });
    }

    @PreDestroy
    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- status

    private void markPending(Set<String> hosts) {
        Instant now = Instant.now();
        synchronized (status) {
            for (String h : hosts) {
                HostStatus s = status.get(h);
                if (s == null || s.state() != State.LIVE) {
                    status.put(h, new HostStatus(h, State.PENDING, "Waiting for Cloudflare.", now));
                }
            }
        }
    }

    /** @return the route state of one host, or null when the feature is off or the host is unknown */
    public Map<String, Object> routeInfo(String host) {
        synchronized (status) {
            HostStatus s = active() ? status.get(host) : null;
            return s == null ? null : toMap(s);
        }
    }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", active());
        m.put("dryRun", props.dryRun());
        m.put("configError", active() ? props.configError() : null);
        List<Map<String, Object>> hosts = new ArrayList<>();
        synchronized (status) {
            status.values().forEach(s -> hosts.add(toMap(s)));
        }
        m.put("hosts", hosts);
        return m;
    }

    private static Map<String, Object> toMap(HostStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", s.host());
        m.put("state", s.state().name().toLowerCase(java.util.Locale.ROOT));
        m.put("message", s.message());
        m.put("at", s.at().toString());
        return m;
    }

    // ---------------------------------------------------------------- reconcile

    /** Brings Cloudflare in line with {@code published}. Serialized: two publishes can never interleave their read-modify-write. */
    public void reconcile(Set<String> published, String trigger) {
        lock.lock();
        try {
            run(published, trigger);
        } catch (RuntimeException e) {
            log.error("Cloudflare reconcile failed unexpectedly: {}", e.toString());
            Map<String, String> errors = new LinkedHashMap<>();
            published.forEach(h -> errors.put(h, "Unexpected error: " + e.getClass().getSimpleName()));
            finish(published, errors, Set.of(), Map.of(), trigger);
        } finally {
            lock.unlock();
        }
    }

    private void run(Set<String> published, String trigger) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> route = new LinkedHashSet<>();
        for (String h : new TreeSet<>(published)) {
            String e = routeHosts.error(h);
            if (e != null) {
                errors.put(h, e);
            } else {
                route.add(h);
            }
        }
        String cfgError = props.configError();
        if (cfgError != null || client == null) {
            route.forEach(h -> errors.put(h, "Cloudflare automation is not configured: " + (cfgError == null ? "no client" : cfgError) + "."));
            finish(published, errors, Set.of(), Map.of(), trigger);
            return;
        }
        boolean dry = props.dryRun();
        Set<String> planned = new LinkedHashSet<>();
        Map<String, String> removeErrors = new LinkedHashMap<>();
        try {
            JsonNode config = client.getTunnelConfig();
            IngressMerge.Result m = IngressMerge.merge(config, route, props.service(), routeHosts::valid);
            m.conflicts().forEach(h -> errors.put(h, "Another tunnel rule already uses this name."));
            route.removeAll(m.conflicts());
            if (m.changed()) {
                if (dry) {
                    log.info("DRY RUN: would PUT the tunnel configuration: add {} remove {}", m.added(), m.removed());
                    planned.addAll(m.added());
                } else {
                    putConfig(m, trigger);
                }
            }
            syncDns(published, route, dry, planned, errors, removeErrors, trigger);
        } catch (ApiException | IllegalStateException e) {
            log.warn("Cloudflare reconcile stopped: {}", e.getMessage());
            route.forEach(h -> errors.putIfAbsent(h, e.getMessage()));
        }
        finish(published, errors, planned, removeErrors, trigger);
    }

    private void putConfig(IngressMerge.Result m, String trigger) {
        try {
            client.putTunnelConfig(m.config());
        } catch (ApiException e) {
            m.added().forEach(h -> audit("cf-ingress-add", h, false, trigger, e.getMessage()));
            m.removed().forEach(h -> audit("cf-ingress-remove", h, false, trigger, e.getMessage()));
            throw e;
        }
        m.added().forEach(h -> audit("cf-ingress-add", h, true, trigger, "-> " + props.service()));
        m.removed().forEach(h -> audit("cf-ingress-remove", h, true, trigger, "rule removed"));
    }

    private void syncDns(Set<String> published, Set<String> route, boolean dry, Set<String> planned, Map<String, String> errors,
                         Map<String, String> removeErrors, String trigger) {
        List<DnsRecord> all = client.listDns(null);
        Map<String, List<DnsRecord>> byName = new HashMap<>();
        all.forEach(r -> byName.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r));
        for (String host : route) {
            List<DnsRecord> recs = byName.getOrDefault(host, List.of());
            if (recs.isEmpty()) {
                addDns(host, dry, planned, errors, trigger);
            } else if (recs.stream().noneMatch(this::pointsAtTunnel)) {
                errors.put(host, "A DNS record named " + host + " already exists and is not managed by Docs.");
            }
        }
        for (DnsRecord r : all) {
            if (isManaged(r) && !published.contains(r.name()) && routeHosts.valid(r.name())) {
                removeDns(r, dry, removeErrors, trigger);
            }
        }
    }

    private void addDns(String host, boolean dry, Set<String> planned, Map<String, String> errors, String trigger) {
        if (dry) {
            log.info("DRY RUN: would POST dns_records CNAME {} -> {} (proxied, comment {})", host, props.cnameTarget(), CloudflareProperties.MARKER);
            planned.add(host);
            return;
        }
        try {
            client.createCname(host);
            audit("cf-dns-add", host, true, trigger, "CNAME -> tunnel");
        } catch (ApiException e) {
            if (e.alreadyExists() && client.listDns(host).stream().anyMatch(this::pointsAtTunnel)) {
                return; // someone (or an earlier run) created it a moment ago: already what we want
            }
            audit("cf-dns-add", host, false, trigger, e.getMessage());
            errors.put(host, e.alreadyExists() ? "A DNS record named " + host + " already exists and is not managed by Docs." : e.getMessage());
        }
    }

    private void removeDns(DnsRecord r, boolean dry, Map<String, String> removeErrors, String trigger) {
        if (dry) {
            log.info("DRY RUN: would DELETE dns_records {} ({})", r.name(), r.id());
            return;
        }
        try {
            boolean existed = client.deleteDns(r.id());
            audit("cf-dns-remove", r.name(), true, trigger, existed ? "record deleted" : "record already gone");
        } catch (ApiException e) {
            audit("cf-dns-remove", r.name(), false, trigger, e.getMessage());
            removeErrors.put(r.name(), "Could not remove the DNS record: " + e.getMessage());
        }
    }

    private boolean pointsAtTunnel(DnsRecord r) {
        return r.type().equals("CNAME") && r.content().equalsIgnoreCase(props.cnameTarget());
    }

    /** Only records the app created: CNAME to this tunnel carrying the marker comment. */
    boolean isManaged(DnsRecord r) {
        return pointsAtTunnel(r) && CloudflareProperties.MARKER.equals(r.comment());
    }

    private void finish(Set<String> published, Map<String, String> errors, Set<String> planned, Map<String, String> removeErrors, String trigger) {
        Instant now = Instant.now();
        synchronized (status) {
            status.keySet().removeIf(h -> !published.contains(h));
            for (String h : published) {
                String err = errors.get(h);
                if (err != null) {
                    status.put(h, new HostStatus(h, State.ERROR, err, now));
                } else if (planned.contains(h)) {
                    status.put(h, new HostStatus(h, State.PLANNED, "Dry run: the change was logged, not sent.", now));
                } else {
                    status.put(h, new HostStatus(h, State.LIVE, "Routed through the tunnel.", now));
                }
            }
            removeErrors.forEach((h, e) -> status.put(h, new HostStatus(h, State.ERROR, e, now)));
        }
        boolean clean = errors.isEmpty() && removeErrors.isEmpty() && planned.isEmpty() && props.configError() == null;
        reconciled = clean ? Set.copyOf(published) : null;
        if (!errors.isEmpty() || !removeErrors.isEmpty()) {
            log.warn("Cloudflare routes ({}): {} host(s) with errors; they are retried", trigger, errors.size() + removeErrors.size());
        }
    }

    private void audit(String action, String host, boolean ok, String trigger, String detail) {
        audit.record(action, host, ok ? "ok" : "failed", "trigger=" + trigger + " " + detail, null, null);
    }
}
