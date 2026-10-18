package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.config.ConfigStore.Health;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ConfigStoreTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired ConfigStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProxmoxClient proxmox;

    static String doc(String title) {
        return "# my comment\nversion: 1\ntitle: " + title + "\ngroups:\n  - name: G   # trailing comment\n    items:\n      - {name: X, href: \"http://x\"}\n";
    }

    Path file() {
        return store.file();
    }

    /** Puts the store into a known state regardless of what earlier tests did. */
    @BeforeEach
    void reset() {
        for (int i = 0; i < 5; i++) {
            try {
                store.save(doc("Reset"), store.state().sha256(), "ui", "reset");
                return;
            } catch (ConfigStore.ConflictException e) {
                // a background reload raced us; retry with the new state
            }
        }
    }

    @Test
    void theStarterFileWasCreatedAndIsHealthy() throws Exception {
        assertThat(file()).exists();
        assertThat(file().toString()).startsWith(DIR.toString());
        assertThat(store.state().health()).isEqualTo(Health.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM config_versions", Integer.class)).isGreaterThan(0);
    }

    @Test
    void savingWritesTheFileExactlyKeepingCommentsAndRecordsAVersion() throws Exception {
        String text = doc("Fresh");
        var s = store.save(text, store.state().sha256(), "ui", "note");
        assertThat(Files.readString(file())).isEqualTo(text);
        assertThat(s.config().title()).isEqualTo("Fresh");
        assertThat(store.versions().get(0).source()).isEqualTo("ui");
        assertThat(store.versions().get(0).sha256()).isEqualTo(s.sha256());
    }

    @Test
    void windowsLineEndingsAreNormalisedAndATrailingNewlineIsAdded() throws Exception {
        store.save("version: 1\r\ntitle: W\r\ngroups: []", store.state().sha256(), "ui", null);
        assertThat(Files.readString(file())).isEqualTo("version: 1\ntitle: W\ngroups: []\n");
    }

    @Test
    void aStaleEditorCannotOverwriteANewerFile() throws Exception {
        String stale = store.state().sha256();
        store.save(doc("Newer"), stale, "ui", null);
        assertThatThrownBy(() -> store.save(doc("Stale edit"), stale, "ui", null))
                .isInstanceOfSatisfying(ConfigStore.ConflictException.class, e -> assertThat(e.current().text()).contains("Newer"));
        assertThat(Files.readString(file())).contains("Newer");
    }

    @Test
    void anInvalidSaveChangesNothingAndAddsNoVersion() throws Exception {
        int before = store.versions().size();
        String onDisk = Files.readString(file());
        assertThatThrownBy(() -> store.save("version: 1\ngroups: nope\n", store.state().sha256(), "ui", null))
                .isInstanceOfSatisfying(ConfigStore.InvalidConfigException.class,
                        e -> assertThat(e.issues()).anySatisfy(i -> assertThat(i.path()).isEqualTo("groups")));
        assertThat(Files.readString(file())).isEqualTo(onDisk);
        assertThat(store.versions()).hasSize(before);
    }

    @Test
    void savingIdenticalTextIsANoOp() throws Exception {
        var s1 = store.save(doc("Same"), store.state().sha256(), "ui", null);
        int n = store.versions().size();
        var s2 = store.save(doc("Same"), s1.sha256(), "ui", null);
        assertThat(s2.sha256()).isEqualTo(s1.sha256());
        assertThat(store.versions()).hasSize(n);
    }

    @Test
    void noTemporaryFilesAreLeftBehind() throws Exception {
        store.save(doc("Clean"), store.state().sha256(), "ui", null);
        try (var files = Files.list(file().getParent())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("dashboard.yaml");
        }
    }

    @Test
    void theFileHasOwnerOnlyPermissions() throws Exception {
        store.save(doc("Perms"), store.state().sha256(), "ui", null);
        assertThat(java.nio.file.Files.getPosixFilePermissions(file()).stream().map(Enum::name))
                .containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE");
    }

    // ---------------------------------------------------------------- edits made outside the app

    @Test
    void aValidEditOverSshIsPickedUpAutomatically() throws Exception {
        Files.writeString(file(), doc("Edited by hand"));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(store.state().config().title()).isEqualTo("Edited by hand"));
        assertThat(store.state().health()).isEqualTo(Health.OK);
        assertThat(store.versions().get(0).source()).isEqualTo("file");
    }

    @Test
    void aBrokenEditOverSshKeepsTheLastGoodConfigAndReportsTheProblem() throws Exception {
        store.save(doc("Good"), store.state().sha256(), "ui", null);
        Files.writeString(file(), "version: 1\ngroups:\n  - name: A\n    items: [\n");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(store.state().health()).isEqualTo(Health.INVALID_ON_DISK));
        assertThat(store.state().config().title()).as("still serving the last good config").isEqualTo("Good");
        assertThat(store.state().issues()).anySatisfy(i -> assertThat(i.level()).isEqualTo(Issue.Level.ERROR));
        assertThat(store.state().text()).contains("items: [");

        Files.writeString(file(), doc("Repaired"));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(store.state().health()).isEqualTo(Health.OK));
        assertThat(store.state().config().title()).isEqualTo("Repaired");
    }

    @Test
    void aSaveFromTheBrowserRepairsABrokenFile() throws Exception {
        Files.writeString(file(), "version: 1\ngroups: nope\n");
        await().atMost(Duration.ofSeconds(8)).until(() -> store.state().health() == Health.INVALID_ON_DISK);
        var s = store.save(doc("Fixed in browser"), store.state().sha256(), "ui", null);
        assertThat(s.health()).isEqualTo(Health.OK);
        assertThat(Files.readString(file())).contains("Fixed in browser");
    }

    @Test
    void anOversizedOrBinaryFileIsRejectedWithoutExhaustingMemory() throws Exception {
        Files.write(file(), new byte[3 * 1024 * 1024]);
        await().atMost(Duration.ofSeconds(8)).until(() -> store.state().health() == Health.INVALID_ON_DISK);
        assertThat(store.state().issues()).isNotEmpty();

        Files.write(file(), new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 1, 2, (byte) 0x80});
        await().atMost(Duration.ofSeconds(8)).until(() -> store.state().health() == Health.INVALID_ON_DISK
                && store.state().text().length() < 100);
        assertThat(store.state().config()).isNotNull();
    }

    @Test
    void aDeletedFileIsToleratedAndCanBeRecreatedByASave() throws Exception {
        String sha = store.state().sha256();
        Files.delete(file());
        Thread.sleep(1500);
        assertThat(store.state().config()).isNotNull();
        // the in-memory state still matches the last known sha, so the browser can save it back
        store.save(doc("Recreated"), sha, "ui", null);
        assertThat(file()).exists();
    }

    // ---------------------------------------------------------------- versions

    @Test
    void anOldVersionCanBeRestored() throws Exception {
        var a = store.save(doc("Version A"), store.state().sha256(), "ui", null);
        long idA = store.versions().stream().filter(v -> v.sha256().equals(a.sha256())).findFirst().orElseThrow().id();
        store.save(doc("Version B"), a.sha256(), "ui", null);

        var restored = store.restore(idA, store.state().sha256());
        assertThat(restored.config().title()).isEqualTo("Version A");
        assertThat(Files.readString(file())).contains("Version A");
        assertThat(store.versionText(idA)).isPresent();
    }

    @Test
    void restoringAnUnknownVersionOrWithAStaleShaFails() {
        assertThatThrownBy(() -> store.restore(999_999, store.state().sha256())).isInstanceOf(java.util.NoSuchElementException.class);
        long any = store.versions().get(0).id();
        assertThatThrownBy(() -> store.restore(any, "deadbeef")).isInstanceOf(ConfigStore.ConflictException.class);
    }

    @Test
    void onlyTheNewestFiftyVersionsAreKept() throws Exception {
        for (int i = 0; i < 60; i++) {
            store.save(doc("Bulk " + i), store.state().sha256(), "ui", null);
        }
        assertThat(store.versions()).hasSize(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM config_versions", Integer.class)).isEqualTo(50);
        assertThat(store.versions().get(0).note()).isNull();
    }

    @Test
    void versionHistoryNeverContainsSecretValuesBecauseTheyCannotBeInTheFile() {
        assertThatThrownBy(() -> store.save(
                "version: 1\ngroups:\n  - name: A\n    items:\n      - {name: X, widget: {type: qbittorrent, url: \"http://x\", username: u, password: hunter2}}\n",
                store.state().sha256(), "ui", null)).isInstanceOf(ConfigStore.InvalidConfigException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM config_versions WHERE content LIKE '%hunter2%'", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void ofManyConcurrentSavesWithTheSameBaseExactlyOneWins() throws Exception {
        String base = store.state().sha256();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                jobs.add(() -> {
                    try {
                        store.save(doc("Racer " + n), base, "ui", null);
                        return "won";
                    } catch (ConfigStore.ConflictException e) {
                        return "conflict";
                    }
                });
            }
            List<String> results = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(jobs)) {
                results.add(f.get());
            }
            assertThat(results.stream().filter("won"::equals)).hasSize(1);
            assertThat(results.stream().filter("conflict"::equals)).hasSize(7);
            assertThat(Files.readString(file(), StandardCharsets.UTF_8)).isEqualTo(store.state().text());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void secretWarningsAppearInTheStateIssues() throws Exception {
        var s = store.save("version: 1\ngroups:\n  - name: A\n    items:\n      - {name: X, widget: {type: qbittorrent, url: \"http://x\", username: u, password: \"${secret:NOT_SET_ANYWHERE}\"}}\n",
                store.state().sha256(), "ui", null);
        assertThat(s.issues()).anySatisfy(i -> {
            assertThat(i.level()).isEqualTo(Issue.Level.WARNING);
            assertThat(i.message()).contains("NOT_SET_ANYWHERE");
        });
    }
}
