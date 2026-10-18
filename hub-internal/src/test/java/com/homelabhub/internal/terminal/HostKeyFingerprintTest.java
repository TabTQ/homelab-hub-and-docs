package com.homelabhub.internal.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.util.Base64;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;

class HostKeyFingerprintTest {

    /**
     * A fixed public key and the fingerprint `ssh-keygen -lf` prints for it. The blob is the RFC 8032 test vector 1
     * public key; the expected value was computed outside Java (Python hashlib: sha256, base64 without padding).
     */
    @Test
    void matchesTheOpenSshFormatForAKnownKey() {
        byte[] blob = Base64.getDecoder().decode("AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea");
        assertThat(HostKeyFingerprint.of(blob)).isEqualTo("SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8");
    }

    @Test
    void agreesWithAnIndependentImplementation() throws Exception {
        for (String alg : new String[] {"EC", "RSA"}) {
            var kp = KeyPairGenerator.getInstance(alg).generateKeyPair();
            assertThat(HostKeyFingerprint.of(TestSshServer.blob(kp.getPublic()))).as(alg).isEqualTo(KeyUtils.getFingerPrint(kp.getPublic()));
        }
    }

    @Test
    void comparesExactly() {
        assertThat(HostKeyFingerprint.matches("SHA256:abc", "SHA256:abc")).isTrue();
        assertThat(HostKeyFingerprint.matches("SHA256:abc", "SHA256:abd")).isFalse();
        assertThat(HostKeyFingerprint.matches(null, "SHA256:abc")).isFalse();
        assertThat(HostKeyFingerprint.matches("SHA256:abc", null)).isFalse();
    }
}
