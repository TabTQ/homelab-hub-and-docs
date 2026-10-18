package com.homelabhub.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ConfigApiTest {

    private static final Path DIR = TestSupport.tempDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
    }

    @Autowired MockMvc mvc;
    @Autowired ConfigStore store;
    @Autowired ObjectMapper json;
    @MockitoBean ProxmoxClient proxmox;

    private static final String BASE = "/api/admin/config";

    private String body(String text) throws Exception {
        return json.writeValueAsString(Map.of("text", text));
    }

    private static String doc(String title) {
        return "version: 1\ntitle: " + title + "\ngroups: []\n";
    }

    private String currentSha() {
        return store.state().sha256();
    }

    @Test
    void everythingNeedsALogin() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/validate").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body("x"))).andExpect(status().isUnauthorized());
        mvc.perform(put(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(doc("x")))).andExpect(status().isUnauthorized());
    }

    @Test
    void writesNeedTheCsrfToken() throws Exception {
        mvc.perform(put(BASE).with(user("admin")).header("If-Match", "\"" + currentSha() + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(body(doc("no csrf")))).andExpect(status().isForbidden());
    }

    @Test
    void getReturnsTheTextTheShaAndAMatchingEtag() throws Exception {
        mvc.perform(get(BASE).with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + currentSha() + "\""))
                .andExpect(jsonPath("$.sha256").value(currentSha()))
                .andExpect(jsonPath("$.health").value("ok"))
                .andExpect(jsonPath("$.text").value(containsString("version: 1")));
    }

    @Test
    void validateReportsIssuesWithLinesWithoutSaving() throws Exception {
        String before = Files.readString(store.file());
        mvc.perform(post(BASE + "/validate").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("version: 1\ngroups:\n  - name: A\n    items:\n      - {name: X, href: \"javascript:alert(1)\"}\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.issues[0].line").value(5))
                .andExpect(jsonPath("$.issues[0].path").value("groups[0].items[0].href"));
        assertThat(Files.readString(store.file())).isEqualTo(before);
    }

    @Test
    void aValidSaveWithIfMatchSucceedsAndReturnsTheNewEtag() throws Exception {
        MvcResult r = mvc.perform(put(BASE).with(user("admin")).with(csrf()).header("If-Match", "\"" + currentSha() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(body(doc("Via API"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(doc("Via API")))
                .andReturn();
        assertThat(r.getResponse().getHeader("ETag")).isEqualTo("\"" + currentSha() + "\"");
        assertThat(Files.readString(store.file())).isEqualTo(doc("Via API"));
    }

    @Test
    void aSaveWithoutIfMatchIsRefusedWith428() throws Exception {
        mvc.perform(put(BASE).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(doc("x"))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.error").value("if_match_required"));
    }

    @Test
    void aStaleIfMatchGets409WithTheCurrentFileSoTheEditorCanShowADiff() throws Exception {
        String stale = currentSha();
        store.save(doc("Someone else"), stale, "ui", null);
        mvc.perform(put(BASE).with(user("admin")).with(csrf()).header("If-Match", "\"" + stale + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(body(doc("Mine"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.text").value(doc("Someone else")))
                .andExpect(jsonPath("$.sha256").value(currentSha()));
    }

    @Test
    void anInvalidSaveGets422WithTheIssues() throws Exception {
        String before = Files.readString(store.file());
        mvc.perform(put(BASE).with(user("admin")).with(csrf()).header("If-Match", currentSha())
                        .contentType(MediaType.APPLICATION_JSON).content(body("version: 1\ngroups: [\n")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid"))
                .andExpect(jsonPath("$.issues[0].message").value(containsString("YAML syntax error")));
        assertThat(Files.readString(store.file())).isEqualTo(before);
    }

    @Test
    void versionsCanBeListedReadAndRestored() throws Exception {
        store.save(doc("Restore me"), currentSha(), "ui", null);
        long id = store.versions().get(0).id();
        store.save(doc("Newer"), currentSha(), "ui", null);

        mvc.perform(get(BASE + "/versions").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].sha256").isString());
        mvc.perform(get(BASE + "/versions/" + id).with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(doc("Restore me")));
        mvc.perform(post(BASE + "/versions/" + id + "/restore").with(user("admin")).with(csrf()).header("If-Match", currentSha()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(doc("Restore me")));
        assertThat(Files.readString(store.file())).isEqualTo(doc("Restore me"));
    }

    @Test
    void unknownVersionsAnd428OnRestore() throws Exception {
        mvc.perform(get(BASE + "/versions/99999999").with(user("admin"))).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/versions/99999999/restore").with(user("admin")).with(csrf()).header("If-Match", currentSha()))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/versions/1/restore").with(user("admin")).with(csrf())).andExpect(status().isPreconditionRequired());
    }

    @Test
    void theHugeBodyIsRefusedCleanlyNotWithAnException() throws Exception {
        mvc.perform(put(BASE).with(user("admin")).with(csrf()).header("If-Match", currentSha())
                        .contentType(MediaType.APPLICATION_JSON).content(body("version: 1\ngroups: []\n# " + "a".repeat(300_000))))
                .andExpect(status().isUnprocessableEntity());
    }
}
