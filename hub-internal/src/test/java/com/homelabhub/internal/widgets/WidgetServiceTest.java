package com.homelabhub.internal.widgets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"hub.status.auto-start=false", "hub.widgets.cache-seconds=15"})
@AutoConfigureMockMvc
@Import(WidgetServiceTest.Clocks.class)
class WidgetServiceTest {

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-24T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration d) { now = now.plus(d); }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    private static final Path DIR = TestSupport.tempDir();
    private static WireMockServer upstream;

    static {
        try {
            Files.writeString(DIR.resolve("secrets.env"), "QB_PW=qb-secret-pass\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @Autowired WidgetService service;
    @Autowired ConfigStore store;
    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @MockitoBean HostMetrics metrics;
    @MockitoBean ProxmoxClient proxmox;

    private String yaml(int port) {
        return "version: 1\nwidgets:\n  - resources: {cpu: true, memory: true, disk: /, cputemp: true, uptime: false, units: imperial}\n  - search: {provider: google}\n"
                + "groups:\n  - name: Media\n    items:\n      - name: qBittorrent\n        widget: {type: qbittorrent, url: \"http://localhost:" + port
                + "\", username: admin, password: \"${secret:QB_PW}\"}\n      - {name: Plain, href: \"http://plain\"}\nactions: []\n";
    }

    private void stubQb(int leech) {
        upstream.stubFor(post("/api/v2/auth/login").willReturn(ok("Ok.").withHeader("Set-Cookie", "SID=s1; path=/")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/api/v2/transfer/info").willReturn(okJson("{\"dl_info_speed\":10,\"up_info_speed\":5}")));
        StringBuilder l = new StringBuilder("[");
        for (int i = 0; i < leech; i++) {
            l.append(i == 0 ? "" : ",").append("{\"state\":\"downloading\"}");
        }
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/api/v2/torrents/info").willReturn(okJson(l.append("]").toString())));
    }

    @BeforeEach
    void reset() {
        upstream.resetAll();
        clock.now = clock.now.plus(Duration.ofHours(1)); // expires anything cached by earlier tests
        store.save(yaml(upstream.port()), store.state().sha256(), "ui", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        return (Map<String, Object>) resp.get("data");
    }

    // ---------------------------------------------------------------- caching

    @Test
    void oneFetchServesManyReadersUntilTheCacheExpires() {
        stubQb(2);
        var first = service.forItem("g0.0").orElseThrow();
        assertThat(first.get("state")).isEqualTo("ok");
        assertThat(data(first)).containsEntry("leech", 2);
        assertThat(first.get("fetchedAt")).isEqualTo(clock.instant().toString());

        clock.advance(Duration.ofSeconds(10));
        service.forItem("g0.0");
        upstream.verify(1, postRequestedFor(urlEqualTo("/api/v2/auth/login")));

        clock.advance(Duration.ofSeconds(6)); // 16 s in total, past the 15 s TTL
        stubQb(5);
        var later = service.forItem("g0.0").orElseThrow();
        assertThat(data(later)).containsEntry("leech", 5);
        upstream.verify(2, postRequestedFor(urlEqualTo("/api/v2/auth/login")));
    }

    @Test
    void concurrentReadersShareOneUpstreamCall() throws Exception {
        upstream.stubFor(post("/api/v2/auth/login").willReturn(ok("Ok.").withHeader("Set-Cookie", "SID=s1").withFixedDelay(300)));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/api/v2/transfer/info").willReturn(okJson("{}")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/api/v2/torrents/info").willReturn(okJson("[]")));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Map<String, Object>>> jobs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                jobs.add(() -> service.forItem("g0.0").orElseThrow());
            }
            for (Future<Map<String, Object>> f : pool.invokeAll(jobs)) {
                assertThat(f.get().get("state")).isEqualTo("ok");
            }
        } finally {
            pool.shutdownNow();
        }
        upstream.verify(1, postRequestedFor(urlEqualTo("/api/v2/auth/login")));
    }

    // ---------------------------------------------------------------- failures

    @Test
    void whenTheUpstreamDiesTheLastGoodValueIsServedAsStaleForTenMinutes() {
        stubQb(3);
        service.forItem("g0.0");
        upstream.resetAll();
        upstream.stubFor(post("/api/v2/auth/login").willReturn(serverError()));

        clock.advance(Duration.ofMinutes(2));
        var stale = service.forItem("g0.0").orElseThrow();
        assertThat(stale.get("state")).isEqualTo("stale");
        assertThat(data(stale)).containsEntry("leech", 3);
        assertThat(stale).extractingByKey("error").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("code", "bad_response");

        clock.advance(Duration.ofMinutes(9)); // now 11 minutes since the last good value
        var dead = service.forItem("g0.0").orElseThrow();
        assertThat(dead.get("state")).isEqualTo("error");
        assertThat(dead.get("data")).isNull();
    }

    @Test
    void withNoEarlierValueAFailureIsAnErrorWithAReadableCode() {
        upstream.stubFor(post("/api/v2/auth/login").willReturn(ok("Fails.")));
        var r = service.forItem("g0.0").orElseThrow();
        assertThat(r.get("state")).isEqualTo("error");
        assertThat(r.toString()).contains("unauthorized").doesNotContain("qb-secret-pass").doesNotContain("localhost:" + upstream.port());
    }

    @Test
    void aMissingSecretShowsUpAsAnErrorNamingTheSecretOnly() {
        store.save(yaml(upstream.port()).replace("QB_PW", "NOPE_MISSING"), store.state().sha256(), "ui", null);
        var r = service.forItem("g0.0").orElseThrow();
        assertThat(r.get("state")).isEqualTo("error");
        assertThat(r.toString()).contains("secret_missing").contains("NOPE_MISSING");
    }

    @Test
    void editingTheWidgetInTheConfigBypassesTheOldCache() {
        stubQb(1);
        service.forItem("g0.0");
        store.save(yaml(upstream.port()).replace("username: admin", "username: someone-else"), store.state().sha256(), "ui", null);
        service.forItem("g0.0");
        upstream.verify(2, postRequestedFor(urlEqualTo("/api/v2/auth/login")));
    }

    // ---------------------------------------------------------------- lookup rules

    @Test
    void unknownItemsAndItemsWithoutAWidgetHaveNoData() {
        assertThat(service.forItem("g9.9")).isEmpty();
        assertThat(service.forItem("g0.1")).isEmpty();
        assertThat(service.forInfo("search")).isEmpty();
        assertThat(service.forInfo("clock")).isEmpty();
    }

    // ---------------------------------------------------------------- resources (info widget)

    @Test
    void resourcesUsesTheConfiguredOptionsAndIsCached() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpu", Map.of("percent", 12.5));
        when(metrics.read(anySet(), any(), any())).thenReturn(m);

        var r = service.forInfo("resources").orElseThrow();
        assertThat(r.get("state")).isEqualTo("ok");
        assertThat(data(r)).containsKey("cpu");
        verify(metrics).read(eq(Set.of("cpu", "memory", "disk", "cputemp")), eq("/"), eq("imperial"));

        service.forInfo("resources");
        verify(metrics, times(1)).read(anySet(), any(), any());
    }

    @Test
    void aCrashingMetricsReaderBecomesAnErrorNotA500() {
        when(metrics.read(anySet(), any(), any())).thenThrow(new IllegalStateException("boom /proc/secret-path"));
        var r = service.forInfo("resources").orElseThrow();
        assertThat(r.get("state")).isEqualTo("error");
        assertThat(r.toString()).doesNotContain("secret-path");
    }

    // ---------------------------------------------------------------- HTTP

    @Test
    void theApiNeedsALoginAndWorksWithDottedItemIds() throws Exception {
        stubQb(1);
        mvc.perform(get("/api/widgets/item/g0.0")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/widgets/item/g0.0").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ok"))
                .andExpect(jsonPath("$.data.leech").value(1));
        mvc.perform(get("/api/widgets/item/g0.1").with(user("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/widgets/info/search").with(user("admin"))).andExpect(status().isNotFound());
    }

    @Test
    void theApiNeverContainsCredentialsOrTheUpstreamAddress() throws Exception {
        stubQb(1);
        String body = mvc.perform(get("/api/widgets/item/g0.0").with(user("admin"))).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("qb-secret-pass").doesNotContain("admin").doesNotContain("localhost").doesNotContain("secret:");
    }
}
