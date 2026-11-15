package com.homelabhub.docs.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homelabhub.docs.DocsAudit;
import com.homelabhub.docs.github.GitHubSync.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** The sync against a fake GitHub: first publish, no-op runs, removals, errors and retry, the Pages build, dry run and secrecy of the token. */
class GitHubSyncTest {

    FakeGitHub gh;
    DocsAudit audit;
    final AtomicReference<Map<String, SiteContent>> site = new AtomicReference<>();
    ListAppender<ILoggingEvent> logs;
    Path tmp;

    static SiteContent text(String s) {
        return SiteContent.of(s.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, SiteContent> site(String... pairs) {
        Map<String, SiteContent> m = new TreeMap<>();
        m.put(".nojekyll", SiteContent.of(new byte[0]));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], text(pairs[i + 1]));
        }
        return m;
    }

    @BeforeEach
    void start() throws Exception {
        gh = new FakeGitHub();
        audit = mock(DocsAudit.class);
        tmp = Files.createTempDirectory("gh-sync-test");
        site.set(site("viewer/assets/app.js", "js", "one/index.html", "<p>one</p>", "two/index.html", "<p>two</p>"));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
        ((Logger) LoggerFactory.getLogger("com.homelabhub.docs.github")).setLevel(Level.DEBUG);
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        gh.close();
    }

    GitHubSync sync() {
        return sync(gh.props());
    }

    GitHubSync sync(GitHubProperties p) {
        GitHubClient client = p.enabled() && !p.dryRun() ? new GitHubClient(p) : null;
        return new GitHubSync(p, client, () -> site.get(), tmp.resolve("dry"), audit, 10, 400);
    }

    /** Waits for the run that was just requested to end in live or error. */
    void settle(GitHubSync s, State expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(s.status().state()).isEqualTo(expected));
    }

    @Test
    void firstPublishToAMissingBranchCreatesAnOrphanCommit() {
        GitHubSync s = sync();
        assertThat(s.status().state()).isEqualTo(State.IDLE);
        s.requestSync();
        assertThat(s.status().state()).as("queued at once").isEqualTo(State.PENDING);
        settle(s, State.LIVE);

        assertThat(gh.commitParents).containsExactly(0);
        assertThat(gh.files().keySet()).containsExactly(".nojekyll", "one/index.html", "two/index.html", "viewer/assets/app.js");
        assertThat(gh.text("one/index.html")).isEqualTo("<p>one</p>");
        assertThat(gh.files().get(".nojekyll")).isEmpty();
        assertThat(gh.badAuth).isEmpty();
        assertThat(s.status().files()).isEqualTo(4);
        assertThat(s.toMap()).containsEntry("state", "live").containsEntry("enabled", true).containsEntry("siteUrl", "https://octo.github.io/showcase/");
        verify(audit, atLeast(1)).record(eq("github-sync"), eq("octo/showcase"), eq("ok"), anyString(), any(), any());
    }

    @Test
    void aBranchThisAppDidNotWriteIsLeftAlone() {
        Map<String, SiteContent> foreign = new TreeMap<>();
        foreign.put("README.md", text("source code lives here"));
        site.set(foreign);
        GitHubSync first = sync();
        first.requestSync();
        settle(first, State.LIVE);
        long commits = gh.commits();

        site.set(site("one/index.html", "<p>one</p>"));
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.ERROR);

        assertThat(s.status().message()).contains("already holds other files");
        assertThat(gh.commits()).isEqualTo(commits);
        assertThat(gh.files().keySet()).containsExactly("README.md");
    }

    @Test
    void anUnchangedTreeMakesNoCommitAndNoUpload() {
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.LIVE);
        long blobs = gh.blobUploads(), commits = gh.commits(), trees = gh.count("POST /repos/octo/showcase/git/trees");

        s.requestSync();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> gh.count("GET /repos/octo/showcase/git/trees/") >= 1);
        settle(s, State.LIVE);

        assertThat(gh.blobUploads()).isEqualTo(blobs);
        assertThat(gh.commits()).isEqualTo(commits);
        assertThat(gh.count("POST /repos/octo/showcase/git/trees")).isEqualTo(trees);
        assertThat(gh.count("PATCH ")).isZero();
        assertThat(s.status().message()).startsWith("Up to date.");
    }

    @Test
    void aChangeUploadsOnlyTheNewBlobAndARemovedPageLeavesTheTree() {
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.LIVE);
        long first = gh.blobUploads();
        assertThat(first).as("four files, two with distinct content each").isEqualTo(4);

        Map<String, SiteContent> next = new TreeMap<>(site.get());
        next.put("one/index.html", text("<p>one, edited</p>"));
        next.remove("two/index.html");
        site.set(next);
        s.requestSync();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> gh.commits() == 2);
        settle(s, State.LIVE);

        assertThat(gh.blobUploads() - first).as("only the edited file is new").isEqualTo(1);
        assertThat(gh.files().keySet()).containsExactly(".nojekyll", "one/index.html", "viewer/assets/app.js");
        assertThat(gh.text("one/index.html")).isEqualTo("<p>one, edited</p>");
        assertThat(gh.commitParents).as("no history").containsExactly(0, 0);
        assertThat(gh.forced).isTrue();
    }

    @Test
    void theSameContentTwiceIsUploadedOnce() {
        site.set(site("a/index.html", "same", "b/index.html", "same"));
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.LIVE);
        assertThat(gh.blobUploads()).as("the empty file and one shared file").isEqualTo(2);
        assertThat(gh.files().keySet()).containsExactly(".nojekyll", "a/index.html", "b/index.html");
    }

    @Test
    void anErrorKeepsTheStateAndARetryRecovers() {
        gh.failPrefix = "POST /repos/octo/showcase/git/blobs";
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.ERROR);
        assertThat(s.status().message()).contains("500").contains("boom");
        assertThat(gh.branchCommit).isNull();

        gh.failPrefix = null;
        Map<String, Object> m = s.retry();
        assertThat(m.get("state")).isEqualTo("pending");
        settle(s, State.LIVE);
        assertThat(gh.files()).isNotEmpty();
    }

    @Test
    void theTimerRetriesAFailedRunByItself() {
        gh.failPrefix = "POST /repos/octo/showcase/git/trees";
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.ERROR);
        gh.failPrefix = null;
        s.retryErrors();
        settle(s, State.LIVE);
        assertThat(gh.branchCommit).isNotNull();
    }

    @Test
    void theTokenNeverAppearsInAMessageAStatusAnAuditEntryOrALogLine() {
        // GitHub answering with the token in its own error text is the worst case: it must be scrubbed.
        gh.failPrefix = "GET /repos/octo/showcase/git/ref";
        gh.failStatus = 401;
        gh.failBody = "{\"message\":\"Bad credentials for " + FakeGitHub.TOKEN + "\"}";
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.ERROR);

        assertThat(s.status().message()).contains("401").doesNotContain(FakeGitHub.TOKEN);
        assertThat(s.toMap().toString()).doesNotContain(FakeGitHub.TOKEN);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeast(1)).record(anyString(), anyString(), anyString(), detail.capture(), any(), any());
        assertThat(detail.getAllValues().toString()).doesNotContain(FakeGitHub.TOKEN);
        assertThat(s.toString()).doesNotContain(FakeGitHub.TOKEN);
        assertThat(gh.props().toString()).doesNotContain(FakeGitHub.TOKEN);

        GitHubException seen = null;
        try {
            new GitHubClient(gh.props()).readBranch();
        } catch (GitHubClient.GitHubException e) {
            seen = new GitHubException(e);
        }
        assertThat(seen).isNotNull();
        assertThat(seen.text).doesNotContain(FakeGitHub.TOKEN);

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : new ArrayList<>(logs.list)) {
            lines.add(e.getFormattedMessage() + " " + e.getThrowableProxy());
        }
        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines)).doesNotContain(FakeGitHub.TOKEN);
    }

    /** Collects what an exception shows: its message and its cause chain. */
    static final class GitHubException {
        final String text;

        GitHubException(Throwable t) {
            StringBuilder b = new StringBuilder();
            for (Throwable x = t; x != null; x = x.getCause()) {
                b.append(x).append(' ');
            }
            text = b.toString();
        }
    }

    @Test
    void theBuildIsWatchedFromBuildingToLive() {
        gh.pages("building", "building", "built");
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.LIVE);
        assertThat(gh.count("GET /repos/octo/showcase/pages/builds/latest")).isEqualTo(3);
        assertThat(s.status().message()).contains("The site is live.");
    }

    @Test
    void aFailedPagesBuildIsAnError() {
        gh.pages("errored");
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.ERROR);
        assertThat(s.status().message()).contains("could not build").contains("Page build failed.");
    }

    @Test
    void whenThePagesApiIsNotAvailableTheFilesAreSentAndTheStateIsLive() {
        gh.pagesCode = 403;
        gh.pagesStatuses = null;
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.LIVE);
        assertThat(s.status().message()).contains("Sent 4 files.");
    }

    @Test
    void aBuildThatNeverReportsEndsAsLiveWithAnHonestMessage() {
        gh.pages("building");
        GitHubSync s = sync();
        s.requestSync();
        settle(s, State.LIVE);
        assertThat(s.status().message()).contains("has not confirmed the build yet");
    }

    @Test
    void manyRequestsAreMergedAndTheLastOneWins() {
        GitHubSync s = sync();
        for (int i = 0; i < 5; i++) {
            s.requestSync();
        }
        site.set(site("final/index.html", "<p>final</p>"));
        s.requestSync();
        settle(s, State.LIVE);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(gh.files().keySet()).contains("final/index.html"));
        assertThat(gh.commits()).isLessThanOrEqualTo(2);
    }

    @Test
    void aDryRunWritesTheTreeToDiskAndSendsNothing() throws Exception {
        GitHubProperties p = new GitHubProperties(true, "octo/showcase", "gh-pages", "", null, true, gh.url());
        GitHubSync s = sync(p);
        s.requestSync();
        settle(s, State.LIVE);
        assertThat(gh.requests).isEmpty();
        assertThat(Files.readString(tmp.resolve("dry/one/index.html"))).isEqualTo("<p>one</p>");
        assertThat(Files.exists(tmp.resolve("dry/.nojekyll"))).isTrue();
        assertThat(s.status().message()).startsWith("Dry run:");

        site.set(site("only/index.html", "x"));
        s.requestSync();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(tmp.resolve("dry/one")));
        assertThat(Files.exists(tmp.resolve("dry/only/index.html"))).isTrue();
        assertThat(s.toMap()).containsEntry("dryRun", true);
    }

    @Test
    void whenDisabledNothingRuns() {
        GitHubProperties p = new GitHubProperties(false, "octo/showcase", "gh-pages", FakeGitHub.TOKEN, null, false, gh.url());
        GitHubSync s = sync(p);
        s.requestSync();
        s.retry();
        assertThat(s.status().state()).isEqualTo(State.OFF);
        assertThat(s.toMap()).containsEntry("state", "off").containsEntry("enabled", false).containsEntry("siteUrl", null);
        assertThat(gh.requests).isEmpty();
    }

    @Test
    void aMissingConfigurationIsAnErrorNotACrash() {
        GitHubProperties p = new GitHubProperties(true, "", "gh-pages", FakeGitHub.TOKEN, null, false, gh.url());
        GitHubSync s = new GitHubSync(p, null, () -> site.get(), tmp.resolve("dry"), audit, 10, 100);
        s.requestSync();
        settle(s, State.ERROR);
        assertThat(s.status().message()).contains("DOCS_GH_REPO");
        assertThat(gh.requests).isEmpty();
    }

    @Test
    void aSiteThatCannotBeBuiltIsAnErrorAndRequestSyncNeverThrows() {
        GitHubSync s = new GitHubSync(gh.props(), new GitHubClient(gh.props()), () -> {
            throw new IOException("disk gone");
        }, tmp.resolve("dry"), audit, 10, 100);
        s.requestSync();
        settle(s, State.ERROR);
        assertThat(s.status().message()).contains("disk gone");
    }
}
