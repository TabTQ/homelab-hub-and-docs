package com.homelabhub.docs.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure read-modify-write logic for the tunnel's ingress list. The PUT replaces the whole configuration, so everything this class does
 * not own is kept untouched and in order: other rules, the wildcard rule, top-level settings, and the catch-all, which stays last.
 *
 * <p>A rule is the app's own only when it routes one explicit, valid hostname to the docs-public service and carries nothing else
 * (just {@code hostname} and {@code service}). Only such rules are ever removed.
 */
public final class IngressMerge {

    public record Result(ObjectNode config, Set<String> added, Set<String> removed, Set<String> conflicts) {
        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    private IngressMerge() {}

    /**
     * @param config    the config as read from Cloudflare (not modified)
     * @param desired   hostnames that must be routed to {@code service}
     * @param service   the docs-public origin, e.g. http://docs-public:3011
     * @param removable whether a hostname may be removed when it is no longer desired (valid, not reserved)
     * @throws IllegalStateException when the config has no final catch-all rule (it is not changed then)
     */
    public static Result merge(JsonNode config, Set<String> desired, String service, Predicate<String> removable) {
        ObjectNode out = config != null && config.isObject() ? ((ObjectNode) config).deepCopy() : JsonNodeFactory.instance.objectNode();
        JsonNode in = out.path("ingress");
        if (!in.isArray() || in.isEmpty() || !isCatchAll(in.get(in.size() - 1))) {
            throw new IllegalStateException("The tunnel configuration has no final catch-all rule; refusing to change it.");
        }
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> conflicts = new LinkedHashSet<>();
        List<JsonNode> rules = new ArrayList<>();
        for (JsonNode r : in) {
            String host = r.path("hostname").asText("");
            if (isOwn(r, service, removable) && !desired.contains(host)) {
                removed.add(host);
                continue;
            }
            rules.add(r);
        }
        for (String host : desired) {
            JsonNode existing = null;
            for (JsonNode r : rules) {
                if (host.equals(r.path("hostname").asText(null))) {
                    existing = r;
                    break;
                }
            }
            if (existing == null) {
                rules.add(insertAt(rules), JsonNodeFactory.instance.objectNode().put("hostname", host).put("service", service));
                added.add(host);
            } else if (!service.equals(existing.path("service").asText())) {
                conflicts.add(host); // another rule (for example the portfolio) already owns this name: leave it alone
            }
        }
        ArrayNode list = out.putArray("ingress");
        rules.forEach(list::add);
        return new Result(out, added, removed, conflicts);
    }

    /** New rules go before the first wildcard or catch-all rule, so a more specific rule is always matched first. */
    private static int insertAt(List<JsonNode> rules) {
        for (int i = 0; i < rules.size(); i++) {
            String host = rules.get(i).path("hostname").asText("");
            if (host.isEmpty() || host.startsWith("*")) return i;
        }
        return rules.size();
    }

    static boolean isCatchAll(JsonNode rule) {
        return rule != null && rule.path("hostname").asText("").isEmpty() && !rule.has("path");
    }

    static boolean isOwn(JsonNode rule, String service, Predicate<String> removable) {
        if (!rule.isObject() || !service.equals(rule.path("service").asText(null))) return false;
        String host = rule.path("hostname").asText("");
        if (host.isEmpty() || host.startsWith("*") || !removable.test(host)) return false;
        Iterator<String> keys = rule.fieldNames();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!k.equals("hostname") && !k.equals("service")) return false;
        }
        return true;
    }
}
