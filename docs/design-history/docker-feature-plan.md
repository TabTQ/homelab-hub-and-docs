# Docker page for Homelab Hub: plan and implementation notes

> Created: 2026-10-02 · Status: built on branch `ccr-b05a2855-8wk87g`, tested against a fake docker CLI; not yet deployed or run against real Docker


## Context
The owner wants one page in `hub-internal` to see and manage Docker containers on the Pi, and to add more Docker hosts (Proxmox LXCs/VMs running Docker) just by editing config. It is LAN/Tailscale only and used only by the owner.

What the owner chose:
- **SSH transport**, reusing the Terminal's SSH layer: the same JSch library, pinned host key and key file kept in `<data>/ssh/`.
- **Hosts declared in `dashboard.yaml`**, so you edit them in the Config page with validation, diff and history.
- **Scope:** list and status; start, stop and restart; logs (tail and live follow); live CPU/memory; pull and recreate for compose services.
- **Creating containers:** both compose stacks (edit, deploy and keep versions in the browser) and a quick `docker run` form. Also a remove action for stopped containers.

The Terminal feature (`docs/terminal-feature-plan.md`, package `terminal/`) is the template for this work. It already gives us pinned-key SSH, `terminals:` config parsing, a feature flag that only shows the nav entry when enabled, an audit table and an embedded SSH test server.

## Approach

### How the hub talks to Docker
The hub opens an SSH **exec** channel (not a shell) to each host and runs the **docker CLI** with JSON output. Only commands the hub itself builds can run:
- A `DockerCommands` builder has a fixed set of verbs: `ps`, `stats`, `logs`, `start`, `stop`, `restart` and `compose pull/up`. Each argument is checked against a strict regex (container id `^[a-f0-9]{12,64}$`, compose project and service `^[a-z0-9][a-z0-9_-]*$`, compose dir `^/[A-Za-z0-9._/-]+$` with no `..`). Each argument is then single-quoted. No text typed in the browser ever reaches a command line.
- The container list is one fixed command: `docker ps -a --no-trunc --format '{"id":{{json .ID}},"name":{{json .Names}},"image":{{json .Image}},"state":{{json .State}},"status":{{json .Status}},"ports":{{json .Ports}},"created":{{json .CreatedAt}},"project":{{json (.Label "com.docker.compose.project")}},"service":{{json (.Label "com.docker.compose.service")}},"workdir":{{json (.Label "com.docker.compose.project.working_dir")}},"self":{{json (.Label "com.homelabhub.self")}}}'`. Health comes from `status` ("(healthy)" / "(unhealthy)").
- Stats come from `docker stats --no-stream --format '{{json .}}'`: one call per host, about 2 s.
- Logs: `docker logs --tail N --timestamps <id>`. Live follow uses `docker logs -f --tail 0 --timestamps <id>` on a long-lived exec channel.
- Update works for **compose-managed containers only**. It runs `docker compose -p <project> --project-directory <workdir> pull --ignore-buildable <service>` and then `up -d --no-deps <service>`. Containers that are not compose-managed get no Update button.

**Remote side, per host:** a dedicated user `hubdocker` in the `docker` group (this is root-equivalent, which the owner accepted when choosing SSH). It uses its own ed25519 key, separate from `hubshell`. Its `authorized_keys` line starts with `restrict` (no PTY, no forwarding). The README documents these steps for the Pi and for a Proxmox LXC (install sshd, add the user, paste the key, run `terminal-hostkey` for the fingerprint).

### Refactor: shared SSH layer
- Move the session-opening code out of `terminal/SshConnector.java` into a new `ssh/` package:
  - `SshTarget` record (host, port, user, key, hostKey)
  - `SshSessions.open(SshTarget)`, which keeps the pinned host-key check, public-key-only auth and the `translate()` error mapping
  - `KeyStore` and `HostKeyFingerprint` (moved there)
- `SshConnector` then calls it, with no change in behaviour, and the existing terminal tests must stay green.
- Add `SshExec.run(session, command, timeout, maxBytes)`: exit code, stdout and stderr, capped at 4 MiB with a timeout. Add `SshExec.stream(...)` for log follow.
- `DockerSessionPool`: one cached SSH session per host, reused across calls and closed after 60 s idle. It creates its scheduler lazily and shuts it down when empty, like `TerminalSessionManager`, so nothing runs while the page is closed. It closes all sessions on `@PreDestroy`.

