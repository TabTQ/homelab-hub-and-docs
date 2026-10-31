package com.homelabhub.docs.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homelabhub.docs.DocsAudit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reconcile against a fake Cloudflare: lifecycle, drift repair, safety, dry run, failures and concurrency. */
class CloudflareRoutesTest {

    static final String TARGET = FakeCloudflare.TUNNEL + ".cfargotunnel.com";

    FakeCloudflare cf;
    DocsAudit audit;

    @BeforeEach
    void start() throws Exception {
        cf = new FakeCloudflare();
        audit = mock(DocsAudit.class);
    }

    @AfterEach
    void stop() {
        cf.close();
    }

    CloudflareRoutes routes(boolean dryRun) {
        CloudflareProperties p = cf.props(dryRun);
        return new CloudflareRoutes(p, audit, new CloudflareClient(p));
    }

    String state(CloudflareRoutes r, String host) {
        Map<String, Object> m = r.routeInfo(host);
        return m == null ? null : (String) m.get("state");
    }

    @Test
    void publishAddsTheIngressRuleAndAMarkedProxiedCname() {
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("doc1.example.test"), "test");

        assertThat(cf.hostnames()).containsExactly("portfolio.example.test", "doc1.example.test", "*.example.test", "");
        ObjectNode rec = cf.dnsNamed("doc1.example.test");
        assertThat(rec.path("type").asText()).isEqualTo("CNAME");
        assertThat(rec.path("content").asText()).isEqualTo(TARGET);
        assertThat(rec.path("proxied").asBoolean()).isTrue();
        assertThat(rec.path("comment").asText()).isEqualTo(CloudflareProperties.MARKER);
        assertThat(state(r, "doc1.example.test")).isEqualTo("live");
        verify(audit).record(eq("cf-ingress-add"), eq("doc1.example.test"), eq("ok"), any(), any(), any());
        verify(audit).record(eq("cf-dns-add"), eq("doc1.example.test"), eq("ok"), any(), any(), any());
    }

    @Test
    void publishingAnAlreadyRoutedHostIsANoOp() {
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("doc1.example.test"), "test");
        cf.requests.clear();
        r.reconcile(Set.of("doc1.example.test"), "test");
        assertThat(cf.mutations()).isEmpty();
        assertThat(state(r, "doc1.example.test")).isEqualTo("live");
    }

    @Test
    void unpublishRemovesBothAndLeavesEverythingElse() {
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("doc1.example.test", "doc2.example.test"), "test");
        r.reconcile(Set.of("doc2.example.test"), "test");

        assertThat(cf.hostnames()).containsExactly("portfolio.example.test", "doc2.example.test", "*.example.test", "");
        assertThat(cf.dnsNamed("doc1.example.test")).isNull();
        assertThat(cf.dnsNamed("doc2.example.test")).isNotNull();
        assertThat(r.routeInfo("doc1.example.test")).isNull();

        cf.requests.clear();
        r.reconcile(Set.of("doc2.example.test"), "test"); // unpublishing a host that has no route is a no-op
        assertThat(cf.mutations()).isEmpty();
        // the pre-existing records and the wildcard are untouched
        for (String n : List.of("portfolio.example.test", "*.example.test", "example.test", "www.example.test")) {
            assertThat(cf.dnsNamed(n)).as(n).isNotNull();
        }
        assertThat(cf.config.path("warp-routing").path("enabled").asBoolean(true)).isFalse();
    }

    @Test
    void reconcileRepairsDriftButOnlyTouchesWhatIsManaged() {
        // stale managed route + record, an unmarked CNAME to the tunnel, an unmarked stale docs-public rule with extras
        cf.dns.add(FakeCloudflare.record("50", "CNAME", "gone.example.test", TARGET, CloudflareProperties.MARKER));
        cf.dns.add(FakeCloudflare.record("51", "CNAME", "manual.example.test", TARGET, "made by hand"));
        cf.dns.add(FakeCloudflare.record("52", "CNAME", "other.example.test", "elsewhere.example.net", CloudflareProperties.MARKER));
        cf.config = (ObjectNode) FakeCloudflare.parse("""
                {"ingress":[
                  {"hostname":"portfolio.example.test","service":"http://hub-public:3011"},
                  {"hostname":"gone.example.test","service":"http://docs-public:3011"},
                  {"hostname":"deco.example.test","service":"http://docs-public:3011","originRequest":{}},
                  {"hostname":"*.example.test","service":"http://docs-public:3011"},
                  {"service":"http_status:404"}]}""");
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("doc1.example.test"), "startup");

        assertThat(cf.hostnames()).containsExactly("portfolio.example.test", "deco.example.test", "doc1.example.test", "*.example.test", "");
        assertThat(cf.dnsNamed("gone.example.test")).isNull();
        assertThat(cf.dnsNamed("manual.example.test")).isNotNull(); // no marker: never deleted
        assertThat(cf.dnsNamed("other.example.test")).isNotNull(); // marker but not our tunnel: never deleted
        assertThat(cf.dnsNamed("doc1.example.test")).isNotNull();
    }

    @Test
    void reservedAndInvalidHostsAreNeverRoutedAndReportAnError() {
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("www.example.test", "portfolio.example.test", "*.example.test", "a.b.example.test", "doc1.example.test"), "test");

        assertThat(state(r, "www.example.test")).isEqualTo("error");
        assertThat(state(r, "portfolio.example.test")).isEqualTo("error");
        assertThat(state(r, "*.example.test")).isEqualTo("error");
        assertThat(state(r, "a.b.example.test")).isEqualTo("error");
        assertThat(state(r, "doc1.example.test")).isEqualTo("live");
        assertThat(cf.hostnames()).containsExactly("portfolio.example.test", "doc1.example.test", "*.example.test", "");
        assertThat(cf.dnsNamed("www.example.test").path("content").asText()).isEqualTo("example.test"); // untouched
    }

    @Test
    void dryRunSendsNoMutatingRequest() {
        cf.dns.add(FakeCloudflare.record("50", "CNAME", "gone.example.test", TARGET, CloudflareProperties.MARKER));
        CloudflareRoutes r = routes(true);
        r.reconcile(Set.of("doc1.example.test"), "test");

        assertThat(cf.mutations()).isEmpty();
        assertThat(cf.requests).isNotEmpty(); // it still reads
        assertThat(state(r, "doc1.example.test")).isEqualTo("planned");
        assertThat(cf.dnsNamed("gone.example.test")).isNotNull();
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTunnelApiFailureIsAnErrorStatusNotACrashAndRetrySucceeds() {
        CloudflareRoutes r = routes(false);
        cf.failPrefix = "PUT ";
        r.reconcile(Set.of("doc1.example.test"), "test");

        assertThat(state(r, "doc1.example.test")).isEqualTo("error");
        assertThat((String) r.routeInfo("doc1.example.test").get("message")).contains("500").contains("boom").doesNotContain(FakeCloudflare.TOKEN);
        assertThat(cf.dnsNamed("doc1.example.test")).isNull(); // no DNS for a host with no ingress rule
        verify(audit).record(eq("cf-ingress-add"), eq("doc1.example.test"), eq("failed"), contains("boom"), any(), any());

        cf.failPrefix = null;
        r.reconcile(Set.of("doc1.example.test"), "retry");
        assertThat(state(r, "doc1.example.test")).isEqualTo("live");
        assertThat(cf.dnsNamed("doc1.example.test")).isNotNull();
    }

    @Test
    void aBadTokenAndAnUnreachableApiBecomeErrorsToo() throws Exception {
        CloudflareProperties bad = new CloudflareProperties(true, false, "wrong-token", cf.baseUrl(), FakeCloudflare.ACCOUNT, FakeCloudflare.ZONE,
                FakeCloudflare.TUNNEL, FakeCloudflare.ZONE_NAME, null, "");
        CloudflareRoutes r = new CloudflareRoutes(bad, audit, new CloudflareClient(bad));
        r.reconcile(Set.of("doc1.example.test"), "test");
        assertThat((String) r.routeInfo("doc1.example.test").get("message")).contains("403").doesNotContain("wrong-token");

        FakeCloudflare dead = cf;
        String url = dead.baseUrl();
        dead.close();
        CloudflareProperties p = new CloudflareProperties(true, false, FakeCloudflare.TOKEN, url, FakeCloudflare.ACCOUNT, FakeCloudflare.ZONE,
                FakeCloudflare.TUNNEL, FakeCloudflare.ZONE_NAME, null, "");
        CloudflareRoutes r2 = new CloudflareRoutes(p, audit, new CloudflareClient(p));
        r2.reconcile(Set.of("doc1.example.test"), "test");
        assertThat(state(r2, "doc1.example.test")).isEqualTo("error");
        assertThat((String) r2.routeInfo("doc1.example.test").get("message")).contains("not reachable");
        cf = new FakeCloudflare(); // so @AfterEach has something to close
    }

    @Test
    void anExistingForeignDnsRecordIsAConflictAndIsNeverTouched() {
        cf.dns.add(FakeCloudflare.record("60", "A", "doc1.example.test", "192.0.2.9", ""));
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("doc1.example.test"), "test");

        assertThat(state(r, "doc1.example.test")).isEqualTo("error");
        assertThat((String) r.routeInfo("doc1.example.test").get("message")).contains("not managed by Docs");
        assertThat(cf.dnsNamed("doc1.example.test").path("content").asText()).isEqualTo("192.0.2.9");
        assertThat(cf.mutations().stream().filter(m -> m.startsWith("DELETE"))).isEmpty();
    }

    @Test
    void anAlreadyExistingRecordPointingAtTheTunnelCountsAsLiveAndIsNotDeletedLater() {
        cf.dns.add(FakeCloudflare.record("60", "CNAME", "doc1.example.test", TARGET, "made by hand"));
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("doc1.example.test"), "test");
        assertThat(state(r, "doc1.example.test")).isEqualTo("live");
        r.reconcile(Set.of(), "test");
        assertThat(cf.dnsNamed("doc1.example.test")).isNotNull(); // not ours: left alone
        assertThat(cf.hostnames()).doesNotContain("doc1.example.test"); // but the docs-public rule we added is gone
    }

    @Test
    void aFailedDnsDeleteIsReportedAndRetried() {
        CloudflareRoutes r = routes(false);
        r.reconcile(Set.of("doc1.example.test"), "test");
        cf.failPrefix = "DELETE ";
        r.reconcile(Set.of(), "test");
        assertThat(state(r, "doc1.example.test")).isEqualTo("error");
        assertThat(cf.dnsNamed("doc1.example.test")).isNotNull();
        cf.failPrefix = null;
        r.reconcile(Set.of(), "retry");
        assertThat(cf.dnsNamed("doc1.example.test")).isNull();
        assertThat(r.routeInfo("doc1.example.test")).isNull();
    }

    @Test
    void concurrentReconcilesNeverInterleaveAndLeaveAValidConfig() throws Exception {
        CloudflareRoutes r = routes(false);
        cf.delayMillis = 5;
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Set<String> mine = Set.of("keep.example.test", "doc" + i + ".example.test");
            fs.add(pool.submit(() -> {
                go.await();
                r.reconcile(mine, "test");
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(cf.maxInFlight.get()).as("requests from different reconciles overlapped").isEqualTo(1);
        List<String> hosts = cf.hostnames();
        assertThat(hosts.get(0)).isEqualTo("portfolio.example.test");
        assertThat(hosts.get(hosts.size() - 1)).isEmpty(); // catch-all still last
        assertThat(hosts).contains("keep.example.test", "*.example.test");
        assertThat(hosts.stream().filter(h -> h.startsWith("doc")).count()).isEqualTo(1); // only the last winner's host remains
        assertThat(hosts.stream().distinct().count()).isEqualTo(hosts.size());
    }

    @Test
    void asyncRequestSyncSettlesAndSkipsApiCallsWhenNothingChanged() throws Exception {
        CloudflareRoutes r = routes(false);
        r.requestSync(Set.of("doc1.example.test"));
        assertThat(state(r, "doc1.example.test")).isIn("pending", "live");
        await(() -> "live".equals(state(r, "doc1.example.test")));

        cf.requests.clear();
        r.requestSync(Set.of("doc1.example.test")); // a republish of the same page
        Thread.sleep(150);
        assertThat(cf.requests).isEmpty();

        r.retry(); // the retry button re-checks even then
        await(() -> !cf.requests.isEmpty());
        r.stop();
    }

    @Test
    void whenDisabledNothingHappensAndHostsAreNotRestricted() {
        CloudflareProperties off = new CloudflareProperties(null, null, null, cf.baseUrl(), null, null, null, null, null, null);
        CloudflareRoutes r = new CloudflareRoutes(off, audit);
        r.requestSync(Set.of("doc1.example.test"));
        r.retry();
        assertThat(cf.requests).isEmpty();
        assertThat(r.hostError("www.example.test")).isNull();
        assertThat(r.routeInfo("doc1.example.test")).isNull();
        assertThat(r.overview()).containsEntry("enabled", false);
    }

    @Test
    void enabledButUnconfiguredReportsAnErrorInsteadOfSilentSuccess() {
        CloudflareProperties p = new CloudflareProperties(true, false, "", cf.baseUrl(), "", "", "", FakeCloudflare.ZONE_NAME, null, "");
        CloudflareRoutes r = new CloudflareRoutes(p, audit);
        r.reconcile(Set.of("doc1.example.test"), "test");
        assertThat(state(r, "doc1.example.test")).isEqualTo("error");
        assertThat(cf.requests).isEmpty();
    }

    @Test
    void theTokenNeverShowsInToString() {
        assertThat(cf.props(false).toString()).doesNotContain(FakeCloudflare.TOKEN).contains("masked");
    }

    static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }
}
