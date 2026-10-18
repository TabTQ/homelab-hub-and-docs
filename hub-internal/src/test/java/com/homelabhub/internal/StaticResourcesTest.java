package com.homelabhub.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.widgets.HostMetrics;
import java.io.IOException;
import java.nio.file.Files;
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
class StaticResourcesTest {

    private static final Path DIR = TestSupport.tempDir();

    static {
        try {
            Files.createDirectories(DIR.resolve("images"));
            Files.write(DIR.resolve("images").resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            Files.writeString(DIR.resolve("secrets.env"), "SECRET=hunter2\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @MockitoBean ProxmoxClient proxmox;
    @MockitoBean HostMetrics metrics;

    @Test
    void userImagesAreServedFromTheDataDirectory() throws Exception {
        mvc.perform(get("/images/logo.png")).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/images/missing.png")).andExpect(status().isNotFound());
    }

    @Test
    void pathTraversalCannotReachOtherFilesInTheDataDirectory() throws Exception {
        for (String evil : new String[] {"/images/../secrets.env", "/images/%2e%2e/secrets.env", "/images/..%2fsecrets.env", "/images/%2e%2e%2fsecrets.env",
                "/images/....//secrets.env", "/images/..\\secrets.env"}) {
            var r = mvc.perform(get(evil)).andReturn().getResponse();
            assertThat(r.getStatus()).as(evil).isIn(400, 404);
            assertThat(r.getContentAsString()).as(evil).doesNotContain("hunter2");
        }
    }

    @Test
    void everyResponseCarriesAContentSecurityPolicyThatAllowsOnlyOwnScripts() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }
}
