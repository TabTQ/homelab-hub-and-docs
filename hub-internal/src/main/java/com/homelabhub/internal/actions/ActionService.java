package com.homelabhub.internal.actions;

import com.homelabhub.internal.audit.AuditLog;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.config.DashboardConfig.*;
import com.homelabhub.internal.legacy.InFlightGuard;
import com.homelabhub.internal.proxmox.GuestStatus;
import com.homelabhub.internal.proxmox.GuestType;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.proxmox.ProxmoxException;
import com.homelabhub.internal.status.StatusService;
import com.homelabhub.internal.wol.MacAddress;
import com.homelabhub.internal.wol.WakeOnLanException;
import com.homelabhub.internal.wol.WakeOnLanSender;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The UI side of power actions (plan 9.1): prepare (describe the impact, hand out a one-time token) then confirm
 * (execute). Guest actions use a DESIRED state, so a double click or two browsers cannot flip a guest twice.
 */
@Service
public class ActionService {

    public record Prepared(String token, Instant expiresAt, String actionId, String title, String kind, String confirm,
                           String word, List<String> impact, String desired) {}

    public record Executed(boolean ok, String message, String taskId) {}

    private final ConfigStore store;
    private final ProxmoxClient proxmox;
    private final WakeOnLanSender wol;
    private final ConfirmationTokens tokens;
    private final TaskTracker tasks;
    private final AuditLog audit;
    private final InFlightGuard inFlight;
    private final StatusService status;

    public ActionService(ConfigStore store, ProxmoxClient proxmox, WakeOnLanSender wol, ConfirmationTokens tokens,
                         TaskTracker tasks, AuditLog audit, InFlightGuard inFlight, StatusService status) {
        this.store = store;
        this.proxmox = proxmox;
        this.wol = wol;
        this.tokens = tokens;
        this.tasks = tasks;
        this.audit = audit;
        this.inFlight = inFlight;
        this.status = status;
    }

