package com.homelabhub.docs;

import static com.homelabhub.docs.DocsClient.M;
import static com.homelabhub.docs.DocsClient.para;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The editor API over real HTTP (plan P3): login, CSRF, optimistic locking, the tree, Trash, history, uploads, preview. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocsApiTest {

    static final Path DIR = DocsClient.tempDir();
    static final String PASSWORD = "docs-test-password";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        DocsClient.useTemp(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
        r.add("docs.domains", () -> "example.test");
        r.add("docs.hub-url", () -> "https://pi.tailnet.ts.net");
        r.add("docs.max-asset-mb", () -> "1");
    }

    @LocalServerPort int port;
    DocsClient c;

    @BeforeEach
    void browser() throws Exception {
        c = new DocsClient(port).login(PASSWORD);
    }

    long create(Long parent, String title, String kind) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("title", title, "kind", kind));
        if (parent != null) {
            body.put("parentId", parent);
        }
        return c.json("POST", "/api/admin/docs/pages", body, 201).path("id").asLong();
    }

    long version(long id) throws Exception {
        return c.json("GET", "/api/admin/docs/pages/" + id, null, 200).path("version").asLong();
    }

    JsonNode save(long id, String title, String bodyJson, long version, int expect) throws Exception {
        return c.json("PUT", "/api/admin/docs/pages/" + id, "{\"title\":" + DocsClient.quote(title) + ",\"body\":" + bodyJson + ",\"version\":" + version + "}", expect);
    }

    @Test
    void everythingNeedsTheDocsSession() throws Exception {
        DocsClient anon = new DocsClient(port);
        assertThat(anon.call("GET", "/api/admin/docs/tree", null).statusCode()).isEqualTo(401);
        assertThat(anon.call("GET", "/api/admin/docs/tree", null).body()).isEqualTo("{\"error\":\"unauthenticated\"}");
        assertThat(c.cookie("DOCSSESSION")).isNotNull();
        assertThat(c.cookie("HUBSESSION")).isNull();
        assertThat(anon.call("GET", "/api/auth/status", null).body()).contains("\"authenticated\":false");
    }

    @Test
    void writesNeedTheCsrfToken() throws Exception {
        c.csrf = false;
        assertThat(c.call("POST", "/api/admin/docs/pages", Map.of("title", "x", "kind", "rich")).statusCode()).isEqualTo(403);
    }

    @Test
    void foreignHostIsRefusedWithTheDocsVariableName() throws Exception {
        String r = c.raw("GET /api/auth/status HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
        assertThat(r).startsWith("HTTP/1.1 400").contains("DOCS_ALLOWED_HOSTS");
    }

    @Test
    void headersOnNormalResponsesForbidFraming() throws Exception {
        HttpResponse<String> r = c.call("GET", "/api/admin/docs/tree", null);
        assertThat(r.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(r.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("frame-ancestors 'none'");
    }

    @Test
    void configExposesTheHubLinkAndDomains() throws Exception {
        JsonNode cfg = c.json("GET", "/api/admin/docs/config", null, 200);
        assertThat(cfg.path("hubUrl").asText()).isEqualTo("https://pi.tailnet.ts.net");
        assertThat(cfg.path("domains").get(0).asText()).isEqualTo("example.test");
    }

    @Test
    void treeCreateMoveAndCycles() throws Exception {
        long a = create(null, "Homelab", "rich");
        long b = create(a, "Networking", "rich");
        long d = create(b, "Tunnel", "rich");
        JsonNode tree = c.json("GET", "/api/admin/docs/tree", null, 200);
        assertThat(tree.findValues("title").stream().map(JsonNode::asText)).contains("Homelab", "Networking", "Tunnel");
        assertThat(c.json("GET", "/api/admin/docs/pages/" + d, null, 200).path("breadcrumbs").findValues("title").stream().map(JsonNode::asText))
                .containsExactly("Homelab", "Networking");
        HttpResponse<String> cycle = c.call("POST", "/api/admin/docs/pages/" + a + "/move", Map.of("parentId", d, "version", version(a)));
        assertThat(cycle.statusCode()).isEqualTo(400);
        assertThat(cycle.body()).contains("into itself");
        c.json("POST", "/api/admin/docs/pages/" + d + "/move", Map.of("version", version(d)), 200); // to the top level, first
        assertThat(c.json("GET", "/api/admin/docs/pages/" + d, null, 200).path("parentId").isNull()).isTrue();
        assertThat(c.json("GET", "/api/admin/docs/pages/" + d + "/versions", null, 200).get(0).path("kind").asText()).isEqualTo("moved");
    }

    @Test
    void saveUsesOptimisticLocking() throws Exception {
        long id = create(null, "Locked", "rich");
        long v = version(id);
        save(id, "Locked", para("one"), v, 200);
        JsonNode stale = save(id, "Locked", para("two"), v, 409);
        assertThat(stale.path("error").asText()).isEqualTo("version_conflict");
        assertThat(stale.path("currentVersion").asLong()).isEqualTo(v + 1);
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id, null, 200).at("/body/content/0/content/0/text").asText()).isEqualTo("one");
    }

    @Test
    void saveRefusesBadBodiesAndTitles() throws Exception {
        long id = create(null, "Bad", "rich");
        long v = version(id);
        assertThat(save(id, "Bad", "{\"type\":\"doc\",\"content\":[{\"type\":\"script\"}]}", v, 400).path("message").asText()).contains("Unsupported");
        assertThat(save(id, "", para("x"), v, 400).path("message").asText()).contains("title");
        assertThat(save(id, "x".repeat(201), para("x"), v, 400).path("message").asText()).contains("200");
        assertThat(c.call("POST", "/api/admin/docs/pages", Map.of("title", "x", "kind", "html")).statusCode()).isEqualTo(400);
    }

    @Test
    void historyAndRestoreIntoDraft() throws Exception {
        long id = create(null, "History", "rich");
        save(id, "History", para("first"), version(id), 200);
        save(id, "History", para("second"), version(id), 200);
        JsonNode versions = c.json("GET", "/api/admin/docs/pages/" + id + "/versions", null, 200);
        assertThat(versions).hasSize(3);
        int first = versions.get(1).path("version").asInt();
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id + "/versions/" + first, null, 200).at("/body/content/0/content/0/text").asText()).isEqualTo("first");
        c.json("POST", "/api/admin/docs/pages/" + id + "/restore/" + first, Map.of("version", version(id)), 200);
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id, null, 200).at("/body/content/0/content/0/text").asText()).isEqualTo("first");
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id + "/versions", null, 200).get(0).path("note").asText()).contains("restored from");
    }

    @Test
    void deleteWithChildrenAsksThenTrashRestores() throws Exception {
        long parent = create(null, "Parent", "rich");
        long child = create(parent, "Child", "rich");
        JsonNode ask = c.json("DELETE", "/api/admin/docs/pages/" + parent, null, 400);
        assertThat(ask.path("error").asText()).isEqualTo("children_choice");
        c.json("DELETE", "/api/admin/docs/pages/" + parent + "?children=delete", null, 204);
        c.json("GET", "/api/admin/docs/pages/" + child, null, 404);
        JsonNode trash = c.json("GET", "/api/admin/docs/trash", null, 200);
        assertThat(trash.findValues("title").stream().map(JsonNode::asText)).contains("Parent").doesNotContain("Child");
        c.json("POST", "/api/admin/docs/trash/" + parent + "/restore", null, 200);
        assertThat(c.json("GET", "/api/admin/docs/pages/" + child, null, 200).path("parentId").asLong()).isEqualTo(parent);

        long p2 = create(null, "Lifted parent", "rich");
        long c2 = create(p2, "Lifted child", "rich");
        c.json("DELETE", "/api/admin/docs/pages/" + p2 + "?children=lift", null, 204);
        assertThat(c.json("GET", "/api/admin/docs/pages/" + c2, null, 200).path("parentId").isNull()).isTrue();
    }

    @Test
    void duplicateCopiesTheDraftButNotTheHostname() throws Exception {
        long id = create(null, "Original", "rich");
        save(id, "Original", para("text"), version(id), 200);
        JsonNode copy = c.json("POST", "/api/admin/docs/pages/" + id + "/duplicate", null, 201);
        assertThat(copy.path("title").asText()).isEqualTo("Original (copy)");
        assertThat(copy.at("/body/content/0/content/0/text").asText()).isEqualTo("text");
        assertThat(copy.path("publicHost").isNull()).isTrue();
    }

    @Test
    void assetsAreSniffedLimitedAndServedSafely() throws Exception {
        HttpResponse<String> png = c.upload("/api/admin/docs/assets", "pic.jpg", DocsClient.PNG);
        assertThat(png.statusCode()).isEqualTo(201);
        JsonNode a = M.readTree(png.body());
        assertThat(a.path("mime").asText()).isEqualTo("image/png");
        HttpResponse<byte[]> got = c.getBytes("/api/admin/docs/assets/" + a.path("sha256").asText());
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(got.headers().firstValue("Content-Security-Policy").orElseThrow()).startsWith("sandbox");
        assertThat(got.headers().firstValue("X-Content-Type-Options")).contains("nosniff");

        assertThat(c.upload("/api/admin/docs/assets", "x.bin", new byte[] {0, 1, 2, 3}).statusCode()).isEqualTo(400);
        assertThat(c.upload("/api/admin/docs/assets", "big.txt", new byte[1024 * 1024 + 10]).statusCode()).isEqualTo(413);

        JsonNode html = M.readTree(c.upload("/api/admin/docs/assets", "evil.html", "<html><script>alert(1)</script>".getBytes()).body());
        assertThat(html.path("mime").asText()).isEqualTo("text/plain");
        assertThat(c.getBytes("/api/admin/docs/assets/" + html.path("sha256").asText()).headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment");
        assertThat(c.getBytes("/api/admin/docs/assets/" + "0".repeat(64)).statusCode()).isEqualTo(404);
    }

    @Test
    void webappBundlesPreviewAndRollback() throws Exception {
        long id = create(null, "Flow", "webapp");
        byte[] zip = BundleReaderTest.zip(BundleReaderTest.files("app/index.html", "<h1>v1</h1>", "app/data.json", "{\"a\":1}"));
        HttpResponse<String> up = c.upload("/api/admin/docs/pages/" + id + "/bundles", "app.zip", zip, "version", String.valueOf(version(id)));
        assertThat(up.statusCode()).as(up.body()).isEqualTo(201);
        HttpResponse<String> stale = c.upload("/api/admin/docs/pages/" + id + "/bundles", "index.html", "<h1>v2</h1>".getBytes(), "version", "0");
        assertThat(stale.statusCode()).isEqualTo(409);
        c.upload("/api/admin/docs/pages/" + id + "/bundles", "index.html", "<h1>v2</h1>".getBytes(), "version", String.valueOf(version(id)));
        JsonNode bundles = c.json("GET", "/api/admin/docs/pages/" + id + "/bundles", null, 200);
        assertThat(bundles).hasSize(2);
        assertThat(bundles.get(0).path("draft").asBoolean()).isTrue();

        HttpResponse<String> evil = c.upload("/api/admin/docs/pages/" + id + "/bundles", "evil.zip",
                BundleReaderTest.zip(BundleReaderTest.files("index.html", "x", "../x.js", "1")), "version", String.valueOf(version(id)));
        assertThat(evil.statusCode()).isEqualTo(400);

        JsonNode token = c.json("POST", "/api/admin/docs/pages/" + id + "/preview-token", Map.of("seq", 1), 200);
        DocsClient anon = new DocsClient(port); // the sandboxed frame has no cookie
        HttpResponse<String> page = anon.call("GET", token.path("url").asText(), null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("v1");
        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).startsWith("sandbox allow-scripts").contains("frame-ancestors 'self'").doesNotContain("frame-ancestors 'none'");
        assertThat(page.headers().allValues("Content-Security-Policy")).hasSize(1);
        assertThat(page.headers().firstValue("X-Frame-Options")).isEmpty();
        assertThat(anon.call("GET", token.path("base").asText() + "data.json", null).body()).contains("\"a\":1");
        assertThat(anon.call("GET", token.path("base").asText() + "_hub/hub-embed.js", null).body()).contains("hub:height");
        assertThat(anon.call("GET", token.path("base").asText() + "../../etc/passwd", null).statusCode()).isIn(400, 404);
        assertThat(anon.call("GET", token.path("base").asText() + "missing.js", null).statusCode()).isEqualTo(404);
        String forged = token.path("url").asText().replaceFirst("/docs-preview/[^/]+/", "/docs-preview/123.abc/");
        assertThat(anon.call("GET", forged, null).statusCode()).isEqualTo(404);

        c.json("POST", "/api/admin/docs/pages/" + id + "/bundles/1/use", Map.of("version", version(id)), 200);
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id, null, 200).path("draftBundle").path("seq").asInt()).isEqualTo(1);
    }

    @Test
    void hostCheckAndPublishWriteTheSnapshot() throws Exception {
        long id = create(null, "Public page", "rich");
        save(id, "Public page", para("hello public"), version(id), 200);
        assertThat(c.json("GET", "/api/admin/docs/hosts/check?host=Page1.Example.Test", null, 200).path("ok").asBoolean()).isTrue();
        assertThat(c.json("GET", "/api/admin/docs/hosts/check?host=page1.other.test", null, 200).path("ok").asBoolean()).isFalse();
        JsonNode pub = c.json("POST", "/api/admin/docs/pages/" + id + "/publish", Map.of("publicHost", "page1.example.test", "version", version(id)), 200);
        assertThat(pub.path("live").asBoolean()).isTrue();
        assertThat(Files.readString(DIR.resolve("snapshot/hosts.json"))).contains("page1.example.test");
        assertThat(Files.readString(DIR.resolve("snapshot/pages/" + id + ".json"))).contains("hello public");
        assertThat(c.json("GET", "/api/admin/docs/tree", null, 200).toString()).contains("\"state\":\"public\"");

        save(id, "Public page", para("draft only"), version(id), 200);
        assertThat(Files.readString(DIR.resolve("snapshot/pages/" + id + ".json"))).as("a save never changes the public page").contains("hello public");
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id, null, 200).path("state").asText()).isEqualTo("public-changed");

        long other = create(null, "Other", "rich");
        JsonNode taken = c.json("POST", "/api/admin/docs/pages/" + other + "/publish", Map.of("publicHost", "page1.example.test", "version", version(other)), 400);
        assertThat(taken.path("message").asText()).contains("already used");

        c.json("POST", "/api/admin/docs/pages/" + id + "/unpublish", Map.of("version", version(id)), 200);
        assertThat(Files.readString(DIR.resolve("snapshot/hosts.json"))).doesNotContain("page1.example.test");
        assertThat(c.json("GET", "/api/admin/docs/pages/" + id, null, 200).path("publicHost").asText()).as("kept reserved").isEqualTo("page1.example.test");
        assertThat(c.json("GET", "/api/admin/docs/publish-status", null, 200).path("upToDate").asBoolean()).isTrue();
    }
}
