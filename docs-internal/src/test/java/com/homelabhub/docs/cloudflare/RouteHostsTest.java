package com.homelabhub.docs.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteHostsTest {

    final RouteHosts rules = new RouteHosts("example.test", List.of("portfolio", "n8n-api"));

    @Test
    void acceptsASingleLowercaseLabelUnderTheZone() {
        assertThat(rules.error("doc1.example.test")).isNull();
        assertThat(rules.error("a.example.test")).isNull();
        assertThat(rules.error("my-doc-2.example.test")).isNull();
        assertThat(rules.error("a".repeat(63) + ".example.test")).isNull();
    }

    @Test
    void rejectsWildcardsTheApexAndOtherZones() {
        assertThat(rules.error("*.example.test")).contains("Wildcard");
        assertThat(rules.error("*")).contains("Wildcard");
        assertThat(rules.error("example.test")).contains("main domain");
        assertThat(rules.error("doc.other.test")).contains("must end with");
        assertThat(rules.error("docexample.test")).contains("must end with");
        assertThat(rules.error("")).isNotNull();
        assertThat(rules.error(null)).isNotNull();
    }

    @Test
    void rejectsDeeperNamesAndBadLabels() {
        assertThat(rules.error("a.b.example.test")).contains("single-level");
        assertThat(rules.error("A.example.test")).isNotNull();
        assertThat(rules.error("-a.example.test")).isNotNull();
        assertThat(rules.error("a-.example.test")).isNotNull();
        assertThat(rules.error("a b.example.test")).isNotNull();
        assertThat(rules.error("a_b.example.test")).isNotNull();
        assertThat(rules.error("a".repeat(64) + ".example.test")).isNotNull();
        assertThat(rules.error(".example.test")).isNotNull();
    }

    @Test
    void reservedLabelsAreNeverClaimable() {
        for (String l : List.of("www", "vpn", "email", "mail", "smtp", "ftp", "autodiscover", "_domainconnect", "portfolio", "n8n-api")) {
            assertThat(rules.error(l + ".example.test")).as(l).isNotNull();
        }
        assertThat(rules.error("_anything.example.test")).contains("_");
        assertThat(rules.error("_dmarc.example.test")).isNotNull();
    }
}
