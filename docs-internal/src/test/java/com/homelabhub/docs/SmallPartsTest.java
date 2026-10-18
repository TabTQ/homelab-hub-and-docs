package com.homelabhub.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BlobStore, PreviewTokens, MimeSniffer, WebappSettings and the hub-embed copy. */
class SmallPartsTest {

    @TempDir Path tmp;

    @Test
    void blobStoreIsContentAddressedAndRefusesOversizedInput() throws Exception {
        BlobStore s = new BlobStore(tmp.resolve("blobs"));
        BlobStore.Stored a = s.put("hello".getBytes());
        BlobStore.Stored b = s.put("hello".getBytes());
        assertThat(a).isEqualTo(b);
        assertThat(a.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(s.path(a.sha256())).isEqualTo(tmp.resolve("blobs/2c/" + a.sha256()));
        assertThat(Files.readString(s.path(a.sha256()))).isEqualTo("hello");
        assertThatThrownBy(() -> s.put(new java.io.ByteArrayInputStream(new byte[2048]), 1024)).isInstanceOf(BlobStore.TooLargeException.class);
        try (var files = Files.list(tmp.resolve("blobs"))) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count()).as("no temp files left").isZero();
        }
        assertThatThrownBy(() -> s.path("../x")).isInstanceOf(IllegalArgumentException.class);
        s.copyTo(a.sha256(), tmp.resolve("other"));
        assertThat(Files.readString(tmp.resolve("other/2c/" + a.sha256()))).isEqualTo("hello");
    }

    @Test
    void previewTokensAreBoundToABundleAndExpire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
        PreviewTokens t = new PreviewTokens(clock, new byte[32]);
        String sha = "a".repeat(64);
        String token = t.mint(sha);
        assertThat(t.valid(token, sha)).isTrue();
        assertThat(t.valid(token, "b".repeat(64))).isFalse();
        assertThat(t.valid(token.replaceFirst("\\d", "9"), sha)).as("expiry is signed").isFalse();
        assertThat(t.valid("garbage", sha)).isFalse();
        assertThat(new PreviewTokens(clock).valid(token, sha)).as("another boot, another key").isFalse();
        clock.now = clock.now.plus(PreviewTokens.TTL).plusSeconds(1);
        assertThat(t.valid(token, sha)).isFalse();
    }

    @Test
    void mimeIsSniffedFromContent() {
        assertThat(MimeSniffer.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "x.jpg")).isEqualTo("image/png");
        assertThat(MimeSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, "x")).isEqualTo("image/jpeg");
        assertThat(MimeSniffer.sniff("%PDF-1.7".getBytes(), "a.png")).isEqualTo("application/pdf");
        assertThat(MimeSniffer.sniff("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(), "a")).isEqualTo("image/svg+xml");
        assertThat(MimeSniffer.sniff("<html><script>alert(1)</script>".getBytes(), "a.html")).isEqualTo("text/plain");
        assertThat(MimeSniffer.sniff("a: 1\n".getBytes(), "c.yaml")).isEqualTo("application/yaml");
        assertThat(MimeSniffer.sniff(new byte[] {0, 1, 2, 3, 4}, "x.bin")).isNull();
        assertThat(MimeSniffer.sniff("é".repeat(10).getBytes(StandardCharsets.UTF_8), "t.txt")).isEqualTo("text/plain");
    }

    @Test
    void webappSettingsAreValidatedAndDriveTheCsp() throws Exception {
        ObjectMapper m = new ObjectMapper();
        var s = WebappSettings.validate(m.readTree("{\"display\":\"framed\",\"height\":{\"mode\":\"auto\",\"px\":700},\"cdns\":[\"jsdelivr\",\"jsdelivr\"]}"));
        assertThat(s.path("cdns")).hasSize(1);
        assertThat(WebappSettings.bundleCsp(s, "'self'")).startsWith("sandbox allow-scripts; ").contains("https://cdn.jsdelivr.net")
                .doesNotContain("unpkg").contains("connect-src 'self'").contains("frame-ancestors 'self'");
        assertThatThrownBy(() -> WebappSettings.validate(m.readTree("{\"cdns\":[\"evil.com\"]}"))).hasMessageContaining("CDN");
        assertThatThrownBy(() -> WebappSettings.validate(m.readTree("{\"height\":{\"mode\":\"auto\",\"px\":10}}"))).hasMessageContaining("height");
        assertThatThrownBy(() -> WebappSettings.validate(m.readTree("{\"display\":\"popup\"}"))).hasMessageContaining("Display");
    }

    @Test
    void hubEmbedScriptIsTheSameInBothApps() throws Exception {
        Path other = Path.of("../docs-public/src/main/java/com/homelabhub/docspub/HubEmbed.java");
        String theirs = Files.readString(other);
        String script = theirs.substring(theirs.indexOf("\"\"\"") + 3, theirs.lastIndexOf("\"\"\""));
        String mine = Files.readString(Path.of("src/main/java/com/homelabhub/docs/HubEmbed.java"));
        assertThat(mine.substring(mine.indexOf("\"\"\"") + 3, mine.lastIndexOf("\"\"\""))).isEqualTo(script);
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
