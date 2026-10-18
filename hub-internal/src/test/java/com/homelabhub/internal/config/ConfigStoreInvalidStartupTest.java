package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The app must come up even when dashboard.yaml is already broken at boot (edited over SSH, then restarted). */
@SpringBootTest
class ConfigStoreInvalidStartupTest {

    private static final Path DIR = TestSupport.tempDir();
    private static final String BROKEN = "version: 1\ngroups:\n  - name: A\n    items: [\n";

    static {
        try {
            Files.createDirectories(DIR.resolve("config"));
            Files.writeString(DIR.resolve("config").resolve("dashboard.yaml"), BROKEN);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired ConfigStore store;
    @MockitoBean ProxmoxClient proxmox;

    @Test
    void bootsWithTheStarterConfigAndReportsTheProblemWithoutTouchingTheFile() throws Exception {
        assertThat(store.state().health()).isEqualTo(ConfigStore.Health.USING_DEFAULT);
        assertThat(store.state().config()).isNotNull();
        assertThat(store.state().config().groups()).isNotEmpty();
        assertThat(store.state().issues()).anySatisfy(i -> assertThat(i.level()).isEqualTo(Issue.Level.ERROR));
        assertThat(Files.readString(store.file())).as("the user's broken file is left for them to fix").isEqualTo(BROKEN);
        assertThat(store.state().text()).isEqualTo(BROKEN);
    }

    @Test
    void theEditorCanStillSaveAFixOverTheBrokenFile() throws Exception {
        var s = store.save("version: 1\ntitle: Fixed\ngroups: []\n", store.state().sha256(), "ui", null);
        assertThat(s.health()).isEqualTo(ConfigStore.Health.OK);
        assertThat(s.config().title()).isEqualTo("Fixed");
    }
}
