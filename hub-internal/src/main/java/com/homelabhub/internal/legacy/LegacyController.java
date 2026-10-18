package com.homelabhub.internal.legacy;

import com.homelabhub.internal.audit.AuditLog;
import com.homelabhub.internal.audit.AuditLog.Result;
import com.homelabhub.internal.legacy.LegacyModels.*;
import com.homelabhub.internal.proxmox.GuestStatus;
import com.homelabhub.internal.proxmox.GuestType;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.proxmox.ProxmoxException;
import com.homelabhub.internal.wol.MacAddress;
import com.homelabhub.internal.wol.WakeOnLanException;
import com.homelabhub.internal.wol.WakeOnLanSender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The wol-server API, unchanged (plan 9.5, decision D15): same paths, methods, parameters and JSON shapes.
 * No login and no confirmation here, by decision. Guarded by CrossSiteGuardFilter, InFlightGuard and AuditLog.
 *
 * Deliberate differences from wol-server (all verified against recorded responses, see contract/legacy-fixtures):
 *  - unknown paths answer 404 instead of a generic 500;
 *  - a POST /toggle body accepts "lxc" as well as "LXC" (wol-server only accepted the upper-case form);
 *  - an unreadable body or a node name that is not a host name answers 400 instead of a generic 500;
 *  - a second command for the same target while one is running answers 409.
 */
@RestController
@ConditionalOnProperty(prefix = "hub.legacy", name = "enabled", havingValue = "true", matchIfMissing = true)
class LegacyController {

    private static final Pattern MAC_PATH = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");

    private final WakeOnLanSender wol;
    private final ProxmoxClient proxmox;
    private final AuditLog audit;
    private final InFlightGuard inFlight;

    LegacyController(WakeOnLanSender wol, ProxmoxClient proxmox, AuditLog audit, InFlightGuard inFlight) {
        this.wol = wol;
        this.proxmox = proxmox;
        this.audit = audit;
        this.inFlight = inFlight;
    }

    // ---------------------------------------------------------------- Wake-on-LAN

    @GetMapping("/wake/mac/{macAddress}")
    ResponseEntity<WolResponse> wakeGet(@PathVariable String macAddress,
                                        @RequestParam(required = false, defaultValue = "device") String target,
                                        HttpServletRequest req) {
        if (!MAC_PATH.matcher(macAddress).matches()) {
            return ResponseEntity.badRequest().body(new WolResponse(false,
                    "Invalid MAC address format. Expected: XX:XX:XX:XX:XX:XX or XX-XX-XX-XX-XX-XX"));
        }
        return wake(macAddress, target, req);
    }

    @PostMapping("/wake")
    ResponseEntity<WolResponse> wakePost(@RequestBody WakeRequest body, HttpServletRequest req) {
        return wake(body.macAddress(), body.target(), req);
    }

