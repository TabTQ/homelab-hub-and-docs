package com.homelabhub.internal.docker;

import com.homelabhub.internal.actions.ConfirmationTokens;
import com.homelabhub.internal.config.DashboardConfig.DockerHostSpec;
import com.homelabhub.internal.docker.DockerCommands.ContainerOp;
import com.homelabhub.internal.docker.DockerCommands.StackOp;
import com.homelabhub.internal.docker.DockerParser.Container;
import com.homelabhub.internal.terminal.SshConnector;
import com.homelabhub.internal.terminal.TerminalException;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Everything the Docker page does on a host, as docker CLI commands over SSH (docs/design-history/docker-feature-plan.md). Reads are
 * plain calls; anything that changes a host is a two-step prepare/confirm, audited, and long ones run as jobs.
 */
@Service
class DockerService {

    static final int MAX_OUTPUT = 4 * 1024 * 1024;
    static final int MAX_STACK_FILE = 256 * 1024;
    static final int KEEP_VERSIONS = 20;
    static final int MAX_FOLLOWERS = 4;

    private final DockerFeature feature;
    private final DockerSessionPool pool;
    private final SshConnector connector;
    private final DockerJobs jobs;
    private final DockerAudit audit;
    private final ConfirmationTokens tokens;
    private final JdbcClient jdbc;
    private final Map<String, Op> pending = new ConcurrentHashMap<>();
    private final AtomicInteger followers = new AtomicInteger();

