package com.homelabhub.docs.github;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The folder name of a page on the Pages site: lower-case letters, digits and hyphens, and not a name the site itself uses. */
public final class GitHubSlug {

    public record Result(String slug, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    public static final Set<String> RESERVED = Set.of("viewer", "portfolio", "assets", "a", "app", "api", "static", "index");

    private GitHubSlug() {}

    /** Trims and lower-cases {@code input}, then checks it. Uniqueness is the database's job. */
    public static Result check(String input) {
        if (input == null || input.isBlank()) {
            return new Result(null, "Enter a folder name.");
        }
        String s = input.strip().toLowerCase(Locale.ROOT);
        if (s.length() > 63) {
            return new Result(null, "The folder name is longer than 63 characters.");
        }
        if (!PATTERN.matcher(s).matches()) {
            return new Result(null, "Use only lower-case letters, digits and hyphens, and start with a letter or digit.");
        }
        if (RESERVED.contains(s)) {
            return new Result(null, "\"" + s + "\" is used by the site itself. Choose another name.");
        }
        return new Result(s, null);
    }
}
