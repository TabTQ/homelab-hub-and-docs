package com.homelabhub.docs.portfolio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Reading is under /api/portfolio, editing under /api/admin/portfolio (so AUTH_MODE=edit protects it). */
@RestController
class PortfolioController {

    private final PortfolioCrud crud;
    private final PortfolioReader reader;
    private final SnapshotPublisher publisher;

    private final String publicUrl;

    PortfolioController(PortfolioCrud crud, PortfolioReader reader, SnapshotPublisher publisher,
                        @org.springframework.beans.factory.annotation.Value("${docs.portfolio.public-url:}") String publicUrl) {
        this.publicUrl = safeUrl(publicUrl);
        this.crud = crud;
        this.reader = reader;
        this.publisher = publisher;
    }

    @GetMapping("/api/portfolio")
    Map<String, Object> portfolio() {
        return reader.read();
    }

    /** Where the public portfolio lives (shown as "Open public site"); empty unless it is a plain http(s) URL. */
    @GetMapping("/api/admin/portfolio/config")
    Map<String, Object> config() {
        return Map.of("publicUrl", publicUrl);
    }

    private static String safeUrl(String u) {
        String v = u == null ? "" : u.strip();
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("https://") || lower.startsWith("http://")) && !v.contains("@") ? v : "";
    }

    @GetMapping("/api/admin/portfolio/schema")
    List<Map<String, Object>> schema() {
        return crud.schema();
    }

    @GetMapping("/api/admin/portfolio/publish-status")
    SnapshotPublisher.Status publishStatus() {
        return publisher.status();
    }

    @PostMapping("/api/admin/portfolio/republish")
    ResponseEntity<SnapshotPublisher.Status> republish() {
        publisher.publish();
        return ResponseEntity.ok(publisher.status());
    }

    @GetMapping("/api/admin/portfolio/{res}")
    List<Map<String, Object>> list(@PathVariable String res) {
        return crud.list(crud.spec(res));
    }

    @GetMapping("/api/admin/portfolio/{res}/{id}")
    Map<String, Object> get(@PathVariable String res, @PathVariable long id) {
        return crud.get(crud.spec(res), id);
    }

    @GetMapping("/api/admin/portfolio/{res}/{id}/usage")
    Map<String, Long> usage(@PathVariable String res, @PathVariable long id) {
        return crud.usage(crud.spec(res), id);
    }

    @PostMapping("/api/admin/portfolio/{res}")
    ResponseEntity<Map<String, Object>> create(@PathVariable String res, @RequestBody Map<String, Object> body) {
        return published(HttpStatus.CREATED, crud.create(crud.spec(res), body));
    }

    /** PUT on the collection saves a singleton (the profile). */
    @PutMapping("/api/admin/portfolio/{res}")
    ResponseEntity<Map<String, Object>> saveSingleton(@PathVariable String res, @RequestBody Map<String, Object> body) {
        ResourceSpec s = crud.spec(res);
        if (!s.singleton()) {
            throw new NoSuchElementException("Not a single item");
        }
        return published(HttpStatus.OK, crud.saveSingleton(s, body));
    }

    @PutMapping("/api/admin/portfolio/{res}/{id}")
    ResponseEntity<Map<String, Object>> update(@PathVariable String res, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return published(HttpStatus.OK, crud.update(crud.spec(res), id, body));
    }

    @DeleteMapping("/api/admin/portfolio/{res}/{id}")
    ResponseEntity<Map<String, Object>> delete(@PathVariable String res, @PathVariable long id) {
        crud.delete(crud.spec(res), id);
        return published(HttpStatus.OK, Map.of("deleted", id));
    }

    @PutMapping("/api/admin/portfolio/{res}/{id}/skills")
    ResponseEntity<Map<String, Object>> skills(@PathVariable String res, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return published(HttpStatus.OK, crud.setSkills(crud.spec(res), id, body.get("skillIds")));
    }

    @PutMapping("/api/admin/portfolio/{res}/{id}/tags")
    ResponseEntity<Map<String, Object>> tags(@PathVariable String res, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return published(HttpStatus.OK, crud.setTags(crud.spec(res), id, body.get("tags")));
    }

    /** After every change the public snapshot is regenerated. If that fails the edit stands and the header says so. */
    private ResponseEntity<Map<String, Object>> published(HttpStatus status, Map<String, Object> body) {
        boolean ok = publisher.publish();
        return ResponseEntity.status(status).header("X-Public-Site", ok ? "ok" : "stale").body(body);
    }

    // ---------------------------------------------------------------- errors

    @ExceptionHandler(PortfolioCrud.ValidationException.class)
    ResponseEntity<Map<String, Object>> invalid(PortfolioCrud.ValidationException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid", "errors", e.errors()));
    }

    @ExceptionHandler(PortfolioCrud.ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(PortfolioCrud.ConflictException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "conflict");
        m.put("message", "Someone else changed this while you were editing it.");
        m.put("current", e.current());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, Object>> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", "The request body is not valid JSON."));
    }

    /**
     * sqlite-jdbc reports constraint violations with no SQL state, so Spring surfaces them as UncategorizedSQLException
     * instead of DataIntegrityViolationException. Both are handled here; anything that is not a constraint problem is
     * left alone (a 500), so real bugs are never hidden as "invalid input".
     */
    @ExceptionHandler({DataIntegrityViolationException.class, org.springframework.jdbc.UncategorizedSQLException.class})
    ResponseEntity<Map<String, Object>> integrity(org.springframework.dao.DataAccessException e, jakarta.servlet.http.HttpServletRequest req) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (!(msg.contains("constraint") || msg.contains("UNIQUE") || msg.contains("FOREIGN KEY"))) {
            throw e;
        }
        String field = "_";
        String text = "That conflicts with existing content.";
        if (msg.contains("UNIQUE")) {
            String[] parts = req.getRequestURI().split("/");
            ResourceSpec s = parts.length > 4 ? PortfolioResources.ALL.get(parts[4]) : null;
            field = s == null ? "_" : crud.uniqueColumnApi(s, msg).orElse("_");
            text = "That value is already used.";
        } else if (msg.contains("FOREIGN KEY")) {
            text = "That refers to something that does not exist.";
        }
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid", "errors", Map.of(field, text)));
    }
}