### Config (`config/ConfigParser.java`, `DashboardConfig.java`)
Add a new root key, `dockerHosts`, to `known(root, …)` at `ConfigParser.java:242`. Factor the per-entry SSH field validation out of `readTerminals` (around line 753) into a shared helper so both keys use it.
```yaml
dockerHosts:
  - id: pi                 # ^[a-z0-9][a-z0-9_-]{0,31}$, unique
    label: Raspberry Pi
    host: 127.0.0.1
    port: 22
    user: hubdocker
    key: hub_docker_ed25519        # file name inside <data>/ssh/, never a path
    hostKey: "SHA256:…"
    protect: [hub-internal, hub-public]   # optional: container names with no stop/restart/update
  - id: media-lxc
    label: Media LXC (Proxmox)
    host: 192.168.1.50
    user: hubdocker
    key: hub_docker_ed25519
    hostKey: "SHA256:…"
```
- The list holds at most 20 entries, and errors carry line numbers.
- Add a `DockerHostSpec` record to `DashboardConfig`.
- Add a commented example to `default-dashboard.yaml`.
- Containers labelled `com.homelabhub.self=true` are always protected. Add that label to `hub-internal` and `hub-public` in `deploy/docker-compose.yml`, so the page can never stop the hub itself.

### Feature flag and settings (`application.yml`, compose, `.env.example`)
`hub.docker.enabled: ${HUB_DOCKER_ENABLED:false}`, `command-timeout-seconds: 20`, `update-timeout-seconds: 600`, `log-follow-max-seconds: 1800`, `max-log-lines: 5000`.
- The feature refuses to turn on when `HUB_AUTH_MODE=none`, with an ERROR log, the same rule as Terminal.
- `DashboardController.java:46` features map: add `docker` (enabled, auth mode not `none`, and at least one host).
- `web/src/shared/types.ts:45`: add `docker: boolean`.

### Backend package `docker/`
- `DockerProperties`, `DockerFeature` (modelled on `TerminalFeature`).
- `DockerCommands` (builder and validation), `DockerParser` (JSON lines into `ContainerInfo`, `ContainerStats`), `DockerService` (per-host calls through the pool, translating errors to clear messages: unreachable, auth rejected, host key mismatch, `docker: command not found`, permission denied on `docker.sock`, no `docker compose`).
- `DockerController` under `/api/admin/docker/**`. That puts it behind login with no `SecurityConfig` change, and writes need CSRF.

| Route | Purpose |
|---|---|
| `GET /hosts` | `[{id,label,host}]` |
| `GET /hosts/{h}/containers` | list, or `{error}` for that host only |
| `GET /hosts/{h}/stats` | CPU %, memory used/limit, net and block I/O per container |
| `GET /hosts/{h}/containers/{c}/logs?tail=200` | last N lines (N ≤ 5000) |
| `GET /hosts/{h}/containers/{c}/logs/stream` | SSE live follow (pattern from `status/StatusStream.java`), ends at the max time or when the client leaves |
| `POST /hosts/{h}/containers/{c}/{start\|stop\|restart\|update}/prepare` → `/confirm` | two-step, reusing `actions/ConfirmationTokens.java` (30 s, bound to the session). Stop and update need the container name typed, matching the existing destructive-action UX |
| `GET /jobs/{id}` | progress and output of a running update (bounded executor, one update per host at a time, output capped) |
| `GET /audit?limit=20` | recent Docker actions |

- `{c}` is always resolved against a fresh `ps` of that host. Unknown ids get 404. Protected containers get 409.
- **Audit:** a new migration `V5__docker_audit.sql` adds a `docker_audit` table (ts, host_id, container, action, result, detail, client_ip, user_agent), keeping the last 1000 rows. It holds no log content. A failed audit write never fails the action.

### Frontend
- `web/src/internal/pages/DockerPage.tsx`, lazy-loaded in `App.tsx`:
  - Add `"docker"` to `Route` and `useRoute()`.
  - Add a nav entry and a palette entry only when `features.docker`, following the terminal entries at `App.tsx:80,108`.
  - Put supporting files in `web/src/internal/docker/`: `types.ts`, `format.ts` (+ tests).
  - All CSS classes use the `dk-` prefix (lesson from the `.w` collision).
- **Layout:**
  - Host chips across the top with running/total counts and a red badge on hosts that error, plus an "All hosts" view.
  - A filter box and a state filter (all, running, stopped, unhealthy).
  - Containers grouped by compose project, each row showing state dot, name, image, status/uptime, health and ports.
  - A "Live stats" toggle adds CPU and memory columns, polled every 10 s only while the toggle is on and the tab is visible.
  - The list refreshes every 15 s while the page is visible. Nothing polls when the page is closed.
- **Row actions:** Start / Stop / Restart / Logs / Update (Update shows only for compose containers). Protected rows show a lock and no destructive buttons. Actions use the existing `Modal` (`components/Modal.tsx`) and the `ActionRunner` confirm pattern. Update shows a progress view that polls `/jobs/{id}`.
- **Logs drawer** (reuse `EditorDrawer` styling):
  - choice of tail size (200, 1000 or 5000) and a Follow toggle (SSE)
  - client-side search with highlight, a wrap toggle, timestamps on/off
  - auto-scroll that pauses when you scroll up, a copy button, and a 10,000-line client buffer
