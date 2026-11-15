package com.homelabhub.docs.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Properties, folder names and the page shell. */
class GitHubSmallPartsTest {

    @Test
    void theSiteAddressDefaultsToTheOwnersGithubIoAndAlwaysEndsWithASlash() {
        assertThat(new GitHubProperties(true, "Octo/Showcase", null, "t", null, null, null).effectiveSiteUrl()).isEqualTo("https://octo.github.io/Showcase/");
        assertThat(new GitHubProperties(true, "octo/showcase", null, "t", "https://docs.example.com", null, null).effectiveSiteUrl()).isEqualTo("https://docs.example.com/");
        assertThat(new GitHubProperties(true, "octo/showcase", null, "t", "https://docs.example.com/", null, null).effectiveSiteUrl()).isEqualTo("https://docs.example.com/");
    }

    @Test
    void defaultsAreSafeAndTheTokenIsMasked() {
        GitHubProperties p = new GitHubProperties(null, "octo/showcase", " ", "ghp_secret_value", null, null, null);
        assertThat(p.enabled()).isFalse();
        assertThat(p.dryRun()).isFalse();
        assertThat(p.branch()).isEqualTo("gh-pages");
        assertThat(p.apiUrl()).isEqualTo("https://api.github.com");
        assertThat(p.toString()).doesNotContain("ghp_secret_value").contains("<masked>");
        assertThat(new GitHubProperties(true, "octo/showcase", null, "", null, null, null).toString()).contains("<unset>");
    }

    @Test
    void configErrorNamesWhatIsMissing() {
        assertThat(new GitHubProperties(true, "octo/showcase", null, "t", null, null, null).configError()).isNull();
        assertThat(new GitHubProperties(true, "", null, "t", null, null, null).configError()).contains("DOCS_GH_REPO");
        assertThat(new GitHubProperties(true, "no-slash", null, "t", null, null, null).configError()).contains("DOCS_GH_REPO");
        assertThat(new GitHubProperties(true, "octo/showcase", null, "", null, null, null).configError()).contains("DOCS_GH_TOKEN");
        assertThat(new GitHubProperties(true, "octo/showcase", null, "", null, true, null).configError()).as("a dry run needs no token").isNull();
        assertThat(new GitHubProperties(true, "octo/showcase", "a..b", "t", null, null, null).configError()).contains("DOCS_GH_BRANCH");
    }

    @Test
    void slugRules() {
        assertThat(GitHubSlug.check("  My-Page-1 ").slug()).isEqualTo("my-page-1");
        assertThat(GitHubSlug.check("b").ok()).isTrue();
        assertThat(GitHubSlug.check("a".repeat(63)).ok()).isTrue();
        for (String bad : List.of("", " ", "-start", "has space", "under_score", "dot.dot", "slash/x", "ünï", "a".repeat(64), "../x")) {
            assertThat(GitHubSlug.check(bad).ok()).as(bad).isFalse();
        }
        for (String reserved : List.of("viewer", "portfolio", "assets", "a", "app", "api", "static", "index", "Viewer")) {
            GitHubSlug.Result r = GitHubSlug.check(reserved);
            assertThat(r.ok()).as(reserved).isFalse();
            assertThat(r.error()).contains("used by the site itself");
        }
        assertThat(GitHubSlug.check(null).ok()).isFalse();
    }

    @Test
    void theDocShellPointsAtTheSharedViewerTheJsonAndEscapesTheTitle() {
        String viewer = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Docs</title>"
                + "<script src=\"./assets/app.js\"></script><link href=\"./assets/app.css\"></head><body></body></html>";
        String html = StaticSite.docShell(viewer, "Fish & <chips> \"2\"");
        assertThat(html).contains("<head>\n<meta name=\"static-data\" content=\"page.json\">\n<meta name=\"robots\" content=\"noindex\">");
        assertThat(html).contains("src=\"../viewer/assets/app.js\"").contains("href=\"../viewer/assets/app.css\"").doesNotContain("\"./assets/");
        assertThat(html).contains("<title>Fish &amp; &lt;chips&gt; &quot;2&quot;</title>").doesNotContain("<title>Docs</title>");
        assertThat(html).containsOnlyOnce("<title>");
    }

    @Test
    void aShellWithoutATitleOrWithAttributesOnHeadStillWorks() {
        String html = StaticSite.docShell("<html><HEAD lang=\"en\"></HEAD><body></body></html>", "T");
        assertThat(html).contains("<title>T</title>").contains("name=\"static-data\"").contains("<HEAD lang=\"en\">\n");
    }

    @Test
    void thePortfolioShellOnlyGainsTheDataTag() {
        String index = "<html><head><title>Portfolio</title><script src=\"./assets/p.js\"></script></head></html>";
        String html = StaticSite.portfolioShell(index);
        assertThat(html).contains("<meta name=\"static-data\" content=\"portfolio.json\">").contains("./assets/p.js").contains("<title>Portfolio</title>")
                .doesNotContain("noindex");
        assertThat(html.replace("<meta name=\"static-data\" content=\"portfolio.json\">\n", "").replace("\n<", "<")).isEqualTo(index);
    }

    @Test
    void extensionsComeFromTheMimeTypeFirstThenTheName() {
        assertThat(StaticSite.extension("image/png", "x.jpeg")).isEqualTo("png");
        assertThat(StaticSite.extension("application/x-weird", "Report.DOCX")).isEqualTo("docx");
        assertThat(StaticSite.extension("application/x-weird", "no-extension")).isEqualTo("bin");
        assertThat(StaticSite.extension("application/x-weird", "bad.ext/../x")).isEqualTo("bin");
    }

    @Test
    void unsafeBundlePathsAreRefused() {
        for (String bad : List.of("../x", "/abs", "a//b", "a/../b", "a\\b", "", "./x")) {
            assertThat(StaticSite.safePath(bad)).as(bad).isFalse();
        }
        assertThat(StaticSite.safePath("assets/app.js")).isTrue();
    }
}
