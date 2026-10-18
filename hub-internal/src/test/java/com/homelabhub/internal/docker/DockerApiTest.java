package com.homelabhub.internal.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.homelabhub.internal.TestSupport;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.terminal.TerminalClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The Docker page through the real security chain, a real SSH server and a real shell (with a fake docker CLI). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DockerApiTest {

    static final Path DIR = TestSupport.tempDir();
    static final String PASSWORD = "correct horse battery staple";
    static final DockerTestServer SSH = DockerTestServer.forHub(DIR);
    static final String JELLYFIN = "bbb0000000000000000000000000000000000000000000000000000000000002";
    static final String HUB = "aaa0000000000000000000000000000000000000000000000000000000000001";
    static final String OLD = "ccc0000000000000000000000000000000000000000000000000000000000003";
    static final String PIHOLE = "ddd0000000000000000000000000000000000000000000000000000000000004";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestSupport.useTempDatabase(r, DIR);
        r.add("hub.auth.password-hash", () -> new BCryptPasswordEncoder(4).encode(PASSWORD));
        r.add("hub.auth.mode", () -> "all");
        r.add("hub.docker.enabled", () -> "true");
        r.add("hub.docker.command-timeout-seconds", () -> "5");
        r.add("hub.docker.idle-session-seconds", () -> "2");
        r.add("hub.status.auto-start", () -> "false");
    }

    @AfterAll
    static void stopSsh() throws IOException {
        SSH.close();
    }

    @LocalServerPort int port;
    @MockitoBean ProxmoxClient proxmox;
    @Autowired JdbcClient jdbc;
    @Autowired DockerSessionPool pool;

    TerminalClient browser() throws Exception {
        return new TerminalClient(port).login(PASSWORD);
    }

    static String field(String json, String name) {
        return json.replaceAll("(?s).*\"" + name + "\":\"([^\"]*)\".*", "$1");
    }

    String confirm(TerminalClient b, String prepared, String typed) throws Exception {
        String token = field(prepared, "token");
        HttpResponse<String> r = b.call("POST", "/api/admin/docker/confirm",
                "{\"token\":\"" + token + "\"" + (typed == null ? "" : ",\"typed\":\"" + typed + "\"") + "}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.body();
    }

    String waitForJob(TerminalClient b, String confirmed) throws Exception {
        String id = field(confirmed, "jobId");
        String[] body = new String[1];
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            body[0] = b.call("GET", "/api/admin/docker/jobs/" + id, null).body();
            return !body[0].contains("\"state\":\"running\"");
        });
        return body[0];
    }

    // ---- access ----------------------------------------------------------------------------------------------------

    @Test
    void everythingNeedsTheLoginAndWritesNeedCsrf() throws Exception {
        TerminalClient anon = new TerminalClient(port);
        assertThat(anon.call("GET", "/api/admin/docker/config", null).statusCode()).isEqualTo(401);
        assertThat(anon.call("GET", "/api/admin/docker/hosts/pi/containers", null).statusCode()).isEqualTo(401);
        TerminalClient b = browser();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/docker/hosts/pi/containers/" + OLD + "/start/prepare"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(b.http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    void configListsHostsButNeverKeys() throws Exception {
        TerminalClient b = browser();
        String body = b.call("GET", "/api/admin/docker/config", null).body();
        assertThat(body).contains("\"enabled\":true").contains("\"id\":\"pi\"").contains("\"id\":\"down\"")
                .doesNotContain("hub_docker").doesNotContain("SHA256");
        assertThat(b.call("GET", "/api/dashboard", null).body()).contains("\"features\":{\"terminal\":false,\"docker\":true}");
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Test
    void containersAreListedWithProtectionAndCapabilities() throws Exception {
        HttpResponse<String> r = browser().call("GET", "/api/admin/docker/hosts/pi/containers", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String body = r.body();
        assertThat(body).contains("\"name\":\"jellyfin\"", "\"stack\":\"media\"", "\"health\":\"healthy\"");
        assertThat(body.substring(body.indexOf("\"name\":\"hub-internal\""))).contains("\"protected\":true", "This is the hub itself.");
        assertThat(body.substring(body.indexOf("\"name\":\"pihole\""))).contains("\"protected\":true", "protect");
        assertThat(body.substring(body.indexOf("\"name\":\"old\""))).contains("\"canRemove\":true");
        assertThat(body.substring(body.indexOf("\"name\":\"jellyfin\""))).contains("\"canUpdate\":true", "\"canRemove\":false");
        assertThat(body).doesNotContain("workdir").doesNotContain("configFiles");
    }

    @Test
    void anUnreachableHostIsAClearErrorForThatHostOnly() throws Exception {
        TerminalClient b = browser();
        HttpResponse<String> r = b.call("GET", "/api/admin/docker/hosts/down/containers", null);
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).contains("\"error\":\"unreachable\"").contains("127.0.0.1:1");
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/containers", null).statusCode()).isEqualTo(200);
        assertThat(b.call("GET", "/api/admin/docker/hosts/nope/containers", null).statusCode()).isEqualTo(404);
    }

    @Test
    void statsLogsAndNetworks() throws Exception {
        TerminalClient b = browser();
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/stats", null).body()).contains("\"cpuPercent\":1.5");
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/networks", null).body()).contains("\"bridge\"", "\"proxy\"");
        HttpResponse<String> logs = b.call("GET", "/api/admin/docker/hosts/pi/containers/" + JELLYFIN + "/logs?tail=2", null);
        assertThat(logs.statusCode()).isEqualTo(200);
        assertThat(logs.body()).contains("second", "third").doesNotContain("first");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("logs", "--timestamps", "--tail", "2", JELLYFIN));
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/containers/nothex/logs", null).statusCode()).isEqualTo(400);
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/containers/" + "e".repeat(64) + "/logs", null).statusCode()).isEqualTo(404);
    }

    @Test
    void followStreamsNewLines() throws Exception {
        TerminalClient b = browser();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/docker/hosts/pi/containers/" + JELLYFIN + "/logs/stream"))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<java.io.InputStream> r = b.http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        StringBuilder seen = new StringBuilder();
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(r.body()))) {
            String line;
            while ((line = in.readLine()) != null && !seen.toString().contains("live line 3")) {
                seen.append(line).append('\n');
            }
        }
        assertThat(seen).contains("event:lines").contains("live line 0");
    }

    @Test
    void followOfAnUnknownContainerIsAFailedEvent() throws Exception {
        TerminalClient b = browser();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/docker/hosts/pi/containers/" + "e".repeat(64) + "/logs/stream"))
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<String> r = b.http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.body()).contains("event:failed").contains("no_container");
    }

    // ---- container actions -----------------------------------------------------------------------------------------

    @Test
    void restartIsTwoStepAndAudited() throws Exception {
        TerminalClient b = browser();
        HttpResponse<String> p = b.call("POST", "/api/admin/docker/hosts/pi/containers/" + JELLYFIN + "/restart/prepare", null);
        assertThat(p.statusCode()).as(p.body()).isEqualTo(200);
        assertThat(p.body()).contains("\"confirm\":\"click\"").contains("Restart jellyfin");
        assertThat(confirm(b, p.body(), null)).contains("\"ok\":true").contains("Restarted jellyfin");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("restart", JELLYFIN));
        assertThat(jdbc.sql("SELECT action || ':' || result FROM docker_audit WHERE target = 'jellyfin'").query(String.class).list())
                .contains("restart:ok");
        // a token works once
        HttpResponse<String> again = b.call("POST", "/api/admin/docker/confirm", "{\"token\":\"" + field(p.body(), "token") + "\"}");
        assertThat(again.statusCode()).isEqualTo(410);
    }

    @Test
    void stopNeedsTheNameTypedAndTokensAreBoundToTheSession() throws Exception {
        TerminalClient b = browser();
        String p = b.call("POST", "/api/admin/docker/hosts/pi/containers/" + JELLYFIN + "/stop/prepare", null).body();
        assertThat(p).contains("\"confirm\":\"type\"").contains("\"word\":\"jellyfin\"");
        TerminalClient other = browser();
        assertThat(other.call("POST", "/api/admin/docker/confirm", "{\"token\":\"" + field(p, "token") + "\",\"typed\":\"jellyfin\"}").statusCode())
                .isEqualTo(403);
        assertThat(b.call("POST", "/api/admin/docker/confirm", "{\"token\":\"" + field(p, "token") + "\",\"typed\":\"nope\"}").statusCode())
                .isEqualTo(422);
        assertThat(confirm(b, p, "jellyfin")).contains("Stopped jellyfin");
    }

    @Test
    void protectedContainersCannotBeStoppedButCanBeStarted() throws Exception {
        TerminalClient b = browser();
        for (String op : List.of("stop", "restart", "update", "remove")) {
            HttpResponse<String> r = b.call("POST", "/api/admin/docker/hosts/pi/containers/" + HUB + "/" + op + "/prepare", null);
            assertThat(r.statusCode()).as(op).isEqualTo(409);
            assertThat(r.body()).contains("protected");
        }
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/containers/" + PIHOLE + "/stop/prepare", null).statusCode()).isEqualTo(409);
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/containers/" + PIHOLE + "/start/prepare", null).statusCode()).isEqualTo(200);
    }

    @Test
    void removeOnlyForStoppedNonComposeContainers() throws Exception {
        TerminalClient b = browser();
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/containers/" + JELLYFIN + "/remove/prepare", null).statusCode()).isEqualTo(409);
        String p = b.call("POST", "/api/admin/docker/hosts/pi/containers/" + OLD + "/remove/prepare", null).body();
        assertThat(confirm(b, p, "old")).contains("Removed old");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("rm", OLD));
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/containers/" + OLD + "/explode/prepare", null).statusCode()).isEqualTo(404);
    }

    @Test
    void updateRunsComposeWithTheContainersOwnLabelsAsAJob() throws Exception {
        TerminalClient b = browser();
        String p = b.call("POST", "/api/admin/docker/hosts/pi/containers/" + JELLYFIN + "/update/prepare", null).body();
        String job = waitForJob(b, confirm(b, p, "jellyfin"));
        assertThat(job).contains("\"state\":\"ok\"").contains("compose ok");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("compose", "-p", "media", "--project-directory",
                "/home/hubdocker/stacks/media", "-f", "/home/hubdocker/stacks/media/compose.yaml", "pull", "--ignore-buildable", "jellyfin"));
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql("SELECT count(*) FROM docker_audit WHERE action = 'update' AND result = 'ok'")
                .query(Integer.class).single() > 0);
    }

    @Test
    void aDockerErrorIsShownAsTheHostSaidIt() throws Exception {
        TerminalClient b = browser();
        Files.writeString(SSH.state.resolve("fail"), "Got permission denied while trying to connect to the Docker daemon socket");
        try {
            String p = b.call("POST", "/api/admin/docker/hosts/pi/containers/" + JELLYFIN + "/restart/prepare", null).body();
            HttpResponse<String> r = b.call("POST", "/api/admin/docker/confirm", "{\"token\":\"" + field(p, "token") + "\"}");
            assertThat(r.statusCode()).isEqualTo(502);
            assertThat(r.body()).contains("docker_permission").contains("usermod -aG docker hubdocker");
        } finally {
            Files.delete(SSH.state.resolve("fail"));
        }
    }

    // ---- run form ----------------------------------------------------------------------------------------------------

    @Test
    void runSendsTheEnvironmentOnStdinNeverOnTheCommandLine() throws Exception {
        TerminalClient b = browser();
        HttpResponse<String> p = b.call("POST", "/api/admin/docker/hosts/pi/run/prepare", """
                {"image":"nginx:1.27","name":"web","ports":["8080:80"],"volumes":[{"source":"/","target":"/host","readOnly":true}],
                 "env":[{"key":"PASSWORD","value":"it's $(secret)"}],"restart":"unless-stopped","network":"proxy","command":[]}""");
        assertThat(p.statusCode()).as(p.body()).isEqualTo(200);
        assertThat(p.body()).doesNotContain("secret)").contains("PASSWORD").contains("Mounts /");
        String job = waitForJob(b, confirm(b, p.body(), null));
        assertThat(job).contains("\"state\":\"ok\"").contains("deadbeef").doesNotContain("secret)");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("run", "-d", "--name", "web", "--restart", "unless-stopped",
                "--network", "proxy", "-p", "8080:80", "-v", "/:/host:ro", "--env-file", "/dev/stdin", "nginx:1.27"));
        assertThat(SSH.lastStdin()).isEqualTo("PASSWORD=it's $(secret)\n");
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/run/prepare", "{\"image\":\"--privileged\"}").statusCode()).isEqualTo(400);
    }

    // ---- stacks ------------------------------------------------------------------------------------------------------

    @Test
    void stacksAreCreatedValidatedVersionedAndDeployed() throws Exception {
        TerminalClient b = browser();
        String compose1 = "services:\\n  web:\\n    image: nginx\\n";
        HttpResponse<String> created = b.call("POST", "/api/admin/docker/hosts/pi/stacks",
                "{\"name\":\"web\",\"compose\":\"" + compose1 + "\",\"env\":\"TOKEN=abc\\n\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(Files.readString(SSH.home.resolve("stacks/web/compose.yaml"))).isEqualTo("services:\n  web:\n    image: nginx\n");
        assertThat(Files.readString(SSH.home.resolve("stacks/web/.env"))).isEqualTo("TOKEN=abc\n");
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(SSH.home.resolve("stacks/web/.env")))).isEqualTo("rw-------");
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/stacks", "{\"name\":\"web\",\"compose\":\"x: 1\"}").statusCode()).isEqualTo(409);

        // an invalid file is refused by compose and changes nothing
        HttpResponse<String> bad = b.call("PUT", "/api/admin/docker/hosts/pi/stacks/web", "{\"compose\":\"INVALID\",\"env\":\"\"}");
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(bad.body()).contains("Additional property nope");
        assertThat(Files.readString(SSH.home.resolve("stacks/web/compose.yaml"))).contains("image: nginx");
        assertThat(Files.exists(SSH.home.resolve("stacks/web/compose.yaml.new"))).isFalse();

        HttpResponse<String> saved = b.call("PUT", "/api/admin/docker/hosts/pi/stacks/web",
                "{\"compose\":\"services:\\n  web:\\n    image: nginx:1.27\\n\",\"env\":\"TOKEN=abc\\n\"}");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/stacks/web/versions", null).body()).contains("image: nginx\\n");
        String read = b.call("GET", "/api/admin/docker/hosts/pi/stacks/web", null).body();
        assertThat(read).contains("nginx:1.27").contains("TOKEN=abc");
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/stacks", null).body()).contains("\"name\":\"web\",\"managed\":true")
                .contains("\"name\":\"media\"").contains("\"name\":\"deploy\",\"managed\":false");

        String p = b.call("POST", "/api/admin/docker/hosts/pi/stacks/web/deploy/prepare", null).body();
        assertThat(waitForJob(b, confirm(b, p, null))).contains("\"state\":\"ok\"");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("compose", "-p", "web", "--project-directory", "stacks/web",
                "up", "-d", "--remove-orphans"));

        String del = b.call("POST", "/api/admin/docker/hosts/pi/stacks/web/delete/prepare?volumes=true", null).body();
        assertThat(del).contains("NAMED VOLUMES").contains("\"word\":\"web\"");
        assertThat(waitForJob(b, confirm(b, del, "web"))).contains("\"state\":\"ok\"");
        assertThat(Files.exists(SSH.home.resolve("stacks/web"))).isFalse();
        assertThat(b.call("GET", "/api/admin/docker/hosts/pi/stacks/web", null).statusCode()).isEqualTo(404);
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/stacks", "{\"name\":\"../x\",\"compose\":\"a: 1\"}").statusCode()).isEqualTo(400);
    }

    // ---- clean up ----------------------------------------------------------------------------------------------------

    static final String USED = "sha256:" + "1".repeat(64);
    static final String STOPPED_ONLY = "sha256:" + "2".repeat(64);
    static final String NGINX = "sha256:" + "3".repeat(64);
    static final String DANGLING = "sha256:" + "4".repeat(64);

    @Test
    void cleanupPreviewOffersOnlyWhatNoContainerUses() throws Exception {
        HttpResponse<String> r = browser().call("GET", "/api/admin/docker/hosts/pi/cleanup/preview", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains(NGINX, DANGLING, "nginx:1.27", "nginx:latest", "187MB", "old-net")
                .doesNotContain(USED).doesNotContain(STOPPED_ONLY).doesNotContain("\"name\":\"bridge\"");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("inspect", "--type", "container", "--format", "{{.Image}}",
                "aaa", "ccc"));
    }

    @Test
    void cleanupRemovesExactlyTheSelectionAsAnAuditedJob() throws Exception {
        TerminalClient b = browser();
        HttpResponse<String> p = b.call("POST", "/api/admin/docker/hosts/pi/cleanup/prepare",
                "{\"images\":[\"" + NGINX + "\",\"" + DANGLING + "\"],\"networks\":[\"old-net\"]}");
        assertThat(p.statusCode()).as(p.body()).isEqualTo(200);
        assertThat(p.body()).contains("\"confirm\":\"click\"").contains("docker rmi 'nginx:1.27' 'nginx:latest'").contains("3 items");
        String job = waitForJob(b, confirm(b, p.body(), null));
        assertThat(job).contains("\"state\":\"ok\"").contains("Removed 3 of 3.");
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("rmi", "nginx:1.27", "nginx:latest"));
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("rmi", DANGLING));
        assertThat(SSH.dockerCalls()).anySatisfy(c -> assertThat(c).containsExactly("network", "rm", "old-net"));
        assertThat(SSH.dockerCalls().stream().filter(c -> c.get(0).equals("rmi") || c.get(0).equals("network"))).allSatisfy(c ->
                assertThat(c).doesNotContain("-f", "--force"));
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql("SELECT count(*) FROM docker_audit WHERE action LIKE 'cleanup:%' AND result = 'ok'")
                .query(Integer.class).single() >= 3);
        assertThat(jdbc.sql("SELECT target FROM docker_audit WHERE action = 'cleanup:network'").query(String.class).list()).contains("network:old-net");
    }

    @Test
    void cleanupRefusesAnythingInUseOrUnknown() throws Exception {
        TerminalClient b = browser();
        for (String body : List.of("{\"images\":[\"" + STOPPED_ONLY + "\"]}", "{\"images\":[\"" + USED + "\"]}", "{\"networks\":[\"bridge\"]}",
                "{\"networks\":[\"proxy\"]}")) {
            HttpResponse<String> r = b.call("POST", "/api/admin/docker/hosts/pi/cleanup/prepare", body);
            assertThat(r.statusCode()).as(body).isEqualTo(409);
            assertThat(r.body()).contains("no_longer_unused");
        }
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/cleanup/prepare", "{\"images\":[],\"networks\":[]}").statusCode()).isEqualTo(400);
        assertThat(b.call("POST", "/api/admin/docker/hosts/pi/cleanup/prepare", "{\"images\":[\"nginx;id\"]}").statusCode()).isEqualTo(400);
    }

    @Test
    void aPartialCleanupFailsTheJobAndAuditsEachItem() throws Exception {
        TerminalClient b = browser();
        Files.writeString(SSH.state.resolve("fail-rm"), DANGLING + "\n");
        try {
            String p = b.call("POST", "/api/admin/docker/hosts/pi/cleanup/prepare",
                    "{\"images\":[\"" + DANGLING + "\",\"" + NGINX + "\"]}").body();
            String job = waitForJob(b, confirm(b, p, null));
            assertThat(job).contains("\"state\":\"failed\"").contains("Removed 1 of 2.").contains("unable to remove");
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql("SELECT count(*) FROM docker_audit WHERE action = 'cleanup:image' AND result = 'failed'")
                    .query(Integer.class).single() > 0);
        } finally {
            Files.delete(SSH.state.resolve("fail-rm"));
        }
    }

    @Test
    void idleSessionsAreClosedAndTheSweeperStops() throws Exception {
        browser().call("GET", "/api/admin/docker/hosts/pi/networks", null);
        await().atMost(Duration.ofSeconds(15)).until(() -> pool.open() == 0 && !pool.sweeperRunning());
    }
}
