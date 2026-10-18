package com.homelabhub.pub;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import com.homelabhub.pub.SnapshotStore.Snapshot;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicController {

    private static final byte[] UNAVAILABLE_BODY =
            "{\"error\":\"snapshot_unavailable\"}".getBytes(StandardCharsets.UTF_8);

    private final SnapshotStore snapshotStore;

    public PublicController(SnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    @GetMapping(value = "/api/public/portfolio", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> portfolio(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Optional<Snapshot> current = snapshotStore.current();
        if (current.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(UNAVAILABLE_BODY);
        }
        Snapshot snapshot = current.get();
        if (etagMatches(ifNoneMatch, snapshot.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(snapshot.etag())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(snapshot.etag())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=30")
                .body(snapshot.bytes());
    }

    @GetMapping("/api/public/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "snapshot", snapshotStore.current().isPresent());
    }

    /** If-None-Match may be a list, may be weak (proxies and compression weaken ETags), or may be "*". */
    static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.equals("*")) {
                return true;
            }
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
