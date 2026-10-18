package com.homelabhub.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostAllowlistTest {

    @Test
    void alwaysAllowsLocalhost() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows("localhost")).isTrue();
        assertThat(allowlist.allows("LOCALHOST")).isTrue();
        assertThat(allowlist.allows("localhost:3010")).isTrue();
        assertThat(allowlist.allows("LOCALHOST:3010")).isTrue();
    }

    @Test
    void alwaysAllowsLiteralIpv4() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows("192.168.10.101")).isTrue();
        assertThat(allowlist.allows("192.168.10.101:3010")).isTrue();
        assertThat(allowlist.allows("0.0.0.0")).isTrue();
        assertThat(allowlist.allows("255.255.255.255")).isTrue();
    }

    @Test
    void rejectsInvalidIpv4Literal() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows("999.1.1.1")).isFalse();
        assertThat(allowlist.allows("999.1.1.1:3010")).isFalse();
        assertThat(allowlist.allows("192.168.1")).isFalse();
        assertThat(allowlist.allows("192.168.1.256")).isFalse();
    }

    @Test
    void alwaysAllowsBracketedIpv6Literal() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows("[::1]")).isTrue();
        assertThat(allowlist.allows("[::1]:3010")).isTrue();
        assertThat(allowlist.allows("[::]")).isTrue();
        assertThat(allowlist.allows("[2001:db8::1]:8080")).isTrue();
    }

    @Test
    void rejectsMalformedIpv6() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows("[::1")).isFalse();
        assertThat(allowlist.allows("[::1]bad")).isFalse();
        assertThat(allowlist.allows("::1")).isFalse();
        assertThat(allowlist.allows("[12345::1]")).isFalse();
    }

    @Test
    void configuredNameWithAndWithoutPort() {
        HostAllowlist allowlist = new HostAllowlist(List.of("pi.local"));
        assertThat(allowlist.allows("pi.local")).isTrue();
        assertThat(allowlist.allows("pi.local:8080")).isTrue();
        assertThat(allowlist.allows("PI.LOCAL")).isTrue();
    }

    @Test
    void trailingDotIsIgnored() {
        HostAllowlist allowlist = new HostAllowlist(List.of("pi.local"));
        assertThat(allowlist.allows("Pi.local.")).isTrue();
        assertThat(allowlist.allows("localhost.")).isTrue();
    }

    @Test
    void wildcardRequiresRealLabelBeforeSuffix() {
        HostAllowlist allowlist = new HostAllowlist(List.of("*.ts.net"));
        assertThat(allowlist.allows("pi.tail1234.ts.net")).isTrue();
        assertThat(allowlist.allows("ts.net")).isFalse();
        assertThat(allowlist.allows("evilts.net")).isFalse();
        assertThat(allowlist.allows("a.b.ts.net")).isTrue();
    }

    @Test
    void foreignHostsAreRejected() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows("evil.com")).isFalse();
        assertThat(allowlist.allows("192.168.10.101.evil.com")).isFalse();
        assertThat(allowlist.allows("dns-rebinding-attack.evil.com:80")).isFalse();
    }

    @Test
    void rejectsHeaderInjection() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows(null)).isFalse();
        assertThat(allowlist.allows("")).isFalse();
        assertThat(allowlist.allows("   ")).isFalse();
        assertThat(allowlist.allows("pi.local@evil.com")).isFalse();
        assertThat(allowlist.allows("pi.local/evil")).isFalse();
        assertThat(allowlist.allows("pi.local\\evil")).isFalse();
        assertThat(allowlist.allows("a?b")).isFalse();
        assertThat(allowlist.allows("a#b")).isFalse();
        assertThat(allowlist.allows("a b")).isFalse();
        assertThat(allowlist.allows("pi.local\nXSS")).isFalse();
    }

    @Test
    void rejectsInvalidPorts() {
        HostAllowlist allowlist = new HostAllowlist(List.of("pi.local"));
        assertThat(allowlist.allows("pi.local:0")).isFalse();
        assertThat(allowlist.allows("pi.local:99999")).isFalse();
        assertThat(allowlist.allows("pi.local:abc")).isFalse();
        assertThat(allowlist.allows("pi.local:")).isFalse();
        assertThat(allowlist.allows("pi.local:8080x")).isFalse();
        assertThat(allowlist.allows("pi.local:0")).isFalse();
    }

    @Test
    void rejectsHeadersLongerThan255Chars() {
        HostAllowlist allowlist = new HostAllowlist(List.of());
        assertThat(allowlist.allows("a".repeat(255))).isFalse();
        assertThat(allowlist.allows("a".repeat(256))).isFalse();
    }

    @Test
    void ignoresBlankOrSchemedConfiguredEntries() {
        HostAllowlist allowlist = new HostAllowlist(Arrays.asList(null, "", "  ", "http://pi.local", "pi.local:3010", "good.example", "*.good"));
        assertThat(allowlist.allows("good.example")).isTrue();
        assertThat(allowlist.allows("pi.local")).isFalse();
        assertThat(allowlist.allows("api.good")).isTrue();
        assertThat(allowlist.allows("http://pi.local")).isFalse();
    }
}