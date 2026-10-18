package com.homelabhub.internal.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homelabhub.internal.docker.DockerCommands.Env;
import com.homelabhub.internal.docker.DockerCommands.RunSpec;
import com.homelabhub.internal.docker.DockerCommands.Volume;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every value is validated and quoted; nothing from the browser can escape its argument. */
class DockerCommandsTest {

    static final String ID = "a".repeat(64);

    @Test
    void quotingSurvivesARealShell() throws Exception {
        for (String nasty : List.of("plain", "it's", "$(touch /tmp/pwned)", "`id`", "a;b", "a b\tc", "'\"'", "\\", "--rm")) {
            assertThat(sh("printf '%s' " + DockerCommands.q(nasty))).isEqualTo(nasty);
        }
    }

    @Test
    void containerIdsMustBeHex() {
        assertThat(DockerCommands.container(DockerCommands.ContainerOp.restart, ID)).isEqualTo("docker restart '" + ID + "' 2>&1");
        assertThat(DockerCommands.container(DockerCommands.ContainerOp.remove, ID)).startsWith("docker rm ");
        for (String bad : List.of("abc", "nginx", ID + ";rm", "'" + ID, "A".repeat(64), "")) {
            assertThatThrownBy(() -> DockerCommands.container(DockerCommands.ContainerOp.stop, bad)).isInstanceOf(DockerException.class);
        }
        assertThatThrownBy(() -> DockerCommands.logs(null, 10)).isInstanceOf(DockerException.class);
    }

    @Test
    void cleanupRemovesByTagOrIdAndNeverForces() {
        String id = "sha256:" + "3".repeat(64);
        assertThat(DockerCommands.removeImage(id, List.of("nginx:1.27", "nginx:latest"))).isEqualTo("docker rmi 'nginx:1.27' 'nginx:latest' 2>&1");
        assertThat(DockerCommands.removeImage(id, List.of())).isEqualTo("docker rmi '" + id + "' 2>&1");
        assertThat(DockerCommands.removeNetwork("old-net")).isEqualTo("docker network rm 'old-net' 2>&1");
        for (String bad : List.of("sha256:xyz", "nginx", id + ";rm -rf ~", "")) {
            assertThatThrownBy(() -> DockerCommands.removeImage(bad, List.of())).isInstanceOf(DockerException.class);
        }
        assertThatThrownBy(() -> DockerCommands.removeImage(id, List.of("-f"))).isInstanceOf(DockerException.class);
        assertThatThrownBy(() -> DockerCommands.removeImage(id, List.of("a b"))).isInstanceOf(DockerException.class);
        for (String bad : List.of("-f", "a b", "$(id)", "")) {
            assertThatThrownBy(() -> DockerCommands.removeNetwork(bad)).isInstanceOf(DockerException.class);
        }
        assertThat(DockerCommands.removeImage(id, List.of())).doesNotContain(" -f");
    }

    @Test
    void updateChecksComposeLabelsFromTheHost() {
        String cmd = DockerCommands.update("media", "jellyfin", "/home/me/media", List.of("/home/me/media/compose.yaml"));
        assertThat(cmd).isEqualTo("docker compose -p 'media' --project-directory '/home/me/media' -f '/home/me/media/compose.yaml' "
                + "pull --ignore-buildable 'jellyfin' 2>&1 && docker compose -p 'media' --project-directory '/home/me/media' "
                + "-f '/home/me/media/compose.yaml' up -d --no-deps 'jellyfin' 2>&1");
        assertThatThrownBy(() -> DockerCommands.update("Media;x", "s", "/a", List.of())).isInstanceOf(DockerException.class);
        assertThatThrownBy(() -> DockerCommands.update("m", "s$(x)", "/a", List.of())).isInstanceOf(DockerException.class);
        assertThatThrownBy(() -> DockerCommands.update("m", "s", "relative", List.of())).isInstanceOf(DockerException.class);
        assertThatThrownBy(() -> DockerCommands.update("m", "s", "/a/../etc", List.of())).isInstanceOf(DockerException.class);
        assertThatThrownBy(() -> DockerCommands.update("m", "s", "/a", List.of("/a/b c.yml"))).isInstanceOf(DockerException.class);
    }

