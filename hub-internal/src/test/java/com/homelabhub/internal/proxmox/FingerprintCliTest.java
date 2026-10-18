package com.homelabhub.internal.proxmox;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FingerprintCliTest {

    private static WireMockServer server;

    @BeforeAll
    static void start() {
        server = new WireMockServer(options().dynamicPort().dynamicHttpsPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    private record Out(int code, String out, String err) {}

    private static Out run(String... args) {
        PrintStream so = System.out, se = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o));
        System.setErr(new PrintStream(e));
        try {
            return new Out(FingerprintCli.run(args), o.toString(), e.toString());
        } finally {
            System.setOut(so);
            System.setErr(se);
        }
    }

    @Test
    void printsTheFingerprintInTheFormatTheConfigurationAccepts() throws Exception {
        Out r = run("fingerprint", "localhost:" + server.httpsPort());
        assertThat(r.code()).as(r.err()).isZero();
        String printed = r.out().lines().filter(l -> l.startsWith("SHA-256:")).findFirst().orElseThrow().substring(8).strip();

        String direct;
        try (SSLSocket s = (SSLSocket) TlsTrust.contextFor(ProxmoxProperties.TlsMode.INSECURE, null).getSocketFactory().createSocket("localhost", server.httpsPort())) {
            s.startHandshake();
            direct = TlsTrust.fingerprintOf((X509Certificate) s.getSession().getPeerCertificates()[0]);
        }
        assertThat(printed).isEqualTo(direct);
        assertThat(TlsTrust.parseFingerprint(printed)).hasSize(32); // round trip through the real parser
        assertThat(r.out()).contains("Subject:").contains("Valid until:").contains("BEFORE trusting it");
    }

    @Test
    void badArgumentsAreRefusedWithoutTryingToConnect() {
        for (String bad : new String[] {"nocolon", "host:port", "-x:80", "a b:80", "host:1234567", "host:80;rm"}) {
            assertThat(run("fingerprint", bad).code()).as(bad).isEqualTo(2);
        }
        assertThat(run("fingerprint").code()).isEqualTo(2);
    }

    @Test
    void anUnreachableHostIsAnErrorMessageNotAStackTrace() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        Out r = run("fingerprint", "127.0.0.1:" + free);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).startsWith("Could not read the certificate").doesNotContain("\tat ");
    }

    @Test
    void aPlainHttpPortIsReportedNotHung() {
        long t0 = System.nanoTime();
        Out r = run("fingerprint", "localhost:" + server.port());
        assertThat(r.code()).isEqualTo(1);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(8000);
    }
}
