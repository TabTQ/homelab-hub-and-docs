package com.homelabhub.internal.proxmox;

import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSocket;

/** `java -jar hub-internal.jar fingerprint 192.168.10.102:8006` prints the certificate to put in PROXMOX_TLS_FINGERPRINT. */
public final class FingerprintCli {

    private FingerprintCli() {}

    public static int run(String[] args) {
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9][A-Za-z0-9.-]*:[0-9]{1,5}")) {
            System.err.println("Usage: fingerprint host:port   (for example 192.168.10.102:8006)");
            return 2;
        }
        int colon = args[1].lastIndexOf(':');
        String host = args[1].substring(0, colon);
        int port = Integer.parseInt(args[1].substring(colon + 1));
        try (SSLSocket s = (SSLSocket) TlsTrust.contextFor(ProxmoxProperties.TlsMode.INSECURE, null).getSocketFactory().createSocket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 5000);
            s.setSoTimeout(5000);
            s.startHandshake();
            X509Certificate c = (X509Certificate) s.getSession().getPeerCertificates()[0];
            System.out.println("Subject:     " + c.getSubjectX500Principal().getName());
            System.out.println("Valid until: " + c.getNotAfter());
            System.out.println("SHA-256:     " + TlsTrust.fingerprintOf(c));
            System.out.println();
            System.out.println("Compare this with the fingerprint shown in the Proxmox web UI (Datacenter > Nodes > Certificates)");
            System.out.println("BEFORE trusting it, then set PROXMOX_TLS_FINGERPRINT to it.");
            return 0;
        } catch (Exception e) {
            System.err.println("Could not read the certificate: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }
}
