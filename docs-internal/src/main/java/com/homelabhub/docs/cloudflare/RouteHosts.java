package com.homelabhub.docs.cloudflare;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which hostnames the automation may route: exactly one lowercase DNS label under the zone ({@code label.zone}). Wildcards, the apex,
 * deeper names, labels starting with {@code _} and the reserved labels (mail, vpn, other apps) are never accepted.
 */
public final class RouteHosts {

    /** Generic names that must never be claimed; DOCS_CF_RESERVED_LABELS adds the deployment's own. */
    static final Set<String> BUILT_IN_RESERVED = Set.of("www", "vpn", "email", "mail", "smtp", "ftp", "autodiscover", "_domainconnect");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private final String zone;
    private final Set<String> reserved = new HashSet<>(BUILT_IN_RESERVED);

    public RouteHosts(String zone, Iterable<String> extraReserved) {
        this.zone = zone;
        extraReserved.forEach(reserved::add);
    }

    /** @return null when {@code host} may be routed, else a short reason for the user */
    public String error(String host) {
        if (host == null || host.isBlank()) return "Enter a hostname.";
        if (zone.isEmpty()) return "The zone is not configured.";
        if (host.contains("*")) return "Wildcard hostnames cannot be routed.";
        if (host.equals(zone)) return "The main domain cannot be a page address.";
        if (!host.endsWith("." + zone)) return "The hostname must end with ." + zone + ".";
        String label = host.substring(0, host.length() - zone.length() - 1);
        if (label.contains(".")) return "Use a single-level name, such as page1." + zone + ".";
        if (label.startsWith("_")) return "Names starting with _ are reserved.";
        if (reserved.contains(label)) return "The name “" + label + "” is reserved.";
        if (!LABEL.matcher(label).matches()) return "Use 1 to 63 lowercase letters, digits and inner hyphens.";
        return null;
    }

    public boolean valid(String host) {
        return error(host) == null;
    }
}
