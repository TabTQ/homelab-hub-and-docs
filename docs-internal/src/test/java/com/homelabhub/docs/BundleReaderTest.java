package com.homelabhub.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BundleReaderTest {

    static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static Map<String, byte[]> files(String... pairs) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Marks entry {@code name} as a Unix symlink in the central directory (what `zip --symlinks` produces). */
    static byte[] withSymlink(byte[] zip, String name) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        for (int p = 0; p + 46 < zip.length; p++) {
            if (zip[p] == 0x50 && zip[p + 1] == 0x4b && zip[p + 2] == 1 && zip[p + 3] == 2) {
                int len = (zip[p + 28] & 0xff) | (zip[p + 29] & 0xff) << 8;
                if (len == n.length && new String(zip, p + 46, len, StandardCharsets.UTF_8).equals(name)) {
                    zip[p + 5] = 3; // made by Unix
                    int mode = 0120777;
                    zip[p + 40] = (byte) mode;
                    zip[p + 41] = (byte) (mode >> 8);
                }
            }
        }
        return zip;
    }

    @Test
    void readsAZipAndStripsOneTopFolder() throws Exception {
        List<BundleReader.Entry> e = BundleReader.zip(zip(files("app/index.html", "<h1>x</h1>", "app/js/app.js", "1", "app/img/a.svg", "<svg/>")));
        assertThat(e).extracting(BundleReader.Entry::path).containsExactly("index.html", "js/app.js", "img/a.svg");
        assertThat(e.get(1).mime()).startsWith("text/javascript");
    }

    @Test
    void keepsFoldersWhenIndexIsAtTheTop() throws Exception {
        List<BundleReader.Entry> e = BundleReader.zip(zip(files("index.html", "x", "data/steps.json", "{}")));
        assertThat(e).extracting(BundleReader.Entry::path).containsExactly("index.html", "data/steps.json");
    }

    @Test
    void refusesTraversalAbsoluteAndBackslashPaths() throws Exception {
        assertThatThrownBy(() -> BundleReader.zip(zip(files("index.html", "x", "../evil.js", "1")))).hasMessageContaining("leaves its folder");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("index.html", "x", "a/../../evil.js", "1")))).hasMessageContaining("leaves its folder");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("/etc/index.html", "x")))).hasMessageContaining("absolute");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("C:/index.html", "x")))).hasMessageContaining("absolute");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("index.html", "x", "a\\b.js", "1")))).hasMessageContaining("backslash");
    }

    @Test
    void refusesSymlinks() throws Exception {
        byte[] z = withSymlink(zip(files("index.html", "x", "passwd.txt", "/etc/passwd")), "passwd.txt");
        assertThatThrownBy(() -> BundleReader.zip(z)).hasMessageContaining("symbolic link");
    }

    @Test
    void refusesDisallowedTypesAndHiddenFiles() throws Exception {
        assertThatThrownBy(() -> BundleReader.zip(zip(files("index.html", "x", "run.sh", "1")))).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("index.html", "x", "noext", "1")))).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("index.html", "x", ".env.js", "1")))).hasMessageContaining("Hidden");
    }

    @Test
    void refusesMissingIndex() throws Exception {
        assertThatThrownBy(() -> BundleReader.zip(zip(files("app.js", "1")))).hasMessageContaining("index.html");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("a/index.html", "1", "b/x.js", "2")))).hasMessageContaining("index.html");
    }

    @Test
    void refusesTooManyEntries() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("index.html", new byte[1]);
        for (int i = 0; i < BundleReader.MAX_ENTRIES; i++) {
            m.put("f" + i + ".txt", new byte[1]);
        }
        assertThatThrownBy(() -> BundleReader.zip(zip(m))).hasMessageContaining("more than 500");
    }

    @Test
    void refusesZipBombsByCountingBytesRead() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("index.html", new byte[1]);
        m.put("big.txt", new byte[20 * 1024 * 1024]); // 20 MB of zeros compress to ~20 KB: ratio far above 100
        assertThatThrownBy(() -> BundleReader.zip(zip(m))).hasMessageContaining("zip bomb");
        Map<String, byte[]> huge = new LinkedHashMap<>();
        huge.put("index.html", new byte[1]);
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < 4; i++) {
            byte[] b = new byte[16 * 1024 * 1024];
            r.nextBytes(b);
            huge.put("r" + i + ".txt", b);
        }
        assertThatThrownBy(() -> BundleReader.zip(zip(huge))).hasMessageContaining("60 MB");
    }

    @Test
    void refusesDuplicatesAndGarbage() throws Exception {
        assertThatThrownBy(() -> BundleReader.zip("not a zip at all".getBytes())).hasMessageContaining("not a valid ZIP");
        assertThatThrownBy(() -> BundleReader.zip(zip(files("index.html", "x", "A.js", "1", "a.js", "2")))).hasMessageContaining("twice");
    }

    @Test
    void singleHtmlIsAOneFileBundle() {
        assertThat(BundleReader.singleHtml("<p>x</p>".getBytes())).extracting(BundleReader.Entry::path).containsExactly("index.html");
        assertThat(BundleReader.looksLikeZip(new byte[] {'P', 'K', 3, 4})).isTrue();
        assertThat(BundleReader.looksLikeZip("<html>".getBytes())).isFalse();
    }
}
