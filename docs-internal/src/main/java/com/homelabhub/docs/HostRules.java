package com.homelabhub.docs;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Public hostnames (D2, D16). One page per hostname, so the rules are strict: lowercase ASCII (punycode for IDNs), at least
 * three labels (a sub-domain of a domain), labels of 1-63 letters, digits and inner hyphens, at most 253 characters, and, when
 * DOCS_DOMAINS is set, a strict sub-domain of one of those domains.
 */
public final class HostRules {

    public record Result(String host, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final List<String> domains;

    public HostRules(Collection<String> domains) {
        List<String> d = new ArrayList<>();
        if (domains != null) {
            for (String s : domains) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                Result r = normalizeSyntax(s.strip().replaceFirst("^\\*\\.", "").replaceFirst("^\\.", ""), 2);
                if (r.ok()) {
                    d.add(r.host());
                }
            }
        }
        this.domains = List.copyOf(d);
    }

    public List<String> domains() {
        return domains;
    }

    /** Checks the syntax and the allowed suffix. Uniqueness is the database's job. */
    public Result check(String input) {
        Result r = normalizeSyntax(input, 3);
        if (!r.ok() || domains.isEmpty()) {
            return r;
        }
        for (String d : domains) {
            if (r.host().endsWith("." + d)) {
                return r;
            }
        }
        return new Result(r.host(), "The hostname must end with " + String.join(" or ", domains.stream().map(x -> "." + x).toList()) + ".");
    }

    static Result normalizeSyntax(String input, int minLabels) {
        if (input == null || input.isBlank()) {
            return new Result(null, "Enter a hostname.");
        }
        String s = input.strip();
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.contains("://") || s.contains("/") || s.contains(":") || s.contains("@") || s.contains(" ")) {
            return new Result(null, "Enter only the hostname, such as page1.domain.com (no https://, port or path).");
        }
        String ascii;
        try {
            ascii = IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return new Result(null, "That is not a valid hostname.");
        }
        if (ascii.length() > 253) {
            return new Result(null, "The hostname is longer than 253 characters.");
        }
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < minLabels) {
            return new Result(null, minLabels > 2 ? "Use a sub-domain, such as page1.domain.com." : "That is not a valid domain.");
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                return new Result(null, "Each part of the hostname must be 1 to 63 characters.");
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                return new Result(null, "A part of the hostname cannot start or end with a hyphen.");
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                    return new Result(null, "Use only letters, digits and hyphens.");
                }
            }
        }
        if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
            return new Result(null, "An IP address cannot be a public page address.");
        }
        return new Result(ascii, null);
    }
}
