package com.homelabhub.internal.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.legacy.InFlightGuard;
import com.homelabhub.internal.proxmox.GuestStatus;
import com.homelabhub.internal.proxmox.GuestType;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.proxmox.ProxmoxClient.GuestInfo;
import com.homelabhub.internal.proxmox.ProxmoxException;
import com.homelabhub.internal.widgets.HostMetrics;
import com.homelabhub.internal.wol.MacAddress;
import com.homelabhub.internal.wol.WakeOnLanSender;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"hub.status.auto-start=false", "hub.actions.task-poll-millis=40"})
@AutoConfigureMockMvc
@Import(ActionsApiTest.Clocks.class)
class ActionsApiTest {

    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-24T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
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

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    static final String UPID = "UPID:pve:0001A2B3:00C0FFEE:66F1234A:vzstop:100:hub@pve!homepage:";

    static final String YAML = """
            version: 1
            groups: []
            actions:
              - {id: wake, label: Wake Proxmox, type: wol, mac: "aa:bb:cc:dd:ee:01"}
              - {id: wake-quiet, label: Quiet wake, type: wol, mac: "aa:bb:cc:dd:ee:ff", confirm: none}
              - {id: pve-off, label: Shutdown, type: proxmox-node, node: pve, command: shutdown}
              - {id: pve-reboot, label: Reboot, type: proxmox-node, node: pve, command: reboot, confirm: click}
              - {id: apps, label: Apps LXC, type: proxmox-guest, node: pve, kind: lxc, vmid: 100}
              - {id: media, label: Media LXC, type: proxmox-guest, node: pve, kind: lxc, vmid: 210, stop_mode: shutdown, confirm: click}
            """;

    @Autowired MockMvc mvc;
    @Autowired ConfigStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired InFlightGuard inFlight;
    @MockitoBean ProxmoxClient proxmox;
    @MockitoBean WakeOnLanSender wol;
    @MockitoBean HostMetrics metrics;

    MockHttpSession session;

