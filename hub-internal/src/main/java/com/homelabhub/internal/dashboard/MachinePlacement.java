package com.homelabhub.internal.dashboard;

import com.homelabhub.internal.config.DashboardConfig;
import com.homelabhub.internal.config.DashboardConfig.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides which machine each dashboard item belongs to. First rule that fits:
 * 1. the item's {@code on:} names a machine;
 * 2. the host of its href (else status target, else widget url) equals a machine address, or matches a machine glob.
 *    Addresses are tried on every machine before any glob, machines in file order;
 * 3. otherwise it is unplaced (no entry in the result).
 * An action tile is placed on the machine that lists its action in {@code power} or {@code toggle}.
 */
public final class MachinePlacement {

    private MachinePlacement() {}

    /** Item id to machine id. Unplaced items are absent. */
    public static Map<String, String> place(DashboardConfig c) {
        Map<String, String> out = new HashMap<>();
        if (c.machines().isEmpty()) {
            return out;
        }
        for (Entry e : c.groups()) {
            walk(e, c, out);
        }
        return out;
    }

    private static void walk(Entry e, DashboardConfig c, Map<String, String> out) {
        if (e instanceof Group g) {
            for (Entry child : g.entries()) {
                walk(child, c, out);
            }
        } else if (e instanceof Item it) {
            String id = placeItem(it, c);
            if (id != null) {
                out.put(it.id(), id);
            }
        }
    }

    static String placeItem(Item it, DashboardConfig c) {
        if (it.on() != null) {
            for (Machine m : c.machines()) {
                if (m.id().equals(it.on())) {
                    return m.id();
                }
            }
        }
        if (it.action() != null) {
            for (Machine m : c.machines()) {
                if (m.power().contains(it.action()) || it.action().equals(m.toggle())) {
                    return m.id();
                }
            }
            return null;
        }
        String host = hostOf(it.href());
        if (host == null && it.status() != null) {
            host = hostOf(it.status().target());
        }
        if (host == null && it.widget() != null) {
            host = hostOf(it.widget().url());
        }
        return host == null ? null : byHost(host, c);
    }

    private static String byHost(String host, DashboardConfig c) {
        for (Machine m : c.machines()) {
            for (String a : m.addresses()) {
                if (host.equals(hostOf(a))) {
                    return m.id();
                }
            }
        }
        for (Machine m : c.machines()) {
            for (String g : m.match()) {
                if (globMatches(g, host)) {
                    return m.id();
                }
            }
        }
        return null;
    }

    /** Only {@code *} is special; the comparison ignores case. */
    static boolean globMatches(String glob, String host) {
        StringBuilder re = new StringBuilder();
        String[] parts = glob.toLowerCase(Locale.ROOT).split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                re.append(".*");
            }
            if (!parts[i].isEmpty()) {
                re.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(re.toString(), Pattern.DOTALL).matcher(host.toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * The lower-case host of a URL, "host:port", a bare host or a bracketed IPv6 address, or null when there is none
     * (for example a relative link such as /docs).
     */
    static String hostOf(String s) {
        if (s == null) {
            return null;
        }
        String v = s.strip();
        int scheme = v.indexOf("://");
        if (scheme > 0) {
            v = v.substring(scheme + 3);
        } else if (v.startsWith("/")) {
            return null;
        }
        int end = v.length();
        for (char ch : new char[] {'/', '?', '#'}) {
            int i = v.indexOf(ch);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        v = v.substring(0, end);
        int at = v.lastIndexOf('@');
        if (at >= 0) {
            v = v.substring(at + 1);
        }
        if (v.startsWith("[")) {
            int close = v.indexOf(']');
            v = close > 0 ? v.substring(1, close) : v.substring(1);
        } else if (v.indexOf(':') >= 0 && v.indexOf(':') == v.lastIndexOf(':')) {
            v = v.substring(0, v.indexOf(':'));
        }
        while (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.isEmpty() ? null : v.toLowerCase(Locale.ROOT);
    }
}
