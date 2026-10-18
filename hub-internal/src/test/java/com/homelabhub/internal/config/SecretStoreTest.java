package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreTest {

    @TempDir Path dir;

    private SecretStore store(String content) throws Exception {
        Path f = dir.resolve("secrets.env");
        Files.writeString(f, content);
        return new SecretStore(f.toString(), dir.toString());
    }

    @Test
    void readsKeyValueLinesIgnoringCommentsBlanksAndQuotes() throws Exception {
        var s = store("# comment\n\nPVE_PASSWORD=abc123\nQUOTED=\"with spaces\"\nSINGLE='x y'\n  SPACED = value  \nBROKEN LINE\n=novalue\n");
        assertThat(s.get("PVE_PASSWORD")).contains("abc123");
        assertThat(s.get("QUOTED")).contains("with spaces");
        assertThat(s.get("SINGLE")).contains("x y");
        assertThat(s.get("SPACED")).contains("value");
        assertThat(s.isSet("BROKEN")).isFalse();
        assertThat(s.isSet("NOPE")).isFalse();
    }

    @Test
    void anEmptyValueCountsAsNotSet() throws Exception {
        assertThat(store("EMPTY=\n").isSet("EMPTY")).isFalse();
    }

    @Test
    void resolveSubstitutesEveryReferenceAndKeepsSpecialCharactersLiteral() throws Exception {
        var s = store("A=p$1\\w\\d\nB=second\n");
        assertThat(s.resolve("user:${secret:A}@${secret:B}")).isEqualTo("user:p$1\\w\\d@second");
        assertThat(s.resolve("no references here")).isEqualTo("no references here");
        assertThat(s.resolve(null)).isNull();
    }

    @Test
    void aMissingSecretFailsInsteadOfSendingAHole() throws Exception {
        var s = store("A=1\n");
        assertThatThrownBy(() -> s.resolve("x ${secret:MISSING} y"))
                .isInstanceOfSatisfying(SecretStore.MissingSecretException.class, e -> {
                    assertThat(e.secretName()).isEqualTo("MISSING");
                    assertThat(e.getMessage()).doesNotContain("x ").doesNotContain(" y");
                });
    }

    @Test
    void aChangedFileIsPickedUpWithoutARestart() throws Exception {
        var s = store("A=old\n");
        assertThat(s.get("A")).contains("old");
        Path f = dir.resolve("secrets.env");
        Files.writeString(f, "A=new\nB=added\n");
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertThat(s.get("A")).contains("new");
        assertThat(s.get("B")).contains("added");
    }

    @Test
    void aMissingFileMeansNoSecretsNotAnError() {
        var s = new SecretStore(dir.resolve("nope.env").toString(), dir.toString());
        assertThat(s.isSet("ANY")).isFalse();
    }

    @Test
    void theDefaultLocationIsInsideTheDataDirectory() throws Exception {
        Files.writeString(dir.resolve("secrets.env"), "X=1\n");
        assertThat(new SecretStore("", dir.toString()).get("X")).contains("1");
    }
}
