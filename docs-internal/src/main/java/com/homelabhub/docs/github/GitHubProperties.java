package com.homelabhub.docs.github;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * docs.github.* settings. Off by default: with {@code enabled=false} nothing here talks to GitHub. The token is a fine-grained
 * personal access token for the one repository, with Contents read and write and Pages read.
 */
@ConfigurationProperties(prefix = "docs.github")
public record GitHubProperties(Boolean enabled, String repo, String branch, String token, String siteUrl, Boolean dryRun, String apiUrl) {

    public static final String DEFAULT_API_URL = "https://api.github.com";
    public static final String DEFAULT_BRANCH = "gh-pages";
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,100}");

    public GitHubProperties {
        enabled = enabled != null && enabled;
        dryRun = dryRun != null && dryRun;
        repo = blank(repo) ? "" : repo.strip();
        branch = blank(branch) ? DEFAULT_BRANCH : branch.strip();
        token = blank(token) ? "" : token.strip();
        siteUrl = blank(siteUrl) ? "" : siteUrl.strip();
        apiUrl = blank(apiUrl) ? DEFAULT_API_URL : apiUrl.strip().replaceAll("/+$", "");
    }

    /** @return what is missing for GitHub publishing to run, or null when it is fully configured (the token is not needed for a dry run) */
    public String configError() {
        if (!REPO.matcher(repo).matches()) return "DOCS_GH_REPO must look like owner/name";
        if (!BRANCH.matcher(branch).matches() || branch.contains("..") || branch.startsWith("/") || branch.endsWith("/")) {
            return "DOCS_GH_BRANCH is not a valid branch name";
        }
        if (token.isEmpty() && !dryRun) return "DOCS_GH_TOKEN is not set";
        return null;
    }

    public String owner() {
        int i = repo.indexOf('/');
        return i < 0 ? "" : repo.substring(0, i);
    }

    public String repoName() {
        int i = repo.indexOf('/');
        return i < 0 ? "" : repo.substring(i + 1);
    }

    /** The public address of the site, always ending with a slash. */
    public String effectiveSiteUrl() {
        if (!siteUrl.isEmpty()) {
            return siteUrl.endsWith("/") ? siteUrl : siteUrl + "/";
        }
        if (owner().isEmpty()) {
            return "";
        }
        return "https://" + owner().toLowerCase(Locale.ROOT) + ".github.io/" + repoName() + "/";
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "GitHubProperties[enabled=" + enabled + ", dryRun=" + dryRun + ", repo=" + repo + ", branch=" + branch
                + ", token=" + (token.isEmpty() ? "<unset>" : "<masked>") + "]";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