- Works on phone width, in light and dark themes.

### Creating containers (both ways, as the owner chose)

**A. Compose stacks** (the main way)
- **Stacks live on each host** at `~hubdocker/stacks/<name>/compose.yaml`, with an optional `.env` next to it. The name must match `^[a-z0-9][a-z0-9_-]{0,39}$`.
- **The file on the host is the source of truth.** The hub also keeps the last 20 versions per stack in SQLite (`docker_stack_version`, in migration V5) so it can show diffs and restore an old version, the same as the Config page history.
- **How the content gets there:** the file content goes over the SSH exec channel's **stdin** (`cat > stacks/<name>/compose.yaml.new`). It never goes on the command line. Files are capped at 256 KiB.
- **Validation:** `docker compose -p <name> --project-directory stacks/<name> -f compose.yaml.new config -q` checks the new file. Only if that passes is it moved over `compose.yaml`. On failure, compose's error text is shown and the old file stays.
- **Actions:**
  - **Deploy:** `up -d --remove-orphans`, run as a job with live output through the same `/jobs/{id}`.
  - **Stop:** `stop`.
  - **Remove:** `down`, with a typed name. A separate "also delete volumes" checkbox adds `-v` and needs a second typed confirmation.
  - **Pull & redeploy.**
- **Listing:** stacks are listed from `ls stacks/` together with the `ps` project labels. Compose projects that were started outside `stacks/` (like the hub itself) show as *external*: you can view and update them, but not edit them here.
- **UI:** a "Stacks" tab on the Docker page shows a list per host and a "New stack" button. That opens an editor (CodeMirror YAML, lazy-loaded the same way the Config page loads it) with a `.env` tab, a diff before deploy, and version history with restore.

**B. Quick run form** (one-off containers)
- **Fields:**
  - image (validated image reference) and name
  - ports, as rows of `hostPort:containerPort/tcp|udp` with an optional host IP
  - volumes, as rows of an absolute host path or volume name, a container path, and a `ro` toggle
  - env, as KEY/VALUE rows (key `^[A-Za-z_][A-Za-z0-9_]*$`)
  - restart policy (`no` / `unless-stopped` / `always` / `on-failure`), a network picked from `docker network ls`, and an optional command (a list of arguments)
- **No privilege options:** the form has no `--privileged`, capabilities or devices. Use a stack for those.
- **Building the command:** env values go in through `--env-file /dev/stdin`, never on the command line. Every other argument is validated and then single-quoted. This needs the `hubdocker` login shell to be POSIX: create the user with `/bin/bash`, and the setup docs say so.
- **Confirm dialog:** shows the exact `docker run` command, with env values masked, before it runs. The image pull runs as a job with progress.
- **Warnings:** bind mounts of `/`, `/etc`, `/var/run/docker.sock` or `/root` show a warning in the dialog but are allowed, since you are the only user.
- **"Save as stack instead"** turns the form into a `compose.yaml` and opens it in the stack editor. That is the recommended route for anything you will keep.

**C. Remove container:** a new row action for stopped containers that are not compose-managed (`docker rm`, with a typed name). Compose containers are removed through their stack.

**New routes:**
- `GET|POST /hosts/{h}/stacks`
- `GET|PUT /hosts/{h}/stacks/{name}` (validate + save)
- `GET /hosts/{h}/stacks/{name}/versions`
- `POST /hosts/{h}/stacks/{name}/{deploy|stop|down|pull}/prepare|confirm`
- `POST /hosts/{h}/run/prepare|confirm`
- `GET /hosts/{h}/networks`
- `POST …/containers/{c}/remove/prepare|confirm`

All of these are audited.

### Tests and tooling
- **Java unit tests:**
  - `DockerCommandsTest`: every validator, plus injection attempts like `;`, `$( )`, quotes and `..`. Also run-form argument building (env only through stdin, port/volume/name/image validation) and stack name and path rules.
  - `StackServiceTest`: write → validate → move; a failed `config -q` keeps the old file; version history and restore.
  - `DockerParserTest`: real `ps` and `stats` output fixtures, including stopped and unhealthy containers and an empty host.
  - `ConfigParserTest` additions: `dockerHosts` errors and duplicate ids.
