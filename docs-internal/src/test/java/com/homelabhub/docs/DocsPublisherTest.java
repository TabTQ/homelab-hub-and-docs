package com.homelabhub.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The publish pipeline (plan P4): snapshot contents, link rewriting, re-rendering, crash safety and delayed clean-up. */
@SpringBootTest
class DocsPublisherTest {

    static final Path DIR = DocsClient.tempDir();
    static final ObjectMapper M = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        DocsClient.useTemp(r, DIR);
        r.add("hub.auth.mode", () -> "none");
        r.add("docs.gc-delay-seconds", () -> "0");
        r.add("docs.publish-retry-millis", () -> "3600000");
    }

    @Autowired DocsService docs;
    @Autowired DocsPublisher publisher;
    @Autowired BlobStore blobs;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void noCrash() {
        publisher.failAfterStep = 0;
    }

    Path snap(String p) {
        return DIR.resolve("snapshot").resolve(p);
    }

    JsonNode pageFile(long id) throws Exception {
        return M.readTree(Files.readString(snap("pages/" + id + ".json")));
    }

    JsonNode hosts() throws Exception {
        return M.readTree(Files.readString(snap("hosts.json"))).path("hosts");
    }

    long version(long id) {
        return ((Number) docs.get(id).get("version")).longValue();
    }

    void save(long id, String title, String json) throws Exception {
        docs.save(id, title, M.readTree(json), version(id));
    }

    void publish(long id, String host) {
        docs.publish(id, host, version(id), "127.0.0.1", "test");
    }

    @Test
    void linksToPublicPagesBecomeHostnamesAndInternalOnesPlainText() throws Exception {
        String img = docs.addAsset(new java.io.ByteArrayInputStream(DocsClient.PNG), "net.png").get("sha256").toString();
        long target = docs.create(null, null, "rich", "Target");
        long internal = docs.create(null, null, "rich", "Internal");
        long page = docs.create(null, null, "rich", "Linker");
        save(page, "Linker", """
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"pageLink","attrs":{"pageId":%d,"label":"Target"}},{"type":"pageLink","attrs":{"pageId":%d,"label":"Internal"}}]},
                  {"type":"image","attrs":{"sha":"%s","alt":"pic"}}]}""".formatted(target, internal, img));
        publish(target, "target.example.test");
        publish(page, "linker.example.test");

        JsonNode p = pageFile(page);
        assertThat(p.at("/body/content/0/content/0/attrs/href").asText()).isEqualTo("https://target.example.test/");
        assertThat(p.at("/body/content/0/content/1/attrs/href").isMissingNode()).isTrue();
        assertThat(p.toString()).as("no other page ids leak").doesNotContain("pageId");
        assertThat(p.path("assets").has(img)).isTrue();
        assertThat(Files.exists(snap("blobs/" + img.substring(0, 2) + "/" + img))).isTrue();
        assertThat(hosts().path("linker.example.test").path("page").asLong()).isEqualTo(page);

        // Changing the target's hostname re-renders the linker.
        docs.setHost(target, "moved.example.test", version(target));
        assertThat(pageFile(page).at("/body/content/0/content/0/attrs/href").asText()).isEqualTo("https://moved.example.test/");
        // Unpublishing the target turns the link into plain text.
        docs.unpublish(target, version(target));
        assertThat(pageFile(page).at("/body/content/0/content/0/attrs/href").isMissingNode()).isTrue();
        assertThat(hosts().has("moved.example.test")).isFalse();
    }

    @Test
    void webappPagesCarryTheirBundleAndEmbedsTheirOwn() throws Exception {
        long app = docs.create(null, null, "webapp", "App");
        docs.uploadBundle(app, BundleReaderTest.zip(BundleReaderTest.files("index.html", "<p>app</p>", "x.js", "1")), "a.zip", version(app));
        long rich = docs.create(null, null, "rich", "Embeds");
        save(rich, "Embeds", "{\"type\":\"doc\",\"content\":[{\"type\":\"webappEmbed\",\"attrs\":{\"pageId\":" + app + ",\"height\":400}}]}");
        publish(rich, "embeds.example.test");
        JsonNode p = pageFile(rich);
        String sha = p.at("/body/content/0/attrs/bundle").asText();
        assertThat(sha).hasSize(64);
        assertThat(p.path("embeds").path(sha).path("bundle").path("files").has("index.html")).isTrue();

        publish(app, "app.example.test");
        JsonNode a = pageFile(app);
        assertThat(a.path("kind").asText()).isEqualTo("webapp");
        assertThat(a.path("bundle").path("sha").asText()).isEqualTo(sha);
        String file = a.path("bundle").path("files").path("x.js").path("sha").asText();
        assertThat(Files.exists(snap("blobs/" + file.substring(0, 2) + "/" + file))).isTrue();
    }

    @Test
    void aCrashBetweenStepsNeverLeavesHostsPointingAtAMissingFile() throws Exception {
        long id = docs.create(null, null, "rich", "Crashy");
        save(id, "Crashy", DocsClient.para("v1"));
        for (int step = 1; step <= 2; step++) {
            publisher.failAfterStep = step;
            docs.publish(id, "crash" + step + ".example.test", version(id), "ip", "ua");
            assertThat(publisher.status().upToDate()).isFalse();
            assertThat(hostsPointAtExistingFiles()).isTrue();
            assertThat(Files.exists(snap("hosts.json")) ? hosts().has("crash" + step + ".example.test") : false)
                    .as("the switch was not thrown").isFalse();
        }
        publisher.failAfterStep = 0;
        assertThat(publisher.publish()).isTrue();
        assertThat(hosts().has("crash2.example.test")).isTrue();
        assertThat(hostsPointAtExistingFiles()).isTrue();
    }

    @Test
    void cleanUpWaitsForTheDelayAndRemovesUnreferencedFiles() throws Exception {
        long id = docs.create(null, null, "rich", "Temporary");
        save(id, "Temporary", DocsClient.para("soon gone"));
        publish(id, "temp.example.test");
        assertThat(Files.exists(snap("pages/" + id + ".json"))).isTrue();
        docs.unpublish(id, version(id));
        // gc-delay-seconds is 0 in this test, so the run that throws the switch also removes the file.
        assertThat(Files.exists(snap("pages/" + id + ".json"))).isFalse();
        assertThat(publisher.pendingGarbage()).isZero();
    }

    @Test
    void deletingAPublicPageTakesItOffline() throws Exception {
        long id = docs.create(null, null, "rich", "Deleted");
        publish(id, "deleted.example.test");
        assertThat(hosts().has("deleted.example.test")).isTrue();
        docs.delete(id, null);
        assertThat(hosts().has("deleted.example.test")).isFalse();
    }

    @Test
    void trashIsPurgedAfterThirtyDays() throws Exception {
        long id = docs.create(null, null, "rich", "Old");
        docs.delete(id, null);
        docs.purgeTrash(Instant.now().plus(DocsService.TRASH_DAYS));
        assertThat(jdbc.sql("SELECT count(*) FROM doc_pages WHERE id = ?").param(id).query(Integer.class).single()).isZero();
    }

    @Test
    void versionsArePrunedButPublishedOnesKept() throws Exception {
        long id = docs.create(null, null, "rich", "Busy");
        publish(id, "busy.example.test");
        for (int i = 0; i < DocsService.KEEP_VERSIONS + 5; i++) {
            save(id, "Busy", DocsClient.para("v" + i));
        }
        int count = jdbc.sql("SELECT count(*) FROM doc_page_versions WHERE page_id = ?").param(id).query(Integer.class).single();
        assertThat(count).isEqualTo(DocsService.KEEP_VERSIONS + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM doc_page_versions WHERE page_id = ? AND kind = 'published'").param(id).query(Integer.class).single()).isEqualTo(1);
    }

    private boolean hostsPointAtExistingFiles() throws Exception {
        if (!Files.exists(snap("hosts.json"))) {
            return true;
        }
        var it = hosts().fields();
        while (it.hasNext()) {
            long page = it.next().getValue().path("page").asLong();
            if (!Files.exists(snap("pages/" + page + ".json"))) {
                return false;
            }
            JsonNode p = pageFile(page);
            try (Stream<String> shas = Stream.concat(
                    java.util.stream.StreamSupport.stream(((Iterable<String>) () -> p.path("assets").fieldNames()).spliterator(), false),
                    java.util.stream.StreamSupport.stream(p.path("bundle").path("files").spliterator(), false).map(f -> f.path("sha").asText()))) {
                if (shas.anyMatch(s -> !Files.exists(snap("blobs/" + s.substring(0, 2) + "/" + s)))) {
                    return false;
                }
            }
        }
        return true;
    }
}
