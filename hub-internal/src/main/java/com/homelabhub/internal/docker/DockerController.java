package com.homelabhub.internal.docker;

import com.homelabhub.internal.config.DashboardConfig.DockerHostSpec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST side of the Docker page. Everything is under /api/admin so the existing security rules require the login (and CSRF
 * for writes); the feature itself refuses to run in login mode none. The browser only ever names a host by its id and a
 * container by its id: commands are built on the server from a fixed set.
 */
@RestController
@RequestMapping("/api/admin/docker")
class DockerController {

    private final DockerFeature feature;
    private final DockerService service;

    DockerController(DockerFeature feature, DockerService service) {
        this.feature = feature;
        this.service = service;
    }

    /** Same binding as the power actions: a confirmation token only works for the browser session that asked for it. */
    private static String sessionKey(HttpServletRequest req) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(req.getSession(true).getId().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @GetMapping("/config")
    Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", feature.enabled());
        // Only what the page shows. Key file names and pinned host keys stay on the server.
        m.put("hosts", feature.enabled() ? feature.hosts().stream().map(DockerController::view).toList() : List.of());
        m.put("maxLogLines", feature.props().maxLogLines());
        return m;
    }

    private static Map<String, Object> view(DockerHostSpec h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id());
        m.put("label", h.label());
        m.put("host", h.host());
        m.put("user", h.user());
        return m;
    }

    @GetMapping("/hosts/{hostId}/containers")
    Map<String, Object> containers(@PathVariable String hostId) {
        DockerHostSpec h = service.host(hostId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hostId", h.id());
        m.put("containers", service.containers(h).stream().map(c -> service.view(h, c)).toList());
        return m;
    }

    @GetMapping("/hosts/{hostId}/stats")
    Map<String, Object> stats(@PathVariable String hostId) {
        DockerHostSpec h = service.host(hostId);
        return Map.of("hostId", h.id(), "stats", service.stats(h));
    }

    @GetMapping("/hosts/{hostId}/networks")
    Map<String, Object> networks(@PathVariable String hostId) {
        DockerHostSpec h = service.host(hostId);
        return Map.of("networks", service.networks(h));
    }

    @GetMapping("/hosts/{hostId}/containers/{id}/logs")
    Map<String, Object> logs(@PathVariable String hostId, @PathVariable String id, @RequestParam(defaultValue = "200") int tail) {
        return service.logs(service.host(hostId), id, tail);
    }

    @GetMapping(path = "/hosts/{hostId}/containers/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter follow(@PathVariable String hostId, @PathVariable String id) {
        DockerHostSpec h;
        try {
            h = service.host(hostId);
            service.container(h, id);
            if (!service.tryStartFollow()) {
                throw new DockerException(HttpStatus.TOO_MANY_REQUESTS, "busy", "Already following " + DockerService.MAX_FOLLOWERS
                        + " logs. Close one first.");
            }
        } catch (DockerException e) {
            // An event stream cannot carry a JSON error response, so the error is the stream's only event.
            SseEmitter failed = new SseEmitter(5_000L);
            try {
                failed.send(SseEmitter.event().name("failed").data(Map.of("error", e.code(), "message", e.getMessage()), MediaType.APPLICATION_JSON));
                failed.complete();
            } catch (IOException | IllegalStateException ex) {
                failed.completeWithError(ex);
            }
            return failed;
        }
        SseEmitter emitter = new SseEmitter(feature.props().logFollowMaxSeconds() * 1000L + 30_000L);
        AtomicBoolean gone = new AtomicBoolean();
        emitter.onCompletion(() -> gone.set(true));
        emitter.onTimeout(() -> gone.set(true));
        emitter.onError(t -> gone.set(true));
        Thread t = new Thread(() -> {
            service.follow(h, id, lines -> {
                if (gone.get()) {
                    return false;
                }
                try {
                    if (lines.isEmpty()) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } else {
                        emitter.send(SseEmitter.event().name("lines").data(lines, MediaType.APPLICATION_JSON));
                    }
                    return true;
                } catch (IOException | IllegalStateException e) {
                    gone.set(true);
                    return false;
                }
            }, gone::get);
            if (!gone.get()) {
                try {
                    emitter.send(SseEmitter.event().name("end").data("{}", MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException | IllegalStateException e) {
                    // the browser left
                }
            }
        }, "docker-logs");
        t.setDaemon(true);
        t.start();
        return emitter;
    }

    // ---- changes ---------------------------------------------------------------------------------------------------

    @PostMapping("/hosts/{hostId}/containers/{id}/{op}/prepare")
    Map<String, Object> prepareContainer(@PathVariable String hostId, @PathVariable String id, @PathVariable String op,
                                         HttpServletRequest req) {
        return service.prepareContainer(service.host(hostId), id, op, sessionKey(req));
    }

    @PostMapping("/hosts/{hostId}/stacks/{name}/{op}/prepare")
    Map<String, Object> prepareStack(@PathVariable String hostId, @PathVariable String name, @PathVariable String op,
                                     @RequestParam(defaultValue = "false") boolean volumes, HttpServletRequest req) {
        return service.prepareStack(service.host(hostId), name, op, volumes, sessionKey(req));
    }

    @PostMapping("/hosts/{hostId}/run/prepare")
    Map<String, Object> prepareRun(@PathVariable String hostId, @RequestBody(required = false) DockerCommands.RunSpec spec,
                                   HttpServletRequest req) {
        DockerHostSpec h = service.host(hostId);
        if (spec == null || spec.image() == null) {
            throw DockerException.bad("An image is required.");
        }
        return service.prepareRun(h, spec, sessionKey(req));
    }

    @GetMapping("/hosts/{hostId}/cleanup/preview")
    DockerService.CleanupPreview cleanupPreview(@PathVariable String hostId) {
        return service.cleanupPreview(service.host(hostId));
    }

    record CleanupBody(List<String> images, List<String> networks) {}

    @PostMapping("/hosts/{hostId}/cleanup/prepare")
    Map<String, Object> prepareCleanup(@PathVariable String hostId, @RequestBody(required = false) CleanupBody body, HttpServletRequest req) {
        DockerHostSpec h = service.host(hostId);
        if (body == null) {
            throw DockerException.bad("Choose at least one image or network to remove.");
        }
        return service.prepareCleanup(h, body.images(), body.networks(), sessionKey(req));
    }

    record ConfirmBody(String token, String typed) {}

    @PostMapping("/confirm")
    Map<String, Object> confirm(@RequestBody(required = false) ConfirmBody body, HttpServletRequest req) {
        if (!feature.enabled()) {
            throw new DockerException(HttpStatus.SERVICE_UNAVAILABLE, "disabled", "The Docker page is turned off on this hub (HUB_DOCKER_ENABLED).");
        }
        return service.confirm(body == null ? null : body.token(), body == null ? null : body.typed(), sessionKey(req),
                req.getRemoteAddr(), req.getHeader("User-Agent"));
    }

    @GetMapping("/jobs/{id}")
    ResponseEntity<Map<String, Object>> job(@PathVariable String id) {
        return service.job(id).map(j -> ResponseEntity.ok(j.view())).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs")
    List<Map<String, Object>> jobs() {
        return service.recentJobs();
    }

    // ---- stacks ----------------------------------------------------------------------------------------------------

    @GetMapping("/hosts/{hostId}/stacks")
    Map<String, Object> stacks(@PathVariable String hostId) {
        DockerHostSpec h = service.host(hostId);
        return Map.of("hostId", h.id(), "stacks", service.stacks(h));
    }

    record StackBody(String name, String compose, String env) {}

    @PostMapping("/hosts/{hostId}/stacks")
    ResponseEntity<Map<String, Object>> createStack(@PathVariable String hostId, @RequestBody(required = false) StackBody body,
                                                    HttpServletRequest req) {
        DockerHostSpec h = service.host(hostId);
        if (body == null) {
            throw DockerException.bad("Nothing to save.");
        }
        Map<String, Object> r = service.saveStack(h, body.name(), body.compose(), body.env(), true, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return ResponseEntity.status(Boolean.TRUE.equals(r.get("ok")) ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }

    @GetMapping("/hosts/{hostId}/stacks/{name}")
    Map<String, Object> stack(@PathVariable String hostId, @PathVariable String name) {
        return service.readStack(service.host(hostId), name);
    }

    @PutMapping("/hosts/{hostId}/stacks/{name}")
    ResponseEntity<Map<String, Object>> saveStack(@PathVariable String hostId, @PathVariable String name,
                                                  @RequestBody(required = false) StackBody body, HttpServletRequest req) {
        DockerHostSpec h = service.host(hostId);
        if (body == null) {
            throw DockerException.bad("Nothing to save.");
        }
        Map<String, Object> r = service.saveStack(h, name, body.compose(), body.env(), false, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return ResponseEntity.status(Boolean.TRUE.equals(r.get("ok")) ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }

    @GetMapping("/hosts/{hostId}/stacks/{name}/versions")
    List<Map<String, Object>> versions(@PathVariable String hostId, @PathVariable String name) {
        return service.versions(service.host(hostId).id(), name);
    }

    @GetMapping("/audit")
    List<DockerAudit.Row> audit(@RequestParam(defaultValue = "20") int limit) {
        return service.recentAudit(limit);
    }

    @ExceptionHandler(DockerException.class)
    ResponseEntity<Map<String, String>> handle(DockerException e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", e.code(), "message", e.getMessage()));
    }
}
