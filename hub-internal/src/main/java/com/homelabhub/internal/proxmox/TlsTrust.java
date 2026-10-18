package com.homelabhub.internal.proxmox;

import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/** Builds the SSLContext for the Proxmox connection according to the configured {@link ProxmoxProperties.TlsMode}. */
public final class TlsTrust {

    public static final String MISMATCH_PREFIX = "Server certificate fingerprint mismatch";

    private TlsTrust() {}

    public static SSLContext contextFor(ProxmoxProperties.TlsMode mode, String fingerprint) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            switch (mode) {
                case SYSTEM -> ctx.init(null, null, new SecureRandom());
                case INSECURE -> ctx.init(null, new TrustManager[] {new Base(null)}, new SecureRandom());
                case PINNED -> ctx.init(null, new TrustManager[] {new Base(parseFingerprint(fingerprint))}, new SecureRandom());
            }
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create TLS context", e);
        }
    }

    /** Accepts hex with or without ':' or spaces, any case. Must be exactly 32 bytes (SHA-256). */
    public static byte[] parseFingerprint(String text) {
        String hex = text == null ? "" : text.replaceAll("[:\\s]", "").toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("PROXMOX_TLS_FINGERPRINT must be a SHA-256 fingerprint (64 hex characters)");
        }
        return HexFormat.of().parseHex(hex);
    }

    public static String fingerprintOf(X509Certificate cert) throws CertificateException {
        try {
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Extended trust manager: because it is an X509ExtendedTrustManager the JDK does not layer its own hostname
     * check on top, so the pinned fingerprint IS the identity check. With {@code expected == null} it trusts all.
     */
    private static final class Base extends X509ExtendedTrustManager {
        private final byte[] expected;

        Base(byte[] expected) {
            this.expected = expected;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (expected == null) {
                return;
            }
            if (chain == null || chain.length == 0) {
                throw new CertificateException(MISMATCH_PREFIX + " (no certificate presented)");
            }
            byte[] actual;
            try {
                actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new CertificateException(e);
            }
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new CertificateException(MISMATCH_PREFIX + ": expected " + HexFormat.ofDelimiter(":").withUpperCase().formatHex(expected)
                        + " but the server presented " + HexFormat.ofDelimiter(":").withUpperCase().formatHex(actual));
            }
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { check(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("client authentication not supported"); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { throw new CertificateException("client authentication not supported"); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { throw new CertificateException("client authentication not supported"); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
