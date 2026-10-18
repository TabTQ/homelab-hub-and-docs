package com.homelabhub.docspub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** docs-public over real HTTP with a hand-written snapshot (plan P5). Requests go over a socket so the Host header is ours. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocsPublicTest {

    static final Path DIR;
    static final String A1 = "a".repeat(64), B1 = "b".repeat(64), APP = "c".repeat(64), APPJS = "d".repeat(64), OTHER = "e".repeat(64);
    static final String EMBED = "f".repeat(64), EMBEDJS = "1".repeat(64);

    static {
        try {
            DIR = Files.createTempDirectory("docs-public-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("docs.snapshot.dir", DIR::toString);
    }

    static void blob(String sha, String content) throws Exception {
        Path p = DIR.resolve("blobs").resolve(sha.substring(0, 2)).resolve(sha);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @BeforeAll
    static void snapshot() throws Exception {
        Files.createDirectories(DIR.resolve("pages"));
        Files.writeString(DIR.resolve("hosts.json"), """
                {"schemaVersion":1,"hosts":{"t1.example.test":{"page":1},"app.example.test":{"page":2}}}""");
        Files.writeString(DIR.resolve("pages/1.json"), """
                {"schemaVersion":1,"kind":"rich","title":"Tunnel","updatedAt":"2026-10-14T18:02:00Z",
                 "body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"hello"}]},
                   {"type":"webappEmbed","attrs":{"bundle":"%s","height":300}}]},
                 "embeds":{"%s":{"settings":{"cdns":["unpkg"]},"bundle":{"sha":"%s","files":{"index.html":{"sha":"%s","mime":"text/html; charset=utf-8","size":5}}}}},
                 "assets":{"%s":{"mime":"image/png","size":3,"name":"a.png"},"%s":{"mime":"application/pdf","size":3,"name":"guide.pdf"}}}
                """.formatted(EMBED, EMBED, EMBED, EMBEDJS, A1, B1));
        Files.writeString(DIR.resolve("pages/2.json"), """
                {"schemaVersion":1,"kind":"webapp","title":"Flow","updatedAt":"2026-10-14T18:02:00Z","settings":{"display":"full","cdns":[]},
                 "bundle":{"sha":"%s","files":{"index.html":{"sha":"%s","mime":"text/html; charset=utf-8","size":5},"js/app.js":{"sha":"%s","mime":"text/javascript; charset=utf-8","size":1}}},
                 "embeds":{},"assets":{"%s":{"mime":"image/png","size":3,"name":"x.png"}}}
                """.formatted(APP, APPJS, APPJS, OTHER));
        blob(A1, "png");
        blob(B1, "pdf");
        blob(APPJS, "<p>app</p>");
        blob(OTHER, "png");
        blob(EMBEDJS, "<p>e</p>");
    }

    @LocalServerPort int port;

    String get(String host, String path) throws Exception {
        return get(host, path, "");
    }

    String get(String host, String path, String extra) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" + extra + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static int status(String response) {
        return Integer.parseInt(response.substring(9, 12));
    }

    static String header(String response, String name) {
        for (String line : response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n")) {
            if (line.toLowerCase().startsWith(name.toLowerCase() + ":")) {
                return line.substring(name.length() + 1).strip();
            }
        }
        return null;
    }

    static long count(String response, String name) {
        return java.util.Arrays.stream(response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n"))
                .filter(l -> l.toLowerCase().startsWith(name.toLowerCase() + ":")).count();
    }

    @Test
    void servesThePageForItsHost() throws Exception {
        String r = get("T1.Example.Test:443", "/api/public/page");
        assertThat(status(r)).isEqualTo(200);
        assertThat(r).contains("\"title\":\"Tunnel\"");
        assertThat(header(r, "X-Robots-Tag")).isEqualTo("noindex, nofollow");
        assertThat(header(r, "Content-Security-Policy")).contains("frame-ancestors 'none'").contains("form-action 'none'");
        String etag = header(r, "ETag");
        assertThat(status(get("t1.example.test", "/api/public/page", "If-None-Match: W/" + etag + "\r\n"))).isEqualTo(304);
    }

    @Test
    void unknownHostIsAnEmpty404OnEveryRoute() throws Exception {
        for (String path : new String[] {"/", "/api/public/page", "/a/" + A1, "/app/" + APP + "/index.html", "/assets/x.js"}) {
            String r = get("random.example.test", path);
            assertThat(status(r)).as(path).isEqualTo(404);
            assertThat(r.substring(r.indexOf("\r\n\r\n") + 4)).as(path).isEmpty();
        }
        assertThat(status(get("", "/api/public/page"))).isEqualTo(404);
    }

    @Test
    void routeSurfaceHasNoAdminAuthOrPortfolio() throws Exception {
        for (String path : new String[] {"/api/admin/docs/tree", "/api/auth/status", "/api/public/portfolio", "/api/admin/docs/pages/1",
                "/docs-preview/x/" + APP + "/index.html", "/pages/1.json", "/hosts.json", "/blobs/aa/" + A1, "/actuator/health"}) {
            assertThat(status(get("t1.example.test", path))).as(path).isEqualTo(404);
        }
    }

    @Test
    void assetsOnlyIfThePageListsThem() throws Exception {
        String png = get("t1.example.test", "/a/" + A1);
        assertThat(status(png)).isEqualTo(200);
        assertThat(header(png, "Content-Type")).isEqualTo("image/png");
        assertThat(header(png, "Content-Security-Policy")).startsWith("sandbox");
        assertThat(count(png, "Content-Security-Policy")).isEqualTo(1);
        assertThat(header(get("t1.example.test", "/a/" + B1), "Content-Disposition")).startsWith("attachment");
        assertThat(status(get("t1.example.test", "/a/" + OTHER))).as("another page's file").isEqualTo(404);
        assertThat(status(get("app.example.test", "/a/" + A1))).isEqualTo(404);
        assertThat(status(get("t1.example.test", "/a/..%2f..%2fhosts.json"))).isIn(400, 404);
    }

    @Test
    void bundleFilesThroughTheManifestOnly() throws Exception {
        String app = get("app.example.test", "/app/" + APP + "/index.html");
        assertThat(status(app)).isEqualTo(200);
        assertThat(header(app, "Content-Security-Policy")).startsWith("sandbox allow-scripts").contains("frame-ancestors 'self'");
        assertThat(count(app, "Content-Security-Policy")).isEqualTo(1);
        assertThat(header(app, "X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(status(get("app.example.test", "/app/" + APP + "/js/app.js"))).isEqualTo(200);
        assertThat(status(get("app.example.test", "/app/" + APP + "/js/other.js"))).as("outside the manifest").isEqualTo(404);
        assertThat(status(get("app.example.test", "/app/" + APP + "/../../hosts.json"))).isIn(400, 404);
        assertThat(get("app.example.test", "/app/" + APP + "/_hub/hub-embed.js")).contains("hub:height");
        assertThat(status(get("t1.example.test", "/app/" + APP + "/index.html"))).as("another page's bundle").isEqualTo(404);
        String embed = get("t1.example.test", "/app/" + EMBED + "/index.html");
        assertThat(status(embed)).as("an embedded bundle").isEqualTo(200);
        assertThat(header(embed, "Content-Security-Policy")).contains("https://unpkg.com");
    }

    @Test
    void robotsAndHealthAnswerOnAnyHost() throws Exception {
        assertThat(get("whatever", "/robots.txt")).contains("Disallow: /");
        assertThat(get("whatever", "/api/public/health")).contains("\"snapshot\":true");
    }
}
