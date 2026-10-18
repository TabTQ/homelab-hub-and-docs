package com.homelabhub.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal webapp preview (D15): {@code /docs-preview/<token>/<bundleSha>/<path>}. The token, not the session, proves
 * access. Files are looked up in the bundle's manifest by path (never joined onto a directory) and served with the sandbox
 * CSP (D10), so even a file opened directly runs without access to Docs.
 */
@RestController
class PreviewController {

    private final DocsService docs;
    private final PreviewTokens tokens;
    private final BlobStore blobs;
    private final ObjectMapper json;

    PreviewController(DocsService docs, PreviewTokens tokens, BlobStore blobs, ObjectMapper json) {
        this.docs = docs;
        this.tokens = tokens;
        this.blobs = blobs;
        this.json = json;
    }

    @GetMapping("/docs-preview/{token}/{sha}/**")
    ResponseEntity<Resource> file(@PathVariable String token, @PathVariable String sha, HttpServletRequest req) throws IOException {
        if (!BlobStore.isSha(sha) || !tokens.valid(token, sha)) {
            return ResponseEntity.status(404).build();
        }
        String prefix = "/docs-preview/" + token + "/" + sha + "/";
        String uri = req.getRequestURI();
        if (!uri.startsWith(prefix)) {
            return ResponseEntity.status(404).build();
        }
        String path = java.net.URLDecoder.decode(uri.substring(prefix.length()).replace("+", "%2B"), StandardCharsets.UTF_8);
        if (path.isEmpty()) {
            path = "index.html";
        }
        DocsService.BundleRow b = docs.bundleBySha(sha).orElse(null);
        if (b == null) {
            return ResponseEntity.status(404).build();
        }
        String csp = WebappSettings.bundleCsp(docs.settingsOfBundle(b), "'self'");
        if (path.equals(HubEmbed.PATH)) {
            return serve(new ByteArrayResource(HubEmbed.SCRIPT.getBytes(StandardCharsets.UTF_8)), "text/javascript; charset=utf-8", csp);
        }
        for (JsonNode f : json.readTree(b.manifestJson())) {
            if (f.path("path").asText().equals(path)) {
                String fileSha = f.path("sha256").asText();
                if (!blobs.exists(fileSha)) {
                    return ResponseEntity.status(404).build();
                }
                return serve(new FileSystemResource(blobs.path(fileSha)), f.path("mime").asText(), csp);
            }
        }
        return ResponseEntity.status(404).build();
    }

    private static ResponseEntity<Resource> serve(Resource r, String mime, String csp) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .header("Content-Security-Policy", csp)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                // The app's own fetch() calls come from an opaque origin; its files are not secret to it.
                .header("Access-Control-Allow-Origin", "*")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(r);
    }
}
