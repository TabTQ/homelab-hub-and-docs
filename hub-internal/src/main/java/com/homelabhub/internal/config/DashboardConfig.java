package com.homelabhub.internal.config;

import java.util.List;
import java.util.Map;

/** The validated, immutable form of dashboard.yaml (plan section 7). Secret references stay unresolved strings. */
public record DashboardConfig(
        String title,
        String themeMode,
        List<InfoWidget> widgets,
        List<String> pinned,
        List<Group> groups,
        List<ActionSpec> actions,
        Defaults defaults,
        List<TerminalSpec> terminals,
        List<DockerHostSpec> dockerHosts,
        /** docs.url: the Docs app on its own LXC (null = no Docs link). */
        String docsUrl,
        /** docs.portfolioUrl: the public portfolio's address, a plain link on the dashboard card (null = none). */
        String portfolioUrl) {

    public record Defaults(boolean autoStatus, int intervalSeconds) {}

    /** resources / search widgets shown above the groups. */
    public record InfoWidget(String type, Map<String, String> options) {}

    public sealed interface Entry permits Item, Group {
        String id();
        String name();
    }

    public record Group(String id, String name, List<Entry> entries) implements Entry {}

    public record Item(String id, String name, String href, String icon, String description,
                       StatusCheck status, WidgetSpec widget, String action) implements Entry {}

    public record StatusCheck(Kind kind, String target, int expectFrom, int expectTo, boolean insecureTls) {
        public enum Kind { HTTP, TCP, ICMP }
    }

    /** Per-service widget (proxmox, proxmoxbackupserver, filebrowser, qbittorrent). Options may hold ${secret:X}. */
    public record WidgetSpec(String type, String url, String tls, String fingerprint, Map<String, String> options) {}

    public enum Confirm { CLICK, TYPE_NAME, NONE, AUTO }

    public sealed interface ActionSpec permits WolAction, NodeAction, GuestAction {
        String id();
        String label();
        Confirm confirm();
        StatusCheck status();
    }

    public record WolAction(String id, String label, Confirm confirm, StatusCheck status, String mac) implements ActionSpec {}

    public record NodeAction(String id, String label, Confirm confirm, StatusCheck status, String node, String command) implements ActionSpec {}

    public record GuestAction(String id, String label, Confirm confirm, StatusCheck status, String node,
                              String kind, int vmid, String stopMode) implements ActionSpec {}

    /**
     * A host the Terminal page may open a shell on. {@code key} is a file NAME inside {data}/ssh (never a path) and
     * {@code hostKey} the pinned SHA256 fingerprint of the server's host key. Neither is ever sent to the browser.
     */
    public record TerminalSpec(String id, String label, String host, int port, String user, String key, String hostKey) {}

    /**
     * A machine the Docker page manages: the docker CLI is run over SSH exactly like a Terminal host is reached (same key
     * and pinning rules). {@code protect} lists container names that may never be stopped, restarted, updated or removed.
     */
    public record DockerHostSpec(String id, String label, String host, int port, String user, String key, String hostKey,
                                 List<String> protect) {
        public TerminalSpec ssh() {
            return new TerminalSpec(id, label, host, port, user, key, hostKey);
        }
    }
}
