package com.homelabhub.docs;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed, short-lived paths for the internal webapp preview (D15). A sandboxed frame has an opaque origin, so the browser
 * does not send the SameSite=Strict session cookie with its requests. Instead the editor asks (logged in) for a token bound
 * to one bundle that expires after an hour. The key is random per boot: a restart invalidates every token.
 */
public final class PreviewTokens {

    public static final Duration TTL = Duration.ofHours(1);

    private final Clock clock;
    private final byte[] key;

    public PreviewTokens(Clock clock) {
        this(clock, random());
    }

    PreviewTokens(Clock clock, byte[] key) {
        this.clock = clock;
        this.key = key.clone();
    }

    public String mint(String bundleSha) {
        long exp = clock.instant().plus(TTL).getEpochSecond();
        return exp + "." + sign(bundleSha, exp);
    }

    public boolean valid(String token, String bundleSha) {
        if (token == null || bundleSha == null || token.length() > 100) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (clock.instant().getEpochSecond() > exp) {
            return false;
        }
        byte[] expected = sign(bundleSha, exp).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String bundleSha, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal((bundleSha + "|" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] random() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }
}
