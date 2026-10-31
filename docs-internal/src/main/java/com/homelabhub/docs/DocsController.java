package com.homelabhub.docs;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.homelabhub.docs.cloudflare.CloudflareRoutes;

/** The editor's API (plan 5.3). Login and CSRF apply in every auth mode except none (/api/admin/** is protected in edit mode too). */
@RestController
@RequestMapping("/api/admin/docs")
class DocsController {

    private final DocsService docs;
    private final DocsPublisher publisher;
    private final PreviewTokens tokens;
    private final BlobStore blobs;
    private final DocsAudit audit;
    private final DocsProperties props;
    private final CloudflareRoutes routes;

    DocsController(DocsService docs, DocsPublisher publisher, PreviewTokens tokens, BlobStore blobs, DocsAudit audit, DocsProperties props,
                   CloudflareRoutes routes) {
        this.docs = docs;
        this.publisher = publisher;
        this.tokens = tokens;
        this.blobs = blobs;
        this.audit = audit;
        this.props = props;
        this.routes = routes;
    }

    record CreateRequest(Long parentId, Long afterId, String kind, String title) {}

    record SaveRequest(String title, JsonNode body, Long version) {}

    record MoveRequest(Long parentId, Long afterId, Long version) {}

    record VersionOnly(Long version) {}

    record PublishRequest(String publicHost, Long version) {}

    record HostRequest(String publicHost, Long version) {}

