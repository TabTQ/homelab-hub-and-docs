package com.homelabhub.internal.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.GuestStatus;
import com.homelabhub.internal.proxmox.GuestType;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.proxmox.ProxmoxException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The legacy wol-server API against the responses recorded from the live service on 2026-09-24
 * (contract/legacy-fixtures/recorded-2026-09-24.txt). Failure bodies are compared as raw strings so that
 * field order and explicit nulls are checked too.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LegacyApiTest {

    private static final Path DIR = TestSupport.tempDir();
    private static final DatagramSocket WOL_RECEIVER = openReceiver();

    private static DatagramSocket openReceiver() {
        try {
            DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            s.setSoTimeout(1500);
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.wol.target", () -> "127.0.0.1");
        r.add("hub.wol.port", WOL_RECEIVER::getLocalPort);
        r.add("hub.wol.repeat", () -> 3);
        r.add("hub.wol.delay-millis", () -> 0);
    }

    @AfterAll
    static void closeReceiver() {
        WOL_RECEIVER.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProxmoxClient proxmox;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
    }

    private static final String NOT_FOUND_BODY =
            "{\"message\":\"Configuration file 'nodes/pve/lxc/99999.conf' does not exist\\n\",\"data\":null}";

    // ---------------------------------------------------------------- Wake-on-LAN

    @Test
    void wakeWithInvalidMacMatchesTheRecordedResponse() throws Exception {
        mvc.perform(get("/wake/mac/zz"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"success\":false,\"message\":\"Invalid MAC address format. Expected: XX:XX:XX:XX:XX:XX or XX-XX-XX-XX-XX-XX\"}"));
    }

    @Test
    void wakePostWithEmptyBodyMatchesTheRecordedResponse() throws Exception {
        mvc.perform(post("/wake").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("{\"success\":false,\"message\":\"Failed to send WOL packet: Invalid MAC address format: null. Expected format: XX:XX:XX:XX:XX:XX or XX-XX-XX-XX-XX-XX\"}"));
    }

    @Test
    void wakeSendsThreeRealPacketsAndUsesTheDocumentedMessage() throws Exception {
        drain();
        mvc.perform(get("/wake/mac/AA:BB:CC:DD:EE:01").param("target", "Proxmox Server"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":true,\"message\":\"WOL packet sent successfully to Proxmox Server (MAC: AA:BB:CC:DD:EE:01)\"}"));

        List<byte[]> packets = receive(3);
        assertThat(packets).hasSize(3).allSatisfy(p -> {
            assertThat(p).hasSize(102);
            assertThat(p[0]).isEqualTo((byte) 0xFF);
            assertThat(p[6]).isEqualTo((byte) 0xAA);
            assertThat(p[11]).isEqualTo((byte) 0x01);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE source='legacy' AND action_id='wake' AND result='ok'", Integer.class)).isEqualTo(1);
    }

    @Test
    void wakeWithoutTargetUsesTheDefaultDeviceName() throws Exception {
        mvc.perform(get("/wake/mac/aa-bb-cc-dd-ee-ff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("WOL packet sent successfully to device (MAC: aa-bb-cc-dd-ee-ff)"));
    }

    @Test
    void wakePostAcceptsTheDocumentedBody() throws Exception {
        mvc.perform(post("/wake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:02\",\"target\":\"Predator RTX4060\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ---------------------------------------------------------------- Toggle: errors recorded from the live service

    @Test
    void toggleOfANonexistentGuestMatchesTheRecordedResponse() throws Exception {
        when(proxmox.guestStatus("pve", GuestType.LXC, 99999)).thenThrow(new ProxmoxException(ProxmoxException.Kind.HTTP_ERROR,
                "Failed to get status for LXC99999 on node pve. HTTP 500: " + NOT_FOUND_BODY));

        String recorded = "{\"success\":false,\"message\":\"Failed to toggle LXC99999 on node pve\",\"node\":null,\"type\":null,\"id\":null,\"action\":null,"
                + "\"previousStatus\":null,\"newStatus\":null,\"error\":\"Operation failed\","
                + "\"errorDetails\":\"Failed to get status for LXC99999 on node pve. HTTP 500: {\\\"message\\\":\\\"Configuration file 'nodes/pve/lxc/99999.conf' does not exist\\\\n\\\",\\\"data\\\":null}\"}";
        mvc.perform(get("/toggle/pve/lxc/99999"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(recorded));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE result='failed'", Integer.class)).isEqualTo(1);
    }

    @Test
    void toggleWithInvalidTypeMatchesTheRecordedResponse() throws Exception {
        mvc.perform(get("/toggle/pve/foo/1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"success\":false,\"message\":\"Invalid container type: foo. Must be 'lxc' or 'qemu'\",\"node\":null,\"type\":null,\"id\":null,\"action\":null,\"previousStatus\":null,\"newStatus\":null,\"error\":\"Invalid parameters\",\"errorDetails\":null}"));
    }

    @Test
    void toggleWithNonNumericIdMatchesTheRecordedShape() throws Exception {
        mvc.perform(get("/toggle/pve/lxc/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid Parameter"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Integer")))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void postToggleWithMissingFieldsMatchesTheRecordedValidationBody() throws Exception {
        mvc.perform(post("/toggle").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Validation Error\",\"message\":\"Invalid request parameters\",\"details\":{\"node\":\"Node name is required\",\"id\":\"Container/VM ID is required\",\"type\":\"Container type is required\"}}", true));
    }

    @Test
    void postToggleWithNegativeIdMatchesTheRecordedValidationBody() throws Exception {
        mvc.perform(post("/toggle").contentType(MediaType.APPLICATION_JSON).content("{\"node\":\"pve\",\"type\":\"LXC\",\"id\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Validation Error\",\"message\":\"Invalid request parameters\",\"details\":{\"id\":\"ID must be a positive number\"}}", true));
    }

    // ---------------------------------------------------------------- Toggle: behavior

    @Test
    void togglingARunningGuestStopsItAndReportsThePredictedState() throws Exception {
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenReturn(GuestStatus.RUNNING);
        mvc.perform(get("/toggle/pve/lxc/100"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":true,\"message\":\"LXC100 on node pve stoped successfully\",\"node\":\"pve\",\"type\":\"LXC\",\"id\":100,\"action\":\"stop\",\"previousStatus\":\"RUNNING\",\"newStatus\":\"STOPPED\",\"error\":null,\"errorDetails\":null}"));
        verify(proxmox).guestAction("pve", GuestType.LXC, 100, "stop");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action_id='toggle-stop' AND result='ok'", Integer.class)).isEqualTo(1);
    }

    @Test
    void togglingAStoppedGuestStartsIt() throws Exception {
        when(proxmox.guestStatus("pve", GuestType.QEMU, 200)).thenReturn(GuestStatus.STOPPED);
        mvc.perform(get("/toggle/pve/qemu/200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("QEMU200 on node pve started successfully"))
                .andExpect(jsonPath("$.newStatus").value("RUNNING"))
                .andExpect(jsonPath("$.type").value("QEMU"));
        verify(proxmox).guestAction("pve", GuestType.QEMU, 200, "start");
    }

    @Test
    void aGuestInATransitionalStateIsNotToggledAndAnswers500LikeWolServer() throws Exception {
        // wol-server's controller maps "Cannot toggle" to 409, but its service swallows the exception first,
        // so the real answer was 500. Recorded behaviour is kept.
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenReturn(GuestStatus.STARTING);
        mvc.perform(get("/toggle/pve/lxc/100"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Operation failed"))
                .andExpect(jsonPath("$.errorDetails").value("Cannot toggle LXC100 on node pve: current status is 'starting'. Can only toggle when 'running' or 'stopped'"));
        verify(proxmox, never()).guestAction(anyString(), any(), anyInt(), anyString());
    }

    @Test
    void postToggleAcceptsBothUpperAndLowerCaseType() throws Exception {
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenReturn(GuestStatus.STOPPED);
        for (String type : new String[] {"LXC", "lxc"}) {
            mvc.perform(post("/toggle").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"node\":\"pve\",\"type\":\"" + type + "\",\"id\":100}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.action").value("start"));
        }
    }

    @Test
    void anUnknownTypeInAPostBodyIsA400NotAGeneric500() throws Exception {
        mvc.perform(post("/toggle").contentType(MediaType.APPLICATION_JSON).content("{\"node\":\"pve\",\"type\":\"foo\",\"id\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid Parameter"));
    }

    @Test
    void aNodeNameThatCouldSteerTheRequestPathIsA400() throws Exception {
        when(proxmox.guestStatus(eq("..%2F"), any(), anyInt())).thenThrow(new IllegalArgumentException("Invalid node name: ..%2F"));
        mvc.perform(get("/toggle/..%2F/lxc/1"))
                .andExpect(status().is4xxClientError());
        verify(proxmox, never()).guestAction(anyString(), any(), anyInt(), anyString());
    }

    @Test
    void aSecondCommandForTheSameGuestWhileOneRunsIsRejectedWith409() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenAnswer(inv -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return GuestStatus.RUNNING;
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> mvc.perform(get("/toggle/pve/lxc/100")).andExpect(status().isOk()));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            mvc.perform(get("/toggle/pve/lxc/100"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Operation in progress"));
            // a different guest is not blocked
            when(proxmox.guestStatus("pve", GuestType.LXC, 101)).thenReturn(GuestStatus.STOPPED);
            mvc.perform(get("/toggle/pve/lxc/101")).andExpect(status().isOk());

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // and once finished the guest can be commanded again
        when(proxmox.guestStatus("pve", GuestType.LXC, 100)).thenReturn(GuestStatus.RUNNING);
        mvc.perform(get("/toggle/pve/lxc/100")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- Node power

    @Test
    void shutdownAndRebootWorkOnGetAndPost() throws Exception {
        mvc.perform(get("/node/pve/shutdown")).andExpect(status().isOk())
                .andExpect(content().string("{\"success\":true,\"message\":\"Shutdown command sent to node pve\",\"node\":\"pve\",\"error\":null,\"errorDetails\":null}"));
        mvc.perform(post("/node/pve/reboot")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Reboot command sent to node pve"));
        verify(proxmox).nodeCommand("pve", "shutdown");
        verify(proxmox).nodeCommand("pve", "reboot");
    }

    @Test
    void nodeCommandFailuresUseTheWolServerStatusRules() throws Exception {
        org.mockito.Mockito.doThrow(new ProxmoxException(ProxmoxException.Kind.UNAUTHORIZED, "token lacks permissions on /nodes/pve"))
                .when(proxmox).nodeCommand("pve", "shutdown");
        mvc.perform(get("/node/pve/shutdown")).andExpect(status().isForbidden())
                .andExpect(content().string("{\"success\":false,\"message\":\"Proxmox API Error\",\"node\":\"pve\",\"error\":\"token lacks permissions on /nodes/pve\",\"errorDetails\":null}"));

        org.mockito.Mockito.doThrow(new ProxmoxException(ProxmoxException.Kind.UNREACHABLE, "Error sending reboot command to node pve: timed out"))
                .when(proxmox).nodeCommand("pve", "reboot");
        mvc.perform(get("/node/pve/reboot")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Error sending reboot command to node pve: timed out"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE result='failed'", Integer.class)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- Cross-site browser requests

    @Test
    void crossSiteBrowserRequestsAreRefusedBeforeAnythingRuns() throws Exception {
        mvc.perform(get("/node/pve/shutdown").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
        mvc.perform(get("/toggle/pve/lxc/100").header("Sec-Fetch-Site", "Cross-Site"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/wake").header("Sec-Fetch-Site", "cross-site").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(proxmox, never()).nodeCommand(anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE result='denied'", Integer.class)).isEqualTo(3);
    }

    @Test
    void sameSiteSameOriginNoHeaderAndDirectNavigationAreAllowed() throws Exception {
        mvc.perform(get("/wake/mac/zz")).andExpect(status().isBadRequest());
        for (String v : new String[] {"same-site", "same-origin", "none"}) {
            mvc.perform(get("/wake/mac/zz").header("Sec-Fetch-Site", v)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void theGuardOnlyAppliesToLegacyPaths() throws Exception {
        mvc.perform(get("/actuator/health").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isOk());
    }

    @Test
    void unknownPathsAre404NotAGeneric500() throws Exception {
        mvc.perform(get("/nothing")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private static void drain() {
        try {
            WOL_RECEIVER.setSoTimeout(50);
            byte[] buf = new byte[200];
            while (true) {
                WOL_RECEIVER.receive(new DatagramPacket(buf, buf.length));
            }
        } catch (java.net.SocketTimeoutException done) {
            // queue empty
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                WOL_RECEIVER.setSoTimeout(1500);
            } catch (Exception ignored) {
            }
        }
    }

    private static List<byte[]> receive(int n) throws Exception {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] buf = new byte[200];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            WOL_RECEIVER.receive(p);
            out.add(java.util.Arrays.copyOf(buf, p.getLength()));
        }
        return out;
    }
}
