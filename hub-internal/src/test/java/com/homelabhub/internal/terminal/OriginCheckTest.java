package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.auth.HostAllowlist;
import java.util.List;
import org.junit.jupiter.api.Test;

class OriginCheckTest {

    final HostAllowlist allow = new HostAllowlist(List.of("pi.example-tailnet.ts.net", "*.lan.example"));

    @Test
    void sameHostIsAllowedWhateverTheScheme() {
        assertThat(OriginCheck.allowed("http://192.168.1.5:3010", "192.168.1.5:3010", allow)).isTrue();
        assertThat(OriginCheck.allowed("http://localhost:3010", "localhost:3010", allow)).isTrue();
        // tailscale serve: https in the browser, Host passed through without a port
        assertThat(OriginCheck.allowed("https://pi.example-tailnet.ts.net", "pi.example-tailnet.ts.net", allow)).isTrue();
        assertThat(OriginCheck.allowed("http://[::1]:3010", "[::1]:3010", allow)).isTrue();
    }

    @Test
    void aProxyThatRewritesTheHostStillWorksForConfiguredNames() {
        assertThat(OriginCheck.allowed("https://pi.example-tailnet.ts.net", "127.0.0.1:3010", allow)).isTrue();
        assertThat(OriginCheck.allowed("https://hub.lan.example", "127.0.0.1:3010", allow)).isTrue();
    }

    @Test
    void otherSitesAreRefused() {
        assertThat(OriginCheck.allowed("https://evil.example", "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("http://1.2.3.4", "192.168.1.5:3010", allow)).as("an IP is only trusted when it IS the host").isFalse();
        assertThat(OriginCheck.allowed("http://192.168.1.5:8081", "192.168.1.5:3010", allow)).as("another port on the same machine").isFalse();
        assertThat(OriginCheck.allowed("http://localhost:3010", "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("https://pi.example-tailnet.ts.net.evil.example", "127.0.0.1:3010", allow)).isFalse();
    }

    @Test
    void missingOrMalformedOriginsAreRefused() {
        assertThat(OriginCheck.allowed(null, "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("", "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("null", "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("file://", "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("http://192.168.1.5:3010/path", "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("not a url", "192.168.1.5:3010", allow)).isFalse();
        assertThat(OriginCheck.allowed("http://192.168.1.5:3010", "evil.example", allow)).isFalse();
    }
}