    @BeforeEach
    void setUp() {
        reset(proxmox, wol);
        jdbc.update("DELETE FROM audit_log");
        session = new MockHttpSession();
        store.save(YAML, store.state().sha256(), "ui", null);
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        when(proxmox.listGuests("pve")).thenReturn(List.of(
                new GuestInfo(100, "UbuntuCT", GuestType.LXC, GuestStatus.RUNNING),
                new GuestInfo(101, "n8n", GuestType.LXC, GuestStatus.STOPPED),
                new GuestInfo(102, "postgres-turnkey", GuestType.LXC, GuestStatus.RUNNING),
                new GuestInfo(999, "pbs", GuestType.LXC, GuestStatus.RUNNING)));
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenReturn(GuestStatus.RUNNING);
        when(proxmox.guestStatus("pve", GuestType.LXC, 210)).thenReturn(GuestStatus.STOPPED);
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode prepare(String id) throws Exception {
        MvcResult r = mvc.perform(post("/api/actions/" + id + "/prepare").with(user("admin")).with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String id, String token, String typed, MockHttpSession s) throws Exception {
        String body = json.writeValueAsString(typed == null ? Map.of("token", token) : Map.of("token", token, "typed", typed));
        return mvc.perform(post("/api/actions/" + id + "/confirm").with(user("admin")).with(csrf()).session(s)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int audit(String result) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE source='ui' AND result=?", Integer.class, result);
    }

    // ---------------------------------------------------------------- security

    @Test
    void everythingNeedsALoginAndPostsNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/api/actions/wake/prepare").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/actions/wake/prepare").with(user("admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/actions/tasks/x")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownActionIs404() throws Exception {
        mvc.perform(post("/api/actions/ghost/prepare").with(user("admin")).with(csrf()).session(session))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("unknown_action"));
    }

    // ---------------------------------------------------------------- prepare

    @Test
    void wakeDescribesItselfAndAsksForAClick() throws Exception {
        JsonNode p = prepare("wake");
        assertThat(p.get("title").asText()).isEqualTo("Wake Wake Proxmox");
        assertThat(p.get("kind").asText()).isEqualTo("wake");
        assertThat(p.get("confirm").asText()).isEqualTo("click");
        assertThat(p.get("word").isNull()).isTrue();
        assertThat(p.get("impact").toString()).contains("AA:BB:CC:DD:EE:01");
        assertThat(p.get("token").asText()).hasSize(22);
        assertThat(p.get("expiresAt").asText()).isNotEmpty();
    }

    @Test
    void shutdownNamesEveryRunningGuestAndRequiresTypingTheNodeName() throws Exception {
        JsonNode p = prepare("pve-off");
        assertThat(p.get("confirm").asText()).isEqualTo("type-name");
        assertThat(p.get("word").asText()).isEqualTo("pve");
        String impact = p.get("impact").toString();
        assertThat(impact).contains("3 guests are running and will go down with the host")
                .contains("UbuntuCT (100)").contains("postgres-turnkey (102)").contains("pbs (999)").doesNotContain("n8n");
        assertThat(impact).contains("will not come back until you wake it");
    }

    @Test
    void rebootCanBeConfiguredToAClickAndSaysDowntime() throws Exception {
        JsonNode p = prepare("pve-reboot");
        assertThat(p.get("confirm").asText()).isEqualTo("click");
        assertThat(p.get("word").isNull()).isTrue();
        assertThat(p.get("impact").toString()).contains("will be interrupted").contains("few minutes of downtime");
    }

    @Test
    void ifGuestsCannotBeListedTheImpactSaysSoAndTheActionStillWorks() throws Exception {
        when(proxmox.listGuests("pve")).thenThrow(new ProxmoxException(ProxmoxException.Kind.UNREACHABLE, "x"));
        JsonNode p = prepare("pve-off");
        assertThat(p.get("impact").toString()).contains("Could not list the running guests").contains("Proxmox did not answer");
        assertThat(p.get("token").asText()).hasSize(22);
    }

    @Test
    void stoppingARunningGuestWarnsAboutTheHardStopAndAsksForTheId() throws Exception {
        JsonNode p = prepare("apps");
        assertThat(p.get("title").asText()).isEqualTo("Stop Apps LXC (LXC 100)");
        assertThat(p.get("desired").asText()).isEqualTo("stopped");
        assertThat(p.get("confirm").asText()).isEqualTo("type-name");
        assertThat(p.get("word").asText()).isEqualTo("100");
        assertThat(p.get("impact").toString()).contains("immediate power-off");
    }

    @Test
    void startingAStoppedGuestIsAClick() throws Exception {
        JsonNode p = prepare("media");
        assertThat(p.get("title").asText()).isEqualTo("Start Media LXC (LXC 210)");
        assertThat(p.get("desired").asText()).isEqualTo("running");
        assertThat(p.get("confirm").asText()).isEqualTo("click");
        assertThat(p.get("impact").toString()).contains("Starts the lxc");
    }

    @Test
    void aGuestInATransitionalStateCannotBePrepared() throws Exception {
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenReturn(GuestStatus.STARTING);
        mvc.perform(post("/api/actions/apps/prepare").with(user("admin")).with(csrf()).session(session))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("not_toggleable"))
                .andExpect(jsonPath("$.message").value(containsString("starting")));
    }

    @Test
    void prepareReportsProxmoxProblemsInPlainLanguage() throws Exception {
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenThrow(new ProxmoxException(ProxmoxException.Kind.UNREACHABLE, "x"));
        mvc.perform(post("/api/actions/apps/prepare").with(user("admin")).with(csrf()).session(session))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("proxmox_unreachable"))
                .andExpect(jsonPath("$.message").value(containsString("Is the host on?")));
    }

    // ---------------------------------------------------------------- confirm: wake

