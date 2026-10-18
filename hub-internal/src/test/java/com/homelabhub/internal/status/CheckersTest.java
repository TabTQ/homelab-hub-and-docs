package com.homelabhub.internal.status;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.homelabhub.internal.config.DashboardConfig.StatusCheck;
import com.homelabhub.internal.config.DashboardConfig.StatusCheck.Kind;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckersTest {

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

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private static StatusCheck check(Kind kind, String target) {
        return new StatusCheck(kind, target, 200, 499, true);
    }

    private static StatusCheck http(String url, int from, int to, boolean insecure) {
        return new StatusCheck(Kind.HTTP, url, from, to, insecure);
    }

    private final HttpChecker http = new HttpChecker(Duration.ofSeconds(2), Duration.ofMillis(700));

    // ---------------------------------------------------------------- TCP

    @Test
    void tcpIsUpForAListeningPortAndReportsLatency() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            CheckResult r = new TcpChecker(1000).run(check(Kind.TCP, "127.0.0.1:" + s.getLocalPort()));
            assertThat(r.state()).isEqualTo(State.UP);
            assertThat(r.latencyMs()).isNotNull().isLessThan(1000);
        }
    }

    @Test
    void tcpIsDownWithAReasonWhenNothingListensOrTheHostIsUnknown() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        CheckResult refused = new TcpChecker(1000).run(check(Kind.TCP, "127.0.0.1:" + free));
        assertThat(refused.state()).isEqualTo(State.DOWN);
        assertThat(refused.detail()).isEqualTo("connection refused");
        CheckResult unknown = new TcpChecker(1000).run(check(Kind.TCP, "no-such-host.invalid:80"));
        assertThat(unknown.state()).isEqualTo(State.DOWN);
    }

    @Test
    void tcpHonoursItsTimeout() {
        long t0 = System.nanoTime();
        CheckResult r = new TcpChecker(300).run(check(Kind.TCP, "10.255.255.1:80"));
        assertThat(r.state()).isEqualTo(State.DOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void tcpRefusesMalformedTargetsWithoutThrowing() {
        assertThat(new TcpChecker(300).run(check(Kind.TCP, "nocolon")).state()).isEqualTo(State.UNKNOWN);
        assertThat(new TcpChecker(300).run(check(Kind.TCP, "host:abc")).state()).isEqualTo(State.UNKNOWN);
    }

    // ---------------------------------------------------------------- HTTP

    @Test
    void httpIsUpForACodeInsideTheExpectedRange() {
        server.stubFor(get("/ok").willReturn(ok("x")));
        server.stubFor(get("/needs-login").willReturn(unauthorized()));
        assertThat(http.run(http(server.baseUrl() + "/ok", 200, 499, true)).state()).isEqualTo(State.UP);
        assertThat(http.run(http(server.baseUrl() + "/needs-login", 200, 499, true)).state())
                .as("401 means the service is alive").isEqualTo(State.UP);
    }

    @Test
    void httpIsDegradedForServerErrorsAndUnexpectedCodes() {
        server.stubFor(get("/boom").willReturn(serverError()));
        server.stubFor(get("/missing").willReturn(notFound()));
        CheckResult boom = http.run(http(server.baseUrl() + "/boom", 200, 499, true));
        assertThat(boom.state()).isEqualTo(State.DEGRADED);
        assertThat(boom.detail()).isEqualTo("HTTP 500");
        assertThat(http.run(http(server.baseUrl() + "/missing", 200, 299, true)).state()).isEqualTo(State.DEGRADED);
    }

    @Test
    void httpDoesNotFollowRedirects() {
        server.stubFor(get("/r").willReturn(temporaryRedirect("http://evil.example/")));
        assertThat(http.run(http(server.baseUrl() + "/r", 200, 399, true)).state()).isEqualTo(State.UP);
        assertThat(http.run(http(server.baseUrl() + "/r", 200, 299, true)).detail()).isEqualTo("HTTP 302");
    }

    @Test
    void aSlowServerIsDownNotHanging() {
        server.stubFor(get("/slow").willReturn(ok().withFixedDelay(3000)));
        long t0 = System.nanoTime();
        CheckResult r = http.run(http(server.baseUrl() + "/slow", 200, 499, true));
        assertThat(r.state()).isEqualTo(State.DOWN);
        assertThat(r.detail()).isEqualTo("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void httpIsDownWhenNothingListens() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        assertThat(http.run(http("http://127.0.0.1:" + free + "/", 200, 499, true)).state()).isEqualTo(State.DOWN);
    }

    @Test
    void selfSignedHttpsIsAcceptedByDefaultAndRefusedWhenTlsIsSystem() {
        server.stubFor(get("/s").willReturn(ok()));
        String url = "https://localhost:" + server.httpsPort() + "/s";
        assertThat(http.run(http(url, 200, 499, true)).state()).isEqualTo(State.UP);
        CheckResult strict = http.run(http(url, 200, 499, false));
        assertThat(strict.state()).isEqualTo(State.DOWN);
        assertThat(strict.detail()).isEqualTo("TLS error");
    }

    @Test
    void aBadUrlIsUnknownNotAnException() {
        assertThat(http.run(http("http://exa mple/", 200, 499, true)).state()).isEqualTo(State.UNKNOWN);
    }

    // ---------------------------------------------------------------- ICMP (uses the system ping)

    @Test
    void icmpIsUpForLoopback() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/ping")) || Files.isExecutable(Path.of("/bin/ping")), "ping not installed");
        CheckResult r = new IcmpChecker(2).run(check(Kind.ICMP, "127.0.0.1"));
        assertThat(r.state()).isEqualTo(State.UP);
    }

    @Test
    void icmpNeverTreatsAHostAsAnOptionOrAShellCommand() throws Exception {
        Path marker = Files.createTempDirectory("icmp").resolve("pwned");
        for (String evil : new String[] {"-f", "--help", "-c100", "127.0.0.1; touch " + marker, "$(touch " + marker + ")", "`touch " + marker + "`", "127.0.0.1 -c 5"}) {
            CheckResult r = new IcmpChecker(1).run(check(Kind.ICMP, evil));
            assertThat(r.state()).as(evil).isIn(State.UNKNOWN, State.DOWN);
        }
        assertThat(marker).as("no command was executed").doesNotExist();
    }

    @Test
    void icmpReportsAnUnresolvableNameAsNotUpAndWithoutThrowing() {
        CheckResult r = new IcmpChecker(1).run(check(Kind.ICMP, "no-such-host.invalid"));
        assertThat(r.state()).isIn(State.UNKNOWN, State.DOWN);
    }
}
