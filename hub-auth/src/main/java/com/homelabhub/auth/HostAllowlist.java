package com.homelabhub.auth;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class HostAllowlist {

    private final Set<String> configured = new HashSet<>();
    private final Set<String> wildcardSuffixes = new HashSet<>();

    public HostAllowlist(Collection<String> configured) {
        if (configured != null) {
            for (String entry : configured) {
                if (entry == null) {
                    continue;
                }
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.indexOf(':') >= 0) {
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith("*.")) {
                    wildcardSuffixes.add(lower.substring(1));
                } else {
                    this.configured.add(lower);
                }
            }
        }
    }

    public boolean allows(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty() || hostHeader.length() > 255) {
            return false;
        }
        for (int i = 0; i < hostHeader.length(); i++) {
            char c = hostHeader.charAt(i);
            if (c <= 0x20 || c == 0x7F) {
                return false;
            }
            if (c == '/' || c == '\\' || c == '@' || c == '?' || c == '#') {
                return false;
            }
        }

        String host;
        if (hostHeader.charAt(0) == '[') {
            int close = hostHeader.indexOf(']');
            if (close < 0) {
                return false;
            }
            String literal = hostHeader.substring(1, close);
            String rest = hostHeader.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.length() < 2 || rest.charAt(0) != ':') {
                    return false;
                }
                if (!isValidPort(rest.substring(1))) {
                    return false;
                }
            }
            return isIPv6Literal(literal);
        }

        int colon = hostHeader.lastIndexOf(':');
        if (colon >= 0) {
            if (!isValidPort(hostHeader.substring(colon + 1))) {
                return false;
            }
            host = hostHeader.substring(0, colon);
            if (host.isEmpty() || host.indexOf(':') >= 0) {
                return false;
            }
        } else {
            host = hostHeader;
        }

        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        String lower = host.toLowerCase(Locale.ROOT);

        if (lower.equals("localhost")) {
            return true;
        }
        if (isIPv4Literal(host)) {
            return true;
        }
        if (configured.contains(lower)) {
            return true;
        }
        for (String suffix : wildcardSuffixes) {
            if (lower.length() > suffix.length() && lower.endsWith(suffix)) {
                int before = lower.length() - suffix.length() - 1;
                if (lower.charAt(before) != '.') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True only for a name listed in HUB_ALLOWED_HOSTS (exactly or by wildcard), never for IP literals or localhost. Used
     * where "any IP" is too loose, such as trusting a WebSocket Origin that a reverse proxy made differ from the Host.
     */
    public boolean isConfiguredName(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (configured.contains(lower)) {
            return true;
        }
        for (String suffix : wildcardSuffixes) {
            if (lower.length() > suffix.length() && lower.endsWith(suffix) && lower.charAt(lower.length() - suffix.length() - 1) != '.') {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidPort(String port) {
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        for (int i = 0; i < port.length(); i++) {
            char c = port.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        int value;
        try {
            value = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return false;
        }
        return value >= 1 && value <= 65535;
    }

    private static boolean isIPv4Literal(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int value = 0;
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIPv6Literal(String s) {
        if (s.isEmpty() || s.length() > 45) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(isHexDigit(c) || c == ':' || c == '.')) {
                return false;
            }
        }
        int dci = s.indexOf("::");
        if (s.indexOf("::", dci + 1) >= 0) {
            return false;
        }
        if (dci < 0) {
            String[] groups = s.split(":", -1);
            if (groups.length != 8) {
                return false;
            }
            for (int i = 0; i < 7; i++) {
                if (!isHexGroup(groups[i])) {
                    return false;
                }
            }
            return isHexGroup(groups[7]) || isIPv4Literal(groups[7]);
        }
        String[] head = s.substring(0, dci).isEmpty() ? new String[0] : s.substring(0, dci).split(":", -1);
        String[] tail = s.substring(dci + 2).isEmpty() ? new String[0] : s.substring(dci + 2).split(":", -1);
        if (head.length + tail.length > 7) {
            return false;
        }
        for (String group : head) {
            if (!isHexGroup(group)) {
                return false;
            }
        }
        for (int i = 0; i < tail.length - 1; i++) {
            if (!isHexGroup(tail[i])) {
                return false;
            }
        }
        if (tail.length > 0) {
            String last = tail[tail.length - 1];
            if (!(isHexGroup(last) || isIPv4Literal(last))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return false;
        }
        for (int i = 0; i < group.length(); i++) {
            if (!isHexDigit(group.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}