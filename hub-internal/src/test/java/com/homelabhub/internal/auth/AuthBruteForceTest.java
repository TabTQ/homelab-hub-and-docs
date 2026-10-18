package com.homelabhub.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Own Spring context: the limiter is per client address, so this must not share state with other tests. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthBruteForceTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode("right"));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProxmoxClient proxmox;

    @Test
    void afterFiveWrongPasswordsEvenTheRightOneIsRefusedUntilTheLockoutEnds() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"right\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("too_many_attempts"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action_id='login' AND result='failed'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action_id='login' AND result='denied'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theLegacyRoutesAreNotAffectedByALoginLockout() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"));
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/wake/mac/zz")).andExpect(status().isBadRequest());
    }
}
