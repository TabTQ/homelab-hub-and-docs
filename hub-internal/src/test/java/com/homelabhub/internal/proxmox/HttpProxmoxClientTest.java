package com.homelabhub.internal.proxmox;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.homelabhub.internal.proxmox.ProxmoxProperties.TlsMode;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpProxmoxClientTest {

    private static final String TOKEN = "hub@pve!test=00000000-0000-0000-0000-000000000000";
    private static WireMockServer server;
    private static String realFingerprint;

    @BeforeAll
    static void start() throws Exception {
        server = new WireMockServer(options().dynamicPort().dynamicHttpsPort());
        server.start();
        realFingerprint = fetchFingerprint(server.httpsPort());
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    /** Reads the certificate the mock server presents, trusting everything just for this probe. */
    private static String fetchFingerprint(int port) throws Exception {
        var ctx = TlsTrust.contextFor(TlsMode.INSECURE, null);
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", port)) {
            s.startHandshake();
            return TlsTrust.fingerprintOf((X509Certificate) s.getSession().getPeerCertificates()[0]);
        }
    }

    private HttpProxmoxClient client(TlsMode mode, String fingerprint, Duration requestTimeout) {
        return new HttpProxmoxClient(new ProxmoxProperties(
                "https://localhost:" + server.httpsPort() + "/api2/json", TOKEN, mode, fingerprint,
                Duration.ofSeconds(2), requestTimeout));
    }

    private HttpProxmoxClient pinned() {
        return client(TlsMode.PINNED, realFingerprint, Duration.ofSeconds(5));
    }

    // ---- status ----

    @Test
    void readsRunningAndStoppedAndSendsTheApiToken() {
        server.stubFor(get(urlEqualTo("/api2/json/nodes/pve/lxc/100/status/current"))
                .willReturn(okJson("{\"data\":{\"status\":\"running\"}}")));
        server.stubFor(get(urlEqualTo("/api2/json/nodes/pve/qemu/200/status/current"))
                .willReturn(okJson("{\"data\":{\"status\":\"stopped\"}}")));

        assertThat(pinned().guestStatus("pve", GuestType.LXC, 100)).isEqualTo(GuestStatus.RUNNING);
        assertThat(pinned().guestStatus("pve", GuestType.QEMU, 200)).isEqualTo(GuestStatus.STOPPED);
        server.verify(getRequestedFor(urlEqualTo("/api2/json/nodes/pve/lxc/100/status/current"))
                .withHeader("Authorization", equalTo("PVEAPIToken=" + TOKEN)));
    }

    @Test
    void unknownStatusStringBecomesUnknown() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":{\"status\":\"weird\"}}")));
        assertThat(pinned().guestStatus("pve", GuestType.LXC, 1)).isEqualTo(GuestStatus.UNKNOWN);
    }

    @Test
    void statusErrorMessageMatchesTheLegacyWording() {
        String body = "{\"message\":\"Configuration file 'nodes/pve/lxc/99999.conf' does not exist\\n\",\"data\":null}";
        server.stubFor(get(anyUrl()).willReturn(serverError().withBody(body)));

        assertThatThrownBy(() -> pinned().guestStatus("pve", GuestType.LXC, 99999))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.HTTP_ERROR);
                    assertThat(e.getMessage()).isEqualTo("Failed to get status for LXC99999 on node pve. HTTP 500: " + body);
                });
    }

    @Test
    void permissionDeniedIsClassifiedAsUnauthorized() {
        server.stubFor(get(anyUrl()).willReturn(forbidden().withBody("{\"data\":null}")));
        assertThatThrownBy(() -> pinned().guestStatus("pve", GuestType.LXC, 1))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.UNAUTHORIZED));
    }

    @Test
    void garbageJsonIsABadResponseNotACrash() {
        server.stubFor(get(anyUrl()).willReturn(ok("<html>not json</html>")));
        assertThatThrownBy(() -> pinned().guestStatus("pve", GuestType.LXC, 1))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.BAD_RESPONSE));
    }

    @Test
    void redirectsAreNotFollowed() {
        server.stubFor(get(anyUrl()).willReturn(temporaryRedirect("https://evil.example/steal")));
        assertThatThrownBy(() -> pinned().guestStatus("pve", GuestType.LXC, 1))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> assertThat(e.getMessage()).contains("HTTP 302"));
    }

    // ---- actions ----

    @Test
    void guestActionPostsToTheRightEndpointAndReturnsTheTaskId() {
        server.stubFor(post(urlEqualTo("/api2/json/nodes/pve/lxc/100/status/stop"))
                .willReturn(okJson("{\"data\":\"UPID:pve:0001:0002:0003:vzstop:100:hub@pve!test:\"}")));
        assertThat(pinned().guestAction("pve", GuestType.LXC, 100, "stop")).startsWith("UPID:pve:");
        server.verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve/lxc/100/status/stop"))
                .withHeader("Authorization", equalTo("PVEAPIToken=" + TOKEN)));
    }

    @Test
    void guestActionFailureMessageMatchesTheLegacyWording() {
        server.stubFor(post(anyUrl()).willReturn(serverError().withBody("boom")));
        assertThatThrownBy(() -> pinned().guestAction("pve", GuestType.QEMU, 7, "start"))
                .isInstanceOfSatisfying(ProxmoxException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Failed to start QEMU7 on node pve. HTTP 500: boom"));
    }

    @Test
    void onlyStartStopAndShutdownAreAcceptedAsGuestActions() {
        assertThatThrownBy(() -> pinned().guestAction("pve", GuestType.LXC, 1, "destroy"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nodeCommandPostsAFormBody() {
        server.stubFor(post(urlEqualTo("/api2/json/nodes/pve/status")).willReturn(okJson("{\"data\":null}")));
        pinned().nodeCommand("pve", "shutdown");
        server.verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve/status"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("command=shutdown")));
    }

    @Test
    void nodeCommandFailureMessageMatchesTheLegacyWording() {
        server.stubFor(post(anyUrl()).willReturn(status(403).withBody("nope")));
        assertThatThrownBy(() -> pinned().nodeCommand("pve", "reboot"))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Failed to reboot node pve. HTTP 403: nope");
                    assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.UNAUTHORIZED);
                });
    }

    @Test
    void onlyShutdownAndRebootAreAcceptedAsNodeCommands() {
        assertThatThrownBy(() -> pinned().nodeCommand("pve", "wipe")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- guest list and task status (real fixtures) ----

    private static String fixture(String name) throws java.io.IOException {
        try (var in = HttpProxmoxClientTest.class.getResourceAsStream("/fixtures/proxmox/" + name)) {
            return new String(in.readAllBytes());
        }
    }

    @Test
    void listsRealGuestsFromBothEndpointsSortedById() throws Exception {
        server.stubFor(get(urlEqualTo("/api2/json/nodes/pve/lxc")).willReturn(okJson(fixture("lxc-list.json"))));
        server.stubFor(get(urlEqualTo("/api2/json/nodes/pve/qemu")).willReturn(okJson(fixture("qemu-list.json"))));
        var guests = pinned().listGuests("pve");
        assertThat(guests).extracting(ProxmoxClient.GuestInfo::vmid).containsExactly(100, 101, 102, 103, 210, 999);
        assertThat(guests).filteredOn(g -> g.status() == GuestStatus.RUNNING).extracting(ProxmoxClient.GuestInfo::name)
                .containsExactly("UbuntuCT", "postgres-turnkey", "pbs");
        assertThat(guests).allMatch(g -> g.type() == GuestType.LXC);
    }

    @Test
    void aFailingGuestListIsReportedNotSwallowed() {
        server.stubFor(get(anyUrl()).willReturn(forbidden()));
        assertThatThrownBy(() -> pinned().listGuests("pve")).isInstanceOfSatisfying(ProxmoxException.class,
                e -> assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.UNAUTHORIZED));
    }

    private static final String UPID = "UPID:pve:0001A2B3:00C0FFEE:66F1234A:vzstop:100:hub@pve!homepage:";

    @Test
    void readsTaskStatusAndEncodesTheTaskIdInThePath() {
        server.stubFor(get(urlPathMatching("/api2/json/nodes/pve/tasks/.*/status"))
                .willReturn(okJson("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));
        var s = pinned().taskStatus("pve", UPID);
        assertThat(s.running()).isFalse();
        assertThat(s.exitStatus()).isEqualTo("OK");
        server.verify(getRequestedFor(urlPathEqualTo("/api2/json/nodes/pve/tasks/UPID%3Apve%3A0001A2B3%3A00C0FFEE%3A66F1234A%3Avzstop%3A100%3Ahub%40pve%21homepage%3A/status")));
    }

    @Test
    void aRunningTaskHasNoExitStatusYet() {
        server.stubFor(get(urlPathMatching("/api2/json/nodes/pve/tasks/.*/status")).willReturn(okJson("{\"data\":{\"status\":\"running\"}}")));
        var s = pinned().taskStatus("pve", UPID);
        assertThat(s.running()).isTrue();
        assertThat(s.exitStatus()).isNull();
    }

    @Test
    void taskIdsThatCouldSteerThePathAreRejectedBeforeAnyRequest() {
        for (String bad : new String[] {"../../etc/passwd", "UPID:pve:1:2:3:x:y:z:/../x", "", "notaupid", "UPID:pve:zz:1:2:t:i:u:", "UPID:pve:1:2:3:t:i:u@r!x /x:"}) {
            assertThatThrownBy(() -> pinned().taskStatus("pve", bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void gracefulShutdownOfAGuestIsAllowedAsAnAction() {
        server.stubFor(post(urlEqualTo("/api2/json/nodes/pve/lxc/100/status/shutdown")).willReturn(okJson("{\"data\":\"" + UPID + "\"}")));
        assertThat(pinned().guestAction("pve", GuestType.LXC, 100, "shutdown")).isEqualTo(UPID);
    }

    // ---- input safety ----

    @Test
    void nodeNamesThatCouldSteerThePathAreRejectedBeforeAnyRequest() {
        for (String bad : new String[] {"../etc", "a/b", "", " ", "pve?x=1", "pve#x", "pve%2Fx", "-lead", "a".repeat(64)}) {
            assertThatThrownBy(() -> pinned().guestStatus(bad, GuestType.LXC, 1))
                    .as("node '%s'", bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void notConfiguredIsReportedClearlyWithoutNetworkAccess() {
        var c = new HttpProxmoxClient(new ProxmoxProperties("", "", TlsMode.PINNED, realFingerprint, null, null));
        assertThatThrownBy(() -> c.guestStatus("pve", GuestType.LXC, 1))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.NOT_CONFIGURED));
    }

    // ---- TLS modes ----

    @Test
    void wrongPinnedFingerprintIsRefusedAsCertMismatchAndSendsNothing() {
        String wrong = "00:".repeat(31) + "00";
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":{\"status\":\"running\"}}")));
        assertThatThrownBy(() -> client(TlsMode.PINNED, wrong, Duration.ofSeconds(5)).guestStatus("pve", GuestType.LXC, 1))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.CERT_MISMATCH);
                    assertThat(e.getMessage()).contains("fingerprint mismatch").contains(realFingerprint);
                });
        assertThat(server.getAllServeEvents()).as("request must not be sent to an unpinned server").isEmpty();
    }

    @Test
    void pinnedFingerprintWorksWithAndWithoutColonsAndInAnyCase() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":{\"status\":\"running\"}}")));
        for (String fp : new String[] {realFingerprint, realFingerprint.replace(":", ""), realFingerprint.toLowerCase()}) {
            assertThat(client(TlsMode.PINNED, fp, Duration.ofSeconds(5)).guestStatus("pve", GuestType.LXC, 1))
                    .isEqualTo(GuestStatus.RUNNING);
        }
    }

    @Test
    void insecureModeTrustsAnyCertificate() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":{\"status\":\"stopped\"}}")));
        assertThat(client(TlsMode.INSECURE, "", Duration.ofSeconds(5)).guestStatus("pve", GuestType.LXC, 1))
                .isEqualTo(GuestStatus.STOPPED);
    }

    @Test
    void systemModeRejectsTheSelfSignedMockCertificate() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":{\"status\":\"running\"}}")));
        assertThatThrownBy(() -> client(TlsMode.SYSTEM, "", Duration.ofSeconds(5)).guestStatus("pve", GuestType.LXC, 1))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.UNREACHABLE));
    }

    @Test
    void malformedPinnedFingerprintFailsFastAtConstruction() {
        for (String bad : new String[] {"", "abc", "zz".repeat(32), "aa".repeat(31)}) {
            assertThatThrownBy(() -> TlsTrust.contextFor(TlsMode.PINNED, bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- failure modes: slow / down host ----

    @Test
    void slowProxmoxTimesOutInsteadOfHangingTheCaller() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":{\"status\":\"running\"}}").withFixedDelay(2500)));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> client(TlsMode.PINNED, realFingerprint, Duration.ofMillis(400)).guestStatus("pve", GuestType.LXC, 5))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.UNREACHABLE);
                    assertThat(e.getMessage()).startsWith("Error communicating with Proxmox API for LXC5 on node pve:");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void connectionRefusedIsReportedAsUnreachable() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        var c = new HttpProxmoxClient(new ProxmoxProperties("https://localhost:" + freePort + "/api2/json", TOKEN,
                TlsMode.INSECURE, "", Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThatThrownBy(() -> c.nodeCommand("pve", "shutdown"))
                .isInstanceOfSatisfying(ProxmoxException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProxmoxException.Kind.UNREACHABLE);
                    assertThat(e.getMessage()).startsWith("Error sending shutdown command to node pve:");
                });
    }

    @Test
    void theTokenNeverAppearsInAnyErrorMessage() {
        server.stubFor(get(anyUrl()).willReturn(serverError().withBody("x")));
        assertThatThrownBy(() -> pinned().guestStatus("pve", GuestType.LXC, 1))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("00000000-0000"));
    }
}