- **Java integration test:** `DockerApiTest` runs against the existing embedded `TestSshServer`, extended with an exec handler that answers known docker commands with fixtures. It covers list, an unreachable host, a host key mismatch, the prepare/confirm flow, protected-container refusal, the feature off, auth mode none and logs SSE.
- **Refactor safety:** the existing `terminal/*` tests must still pass after the move to `ssh/`.
- **Vitest:** formatting (bytes, percent, uptime) and grouping.
- **Playwright:** `tools/mock-ssh/mock_ssh.py` gains canned docker responses for exec requests. `web/e2e/e2e.mjs` adds a Docker page flow: list, filter, restart confirm, logs drawer, protected row, host error card.
- **Docs:** `docs/docker-feature-plan.md` (this plan) and README sections for the feature, setup per host (Pi and Proxmox LXC), API and limitations.

## Critical files
- New: `hub-internal/.../ssh/*`, `hub-internal/.../docker/*`, `db/migration/V5__docker_audit.sql`, `web/src/internal/pages/DockerPage.tsx`, `web/src/internal/docker/*`
- Modified: `terminal/SshConnector.java` (use the shared `ssh/` package), `config/ConfigParser.java`, `config/DashboardConfig.java`, `dashboard/DashboardController.java`, `application.yml`, `default-dashboard.yaml`, `deploy/docker-compose.yml`, `deploy/.env.example`, `web/src/internal/App.tsx`, `web/src/shared/types.ts`, `web/src/shared/styles.css`, `tools/mock-ssh/mock_ssh.py`, `web/e2e/e2e.mjs`, `README.md`

## Verification
1. `scripts/build-all.sh`: web tests and build, then `mvn verify` (all existing tests plus the new ones).
2. `scripts/e2e.sh`: the full Playwright run including the new Docker flow against the mock SSH server.
3. `web/scripts/check-public-bundle.mjs` still passes: nothing from Docker in the public bundle.
4. Manually with `scripts/run-local.sh` and `HUB_DOCKER_ENABLED=true` against the mock: take screenshots of the page in light and dark themes and at phone width.
5. **Not verifiable here** (no Docker or Pi in this environment): the real `docker` CLI output on your Pi and LXCs. The parser is built against documented `--format` output, and one manual check is needed after deploying.

## Implementation notes (what changed from the plan, and why)

- **No `ssh/` package move.** The plan said to move the SSH session code into a new package. Instead `terminal/SshConnector` got a
  public `connect(TerminalSpec)` that returns an authenticated JSch session (pinned host key checked before authenticating,
  public-key only), and `DockerHostSpec.ssh()` turns a Docker host into a `TerminalSpec`. Same behaviour, far less churn in the
  terminal code and its tests.
- **Shared config validation:** `readTerminals` and the new `readDockerHosts` both use `readSshHosts(...)` in `ConfigParser`.
  Error messages are unchanged for `terminals:`.
- **One migration, `V5__docker.sql`:** the `docker_audit` table and `docker_stack_version` (compose.yaml history only; the `.env`
  is never copied into the hub's database because it usually holds passwords).
- **Sessions:** reads share one pooled SSH session per host (idle-closed after 60 s; the sweeper thread exists only while a session
  is open). Jobs (pull, deploy, update, run) and live log follow each open their own session, so the idle sweep can never cut them.
- **Live follow** runs `docker logs -f` on an exec channel **with a PTY**, so closing the channel hangs the process up on the host.
  That is why the setup asks for `restrict,pty` in `authorized_keys`.
- **Errors on an event stream:** an SSE request cannot carry a JSON error, so a refused follow returns a stream whose only event is
  `failed` with the usual `{error, message}`.
- **Run form:** environment values go through `--env-file /dev/stdin`; the confirm dialog shows the exact command with only the
  variable names. "Save as stack instead" converts the form to a compose file and a `.env` (values stay out of compose.yaml).
- **Test tools:** `tools/mock-docker/docker` is a stateful fake CLI; `tools/mock-ssh/mock_ssh.py` now also serves exec requests for
  `hubdocker` (several channels per connection, like the pool uses). `scripts/run-local.sh` declares a working "Mock Pi" and an
  unreachable "Media LXC" so the per-host error path is always visible.

## Verification done

- Java: `DockerCommandsTest` (validation, quoting proven against a real `/bin/sh`, run-form building), `DockerParserTest`,
  `DockerConfigTest`, `DockerOffTest` (off by default, refused with auth mode none), and `DockerApiTest`: the full API through the
  real security chain and a real embedded SSH server running commands through `/bin/sh` with a recording fake `docker`, so the
  exact argv each action produces is asserted. The existing Terminal tests pass unchanged.
- Web: Vitest for the formatting/grouping/compose-conversion helpers; Playwright flows for listing, filtering, protection, a failing
  host, restart/stop/start with confirmation, logs with search and live follow, creating/validating/editing/versioning/deploying/
  deleting a stack, the run form (env kept off the command line), save-as-stack, and phone width in light and dark.
- **Not verified:** real Docker on the Pi or an LXC, real `tailscale serve`, memory on the Pi.
