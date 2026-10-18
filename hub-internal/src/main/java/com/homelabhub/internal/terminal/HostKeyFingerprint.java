package com.homelabhub.internal.terminal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** OpenSSH-style host key fingerprints: "SHA256:" + base64 (no padding) of the SHA-256 of the public key blob. */
public final class HostKeyFingerprint {

    private HostKeyFingerprint() {}

    public static String of(byte[] keyBlob) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(keyBlob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison, so a probe cannot learn the pinned value byte by byte. */
    public static boolean matches(String pinned, String offered) {
        if (pinned == null || offered == null) {
            return false;
        }
        return MessageDigest.isEqual(pinned.getBytes(StandardCharsets.US_ASCII), offered.getBytes(StandardCharsets.US_ASCII));
    }
}