    DockerService(DockerFeature feature, DockerSessionPool pool, SshConnector connector, DockerJobs jobs, DockerAudit audit,
                  ConfirmationTokens tokens, JdbcClient jdbc) {
        this.feature = feature;
        this.pool = pool;
        this.connector = connector;
        this.jobs = jobs;
        this.audit = audit;
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    // ---- hosts and running commands -------------------------------------------------------------------------------

    DockerHostSpec host(String id) {
        if (!feature.enabled()) {
            throw new DockerException(HttpStatus.SERVICE_UNAVAILABLE, "disabled", "The Docker page is turned off on this hub (HUB_DOCKER_ENABLED).");
        }
        return feature.host(id).orElseThrow(() -> new DockerException(HttpStatus.NOT_FOUND, "unknown_host",
                "There is no Docker host with that id in dashboard.yaml."));
    }

    long timeoutMillis() {
        return feature.props().commandTimeoutSeconds() * 1000L;
    }

    /** One command on the host's shared session; a dropped session is re-opened once. Fails with the host's own error text mapped. */
    SshExec.Result exec(DockerHostSpec h, String command, byte[] stdin, long timeoutMillis) {
        for (int attempt = 0; ; attempt++) {
            Session s;
            try {
                s = pool.get(h.ssh());
            } catch (TerminalException e) {
                throw fromSsh(e);
            }
            try {
                return SshExec.run(s, command, stdin, timeoutMillis, MAX_OUTPUT, null, null);
            } catch (JSchException e) {
                // The channel could not be opened (typically a pooled session that went stale), so the command never ran:
                // safe to retry once on a fresh session.
                pool.evict(h.id());
                if (attempt == 0) {
                    continue;
                }
                throw new DockerException(HttpStatus.BAD_GATEWAY, "dropped", "The SSH connection to " + h.label() + " dropped.");
            } catch (IOException e) {
                // The command may already have run: never repeat it.
                pool.evict(h.id());
                throw new DockerException(HttpStatus.BAD_GATEWAY, "dropped", "The SSH connection to " + h.label()
                        + " dropped while the command ran. Check the result before trying again.");
            } catch (SshExec.Timeout e) {
                throw new DockerException(HttpStatus.GATEWAY_TIMEOUT, "timeout", h.label() + " did not answer in time.");
            }
        }
    }

    /** Like exec, but a non-zero exit is turned into a clear error. */
    String run(DockerHostSpec h, String command) {
        return run(h, command, null);
    }

    String run(DockerHostSpec h, String command, byte[] stdin) {
        SshExec.Result r = exec(h, command, stdin, timeoutMillis());
        if (!r.ok()) {
            throw failure(h, r);
        }
        return r.out();
    }

    static DockerException fromSsh(TerminalException e) {
        return new DockerException(HttpStatus.BAD_GATEWAY, e.code().name(), e.getMessage());
    }

    /** The host's error text, recognised where it is a known setup problem. */
    static DockerException failure(DockerHostSpec h, SshExec.Result r) {
        String text = r.all();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (r.exit() == 127 || lower.contains("docker: not found") || lower.contains("docker: command not found")) {
            return new DockerException(HttpStatus.BAD_GATEWAY, "docker_missing", "The docker command is not installed (or not on the PATH) for user '"
                    + h.user() + "' on " + h.label() + ".");
        }
        if (lower.contains("permission denied") && lower.contains("docker")) {
            return new DockerException(HttpStatus.BAD_GATEWAY, "docker_permission", "User '" + h.user() + "' may not use Docker on " + h.label()
                    + ". Add it to the docker group (sudo usermod -aG docker " + h.user() + ").");
        }
        if (lower.contains("cannot connect to the docker daemon") || lower.contains("is the docker daemon running")) {
            return new DockerException(HttpStatus.BAD_GATEWAY, "docker_down", "Docker is not running on " + h.label() + ".");
        }
        if (lower.contains("'compose' is not a docker command") || lower.contains("unknown shorthand flag: 'p' in -p")) {
            return new DockerException(HttpStatus.BAD_GATEWAY, "compose_missing", "Docker Compose v2 (the 'docker compose' plugin) is not installed on "
                    + h.label() + ".");
        }
        if (lower.contains("no such container")) {
            return new DockerException(HttpStatus.NOT_FOUND, "no_container", "That container no longer exists on " + h.label() + ".");
        }
        return new DockerException(HttpStatus.BAD_GATEWAY, "docker_failed", firstLines(text, 6).isBlank()
                ? "The command failed on " + h.label() + " (exit " + r.exit() + ")." : firstLines(text, 6));
    }

    static String firstLines(String text, int n) {
        List<String> lines = text.strip().lines().limit(n).toList();
        String s = String.join("\n", lines);
        return s.length() > 1200 ? s.substring(0, 1200) + "..." : s;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    List<Container> containers(DockerHostSpec h) {
        return DockerParser.containers(run(h, DockerCommands.PS));
    }

    String protectedReason(DockerHostSpec h, Container c) {
        if (c.self()) {
            return "This is the hub itself.";
        }
        if (c.name() != null && h.protect().contains(c.name())) {
            return "Protected in dashboard.yaml (protect).";
        }
        return null;
    }

    static boolean managedStack(Container c) {
        return c.project() != null && c.workdir() != null && c.workdir().endsWith("/stacks/" + c.project());
    }

    Map<String, Object> view(DockerHostSpec h, Container c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("image", c.image());
        m.put("state", c.state());
        m.put("status", c.status());
        m.put("health", c.health());
        m.put("ports", c.ports());
        m.put("created", c.created());
        m.put("runningFor", c.runningFor());
        m.put("project", c.project());
        m.put("service", c.service());
        m.put("stack", managedStack(c) ? c.project() : null);
        String reason = protectedReason(h, c);
        m.put("protected", reason != null);
        m.put("protectedReason", reason);
        m.put("canUpdate", reason == null && c.project() != null && c.service() != null && c.workdir() != null);
        m.put("canRemove", reason == null && c.project() == null && !"running".equals(c.state()) && !"restarting".equals(c.state()));
        return m;
    }

    Container container(DockerHostSpec h, String id) {
        DockerCommands.containerId(id);
        return containers(h).stream().filter(c -> id.equals(c.id())).findFirst()
                .orElseThrow(() -> new DockerException(HttpStatus.NOT_FOUND, "no_container", "That container no longer exists on " + h.label() + "."));
    }

    List<DockerParser.Stats> stats(DockerHostSpec h) {
        return DockerParser.stats(run(h, DockerCommands.STATS));
    }

    List<String> networks(DockerHostSpec h) {
        return DockerParser.names(run(h, DockerCommands.NETWORKS), DockerCommands.NETWORK);
    }

    Map<String, Object> logs(DockerHostSpec h, String id, int tail) {
        int n = Math.max(1, Math.min(tail, feature.props().maxLogLines()));
        container(h, id);
        SshExec.Result r = exec(h, DockerCommands.logs(id, n), null, timeoutMillis());
        if (!r.ok()) {
            throw failure(h, r);
        }
        List<String> lines = r.out().lines().toList();
        if (lines.size() > n) {
            lines = lines.subList(lines.size() - n, lines.size());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lines", lines);
        m.put("truncated", r.truncated());
        return m;
    }

    // ---- stacks ----------------------------------------------------------------------------------------------------

    List<String> stackNames(DockerHostSpec h) {
        return DockerParser.names(run(h, DockerCommands.STACKS), DockerCommands.STACK);
    }

    /** Stacks the hub manages (folders in ~/stacks) and compose projects started elsewhere, with container counts. */
    List<Map<String, Object>> stacks(DockerHostSpec h) {
        List<String> names = stackNames(h);
        List<Container> cs = containers(h);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String n : names) {
            out.put(n, stackRow(n, true, null));
        }
        for (Container c : cs) {
            if (c.project() == null) {
                continue;
            }
            Map<String, Object> row = out.computeIfAbsent(c.project(), p -> stackRow(p, false, c.workdir()));
            row.put("containers", (Integer) row.get("containers") + 1);
            if ("running".equals(c.state())) {
                row.put("running", (Integer) row.get("running") + 1);
            }
        }
        List<Map<String, Object>> list = new ArrayList<>(out.values());
        list.sort((a, b) -> {
            int m = Boolean.compare((Boolean) b.get("managed"), (Boolean) a.get("managed"));
            return m != 0 ? m : ((String) a.get("name")).compareTo((String) b.get("name"));
        });
        return list;
    }

    private static Map<String, Object> stackRow(String name, boolean managed, String workdir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("managed", managed);
        m.put("workdir", managed ? null : workdir);
        m.put("containers", 0);
        m.put("running", 0);
        return m;
    }

    Map<String, Object> readStack(DockerHostSpec h, String name) {
        DockerCommands.stackName(name);
        if (!stackNames(h).contains(name)) {
            throw new DockerException(HttpStatus.NOT_FOUND, "no_stack", "There is no stack '" + name + "' on " + h.label() + ".");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        SshExec.Result compose = exec(h, DockerCommands.readStackFile(name, DockerCommands.COMPOSE_FILE), null, timeoutMillis());
        m.put("compose", compose.ok() ? compose.out() : "");
        m.put("env", run(h, DockerCommands.readStackFile(name, DockerCommands.ENV_FILE)));
        return m;
    }

    /**
     * Writes both files (contents through stdin), lets compose validate them, and only then replaces the current ones.
     * The previous compose.yaml is kept in the hub's history.
     */
    Map<String, Object> saveStack(DockerHostSpec h, String name, String compose, String env, boolean create, String ip, String ua) {
        DockerCommands.stackName(name);
        String c = compose == null ? "" : compose;
        String e = env == null ? "" : env;
        if (c.isBlank()) {
            throw DockerException.bad("The compose file is empty.");
        }
        if (c.length() > MAX_STACK_FILE || e.length() > MAX_STACK_FILE) {
            throw DockerException.bad("A stack file may be at most 256 KiB.");
        }
        if (c.indexOf('\0') >= 0 || e.indexOf('\0') >= 0) {
            throw DockerException.bad("The files must be text.");
        }
        List<String> existing = stackNames(h);
        if (create && existing.contains(name)) {
            throw new DockerException(HttpStatus.CONFLICT, "exists", "A stack named '" + name + "' already exists on " + h.label() + ".");
        }
        if (!create && !existing.contains(name)) {
            throw new DockerException(HttpStatus.NOT_FOUND, "no_stack", "There is no stack '" + name + "' on " + h.label() + ".");
        }
        String previous = null;
        if (!create) {
            SshExec.Result old = exec(h, DockerCommands.readStackFile(name, DockerCommands.COMPOSE_FILE), null, timeoutMillis());
            previous = old.ok() ? old.out() : null;
        }
        run(h, DockerCommands.writeStackFile(name, DockerCommands.COMPOSE_FILE), c.getBytes(StandardCharsets.UTF_8));
        run(h, DockerCommands.writeStackFile(name, DockerCommands.ENV_FILE), e.getBytes(StandardCharsets.UTF_8));
        SshExec.Result r = exec(h, DockerCommands.commitStack(name), null, timeoutMillis());
        if (r.exit() == 3) {
            if (create) {
                exec(h, "rmdir " + DockerCommands.stackDir(name) + " 2>/dev/null; true", null, timeoutMillis());
            }
            audit.record(h.id(), "stack:" + name, create ? "create" : "save", DockerAudit.Result.failed, "invalid compose file", ip, ua);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("message", "Compose refused the file. Nothing was changed.");
            m.put("detail", firstLines(r.all(), 20));
            return m;
        }
        if (!r.ok()) {
            throw failure(h, r);
        }
        if (previous != null && !previous.equals(c)) {
            keepVersion(h.id(), name, previous);
        }
        audit.record(h.id(), "stack:" + name, create ? "create" : "save", DockerAudit.Result.ok, null, ip, ua);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", create ? "Stack '" + name + "' created. Deploy it to start its containers." : "Saved. Deploy to apply the changes.");
        return m;
    }

    void keepVersion(String hostId, String stack, String content) {
        jdbc.sql("INSERT INTO docker_stack_version (host_id, stack, content) VALUES (?,?,?)").params(hostId, stack, content).update();
        jdbc.sql("DELETE FROM docker_stack_version WHERE host_id = ? AND stack = ? AND id NOT IN "
                        + "(SELECT id FROM docker_stack_version WHERE host_id = ? AND stack = ? ORDER BY id DESC LIMIT ?)")
                .params(hostId, stack, hostId, stack, KEEP_VERSIONS).update();
    }

    List<Map<String, Object>> versions(String hostId, String stack) {
        DockerCommands.stackName(stack);
        return jdbc.sql("SELECT id, ts, content FROM docker_stack_version WHERE host_id = ? AND stack = ? ORDER BY id DESC")
                .params(hostId, stack)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("ts", rs.getString(2));
                    m.put("content", rs.getString(3));
                    return m;
                }).list();
    }

    // ---- changes: prepare and confirm ------------------------------------------------------------------------------

    /** What a confirmed token will do. Built (and checked) at prepare time, so the confirm runs exactly what was shown. */
    record Op(String hostId, String target, String action, String title, String detail, String word, String command, byte[] stdin,
              boolean job, String doneMessage, Instant expires, List<Step> steps) {

        Op(String hostId, String target, String action, String title, String detail, String word, String command, byte[] stdin,
           boolean job, String doneMessage, Instant expires) {
            this(hostId, target, action, title, detail, word, command, stdin, job, doneMessage, expires, null);
        }
    }

    /** One command of a multi-step job (clean up), audited on its own so a partial failure is traceable. */
    record Step(String target, String action, String command) {}

    Map<String, Object> prepareContainer(DockerHostSpec h, String id, String opName, String sessionKey) {
        Container c = container(h, id);
        String reason = protectedReason(h, c);
        if ("update".equals(opName)) {
            if (reason != null) {
                throw new DockerException(HttpStatus.CONFLICT, "protected", c.name() + " cannot be changed from here. " + reason);
            }
            if (c.project() == null || c.service() == null || c.workdir() == null) {
                throw new DockerException(HttpStatus.CONFLICT, "not_compose", c.name() + " was not started by docker compose, so the hub "
                        + "cannot recreate it with a new image.");
            }
            String cmd = DockerCommands.update(c.project(), c.service(), c.workdir(), c.configFiles());
            return prepare(new Op(h.id(), c.name(), "update", "Update " + c.name() + " on " + h.label(),
                    "Pulls the newest image for service '" + c.service() + "' of compose project '" + c.project()
                            + "' and recreates the container if the image changed.\n\n$ " + cmd.replace(" 2>&1", ""),
                    c.name(), cmd, null, true, "Updated " + c.name() + ".", null), sessionKey);
        }
        ContainerOp op;
        try {
            op = ContainerOp.valueOf(opName);
        } catch (IllegalArgumentException e) {
            throw new DockerException(HttpStatus.NOT_FOUND, "unknown_action", "Unknown container action.");
        }
        if (reason != null && op != ContainerOp.start) {
            throw new DockerException(HttpStatus.CONFLICT, "protected", c.name() + " cannot be stopped or changed from here. " + reason);
        }
        if (op == ContainerOp.remove && (c.project() != null || "running".equals(c.state()) || "restarting".equals(c.state()))) {
            throw new DockerException(HttpStatus.CONFLICT, "cannot_remove", c.project() != null
                    ? c.name() + " belongs to compose project '" + c.project() + "'. Remove it through its stack."
                    : "Stop " + c.name() + " before removing it.");
        }
        String verb = switch (op) {
            case start -> "Start";
            case stop -> "Stop";
            case restart -> "Restart";
            case remove -> "Remove";
        };
        String detail = switch (op) {
            case start -> "Starts the container.";
            case stop -> "Stops the container. Anything it serves goes offline until it is started again.";
            case restart -> "Stops and starts the container. It is briefly offline.";
            case remove -> "Deletes the stopped container. Its named volumes and image stay; anything stored only inside the container is lost.";
        };
        boolean typed = op == ContainerOp.stop || op == ContainerOp.remove;
        String cmd = DockerCommands.container(op, c.id());
        return prepare(new Op(h.id(), c.name(), op.name(), verb + " " + c.name() + " on " + h.label(), detail, typed ? c.name() : null,
                cmd, null, false, verb + (op == ContainerOp.stop ? "ped " : op == ContainerOp.remove ? "d " : "ed ") + c.name() + ".", null),
                sessionKey);
    }

    Map<String, Object> prepareStack(DockerHostSpec h, String name, String opName, boolean volumes, String sessionKey) {
        DockerCommands.stackName(name);
        StackOp op;
        try {
            op = StackOp.valueOf(opName);
        } catch (IllegalArgumentException e) {
            throw new DockerException(HttpStatus.NOT_FOUND, "unknown_action", "Unknown stack action.");
        }
        if (!stackNames(h).contains(name)) {
            throw new DockerException(HttpStatus.NOT_FOUND, "no_stack", "There is no stack '" + name + "' on " + h.label() + ".");
        }
        boolean withVolumes = volumes && (op == StackOp.down || op == StackOp.delete);
        String cmd = DockerCommands.stack(op, name, withVolumes);
        String title;
        String detail;
        switch (op) {
            case deploy -> {
                title = "Deploy " + name;
                detail = "Creates or updates the stack's containers to match compose.yaml (docker compose up -d).";
            }
            case pull -> {
                title = "Pull and redeploy " + name;
                detail = "Pulls newer images for every service, then recreates the containers whose image changed.";
            }
            case stop -> {
                title = "Stop " + name;
                detail = "Stops every container of the stack. They stay in place and can be started again with Deploy.";
            }
            case down -> {
                title = "Take down " + name;
                detail = "Stops and removes the stack's containers and networks. The stack's files stay, so Deploy brings it back."
                        + (withVolumes ? "\n\nALSO DELETES THE STACK'S NAMED VOLUMES: their data is gone for good." : "");
            }
            default -> {
                title = "Delete " + name;
                detail = "Takes the stack down and deletes its folder (compose.yaml and .env) from the host."
                        + (withVolumes ? "\n\nALSO DELETES THE STACK'S NAMED VOLUMES: their data is gone for good." : " Named volumes are kept.");
            }
        }
        boolean typed = op == StackOp.down || op == StackOp.delete || op == StackOp.stop;
        return prepare(new Op(h.id(), "stack:" + name, op.name() + (withVolumes ? "+volumes" : ""), title + " on " + h.label(),
                detail + "\n\n$ " + cmd.replace(" 2>&1", ""), typed ? name : null, cmd, null, true, title + ": done.", null), sessionKey);
    }

    Map<String, Object> prepareRun(DockerHostSpec h, DockerCommands.RunSpec spec, String sessionKey) {
        DockerCommands.Run r = DockerCommands.run(spec);
        Map<String, Object> m = prepare(new Op(h.id(), spec.name() == null || spec.name().isBlank() ? spec.image() : spec.name(), "run",
                "Run " + spec.image().strip() + " on " + h.label(), "Pulls the image if needed and starts a new container.\n\n$ " + r.display(),
                null, r.command(), r.stdin(), true, "Container started.", null), sessionKey);
        m.put("warnings", r.warnings());
        return m;
    }

    private Map<String, Object> prepare(Op op, String sessionKey) {
        Instant now = Instant.now();
        pending.values().removeIf(o -> o.expires().isBefore(now));
        if (pending.size() > 100) {
            throw new DockerException(HttpStatus.TOO_MANY_REQUESTS, "busy", "Too many confirmations are open. Try again shortly.");
        }
        String token = tokens.issue("docker:" + op.hostId(), sessionKey, op.action(), op.word());
        if (token == null) {
            throw new DockerException(HttpStatus.TOO_MANY_REQUESTS, "busy", "Too many confirmations are open. Try again shortly.");
        }
        Instant expires = now.plus(ConfirmationTokens.TTL);
        pending.put(token, new Op(op.hostId(), op.target(), op.action(), op.title(), op.detail(), op.word(), op.command(), op.stdin(),
                op.job(), op.doneMessage(), expires, op.steps()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("expiresAt", expires.toString());
        m.put("title", op.title());
        m.put("detail", op.detail());
        m.put("confirm", op.word() == null ? "click" : "type");
        m.put("word", op.word());
        m.put("warnings", List.of());
        return m;
    }

    Map<String, Object> confirm(String token, String typed, String sessionKey, String ip, String ua) {
        ConfirmationTokens.Result r = tokens.consume(token, sessionKey, typed);
        switch (r.outcome()) {
            case OK -> { }
            case WRONG_ANSWER -> throw new DockerException(HttpStatus.UNPROCESSABLE_ENTITY, "typed_mismatch", "That is not the name shown. Type it exactly, or cancel.");
            case TOO_MANY_WRONG -> {
                pending.remove(token);
                throw new DockerException(HttpStatus.TOO_MANY_REQUESTS, "too_many_wrong", "Too many wrong answers. Start again.");
            }
            case WRONG_SESSION -> throw new DockerException(HttpStatus.FORBIDDEN, "forbidden", "This confirmation belongs to another session.");
            default -> {
                pending.remove(token);
                throw new DockerException(HttpStatus.GONE, "token_expired", "This confirmation expired. Start again.");
            }
        }
        Op op = pending.remove(token);
        if (op == null || !r.pending().actionId().equals("docker:" + op.hostId())) {
            throw new DockerException(HttpStatus.GONE, "token_expired", "This confirmation expired. Start again.");
        }
        DockerHostSpec h = host(op.hostId());
        Map<String, Object> m = new LinkedHashMap<>();
        if (op.job()) {
            DockerJobs.Job job = jobs.start(h.id(), op.title(), out -> op.steps() != null ? runSteps(h, op, out, ip, ua) : runJob(h, op, out),
                    j -> audit.record(h.id(), op.target(), op.action(), j.state() == DockerJobs.State.ok ? DockerAudit.Result.ok
                            : DockerAudit.Result.failed, (String) j.view().get("message"), ip, ua));
            audit.record(h.id(), op.target(), op.action(), DockerAudit.Result.started, "job=" + job.id, ip, ua);
            m.put("ok", true);
            m.put("jobId", job.id);
            m.put("message", op.title() + ": started.");
            return m;
        }
        try {
            SshExec.Result res = exec(h, op.command(), op.stdin(), Math.max(timeoutMillis(), 90_000));
            if (!res.ok()) {
                throw failure(h, res);
            }
            audit.record(h.id(), op.target(), op.action(), DockerAudit.Result.ok, null, ip, ua);
            m.put("ok", true);
            m.put("message", op.doneMessage());
            return m;
        } catch (DockerException e) {
            audit.record(h.id(), op.target(), op.action(), DockerAudit.Result.failed, e.getMessage(), ip, ua);
            throw e;
        }
    }

    /** Jobs get their own SSH session, so the pool's idle sweep can never cut a ten-minute pull short. */
    private String runJob(DockerHostSpec h, Op op, Consumer<String> out) throws Exception {
        Session s;
        try {
            s = connector.connect(h.ssh());
        } catch (TerminalException e) {
            throw fromSsh(e);
        }
        try {
            out.accept("$ " + op.command().replace(" 2>&1", "") + "\n");
            SshExec.Result r = SshExec.run(s, op.command(), op.stdin(), feature.props().jobTimeoutSeconds() * 1000L, MAX_OUTPUT, out, null);
            if (!r.ok()) {
                DockerException f = failure(h, r);
                throw new DockerException(f.status(), f.code(), f.code().equals("docker_failed")
                        ? "Failed (exit " + r.exit() + "). See the output above." : f.getMessage());
            }
            return op.doneMessage();
        } catch (SshExec.Timeout e) {
            throw new DockerException(HttpStatus.GATEWAY_TIMEOUT, "timeout", "Stopped waiting after " + feature.props().jobTimeoutSeconds()
                    + " s. The command may still be running on the host.");
        } catch (JSchException | IOException e) {
            throw new DockerException(HttpStatus.BAD_GATEWAY, "dropped", "The SSH connection to " + h.label() + " dropped.");
        } finally {
            s.disconnect();
        }
    }

    /** Runs each step on one SSH session, carrying on past a failed one; every step is audited, the job fails if any did. */
    private String runSteps(DockerHostSpec h, Op op, Consumer<String> out, String ip, String ua) throws Exception {
        Session s;
        try {
            s = connector.connect(h.ssh());
        } catch (TerminalException e) {
            throw fromSsh(e);
        }
        int done = 0;
        List<String> failed = new ArrayList<>();
        try {
            for (Step step : op.steps()) {
                out.accept("$ " + step.command().replace(" 2>&1", "") + "\n");
                String detail;
                boolean ok;
                try {
                    SshExec.Result r = SshExec.run(s, step.command(), null, timeoutMillis() * 4, MAX_OUTPUT, out, null);
                    ok = r.ok();
                    detail = ok ? null : firstLines(r.all(), 3);
                } catch (SshExec.Timeout e) {
                    ok = false;
                    detail = "timed out";
                } catch (JSchException | IOException e) {
                    audit.record(h.id(), step.target(), step.action(), DockerAudit.Result.failed, "connection dropped", ip, ua);
                    throw new DockerException(HttpStatus.BAD_GATEWAY, "dropped", "The SSH connection to " + h.label() + " dropped after "
                            + done + " of " + op.steps().size() + " removals.");
                }
                audit.record(h.id(), step.target(), step.action(), ok ? DockerAudit.Result.ok : DockerAudit.Result.failed, detail, ip, ua);
                if (ok) {
                    done++;
                } else {
                    failed.add(step.target());
                    out.accept("  (failed, carrying on)\n");
                }
            }
        } finally {
            s.disconnect();
        }
        String msg = "Removed " + done + " of " + op.steps().size() + ".";
        if (!failed.isEmpty()) {
            throw new DockerException(HttpStatus.BAD_GATEWAY, "partial", msg + " Not removed: " + String.join(", ", failed) + ". See the output above.");
        }
        return msg;
    }

    // ---- clean up --------------------------------------------------------------------------------------------------

    /** More items than this need the word typed, like other changes that touch many things at once. */
    static final int CLEANUP_TYPED_OVER = 10;
    static final int MAX_CLEANUP = 200;

    record CleanupPreview(List<DockerParser.Image> images, List<DockerParser.Network> networks) {}

    /** A dry run: the images no container uses (running or stopped) and the networks nothing is attached to. */
    CleanupPreview cleanupPreview(DockerHostSpec h) {
        List<DockerParser.Image> all = DockerParser.images(run(h, DockerCommands.IMAGES));
        java.util.Set<String> used = DockerParser.imageIds(run(h, DockerCommands.CONTAINER_IMAGES));
        List<DockerParser.Image> unused = all.stream().filter(i -> !used.contains(i.id())).toList();
        return new CleanupPreview(unused, DockerParser.networks(run(h, DockerCommands.DANGLING_NETWORKS)));
    }

    /** Only what a fresh preview still offers can be removed, so nothing that came into use since the dialog opened is touched. */
    Map<String, Object> prepareCleanup(DockerHostSpec h, List<String> imageIds, List<String> networkNames, String sessionKey) {
        List<String> ids = imageIds == null ? List.of() : imageIds.stream().distinct().toList();
        List<String> nets = networkNames == null ? List.of() : networkNames.stream().distinct().toList();
        if (ids.isEmpty() && nets.isEmpty()) {
            throw DockerException.bad("Choose at least one image or network to remove.");
        }
        if (ids.size() + nets.size() > MAX_CLEANUP) {
            throw DockerException.bad("At most " + MAX_CLEANUP + " items at a time.");
        }
        ids.forEach(DockerCommands::imageId);
        CleanupPreview p = cleanupPreview(h);
        Map<String, DockerParser.Image> offered = new LinkedHashMap<>();
        p.images().forEach(i -> offered.put(i.id(), i));
        java.util.Set<String> offeredNets = new java.util.HashSet<>();
        p.networks().forEach(n -> offeredNets.add(n.name()));
        List<String> gone = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        StringBuilder detail = new StringBuilder("Removes these, one at a time. Docker refuses anything a container uses, and the job carries on past a refusal.\n");
        for (String id : ids) {
            DockerParser.Image img = offered.get(id.startsWith("sha256:") ? id : "sha256:" + id);
            if (img == null) {
                gone.add(id.replace("sha256:", "").substring(0, Math.min(12, id.replace("sha256:", "").length())));
                continue;
            }
            String cmd = DockerCommands.removeImage(img.id(), img.tags());
            String label = img.tags().isEmpty() ? img.id().substring(7, 19) : String.join(", ", img.tags());
            steps.add(new Step("image:" + label, "cleanup:image", cmd));
            detail.append("\n$ ").append(cmd.replace(" 2>&1", "")).append(img.size() == null ? "" : "   # " + img.size());
        }
        for (String n : nets) {
            if (!offeredNets.contains(n)) {
                gone.add(DockerCommands.clip(n));
                continue;
            }
            String cmd = DockerCommands.removeNetwork(n);
            steps.add(new Step("network:" + n, "cleanup:network", cmd));
            detail.append("\n$ ").append(cmd.replace(" 2>&1", ""));
        }
        if (!gone.isEmpty()) {
            throw new DockerException(HttpStatus.CONFLICT, "no_longer_unused", "No longer unused (or gone) on " + h.label() + ": "
                    + String.join(", ", gone) + ". Open Clean up again for a fresh list.");
        }
        int n = steps.size();
        String title = "Clean up " + h.label() + " (" + n + " item" + (n == 1 ? "" : "s") + ")";
        return prepare(new Op(h.id(), "cleanup", "cleanup", title, detail.toString(), n > CLEANUP_TYPED_OVER ? "cleanup" : null,
                "cleanup", null, true, "Removed " + n + " of " + n + ".", null, List.copyOf(steps)), sessionKey);
    }

    Optional<DockerJobs.Job> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    // ---- live logs -------------------------------------------------------------------------------------------------

    interface LineSink {
        /** @return false when the receiver is gone */
        boolean send(List<String> lines);
    }

    boolean tryStartFollow() {
        while (true) {
            int n = followers.get();
            if (n >= MAX_FOLLOWERS) {
                return false;
            }
            if (followers.compareAndSet(n, n + 1)) {
                return true;
            }
        }
    }

    /**
     * Streams new log lines until the receiver is gone, {@code stop} says so, or the maximum time passes. Runs on its own
     * SSH session with a PTY, so closing it also ends `docker logs -f` on the host.
     */
    void follow(DockerHostSpec h, String id, LineSink sink, BooleanSupplier stop) {
        Session s = null;
        ChannelExec ch = null;
        try {
            s = connector.connect(h.ssh());
            ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand(DockerCommands.follow(id));
            ch.setPty(true);
            ch.setAgentForwarding(false);
            var in = ch.getInputStream();
            ch.connect(10_000);
            long deadline = System.currentTimeMillis() + feature.props().logFollowMaxSeconds() * 1000L;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> batch = new ArrayList<>();
            long lastFlush = System.currentTimeMillis();
            while (!stop.getAsBoolean() && System.currentTimeMillis() < deadline) {
                if (in.available() > 0 || reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    batch.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                } else if (ch.isClosed()) {
                    break;
                } else {
                    Thread.sleep(50);
                }
                long now = System.currentTimeMillis();
                if (!batch.isEmpty() && (batch.size() >= 200 || now - lastFlush > 250)) {
                    if (!sink.send(List.copyOf(batch))) {
                        return;
                    }
                    batch.clear();
                    lastFlush = now;
                } else if (batch.isEmpty() && now - lastFlush > 15_000) {
                    if (!sink.send(List.of())) { // keep-alive; also notices a closed browser
                        return;
                    }
                    lastFlush = now;
                }
            }
            if (!batch.isEmpty()) {
                sink.send(List.copyOf(batch));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TerminalException | JSchException | IOException e) {
            // the stream just ends; the page shows that following stopped
        } finally {
            if (ch != null) {
                ch.disconnect();
            }
            if (s != null) {
                s.disconnect();
            }
            followers.decrementAndGet();
        }
    }

    // ---- audit -----------------------------------------------------------------------------------------------------

    List<DockerAudit.Row> recentAudit(int limit) {
        return audit.recent(limit);
    }

    List<Map<String, Object>> recentJobs() {
        return jobs.recent();
    }
}
