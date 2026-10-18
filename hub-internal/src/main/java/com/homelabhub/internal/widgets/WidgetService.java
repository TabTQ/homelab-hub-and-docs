package com.homelabhub.internal.widgets;

import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.config.DashboardConfig;
import com.homelabhub.internal.config.DashboardConfig.*;
import com.homelabhub.internal.config.SecretStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fetches widget data on the server (credentials never reach the browser), caches it briefly so many open browsers do
 * not hammer the upstream, and serves the last good value marked "stale" when the upstream is down (plan 7.5).
 */
@Service
public class WidgetService {

    private static final Logger log = LoggerFactory.getLogger(WidgetService.class);
    private static final Duration MAX_STALE = Duration.ofMinutes(10);

    private record Cached(Map<String, Object> data, Instant fetchedAt) {}

    private final ConfigStore store;
    private final SecretStore secrets;
    private final Map<String, WidgetFetcher> fetchers;
    private final HostMetrics metrics;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, WidgetHttp> clients = new ConcurrentHashMap<>();

    public WidgetService(ConfigStore store, SecretStore secrets, List<WidgetFetcher> fetchers, HostMetrics metrics, Clock clock,
                         @Value("${hub.widgets.cache-seconds:15}") int cacheSeconds) {
        this.store = store;
        this.secrets = secrets;
        this.fetchers = fetchers.stream().collect(Collectors.toMap(WidgetFetcher::type, Function.identity()));
        this.metrics = metrics;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(cacheSeconds);
    }

    /** @return empty when there is no such item or it has no widget */
    public Optional<Map<String, Object>> forItem(String itemId) {
        Optional<WidgetSpec> spec = find(store.state().config().groups(), itemId);
        if (spec.isEmpty()) {
            return Optional.empty();
        }
        WidgetSpec s = spec.get();
        WidgetFetcher f = fetchers.get(s.type());
        if (f == null) {
            return Optional.of(error(s.type(), WidgetException.Code.NOT_CONFIGURED, "This widget type is not supported"));
        }
        return Optional.of(cached("item:" + itemId + "|" + s.hashCode(), s.type(), () -> f.fetch(s, secrets, http(s))));
    }

    /** resources widget. Other info widgets (search) carry no server data. */
    public Optional<Map<String, Object>> forInfo(String type) {
        if (!type.equals("resources")) {
            return Optional.empty();
        }
        Optional<InfoWidget> w = store.state().config().widgets().stream().filter(x -> x.type().equals("resources")).findFirst();
        if (w.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> o = w.get().options();
        Set<String> wanted = new HashSet<>();
        for (String k : List.of("cpu", "memory", "cputemp", "uptime", "network")) {
            if ("true".equals(o.get(k))) {
                wanted.add(k);
            }
        }
        if (o.containsKey("disk") && !o.get("disk").isEmpty()) {
            wanted.add("disk");
        }
        return Optional.of(cached("info:resources|" + o.hashCode(), "resources", () -> metrics.read(wanted, o.get("disk"), o.get("units"))));
    }

    private WidgetHttp http(WidgetSpec s) {
        String key = s.url() + "|" + s.tls() + "|" + s.fingerprint();
        return clients.computeIfAbsent(key, k -> new WidgetHttp(s, Duration.ofSeconds(3), Duration.ofSeconds(8)));
    }

    private Map<String, Object> cached(String key, String type, java.util.function.Supplier<Map<String, Object>> fetch) {
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) { // single flight: concurrent browsers share one upstream call
            Instant now = clock.instant();
            Cached c = cache.get(key);
            if (c != null && Duration.between(c.fetchedAt(), now).compareTo(ttl) < 0) {
                return response("ok", type, c, null);
            }
            try {
                Map<String, Object> data = fetch.get();
                Cached fresh = new Cached(data, now);
                cache.put(key, fresh);
                return response("ok", type, fresh, null);
            } catch (WidgetException e) {
                return failure(key, type, c, now, e.code(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Widget {} failed unexpectedly: {}", type, e.toString());
                return failure(key, type, c, now, WidgetException.Code.BAD_RESPONSE, "The widget could not be read");
            }
        }
    }

    private Map<String, Object> failure(String key, String type, Cached last, Instant now, WidgetException.Code code, String message) {
        if (last != null && Duration.between(last.fetchedAt(), now).compareTo(MAX_STALE) < 0) {
            return response("stale", type, last, Map.of("code", code.name().toLowerCase(), "message", message));
        }
        cache.remove(key);
        return error(type, code, message);
    }

    private Map<String, Object> response(String state, String type, Cached c, Map<String, String> error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("type", type);
        m.put("data", c.data());
        m.put("fetchedAt", c.fetchedAt().toString());
        if (error != null) {
            m.put("error", error);
        }
        return m;
    }

    private Map<String, Object> error(String type, WidgetException.Code code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", "error");
        m.put("type", type);
        m.put("data", null);
        m.put("error", Map.of("code", code.name().toLowerCase(), "message", message));
        return m;
    }

    private static Optional<WidgetSpec> find(List<Group> groups, String itemId) {
        for (Group g : groups) {
            Optional<WidgetSpec> r = find(g.entries(), itemId);
            if (r.isPresent()) {
                return r;
            }
        }
        return Optional.empty();
    }

    private static Optional<WidgetSpec> find(java.util.Collection<DashboardConfig.Entry> entries, String itemId) {
        for (DashboardConfig.Entry e : entries) {
            if (e instanceof Item it && it.id().equals(itemId)) {
                return Optional.ofNullable(it.widget());
            }
            if (e instanceof Group g) {
                Optional<WidgetSpec> r = find(g.entries(), itemId);
                if (r.isPresent()) {
                    return r;
                }
            }
        }
        return Optional.empty();
    }
}
