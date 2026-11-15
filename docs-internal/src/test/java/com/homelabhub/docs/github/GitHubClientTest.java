package com.homelabhub.docs.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homelabhub.docs.github.GitHubClient.GitHubException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubClientTest {

    FakeGitHub gh;
    GitHubClient client;

    @BeforeEach
    void start() throws Exception {
        gh = new FakeGitHub();
        client = new GitHubClient(gh.props());
    }

    @AfterEach
    void stop() {
        gh.close();
    }

    @Test
    void aMissingBranchIsEmptyNotAnError() {
        assertThat(client.readBranch()).isEmpty();
    }

    @Test
    void aBigFileIsStreamedAsBase64AndKeepsItsGitId() throws Exception {
        byte[] data = new byte[5 * 1024 * 1024 + 7];
        new Random(42).nextBytes(data);
        Path f = Files.createTempFile("gh-client-test", ".bin");
        try {
            Files.write(f, data);
            SiteContent c = SiteContent.of(f);
            String sha = client.createBlob(c);
            assertThat(sha).isEqualTo(c.gitBlobSha()).isEqualTo(FakeGitHub.gitSha(data));
            assertThat(gh.blobs.get(sha)).isEqualTo(data);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void anEmptyFileAndASmallOneRoundTrip() throws Exception {
        assertThat(client.createBlob(SiteContent.of(new byte[0]))).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
        byte[] three = {1, 2, 3};
        assertThat(client.createBlob(SiteContent.of(three))).isEqualTo(FakeGitHub.gitSha(three));
        assertThat(gh.blobs.get(FakeGitHub.gitSha(three))).isEqualTo(three);
    }

    @Test
    void treeCommitAndBranchRoundTripAndTheBranchCanBeMovedWithForce() throws Exception {
        String blob = client.createBlob(SiteContent.of(new byte[] {7}));
        String tree = client.createTree(List.of(new GitHubClient.TreeEntry("x/y.txt", blob)));
        String commit = client.createCommit("first", tree);
        client.moveBranch(commit, false);
        GitHubClient.Remote r = client.readBranch().orElseThrow();
        assertThat(r.commitSha()).isEqualTo(commit);
        assertThat(r.files()).containsEntry("x/y.txt", blob);
        assertThat(gh.commitParents).containsExactly(0);

        String commit2 = client.createCommit("second", tree);
        client.moveBranch(commit2, true);
        assertThat(client.readBranch().orElseThrow().commitSha()).isEqualTo(commit2);
        assertThat(gh.forced).isTrue();
    }

    @Test
    void creatingABranchThatAppearedMeanwhileFallsBackToMovingIt() throws Exception {
        String blob = client.createBlob(SiteContent.of(new byte[] {7}));
        String tree = client.createTree(List.of(new GitHubClient.TreeEntry("a", blob)));
        client.moveBranch(client.createCommit("one", tree), false);
        String two = client.createCommit("two", tree);
        client.moveBranch(two, false); // 422 on create, then PATCH
        assertThat(gh.branchCommit).isEqualTo(two);
    }

    @Test
    void errorsCarryTheStatusAndNeverTheToken() {
        gh.failPrefix = "POST ";
        gh.failStatus = 401;
        gh.failBody = "{\"message\":\"Bad credentials: " + FakeGitHub.TOKEN + "\"}";
        assertThatThrownBy(() -> client.createTree(List.of()))
                .isInstanceOf(GitHubException.class).hasMessageContaining("401").hasMessageContaining("DOCS_GH_TOKEN")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(FakeGitHub.TOKEN));
        gh.failStatus = 404;
        assertThatThrownBy(() -> client.createTree(List.of())).isInstanceOf(GitHubException.class).hasMessageContaining("DOCS_GH_REPO");
    }

    @Test
    void anUnreachableServerIsAClearErrorWithStatusZero() {
        GitHubProperties p = new GitHubProperties(true, "octo/showcase", "gh-pages", FakeGitHub.TOKEN, null, false, "http://127.0.0.1:1");
        assertThatThrownBy(() -> new GitHubClient(p).readBranch()).isInstanceOf(GitHubException.class).hasMessageContaining("not reachable")
                .satisfies(e -> {
                    assertThat(((GitHubException) e).status()).isZero();
                    assertThat(e.getMessage()).doesNotContain(FakeGitHub.TOKEN);
                });
    }

    @Test
    void thePagesBuildIsReadAndAnUnavailableApiIsAStatusCode() {
        gh.pages("built");
        gh.branchCommit = "c1";
        assertThat(client.latestBuild().status()).isEqualTo("built");
        gh.pagesStatuses = null;
        gh.pagesCode = 404;
        assertThatThrownBy(() -> client.latestBuild()).isInstanceOf(GitHubException.class).satisfies(e -> assertThat(((GitHubException) e).status()).isEqualTo(404));
    }
}
