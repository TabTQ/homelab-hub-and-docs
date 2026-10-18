package com.homelabhub.internal.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Browser editing of dashboard.yaml (plan 7.3). Under /api/admin so AUTH_MODE=edit protects it too. */
@RestController
@RequestMapping("/api/admin/config")
class ConfigController {

    record TextBody(String text) {}

    private final ConfigStore store;

    ConfigController(ConfigStore store) {
        this.store = store;
    }

    private static Map<String, Object> view(ConfigStore.State s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", s.text());
        m.put("sha256", s.sha256());
        m.put("health", s.health().name().toLowerCase());
        m.put("issues", s.issues());
        m.put("loadedAt", s.loadedAt().toString());
        return m;
    }

    private static String stripQuotes(String etag) {
        if (etag == null) {
            return null;
        }
        String t = etag.trim();
        if (t.startsWith("W/")) {
            t = t.substring(2);
        }
        return t.replace("\"", "");
    }

    @GetMapping
    ResponseEntity<Map<String, Object>> get() {
        ConfigStore.State s = store.state();
        return ResponseEntity.ok().eTag(s.sha256()).body(view(s));
    }

    @PostMapping("/validate")
    Map<String, Object> validate(@RequestBody TextBody body) {
        ConfigParser.Result r = store.validate(body.text());
        return Map.of("ok", r.ok(), "issues", r.issues());
    }

    @PutMapping
    ResponseEntity<?> put(@RequestBody TextBody body, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        if (ifMatch == null) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(Map.of("error", "if_match_required"));
        }
        return apply(() -> store.save(body.text(), stripQuotes(ifMatch), "ui", "saved in the browser"));
    }

    @GetMapping("/versions")
    List<ConfigStore.VersionInfo> versions() {
        return store.versions();
    }

    @GetMapping("/versions/{id}")
    ResponseEntity<?> version(@PathVariable long id) {
        return store.versionText(id).<ResponseEntity<?>>map(t -> ResponseEntity.ok(Map.of("id", id, "text", t)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found")));
    }

    @PostMapping("/versions/{id}/restore")
    ResponseEntity<?> restore(@PathVariable long id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        if (ifMatch == null) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(Map.of("error", "if_match_required"));
        }
        return apply(() -> store.restore(id, stripQuotes(ifMatch)));
    }

    private ResponseEntity<?> apply(java.util.function.Supplier<ConfigStore.State> action) {
        try {
            ConfigStore.State s = action.get();
            return ResponseEntity.ok().eTag(s.sha256()).body(view(s));
        } catch (ConfigStore.ConflictException e) {
            Map<String, Object> body = view(e.current());
            body.put("error", "conflict");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (ConfigStore.InvalidConfigException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid", "issues", e.issues()));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
    }
}
