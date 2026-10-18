package com.homelabhub.internal.terminal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Throwaway ed25519 client keys, written in the exact "openssh-key-v1" format ssh-keygen produces, so the tests prove
 * the hub reads real OpenSSH keys. Generated per run with the JDK: no private key is ever committed.
 */
public final class TestKeys {

    public final byte[] publicBlob;
    final String openSshPrivate;

    private TestKeys(byte[] publicBlob, String openSshPrivate) {
        this.publicBlob = publicBlob;
        this.openSshPrivate = openSshPrivate;
    }

    public static TestKeys ed25519() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] x509 = kp.getPublic().getEncoded();
            byte[] pub = Arrays.copyOfRange(x509, x509.length - 32, x509.length);
            byte[] pkcs8 = kp.getPrivate().getEncoded();
            byte[] seed = Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);

            byte[] blob = sshBlob(out -> {
                string(out, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
                string(out, pub);
            });
            byte[] priv = sshBlob(out -> {
                out.writeInt(0x5eed5eed);
                out.writeInt(0x5eed5eed);
                string(out, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
                string(out, pub);
                byte[] both = new byte[64];
                System.arraycopy(seed, 0, both, 0, 32);
                System.arraycopy(pub, 0, both, 32, 32);
                string(out, both);
                string(out, "hub-test".getBytes(StandardCharsets.US_ASCII));
            });
            int pad = (8 - priv.length % 8) % 8;
            byte[] padded = Arrays.copyOf(priv, priv.length + pad);
            for (int i = 0; i < pad; i++) {
                padded[priv.length + i] = (byte) (i + 1);
            }
            byte[] file = sshBlob(out -> {
                out.write("openssh-key-v1".getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                string(out, "none".getBytes(StandardCharsets.US_ASCII));
                string(out, "none".getBytes(StandardCharsets.US_ASCII));
                string(out, new byte[0]);
                out.writeInt(1);
                string(out, blob);
                string(out, padded);
            });
            String b64 = Base64.getMimeEncoder(70, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(file);
            return new TestKeys(blob, "-----BEGIN OPENSSH PRIVATE KEY-----\n" + b64 + "\n-----END OPENSSH PRIVATE KEY-----\n");
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void writeTo(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, openSshPrivate);
    }

    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] sshBlob(Writer w) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        w.write(out);
        out.flush();
        return b.toByteArray();
    }

    private static void string(DataOutputStream out, byte[] v) throws IOException {
        out.writeInt(v.length);
        out.write(v);
    }
}
