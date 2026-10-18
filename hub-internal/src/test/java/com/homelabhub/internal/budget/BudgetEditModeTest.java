package com.homelabhub.internal.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** In HUB_AUTH_MODE=edit the dashboard is open but /api/admin/** is not: the budget must stay behind the login (N3). */
@SpringBootTest(properties = {"hub.status.auto-start=false", "hub.auth.mode=edit"})
@AutoConfigureMockMvc
class BudgetEditModeTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @MockitoBean ProxmoxClient proxmox;

    @Test
    void budgetNeedsTheLoginInEditMode() throws Exception {
        for (String path : List.of("/api/admin/budget/people", "/api/admin/budget/recurring", "/api/admin/budget/statement?from=2026-09-01&to=2026-09-30",
                "/api/admin/budget/export.pdf?from=2026-09-01&to=2026-09-30")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
        // while a non-admin route stays open in this mode
        assertThat(mvc.perform(get("/api/auth/status")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
