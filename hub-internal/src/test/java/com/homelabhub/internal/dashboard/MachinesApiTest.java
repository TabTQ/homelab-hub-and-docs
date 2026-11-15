package com.homelabhub.internal.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.status.Checker;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "hub.status.auto-start=false")
@AutoConfigureMockMvc
class MachinesApiTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @Autowired ConfigStore store;
    @MockitoBean Checker checker;
    @MockitoBean ProxmoxClient proxmox;

    private static final String GROUPS = """
            version: 1
            groups:
              - name: "Apps"
                items:
                  - name: "Wiki"
                    href: "http://192.0.2.30:8080/"
                  - name: "Loose"
                    href: "http://192.0.2.99/"
                  - name: "Wake"
                    action: wake-pve
            actions:
              - id: wake-pve
                label: "Wake"
                type: wol
                mac: "02:00:00:00:00:01"
              - id: toggle-apps
                label: "Toggle Apps"
                type: proxmox-guest
                node: pve
                kind: lxc
                vmid: 100
            """;

    private static final String MACHINES = """
            machines:
              - id: "pve"
                name: "Proxmox host"
                addresses:
                  - "192.0.2.20"
                power:
                  - "wake-pve"
              - id: "apps"
                name: "Apps"
                in: "pve"
                addresses:
                  - "192.0.2.30"
                toggle: "toggle-apps"
            """;

    @Test
    void theDashboardCarriesMachinesAndEachItemsMachine() throws Exception {
        store.save(GROUPS + MACHINES, store.state().sha256(), "ui", null);
        String body = mvc.perform(get("/api/dashboard").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines.length()").value(2))
                .andExpect(jsonPath("$.machines[0].id").value("pve"))
                .andExpect(jsonPath("$.machines[0].name").value("Proxmox host"))
                .andExpect(jsonPath("$.machines[0].parent").isEmpty())
                .andExpect(jsonPath("$.machines[0].power[0]").value("wake-pve"))
                .andExpect(jsonPath("$.machines[0].toggle").isEmpty())
                .andExpect(jsonPath("$.machines[1].id").value("apps"))
                .andExpect(jsonPath("$.machines[1].parent").value("pve"))
                .andExpect(jsonPath("$.machines[1].power.length()").value(0))
                .andExpect(jsonPath("$.machines[1].toggle").value("toggle-apps"))
                .andExpect(jsonPath("$.groups[0].entries[0].machine").value("apps"))
                .andExpect(jsonPath("$.groups[0].entries[1].machine").isEmpty())
                .andExpect(jsonPath("$.groups[0].entries[2].machine").value("pve"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).as("addresses and globs stay on the server").doesNotContain("192.0.2.20").doesNotContain("addresses")
                .doesNotContain("\"match\"");
        assertThat(body).contains("\"machine\":null");
    }

    @Test
    void aConfigWithoutMachinesGivesAnEmptyListAndNoPlacement() throws Exception {
        store.save(GROUPS, store.state().sha256(), "ui", null);
        mvc.perform(get("/api/dashboard").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines.length()").value(0))
                .andExpect(jsonPath("$.groups[0].entries[0].machine").isEmpty())
                .andExpect(jsonPath("$.groups[0].entries[0].href").value("http://192.0.2.30:8080/"))
                .andExpect(jsonPath("$.groups[0].entries[2].action").value("wake-pve"))
                .andExpect(jsonPath("$.actions[0].id").value("wake-pve"))
                .andExpect(jsonPath("$.config.health").value("ok"));
    }
}
