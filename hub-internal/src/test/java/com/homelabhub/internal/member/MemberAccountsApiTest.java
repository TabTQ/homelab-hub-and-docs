package com.homelabhub.internal.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.widgets.HostMetrics;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Member logins, the member allowlist and the member port, on a real SQLite database through the real security chain. */
@SpringBootTest(properties = {"hub.status.auto-start=false", "hub.auth.mode=all"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MemberAccountsApiTest {

    private static final Path DIR = TestSupport.tempDir();
    private static final int MEMBER_PORT = freePort();
    private static final String B = "/api/admin/budget";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.backup.dir", () -> DIR.resolve("backups").toString());
        r.add("hub.member.port", () -> MEMBER_PORT);
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean ProxmoxClient proxmox;
    @MockitoBean HostMetrics metrics;

    record Res(int status, JsonNode body, MockHttpServletResponse raw) {}

    record Member(long id, String name, String password, MockHttpSession session, String ip) {}

    private static RequestPostProcessor from(String ip) {
        return req -> {
            req.setRemoteAddr(ip);
            return req;
        };
    }

    private static RequestPostProcessor onMemberPort() {
        return req -> {
            req.setLocalPort(MEMBER_PORT);
            return req;
        };
    }

    private static String freshIp() {
        return "192.0.2." + (10 + SEQ.incrementAndGet());
    }

    private static String freshName(String base) {
        return base + SEQ.incrementAndGet();
    }

    private Res run(MockHttpServletRequestBuilder b, Object body) throws Exception {
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
        String text = r.getContentType() != null && r.getContentType().contains("json") ? r.getContentAsString() : "";
        return new Res(r.getStatus(), text.isEmpty() ? null : json.readTree(text), r);
    }

    private Res admin(MockHttpServletRequestBuilder b, Object body) throws Exception {
        return run(b.with(user("admin")).with(csrf()), body);
    }

    private Res asMember(MockHttpServletRequestBuilder b, Member m, Object body) throws Exception {
        return run(b.session(m.session()).with(csrf()).with(from(m.ip())), body);
    }

    private Res login(String name, String password, String ip) throws Exception {
        return run(post("/api/member/login").with(from(ip)), Map.of("name", name, "password", password));
    }

    /** A person with a login, signed in, with a password of their own choosing. */
    private Member member(boolean canAdd) throws Exception {
        String name = freshName("Asha");
        Res created = admin(post(B + "/people"), Map.of("name", name, "createLogin", true));
        assertThat(created.status()).isEqualTo(201);
        long id = created.body().get("id").asLong();
        String ip = freshIp();
        Res in = login(name, created.body().get("password").asText(), ip);
        assertThat(in.status()).isEqualTo(200);
        MockHttpSession session = (MockHttpSession) in.raw().getHeaderValue("X-Test") == null ? sessionOf(name, created.body().get("password").asText(), ip) : null;
        Member m = new Member(id, name, "Chosen-pass-" + id, session, ip);
        assertThat(asMember(post("/api/member/password"), m, Map.of("current", created.body().get("password").asText(), "next", m.password())).status())
                .isEqualTo(204);
        if (canAdd) {
            assertThat(admin(put(B + "/people/" + id + "/account"), Map.of("canAdd", true)).status()).isEqualTo(200);
        }
        return m;
    }

    private MockHttpSession sessionOf(String name, String password, String ip) throws Exception {
        var result = mvc.perform(post("/api/member/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "password", password)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static Map<String, Object> expense(String name, long paise, long paidBy, String month, int day, List<Long> split) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("amountPaise", paise);
        m.put("paidBy", paidBy);
        m.put("month", month);
        m.put("day", day);
        m.put("splitIds", split);
        return m;
    }

    // ---------------------------------------------------------------- sign-in

    @Test
    void generatedPasswordLooksRightAndFirstLoginForcesAChange(CapturedOutput output) throws Exception {
        String name = freshName("Ravi");
        Res created = admin(post(B + "/people"), Map.of("name", name, "createLogin", true));
        String pw = created.body().get("password").asText();
        assertThat(pw).matches("[A-HJ-NP-Za-km-z2-9]{4}-[A-HJ-NP-Za-km-z2-9]{4}-[A-HJ-NP-Za-km-z2-9]{4}");
        assertThat(created.body().get("account").get("mustChange").asBoolean()).isTrue();
        assertThat(created.body().get("account").get("canAdd").asBoolean()).isFalse();
        String ip = freshIp();

        assertThat(login(name, "wrong-password", ip).status()).isEqualTo(401);
        Res unknown = login("Nobody", pw, ip);
        Res wrong = login(name, "wrong-password", ip);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.body().toString()).isEqualTo(wrong.body().toString()).contains("invalid_credentials");

        MockHttpSession session = sessionOf(name.toLowerCase(), pw, ip); // the name is not case sensitive
        Member m = new Member(created.body().get("id").asLong(), name, pw, session, ip);
        assertThat(asMember(get(B + "/people"), m, null).status()).isEqualTo(403);
        assertThat(asMember(get(B + "/people"), m, null).body().get("error").asText()).isEqualTo("password_change_required");
        JsonNode ctx = asMember(get("/api/member/context"), m, null).body();
        assertThat(ctx.get("mustChange").asBoolean()).isTrue();
        assertThat(ctx.get("role").asText()).isEqualTo("member");

        assertThat(asMember(post("/api/member/password"), m, Map.of("current", pw, "next", "short")).status()).isEqualTo(422);
        assertThat(asMember(post("/api/member/password"), m, Map.of("current", pw, "next", pw)).status()).isEqualTo(422);
        assertThat(asMember(post("/api/member/password"), m, Map.of("current", "not-it-at-all", "next", "Another-pass-1")).status()).isEqualTo(422);
        assertThat(asMember(post("/api/member/password"), m, Map.of("current", pw, "next", "Another-pass-1")).status()).isEqualTo(204);

        assertThat(asMember(get(B + "/people"), m, null).status()).isEqualTo(200);
        assertThat(asMember(get(B + "/people"), m, null).body().get(0).get("account").isNull()).isTrue(); // members never see logins
        assertThat(login(name, pw, ip).status()).isEqualTo(401);
        assertThat(login(name, "Another-pass-1", ip).status()).isEqualTo(200);

        // No password reaches the log or the audit table.
        assertThat(output.getAll()).doesNotContain(pw).doesNotContain("Another-pass-1").doesNotContain("wrong-password");
        List<String> audit = jdbc.queryForList("SELECT action_id || '|' || target || '|' || coalesce(detail, '') || '|' || coalesce(user_agent, '') FROM audit_log", String.class);
        assertThat(audit).isNotEmpty().noneMatch(r -> r.contains(pw) || r.contains("Another-pass-1") || r.contains("wrong-password"));
        assertThat(audit).anyMatch(r -> r.startsWith("member-login|person:" + m.id() + "|"));
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM budget_accounts", String.class);
        assertThat(hashes).isNotEmpty().allMatch(h -> h.startsWith("$2"));
    }

    @Test
    void tooManyWrongPasswordsAreRefusedWith429() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 5; i++) {
            assertThat(login("Nobody", "x-wrong-" + i, ip).status()).isEqualTo(401);
        }
        Res r = login("Nobody", "x-wrong-again", ip);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.raw().getHeader("Retry-After")).isNotNull();
    }

    @Test
    void disabledAndArchivedLoginsCannotSignInAndTheirSessionsEnd() throws Exception {
        Member a = member(false);
        assertThat(asMember(get(B + "/people"), a, null).status()).isEqualTo(200);
        assertThat(admin(put(B + "/people/" + a.id() + "/account"), Map.of("disabled", true)).status()).isEqualTo(200);
        assertThat(login(a.name(), a.password(), a.ip()).status()).isEqualTo(401);
        assertThat(asMember(get(B + "/people"), a, null).status()).isEqualTo(401); // the open session ends too
        assertThat(admin(put(B + "/people/" + a.id() + "/account"), Map.of("disabled", false)).status()).isEqualTo(200);
        assertThat(login(a.name(), a.password(), a.ip()).status()).isEqualTo(200);

        Member b = member(false);
        Res people = admin(get(B + "/people"), null);
        int version = 0;
        for (JsonNode p : people.body()) {
            if (p.get("id").asLong() == b.id()) {
                version = p.get("version").asInt();
            }
        }
        Res archived = admin(put(B + "/people/" + b.id()), Map.of("name", b.name(), "archived", true, "version", version));
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.body().get("account").get("disabled").asBoolean()).isTrue();
        assertThat(login(b.name(), b.password(), b.ip()).status()).isEqualTo(401);
    }

    @Test
    void forgotPasswordThenAdminResetGivesANewPasswordAndEndsTheOldSession() throws Exception {
        Member m = member(false);
        String ip = freshIp();
        Res known = run(post("/api/member/forgot").with(from(ip)), Map.of("name", m.name()));
        Res unknown = run(post("/api/member/forgot").with(from(ip)), Map.of("name", "Nobody At All"));
        assertThat(known.status()).isEqualTo(200);
        assertThat(known.body().toString()).isEqualTo(unknown.body().toString()).isEqualTo("{\"ok\":true}");

        JsonNode account = personOf(m.id()).get("account");
        assertThat(account.get("resetRequestedAt").isNull()).isFalse();

        Res reset = admin(post(B + "/people/" + m.id() + "/account/reset"), null);
        assertThat(reset.status()).isEqualTo(200);
        String fresh = reset.body().get("password").asText();
        JsonNode after = personOf(m.id()).get("account");
        assertThat(after.get("resetRequestedAt").isNull()).isTrue();
        assertThat(after.get("mustChange").asBoolean()).isTrue();

        assertThat(asMember(get(B + "/people"), m, null).status()).isEqualTo(401); // the session from before the reset ended
        assertThat(login(m.name(), m.password(), m.ip()).status()).isEqualTo(401);
        Res in = login(m.name(), fresh, m.ip());
        assertThat(in.status()).isEqualTo(200);
        assertThat(in.body().get("mustChange").asBoolean()).isTrue();
    }

    @Test
    void adminAccountEndpointsAndTheUnchangedAdminFlow() throws Exception {
        String name = freshName("Plain");
        Res plain = admin(post(B + "/people"), Map.of("name", name));
        assertThat(plain.status()).isEqualTo(201);
        assertThat(plain.body().has("password")).isFalse();
        assertThat(plain.body().get("account").isNull()).isTrue();
        long id = plain.body().get("id").asLong();

        assertThat(admin(post(B + "/people/" + id + "/account/reset"), null).status()).isEqualTo(404); // no login yet
        Res created = admin(post(B + "/people/" + id + "/account"), null);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().get("password").asText()).hasSize(14);
        assertThat(admin(post(B + "/people/" + id + "/account"), null).status()).isEqualTo(409);
        assertThat(admin(post(B + "/people/99999/account"), null).status()).isEqualTo(404);
        assertThat(admin(put(B + "/people/" + id + "/account"), Map.of("canAdd", true)).body().get("account").get("canAdd").asBoolean()).isTrue();
        assertThat(personOf(id).get("account").get("lastLoginAt").isNull()).isTrue();
    }

    private JsonNode personOf(long id) throws Exception {
        for (JsonNode p : admin(get(B + "/people"), null).body()) {
            if (p.get("id").asLong() == id) {
                return p;
            }
        }
        throw new AssertionError("no person " + id);
    }

    // ---------------------------------------------------------------- entries

    @Test
    void canAddOffAndOnAndOnlyOwnEntriesCanBeChanged() throws Exception {
        Member viewer = member(false);
        Member writer = member(true);
        Map<String, Object> body = expense("Milk", 12000, writer.id(), "2026-09", 5, List.of(writer.id(), viewer.id()));

        assertThat(asMember(post(B + "/expenses"), viewer, body).status()).isEqualTo(403);
        Res added = asMember(post(B + "/expenses"), writer, body);
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.body().get("createdBy").asLong()).isEqualTo(writer.id());
        long mine = added.body().get("id").asLong();

        Res byAdmin = admin(post(B + "/expenses"), expense("Rent", 500000, viewer.id(), "2026-09", 6, List.of(writer.id(), viewer.id())));
        assertThat(byAdmin.body().get("createdBy").isNull()).isTrue();
        long theirs = byAdmin.body().get("id").asLong();

        Map<String, Object> edit = new LinkedHashMap<>(expense("Milk and eggs", 15000, writer.id(), "2026-09", 5, List.of(writer.id(), viewer.id())));
        edit.put("version", added.body().get("version").asInt());
        assertThat(asMember(put(B + "/expenses/" + mine), writer, edit).status()).isEqualTo(200);
        assertThat(asMember(put(B + "/expenses/" + mine), viewer, edit).status()).isEqualTo(403); // view only
        assertThat(asMember(put(B + "/expenses/" + theirs), writer, edit).status()).isEqualTo(403);
        assertThat(asMember(delete(B + "/expenses/" + theirs), writer, null).status()).isEqualTo(403);
        assertThat(asMember(delete(B + "/expenses/" + mine), writer, null).status()).isEqualTo(200);
        assertThat(admin(delete(B + "/expenses/" + theirs), null).status()).isEqualTo(200);
    }

    @Test
    void theMonthViewIsReadOnlyForMembersAndCarriesCreatedBy() throws Exception {
        Member w = member(true);
        Res added = asMember(post(B + "/expenses"), w, expense("Tea", 4000, w.id(), "2026-07", 3, List.of(w.id())));
        assertThat(added.status()).isEqualTo(201);

        Res view = asMember(get(B + "/months/2026-07"), w, null);
        assertThat(view.status()).isEqualTo(200);
        JsonNode mine = null;
        for (JsonNode e : view.body().get("expenses")) {
            if (e.get("id").asLong() == added.body().get("id").asLong()) {
                mine = e;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("createdBy").asLong()).isEqualTo(w.id());
        assertThat(view.body().has("statement")).isTrue();
        assertThat(view.body().has("history")).isTrue();

        // A recurring item that has not been opened yet: the read creates nothing, the admin's "open" does.
        Res rec = admin(post(B + "/recurring"), Map.of("name", "Internet", "amountPaise", 80000, "paidBy", w.id(), "splitIds", List.of(w.id()), "startMonth", "2026-04"));
        assertThat(rec.status()).isEqualTo(201);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM budget_expenses", Long.class);
        assertThat(asMember(get(B + "/months/2026-04"), w, null).body().get("expenses")).isEmpty();
        assertThat(admin(get(B + "/months/2026-04"), null).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_expenses", Long.class)).isEqualTo(before);
        assertThat(asMember(post(B + "/months/2026-04/open"), w, null).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_expenses", Long.class)).isEqualTo(before);
        assertThat(admin(post(B + "/months/2026-04/open"), null).body().get("expenses")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_expenses", Long.class)).isGreaterThan(before);
        assertThat(admin(get(B + "/months/2026-4"), null).status()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- security

    @Test
    void aMemberSessionCannotReachAnyAdminRoute() throws Exception {
        Member m = member(true);
        String[][] routes = {
                {"GET", "/api/dashboard"}, {"GET", "/api/me"}, {"GET", "/api/auth/status"}, {"POST", "/api/auth/login"},
                {"GET", "/api/status"}, {"GET", "/api/status/stream"}, {"POST", "/api/status/refresh"},
                {"POST", "/api/actions/apps/prepare"}, {"POST", "/api/actions/apps/confirm"}, {"GET", "/api/actions/tasks/abc"},
                {"GET", "/api/admin/config"}, {"PUT", "/api/admin/config"}, {"POST", "/api/admin/config/validate"},
                {"GET", "/api/admin/config/versions"}, {"POST", "/api/admin/config/versions/1/restore"},
                {"GET", "/api/admin/docker/config"}, {"GET", "/api/admin/docker/hosts/h/containers"}, {"POST", "/api/admin/docker/confirm"},
                {"GET", "/api/admin/docker/jobs"}, {"GET", "/api/admin/docker/audit"},
                {"GET", "/api/admin/terminal/config"}, {"POST", "/api/admin/terminal/sessions"}, {"GET", "/api/admin/terminal/audit"},
                {"GET", "/api/admin/terminal/ws"},
                {"GET", "/api/widgets/item/g0.0"}, {"GET", "/api/widgets/info/search"},
                {"GET", "/api/admin/backups"}, {"POST", "/api/admin/backups"},
                {"GET", "/wake/mac/00:00:5E:00:53:01"}, {"POST", "/wake"}, {"GET", "/toggle/pve/qemu/100"}, {"POST", "/toggle"},
                {"GET", "/node/pve/shutdown"}, {"POST", "/node/pve/reboot"},
                {"GET", "/actuator/info"}, {"GET", "/images/logo.png"},
                {"POST", B + "/people"}, {"PUT", B + "/people/1"}, {"POST", B + "/people/1/account"}, {"POST", B + "/people/1/account/reset"},
                {"PUT", B + "/people/1/account"}, {"POST", B + "/recurring"}, {"PUT", B + "/recurring/1"}, {"DELETE", B + "/recurring/1"},
                {"POST", B + "/settlements"}, {"DELETE", B + "/settlements/1"}, {"POST", B + "/months/2026-09/open"},
                {"GET", B + "/people/"},
        };
        for (String[] r : routes) {
            for (boolean token : new boolean[] {true, false}) {
                MockHttpServletRequestBuilder b = switch (r[0]) {
                    case "POST" -> post(r[1]);
                    case "PUT" -> put(r[1]);
                    case "DELETE" -> delete(r[1]);
                    default -> get(r[1]);
                };
                b = b.session(m.session()).with(from(m.ip()));
                if (token) {
                    b = b.with(csrf());
                }
                if (!r[0].equals("GET") && !r[0].equals("DELETE")) {
                    b = b.contentType(MediaType.APPLICATION_JSON).content("{}");
                }
                int status = mvc.perform(b).andReturn().getResponse().getStatus();
                assertThat(status).as("%s %s (csrf=%s)", r[0], r[1], token).isIn(403, 404);
            }
        }
        // The same session still reaches what is allowed.
        assertThat(asMember(get(B + "/people"), m, null).status()).isEqualTo(200);
    }

    @Test
    void theMemberPortServesOnlyTheMemberSurface() throws Exception {
        Member m = member(true);
        String[][] hidden = {
                {"POST", "/api/auth/login"}, {"GET", "/api/auth/status"}, {"POST", "/api/auth/logout"}, {"GET", "/api/me"},
                {"GET", "/api/dashboard"}, {"GET", "/api/admin/config"}, {"GET", "/api/admin/terminal/ws"}, {"GET", "/api/admin/docker/config"},
                {"GET", "/api/widgets/info/search"}, {"GET", "/api/status"}, {"POST", "/api/actions/apps/prepare"},
                {"GET", "/wake/mac/00:00:5E:00:53:01"}, {"POST", "/wake"}, {"GET", "/toggle/pve/qemu/100"}, {"POST", "/toggle"},
                {"GET", "/node/pve/shutdown"}, {"POST", "/node/pve/reboot"},
                {"GET", "/actuator/health"}, {"GET", "/actuator"}, {"GET", "/images/logo.png"},
                {"POST", B + "/people"}, {"PUT", B + "/people/1"}, {"POST", B + "/recurring"}, {"POST", B + "/settlements"},
                {"POST", B + "/months/2026-09/open"}, {"GET", B + "/nothing"}, {"GET", B + "/people/1/account"},
                {"OPTIONS", B + "/people"}, {"PATCH", B + "/expenses/1"},
        };
        for (String[] r : hidden) {
            MockHttpServletRequestBuilder b = switch (r[0]) {
                case "POST" -> post(r[1]);
                case "PUT" -> put(r[1]);
                case "OPTIONS" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(r[1]);
                case "PATCH" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(r[1]);
                default -> get(r[1]);
            };
            // First with a member session, then with an admin session: neither opens anything that is not on the list.
            for (RequestPostProcessor who : new RequestPostProcessor[] {req -> { req.setSession(m.session()); return req; }, user("admin")}) {
                MockHttpServletResponse res = mvc.perform(b.with(onMemberPort()).with(who).with(csrf()).with(from(m.ip()))).andReturn().getResponse();
                assertThat(res.getStatus()).as("%s %s", r[0], r[1]).isEqualTo(404);
                assertThat(res.getContentAsString()).isEqualTo("{\"error\":\"not_found\"}");
            }
        }
        // What does exist on that port.
        Res ctx = run(get("/api/member/context").with(onMemberPort()), null);
        assertThat(ctx.body().get("memberPort").asBoolean()).isTrue();
        assertThat(run(get("/api/member/context"), null).body().get("memberPort").asBoolean()).isFalse();
        Res in = run(post("/api/member/login").with(onMemberPort()).with(from(m.ip())), Map.of("name", m.name(), "password", m.password()));
        assertThat(in.status()).isEqualTo(200);
        assertThat(mvc.perform(get(B + "/people").with(onMemberPort()).session(m.session())).andReturn().getResponse().getStatus()).isEqualTo(200);
        MockHttpServletResponse root = mvc.perform(get("/").with(onMemberPort())).andReturn().getResponse();
        assertThat(root.getContentAsString()).isNotEqualTo("{\"error\":\"not_found\"}"); // the SPA path is passed on (this build has no SPA files)
        MockHttpServletResponse asset = mvc.perform(get("/assets/app.js").with(onMemberPort())).andReturn().getResponse();
        assertThat(asset.getContentAsString()).isNotEqualTo("{\"error\":\"not_found\"}");
        // On the main port nothing changed for the admin.
        assertThat(run(get("/api/auth/status"), null).status()).isEqualTo(200);
        assertThat(run(get("/api/dashboard").with(user("admin")), null).status()).isEqualTo(200);
    }

    @Test
    void contextTellsEachCallerWhoTheyAre() throws Exception {
        JsonNode anon = run(get("/api/member/context"), null).body();
        assertThat(anon.get("authenticated").asBoolean()).isFalse();
        assertThat(anon.get("role").isNull()).isTrue();
        assertThat(anon.get("memberPortNumber").isNull()).isTrue();

        JsonNode adminCtx = admin(get("/api/member/context"), null).body();
        assertThat(adminCtx.get("role").asText()).isEqualTo("admin");
        assertThat(adminCtx.get("memberPortNumber").asInt()).isEqualTo(MEMBER_PORT);
        assertThat(run(get("/api/member/context").with(user("admin")).with(onMemberPort()), null).body().get("role").isNull()).isTrue();

        Member m = member(true);
        JsonNode mc = asMember(get("/api/member/context"), m, null).body();
        assertThat(mc.get("role").asText()).isEqualTo("member");
        assertThat(mc.get("name").asText()).isEqualTo(m.name());
        assertThat(mc.get("personId").asLong()).isEqualTo(m.id());
        assertThat(mc.get("canAdd").asBoolean()).isTrue();
        assertThat(mc.get("mustChange").asBoolean()).isFalse();
        assertThat(mc.get("memberPortNumber").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- filtered export

    @Test
    void theFilteredPdfStatesTheFilterAndListsOnlyMatchingEntries() throws Exception {
        String n1 = freshName("Asha");
        String n2 = freshName("Ravi");
        String n3 = freshName("Meera");
        long a = admin(post(B + "/people"), Map.of("name", n1)).body().get("id").asLong();
        long r = admin(post(B + "/people"), Map.of("name", n2)).body().get("id").asLong();
        long c = admin(post(B + "/people"), Map.of("name", n3)).body().get("id").asLong();
        admin(post(B + "/expenses"), expense("Groceries", 90000, a, "2025-03", 4, List.of(a, r)));
        admin(post(B + "/expenses"), expense("Taxi", 30000, a, "2025-03", 5, List.of(a)));
        admin(post(B + "/expenses"), expense("Cinema", 60000, c, "2025-03", 6, List.of(r, c)));

        String all = pdfText("/export.pdf?from=2025-03-01&to=2025-03-31");
        assertThat(all).contains("Groceries", "Taxi", "Cinema").doesNotContain("Filter:");
        String split = pdfText("/export.pdf?from=2025-03-01&to=2025-03-31&split=true");
        assertThat(split).contains("Groceries", "Cinema", "Filter: Split entries only").doesNotContain("Taxi");
        String people = pdfText("/export.pdf?from=2025-03-01&to=2025-03-31&people=" + a);
        assertThat(people).contains("Groceries", "Taxi", n1).doesNotContain("Cinema");
        String both = pdfText("/export.pdf?from=2025-03-01&to=2025-03-31&split=true&people=" + a + "," + c);
        assertThat(both).contains("Groceries", "Cinema").doesNotContain("Taxi");
        assertThat(admin(get(B + "/export.pdf?from=2025-03-01&to=2025-03-31&people=abc"), null).status()).isEqualTo(422);
    }

    private String pdfText(String path) throws Exception {
        byte[] bytes = mvc.perform(get(B + path).with(user("admin"))).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
