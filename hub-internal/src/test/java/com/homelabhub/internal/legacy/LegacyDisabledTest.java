package com.homelabhub.internal.legacy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** hub.legacy.enabled=false must remove the routes entirely (plan 9.5 item 4). */
@SpringBootTest(properties = "hub.legacy.enabled=false")
@AutoConfigureMockMvc
class LegacyDisabledTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @MockitoBean ProxmoxClient proxmox;

    @Test
    void allLegacyRoutesAreGoneWhenSwitchedOff() throws Exception {
        mvc.perform(get("/wake/mac/AA:BB:CC:DD:EE:FF")).andExpect(status().isNotFound());
        mvc.perform(get("/toggle/pve/lxc/100")).andExpect(status().isNotFound());
        mvc.perform(get("/node/pve/shutdown")).andExpect(status().isNotFound());
    }
}
