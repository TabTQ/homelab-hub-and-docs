package com.homelabhub.docspub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocsStoreTest {

    @TempDir Path dir;

    void write(String name, String content, long mtime) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
    }

    @Test
    void keepsTheLastGoodCopyWhenFilesBreak() throws Exception {
        DocsStore s = new DocsStore(dir.toString());
        assertThat(s.pageFor("t1.example.test")).isEmpty();
        write("hosts.json", "{\"schemaVersion\":1,\"hosts\":{\"t1.example.test\":{\"page\":1}}}", 1000);
        write("pages/1.json", "{\"schemaVersion\":1,\"title\":\"good\"}", 1000);
        assertThat(s.pageFor("t1.example.test").orElseThrow().tree().path("title").asText()).isEqualTo("good");

        write("pages/1.json", "{not json", 2000);
        assertThat(s.pageFor("t1.example.test").orElseThrow().tree().path("title").asText()).isEqualTo("good");
        write("pages/1.json", "{\"schemaVersion\":1,\"title\":\"" + "x".repeat(DocsStore.MAX_PAGE_BYTES) + "\"}", 3000);
        assertThat(s.pageFor("t1.example.test").orElseThrow().tree().path("title").asText()).isEqualTo("good");
        write("hosts.json", "garbage", 4000);
        assertThat(s.pageFor("t1.example.test")).isPresent();
        write("hosts.json", "{\"schemaVersion\":2,\"hosts\":{}}", 5000);
        assertThat(s.pageFor("t1.example.test")).as("unknown schema: last good copy").isPresent();

        write("pages/1.json", "{\"schemaVersion\":1,\"title\":\"newer\"}", 6000);
        assertThat(s.pageFor("t1.example.test").orElseThrow().tree().path("title").asText()).isEqualTo("newer");
        write("hosts.json", "{\"schemaVersion\":1,\"hosts\":{}}", 7000);
        assertThat(s.pageFor("t1.example.test")).as("unpublished").isEmpty();
    }

    @Test
    void hostNormalisation() {
        assertThat(DocsStore.normalizeHost("T1.Example.Test:8443")).isEqualTo("t1.example.test");
        assertThat(DocsStore.normalizeHost("t1.example.test.")).isEqualTo("t1.example.test");
        assertThat(DocsStore.normalizeHost("[::1]:80")).isNull();
        assertThat(DocsStore.normalizeHost("evil/path")).isNull();
        assertThat(DocsStore.normalizeHost(null)).isNull();
    }

    @Test
    void etagMatching() {
        assertThat(DocsPublicController.etagMatches("W/\"abc\", \"x\"", "\"abc\"")).isTrue();
        assertThat(DocsPublicController.etagMatches("*", "\"abc\"")).isTrue();
        assertThat(DocsPublicController.etagMatches("\"nope\"", "\"abc\"")).isFalse();
    }
}