    @Test
    void stackNamesAndCommands() {
        assertThat(DockerCommands.stackName("media-2")).isEqualTo("media-2");
        for (String bad : List.of("Media", "-x", "a/b", "..", "a b", "x".repeat(41), "")) {
            assertThatThrownBy(() -> DockerCommands.stackName(bad)).as(bad).isInstanceOf(DockerException.class);
        }
        assertThat(DockerCommands.stack(DockerCommands.StackOp.deploy, "web", false))
                .isEqualTo("docker compose -p 'web' --project-directory stacks/'web' up -d --remove-orphans 2>&1");
        assertThat(DockerCommands.stack(DockerCommands.StackOp.down, "web", true)).contains(" down -v ");
        assertThat(DockerCommands.stack(DockerCommands.StackOp.delete, "web", false)).endsWith("&& rm -rf stacks/'web'").doesNotContain("-v");
        assertThat(DockerCommands.writeStackFile("web", DockerCommands.COMPOSE_FILE))
                .isEqualTo("umask 077 && mkdir -p stacks/'web' && cat > stacks/'web'/compose.yaml.new");
        assertThatThrownBy(() -> DockerCommands.writeStackFile("web", "../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runBuildsAQuotedCommandAndSendsEnvOnStdin() {
        DockerCommands.Run r = DockerCommands.run(new RunSpec("nginx:1.27", "web", List.of("8080:80", "127.0.0.1:8443:443/tcp", " "),
                List.of(new Volume("/srv/web", "/usr/share/nginx/html", true), new Volume("webdata", "/data", false), new Volume("", "", false)),
                List.of(new Env("PASSWORD", "s3cret; $(rm -rf /)"), new Env("", "")), "always", "proxy", List.of("nginx", "-g", "daemon off;")));
        assertThat(r.command()).isEqualTo("docker run -d --name 'web' --restart 'always' --network 'proxy' -p '8080:80' "
                + "-p '127.0.0.1:8443:443/tcp' -v '/srv/web:/usr/share/nginx/html:ro' -v 'webdata:/data' --env-file /dev/stdin "
                + "'nginx:1.27' 'nginx' '-g' 'daemon off;' 2>&1");
        assertThat(r.command()).doesNotContain("s3cret");
        assertThat(r.display()).doesNotContain("s3cret").contains("PASSWORD");
        assertThat(new String(r.stdin(), StandardCharsets.UTF_8)).isEqualTo("PASSWORD=s3cret; $(rm -rf /)\n");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void runDefaultsAndWarnings() {
        DockerCommands.Run r = DockerCommands.run(new RunSpec("alpine", null, null, List.of(new Volume("/", "/host", true),
                new Volume("/var/run/docker.sock", "/var/run/docker.sock", false)), null, null, null, null));
        assertThat(r.command()).startsWith("docker run -d --restart 'unless-stopped' -v '/:/host:ro'");
        assertThat(r.stdin()).isNull();
        assertThat(r.warnings()).hasSize(2);
    }

    @Test
    void runRefusesBadValues() {
        List<RunSpec> bad = List.of(
                spec("-v/:/x", null, null, null),
                spec("nginx", "bad name", null, null),
                spec("nginx", null, List.of("80"), null),
                spec("nginx", null, List.of("70000:80"), null),
                spec("nginx", null, List.of("999.1.1.1:80:80"), null),
                spec("nginx", null, List.of("8080:80; id"), null),
                new RunSpec("nginx", null, null, List.of(new Volume("rel/path", "/x", false)), null, null, null, null),
                new RunSpec("nginx", null, null, List.of(new Volume("/a", "x", false)), null, null, null, null),
                new RunSpec("nginx", null, null, null, List.of(new Env("1BAD", "x")), null, null, null),
                new RunSpec("nginx", null, null, null, List.of(new Env("OK", "two\nlines")), null, null, null),
                new RunSpec("nginx", null, null, null, null, "sometimes", null, null),
                new RunSpec("nginx", null, null, null, null, null, "net;x", null),
                new RunSpec("nginx", null, null, null, null, null, null, List.of("a\nb")));
        for (RunSpec s : bad) {
            assertThatThrownBy(() -> DockerCommands.run(s)).as(s.toString()).isInstanceOf(DockerException.class);
        }
    }

    private static RunSpec spec(String image, String name, List<String> ports, List<Volume> volumes) {
        return new RunSpec(image, name, ports, volumes, null, null, null, null);
    }

    @Test
    void psFormatIsOneQuotedArgument() throws Exception {
        Path bin = Files.createTempDirectory("fake-bin");
        Path docker = bin.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\nprintf '%s\\n' \"$#\" \"$@\"\n");
        docker.toFile().setExecutable(true);
        String out = sh("PATH=" + bin + ":$PATH; " + DockerCommands.PS);
        List<String> args = out.lines().toList();
        assertThat(args.get(0)).isEqualTo("5");
        assertThat(args.subList(1, 5)).containsExactly("ps", "-a", "--no-trunc", "--format");
        assertThat(args.get(5)).isEqualTo(DockerCommands.PS_FORMAT);
    }

    static String sh(String command) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        p.getInputStream().transferTo(b);
        p.waitFor();
        return b.toString(StandardCharsets.UTF_8);
    }
}
