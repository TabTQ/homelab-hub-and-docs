package com.homelabhub.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HostRulesTest {

    final HostRules any = new HostRules(List.of());
    final HostRules limited = new HostRules(List.of("domain.com", " docs.example.org ", "*.wild.example"));

    @Test
    void uppercaseAndTrailingDotAreNormalised() {
        assertThat(any.check("Page1.Domain.COM.").host()).isEqualTo("page1.domain.com");
        assertThat(any.check("  tunnel.domain.com ").ok()).isTrue();
    }

    @Test
    void unicodeBecomesPunycode() {
        HostRules.Result r = any.check("bücher.domain.com");
        assertThat(r.ok()).isTrue();
        assertThat(r.host()).isEqualTo("xn--bcher-kva.domain.com");
    }

    @Test
    void labelsAreLimitedTo63Characters() {
        assertThat(any.check("a".repeat(63) + ".domain.com").ok()).isTrue();
        assertThat(any.check("a".repeat(64) + ".domain.com").ok()).isFalse();
        assertThat(any.check(("a".repeat(60) + ".").repeat(5) + "com").ok()).isFalse(); // over 253
    }

    @Test
    void rejectsSchemesPortsPathsAndBadCharacters() {
        for (String bad : List.of("", " ", "https://x.domain.com", "x.domain.com:443", "x.domain.com/path", "user@x.domain.com",
                "-x.domain.com", "x-.domain.com", "x..domain.com", "x_y.domain.com", "x y.domain.com", "*.domain.com",
                "domain.com", "localhost", "10.0.0.1", "1.2.3.4")) {
            assertThat(any.check(bad).ok()).as(bad).isFalse();
        }
    }

    @Test
    void suffixMustBeOneOfTheDomains() {
        assertThat(limited.check("page.domain.com").ok()).isTrue();
        assertThat(limited.check("a.b.docs.example.org").ok()).isTrue();
        assertThat(limited.check("x.wild.example").ok()).isTrue();
        assertThat(limited.check("page.evil-domain.com").ok()).isFalse();
        assertThat(limited.check("pagedomain.com.evil.net").ok()).isFalse();
        assertThat(limited.check("page.domain.com.evil.net").error()).contains(".domain.com");
        assertThat(limited.domains()).containsExactly("domain.com", "docs.example.org", "wild.example");
    }
}
