package com.homelabhub.pub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import com.homelabhub.pub.SnapshotStore.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreTest {

    @TempDir
    Path tempDir;

    private SnapshotStore store;
    private Path snapshotFile;

    @BeforeEach
    void setUp() {
        store = new SnapshotStore(tempDir.toString());
        snapshotFile = tempDir.resolve("snapshot.json");
    }

    private void write(String content) throws IOException {
        Files.writeString(snapshotFile, content);
        Files.setLastModifiedTime(snapshotFile, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
    }

    private static String validJson(String marker) {
        return "{\"schemaVersion\":1,\"generatedAt\":\"2026-01-01T00:00:00Z\",\"data\":{\"marker\":\"" + marker + "\"}}";
    }

    @Test
    void missingFileReturnsEmpty() {
        assertThat(store.current()).isEmpty();
    }

    @Test
    void validFileIsPresentWithQuotedEtag() throws IOException {
        write(validJson("x"));
        Optional<Snapshot> result = store.current();
        assertThat(result).isPresent();
        assertThat(result.get().bytes()).isEqualTo(validJson("x").getBytes(StandardCharsets.UTF_8));
        assertThat(result.get().etag()).startsWith("\"").endsWith("\"");
        assertThat(result.get().etag()).hasSize(18);
    }

    @Test
    void changedFileYieldsNewEtag() throws IOException {
        write(validJson("a"));
        Snapshot first = store.current().orElseThrow();
        write(validJson("b"));
        Snapshot second = store.current().orElseThrow();
        assertThat(second.etag()).isNotEqualTo(first.etag());
        assertThat(second.bytes()).isNotSameAs(first.bytes());
    }

    @Test
    void invalidJsonAfterGoodKeepsPreviousCopy() throws IOException {
        write(validJson("a"));
        Snapshot good = store.current().orElseThrow();
        write("this is not json");
        Optional<Snapshot> now = store.current();
        assertThat(now).isPresent();
        assertThat(now.get().bytes()).isSameAs(good.bytes());
        assertThat(now.get().etag()).isEqualTo(good.etag());
    }

    @Test
    void wrongSchemaVersionKeepsPreviousCopy() throws IOException {
        write(validJson("a"));
        Snapshot good = store.current().orElseThrow();
        write("{\"schemaVersion\":99,\"generatedAt\":\"2026-01-01T00:00:00Z\",\"data\":{\"marker\":\"z\"}}");
        Optional<Snapshot> now = store.current();
        assertThat(now).isPresent();
        assertThat(now.get().bytes()).isSameAs(good.bytes());
        assertThat(now.get().etag()).isEqualTo(good.etag());
    }

    @Test
    void nullDataKeepsPreviousCopy() throws IOException {
        write(validJson("a"));
        Snapshot good = store.current().orElseThrow();
        write("{\"schemaVersion\":1,\"generatedAt\":\"2026-01-01T00:00:00Z\"}");
        Optional<Snapshot> now = store.current();
        assertThat(now).isPresent();
        assertThat(now.get().bytes()).isSameAs(good.bytes());
    }

    @Test
    void deletedFileKeepsPreviousCopy() throws IOException {
        write(validJson("a"));
        Snapshot good = store.current().orElseThrow();
        Files.delete(snapshotFile);
        Optional<Snapshot> now = store.current();
        assertThat(now).isPresent();
        assertThat(now.get().bytes()).isSameAs(good.bytes());
    }

    @Test
    void neverValidFileReturnsEmpty() throws IOException {
        write("not json");
        assertThat(store.current()).isEmpty();
    }

    @Test
    void unchangedFileIsNotReread() throws IOException {
        write(validJson("a"));
        Snapshot first = store.current().orElseThrow();
        Snapshot second = store.current().orElseThrow();
        assertThat(second.etag()).isEqualTo(first.etag());
        assertThat(second.bytes()).isSameAs(first.bytes());
    }

    @Test
    void jsonNullLiteralKeepsPreviousGoodCopyInsteadOfThrowing() throws IOException {
        write(validJson("good"));
        Snapshot good = store.current().orElseThrow();
        write("null");
        assertThat(store.current()).isPresent();
        assertThat(store.current().get().etag()).isEqualTo(good.etag());
    }

    @Test
    void jsonNullLiteralWithNoPriorCopyIsEmpty() throws IOException {
        write("null");
        assertThat(store.current()).isEmpty();
    }

    @Test
    void emptyFileIsTreatedAsInvalid() throws IOException {
        write(validJson("good"));
        Snapshot good = store.current().orElseThrow();
        write("");
        assertThat(store.current().orElseThrow().etag()).isEqualTo(good.etag());
    }

    @Test
    void oversizedFileIsRejectedAndPreviousCopyKept() throws IOException {
        write(validJson("good"));
        Snapshot good = store.current().orElseThrow();
        write(validJson("x".repeat(SnapshotStore.MAX_BYTES + 1)));
        assertThat(store.current().orElseThrow().etag()).isEqualTo(good.etag());
    }

    @Test
    void recoversAfterAnInvalidPeriod() throws IOException {
        write(validJson("one"));
        store.current();
        write("{not json");
        store.current();
        write(validJson("two"));
        Snapshot after = store.current().orElseThrow();
        assertThat(new String(after.bytes(), StandardCharsets.UTF_8)).contains("two");
    }
}
