package com.homelabhub.internal.actions;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ActionController {

    record ConfirmBody(String token, String typed) {}

    private final ActionService service;
    private final TaskTracker tasks;

    ActionController(ActionService service, TaskTracker tasks) {
        this.service = service;
        this.tasks = tasks;
    }

    /** The token is bound to the browser session, so it is useless to any other session or another site. */
    private static String sessionKey(HttpServletRequest req) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(req.getSession(true).getId().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @PostMapping("/api/actions/{id}/prepare")
    ActionService.Prepared prepare(@PathVariable String id, HttpServletRequest req) {
        return service.prepare(id, sessionKey(req));
    }

    @PostMapping("/api/actions/{id}/confirm")
    ActionService.Executed confirm(@PathVariable String id, @RequestBody ConfirmBody body, HttpServletRequest req) {
        return service.confirm(id, body == null ? null : body.token(), body == null ? null : body.typed(), sessionKey(req),
                req.getRemoteAddr(), req.getHeader("User-Agent"));
    }

    @GetMapping("/api/actions/tasks/{id:.+}")
    ResponseEntity<TaskTracker.Status> task(@PathVariable String id) {
        return tasks.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(ActionException.class)
    ResponseEntity<Map<String, String>> handle(ActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.code(), "message", e.getMessage()));
    }
}
