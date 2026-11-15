package com.homelabhub.docs;

import static com.homelabhub.docs.DocsClient.M;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.homelabhub.docs.github.FakeGitHub;
import com.homelabhub.docs.github.GitHubSync;
import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * GitHub Pages publishing end to end against a fake GitHub: the tree written for each kind of page, links both ways, the API for
 * publish, targets and unpublish, a GitHub failure that must not touch the local publish, and the portfolio deploy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitHubPublishingTest {

    static final Path DIR = DocsClient.tempDir();
    static final String PASSWORD = "docs-test-password";
    static final FakeGitHub GH = start();
    static final String SITE = "https://octo.github.io/showcase/";

    static FakeGitHub start() {
        try {
            return new FakeGitHub();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        DocsClient.useTemp(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
        r.add("docs.domains", () -> "example.test");
        r.add("docs.github.enabled", () -> "true");
        r.add("docs.github.repo", () -> "octo/showcase");
        r.add("docs.github.branch", () -> "gh-pages");
        r.add("docs.github.token", () -> FakeGitHub.TOKEN);
        r.add("docs.github.api-url", GH::url);
    }

    @AfterAll
    static void stop() {
        GH.close();
    }

    @LocalServerPort int port;
    @Autowired DocsService docs;
    @Autowired GitHubSync github;
    DocsClient c;

    @BeforeEach
    void browser() throws Exception {
        c = new DocsClient(port).login(PASSWORD);
        GH.failPrefix = null;
    }

    // ---------------------------------------------------------------- helpers

    long version(long id) {
        return ((Number) docs.get(id).get("version")).longValue();
    }

    void save(long id, String title, String json) throws Exception {
        docs.save(id, title, M.readTree(json), version(id));
    }

    void publishTo(long id, String host, String slug) {
        docs.publish(id, DocsService.Target.of(host), DocsService.Target.of(slug), version(id), "127.0.0.1", "test");
    }

    /** Waits until the sync run ended and the fake GitHub's branch holds a tree that satisfies {@code ok}. */
    void awaitTree(Predicate<Map<String, byte[]>> ok) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(github.status().state()).isIn(GitHubSync.State.LIVE, GitHubSync.State.ERROR);
            assertThat(ok.test(GH.files())).as("tree: " + GH.files().keySet()).isTrue();
        });
        assertThat(github.status().state()).isEqualTo(GitHubSync.State.LIVE);
    }

    void awaitFile(String path) {
        awaitTree(f -> f.containsKey(path));
    }

    void awaitGone(String path) {
        awaitTree(f -> !f.containsKey(path));
    }

    JsonNode json(String path) throws Exception {
        return M.readTree(GH.text(path));
    }

    JsonNode localPage(long id) throws Exception {
        return M.readTree(Files.readString(DIR.resolve("snapshot/pages/" + id + ".json")));
    }

    JsonNode localHosts() throws Exception {
        return M.readTree(Files.readString(DIR.resolve("snapshot/hosts.json"))).path("hosts");
    }

    long create(String title, String kind) throws Exception {
        return c.json("POST", "/api/admin/docs/pages", Map.of("title", title, "kind", kind), 201).path("id").asLong();
    }

    JsonNode page(long id) throws Exception {
        return c.json("GET", "/api/admin/docs/pages/" + id, null, 200);
    }

    // ---------------------------------------------------------------- the tree

    @Test
    void aRichPageWithAnImageAWebappAndAnEmbedAreWrittenToTheirFolders() throws Exception {
        String img = docs.addAsset(new ByteArrayInputStream(DocsClient.PNG), "net.png").get("sha256").toString();
        long app = docs.create(null, null, "webapp", "Tiny app");
        docs.uploadBundle(app, BundleReaderTest.zip(BundleReaderTest.files("index.html", "<p>app</p>", "x.js", "1")), "a.zip", version(app));
        long rich = docs.create(null, null, "rich", "Notes & more");
        save(rich, "Notes & more", """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"hello"}]},
                  {"type":"image","attrs":{"sha":"%s","alt":"pic"}},
                  {"type":"webappEmbed","attrs":{"pageId":%d,"height":300}}]}""".formatted(img, app));
        publishTo(rich, null, "tree-notes");
        publishTo(app, null, "tree-app");
        awaitFile("tree-app/page.json");
        awaitFile("tree-notes/page.json");

        Map<String, byte[]> files = GH.files();
        assertThat(files).containsKeys(".nojekyll", "viewer/assets/app.js", "viewer/assets/app.css");
        assertThat(files).doesNotContainKeys("index.html", "viewer/index.html");
        assertThat(files.get(".nojekyll")).isEmpty();

        String shell = GH.text("tree-notes/index.html");
        assertThat(shell).contains("<meta name=\"static-data\" content=\"page.json\">").contains("<meta name=\"robots\" content=\"noindex\">")
                .contains("../viewer/assets/app.js").contains("<title>Notes &amp; more</title>").doesNotContain("\"./assets/");

        JsonNode p = json("tree-notes/page.json");
        assertThat(p.path("title").asText()).isEqualTo("Notes & more");
        assertThat(p.at("/assets/" + img + "/file").asText()).isEqualTo("a/" + img + ".png");
        assertThat(files.get("tree-notes/a/" + img + ".png")).isEqualTo(DocsClient.PNG);
        String embed = p.at("/body/content/2/attrs/bundle").asText();
        assertThat(embed).hasSize(64);
        assertThat(p.at("/embeds/" + embed + "/bundle/files/index.html/sha").asText()).hasSize(64);
        assertThat(GH.text("tree-notes/app/" + embed + "/index.html")).isEqualTo("<p>app</p>");
        assertThat(GH.text("tree-notes/app/" + embed + "/x.js")).isEqualTo("1");
        assertThat(GH.text("tree-notes/app/" + embed + "/" + HubEmbed.PATH)).isEqualTo(HubEmbed.SCRIPT);

        JsonNode a = json("tree-app/page.json");
        assertThat(a.path("kind").asText()).isEqualTo("webapp");
        String bundle = a.at("/bundle/sha").asText();
        assertThat(bundle).isEqualTo(embed);
        assertThat(GH.text("tree-app/app/" + bundle + "/x.js")).isEqualTo("1");
        assertThat(GH.text("tree-app/index.html")).contains("<title>Tiny app</title>");
        assertThat(GH.files().keySet().stream().filter(k -> k.startsWith("tree-"))).allMatch(k -> k.matches("tree-(notes|app)/.*"));
        assertThat(GH.badAuth).isEmpty();
        assertThat(GH.commitParents).as("every commit is an orphan").allMatch(n -> n == 0);

        // The local snapshot is untouched by GitHub-only pages.
        assertThat(Files.exists(DIR.resolve("snapshot/pages/" + rich + ".json"))).isFalse();
        assertThat(Files.exists(DIR.resolve("snapshot/pages/" + app + ".json"))).isFalse();
    }

    // ---------------------------------------------------------------- links

    @Test
    void linksResolveForEachPlaceTheMirrorWay() throws Exception {
        long onlyGh = docs.create(null, null, "rich", "Only GitHub");
        long onlyLocal = docs.create(null, null, "rich", "Only local");
        long both = docs.create(null, null, "rich", "Both");
        long draft = docs.create(null, null, "rich", "Draft");
        long linker = docs.create(null, null, "rich", "Linker");
        save(linker, "Linker", """
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"pageLink","attrs":{"pageId":%d,"label":"gh"}},{"type":"pageLink","attrs":{"pageId":%d,"label":"local"}},
                  {"type":"pageLink","attrs":{"pageId":%d,"label":"both"}},{"type":"pageLink","attrs":{"pageId":%d,"label":"draft"}}]}]}"""
                .formatted(onlyGh, onlyLocal, both, draft));
        publishTo(onlyGh, null, "link-gh");
        publishTo(onlyLocal, "link-local.example.test", null);
        publishTo(both, "link-both.example.test", "link-both");
        publishTo(linker, "link-linker.example.test", "link-linker");
        awaitFile("link-linker/page.json");

        // GitHub copy: GitHub first, else the local address, else plain text.
        JsonNode gh = json("link-linker/page.json").at("/body/content/0/content");
        assertThat(gh.get(0).at("/attrs/href").asText()).isEqualTo(SITE + "link-gh/");
        assertThat(gh.get(1).at("/attrs/href").asText()).isEqualTo("https://link-local.example.test/");
        assertThat(gh.get(2).at("/attrs/href").asText()).isEqualTo(SITE + "link-both/");
        assertThat(gh.get(3).path("attrs").has("href")).isFalse();

        // Local copy: local first, else GitHub, else plain text.
        JsonNode local = localPage(linker).at("/body/content/0/content");
        assertThat(local.get(0).at("/attrs/href").asText()).isEqualTo(SITE + "link-gh/");
        assertThat(local.get(1).at("/attrs/href").asText()).isEqualTo("https://link-local.example.test/");
        assertThat(local.get(2).at("/attrs/href").asText()).isEqualTo("https://link-both.example.test/");
        assertThat(local.get(3).path("attrs").has("href")).isFalse();
        assertThat(localHosts().has("link-linker.example.test")).isTrue();
        assertThat(Files.exists(DIR.resolve("snapshot/pages/" + onlyGh + ".json"))).as("not local").isFalse();

        // The pre-publish check tells where the GitHub copy points and warns about local-only targets.
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode l : c.json("GET", "/api/admin/docs/pages/" + linker + "/publish-check", null, 200).path("links")) {
            byId.put(l.path("pageId").asLong(), l);
        }
        assertThat(byId.get(onlyGh).path("githubHref").asText()).isEqualTo(SITE + "link-gh/");
        assertThat(byId.get(onlyGh).path("githubLocalOnly").asBoolean()).isFalse();
        assertThat(byId.get(onlyGh).path("href").asText()).as("the local copy points at GitHub").isEqualTo(SITE + "link-gh/");
        assertThat(byId.get(onlyLocal).path("githubHref").asText()).isEqualTo("https://link-local.example.test/");
        assertThat(byId.get(onlyLocal).path("githubLocalOnly").asBoolean()).isTrue();
        assertThat(byId.get(both).path("githubHref").asText()).isEqualTo(SITE + "link-both/");
        assertThat(byId.get(both).path("githubLocalOnly").asBoolean()).isFalse();
        assertThat(byId.get(draft).path("githubHref").isNull()).isTrue();
        assertThat(byId.get(draft).path("githubLocalOnly").asBoolean()).isFalse();
        assertThat(byId.get(draft).path("public").asBoolean()).isFalse();
        assertThat(c.json("GET", "/api/admin/docs/pages/" + linker + "/publish-check", null, 200).path("githubSlug").asText()).isEqualTo("link-linker");

        // Taking the GitHub-only page off GitHub re-renders both copies of the linker.
        docs.unpublish(onlyGh, version(onlyGh));
        awaitTree(f -> !f.containsKey("link-gh/page.json") && !new String(f.get("link-linker/page.json"), StandardCharsets.UTF_8).contains("link-gh/"));
        assertThat(localPage(linker).at("/body/content/0/content/0/attrs").has("href")).isFalse();
    }

    // ---------------------------------------------------------------- API

    @Test
    void configAndTreeShowTheGithubState() throws Exception {
        JsonNode cfg = c.json("GET", "/api/admin/docs/config", null, 200).path("github");
        assertThat(cfg.path("enabled").asBoolean()).isTrue();
        assertThat(cfg.path("siteUrl").asText()).isEqualTo(SITE);
        assertThat(cfg.path("dryRun").asBoolean()).isFalse();
        long id = create("Tree badge", "rich");
        assertThat(page(id).path("githubSlug").isNull()).isTrue();
        publishTo(id, "badge.example.test", "api-badge");
        boolean found = false;
        for (JsonNode t : c.json("GET", "/api/admin/docs/tree", null, 200)) {
            if (t.path("id").asLong() == id) {
                found = true;
                assertThat(t.path("githubSlug").asText()).isEqualTo("api-badge");
                assertThat(t.path("publicHost").asText()).isEqualTo("badge.example.test");
                assertThat(t.path("state").asText()).isEqualTo("public");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void publishLocalOnlyGithubOnlyAndBoth() throws Exception {
        long id = create("Three ways", "rich");

        // Old client: only publicHost, no githubSlug key.
        JsonNode local = c.json("POST", "/api/admin/docs/pages/" + id + "/publish", Map.of("publicHost", "three.example.test", "version", version(id)), 200);
        assertThat(local.path("publicHost").asText()).isEqualTo("three.example.test");
        assertThat(local.path("githubUrl").isNull()).isTrue();
        assertThat(local.path("github").path("state").asText()).isIn("pending", "building", "live");
        assertThat(local.path("live").asBoolean()).isTrue();
        assertThat(page(id).path("state").asText()).isEqualTo("public");
        assertThat(localHosts().has("three.example.test")).isTrue();

        // GitHub only: the local address is switched off in the same save.
        Map<String, Object> ghOnly = new HashMap<>();
        ghOnly.put("publicHost", null);
        ghOnly.put("githubSlug", "three-ways");
        ghOnly.put("version", version(id));
        JsonNode gh = c.json("POST", "/api/admin/docs/pages/" + id + "/publish", ghOnly, 200);
        assertThat(gh.path("publicHost").isNull()).isTrue();
        assertThat(gh.path("githubUrl").asText()).isEqualTo(SITE + "three-ways/");
        assertThat(localHosts().has("three.example.test")).isFalse();
        awaitFile("three-ways/page.json");
        JsonNode detail = page(id);
        assertThat(detail.path("state").asText()).isEqualTo("public");
        assertThat(detail.path("githubUrl").asText()).isEqualTo(SITE + "three-ways/");
        assertThat(detail.path("publicHost").isNull()).isTrue();

        // Both.
        JsonNode both = c.json("POST", "/api/admin/docs/pages/" + id + "/publish",
                Map.of("publicHost", "three.example.test", "githubSlug", "three-ways", "version", version(id)), 200);
        assertThat(both.path("publicHost").asText()).isEqualTo("three.example.test");
        assertThat(both.path("githubSlug").asText()).isEqualTo("three-ways");
        assertThat(localHosts().has("three.example.test")).isTrue();

        // An old client that leaves githubSlug out keeps the folder.
        JsonNode kept = c.json("POST", "/api/admin/docs/pages/" + id + "/publish", Map.of("publicHost", "three.example.test", "version", version(id)), 200);
        assertThat(kept.path("githubSlug").asText()).isEqualTo("three-ways");

        // Neither place: refused.
        Map<String, Object> none = new HashMap<>();
        none.put("publicHost", null);
        none.put("githubSlug", null);
        none.put("version", version(id));
        assertThat(c.json("POST", "/api/admin/docs/pages/" + id + "/publish", none, 400).path("message").asText()).contains("Choose where to publish");
    }

    @Test
    void folderNamesAreCheckedAndUnique() throws Exception {
        long a = create("Name A", "rich");
        long b = create("Name B", "rich");
        publishTo(a, null, "taken-name");
        assertThat(c.json("GET", "/api/admin/docs/github/check?slug=taken-name&pageId=" + a, null, 200).path("ok").asBoolean()).isTrue();
        JsonNode other = c.json("GET", "/api/admin/docs/github/check?slug=Taken-Name&pageId=" + b, null, 200);
        assertThat(other.path("ok").asBoolean()).isFalse();
        assertThat(other.path("message").asText()).contains("already used").contains("Name A");
        JsonNode free = c.json("GET", "/api/admin/docs/github/check?slug=free-name", null, 200);
        assertThat(free.path("ok").asBoolean()).isTrue();
        assertThat(free.path("message").asText()).isEqualTo(SITE + "free-name/ is free.");
        for (String bad : new String[] {"viewer", "portfolio", "a", "has%20space", "under_score", "-x"}) {
            assertThat(c.json("GET", "/api/admin/docs/github/check?slug=" + bad, null, 200).path("ok").asBoolean()).as(bad).isFalse();
        }
        Map<String, Object> req = new HashMap<>();
        req.put("publicHost", null);
        req.put("githubSlug", "taken-name");
        req.put("version", version(b));
        assertThat(c.json("POST", "/api/admin/docs/pages/" + b + "/publish", req, 400).path("message").asText()).contains("already used");
        req.put("githubSlug", "viewer");
        assertThat(c.json("POST", "/api/admin/docs/pages/" + b + "/publish", req, 400).path("message").asText()).contains("used by the site itself");
    }

    @Test
    void targetsChangeWhereTheVersionIsShownWithoutANewVersion() throws Exception {
        long id = create("Moves", "rich");
        publishTo(id, "moves.example.test", null);
        int published = page(id).path("publishedVersion").asInt();

        // Add GitHub.
        JsonNode r = c.json("PUT", "/api/admin/docs/pages/" + id + "/targets",
                Map.of("publicHost", "moves.example.test", "githubSlug", "moves", "version", version(id)), 200);
        assertThat(r.path("githubUrl").asText()).isEqualTo(SITE + "moves/");
        assertThat(page(id).path("publishedVersion").asInt()).as("no new published version").isEqualTo(published);
        awaitFile("moves/page.json");
        assertThat(localHosts().has("moves.example.test")).isTrue();

        // Move to GitHub only.
        Map<String, Object> body = new HashMap<>();
        body.put("publicHost", null);
        body.put("githubSlug", "moves");
        body.put("version", version(id));
        c.json("PUT", "/api/admin/docs/pages/" + id + "/targets", body, 200);
        assertThat(localHosts().has("moves.example.test")).isFalse();
        assertThat(page(id).path("state").asText()).isEqualTo("public");
        assertThat(page(id).path("publicHost").isNull()).isTrue();
        JsonNode history = c.json("GET", "/api/admin/docs/pages/" + id + "/versions", null, 200);
        assertThat(history.get(0).path("kind").asText()).isEqualTo("host");
        assertThat(history.get(0).path("note").asText()).contains("hostname removed").contains("GitHub folder moves");

        // A missing key leaves that place alone.
        c.json("PUT", "/api/admin/docs/pages/" + id + "/targets", Map.of("publicHost", "moves.example.test", "version", version(id)), 200);
        assertThat(page(id).path("githubSlug").asText()).isEqualTo("moves");

        // Both null on a published page unpublishes it.
        Map<String, Object> off = new HashMap<>();
        off.put("publicHost", null);
        off.put("githubSlug", null);
        off.put("version", version(id));
        c.json("PUT", "/api/admin/docs/pages/" + id + "/targets", off, 200);
        JsonNode after = page(id);
        assertThat(after.path("state").asText()).isEqualTo("internal");
        assertThat(after.path("publishedVersion").isNull()).isTrue();
        assertThat(after.path("githubSlug").isNull()).isTrue();
        awaitGone("moves/page.json");
        assertThat(localHosts().has("moves.example.test")).isFalse();

        // On a page that was never published, the places are only reserved.
        long draft = create("Reserve", "rich");
        c.json("PUT", "/api/admin/docs/pages/" + draft + "/targets", Map.of("publicHost", "reserve.example.test", "githubSlug", "reserve", "version", version(draft)), 200);
        assertThat(page(draft).path("state").asText()).isEqualTo("internal");
        assertThat(page(draft).path("githubSlug").asText()).isEqualTo("reserve");
        assertThat(page(draft).path("githubUrl").isNull()).as("not live").isTrue();
        assertThat(localHosts().has("reserve.example.test")).isFalse();
    }

    @Test
    void unpublishTakesThePageOffBothPlacesAndKeepsTheNamesReserved() throws Exception {
        long id = create("Both off", "rich");
        publishTo(id, "bothoff.example.test", "both-off");
        awaitFile("both-off/page.json");
        c.json("POST", "/api/admin/docs/pages/" + id + "/unpublish", Map.of("version", version(id)), 200);
        awaitGone("both-off/page.json");
        JsonNode d = page(id);
        assertThat(d.path("state").asText()).isEqualTo("internal");
        assertThat(d.path("publicHost").asText()).isEqualTo("bothoff.example.test");
        assertThat(d.path("githubSlug").asText()).isEqualTo("both-off");
        assertThat(d.path("githubUrl").isNull()).isTrue();
        assertThat(localHosts().has("bothoff.example.test")).isFalse();
        // Still reserved for this page: another page cannot take them.
        long other = create("Wants the names", "rich");
        Map<String, Object> req = new HashMap<>();
        req.put("publicHost", null);
        req.put("githubSlug", "both-off");
        req.put("version", version(other));
        c.json("POST", "/api/admin/docs/pages/" + other + "/publish", req, 400);
        // Publishing again with nothing mentioned brings both places back.
        c.json("POST", "/api/admin/docs/pages/" + id + "/publish", Map.of("version", version(id)), 200);
        assertThat(localHosts().has("bothoff.example.test")).isTrue();
        awaitFile("both-off/page.json");
    }

    @Test
    void aGithubFailureNeverFailsOrDelaysTheLocalPublishAndRetryRecovers() throws Exception {
        GH.failPrefix = "GET ";
        GH.failStatus = 401;
        GH.failBody = "{\"message\":\"Bad credentials " + FakeGitHub.TOKEN + "\"}";
        long id = create("Resilient", "rich");
        long start = System.nanoTime();
        JsonNode r = c.json("POST", "/api/admin/docs/pages/" + id + "/publish",
                Map.of("publicHost", "resilient.example.test", "githubSlug", "resilient", "version", version(id)), 200);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(r.path("live").asBoolean()).isTrue();
        assertThat(localHosts().has("resilient.example.test")).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> github.status().state() == GitHubSync.State.ERROR);
        JsonNode st = c.json("GET", "/api/admin/docs/github/status", null, 200);
        assertThat(st.path("state").asText()).isEqualTo("error");
        assertThat(st.path("message").asText()).contains("401").doesNotContain(FakeGitHub.TOKEN);
        assertThat(st.path("enabled").asBoolean()).isTrue();
        assertThat(st.has("lastSyncedAt")).isTrue();
        assertThat(st.path("siteUrl").asText()).isEqualTo(SITE);
        assertThat(st.has("files")).isTrue();
        assertThat(st.has("dryRun")).isTrue();

        GH.failPrefix = null;
        JsonNode retry = c.json("POST", "/api/admin/docs/github/retry", null, 200);
        assertThat(retry.path("state").asText()).isIn("pending", "building", "live");
        awaitFile("resilient/page.json");
        assertThat(c.json("GET", "/api/admin/docs/github/status", null, 200).path("state").asText()).isEqualTo("live");
    }

    // ---------------------------------------------------------------- portfolio

    @Test
    void thePortfolioIsDeployedByHandBehindWhenEditedAndRemovable() throws Exception {
        String base = "/api/admin/portfolio/";
        JsonNode before = c.json("GET", base + "github/status", null, 200);
        assertThat(before.path("enabled").asBoolean()).isTrue();
        assertThat(before.path("deployed").asBoolean()).isFalse();
        assertThat(before.path("behind").asBoolean()).isFalse();
        assertThat(before.path("url").asText()).isEqualTo(SITE + "portfolio/");
        assertThat(before.path("sync").has("state")).isTrue();

        JsonNode d = c.json("POST", base + "github/deploy", null, 200);
        assertThat(d.path("deployed").asBoolean()).isTrue();
        assertThat(d.path("behind").asBoolean()).isFalse();
        assertThat(d.path("deployedAt").asText()).isNotEmpty();
        awaitFile("portfolio/portfolio.json");
        assertThat(GH.text("portfolio/index.html")).contains("<meta name=\"static-data\" content=\"portfolio.json\">").contains("./assets/p.js");
        assertThat(GH.text("portfolio/assets/p.js")).contains("portfolio fixture");
        assertThat(GH.files()).containsKey("portfolio/avatar.webp");
        String deployedJson = GH.text("portfolio/portfolio.json");
        assertThat(M.readTree(deployedJson).has("data")).isTrue();
        assertThat(deployedJson).isEqualTo(Files.readString(DIR.resolve("portfolio-snapshot/snapshot.json")));

        // A new timestamp alone is not a change.
        c.json("POST", base + "republish", null, 200);
        assertThat(c.json("GET", base + "github/status", null, 200).path("behind").asBoolean()).isFalse();

        // A real edit makes it behind; the site keeps the deployed copy.
        c.json("POST", base + "skill-groups", Map.of("category", "Languages", "icon", "Code", "sortOrder", 1), 201);
        assertThat(c.json("GET", base + "github/status", null, 200).path("behind").asBoolean()).isTrue();
        assertThat(GH.text("portfolio/portfolio.json")).isEqualTo(deployedJson);

        // The zip is the same site at the zip root, with the current content, and works for any state.
        HttpResponse<byte[]> zipRes = c.getBytes(base + "export.zip");
        assertThat(zipRes.statusCode()).isEqualTo(200);
        assertThat(zipRes.headers().firstValue("Content-Type").orElse("")).contains("application/zip");
        assertThat(zipRes.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        Map<String, String> zip = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipRes.body()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                zip.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(zip.keySet()).containsExactlyInAnyOrder("index.html", "assets/p.js", "avatar.webp", "portfolio.json");
        assertThat(zip.get("index.html")).contains("name=\"static-data\" content=\"portfolio.json\"");
        assertThat(zip.get("portfolio.json")).contains("Languages").isNotEqualTo(deployedJson);

        // Deploy again: up to date and the new content is on the branch.
        JsonNode again = c.json("POST", base + "github/deploy", null, 200);
        assertThat(again.path("behind").asBoolean()).isFalse();
        awaitTree(f -> new String(f.get("portfolio/portfolio.json"), StandardCharsets.UTF_8).contains("Languages"));

        // Remove: the folder, the stored copy and the marks are gone.
        JsonNode removed = c.json("POST", base + "github/remove", null, 200);
        assertThat(removed.path("deployed").asBoolean()).isFalse();
        assertThat(removed.path("deployedAt").isNull()).isTrue();
        assertThat(removed.path("behind").asBoolean()).isFalse();
        awaitTree(f -> f.keySet().stream().noneMatch(k -> k.startsWith("portfolio/")));
        assertThat(Files.exists(DIR.resolve("data/github/portfolio.json"))).isFalse();
        Set<String> seen = new HashSet<>(GH.files().keySet());
        assertThat(seen).contains("viewer/assets/app.js");
    }
}
