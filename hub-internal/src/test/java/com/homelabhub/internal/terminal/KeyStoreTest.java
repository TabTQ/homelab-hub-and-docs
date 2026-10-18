package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyStoreTest {

    @TempDir Path dir;

    private TerminalException.Code codeOf(KeyStore ks, String name) {
        try {
            ks.read(name);
            throw new AssertionError("expected a refusal for " + name);
        } catch (TerminalException e) {
            return e.code();
        }
    }

    @Test
    void readsARegularFileInsideTheDirectory() throws Exception {
        Files.createDirectories(dir.resolve("ssh"));
        Files.writeString(dir.resolve("ssh/k"), "key");
        assertThat(new KeyStore(dir.resolve("ssh")).read("k")).isEqualTo("key".getBytes());
    }

    @Test
    void refusesPathsAndTraversal() throws Exception {
        Files.createDirectories(dir.resolve("ssh"));
        Files.writeString(dir.resolve("secret"), "outside");
        KeyStore ks = new KeyStore(dir.resolve("ssh"));
        for (String bad : new String[] {"../secret", "/etc/passwd", "a/b", "a\\b", "..", ".", "", null}) {
            assertThat(codeOf(ks, bad)).as(String.valueOf(bad)).isIn(TerminalException.Code.key_invalid, TerminalException.Code.key_missing);
        }
        assertThat(codeOf(ks, "../secret")).isEqualTo(TerminalException.Code.key_invalid);
    }

    @Test
    void refusesSymlinksThatLeaveTheDirectory() throws Exception {
        Files.createDirectories(dir.resolve("ssh"));
        Files.writeString(dir.resolve("secret"), "outside");
        Files.createSymbolicLink(dir.resolve("ssh/link"), dir.resolve("secret"));
        assertThat(codeOf(new KeyStore(dir.resolve("ssh")), "link")).isEqualTo(TerminalException.Code.key_invalid);
    }

    @Test
    void refusesDirectoriesAndReportsMissingFiles() throws Exception {
        Files.createDirectories(dir.resolve("ssh/sub"));
        KeyStore ks = new KeyStore(dir.resolve("ssh"));
        assertThat(codeOf(ks, "sub")).isEqualTo(TerminalException.Code.key_invalid);
        assertThat(codeOf(ks, "missing")).isEqualTo(TerminalException.Code.key_missing);
        assertThat(codeOf(new KeyStore(dir.resolve("no-such-dir")), "k")).isEqualTo(TerminalException.Code.key_missing);
    }
}