    @GetMapping("/config")
    Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hubUrl", props.safeHubUrl());
        m.put("domains", props.domainList());
        m.put("maxAssetMb", props.maxAssetMb());
        m.put("maxBundleMb", props.maxBundleMb());
        m.put("cdns", WebappSettings.CDNS);
        return m;
    }

    @GetMapping("/tree")
    List<DocsService.TreeItem> tree() {
        return docs.tree();
    }

    @PostMapping("/pages")
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest r) {
        long id = docs.create(r.parentId(), r.afterId(), r.kind() == null ? "rich" : r.kind(), r.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(docs.get(id));
    }

    @GetMapping("/pages/{id}")
    Map<String, Object> get(@PathVariable long id) {
        return docs.get(id);
    }

    @PutMapping("/pages/{id}")
    Map<String, Object> save(@PathVariable long id, @RequestBody SaveRequest r) {
        return docs.save(id, r.title(), r.body(), r.version());
    }

    @PostMapping("/pages/{id}/move")
    Map<String, Object> move(@PathVariable long id, @RequestBody MoveRequest r) {
        return docs.move(id, r.parentId(), r.afterId(), r.version());
    }

    @PostMapping("/pages/{id}/duplicate")
    ResponseEntity<Map<String, Object>> duplicate(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(docs.get(docs.duplicate(id)));
    }

    @DeleteMapping("/pages/{id}")
    ResponseEntity<Void> delete(@PathVariable long id, @RequestParam(required = false) String children, HttpServletRequest req) {
        docs.delete(id, children);
        audit.record("delete", "page " + id, "ok", children == null ? null : "children=" + children, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trash")
    List<Map<String, Object>> trash() {
        return docs.trash();
    }

    @PostMapping("/trash/{id}/restore")
    ResponseEntity<Map<String, Object>> restoreFromTrash(@PathVariable long id) {
        docs.restoreFromTrash(id);
        return ResponseEntity.ok(docs.get(id));
    }

    @GetMapping("/pages/{id}/versions")
    List<Map<String, Object>> versions(@PathVariable long id) {
        return docs.versions(id);
    }

    @GetMapping("/pages/{id}/versions/{v}")
    Map<String, Object> version(@PathVariable long id, @PathVariable int v) {
        return docs.version(id, v);
    }

    @PostMapping("/pages/{id}/restore/{v}")
    Map<String, Object> restore(@PathVariable long id, @PathVariable int v, @RequestBody VersionOnly r) {
        return docs.restoreVersion(id, v, r.version());
    }

    @GetMapping("/pages/{id}/publish-check")
    Map<String, Object> publishCheck(@PathVariable long id) {
        return docs.publishCheck(id);
    }

    @PostMapping("/pages/{id}/publish")
    Map<String, Object> publish(@PathVariable long id, @RequestBody PublishRequest r, HttpServletRequest req) {
        return docs.publish(id, r.publicHost(), r.version(), req.getRemoteAddr(), req.getHeader("User-Agent"));
    }

    @PostMapping("/pages/{id}/unpublish")
    Map<String, Object> unpublish(@PathVariable long id, @RequestBody VersionOnly r, HttpServletRequest req) {
        Map<String, Object> out = docs.unpublish(id, r.version());
        audit.record("unpublish", "page " + id, "ok", null, req.getRemoteAddr(), req.getHeader("User-Agent"));
        return out;
    }

    @PutMapping("/pages/{id}/host")
    Map<String, Object> host(@PathVariable long id, @RequestBody HostRequest r, HttpServletRequest req) {
        Map<String, Object> out = docs.setHost(id, r.publicHost(), r.version());
        audit.record("host", "page " + id, "ok", String.valueOf(out.get("publicHost")), req.getRemoteAddr(), req.getHeader("User-Agent"));
        return out;
    }

    @GetMapping("/hosts/check")
    Map<String, Object> checkHost(@RequestParam String host, @RequestParam(required = false) Long pageId) {
        return docs.checkHost(host, pageId);
    }

    @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(docs.addAsset(in, file.getOriginalFilename()));
        }
    }

    /** Assets for the editor. SVG gets a sandbox CSP, anything that is not an image is a download. */
    @GetMapping("/assets/{sha}")
    ResponseEntity<FileSystemResource> asset(@PathVariable String sha) {
        if (!BlobStore.isSha(sha)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> a = docs.asset(sha).orElse(null);
        if (a == null || !Files.isRegularFile(blobs.path(sha))) {
            return ResponseEntity.notFound().build();
        }
        String mime = (String) a.get("mime");
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:");
        if (!MimeSniffer.isImage(mime)) {
            b.header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                    .filename((String) a.get("name"), java.nio.charset.StandardCharsets.UTF_8).build().toString());
        }
        return b.body(new FileSystemResource(blobs.path(sha)));
    }

    @PostMapping(value = "/pages/{id}/bundles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> uploadBundle(@PathVariable long id, @RequestParam("file") MultipartFile file,
                                                     @RequestParam("version") Long version) throws IOException {
        long max = (long) props.maxBundleMb() * 1024 * 1024;
        if (file.getSize() > max) {
            throw new BlobStore.TooLargeException("The upload is larger than " + props.maxBundleMb() + " MB.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(docs.uploadBundle(id, file.getBytes(), file.getOriginalFilename(), version));
    }

    @GetMapping("/pages/{id}/bundles")
    List<Map<String, Object>> bundles(@PathVariable long id) {
        return docs.bundles(id);
    }

    @PostMapping("/pages/{id}/bundles/{seq}/use")
    Map<String, Object> use(@PathVariable long id, @PathVariable int seq, @RequestBody VersionOnly r) {
        return docs.useBundle(id, seq, r.version());
    }

    record PreviewRequest(Integer seq) {}

    @PostMapping("/pages/{id}/preview-token")
    Map<String, Object> previewToken(@PathVariable long id, @RequestBody(required = false) PreviewRequest r) {
        DocsService.BundleRow b = docs.previewBundle(id, r == null ? null : r.seq());
        String token = tokens.mint(b.sha256());
        return Map.of("url", "/docs-preview/" + token + "/" + b.sha256() + "/index.html", "base", "/docs-preview/" + token + "/" + b.sha256() + "/",
                "settings", docs.settingsOfBundle(b), "seq", b.seq());
    }

    /** Cloudflare route state per public hostname (pending / live / error); empty and disabled unless DOCS_CF_AUTOMATION_ENABLED. */
    @GetMapping("/routes")
    Map<String, Object> routes() {
        return routes.overview();
    }

    @PostMapping("/routes/retry")
    Map<String, Object> retryRoutes() {
        routes.retry();
        return routes.overview();
    }

    @GetMapping("/publish-status")
    Map<String, Object> publishStatus() {
        DocsPublisher.Status s = publisher.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upToDate", s.upToDate());
        m.put("lastPublished", s.lastPublished() == null ? null : s.lastPublished().toString());
        m.put("lastError", s.lastError());
        return m;
    }
}
