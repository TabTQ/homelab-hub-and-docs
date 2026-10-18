package com.homelabhub.internal.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real HTTP, real SSE. Auth mode none only to keep the test about streaming, not login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"hub.status.auto-start=false", "hub.auth.mode=none"})
class StatusStreamTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @LocalServerPort int port;
    @Autowired StatusService service;
    @Autowired StatusStream stream;
    @Autowired ConfigStore store;
    @MockitoBean Checker checker;
    @MockitoBean ProxmoxClient proxmox;

    /** A closed tab is only noticed on the next write. In production the 20 s heartbeat does that; here we drive it. */
    @AfterEach
    void dropDeadSubscribers() {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).pollInterval(java.time.Duration.ofMillis(100)).until(() -> {
            stream.heartbeat();
            return stream.subscribers() == 0;
        });
    }

    private HttpResponse<InputStream> open(HttpClient http) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/status/stream")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    /** Reads SSE frames until predicate matches, returning "event: data" pairs. */
    private static List<String> readEvents(InputStream in, int wanted) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        List<String> events = new ArrayList<>();
        String name = null;
        String line;
        while (events.size() < wanted && (line = r.readLine()) != null) {
            if (line.startsWith("event:")) {
                name = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                events.add(name + " " + line.substring(5).trim());
            }
        }
        return events;
    }

    @Test
    void aBrowserGetsASnapshotThenLiveChanges() throws Exception {
        store.save("version: 1\ngroups:\n  - name: G\n    items:\n      - {name: A, status: {tcp: \"h:1\"}}\nactions: []\n", store.state().sha256(), "ui", null);
        when(checker.run(any())).thenReturn(CheckResult.up(3));

        HttpResponse<InputStream> resp = open(HttpClient.newHttpClient());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("no-store");

        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> readEvents(resp.body(), 2));
            Thread.sleep(300); // the snapshot is sent on connect
            service.runRound();
            List<String> events = future.get(5, TimeUnit.SECONDS);
            assertThat(events.get(0)).startsWith("snapshot ").contains("\"item:g0.0\"").contains("\"state\":\"unknown\"");
            assertThat(events.get(1)).startsWith("status ").contains("\"id\":\"item:g0.0\"").contains("\"state\":\"up\"");
        } finally {
            executor.shutdownNow();
            resp.body().close();
        }
    }

    @Test
    void aSecondRoundWithTheSameStateSendsNothing() throws Exception {
        store.save("version: 1\ngroups:\n  - name: G\n    items:\n      - {name: A, status: {tcp: \"h:1\"}}\nactions: []\n", store.state().sha256(), "ui", null);
        when(checker.run(any())).thenReturn(CheckResult.up(3));
        service.runRound();
        HttpResponse<InputStream> resp = open(HttpClient.newHttpClient());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> readEvents(resp.body(), 2));
            Thread.sleep(300);
            service.runRound();
            service.runRound();
            Object got = null;
            Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> future.get(1500, TimeUnit.MILLISECONDS));
            if (t == null) {
                got = future.get();
            }
            assertThat(t).as("only the snapshot arrives, no status events for an unchanged state; got: " + got)
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
        } finally {
            executor.shutdownNow();
            resp.body().close();
        }
    }

    @Test
    void theNumberOfSimultaneousBrowsersIsCapped() throws Exception {
        List<HttpResponse<InputStream>> open = new ArrayList<>();
        try {
            for (int i = 0; i < StatusStream.MAX_SUBSCRIBERS; i++) {
                HttpResponse<InputStream> r = open(HttpClient.newHttpClient());
                assertThat(r.statusCode()).isEqualTo(200);
                open.add(r);
            }
            assertThat(open(HttpClient.newHttpClient()).statusCode()).isEqualTo(429);
        } finally {
            for (var r : open) {
                r.body().close();
            }
        }
    }
}
