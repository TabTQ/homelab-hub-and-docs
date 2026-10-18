package com.homelabhub.internal.terminal;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * `java -jar hub-internal.jar terminal-hostkey 127.0.0.1:22` prints the SSH host key fingerprint to pin in dashboard.yaml
 * (`hostKey:`). It only reads the key the server offers during the handshake and then disconnects: no authentication is
 * attempted.
 */
public final class HostKeyCli {

    private HostKeyCli() {}

    public static int run(String[] args) {
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?")) {
            System.err.println("Usage: terminal-hostkey host[:port]   (for example 127.0.0.1:22)");
            return 2;
        }
        String host = args[1];
        int port = 22;
        int colon = args[1].lastIndexOf(':');
        if (colon > 0) {
            host = args[1].substring(0, colon);
            port = Integer.parseInt(args[1].substring(colon + 1));
        }
        // A repository with no pin rejects every key, after remembering the one offered.
        SshConnector.PinnedHostKey probe = new SshConnector.PinnedHostKey(null);
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(probe);
        Session s = null;
        try {
            s = jsch.getSession("hub-hostkey-probe", host, port);
            s.setConfig("StrictHostKeyChecking", "yes");
            s.connect(10_000);
        } catch (JSchException e) {
            // expected: the probe rejects every key once it has seen it
        } finally {
            if (s != null) {
                s.disconnect();
            }
        }
        byte[] blob = probe.offeredKey;
        if (blob == null) {
            System.err.println("Could not read a host key from " + host + ":" + port + " (is sshd running there?).");
            return 1;
        }
        System.out.println("Key type:    " + keyType(blob));
        System.out.println("Fingerprint: " + HostKeyFingerprint.of(blob));
        System.out.println();
        System.out.println("Compare this with the output of `ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub` ON THAT MACHINE before");
        System.out.println("trusting it, then put it into dashboard.yaml as   hostKey: \"" + HostKeyFingerprint.of(blob) + "\"");
        return 0;
    }

    static String keyType(byte[] blob) {
        try {
            ByteBuffer b = ByteBuffer.wrap(blob);
            int len = b.getInt();
            byte[] name = new byte[len];
            b.get(name);
            return new String(name, StandardCharsets.US_ASCII);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
