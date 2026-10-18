package com.homelabhub.internal.widgets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.config.SecretStore;
import com.homelabhub.internal.proxmox.TlsTrust;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchersTest {

    private static WireMockServer server;

    @TempDir Path dir;
    SecretStore secrets;

    @BeforeAll
    static void start() {
        server = new WireMockServer(options().dynamicPort().dynamicHttpsPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        server.resetAll();
        Path f = dir.resolve("secrets.env");
        Files.writeString(f, "PVE_PW=tok-secret-1234\nPBS_PW=pbs-secret-5678\nFB_PW=fb pass\nQB_PW=qb$pass\n");
        secrets = new SecretStore(f.toString(), dir.toString());
    }

    /** WireMock's baseUrl() is https when an https port is enabled, so build the plain-http address explicitly. */
    private static String httpBase() {
        return "http://localhost:" + server.port();
    }

    private static String fixture(String name) throws IOException {
        try (var in = FetchersTest.class.getResourceAsStream("/fixtures/proxmox/" + name)) {
            return new String(in.readAllBytes());
        }
    }

    private WidgetSpec spec(String type, String url, Map<String, String> opts) {
        return new WidgetSpec(type, url, "system", null, opts);
    }

    private WidgetHttp http(WidgetSpec s) {
        return new WidgetHttp(s, Duration.ofSeconds(2), Duration.ofMillis(800));
    }

    private Map<String, Object> run(WidgetFetcher f, WidgetSpec s) {
        return f.fetch(s, secrets, http(s));
    }

    private static String secretsIn(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('|');
        }
        return sb.toString();
    }

    // ================================================================= Proxmox (real fixtures)

    private WidgetSpec pve() {
        return spec("proxmox", httpBase(), Map.of("username", "hub@pve!homepage", "password", "${secret:PVE_PW}", "node", "pve"));
    }

    private void stubPve() throws IOException {
        server.stubFor(get("/api2/json/nodes/pve/status").willReturn(okJson(fixture("node-status.json"))));
        server.stubFor(get("/api2/json/nodes/pve/lxc").willReturn(okJson(fixture("lxc-list.json"))));
        server.stubFor(get("/api2/json/nodes/pve/qemu").willReturn(okJson(fixture("qemu-list.json"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxmoxSummarisesARealNodeResponse() throws Exception {
        stubPve();
        Map<String, Object> d = run(new ProxmoxFetcher(), pve());
        Map<String, Object> mem = (Map<String, Object>) d.get("memory");
        assertThat(mem.get("usedBytes")).isEqualTo(5155676160L);
        assertThat(mem.get("totalBytes")).isEqualTo(16650022912L);
        assertThat(mem.get("percent")).isEqualTo(31.0);
        assertThat(d.get("uptimeSeconds")).isEqualTo(74243L);
        assertThat(d.get("cpuPercent")).isEqualTo(0.0);
        assertThat(d.get("lxc")).isEqualTo(Map.of("running", 3, "total", 6));
        assertThat(d.get("vms")).isEqualTo(Map.of("running", 0, "total", 0));
    }

    @Test
    void proxmoxSendsTheApiTokenHeaderBuiltFromUsernameAndSecret() throws Exception {
        stubPve();
        run(new ProxmoxFetcher(), pve());
        server.verify(getRequestedFor(urlEqualTo("/api2/json/nodes/pve/status")).withHeader("Authorization", equalTo("PVEAPIToken=hub@pve!homepage=tok-secret-1234")));
    }

    @Test
    void proxmoxNeedsATokenNotAPassword() {
        WidgetSpec s = spec("proxmox", httpBase(), Map.of("username", "root@pam", "password", "${secret:PVE_PW}", "node", "pve"));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), s)).isInstanceOfSatisfying(WidgetException.class, e -> {
            assertThat(e.code()).isEqualTo(WidgetException.Code.NOT_CONFIGURED);
            assertThat(e.getMessage()).contains("API token");
        });
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void aMissingSecretIsReportedByNameAndNothingIsSent() {
        WidgetSpec s = spec("proxmox", httpBase(), Map.of("username", "u@pve!t", "password", "${secret:NOT_THERE}", "node", "pve"));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), s)).isInstanceOfSatisfying(WidgetException.class, e -> {
            assertThat(e.code()).isEqualTo(WidgetException.Code.SECRET_MISSING);
            assertThat(e.getMessage()).isEqualTo("Secret 'NOT_THERE' is not set");
        });
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void refusedCredentialsAreUnauthorizedAndTheBodyIsNotCopiedIntoTheError() {
        server.stubFor(get(anyUrl()).willReturn(forbidden().withBody("Permission check failed (/nodes/pve, Sys.Audit) tok-secret-1234")));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), pve())).isInstanceOfSatisfying(WidgetException.class, e -> {
            assertThat(e.code()).isEqualTo(WidgetException.Code.UNAUTHORIZED);
            assertThat(secretsIn(e)).doesNotContain("tok-secret-1234").doesNotContain("Permission check failed");
        });
    }

    @Test
    void serverErrorsAndGarbageAreBadResponses() {
        server.stubFor(get(anyUrl()).willReturn(serverError()));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), pve())).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.BAD_RESPONSE));
        server.resetAll();
        server.stubFor(get(anyUrl()).willReturn(ok("<html>proxy error</html>")));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), pve())).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.BAD_RESPONSE));
        server.resetAll();
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":null}")));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), pve())).isInstanceOf(WidgetException.class);
    }

    @Test
    void slowAndDeadUpstreamsAreTimeoutAndUnreachable() throws Exception {
        server.stubFor(get(anyUrl()).willReturn(ok().withFixedDelay(2000)));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), pve())).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.TIMEOUT));
        int free;
        try (var s = new java.net.ServerSocket(0)) {
            free = s.getLocalPort();
        }
        WidgetSpec dead = spec("proxmox", "http://127.0.0.1:" + free, Map.of("username", "u@pve!t", "password", "${secret:PVE_PW}", "node", "pve"));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), dead)).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.UNREACHABLE));
    }

    @Test
    void anOversizedResponseIsRefusedNotBuffered() {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":\"" + "x".repeat(WidgetHttp.MAX_BODY + 10) + "\"}")));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), pve())).isInstanceOfSatisfying(WidgetException.class, e -> {
            assertThat(e.code()).isEqualTo(WidgetException.Code.BAD_RESPONSE);
            assertThat(e.getMessage()).contains("large");
        });
    }

    @Test
    void redirectsAreNotFollowed() {
        server.stubFor(get(anyUrl()).willReturn(temporaryRedirect("http://evil.example/steal")));
        assertThatThrownBy(() -> run(new ProxmoxFetcher(), pve())).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.BAD_RESPONSE));
    }

    @Test
    void certificatePinningWorksPerWidget() throws Exception {
        server.stubFor(get(anyUrl()).willReturn(okJson("{\"data\":[]}")));
        String realFp;
        try (SSLSocket s = (SSLSocket) TlsTrust.contextFor(com.homelabhub.internal.proxmox.ProxmoxProperties.TlsMode.INSECURE, null)
                .getSocketFactory().createSocket("localhost", server.httpsPort())) {
            s.startHandshake();
            realFp = TlsTrust.fingerprintOf((X509Certificate) s.getSession().getPeerCertificates()[0]);
        }
        String url = "https://localhost:" + server.httpsPort();
        Map<String, String> o = Map.of("username", "hub@pbs!t", "password", "${secret:PBS_PW}");
        assertThat(run(new PbsFetcher(), new WidgetSpec("proxmoxbackupserver", url, "pinned", realFp, o))).containsKey("datastores");
        assertThat(run(new PbsFetcher(), new WidgetSpec("proxmoxbackupserver", url, "insecure", null, o))).containsKey("datastores");
        assertThatThrownBy(() -> run(new PbsFetcher(), new WidgetSpec("proxmoxbackupserver", url, "pinned", "00:".repeat(31) + "00", o)))
                .isInstanceOfSatisfying(WidgetException.class, e -> assertThat(e.code()).isEqualTo(WidgetException.Code.CERT_MISMATCH));
        assertThatThrownBy(() -> run(new PbsFetcher(), new WidgetSpec("proxmoxbackupserver", url, "system", null, o)))
                .isInstanceOfSatisfying(WidgetException.class, e -> assertThat(e.code()).isEqualTo(WidgetException.Code.UNREACHABLE));
    }

    // ================================================================= PBS

    @Test
    @SuppressWarnings("unchecked")
    void pbsListsDatastoresAndSurvivesATokenWithoutNodeAccess() {
        server.stubFor(get("/api2/json/status/datastore-usage").willReturn(okJson(
                "{\"data\":[{\"store\":\"backups\",\"total\":240000000000,\"used\":60000000000,\"avail\":180000000000}]}")));
        server.stubFor(get("/api2/json/nodes/localhost/status").willReturn(forbidden()));
        WidgetSpec s = spec("proxmoxbackupserver", httpBase(), Map.of("username", "hub@pbs!homepage", "password", "${secret:PBS_PW}"));
        Map<String, Object> d = run(new PbsFetcher(), s);
        var stores = (java.util.List<Map<String, Object>>) d.get("datastores");
        assertThat(stores).singleElement().satisfies(x -> {
            assertThat(x.get("name")).isEqualTo("backups");
            assertThat(x.get("percent")).isEqualTo(25.0);
        });
        assertThat(d).doesNotContainKey("cpuPercent");
        server.verify(getRequestedFor(urlEqualTo("/api2/json/status/datastore-usage")).withHeader("Authorization", equalTo("PBSAPIToken=hub@pbs!homepage:pbs-secret-5678")));
    }

    @Test
    void pbsAddsNodeHealthWhenAllowed() {
        server.stubFor(get("/api2/json/status/datastore-usage").willReturn(okJson("{\"data\":[]}")));
        server.stubFor(get("/api2/json/nodes/localhost/status").willReturn(okJson("{\"data\":{\"cpu\":0.25,\"memory\":{\"used\":500,\"total\":2000}}}")));
        WidgetSpec s = spec("proxmoxbackupserver", httpBase(), Map.of("username", "hub@pbs!homepage", "password", "${secret:PBS_PW}"));
        Map<String, Object> d = run(new PbsFetcher(), s);
        assertThat(d.get("cpuPercent")).isEqualTo(25.0);
    }

    @Test
    void pbsAlsoNeedsAToken() {
        WidgetSpec s = spec("proxmoxbackupserver", httpBase(), Map.of("username", "root@pam", "password", "${secret:PBS_PW}"));
        assertThatThrownBy(() -> run(new PbsFetcher(), s)).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.NOT_CONFIGURED));
    }

    // ================================================================= File Browser

    @Test
    void fileBrowserLogsInThenReadsUsage() {
        server.stubFor(post("/api/login").willReturn(ok("jwt-token-value")));
        server.stubFor(get("/api/usage/").willReturn(okJson("{\"total\":1000,\"used\":250}")));
        WidgetSpec s = spec("filebrowser", httpBase(), Map.of("username", "admin", "password", "${secret:FB_PW}"));
        Map<String, Object> d = run(new FileBrowserFetcher(), s);
        assertThat(d).containsEntry("percent", 25.0).containsEntry("usedBytes", 250L).containsEntry("totalBytes", 1000L);
        server.verify(postRequestedFor(urlEqualTo("/api/login")).withRequestBody(equalToJson("{\"username\":\"admin\",\"password\":\"fb pass\"}")));
        server.verify(getRequestedFor(urlEqualTo("/api/usage/")).withHeader("X-Auth", equalTo("jwt-token-value")));
    }

    @Test
    void fileBrowserRefusedLoginIsUnauthorizedAndAnEmptyTokenIsABadResponse() {
        server.stubFor(post("/api/login").willReturn(forbidden()));
        WidgetSpec s = spec("filebrowser", httpBase(), Map.of("username", "admin", "password", "${secret:FB_PW}"));
        assertThatThrownBy(() -> run(new FileBrowserFetcher(), s)).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.UNAUTHORIZED));
        server.resetAll();
        server.stubFor(post("/api/login").willReturn(ok("")));
        assertThatThrownBy(() -> run(new FileBrowserFetcher(), s)).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.BAD_RESPONSE));
    }

    // ================================================================= qBittorrent

    private WidgetSpec qb() {
        return spec("qbittorrent", httpBase(), Map.of("username", "admin", "password", "${secret:QB_PW}"));
    }

    @Test
    void qbittorrentCountsLeechersAndSeedersAndReportsSpeeds() {
        server.stubFor(post("/api/v2/auth/login").willReturn(ok("Ok.").withHeader("Set-Cookie", "SID=abc123; HttpOnly; path=/")));
        server.stubFor(get("/api/v2/transfer/info").willReturn(okJson("{\"dl_info_speed\":1500,\"up_info_speed\":300}")));
        server.stubFor(get("/api/v2/torrents/info").willReturn(okJson(
                "[{\"state\":\"downloading\"},{\"state\":\"stalledDL\"},{\"state\":\"uploading\"},{\"state\":\"stalledUP\"},{\"state\":\"stalledUP\"},{\"state\":\"pausedUP\"}]")));
        Map<String, Object> d = run(new QbittorrentFetcher(), qb());
        assertThat(d).containsEntry("leech", 2).containsEntry("seed", 3).containsEntry("total", 6)
                .containsEntry("downloadBytesPerSec", 1500L).containsEntry("uploadBytesPerSec", 300L);
        server.verify(postRequestedFor(urlEqualTo("/api/v2/auth/login")).withRequestBody(containing("username=admin")).withRequestBody(containing("password=qb%24pass")).withHeader("Referer", equalTo(httpBase())));
        server.verify(getRequestedFor(urlEqualTo("/api/v2/transfer/info")).withHeader("Cookie", equalTo("SID=abc123")));
    }

    @Test
    void qbittorrentWrongPasswordAnswersFailsWith200AndMustStillBeUnauthorized() {
        server.stubFor(post("/api/v2/auth/login").willReturn(ok("Fails.")));
        assertThatThrownBy(() -> run(new QbittorrentFetcher(), qb())).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.UNAUTHORIZED));
    }

    @Test
    void qbittorrentWithoutASessionCookieIsABadResponse() {
        server.stubFor(post("/api/v2/auth/login").willReturn(ok("Ok.")));
        assertThatThrownBy(() -> run(new QbittorrentFetcher(), qb())).isInstanceOfSatisfying(WidgetException.class,
                e -> assertThat(e.code()).isEqualTo(WidgetException.Code.BAD_RESPONSE));
    }

    @Test
    void sessionCookieIsFoundAmongOtherAttributes() {
        assertThat(QbittorrentFetcher.sessionCookie(java.util.List.of("other=1; path=/", "SID=xyz; HttpOnly"))).isEqualTo("xyz");
        assertThat(QbittorrentFetcher.sessionCookie(java.util.List.of("SID=; path=/"))).isNull();
        assertThat(QbittorrentFetcher.sessionCookie(java.util.List.of())).isNull();
    }
}