    private ResponseEntity<WolResponse> wake(String macText, String target, HttpServletRequest req) {
        String what = target + " (" + macText + ")";
        try {
            wol.send(MacAddress.parse(macText));
        } catch (IllegalArgumentException e) { // invalid MAC on the POST route: wol-server answered 500 with this wording
            audit(req, "wake", what, Result.failed, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new WolResponse(false, "Failed to send WOL packet: " + e.getMessage()));
        } catch (WakeOnLanException e) {
            String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            audit(req, "wake", what, Result.failed, cause);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new WolResponse(false,
                    "Failed to send WOL packet: Failed to send WOL packet to " + target + " (MAC: " + macText + "): " + cause));
        }
        audit(req, "wake", what, Result.ok, null);
        return ResponseEntity.ok(new WolResponse(true,
                "WOL packet sent successfully to " + target + " (MAC: " + macText + ")"));
    }

    // ---------------------------------------------------------------- Guest toggle

    @GetMapping("/toggle/{node}/{type}/{id}")
    ResponseEntity<ToggleResponse> toggleGet(@PathVariable String node, @PathVariable String type,
                                             @PathVariable Integer id, HttpServletRequest req) {
        GuestType guestType;
        try {
            guestType = GuestType.fromString(type);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ToggleResponse.failure("Invalid parameters", e.getMessage(), null));
        }
        return toggle(node, guestType, id, req);
    }

    @PostMapping("/toggle")
    ResponseEntity<ToggleResponse> togglePost(@Valid @RequestBody ToggleRequest body, HttpServletRequest req) {
        return toggle(body.node(), body.type(), body.id(), req);
    }

    private ResponseEntity<ToggleResponse> toggle(String node, GuestType type, int id, HttpServletRequest req) {
        String label = type.apiName().toUpperCase(Locale.ROOT) + id + " on node " + node;
        try (InFlightGuard.Handle lock = inFlight.tryAcquire("guest:" + node + ":" + type + ":" + id)) {
            if (lock == null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ToggleResponse.failure("Operation in progress",
                        "Failed to toggle " + label, "Another command for this guest is still running"));
            }
            try {
                GuestStatus before = proxmox.guestStatus(node, type, id);
                if (!before.canToggle()) {
                    throw new ProxmoxException(ProxmoxException.Kind.INVALID_STATE, "Cannot toggle " + type.apiName().toUpperCase(Locale.ROOT) + id
                            + " on node " + node + ": current status is '" + before.apiStatus() + "'. Can only toggle when 'running' or 'stopped'");
                }
                String action = before.toggleAction();
                proxmox.guestAction(node, type, id, action);
                GuestStatus predicted = action.equals("start") ? GuestStatus.RUNNING : GuestStatus.STOPPED;
                // "stoped" is wol-server's real wording (action + "ed"). Kept so the message is byte-identical.
                String message = type.apiName().toUpperCase(Locale.ROOT) + id + " on node " + node + " " + action + "ed successfully";
                audit(req, "toggle-" + action, label, Result.ok, null);
                return ResponseEntity.ok(ToggleResponse.ok(message, node, type, id, action, before, predicted));
            } catch (IllegalArgumentException e) {
                throw e; // e.g. a node name that is not a host name: 400 via the advice
            } catch (Exception e) {
                audit(req, "toggle", label, Result.failed, e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ToggleResponse.failure("Operation failed", "Failed to toggle " + label, e.getMessage()));
            }
        }
    }

    // ---------------------------------------------------------------- Node power

    @RequestMapping(value = "/node/{node}/shutdown", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<NodeResponse> shutdown(@PathVariable String node, HttpServletRequest req) {
        return nodeCommand(node, "shutdown", "Shutdown", req);
    }

    @RequestMapping(value = "/node/{node}/reboot", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<NodeResponse> reboot(@PathVariable String node, HttpServletRequest req) {
        return nodeCommand(node, "reboot", "Reboot", req);
    }

    private ResponseEntity<NodeResponse> nodeCommand(String node, String command, String noun, HttpServletRequest req) {
        try (InFlightGuard.Handle lock = inFlight.tryAcquire("node:" + node)) {
            if (lock == null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(new NodeResponse(false, "Operation in progress", node,
                        "Another power command for this node is still running", null));
            }
            try {
                proxmox.nodeCommand(node, command);
                audit(req, "node-" + command, node, Result.ok, null);
                return ResponseEntity.ok(new NodeResponse(true, noun + " command sent to node " + node, node, null, null));
            } catch (ProxmoxException e) {
                audit(req, "node-" + command, node, Result.failed, e.getMessage());
                HttpStatus status = e.getMessage().contains("permissions") ? HttpStatus.FORBIDDEN : HttpStatus.INTERNAL_SERVER_ERROR;
                return ResponseEntity.status(status).body(new NodeResponse(false, "Proxmox API Error", node, e.getMessage(), null));
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                audit(req, "node-" + command, node, Result.failed, e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new NodeResponse(false, "Internal Server Error", node, "An unexpected error occurred", e.getMessage()));
            }
        }
    }

    private void audit(HttpServletRequest req, String action, String target, Result result, String detail) {
        audit.record(AuditLog.Source.legacy, action, req.getMethod() + " " + target, result, detail,
                req.getRemoteAddr(), req.getHeader("User-Agent"));
    }
}
