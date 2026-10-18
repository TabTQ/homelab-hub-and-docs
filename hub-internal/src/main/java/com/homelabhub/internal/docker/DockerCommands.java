package com.homelabhub.internal.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every command the Docker page can run on a host is built here, from a fixed set of verbs. Each value that came from the
 * browser or from a host is checked against a strict pattern first AND single-quoted, so nothing can break out of its
 * argument. Free text (file contents, environment values) never goes on a command line: it is sent on the channel's stdin.
 */
final class DockerCommands {

    private DockerCommands() {}

    static final Pattern CONTAINER_ID = Pattern.compile("[a-f0-9]{12,64}");
    static final Pattern CONTAINER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    static final Pattern PROJECT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");
    static final Pattern SERVICE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}");
    static final Pattern STACK = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    static final Pattern ABS_PATH = Pattern.compile("/[A-Za-z0-9._/@+-]{0,250}");
    static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}");
    static final Pattern IMAGE_ID = Pattern.compile("(?:sha256:)?[a-f0-9]{12,64}");
    static final Pattern NETWORK = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    static final Pattern VOLUME_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    static final Pattern PORT = Pattern.compile("(?:((?:\\d{1,3}\\.){3}\\d{1,3}):)?(\\d{1,5}):(\\d{1,5})(?:/(tcp|udp))?");
    static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    static final Set<String> RESTART = Set.of("no", "unless-stopped", "always", "on-failure");
    static final int MAX_ARGS = 30;
    static final int MAX_ARG_LENGTH = 1000;
    static final int MAX_ROWS = 40;

    /**
     * One JSON object per container. Labels give the compose project, service, folder and files (for Update) and the
     * hub's own marker (com.homelabhub.self) so the hub can never stop itself.
     */
    static final String PS_FORMAT = "{\"id\":{{json .ID}},\"name\":{{json .Names}},\"image\":{{json .Image}},\"state\":{{json .State}},"
            + "\"status\":{{json .Status}},\"ports\":{{json .Ports}},\"created\":{{json .CreatedAt}},\"runningFor\":{{json .RunningFor}},"
            + "\"project\":{{json (.Label \"com.docker.compose.project\")}},\"service\":{{json (.Label \"com.docker.compose.service\")}},"
            + "\"workdir\":{{json (.Label \"com.docker.compose.project.working_dir\")}},"
            + "\"configFiles\":{{json (.Label \"com.docker.compose.project.config_files\")}},"
            + "\"self\":{{json (.Label \"com.homelabhub.self\")}}}";
    static final String PS = "docker ps -a --no-trunc --format " + q(PS_FORMAT);
    static final String STATS = "docker stats --no-stream --no-trunc --format '{{json .}}'";
    static final String NETWORKS = "docker network ls --format '{{.Name}}'";
    static final String STACKS = "mkdir -p stacks && ls -1 stacks";

    // ---- clean up: what nothing uses, and removing it one item at a time ------------------------------------------

    /** Every image, one JSON row per tag, full ids. */
    static final String IMAGES = "docker image ls --no-trunc --format '{{json .}}'";
    /** The image id of every container, running or stopped (docker ps only knows the name it was started with). */
    static final String CONTAINER_IMAGES = "ids=$(docker ps -aq --no-trunc); [ -z \"$ids\" ] || docker inspect --type container --format '{{.Image}}' $ids";
    /** Networks no container is attached to (docker leaves out bridge, host and none itself). */
    static final String DANGLING_NETWORKS = "docker network ls --filter dangling=true --format '{{json .}}'";

    enum ContainerOp { start, stop, restart, remove }

    enum StackOp { deploy, pull, stop, down, delete }

    static final String COMPOSE_FILE = "compose.yaml";
    static final String ENV_FILE = ".env";

    /** POSIX single quoting: the only character that needs care inside '...' is ' itself. */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String containerId(String id) {
        if (id == null || !CONTAINER_ID.matcher(id).matches()) {
            throw DockerException.bad("That is not a container id.");
        }
        return id;
    }

    static String stackName(String name) {
        if (name == null || !STACK.matcher(name).matches()) {
            throw DockerException.bad("A stack name uses lower-case letters, digits, - and _ (at most 40, starting with a letter or digit).");
        }
        return name;
    }

    static String absPath(String p, String what) {
        if (p == null || !ABS_PATH.matcher(p).matches() || p.contains("/../") || p.endsWith("/..") || p.contains("//")) {
            throw DockerException.bad("The " + what + " '" + clip(p) + "' is not a plain absolute path.");
        }
        return p;
    }

    static String logs(String id, int tail) {
        return "docker logs --timestamps --tail " + tail + " " + q(containerId(id)) + " 2>&1";
    }

    /** Run with a PTY so closing the channel hangs the process up instead of leaving it to the next write. */
    static String follow(String id) {
        return "exec docker logs --follow --timestamps --tail 0 " + q(containerId(id)) + " 2>&1";
    }

    static String container(ContainerOp op, String id) {
        String verb = op == ContainerOp.remove ? "rm" : op.name();
        return "docker " + verb + " " + q(containerId(id)) + " 2>&1";
    }

    /** docker compose for a project started anywhere (Update on a compose-managed container). */
    static String composeFor(String project, String workdir, List<String> files) {
        if (project == null || !PROJECT.matcher(project).matches()) {
            throw DockerException.bad("The container's compose project name is not one the hub can use.");
        }
        StringBuilder b = new StringBuilder("docker compose -p ").append(q(project))
                .append(" --project-directory ").append(q(absPath(workdir, "compose folder")));
        for (String f : files) {
            b.append(" -f ").append(q(absPath(f, "compose file")));
        }
        return b.toString();
    }

    static String update(String project, String service, String workdir, List<String> files) {
        if (service == null || !SERVICE.matcher(service).matches()) {
            throw DockerException.bad("The container's compose service name is not one the hub can use.");
        }
        String base = composeFor(project, workdir, files);
        return base + " pull --ignore-buildable " + q(service) + " 2>&1 && " + base + " up -d --no-deps " + q(service) + " 2>&1";
    }

    // ---- stacks (folders under ~/stacks on the host) ---------------------------------------------------------------

    static String stackDir(String name) {
        return "stacks/" + q(stackName(name));
    }

    static String stackCompose(String name) {
        return "docker compose -p " + q(stackName(name)) + " --project-directory " + stackDir(name);
    }

    /** Content arrives on stdin; files are private to the user (the .env usually holds passwords). */
    static String writeStackFile(String name, String file) {
        if (!file.equals(COMPOSE_FILE) && !file.equals(ENV_FILE)) {
            throw new IllegalArgumentException(file);
        }
        return "umask 077 && mkdir -p " + stackDir(name) + " && cat > " + stackDir(name) + "/" + file + ".new";
    }

    /** Validate the new files with compose itself; only if they pass do they replace the current ones. */
    static String commitStack(String name) {
        String d = stackDir(name);
        return "if " + stackCompose(name) + " -f " + d + "/" + COMPOSE_FILE + ".new --env-file " + d + "/" + ENV_FILE + ".new config -q 2>&1; "
                + "then mv " + d + "/" + COMPOSE_FILE + ".new " + d + "/" + COMPOSE_FILE + " && mv " + d + "/" + ENV_FILE + ".new "
                + d + "/" + ENV_FILE + "; else rm -f " + d + "/" + COMPOSE_FILE + ".new " + d + "/" + ENV_FILE + ".new; exit 3; fi";
    }

    static String readStackFile(String name, String file) {
        if (!file.equals(COMPOSE_FILE) && !file.equals(ENV_FILE)) {
            throw new IllegalArgumentException(file);
        }
        String path = stackDir(name) + "/" + file;
        return file.equals(ENV_FILE) ? "cat " + path + " 2>/dev/null; true" : "cat " + path;
    }

    static String stack(StackOp op, String name, boolean volumes) {
        String c = stackCompose(name);
        String v = volumes ? " -v" : "";
        switch (op) {
            case deploy:
                return c + " up -d --remove-orphans 2>&1";
            case pull:
                return c + " pull --ignore-buildable 2>&1 && " + c + " up -d --remove-orphans 2>&1";
            case stop:
                return c + " stop 2>&1";
            case down:
                return c + " down" + v + " 2>&1";
            case delete:
                return c + " down" + v + " 2>&1 && rm -rf " + stackDir(name);
            default:
                throw new IllegalArgumentException(op.name());
        }
    }

    static String imageId(String id) {
        if (id == null || !IMAGE_ID.matcher(id).matches()) {
            throw DockerException.bad("That is not an image id.");
        }
        return id;
    }

    /**
     * Removes an unused image by its tags (all of them, so an image with several names really goes), or by id when it has
     * none. Never -f: docker itself still refuses an image a container uses.
     */
    static String removeImage(String id, List<String> tags) {
        imageId(id);
        StringBuilder b = new StringBuilder("docker rmi");
        if (tags == null || tags.isEmpty()) {
            b.append(' ').append(q(id));
        } else {
            for (String t : tags) {
                if (t == null || !IMAGE.matcher(t).matches()) {
                    throw DockerException.bad("The image name '" + clip(t) + "' is not one the hub can use.");
                }
                b.append(' ').append(q(t));
            }
        }
        return b.append(" 2>&1").toString();
    }

    static String removeNetwork(String name) {
        if (name == null || !NETWORK.matcher(name).matches()) {
            throw DockerException.bad("That is not a network name.");
        }
        return "docker network rm " + q(name) + " 2>&1";
    }

    // ---- quick run form --------------------------------------------------------------------------------------------

    record Volume(String source, String target, boolean readOnly) {}

    record Env(String key, String value) {}

    record RunSpec(String image, String name, List<String> ports, List<Volume> volumes, List<Env> env, String restart, String network,
                   List<String> command) {}

    /** The checked command and the env-file text for its stdin (null when there is no environment). */
    record Run(String command, String display, byte[] stdin, List<String> warnings) {}

    static final List<String> RISKY_MOUNTS = List.of("/", "/etc", "/root", "/var/run/docker.sock", "/run/docker.sock", "/boot", "/proc", "/sys");

    static Run run(RunSpec s) {
        if (s == null) {
            throw DockerException.bad("Nothing to run.");
        }
        List<String> warnings = new ArrayList<>();
        String image = s.image() == null ? "" : s.image().strip();
        if (!IMAGE.matcher(image).matches()) {
            throw DockerException.bad("The image must look like nginx:1.27 or ghcr.io/owner/app:tag.");
        }
        StringBuilder b = new StringBuilder("docker run -d");
        if (s.name() != null && !s.name().isBlank()) {
            String name = s.name().strip();
            if (!CONTAINER_NAME.matcher(name).matches()) {
                throw DockerException.bad("The name may use letters, digits, _ . and - (starting with a letter or digit).");
            }
            b.append(" --name ").append(q(name));
        }
        String restart = s.restart() == null || s.restart().isBlank() ? "unless-stopped" : s.restart();
        if (!RESTART.contains(restart)) {
            throw DockerException.bad("Restart must be one of no, unless-stopped, always, on-failure.");
        }
        b.append(" --restart ").append(q(restart));
        if (s.network() != null && !s.network().isBlank()) {
            if (!NETWORK.matcher(s.network()).matches()) {
                throw DockerException.bad("That is not a network name.");
            }
            b.append(" --network ").append(q(s.network()));
        }
        for (String p : rows(s.ports(), "port")) {
            var m = PORT.matcher(p.strip());
            if (!m.matches() || !portOk(m.group(2)) || !portOk(m.group(3)) || (m.group(1) != null && !ipOk(m.group(1)))) {
                throw DockerException.bad("The port '" + clip(p) + "' must look like 8080:80, 8080:80/udp or 127.0.0.1:8080:80.");
            }
            b.append(" -p ").append(q(p.strip()));
        }
        for (Volume v : rows(s.volumes(), "volume")) {
            String src = v.source() == null ? "" : v.source().strip();
            String dst = v.target() == null ? "" : v.target().strip();
            if (src.startsWith("/")) {
                absPath(src, "host path");
                String norm = src.length() > 1 && src.endsWith("/") ? src.substring(0, src.length() - 1) : src;
                if (RISKY_MOUNTS.contains(norm)) {
                    warnings.add("Mounts " + norm + " from the host: the container gets wide access to the host.");
                }
            } else if (!VOLUME_NAME.matcher(src).matches()) {
                throw DockerException.bad("A volume source is an absolute host path or a volume name, not '" + clip(src) + "'.");
            }
            absPath(dst, "container path");
            b.append(" -v ").append(q(src + ":" + dst + (v.readOnly() ? ":ro" : "")));
        }
        StringBuilder envFile = new StringBuilder();
        for (Env e : rows(s.env(), "environment")) {
            if (e.key() == null || !ENV_KEY.matcher(e.key()).matches()) {
                throw DockerException.bad("An environment name uses letters, digits and _ and does not start with a digit.");
            }
            String val = e.value() == null ? "" : e.value();
            if (val.indexOf('\n') >= 0 || val.indexOf('\r') >= 0 || val.indexOf('\0') >= 0 || val.length() > 4000) {
                throw DockerException.bad("The value of " + e.key() + " must be a single line (at most 4000 characters).");
            }
            envFile.append(e.key()).append('=').append(val).append('\n');
        }
        if (envFile.length() > 0) {
            b.append(" --env-file /dev/stdin");
        }
        b.append(' ').append(q(image));
        List<String> cmd = s.command() == null ? List.of() : s.command();
        if (cmd.size() > MAX_ARGS) {
            throw DockerException.bad("At most " + MAX_ARGS + " command arguments.");
        }
        for (String a : cmd) {
            if (a == null || a.length() > MAX_ARG_LENGTH || a.indexOf('\0') >= 0 || a.indexOf('\n') >= 0) {
                throw DockerException.bad("Each command argument is one line of at most " + MAX_ARG_LENGTH + " characters.");
            }
            b.append(' ').append(q(a));
        }
        String command = b.append(" 2>&1").toString();
        String display = command.substring(0, command.length() - " 2>&1".length());
        List<String> keys = rows(s.env(), "environment").stream().map(Env::key).toList();
        if (!keys.isEmpty()) {
            display += "\n  environment (sent privately, not on the command line): " + String.join(", ", keys);
        }
        return new Run(command, display, envFile.length() == 0 ? null : envFile.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                List.copyOf(warnings));
    }

    private static <T> List<T> rows(List<T> list, String what) {
        if (list == null) {
            return List.of();
        }
        if (list.size() > MAX_ROWS) {
            throw DockerException.bad("At most " + MAX_ROWS + " " + what + " rows.");
        }
        return list.stream().filter(x -> x != null && !(x instanceof String str && str.isBlank())
                && !(x instanceof Volume v && blank(v.source()) && blank(v.target()))
                && !(x instanceof Env e && blank(e.key()) && blank(e.value()))).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean portOk(String p) {
        int n = Integer.parseInt(p);
        return n >= 1 && n <= 65535;
    }

    private static boolean ipOk(String ip) {
        for (String part : ip.split("\\.")) {
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
