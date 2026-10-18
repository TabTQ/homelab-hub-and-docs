package com.homelabhub.internal.config;

import com.homelabhub.internal.config.DashboardConfig.*;
import com.homelabhub.internal.config.YamlTree.L;
import com.homelabhub.internal.config.YamlTree.M;
import com.homelabhub.internal.config.YamlTree.N;
import com.homelabhub.internal.config.YamlTree.S;
import com.homelabhub.internal.wol.MacAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns dashboard.yaml text into a validated {@link DashboardConfig} plus a list of line-numbered issues (plan 7.3).
 * Refuses plaintext credentials, non-http(s) links (javascript: URLs), URL credentials, and host names that could be
 * read as command options by the {@code ping} check. Unknown keys are warnings, not errors, so a typo cannot take
 * the dashboard down.
 */
public final class ConfigParser {

    public record Result(DashboardConfig config, List<Issue> issues) {
        public boolean ok() {
            return config != null && issues.stream().noneMatch(i -> i.level() == Issue.Level.ERROR);
        }

        public List<Issue> errors() {
            return issues.stream().filter(i -> i.level() == Issue.Level.ERROR).toList();
        }
    }

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    static final Pattern NODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,62}");
    /** Must start with a letter or digit so it can never be read as an option flag (-f, --help). */
    static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,252}");
    static final Pattern ICON = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,119}|/[A-Za-z0-9._/-]{1,119}");
    static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z][A-Za-z0-9_]{0,63})}");
    private static final Pattern ANY_SECRET_REF = Pattern.compile("\\$\\{secret:([^}]*)}");
    /** Well-formed references anywhere inside a string; used when secrets are substituted at call time. */
    static final Pattern SECRET_REF_ANYWHERE = Pattern.compile("\\$\\{secret:([A-Za-z][A-Za-z0-9_]{0,63})}");
    private static final Pattern EXPECT = Pattern.compile("(\\d{3})(?:-(\\d{3}))?");
    private static final Set<String> SENSITIVE_KEYS = Set.of("password", "token", "apikey", "api_key", "secret", "passphrase");

    static final Pattern TERMINAL_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    static final Pattern UNIX_USER = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");
    /** A file name inside {data}/ssh. Never a path: no separators, and ".." is refused separately. */
    static final Pattern KEY_FILE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    static final Pattern HOST_KEY = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
    static final int MAX_TERMINALS = 10;
    static final int MAX_DOCKER_HOSTS = 20;
    /** Container names as Docker allows them. */
    static final Pattern CONTAINER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");

    private static final int MAX_ITEMS = 500, MAX_TOP_GROUPS = 50, MAX_DEPTH = 4, MAX_ACTIONS = 200, MAX_PINNED = 12;

    private final SecretLookup secrets;

    public ConfigParser(SecretLookup secrets) {
        this.secrets = secrets;
    }

    public Result parse(String text) {
        YamlTree.Result tree = YamlTree.parse(text == null ? "" : text);
        Run run = new Run(new ArrayList<>(tree.issues()));
        if (tree.root() == null) {
            return new Result(null, run.issues);
        }
        if (!(tree.root() instanceof M root)) {
            run.err("", tree.root().line(), "The top level must be a mapping (version, title, groups, ...).");
            return new Result(null, run.issues);
        }
        DashboardConfig cfg = run.readRoot(root);
        run.scanPlaintextCredentials(root, "");
        run.checkSecretRefs(root, "");
        return new Result(run.errorCount() == 0 ? cfg : null, run.issues);
    }

    // ------------------------------------------------------------------------------------------------------------

    private final class Run {
        final List<Issue> issues;
        int itemCount;
        final Set<String> actionIds = new LinkedHashSet<>();
        /** item -> action references, checked once all actions are known: {path, line, id} */
        final List<Object[]> itemActionRefs = new ArrayList<>();

        Run(List<Issue> issues) {
            this.issues = issues;
        }

        int errorCount() {
            return (int) issues.stream().filter(i -> i.level() == Issue.Level.ERROR).count();
        }

        void err(String path, int line, String msg) {
            issues.add(Issue.error(path, line, msg));
        }

        void warn(String path, int line, String msg) {
            issues.add(Issue.warning(path, line, msg));
        }

        // ---- primitive readers ------------------------------------------------------------------------------

        void known(M m, String path, String... allowed) {
            Set<String> ok = Set.of(allowed);
            for (String k : m.map().keySet()) {
                if (!ok.contains(k)) {
                    warn(YamlTree.join(path, k), m.keyLines().get(k), "Unknown key '" + k + "' is ignored.");
                }
            }
        }

        String str(M m, String key, String path, boolean required, int max) {
            N n = m.map().get(key);
            String p = YamlTree.join(path, key);
            if (n == null || (n instanceof S s && s.isNull())) {
                if (required) {
                    err(p, m.line(), "'" + key + "' is required.");
                }
                return null;
            }
            if (!(n instanceof S s)) {
                err(p, n.line(), "'" + key + "' must be text.");
                return null;
            }
            String v = s.raw().strip();
            if (v.isEmpty()) {
                if (required) {
                    err(p, n.line(), "'" + key + "' must not be empty.");
                }
                return null;
            }
            if (v.length() > max) {
                err(p, n.line(), "'" + key + "' is longer than " + max + " characters.");
                return null;
            }
            if (v.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
                err(p, n.line(), "'" + key + "' contains control characters.");
                return null;
            }
            return v;
        }

        Boolean bool(M m, String key, String path) {
            N n = m.map().get(key);
            if (n == null) {
                return null;
            }
            if (n instanceof S s) {
                String v = s.raw().strip().toLowerCase();
                if (v.equals("true") || v.equals("yes") || v.equals("on")) {
                    return true;
                }
                if (v.equals("false") || v.equals("no") || v.equals("off")) {
                    return false;
                }
            }
            err(YamlTree.join(path, key), n.line(), "'" + key + "' must be true or false.");
            return null;
        }

        Integer integer(M m, String key, String path, int min, int max) {
            N n = m.map().get(key);
            if (n == null) {
                return null;
            }
            if (n instanceof S s) {
                try {
                    int v = Integer.parseInt(s.raw().strip());
                    if (v >= min && v <= max) {
                        return v;
                    }
                } catch (NumberFormatException ignored) {
                    // falls through to the error
                }
            }
            err(YamlTree.join(path, key), n.line(), "'" + key + "' must be a whole number from " + min + " to " + max + ".");
            return null;
        }

        String oneOf(M m, String key, String path, boolean required, String... allowed) {
            String v = str(m, key, path, required, 40);
            if (v == null) {
                return null;
            }
            for (String a : allowed) {
                if (a.equalsIgnoreCase(v)) {
                    return a;
                }
            }
            err(YamlTree.join(path, key), m.map().get(key).line(), "'" + key + "' must be one of: " + String.join(", ", allowed) + ".");
            return null;
        }

        M map(N n, String path) {
            if (n instanceof M m) {
                return m;
            }
            err(path, n.line(), "Expected a mapping here.");
            return null;
        }

        /** A link the browser will follow: http(s) only, no credentials inside the URL. */
        String url(M m, String key, String path, boolean required, boolean allowRelative) {
            String v = str(m, key, path, required, 500);
            if (v == null) {
                return null;
            }
            String p = YamlTree.join(path, key);
            int line = m.map().get(key).line();
            if (allowRelative && v.startsWith("/") && !v.startsWith("//") && !v.contains("\\")) {
                return v;
            }
            try {
                URI u = new URI(v);
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (!(scheme.equals("http") || scheme.equals("https"))) {
                    err(p, line, "'" + key + "' must start with http:// or https://" + (allowRelative ? " (or be a path starting with /)." : "."));
                    return null;
                }
                if (u.getHost() == null || u.getHost().isEmpty()) {
                    err(p, line, "'" + key + "' has no host name.");
                    return null;
                }
                if (u.getUserInfo() != null) {
                    err(p, line, "'" + key + "' must not contain a user name or password. Use ${secret:NAME} in the widget options.");
                    return null;
                }
            } catch (java.net.URISyntaxException e) {
                err(p, line, "'" + key + "' is not a valid URL.");
                return null;
            }
            return v;
        }

        // ---- document ---------------------------------------------------------------------------------------

        DashboardConfig readRoot(M root) {
            known(root, "", "version", "title", "theme", "widgets", "pinned", "groups", "actions", "defaults", "terminals", "dockerHosts", "docs");
            Integer version = integer(root, "version", "", 1, 1);
            if (version == null && !root.map().containsKey("version")) {
                err("version", root.line(), "'version: 1' is required.");
            }
            String title = str(root, "title", "", false, 100);
            String theme = "auto";
            if (root.map().get("theme") != null) {
                M t = map(root.map().get("theme"), "theme");
                if (t != null) {
                    known(t, "theme", "mode");
                    String mode = oneOf(t, "mode", "theme", false, "auto", "light", "dark");
                    theme = mode == null ? "auto" : mode;
                }
            }
            Defaults defaults = readDefaults(root);
            List<InfoWidget> widgets = readInfoWidgets(root);
            List<Group> groups = new ArrayList<>();
            N g = root.map().get("groups");
            if (g == null) {
                err("groups", root.line(), "'groups' is required (it can be an empty list).");
            } else if (!(g instanceof L gl)) {
                err("groups", g.line(), "'groups' must be a list.");
            } else {
                if (gl.items().size() > MAX_TOP_GROUPS) {
                    err("groups", gl.line(), "At most " + MAX_TOP_GROUPS + " groups are allowed.");
                }
                for (int i = 0; i < gl.items().size(); i++) {
                    N e = gl.items().get(i);
                    String path = "groups[" + i + "]";
                    M gm = map(e, path);
                    if (gm == null) {
                        continue;
                    }
                    known(gm, path, "name", "items");
                    String name = str(gm, "name", path, true, 80);
                    List<Entry> entries = entries(gm, path, "g" + i, 1);
                    if (name != null) {
                        groups.add(new Group("g" + i, name, entries));
                    }
                }
            }
            List<ActionSpec> actions = readActions(root);
            for (Object[] ref : itemActionRefs) {
                if (!actionIds.contains((String) ref[2])) {
                    err((String) ref[0], (Integer) ref[1], "There is no action with the id '" + ref[2] + "'.");
                }
            }
            List<String> pinned = readPinned(root);
            List<TerminalSpec> terminals = readTerminals(root);
            List<DockerHostSpec> dockerHosts = readDockerHosts(root);
            String docsUrl = readDocsUrl(root);
            String portfolioUrl = readPortfolioUrl(root);
            return new DashboardConfig(title == null ? "Homelab" : title, theme, widgets, pinned, groups, actions, defaults, terminals,
                    dockerHosts, docsUrl, portfolioUrl);
        }

        /** docs: {url}: where the top bar's Docs link goes (docs plan D-L2). Only a link: the hub never calls it. */
        String readDocsUrl(M root) {
            N d = root.map().get("docs");
            if (d == null) {
                return null;
            }
            M m = map(d, "docs");
            if (m == null) {
                return null;
            }
            known(m, "docs", "url", "portfolioUrl");
            return url(m, "url", "docs", true, false);
        }

        /** docs.portfolioUrl (optional): the public portfolio's address. The portfolio is edited and served from the Docs LXC; the hub only links. */
        String readPortfolioUrl(M root) {
            N d = root.map().get("docs");
            if (d == null || !(d instanceof M m) || !m.map().containsKey("portfolioUrl")) {
                return null;
            }
            return url(m, "portfolioUrl", "docs", false, false);
        }

        Defaults readDefaults(M root) {
            boolean auto = false;
            int interval = 30;
            N n = root.map().get("defaults");
            if (n != null) {
                M d = map(n, "defaults");
                if (d != null) {
                    known(d, "defaults", "status", "interval");
                    String st = oneOf(d, "status", "defaults", false, "none", "auto");
                    auto = "auto".equals(st);
                    Integer iv = integer(d, "interval", "defaults", 10, 3600);
                    if (iv != null) {
                        interval = iv;
                    }
                }
            }
            return new Defaults(auto, interval);
        }

        List<InfoWidget> readInfoWidgets(M root) {
            List<InfoWidget> out = new ArrayList<>();
            N n = root.map().get("widgets");
            if (n == null) {
                return out;
            }
            if (!(n instanceof L l)) {
                err("widgets", n.line(), "'widgets' must be a list.");
                return out;
            }
            for (int i = 0; i < l.items().size(); i++) {
                String path = "widgets[" + i + "]";
                M m = map(l.items().get(i), path);
                if (m == null) {
                    continue;
                }
                if (m.map().size() != 1) {
                    err(path, m.line(), "Each widgets entry has exactly one key: resources or search.");
                    continue;
                }
                String type = m.map().keySet().iterator().next();
                N body = m.map().get(type);
                String p = path + "." + type;
                M opts = body instanceof S s && s.isNull() ? new M(new LinkedHashMap<>(), Map.of(), m.line()) : map(body, p);
                if (opts == null) {
                    continue;
                }
                Map<String, String> o = new LinkedHashMap<>();
                switch (type) {
                    case "resources" -> {
                        known(opts, p, "cpu", "memory", "disk", "cputemp", "uptime", "network", "units", "refresh");
                        for (String b : new String[] {"cpu", "memory", "cputemp", "uptime", "network"}) {
                            Boolean v = bool(opts, b, p);
                            if (v != null) {
                                o.put(b, v.toString());
                            }
                        }
                        N disk = opts.map().get("disk");
                        if (disk instanceof S s && !s.isNull()) {
                            String d = s.raw().strip();
                            if (d.equalsIgnoreCase("false") || d.equalsIgnoreCase("no")) {
                                o.put("disk", "");
                            } else if (d.startsWith("/") && d.length() <= 200 && !d.contains("..")) {
                                o.put("disk", d);
                            } else {
                                err(p + ".disk", s.line(), "'disk' must be an absolute path such as / (or false).");
                            }
                        }
                        String units = oneOf(opts, "units", p, false, "metric", "imperial");
                        if (units != null) {
                            o.put("units", units);
                        }
                    }
                    case "search" -> {
                        known(opts, p, "provider", "target");
                        String prov = oneOf(opts, "provider", p, false, "google", "duckduckgo", "bing");
                        o.put("provider", prov == null ? "google" : prov);
                        String target = oneOf(opts, "target", p, false, "_blank", "_self");
                        o.put("target", target == null ? "_blank" : target);
                    }
                    default -> {
                        err(p, m.line(), "Unknown widget '" + type + "'. Use resources or search.");
                        continue;
                    }
                }
                out.add(new InfoWidget(type, o));
            }
            return out;
        }

        List<Entry> entries(M parent, String path, String idPrefix, int depth) {
            List<Entry> out = new ArrayList<>();
            N n = parent.map().get("items");
            if (n == null) {
                err(YamlTree.join(path, "items"), parent.line(), "'items' is required (it can be an empty list).");
                return out;
            }
            if (!(n instanceof L l)) {
                err(YamlTree.join(path, "items"), n.line(), "'items' must be a list.");
                return out;
            }
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < l.items().size(); i++) {
                String p = path + ".items[" + i + "]";
                M m = map(l.items().get(i), p);
                if (m == null) {
                    continue;
                }
                String id = idPrefix + "." + i;
                if (m.map().containsKey("items")) {
                    if (depth >= MAX_DEPTH) {
                        err(p, m.line(), "Groups can be nested at most " + MAX_DEPTH + " levels deep.");
                        continue;
                    }
                    known(m, p, "name", "items");
                    String name = str(m, "name", p, true, 80);
                    List<Entry> sub = entries(m, p, id, depth + 1);
                    if (name != null) {
                        out.add(new Group(id, name, sub));
                    }
                } else {
                    Item item = item(m, p, id);
                    if (item != null) {
                        if (!names.add(item.name().toLowerCase())) {
                            warn(p + ".name", m.keyLines().getOrDefault("name", m.line()), "Another item in this group is also called '" + item.name() + "'.");
                        }
                        out.add(item);
                    }
                }
            }
            return out;
        }

        Item item(M m, String p, String id) {
            if (++itemCount > MAX_ITEMS) {
                if (itemCount == MAX_ITEMS + 1) {
                    err(p, m.line(), "At most " + MAX_ITEMS + " items are allowed.");
                }
                return null;
            }
            known(m, p, "name", "href", "icon", "description", "status", "widget", "action");
            String name = str(m, "name", p, true, 80);
            String action = str(m, "action", p, false, 64);
            if (action != null) {
                itemActionRefs.add(new Object[] {p + ".action", m.map().get("action").line(), action});
                if (m.map().containsKey("href")) {
                    warn(p + ".href", m.map().get("href").line(), "This item runs an action, so its 'href' is ignored.");
                }
            }
            String href = url(m, "href", p, false, true);
            String icon = icon(m, p);
            String desc = str(m, "description", p, false, 200);
            StatusCheck status = m.map().containsKey("status") ? status(m.map().get("status"), p + ".status") : null;
            WidgetSpec widget = m.map().containsKey("widget") ? widget(m.map().get("widget"), p + ".widget") : null;
            return name == null ? null : new Item(id, name, href, icon, desc, status, widget, action);
        }

        String icon(M m, String p) {
            String v = str(m, "icon", p, false, 300);
            if (v == null) {
                return null;
            }
            if (ICON.matcher(v).matches() || v.startsWith("https://") || v.startsWith("http://")) {
                if (v.startsWith("http")) {
                    // reuse the URL rules for absolute icons
                    Map<String, N> tmp = new LinkedHashMap<>();
                    tmp.put("icon", m.map().get("icon"));
                    url(new M(tmp, Map.of(), m.line()), "icon", p, false, false);
                }
                return v;
            }
            err(YamlTree.join(p, "icon"), m.map().get("icon").line(), "'icon' must be an icon name (proxmox.svg, mdi-home), a path starting with /, or an https URL.");
            return null;
        }

        StatusCheck status(N n, String p) {
            M m = map(n, p);
            if (m == null) {
                return null;
            }
            known(m, p, "icmp", "tcp", "http", "expect", "tls");
            List<String> kinds = new ArrayList<>();
            for (String k : new String[] {"icmp", "tcp", "http"}) {
                if (m.map().containsKey(k)) {
                    kinds.add(k);
                }
            }
            if (kinds.size() != 1) {
                err(p, m.line(), "A status check has exactly one of: icmp, tcp, http.");
                return null;
            }
            String kind = kinds.get(0);
            boolean insecure = !"system".equals(oneOf(m, "tls", p, false, "insecure", "system"));
            int from = 200, to = 499;
            String expect = str(m, "expect", p, false, 20);
            if (expect != null) {
                Matcher em = EXPECT.matcher(expect);
                if (!em.matches()) {
                    err(p + ".expect", m.map().get("expect").line(), "'expect' must be a status code like 200 or a range like 200-399.");
                } else {
                    from = Integer.parseInt(em.group(1));
                    to = em.group(2) == null ? from : Integer.parseInt(em.group(2));
                    if (from < 100 || to > 599 || to < from) {
                        err(p + ".expect", m.map().get("expect").line(), "'expect' must be within 100-599.");
                    }
                }
            }
            switch (kind) {
                case "icmp" -> {
                    String host = str(m, "icmp", p, true, 253);
                    if (host == null) {
                        return null;
                    }
                    if (!HOST.matcher(host).matches()) {
                        err(p + ".icmp", m.map().get("icmp").line(), "'icmp' must be a host name or IP address.");
                        return null;
                    }
                    return new StatusCheck(StatusCheck.Kind.ICMP, host, from, to, insecure);
                }
                case "tcp" -> {
                    String hp = str(m, "tcp", p, true, 260);
                    if (hp == null) {
                        return null;
                    }
                    int c = hp.lastIndexOf(':');
                    boolean okPort = false;
                    if (c > 0) {
                        try {
                            int port = Integer.parseInt(hp.substring(c + 1));
                            okPort = port >= 1 && port <= 65535 && HOST.matcher(hp.substring(0, c)).matches();
                        } catch (NumberFormatException ignored) {
                            // reported below
                        }
                    }
                    if (!okPort) {
                        err(p + ".tcp", m.map().get("tcp").line(), "'tcp' must look like host:port, for example 192.168.10.101:3000.");
                        return null;
                    }
                    return new StatusCheck(StatusCheck.Kind.TCP, hp, from, to, insecure);
                }
                default -> {
                    String u = url(m, "http", p, true, false);
                    return u == null ? null : new StatusCheck(StatusCheck.Kind.HTTP, u, from, to, insecure);
                }
            }
        }

        WidgetSpec widget(N n, String p) {
            M m = map(n, p);
            if (m == null) {
                return null;
            }
            String type = oneOf(m, "type", p, true, "proxmox", "proxmoxbackupserver", "filebrowser", "qbittorrent");
            if (type == null) {
                return null;
            }
            String url = url(m, "url", p, true, false);
            String tls = oneOf(m, "tls", p, false, "system", "insecure", "pinned");
            tls = tls == null ? "system" : tls;
            String fp = str(m, "fingerprint", p, false, 100);
            if (fp != null) {
                try {
                    com.homelabhub.internal.proxmox.TlsTrust.parseFingerprint(fp);
                } catch (IllegalArgumentException e) {
                    err(p + ".fingerprint", m.map().get("fingerprint").line(), "'fingerprint' must be a SHA-256 fingerprint (64 hex characters).");
                }
            }
            if (tls.equals("pinned") && fp == null) {
                err(p + ".tls", m.line(), "tls: pinned needs a 'fingerprint'.");
            }
            Set<String> allowed = new LinkedHashSet<>(List.of("type", "url", "tls", "fingerprint", "username", "password"));
            if (type.equals("proxmox")) {
                allowed.add("node");
            }
            known(m, p, allowed.toArray(new String[0]));
            Map<String, String> opts = new LinkedHashMap<>();
            String user = str(m, "username", p, true, 200);
            if (user != null) {
                opts.put("username", user);
            }
            credential(m, "password", p, true, opts);
            if (type.equals("proxmox")) {
                String node = str(m, "node", p, true, 63);
                if (node != null && !NODE.matcher(node).matches()) {
                    err(p + ".node", m.map().get("node").line(), "'node' must be a Proxmox node name such as pve.");
                } else if (node != null) {
                    opts.put("node", node);
                }
            }
            return url == null ? null : new WidgetSpec(type, url, tls, fp, opts);
        }

        /** Keeps a valid ${secret:NAME}. A literal value is reported by scanPlaintextCredentials, wherever it appears. */
        void credential(M m, String key, String p, boolean required, Map<String, String> into) {
            String v = str(m, key, p, required, 200);
            if (v != null && SECRET_REF.matcher(v).matches()) {
                into.put(key, v);
            }
        }

        /** Any key named password/token/apikey/secret must hold a whole ${secret:NAME}, in known AND unknown places. */
        void scanPlaintextCredentials(N n, String path) {
            if (n instanceof M m) {
                m.map().forEach((k, v) -> {
                    String p = YamlTree.join(path, k);
                    if (SENSITIVE_KEYS.contains(k.toLowerCase()) && v instanceof S s && !s.isNull()
                            && !SECRET_REF.matcher(s.raw().strip()).matches()) {
                        err(p, s.line(), "'" + k + "' must be a secret reference such as ${secret:PVE_PASSWORD}. "
                                + "Put the value in secrets.env, not in this file.");
                    } else if (SENSITIVE_KEYS.contains(k.toLowerCase()) && (v instanceof M || v instanceof L)) {
                        err(p, v.line(), "'" + k + "' must be a single secret reference such as ${secret:NAME}.");
                    } else {
                        scanPlaintextCredentials(v, p);
                    }
                });
            } else if (n instanceof L l) {
                for (int i = 0; i < l.items().size(); i++) {
                    scanPlaintextCredentials(l.items().get(i), path + "[" + i + "]");
                }
            }
        }

        List<ActionSpec> readActions(M root) {
            List<ActionSpec> out = new ArrayList<>();
            N n = root.map().get("actions");
            if (n == null) {
                return out;
            }
            if (!(n instanceof L l)) {
                err("actions", n.line(), "'actions' must be a list.");
                return out;
            }
            if (l.items().size() > MAX_ACTIONS) {
                err("actions", l.line(), "At most " + MAX_ACTIONS + " actions are allowed.");
                return out;
            }
            for (int i = 0; i < l.items().size(); i++) {
                String p = "actions[" + i + "]";
                M m = map(l.items().get(i), p);
                if (m == null) {
                    continue;
                }
                ActionSpec a = action(m, p);
                if (a != null) {
                    out.add(a);
                }
            }
            return out;
        }

        ActionSpec action(M m, String p) {
            String type = oneOf(m, "type", p, true, "wol", "proxmox-node", "proxmox-guest");
            String id = str(m, "id", p, true, 64);
            if (id != null) {
                if (!ID.matcher(id).matches()) {
                    err(p + ".id", m.map().get("id").line(), "'id' may only use lower-case letters, digits, - and _.");
                    id = null;
                } else if (!actionIds.add(id)) {
                    err(p + ".id", m.map().get("id").line(), "Another action already uses the id '" + id + "'.");
                    id = null;
                }
            }
            String label = str(m, "label", p, true, 80);
            if (type == null) {
                return null;
            }
            Confirm confirm = confirm(m, p, type);
            StatusCheck status = m.map().containsKey("status") ? status(m.map().get("status"), p + ".status") : null;
            switch (type) {
                case "wol" -> {
                    known(m, p, "id", "label", "type", "confirm", "status", "mac");
                    String mac = str(m, "mac", p, true, 40);
                    if (mac != null) {
                        try {
                            mac = MacAddress.parse(mac).toString();
                        } catch (IllegalArgumentException e) {
                            err(p + ".mac", m.map().get("mac").line(), "'mac' must look like AA:BB:CC:DD:EE:01.");
                            mac = null;
                        }
                    }
                    return id == null || label == null || mac == null ? null : new WolAction(id, label, confirm, status, mac);
                }
                case "proxmox-node" -> {
                    known(m, p, "id", "label", "type", "confirm", "status", "node", "command");
                    String node = node(m, p);
                    String cmd = oneOf(m, "command", p, true, "shutdown", "reboot");
                    return id == null || label == null || node == null || cmd == null ? null : new NodeAction(id, label, confirm, status, node, cmd);
                }
                default -> {
                    known(m, p, "id", "label", "type", "confirm", "status", "node", "kind", "vmid", "stop_mode");
                    String node = node(m, p);
                    String kind = oneOf(m, "kind", p, true, "lxc", "qemu");
                    Integer vmid = integer(m, "vmid", p, 1, 999_999_999);
                    if (vmid == null && !m.map().containsKey("vmid")) {
                        err(p + ".vmid", m.line(), "'vmid' is required.");
                    }
                    String stop = oneOf(m, "stop_mode", p, false, "stop", "shutdown");
                    return id == null || label == null || node == null || kind == null || vmid == null
                            ? null : new GuestAction(id, label, confirm, status, node, kind, vmid, stop == null ? "stop" : stop);
                }
            }
        }

        String node(M m, String p) {
            String node = str(m, "node", p, true, 63);
            if (node != null && !NODE.matcher(node).matches()) {
                err(p + ".node", m.map().get("node").line(), "'node' must be a Proxmox node name such as pve.");
                return null;
            }
            return node;
        }

        Confirm confirm(M m, String p, String type) {
            String v = oneOf(m, "confirm", p, false, "click", "type-name", "none");
            if (v == null) {
                return switch (type) {
                    case "wol" -> Confirm.CLICK;
                    case "proxmox-node" -> Confirm.TYPE_NAME;
                    default -> Confirm.AUTO;
                };
            }
            if (v.equals("none") && !type.equals("wol")) {
                err(p + ".confirm", m.map().get("confirm").line(), "'confirm: none' is only allowed for wol actions.");
            }
            return switch (v) {
                case "click" -> Confirm.CLICK;
                case "type-name" -> Confirm.TYPE_NAME;
                default -> Confirm.NONE;
            };
        }

        List<String> readPinned(M root) {
            List<String> out = new ArrayList<>();
            N n = root.map().get("pinned");
            if (n == null) {
                return out;
            }
            if (!(n instanceof L l)) {
                err("pinned", n.line(), "'pinned' must be a list of action ids.");
                return out;
            }
            if (l.items().size() > MAX_PINNED) {
                err("pinned", l.line(), "At most " + MAX_PINNED + " actions can be pinned.");
            }
            for (int i = 0; i < l.items().size(); i++) {
                if (l.items().get(i) instanceof S s && !s.isNull()) {
                    String id = s.raw().strip();
                    if (!actionIds.contains(id)) {
                        err("pinned[" + i + "]", s.line(), "There is no action with the id '" + id + "'.");
                    } else {
                        out.add(id);
                    }
                } else {
                    err("pinned[" + i + "]", l.items().get(i).line(), "Each pinned entry is an action id.");
                }
            }
            return out;
        }

        /** terminals: hosts the Terminal page may connect to. Only these can ever be reached (plan 5.2). */
        List<TerminalSpec> readTerminals(M root) {
            List<TerminalSpec> out = new ArrayList<>();
            readSshHosts(root, "terminals", "terminal", MAX_TERMINALS, List.of(), (spec, m, p) -> out.add(spec));
            return out;
        }

        /** dockerHosts: machines the Docker page manages over SSH (docs/design-history/docker-feature-plan.md). Same fields as terminals. */
        List<DockerHostSpec> readDockerHosts(M root) {
            List<DockerHostSpec> out = new ArrayList<>();
            readSshHosts(root, "dockerHosts", "Docker host", MAX_DOCKER_HOSTS, List.of("protect"), (spec, m, p) -> {
                List<String> protect = new ArrayList<>();
                N pn = m.map().get("protect");
                if (pn instanceof L pl) {
                    for (int i = 0; i < pl.items().size(); i++) {
                        N item = pl.items().get(i);
                        if (item instanceof S ps && !ps.isNull() && CONTAINER_NAME.matcher(ps.raw().strip()).matches()) {
                            protect.add(ps.raw().strip());
                        } else {
                            err(p + ".protect[" + i + "]", item.line(), "Each 'protect' entry is a container name such as hub-internal.");
                        }
                    }
                } else if (pn != null && !(pn instanceof S ps && ps.isNull())) {
                    err(p + ".protect", pn.line(), "'protect' must be a list of container names.");
                }
                out.add(new DockerHostSpec(spec.id(), spec.label(), spec.host(), spec.port(), spec.user(), spec.key(), spec.hostKey(),
                        List.copyOf(protect)));
            });
            return out;
        }

        interface SshHostSink {
            void accept(TerminalSpec spec, M entry, String path);
        }

        /** The shared validation of a list of SSH targets (id, label, host, port, user, key file name, pinned host key). */
        void readSshHosts(M root, String rootKey, String noun, int max, List<String> extraKeys, SshHostSink sink) {
            N n = root.map().get(rootKey);
            if (n == null || (n instanceof S s && s.isNull())) {
                return;
            }
            if (!(n instanceof L l)) {
                err(rootKey, n.line(), "'" + rootKey + "' must be a list.");
                return;
            }
            if (l.items().size() > max) {
                err(rootKey, l.line(), "At most " + max + " entries are allowed in '" + rootKey + "'.");
                return;
            }
            List<String> keys = new ArrayList<>(List.of("id", "label", "host", "port", "user", "key", "hostKey"));
            keys.addAll(extraKeys);
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < l.items().size(); i++) {
                String p = rootKey + "[" + i + "]";
                M m = map(l.items().get(i), p);
                if (m == null) {
                    continue;
                }
                known(m, p, keys.toArray(new String[0]));
                String id = str(m, "id", p, true, 32);
                if (id != null && !TERMINAL_ID.matcher(id).matches()) {
                    err(p + ".id", m.map().get("id").line(), "'id' may only use lower-case letters, digits, - and _ (at most 32).");
                    id = null;
                } else if (id != null && !ids.add(id)) {
                    err(p + ".id", m.map().get("id").line(), "Another " + noun + " already uses the id '" + id + "'.");
                    id = null;
                }
                String label = str(m, "label", p, true, 80);
                String host = str(m, "host", p, true, 253);
                if (host != null && !HOST.matcher(host).matches()) {
                    err(p + ".host", m.map().get("host").line(), "'host' must be a host name or an IP address such as 127.0.0.1.");
                    host = null;
                }
                Integer port = integer(m, "port", p, 1, 65535);
                boolean portOk = port != null || !m.map().containsKey("port");
                String user = str(m, "user", p, true, 32);
                if (user != null && !UNIX_USER.matcher(user).matches()) {
                    err(p + ".user", m.map().get("user").line(), "'user' must be a Linux user name such as hubshell.");
                    user = null;
                }
                String key = str(m, "key", p, true, 64);
                if (key != null && (!KEY_FILE.matcher(key).matches() || key.contains(".."))) {
                    err(p + ".key", m.map().get("key").line(), "'key' must be the NAME of a file in the data folder's ssh directory "
                            + "(letters, digits, . _ -), not a path.");
                    key = null;
                }
                String hostKey = str(m, "hostKey", p, true, 60);
                if (hostKey != null && !HOST_KEY.matcher(hostKey).matches()) {
                    err(p + ".hostKey", m.map().get("hostKey").line(), "'hostKey' must be a fingerprint such as SHA256:AbC... "
                            + "(43 characters after the colon, as printed by ssh-keygen -lf or terminal-hostkey).");
                    hostKey = null;
                }
                if (id != null && label != null && host != null && portOk && user != null && key != null && hostKey != null) {
                    sink.accept(new TerminalSpec(id, label, host, port == null ? 22 : port, user, key, hostKey), m, p);
                }
            }
        }

        /** Warn about ${secret:X} references that are not set, and about malformed ones. */
        void checkSecretRefs(N n, String path) {
            if (n instanceof S s) {
                Matcher m = ANY_SECRET_REF.matcher(s.raw());
                while (m.find()) {
                    String name = m.group(1);
                    if (!SECRET_REF.matcher("${secret:" + name + "}").matches()) {
                        err(path, s.line(), "Malformed secret reference '${secret:" + name + "}'. Names use letters, digits and _.");
                    } else if (!secrets.isSet(name)) {
                        warn(path, s.line(), "Secret '" + name + "' is not set. Add " + name + "=... to secrets.env.");
                    }
                }
            } else if (n instanceof M m) {
                m.map().forEach((k, v) -> checkSecretRefs(v, YamlTree.join(path, k)));
            } else if (n instanceof L l) {
                for (int i = 0; i < l.items().size(); i++) {
                    checkSecretRefs(l.items().get(i), path + "[" + i + "]");
                }
            }
        }
    }
}
