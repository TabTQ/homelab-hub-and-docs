package com.homelabhub.pub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EtagMatchTest {

    private static final String ETAG = "\"abc123\"";

    @Test
    void matchesExactStrongValue() {
        assertThat(PublicController.etagMatches("\"abc123\"", ETAG)).isTrue();
    }

    @Test
    void matchesWeakenedValueAddedByProxiesOrCompression() {
        assertThat(PublicController.etagMatches("W/\"abc123\"", ETAG)).isTrue();
    }

    @Test
    void matchesWithinACommaSeparatedList() {
        assertThat(PublicController.etagMatches("\"zzz\", W/\"abc123\" , \"yyy\"", ETAG)).isTrue();
    }

    @Test
    void wildcardMatches() {
        assertThat(PublicController.etagMatches("*", ETAG)).isTrue();
    }

    @Test
    void differentOrMissingValueDoesNotMatch() {
        assertThat(PublicController.etagMatches("\"other\"", ETAG)).isFalse();
        assertThat(PublicController.etagMatches(null, ETAG)).isFalse();
        assertThat(PublicController.etagMatches("", ETAG)).isFalse();
    }
}
