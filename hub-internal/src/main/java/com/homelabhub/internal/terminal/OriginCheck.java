package com.homelabhub.internal.terminal;

import com.homelabhub.auth.HostAllowlist;
import java.net.URI;
import java.util.Locale;

/**
 * Cross-site WebSocket hijacking defence (plan 5.3). A browser always sends Origin on a WebSocket upgrade, and a page on
 * another site cannot fake it. Accepted when the Origin names the same host (and port, when the Host header has one) as
 * the request, or names a host from HUB_ALLOWED_HOSTS. The second case covers reverse proxies such as
 * `tailscale serve`, which terminate https and may forward a different Host.
 *
 * <p>The scheme is deliberately NOT compared: behind `tailscale serve` the page is https while the hub sees plain http,
 * which is exactly why Spring's own same-origin check cannot be used here.
 */
final class OriginCheck {

    private OriginCheck() {}

    static boolean allowed(String origin, String hostHeader, HostAllowlist allowlist) {
        if (origin == null || origin.isBlank() || origin.equals("null")) {
            return false;
        }
        URI u;
        try {
            u = new URI(origin.strip());
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawPath() != null && !u.getRawPath().isEmpty()) {
            return false;
        }
        String oHost = stripBrackets(u.getHost()).toLowerCase(Locale.ROOT);
        int oPort = u.getPort() != -1 ? u.getPort() : (scheme.equals("https") ? 443 : 80);

        if (hostHeader != null && allowlist.allows(hostHeader)) {
            String h = hostHeader.strip();
            String hHost;
            int hPort = -1;
            if (h.startsWith("[")) {
                int close = h.indexOf(']');
                hHost = h.substring(1, close);
                if (h.length() > close + 2) {
                    hPort = Integer.parseInt(h.substring(close + 2));
                }
            } else {
                int colon = h.lastIndexOf(':');
                hHost = colon < 0 ? h : h.substring(0, colon);
                if (colon >= 0) {
                    hPort = Integer.parseInt(h.substring(colon + 1));
                }
            }
            if (hHost.toLowerCase(Locale.ROOT).equals(oHost) && (hPort == -1 || hPort == oPort)) {
                return true;
            }
        }
        return allowlist.isConfiguredName(oHost);
    }

    private static String stripBrackets(String h) {
        return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
    }
}
