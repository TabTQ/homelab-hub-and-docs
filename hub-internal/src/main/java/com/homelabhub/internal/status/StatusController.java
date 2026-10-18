package com.homelabhub.internal.status;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/status")
class StatusController {

    private final StatusService service;
    private final StatusStream stream;

    StatusController(StatusService service, StatusStream stream) {
        this.service = service;
        this.stream = stream;
    }

    @GetMapping
    Map<String, Object> snapshot() {
        return Map.of("items", service.snapshot(), "generatedAt", Instant.now().toString());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream() {
        SseEmitter e = stream.subscribe(Map.of("items", service.snapshot(), "generatedAt", Instant.now().toString()));
        if (e == null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(e);
    }

    @PostMapping("/refresh")
    ResponseEntity<Map<String, Object>> refresh() {
        boolean started = service.refreshNow();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.TOO_MANY_REQUESTS).body(Map.of("started", started));
    }
}