    @Test
    void wakeSendsThePacketOnceAndTheTokenCannotBeReused() throws Exception {
        String token = prepare("wake").get("token").asText();
        confirm("wake", token, null, session).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value(containsString("Wake packet sent")));
        verify(wol).send(MacAddress.parse("AA:BB:CC:DD:EE:01"));
        confirm("wake", token, null, session).andExpect(status().isGone()).andExpect(jsonPath("$.error").value("token_expired"));
        verify(wol, org.mockito.Mockito.times(1)).send(any());
        assertThat(audit("ok")).isEqualTo(1);
    }

    @Test
    void aFailingWakePacketIsReportedAndAudited() throws Exception {
        doThrow(new com.homelabhub.internal.wol.WakeOnLanException("io")).when(wol).send(any());
        confirm("wake", prepare("wake").get("token").asText(), null, session).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("wol_failed"));
        assertThat(audit("failed")).isEqualTo(1);
    }

    @Test
    void confirmNoneStillGoesThroughATokenSoAForgedRequestCannotSkipIt() throws Exception {
        JsonNode p = prepare("wake-quiet");
        assertThat(p.get("confirm").asText()).isEqualTo("none");
        mvc.perform(post("/api/actions/wake-quiet/confirm").with(user("admin")).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isGone());
        verify(wol, never()).send(any());
        confirm("wake-quiet", p.get("token").asText(), null, session).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- confirm: typing

    @Test
    void shutdownNeedsTheExactNameAndNothingRunsOnAMismatch() throws Exception {
        String token = prepare("pve-off").get("token").asText();
        confirm("pve-off", token, "PVE", session).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("typed_mismatch"));
        confirm("pve-off", token, null, session).andExpect(status().isUnprocessableEntity());
        verify(proxmox, never()).nodeCommand(anyString(), anyString());

        confirm("pve-off", token, "pve", session).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Shutdown command sent to pve."));
        verify(proxmox).nodeCommand("pve", "shutdown");
    }

    @Test
    void threeWrongAnswersEndTheConfirmation() throws Exception {
        String token = prepare("pve-off").get("token").asText();
        confirm("pve-off", token, "a", session).andExpect(status().isUnprocessableEntity());
        confirm("pve-off", token, "b", session).andExpect(status().isUnprocessableEntity());
        confirm("pve-off", token, "c", session).andExpect(status().isTooManyRequests());
        confirm("pve-off", token, "pve", session).andExpect(status().isGone());
        verify(proxmox, never()).nodeCommand(anyString(), anyString());
    }

    // ---------------------------------------------------------------- confirm: token binding

    @Test
    void anotherSessionCannotUseMyTokenButICan() throws Exception {
        String token = prepare("pve-reboot").get("token").asText();
        confirm("pve-reboot", token, null, new MockHttpSession()).andExpect(status().isForbidden());
        verify(proxmox, never()).nodeCommand(anyString(), anyString());
        confirm("pve-reboot", token, null, session).andExpect(status().isOk());
        verify(proxmox).nodeCommand("pve", "reboot");
    }

    @Test
    void aTokenIsBoundToItsActionAndExpiresAfterThirtySeconds() throws Exception {
        String token = prepare("wake").get("token").asText();
        confirm("pve-reboot", token, null, session).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("wrong_action"));
        String t2 = prepare("pve-reboot").get("token").asText();
        clock.now = clock.now.plusSeconds(31);
        confirm("pve-reboot", t2, null, session).andExpect(status().isGone());
        verify(proxmox, never()).nodeCommand(anyString(), anyString());
    }

    @Test
    void ifTheActionDisappearsFromTheConfigWhileConfirmingNothingRuns() throws Exception {
        String token = prepare("pve-reboot").get("token").asText();
        store.save(YAML.replace("  - {id: pve-reboot, label: Reboot, type: proxmox-node, node: pve, command: reboot, confirm: click}\n", ""),
                store.state().sha256(), "ui", null);
        confirm("pve-reboot", token, null, session).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("action_changed"));
        verify(proxmox, never()).nodeCommand(anyString(), anyString());
    }

    // ---------------------------------------------------------------- confirm: guests

    @Test
    void stoppingAGuestPowersItOffAndTracksTheRealTaskToItsOutcome() throws Exception {
        when(proxmox.guestAction("pve", GuestType.LXC, 100, "stop")).thenReturn(UPID);
        when(proxmox.taskStatus("pve", UPID)).thenReturn(new ProxmoxClient.TaskStatus(true, null))
                .thenReturn(new ProxmoxClient.TaskStatus(false, "OK"));
        String token = prepare("apps").get("token").asText();
        confirm("apps", token, "100", session).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Stopping (power-off) Apps LXC."))
                .andExpect(jsonPath("$.taskId").value(UPID));
        verify(proxmox).guestAction("pve", GuestType.LXC, 100, "stop");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> mvc.perform(get("/api/actions/tasks/" + UPID).with(user("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OK")));
    }

    @Test
    void aFailedProxmoxTaskIsReportedAsFailedWithItsReason() throws Exception {
        when(proxmox.guestAction("pve", GuestType.LXC, 100, "stop")).thenReturn(UPID);
        when(proxmox.taskStatus("pve", UPID)).thenReturn(new ProxmoxClient.TaskStatus(false, "command 'lxc-stop' failed: exit code 1"));
        confirm("apps", prepare("apps").get("token").asText(), "100", session).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> mvc.perform(get("/api/actions/tasks/" + UPID).with(user("admin")))
                .andExpect(jsonPath("$.state").value("FAILED")).andExpect(jsonPath("$.detail").value(containsString("lxc-stop"))));
    }

    @Test
    void aTaskThatNeverFinishesTimesOutButIsNotReportedAsAFailure() throws Exception {
        String upid = "UPID:pve:0001A2B3:00C0FFEE:66F1234A:vzstart:210:hub@pve!homepage:";
        when(proxmox.guestAction("pve", GuestType.LXC, 210, "start")).thenReturn(upid);
        when(proxmox.taskStatus("pve", upid)).thenAnswer(inv -> {
            clock.now = clock.now.plusSeconds(60);
            return new ProxmoxClient.TaskStatus(true, null);
        });
        confirm("media", prepare("media").get("token").asText(), null, session).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> mvc.perform(get("/api/actions/tasks/" + upid).with(user("admin")))
                .andExpect(jsonPath("$.state").value("TIMEOUT")).andExpect(jsonPath("$.detail").value(containsString("may finish later"))));
    }

    @Test
    void aGuestThatSomeoneElseAlreadyStoppedIsNotStoppedAgainOrStartedByMistake() throws Exception {
        String token = prepare("apps").get("token").asText(); // desired = stopped
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenReturn(GuestStatus.STOPPED);
        confirm("apps", token, "100", session).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Apps LXC is already stopped. Nothing to do."));
        verify(proxmox, never()).guestAction(anyString(), any(), anyInt(), anyString());
    }

    @Test
    void gracefulShutdownIsUsedWhenTheActionAsksForIt() throws Exception {
        when(proxmox.guestStatus("pve", GuestType.LXC, 210)).thenReturn(GuestStatus.RUNNING);
        when(proxmox.guestAction("pve", GuestType.LXC, 210, "shutdown")).thenReturn("");
        JsonNode p = prepare("media");
        assertThat(p.get("impact").toString()).contains("gracefully");
        confirm("media", p.get("token").asText(), null, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Shutting down Media LXC.")).andExpect(jsonPath("$.taskId").doesNotExist());
        verify(proxmox).guestAction("pve", GuestType.LXC, 210, "shutdown");
    }

    @Test
    void aBusyGuestIsRefusedWhileAnotherCommandRuns() throws Exception {
        String token = prepare("apps").get("token").asText();
        try (var lock = inFlight.tryAcquire("guest:pve:LXC:100")) {
            confirm("apps", token, "100", session).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("in_progress"));
        }
        verify(proxmox, never()).guestAction(anyString(), any(), anyInt(), anyString());
    }

    @Test
    void theNodeLockIsSharedWithTheLegacyRoutes() throws Exception {
        String token = prepare("pve-reboot").get("token").asText();
        try (var lock = inFlight.tryAcquire("node:pve")) {
            confirm("pve-reboot", token, null, session).andExpect(status().isConflict());
        }
    }

    // ---------------------------------------------------------------- confirm: Proxmox failures

    @Test
    void proxmoxProblemsBecomeClearStatusCodesAndAreAudited() throws Exception {
        Object[][] cases = {
                {ProxmoxException.Kind.UNAUTHORIZED, 403, "proxmox_unauthorized"},
                {ProxmoxException.Kind.CERT_MISMATCH, 502, "cert_mismatch"},
                {ProxmoxException.Kind.UNREACHABLE, 502, "proxmox_unreachable"},
                {ProxmoxException.Kind.NOT_CONFIGURED, 503, "proxmox_not_configured"},
                {ProxmoxException.Kind.HTTP_ERROR, 502, "proxmox_error"}};
        for (Object[] c : cases) {
            reset(proxmox);
            when(proxmox.listGuests("pve")).thenReturn(List.of());
            doThrow(new ProxmoxException((ProxmoxException.Kind) c[0], "secret upstream detail")).when(proxmox).nodeCommand("pve", "reboot");
            String token = prepare("pve-reboot").get("token").asText();
            MvcResult r = confirm("pve-reboot", token, null, session).andExpect(status().is((Integer) c[1])).andExpect(jsonPath("$.error").value(c[2])).andReturn();
            assertThat(r.getResponse().getContentAsString()).doesNotContain("secret upstream detail");
        }
        assertThat(audit("failed")).isEqualTo(cases.length);
    }

    @Test
    void everySuccessAndFailureLeavesAnAuditRowWithTheBrowser() throws Exception {
        confirm("wake", prepare("wake").get("token").asText(), null, session).andExpect(status().isOk());
        var row = jdbc.queryForMap("SELECT source, action_id, result FROM audit_log ORDER BY id DESC LIMIT 1");
        assertThat(row).containsEntry("source", "ui").containsEntry("action_id", "wake").containsEntry("result", "ok");
    }
}
