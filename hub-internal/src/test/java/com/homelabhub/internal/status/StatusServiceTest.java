package com.homelabhub.internal.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.config.DashboardConfig.StatusCheck;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"hub.status.auto-start=false", "hub.status.check-timeout-seconds=1"})
class StatusServiceTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired StatusService service;
    @Autowired ConfigStore store;
    @MockitoBean Checker checker;
    @MockitoBean ProxmoxClient proxmox;

    private void config(String yaml) {
        store.save(yaml, store.state().sha256(), "ui", null);
    }

    private static String doc(String defaults, String items, String actions) {
        return "version: 1\n" + defaults + "groups:\n  - name: G\n    items:\n" + items + "actions:\n" + actions;
    }

    private String state(String id) {
        return (String) service.snapshot().get(id).get("state");
    }

    @BeforeEach
    void resetChecker() {
        org.mockito.Mockito.reset(checker);
        when(checker.run(any())).thenReturn(CheckResult.up(5));
    }

    @Test
    void everythingIsUnknownUntilTheFirstRound() {
        config(doc("", "      - {name: A, status: {tcp: \"h:1\"}}\n", " []\n").replace("actions:\n []", "actions: []"));
        assertThat(state("item:g0.0")).isEqualTo("unknown");
        assertThat(service.snapshot().get("item:g0.0").get("detail")).isEqualTo("not checked yet");
        assertThat(service.snapshot().get("item:g0.0").get("checkedAt")).isNull();
    }

    @Test
    void itemsAndActionsWithAStatusGetALightAndOthersDoNot() {
        config(doc("", "      - {name: A, status: {tcp: \"h:1\"}}\n      - {name: B, href: \"http://b\"}\n",
                "  - {id: w, label: W, type: wol, mac: \"AA:BB:CC:DD:EE:FF\", status: {icmp: 10.0.0.2}}\n  - {id: n, label: N, type: proxmox-node, node: pve, command: reboot}\n"));
        assertThat(service.ids()).containsExactlyInAnyOrder("item:g0.0", "action:w");
        assertThat(service.tracks("item:g0.1")).isFalse();
        assertThat(service.tracks("action:n")).isFalse();
    }

    @Test
    void aRoundSetsTheStateAndSinceOnlyMovesWhenTheStateChanges() throws Exception {
        config(doc("", "      - {name: A, status: {tcp: \"h:1\"}}\n", "").replace("actions:\n", "actions: []\n"));
        service.runRound();
        var first = service.snapshot().get("item:g0.0");
        assertThat(first.get("state")).isEqualTo("up");
        assertThat(first.get("latencyMs")).isEqualTo(5);

        Thread.sleep(5);
        service.runRound();
        var second = service.snapshot().get("item:g0.0");
        assertThat(second.get("since")).as("still up, so 'since' does not move").isEqualTo(first.get("since"));
        assertThat(second.get("checkedAt")).isNotEqualTo(first.get("checkedAt"));

        when(checker.run(any())).thenReturn(CheckResult.down("connection refused"));
        Thread.sleep(5);
        service.runRound();
        var third = service.snapshot().get("item:g0.0");
        assertThat(third.get("state")).isEqualTo("down");
        assertThat(third.get("detail")).isEqualTo("connection refused");
        assertThat(third.get("since")).isNotEqualTo(first.get("since"));
    }

    @Test
    void identicalChecksAreRunOnceAndSharedByEveryItemThatUsesThem() {
        config(doc("", "      - {name: A, status: {icmp: 10.0.0.9}}\n      - {name: B, status: {icmp: 10.0.0.9}}\n      - {name: C, status: {icmp: 10.0.0.9, expect: 200-300}}\n", "")
                .replace("actions:\n", "actions: []\n"));
        service.runRound();
        verify(checker, times(2)).run(any()); // C differs only in `expect`, so it is a distinct key
        assertThat(state("item:g0.0")).isEqualTo("up");
        assertThat(state("item:g0.1")).isEqualTo("up");
    }

    @Test
    void aCrashingCheckIsUnknownForThatCheckOnly() {
        config(doc("", "      - {name: A, status: {tcp: \"good:1\"}}\n      - {name: B, status: {tcp: \"bad:2\"}}\n", "").replace("actions:\n", "actions: []\n"));
        when(checker.run(any())).thenAnswer(inv -> {
            StatusCheck c = inv.getArgument(0);
            if (c.target().startsWith("bad")) {
                throw new IllegalStateException("boom secret-detail");
            }
            return CheckResult.up(1);
        });
        service.runRound();
        assertThat(state("item:g0.0")).isEqualTo("up");
        assertThat(state("item:g0.1")).isEqualTo("unknown");
        assertThat((String) service.snapshot().get("item:g0.1").get("detail")).startsWith("check crashed").doesNotContain("secret-detail");
    }

    @Test
    void aNullResultIsUnknown() {
        config(doc("", "      - {name: A, status: {tcp: \"h:1\"}}\n", "").replace("actions:\n", "actions: []\n"));
        when(checker.run(any())).thenReturn(null);
        service.runRound();
        assertThat(state("item:g0.0")).isEqualTo("unknown");
    }

    @Test
    void aCheckThatNeverReturnsIsCutOffAndDoesNotBlockTheOthers() throws Exception {
        config(doc("", "      - {name: A, status: {tcp: \"fast:1\"}}\n      - {name: B, status: {tcp: \"hang:2\"}}\n", "").replace("actions:\n", "actions: []\n"));
        CountDownLatch never = new CountDownLatch(1);
        when(checker.run(any())).thenAnswer(inv -> {
            StatusCheck c = inv.getArgument(0);
            if (c.target().startsWith("hang")) {
                never.await();
            }
            return CheckResult.up(1);
        });
        long t0 = System.nanoTime();
        service.runRound();
        never.countDown();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(4000);
        assertThat(state("item:g0.0")).isEqualTo("up");
        assertThat(state("item:g0.1")).isEqualTo("unknown");
        assertThat((String) service.snapshot().get("item:g0.1").get("detail")).isEqualTo("check did not finish");
    }

    @Test
    void autoStatusDerivesATcpCheckFromHttpLinksOnly() {
        config(doc("defaults: {status: auto}\n", "      - {name: Web, href: \"http://192.168.10.5:8080/x\"}\n      - {name: Secure, href: \"https://svc.lan/\"}\n"
                + "      - {name: Rel, href: /portfolio}\n      - {name: NoLink}\n      - {name: Act, action: w, href: \"http://ignored\"}\n",
                "  - {id: w, label: W, type: wol, mac: \"AA:BB:CC:DD:EE:FF\"}\n"));
        assertThat(service.ids()).containsExactlyInAnyOrder("item:g0.0", "item:g0.1");
        service.runRound();
        var c = org.mockito.ArgumentCaptor.forClass(StatusCheck.class);
        verify(checker, atLeastOnce()).run(c.capture());
        assertThat(c.getAllValues()).extracting(StatusCheck::target).containsExactlyInAnyOrder("192.168.10.5:8080", "svc.lan:443");
        assertThat(c.getAllValues()).allMatch(x -> x.kind() == StatusCheck.Kind.TCP);
    }

    @Test
    void autoStatusStaysOffByDefaultAndAnExplicitCheckWins() {
        config(doc("defaults: {status: none}\n", "      - {name: Web, href: \"http://h:1\"}\n      - {name: Own, href: \"http://h:2\", status: {icmp: 10.1.1.1}}\n", "")
                .replace("actions:\n", "actions: []\n"));
        assertThat(service.ids()).containsExactly("item:g0.1");
    }

    @Test
    void changingTheConfigDropsStaleResultsAndKeepsTheUnchangedOnes() throws Exception {
        config(doc("", "      - {name: A, status: {tcp: \"a:1\"}}\n      - {name: B, status: {tcp: \"b:2\"}}\n", "").replace("actions:\n", "actions: []\n"));
        service.runRound();
        String sinceA = (String) service.snapshot().get("item:g0.0").get("since");

        config(doc("", "      - {name: A, status: {tcp: \"a:1\"}}\n", "").replace("actions:\n", "actions: []\n"));
        assertThat(service.ids()).containsExactly("item:g0.0");
        assertThat(service.snapshot().get("item:g0.0").get("since")).isEqualTo(sinceA);
    }

    @Test
    void onlyOneRoundRunsAtATime() throws Exception {
        config(doc("", "      - {name: A, status: {tcp: \"h:1\"}}\n", "").replace("actions:\n", "actions: []\n"));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        when(checker.run(any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            inside.countDown();
            release.await();
            return CheckResult.up(1);
        });
        Thread first = new Thread(service::runRound);
        first.start();
        assertThat(inside.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        service.runRound(); // returns immediately
        release.countDown();
        first.join(3000);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void theManualRefreshHasACooldown() throws Exception {
        config(doc("", "      - {name: A, status: {tcp: \"h:1\"}}\n", "").replace("actions:\n", "actions: []\n"));
        Thread.sleep(5100); // let any cooldown from earlier tests expire
        assertThat(service.refreshNow()).isTrue();
        Thread.sleep(200);
        assertThat(service.refreshNow()).isFalse();
    }
}
