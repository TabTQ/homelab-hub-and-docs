package com.homelabhub.internal.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
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
class DashboardApiTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @Autowired ConfigStore store;
    @Autowired StatusService statusService;
    @MockitoBean Checker checker;
    @MockitoBean ProxmoxClient proxmox;

    static final String YAML = """
            version: 1
            title: Test Lab
            widgets:
              - resources: {cpu: true, disk: /}
            pinned: [wake-pve]
            groups:
              - name: Proxmox
                items:
                  - name: PVE
                    href: https://192.168.10.102:8006
                    icon: proxmox.svg
                    status: {icmp: 192.168.10.102}
                    widget: {type: proxmox, url: "https://192.168.10.102:8006", node: pve, username: "hub@pve!homepage", password: "${secret:TOP_SECRET_NAME}"}
                  - name: Nested
                    items:
                      - {name: Grafana, href: "http://192.168.10.101:3000/"}
                      - {name: Wake, action: wake-pve}
            actions:
              - {id: wake-pve, label: Wake Proxmox, type: wol, mac: "aa:bb:cc:dd:ee:01", status: {icmp: 192.168.10.102}}
              - {id: pve-off, label: Shutdown, type: proxmox-node, node: pve, command: shutdown}
              - {id: lxc-100, label: Apps, type: proxmox-guest, node: pve, kind: lxc, vmid: 100}
            """;

    @BeforeEach
    void load() {
        store.save(YAML, store.state().sha256(), "ui", null);
    }

    @Test
    void requiresALogin() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void describesTheDashboardTheWayTheUiNeedsIt() throws Exception {
        mvc.perform(get("/api/dashboard").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Test Lab"))
                .andExpect(jsonPath("$.pinned[0]").value("wake-pve"))
                .andExpect(jsonPath("$.widgets[0].type").value("resources"))
                .andExpect(jsonPath("$.groups[0].name").value("Proxmox"))
                .andExpect(jsonPath("$.groups[0].entries[0].kind").value("item"))
                .andExpect(jsonPath("$.groups[0].entries[0].statusId").value("item:g0.0"))
                .andExpect(jsonPath("$.groups[0].entries[0].widget").value("proxmox"))
                .andExpect(jsonPath("$.groups[0].entries[1].kind").value("group"))
                .andExpect(jsonPath("$.groups[0].entries[1].entries[1].action").value("wake-pve"))
                .andExpect(jsonPath("$.groups[0].entries[1].entries[1].statusId").value("action:wake-pve"))
                .andExpect(jsonPath("$.groups[0].entries[1].entries[0].statusId").doesNotExist())
                .andExpect(jsonPath("$.actions[0].type").value("wol"))
                .andExpect(jsonPath("$.actions[0].mac").value("AA:BB:CC:DD:EE:01"))
                .andExpect(jsonPath("$.actions[0].confirm").value("click"))
                .andExpect(jsonPath("$.actions[1].confirm").value("type-name"))
                .andExpect(jsonPath("$.actions[2].confirm").value("auto"))
                .andExpect(jsonPath("$.actions[2].vmid").value(100))
                .andExpect(jsonPath("$.actions[2].stopMode").value("stop"))
                .andExpect(jsonPath("$.config.health").value("ok"))
                .andExpect(jsonPath("$.docsUrl").doesNotExist());
    }

    @Test
    void portfolioUrlIsExposedWithoutAnyOutboundCall() throws Exception {
        store.save(YAML + "docs: {url: https://docs.example-tailnet.ts.net, portfolioUrl: https://portfolio.example.com}\n", store.state().sha256(), "ui", null);
        mvc.perform(get("/api/dashboard").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docsUrl").value("https://docs.example-tailnet.ts.net"))
                .andExpect(jsonPath("$.portfolioUrl").value("https://portfolio.example.com"));
        org.mockito.Mockito.verifyNoInteractions(checker);
    }

    @Test
    void docsLinkIsExposedWhenConfigured() throws Exception {
        store.save(YAML + "docs: {url: https://docs.example-tailnet.ts.net}\n", store.state().sha256(), "ui", null);
        mvc.perform(get("/api/dashboard").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docsUrl").value("https://docs.example-tailnet.ts.net"));
    }

    @Test
    void neverLeaksWidgetUrlsUsernamesOrSecretReferences() throws Exception {
        String body = mvc.perform(get("/api/dashboard").with(user("admin"))).andReturn().getResponse().getContentAsString();
        // The item's own href (a link the user clicks) is public content. The WIDGET's url, username and password are not.
        assertThat(body).doesNotContain("hub@pve").doesNotContain("secret:").doesNotContain("password").doesNotContain("username")
                .doesNotContain("\"url\"").doesNotContain("TOP_SECRET_NAME}");
        assertThat(body).as("the browser only learns the widget type").contains("\"widget\":\"proxmox\"");
    }

    @Test
    void reportsMissingSecretsByNameOnly() throws Exception {
        mvc.perform(get("/api/dashboard").with(user("admin")))
                .andExpect(jsonPath("$.config.missingSecrets", hasItem("TOP_SECRET_NAME")))
                .andExpect(jsonPath("$.config.warnings").value(1));
    }

    @Test
    void statusSnapshotListsEveryTrackedLightAsUnknownBeforeTheFirstRound() throws Exception {
        mvc.perform(get("/api/status").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items['item:g0.0'].state").value("unknown"))
                .andExpect(jsonPath("$.items['action:wake-pve'].state").value("unknown"))
                .andExpect(jsonPath("$.generatedAt").isString());
    }

    @Test
    void manualRefreshNeedsCsrfAndIsRateLimited() throws Exception {
        var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/status/refresh").with(user("admin"));
        mvc.perform(post).andExpect(status().isForbidden());
        var withCsrf = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/status/refresh").with(user("admin"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
        mvc.perform(withCsrf);
        mvc.perform(withCsrf).andExpect(status().isTooManyRequests());
    }
}
