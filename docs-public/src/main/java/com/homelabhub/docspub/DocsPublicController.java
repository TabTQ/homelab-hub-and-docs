package com.homelabhub.docspub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything a visitor of one page hostname can reach (plan 5.5): the page SPA, its JSON, the files that page lists, and
 * the files of its own or embedded webapp bundles, all looked up by hash through the page JSON. Nothing else: no list,
 * no search, no other page.
 */
@RestController
public class DocsPublicController {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SPA_ASSET = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    static final Map<String, String> CDNS = Map.of(
            "cdnjs", "https://cdnjs.cloudflare.com", "jsdelivr", "https://cdn.jsdelivr.net", "unpkg", "https://unpkg.com");

    private final DocsStore store;

    public DocsPublicController(DocsStore store) {
        this.store = store;
    }

    @GetMapping("/api/public/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "snapshot", store.hasSnapshot());
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain")
    public String robots() {
        return "User-agent: *\nDisallow: /\n";
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<Resource> index() {
        ClassPathResource r = new ClassPathResource("static/index.html");
        if (!r.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noCache()).body(r);
    }

    /** The SPA's own built files (hashed names from Vite). */
    @GetMapping("/assets/{name}")
    public ResponseEntity<Resource> spaAsset(@PathVariable String name) {
        if (!SPA_ASSET.matcher(name).matches() || name.startsWith(".")) {
            return ResponseEntity.notFound().build();
        }
        ClassPathResource r = new ClassPathResource("static/assets/" + name);
        if (!r.exists()) {
            return ResponseEntity.notFound().build();
        }
        String mime = name.endsWith(".js") ? "text/javascript; charset=utf-8" : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".woff2") ? "font/woff2" : name.endsWith(".woff") ? "font/woff" : name.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable()).body(r);
    }

    @GetMapping(value = "/api/public/page", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> page(@RequestAttribute(HostRouter.PAGE) DocsStore.Page page,
                                       @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        if (etagMatches(ifNoneMatch, page.etag())) {
            return ResponseEntity.status(304).eTag(page.etag()).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).eTag(page.etag())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=30").body(page.bytes());
    }

    /** A file the page lists in its assets, and nothing else. */
    @GetMapping("/a/{sha}")
    public ResponseEntity<Resource> asset(@RequestAttribute(HostRouter.PAGE) DocsStore.Page page, @PathVariable String sha) {
        JsonNode a = page.tree().path("assets").path(sha);
        if (!SHA.matcher(sha).matches() || !a.isObject()) {
            return ResponseEntity.notFound().build();
        }
        Path f = blob(sha);
        if (f == null) {
            return ResponseEntity.notFound().build();
        }
        String mime = a.path("mime").asText("application/octet-stream");
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
                .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:");
        if (!mime.startsWith("image/")) {
            b.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(a.path("name").asText("file"), StandardCharsets.UTF_8).build().toString());
        }
        return b.body(new FileSystemResource(f));
    }

    /** A file of this page's bundle or of a bundle it embeds, looked up through the manifest (never by joining paths). */
    @GetMapping("/app/{bundle}/**")
    public ResponseEntity<Resource> app(@RequestAttribute(HostRouter.PAGE) DocsStore.Page page, @PathVariable String bundle,
                                        HttpServletRequest req) throws IOException {
        if (!SHA.matcher(bundle).matches()) {
            return ResponseEntity.notFound().build();
        }
        JsonNode tree = page.tree();
        JsonNode b;
        JsonNode settings;
        if (bundle.equals(tree.path("bundle").path("sha").asText())) {
            b = tree.path("bundle");
            settings = tree.path("settings");
        } else if (tree.path("embeds").path(bundle).isObject()) {
            b = tree.path("embeds").path(bundle).path("bundle");
            settings = tree.path("embeds").path(bundle).path("settings");
        } else {
            return ResponseEntity.notFound().build();
        }
        String prefix = "/app/" + bundle + "/";
        String uri = req.getRequestURI();
        String path = uri.length() <= prefix.length() ? "index.html"
                : java.net.URLDecoder.decode(uri.substring(prefix.length()).replace("+", "%2B"), StandardCharsets.UTF_8);
        String csp = bundleCsp(settings);
        if (path.equals(HubEmbed.PATH)) {
            return serve(new ByteArrayResource(HubEmbed.SCRIPT.getBytes(StandardCharsets.UTF_8)), "text/javascript; charset=utf-8", csp);
        }
        JsonNode f = b.path("files").path(path);
        if (!f.isObject() || !SHA.matcher(f.path("sha").asText()).matches()) {
            return ResponseEntity.notFound().build();
        }
        Path file = blob(f.path("sha").asText());
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return serve(new FileSystemResource(file), f.path("mime").asText("application/octet-stream"), csp);
    }

    private static ResponseEntity<Resource> serve(Resource r, String mime, String csp) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .header("Content-Security-Policy", csp)
                // Our own page frames the app, so this route alone allows framing by the same origin.
                .header("X-Frame-Options", "SAMEORIGIN")
                .header("Access-Control-Allow-Origin", "*")
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(r);
    }

    /** Same policy as docs-internal's WebappSettings.bundleCsp (D10). */
    static String bundleCsp(JsonNode settings) {
        StringBuilder src = new StringBuilder("'self'");
        for (JsonNode c : settings.path("cdns")) {
            String origin = CDNS.get(c.asText());
            if (origin != null) {
                src.append(' ').append(origin);
            }
        }
        return "sandbox allow-scripts; default-src " + src + " data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src 'self'; "
                + "frame-ancestors 'self'; base-uri 'self'; form-action 'none'";
    }

    private Path blob(String sha) {
        Path f = store.blobs().resolve(sha.substring(0, 2)).resolve(sha);
        return Files.isRegularFile(f) ? f : null;
    }

    /** If-None-Match may be a list, may be weak, or may be "*". */
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
