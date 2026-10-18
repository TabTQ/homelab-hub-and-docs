package com.homelabhub.internal.auth;

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

@SpringBootTest(properties = "hub.auth.mode=edit")
@AutoConfigureMockMvc
class AuthModeEditTest {
    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @MockitoBean ProxmoxClient proxmox;

    @Test
    void modeEditOnlyProtectsTheAdminArea() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/anything")).andExpect(status().isUnauthorized());
    }
}
