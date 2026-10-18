package com.homelabhub.internal.dashboard;

import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.config.DashboardConfig;
import com.homelabhub.internal.config.DashboardConfig.*;
import com.homelabhub.internal.config.Issue;
import com.homelabhub.internal.docker.DockerFeature;
import com.homelabhub.internal.status.StatusService;
import com.homelabhub.internal.terminal.TerminalFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the browser needs to draw the dashboard, and nothing more. Widget URLs, user names and secret references stay
 * on the server: the browser only learns that an item has a widget of some type (plan 7.4).
 */
@RestController
class DashboardController {

    private final ConfigStore store;
    private final StatusService status;
    private final TerminalFeature terminal;
    private final DockerFeature docker;

    DashboardController(ConfigStore store, StatusService status, TerminalFeature terminal, DockerFeature docker) {
        this.store = store;
        this.status = status;
        this.terminal = terminal;
        this.docker = docker;
    }

    @GetMapping("/api/dashboard")
    Map<String, Object> dashboard() {
        ConfigStore.State st = store.state();
        DashboardConfig c = st.config();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", c.title());
        out.put("theme", c.themeMode());
        out.put("widgets", c.widgets().stream().map(w -> Map.of("type", w.type(), "options", w.options())).toList());
        out.put("pinned", c.pinned());
        out.put("groups", c.groups().stream().map(this::group).toList());
        out.put("actions", c.actions().stream().map(this::action).toList());
        out.put("config", configHealth(st));
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("terminal", terminal.available());
        features.put("docker", docker.available());
        out.put("features", features);
        out.put("docsUrl", c.docsUrl());
        out.put("portfolioUrl", c.portfolioUrl());
        return out;
    }

    private Map<String, Object> configHealth(ConfigStore.State st) {
        long errors = st.issues().stream().filter(i -> i.level() == Issue.Level.ERROR).count();
        long warnings = st.issues().stream().filter(i -> i.level() == Issue.Level.WARNING).count();
        List<String> missing = st.issues().stream().filter(i -> i.message().startsWith("Secret '") && i.message().contains("is not set"))
                .map(i -> i.message().substring(8, i.message().indexOf("' is not set"))).distinct().toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("health", st.health().name().toLowerCase(Locale.ROOT));
        m.put("errors", errors);
        m.put("warnings", warnings);
        m.put("missingSecrets", missing);
        m.put("loadedAt", st.loadedAt().toString());
        return m;
    }

    private Map<String, Object> group(Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "group");
        m.put("id", g.id());
        m.put("name", g.name());
        m.put("entries", entries(g.entries()));
        return m;
    }

    private List<Object> entries(List<Entry> list) {
        List<Object> out = new ArrayList<>();
        for (Entry e : list) {
            if (e instanceof Group g) {
                out.add(group(g));
            } else if (e instanceof Item it) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "item");
                m.put("id", it.id());
                m.put("name", it.name());
                m.put("href", it.href());
                m.put("icon", it.icon());
                m.put("description", it.description());
                m.put("action", it.action());
                m.put("widget", it.widget() == null ? null : it.widget().type());
                m.put("statusId", status.tracks("item:" + it.id()) ? "item:" + it.id()
                        : (it.action() != null && status.tracks("action:" + it.action()) ? "action:" + it.action() : null));
                out.add(m);
            }
        }
        return out;
    }

    private Map<String, Object> action(ActionSpec a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("label", a.label());
        m.put("confirm", a.confirm().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        m.put("statusId", status.tracks("action:" + a.id()) ? "action:" + a.id() : null);
        if (a instanceof WolAction w) {
            m.put("type", "wol");
            m.put("mac", w.mac());
        } else if (a instanceof NodeAction n) {
            m.put("type", "proxmox-node");
            m.put("node", n.node());
            m.put("command", n.command());
        } else if (a instanceof GuestAction g) {
            m.put("type", "proxmox-guest");
            m.put("node", g.node());
            m.put("kind", g.kind());
            m.put("vmid", g.vmid());
            m.put("stopMode", g.stopMode());
        }
        return m;
    }
}