    private ActionSpec find(String id) {
        return store.state().config().actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new ActionException(HttpStatus.NOT_FOUND, "unknown_action", "There is no action called '" + id + "'."));
    }

    // ---------------------------------------------------------------- prepare

    public Prepared prepare(String actionId, String sessionKey) {
        ActionSpec a = find(actionId);
        List<String> impact = new ArrayList<>();
        String title, kind, desired = null, word = null;
        Confirm confirm = a.confirm();

        if (a instanceof WolAction w) {
            kind = "wake";
            title = "Wake " + w.label();
            impact.add("Sends a Wake-on-LAN magic packet to " + w.mac() + ".");
            impact.add("It can only confirm that the packet was sent. The status light shows whether the device answers.");
            Map<String, Object> st = status.viewOf("action:" + a.id());
            if (st != null && "up".equals(st.get("state"))) {
                impact.add("It already answers, so this will probably do nothing.");
            }
        } else if (a instanceof NodeAction n) {
            kind = "node";
            boolean off = n.command().equals("shutdown");
            title = (off ? "Shut down" : "Reboot") + " Proxmox host \"" + n.node() + "\"";
            impact.addAll(runningGuests(n.node(), off ? "will go down with the host" : "will be interrupted"));
            impact.add(off ? "The host will not come back until you wake it." : "Expect a few minutes of downtime.");
            word = confirm == Confirm.TYPE_NAME ? n.node() : null;
        } else {
            GuestAction g = (GuestAction) a;
            kind = "guest";
            GuestType type = GuestType.fromString(g.kind());
            GuestStatus now = current(g, type);
            if (!now.canToggle()) {
                throw new ActionException(HttpStatus.CONFLICT, "not_toggleable",
                        g.label() + " is " + now.apiStatus() + " right now. Wait for it to settle, then try again.");
            }
            boolean stopping = now == GuestStatus.RUNNING;
            desired = stopping ? "stopped" : "running";
            title = (stopping ? "Stop " : "Start ") + g.label() + " (" + g.kind().toUpperCase(Locale.ROOT) + " " + g.vmid() + ")";
            if (!stopping) {
                impact.add("Starts the " + g.kind() + ". It is stopped right now.");
            } else if (g.stopMode().equals("stop")) {
                impact.add("This is an immediate power-off, not a graceful shutdown. Anything running inside is cut off.");
            } else {
                impact.add("Asks the " + g.kind() + " to shut down gracefully.");
            }
            Confirm eff = confirm == Confirm.AUTO ? (stopping ? Confirm.TYPE_NAME : Confirm.CLICK) : confirm;
            confirm = eff;
            word = eff == Confirm.TYPE_NAME ? String.valueOf(g.vmid()) : null;
        }
        if (confirm == Confirm.AUTO) {
            confirm = Confirm.CLICK;
        }
        String token = tokens.issue(a.id(), sessionKey, desired, word);
        if (token == null) {
            throw new ActionException(HttpStatus.TOO_MANY_REQUESTS, "too_many_pending", "Too many confirmations are waiting. Try again in a moment.");
        }
        return new Prepared(token, Instant.now().plus(ConfirmationTokens.TTL), a.id(), title, kind,
                confirm.name().toLowerCase(Locale.ROOT).replace('_', '-'), word, impact, desired);
    }

    private List<String> runningGuests(String node, String consequence) {
        try {
            List<ProxmoxClient.GuestInfo> running = proxmox.listGuests(node).stream().filter(g -> g.status() == GuestStatus.RUNNING).toList();
            if (running.isEmpty()) {
                return List.of("No guests are running on this host.");
            }
            List<String> out = new ArrayList<>();
            out.add(running.size() + " guest" + (running.size() == 1 ? " is" : "s are") + " running and " + consequence + ":");
            running.forEach(g -> out.add("  " + g.name() + " (" + g.vmid() + ")"));
            return out;
        } catch (ProxmoxException e) {
            return List.of("Could not list the running guests (" + reason(e) + "). The command will still be sent if you confirm.");
        }
    }

    private GuestStatus current(GuestAction g, GuestType type) {
        try {
            return proxmox.guestStatus(g.node(), type, g.vmid());
        } catch (ProxmoxException e) {
            throw map(e);
        }
    }

    // ---------------------------------------------------------------- confirm

    public Executed confirm(String actionId, String token, String typed, String sessionKey, String ip, String userAgent) {
        ConfirmationTokens.Result r = tokens.consume(token, sessionKey, typed);
        switch (r.outcome()) {
            case OK -> { }
            case WRONG_ANSWER -> throw new ActionException(HttpStatus.UNPROCESSABLE_ENTITY, "typed_mismatch", "That is not the name shown. Type it exactly, or cancel.");
            case TOO_MANY_WRONG -> throw new ActionException(HttpStatus.TOO_MANY_REQUESTS, "too_many_wrong", "Too many wrong answers. Start again.");
            case WRONG_SESSION -> throw new ActionException(HttpStatus.FORBIDDEN, "forbidden", "This confirmation belongs to another session.");
            default -> throw new ActionException(HttpStatus.GONE, "token_expired", "This confirmation expired. Start again.");
        }
        ConfirmationTokens.Pending p = r.pending();
        if (!p.actionId().equals(actionId)) {
            throw new ActionException(HttpStatus.BAD_REQUEST, "wrong_action", "This confirmation is for a different action.");
        }
        ActionSpec a = store.state().config().actions().stream().filter(x -> x.id().equals(actionId)).findFirst()
                .orElseThrow(() -> new ActionException(HttpStatus.CONFLICT, "action_changed", "The configuration changed while you were confirming. Start again."));
        try {
            Executed result = execute(a, p);
            audit.record(AuditLog.Source.ui, a.id(), a.label(), AuditLog.Result.ok, result.message(), ip, userAgent);
            return result;
        } catch (ActionException e) {
            audit.record(AuditLog.Source.ui, a.id(), a.label(), e.status() == HttpStatus.CONFLICT ? AuditLog.Result.denied : AuditLog.Result.failed, e.getMessage(), ip, userAgent);
            throw e;
        }
    }

    private Executed execute(ActionSpec a, ConfirmationTokens.Pending p) {
        if (a instanceof WolAction w) {
            try {
                wol.send(MacAddress.parse(w.mac()));
            } catch (WakeOnLanException | IllegalArgumentException e) {
                throw new ActionException(HttpStatus.BAD_GATEWAY, "wol_failed", "Could not send the wake packet.");
            }
            return new Executed(true, "Wake packet sent to " + w.label() + ". The status light will show when it answers.", null);
        }
        if (a instanceof NodeAction n) {
            try (InFlightGuard.Handle lock = inFlight.tryAcquire("node:" + n.node())) {
                if (lock == null) {
                    throw new ActionException(HttpStatus.CONFLICT, "in_progress", "A power command for this host is already running.");
                }
                try {
                    proxmox.nodeCommand(n.node(), n.command());
                } catch (ProxmoxException e) {
                    throw map(e);
                }
                return new Executed(true, (n.command().equals("shutdown") ? "Shutdown" : "Reboot") + " command sent to " + n.node() + ".", null);
            }
        }
        GuestAction g = (GuestAction) a;
        GuestType type = GuestType.fromString(g.kind());
        try (InFlightGuard.Handle lock = inFlight.tryAcquire("guest:" + g.node() + ":" + type + ":" + g.vmid())) {
            if (lock == null) {
                throw new ActionException(HttpStatus.CONFLICT, "in_progress", "Another command for " + g.label() + " is still running.");
            }
            try {
                GuestStatus now = proxmox.guestStatus(g.node(), type, g.vmid());
                boolean wantRunning = "running".equals(p.desired());
                if ((wantRunning && now == GuestStatus.RUNNING) || (!wantRunning && now == GuestStatus.STOPPED)) {
                    return new Executed(true, g.label() + " is already " + (wantRunning ? "running" : "stopped") + ". Nothing to do.", null);
                }
                if (!now.canToggle()) {
                    throw new ActionException(HttpStatus.CONFLICT, "not_toggleable", g.label() + " is " + now.apiStatus() + " right now. Try again in a moment.");
                }
                String action = wantRunning ? "start" : g.stopMode();
                String upid = proxmox.guestAction(g.node(), type, g.vmid(), action);
                tasks.track(g.node(), upid, g.label());
                String verb = wantRunning ? "Starting" : (action.equals("stop") ? "Stopping (power-off)" : "Shutting down");
                return new Executed(true, verb + " " + g.label() + ".", upid.isEmpty() ? null : upid);
            } catch (ProxmoxException e) {
                throw map(e);
            }
        }
    }

    // ---------------------------------------------------------------- errors

    static String reason(ProxmoxException e) {
        return switch (e.kind()) {
            case UNREACHABLE -> "Proxmox did not answer";
            case UNAUTHORIZED -> "the API token was refused";
            case CERT_MISMATCH -> "the certificate changed";
            case NOT_CONFIGURED -> "Proxmox is not configured";
            default -> "unexpected answer";
        };
    }

    static ActionException map(ProxmoxException e) {
        return switch (e.kind()) {
            case UNAUTHORIZED -> new ActionException(HttpStatus.FORBIDDEN, "proxmox_unauthorized", "Proxmox refused the API token. Check its permissions.");
            case CERT_MISMATCH -> new ActionException(HttpStatus.BAD_GATEWAY, "cert_mismatch", "The Proxmox certificate changed. Update PROXMOX_TLS_FINGERPRINT if that is expected.");
            case NOT_CONFIGURED -> new ActionException(HttpStatus.SERVICE_UNAVAILABLE, "proxmox_not_configured", "Proxmox is not configured (PROXMOX_API_URL and PROXMOX_API_TOKEN).");
            case UNREACHABLE -> new ActionException(HttpStatus.BAD_GATEWAY, "proxmox_unreachable", "Proxmox did not answer. Is the host on?");
            default -> new ActionException(HttpStatus.BAD_GATEWAY, "proxmox_error", "Proxmox reported an error.");
        };
    }
}
