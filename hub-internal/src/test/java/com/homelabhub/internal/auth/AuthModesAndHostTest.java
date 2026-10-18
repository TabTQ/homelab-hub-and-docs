package com.homelabhub.internal.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** No password configured (the state before first setup) plus the Host allowlist wiring. */
@SpringBootTest(properties = {"hub.security.allowed-hosts=hub.lan,*.ts.net"})
@AutoConfigureMockMvc
class AuthModesAndHostTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @MockitoBean ProxmoxClient proxmox;
    @Autowired org.springframework.security.core.userdetails.UserDetailsService users;

    @Test
    void thereIsNoDefaultUserWithAGeneratedPasswordLeftBehind() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> users.loadUserByUsername("user"))
                .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
    }

    @Test
    void withNoPasswordConfiguredTheDashboardIsLockedAndLoginSaysWhy() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType("application/json").content("{\"password\":\"anything\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("password_not_configured"));
        mvc.perform(get("/api/auth/status")).andExpect(jsonPath("$.passwordConfigured").value(false));
    }

    @Test
    void aForeignHostHeaderIsRefusedOnEveryRouteIncludingTheLegacyOnes() throws Exception {
        for (String path : new String[] {"/api/auth/status", "/wake/mac/zz", "/actuator/health", "/index.html"}) {
            mvc.perform(get(path).header("Host", "evil.example")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void namesWeAreConfiguredForAndIpAddressesAreServed() throws Exception {
        for (String host : new String[] {"localhost", "192.168.10.101:3010", "100.64.0.5", "hub.lan:3010", "pi.tail1234.ts.net"}) {
            mvc.perform(get("/api/auth/status").header("Host", host)).andExpect(status().isOk());
        }
    }

    @Test
    void aMissingHostHeaderIsAllowedBecauseBrowsersAlwaysSendOne() throws Exception {
        // HTTP/1.0 scripts and embedded devices may omit it; DNS rebinding cannot.
        mvc.perform(get("/api/auth/status")).andExpect(status().isOk());
    }

    @Test
    void aLookAlikeHostIsRefused() throws Exception {
        for (String host : new String[] {"192.168.10.101.evil.com", "hub.lan.evil.com", "evilts.net", "ts.net"}) {
            mvc.perform(get("/api/auth/status").header("Host", host)).andExpect(status().isBadRequest());
        }
    }
}
