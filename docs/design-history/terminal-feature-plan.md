# Terminal page for Homelab Hub: implementation plan

> Created: 2026-09-27 · Status: Built on a branch, not deployed (see docs/plan.md 18.u)

This plan is self-contained. It was written from a design discussion with the owner (Tabish). The implementing agent has not seen that conversation, so everything needed is here. **Decided** = the owner chose it. **Refinement** = my detail the owner has not seen; mention it in your final report.

## 0. Read this first: project, git and working rules

### 0.1 The project
"Homelab Hub" is one self-hosted app for the owner's Raspberry Pi 4 (arm64, Docker). Two containers:
- `hub-internal` (port 3010, **host networking**, reachable from LAN and Tailscale only): dashboard, power actions, YAML config editor, portfolio editor, legacy routes. **The terminal feature lives only here.**
- `hub-public` (port 3011): read-only public portfolio. **Do not touch it**; nothing from this feature may reach it (a build script checks the public bundle for leaks).

Stack: Java 17, Spring Boot 3.5, plain JDBC (`JdbcClient`), Flyway, SQLite (WAL); Maven multi-module (`hub-snapshot`, `hub-public`, `hub-internal`); frontend Vite + React 19 + TypeScript in `web/` (hash routing, lazy-loaded heavy pages); tests JUnit 5 / AssertJ / Spring test, Vitest, Playwright (`web/e2e/e2e.mjs`).

Read before starting: `README.md` (architecture, security, config, API), `docs/plan.md` (decisions, lessons and defects in the progress log, section 18). The app is also installed as a phone app (PWA) by the owner, and reached at `https://<pi>.<tailnet>.ts.net` through `tailscale serve`.

### 0.2 Git
- Repo: `https://github.com/TabTQ/homelab-hub-and-docs`, branch `main`. HEAD at time of writing: `566d5bd` (check with `git log --oneline | head -3`; newer commits may exist). GitHub CLI is logged in as `TabTQ` and `gh auth setup-git` was run, so plain `git push` works.
- **Work on branch `feature/terminal`. Do not commit, push or merge unless the owner asks** (standing rule). Commit messages end with `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` (or the model that made the commit).
- `.gitignore` excludes `secrets.env`, `.env`, `*.db`, `.scratch/`, `node_modules`, `dist`, `hub-*/src/main/resources/static/`, `__pycache__`. Never commit anything from `.scratch/`. Before any commit: `git status` and scan staged files for secrets. **Never commit private keys** (add `*.key`, `hub_terminal_ed25519*` patterns to `.gitignore` for the mock SSH tool's generated keys). Real hostnames (Tailscale name, company domain) must not appear in the repo; use placeholders (`<pi>.<tailnet>.ts.net`, `example.com`).
- **Another feature (a Budget page) may be built in parallel by another agent** in its own branch. Expect merge conflicts in `web/src/internal/App.tsx` (Route type, nav, lazy imports, palette), `web/src/shared/styles.css`, `web/e2e/e2e.mjs`, `README.md`, `docs/plan.md`, `hub-internal/src/main/resources/application.yml`. Keep your edits in those files small and localised, and rebase on `main` before reporting. **Flyway migration numbers must be unique**: check the highest existing `V<n>__*.sql` in `hub-internal/src/main/resources/db/migration/` at implementation time and use the next free number (the Budget plan uses `V3__budget.sql`; do not assume `V3` is free).

### 0.3 Environment
- Build tools are not on PATH: JDK `~/tools/jdk-17.0.20.1+1`, Maven `~/tools/apache-maven-3.9.11/bin`. `scripts/build-all.sh` sets them; for ad-hoc Maven: `export JAVA_HOME="$HOME/tools/jdk-17.0.20.1+1" PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.11/bin:$PATH"`.
- Node 22+, Python 3, Chromium at `/usr/bin/chromium` (Playwright uses `playwright-core` with `executablePath` and `--no-sandbox`).
- **Docker is NOT usable on this dev machine**; images are built on the Pi.
- New Maven and npm dependencies (Spring WebSocket, an SSH client library, xterm.js, a test SSH server) are **not in local caches**: the first resolution needs network (run Maven without `-o` once).
- Commands: `scripts/build-all.sh` (web tests + build, copy SPAs into the jars, `mvn verify`), `scripts/e2e.sh` (starts the local stack on 3910/3911 with a mock Proxmox and runs all browser tests; needs a prior build), `scripts/run-local.sh` / `scripts/stop-local.sh` (manual testing; dashboard password `local-test-pass`; data in `.scratch/e2e-data`, wiped each run).
- Baseline at `566d5bd` (re-run to confirm in Phase 0): about 410 Java tests, 62 Vitest tests, 44 Playwright tests, all passing.
- A foreground shell command is limited to 10 minutes: run long builds/deploys in the background and wait with a monitor; do not chain sleeps.

### 0.4 Owner's standing rules
- Never print, copy or commit credentials. The Pi's `deploy/.env` (password hash, Proxmox token) and `data/hub/secrets.env` exist only on the Pi.
- Never run real Proxmox actions or real Wake-on-LAN. Not needed here.
- **Ask before any change on the Pi**: deploying, restarting containers, creating users, editing `authorized_keys`/sudoers, firewall rules. Keep ports 3010/3011. Do not change the `tailscale serve` setup (unless the WebSocket spike in 0.6 proves it must change; then ask).
- State assumptions, do not claim anything verified that you did not verify, handle non-happy paths, say plainly what is untested.
- Use they/them for people unless told otherwise.
- Match the surrounding code style. Add tests for everything; run the relevant suites before saying something is done.
- To ask the owner for a secret use a graphical prompt (`pinentry-qt` / the `pass-entry` skill), never the chat.

## 1. Goal and background

Add a **Terminal** page (top nav entry next to Dashboard / Portfolio / Config) that gives the owner a **shell on the Raspberry Pi from the browser**, including on the phone (installed PWA). It replaces the need to open a separate SSH client. Any other tools the owner runs (for example Guacamole on port 8081) stay as they are.

How: the browser renders the terminal with **xterm.js**; the hub server opens an **SSH session to `127.0.0.1:22` on the same Pi** (the container uses host networking) with a dedicated key, and relays bytes over a **WebSocket**. The shell runs as a **new unprivileged user `hubshell`** (no sudo by default), not as the owner's main account (which has passwordless sudo, i.e. root in practice).

Principles:
- **Fails closed and off by default** (`HUB_TERMINAL_ENABLED`).
- **Nothing runs unless the page is open and a session is active**: lazy frontend chunk; no polling; server threads only exist while a session exists.
- The owner explicitly accepted the residual risk: the dashboard password (plus step-up) opens a shell as `hubshell` on the Pi.

## 2. Requirements

### Functional
- [ ] R1 A **Terminal** page (`#/terminal`, `#/terminal/<id>`) with a host selector (one host today: the Pi), a Connect button, the terminal, a status bar and a Disconnect button. Shown in the nav only when the feature is enabled and at least one terminal is configured.
- [ ] R2 **Step-up password**: every time a terminal is opened, the owner re-enters the dashboard password. The server verifies it and returns a one-time **ticket** (30 s, bound to that login session and host); the WebSocket must present the ticket.
- [ ] R3 Interactive shell with PTY (`xterm-256color`), resize support, UTF-8, 256 colours, scrollback (5000 lines), copy/paste.
- [ ] R4 **Limits**: idle timeout 15 min, maximum session length 2 h, at most 2 concurrent sessions. A warning is shown 60 s before an idle close ("session closes in 60 s: press any key").
- [ ] R5 **Phone UX**: an on-screen key row (Esc, Tab, sticky Ctrl, sticky Alt, arrows, Home, End, PgUp, PgDn, `|`, `~`, `/`, `-`), paste and copy buttons, font-size +/-, correct resizing with the on-screen keyboard, landscape support, autocorrect/capitalisation off for the input. A **Reconnect** button after a session ends.
- [ ] R6 **Host list in config**: terminals are declared in `dashboard.yaml` under a new `terminals:` key (validated with line-numbered messages), so more hosts can be added later by editing the file in the Config page. Only declared hosts can be connected to (the UI never accepts a free-typed host).
- [ ] R7 **Host key pinning**: each terminal has a pinned SHA-256 host-key fingerprint; a mismatch refuses the connection with a clear message. A CLI command prints a host's fingerprint for the owner to compare.
- [ ] R8 **Audit**: record session open, close (reason, duration, byte counts), and denied attempts (wrong password, disabled, limit reached, bad origin) with time, host id, client IP and user agent. **Never record typed input or output.** A small "Recent sessions" card on the Terminal page lists the last 20.
- [ ] R9 Logging out of the dashboard closes that login's terminal sessions immediately.
- [ ] R10 Clear, specific errors for: sshd unreachable, key file missing/unreadable, host key mismatch, authentication rejected, session limit reached, wrong password, too many attempts, ticket expired, connection dropped, idle/max-time close.

### Non-functional / constraints
- [ ] N1 Java 17 only (no pattern-matching `switch`, no `Thread.ofPlatform`, no sequenced collections). Past defects came from this.
- [ ] N2 Everything under `/api/admin/terminal/**` (including the WebSocket path), so it is authenticated in login modes `all` and `edit` with no change to `SecurityConfig`'s matchers. **If `HUB_AUTH_MODE=none`, the terminal must refuse to enable** (log an error at startup, feature reports disabled).
- [ ] N3 No new runtime third-party requests; the CSP (`default-src 'self'`) must keep working, including `connect-src` for the same-origin WebSocket (verify in a real browser, see 0.6). Do not loosen the CSP with wildcards.
- [ ] N4 No background work when unused: no `@Scheduled` in the terminal package; the idle/max-time watchdog uses a scheduler created on the first session and shut down after the last one ends.
- [ ] N5 Bundle: xterm.js and its CSS only in the lazy Terminal chunk; the public bundle leak check (`web/scripts/check-public-bundle.mjs`) must still pass; the main internal chunk must not grow noticeably.
- [ ] N6 Memory: `hub-internal` idle RSS was about 233 MiB (243 MiB after ~1000 requests) with `-Xmx256m`. Measure after the feature, at idle and with 1 and 2 open sessions.
- [ ] N7 Non-happy paths handled and tested (R10, oversized messages, slow client, dropped connections).

### Out of scope
- Recording sessions or keystrokes, file transfer (SFTP/scp), port forwarding, session persistence on the server (the owner uses `tmux` for long jobs), multi-user support, terminal sharing, a config-page form for terminals (edit the YAML), a key generator (the owner uses `ssh-keygen`), key passphrases, hosts not declared in config, granting `hubshell` Docker access.

## 3. Decisions and rationale

| # | Decision | Rationale | Alternatives considered |
|---|---|---|---|
| D1 | SSH relay: hub opens an SSH client session to the target and relays over a WebSocket (xterm.js in the browser). | No Docker socket or extra container privileges; works for other hosts later; uses the existing sshd. | A separate ttyd-style terminal server (another process to secure); a link tile to Guacamole (zero code, but the owner wants it built in). |
| D2 | **Decided:** the shell account is a **new unprivileged user `hubshell`**, no sudo by default. If root is needed the owner can grant `hubshell` a password-requiring sudo rule (see 8.3); never passwordless. | The owner's main user has passwordless sudo, i.e. a terminal as that user is root. | Use the main account (rejected as recommended). |
| D3 | **Decided:** **step-up password every time** a terminal is opened. | A stolen session cookie alone must not give a shell. | Once per login. |
| D4 | **Decided:** limits: idle 15 min, max 2 h, 2 concurrent sessions (all configurable). | Owner: "go with recommendations". Two sessions cover phone + laptop. | 1 session. |
| D5 | **Decided:** audit **metadata only**; never record input or output. | Recordings would capture sudo passwords typed into the terminal. | Output recording; input recording (rejected). |
| D6 | **Decided:** built for **phone use** (key row etc.). | Owner will use it on the phone. | Desktop only. |
| D7 | Hosts are declared in `dashboard.yaml` (`terminals:`), keys referenced **by file name only**, resolved inside `<data>/ssh/`. | Editable in the browser like the rest of the config; a name (not a path) prevents reading arbitrary files if the YAML is edited. **Refinement.** | Environment variables per host; free host entry in the UI (rejected: SSRF-like risk). |
| D8 | SSH host key **pinned by SHA-256 fingerprint** in config (`SHA256:<base64>`, as printed by `ssh-keygen -lf`). | Same idea as the pinned Proxmox certificate; no trust-on-first-use surprises. | `known_hosts` file. |
| D9 | The WebSocket carries **binary frames for terminal output** and **JSON text frames for control**; auth is (session cookie via Spring Security) + (same-origin `Origin` check) + (one-time ticket sent as the first message). | Binary output lets xterm handle partial UTF-8 correctly; the ticket keeps secrets out of URLs and logs. **Refinement.** | Ticket in the URL query (ends up in logs). |
| D10 | Idle = **no client input or resize** for the idle period; keep-alive pings do not count. Output does not count. | A forgotten open session must close; a long `top` view needs a keypress now and then. **Refinement.** | Count output as activity. |
| D11 | The SSH client library is chosen in a short **spike** (Phase 0). Preference: `com.github.mwiede:jsch` (small, pure Java, ed25519 via the JDK on Java 15+ **(believed, verify)**); alternative Apache MINA SSHD client; sshj needs BouncyCastle (heavier). Criteria: ed25519 client keys on JDK 17 without extra crypto libs, host-key verification hook for fingerprint pinning, PTY + window-change, maintained, pure Java (arm64), small. | I have not verified library details; do not assume. | - |
| D12 | Tests use an **embedded SSH test server** (Apache MINA SSHD `sshd-core`, **test scope**) in Java, and a small **mock SSH server tool** for the browser tests (Section 5.9). | Real end-to-end coverage without touching the Pi. | Mocks only. |
| D13 | A **new table** for terminal audit events, not the existing power-action `audit_log`. | `AuditLog.record(Source{ui,legacy}, ...)` is shaped for power actions. **Refinement.** | Extend `audit_log`. |

## 4. Current state and discoveries (verified unless marked)

- The Pi (read-only inspection): sshd listens on port 22; `PasswordAuthentication no`, `PubkeyAuthentication yes`, `KbdInteractiveAuthentication no`; the only real user is `tabishrpi4` (bash) which has **passwordless sudo** (its `sudo` calls worked non-interactively; the exact sudoers rule was not read); `ufw` allows 22/tcp from anywhere (pre-existing, not part of this work). Not yet checked: which host key types sshd offers (check `ls /etc/ssh/ssh_host_*` and prefer ed25519), whether `tailscale serve` (v1.102) passes WebSocket traffic (see 0.6).
- `hub-internal` packages (`hub-internal/src/main/java/com/homelabhub/internal/`): `actions audit auth backup config dashboard legacy portfolio proxmox status widgets wol` plus `HubBeans`, `WebConfig`, `InternalApplication`. Create `com.homelabhub.internal.terminal`.
- `InternalApplication` has CLI sub-commands (`hash-password`, `import`, `fingerprint <host:port>` for Proxmox TLS, `validate-config`); see `proxmox/FingerprintCli.java` for the style. Add a sibling CLI (task 4.1).
- Config: `config/ConfigParser.java` validates `dashboard.yaml` with strict known keys (root keys today: `version title theme widgets pinned groups actions defaults`), line-numbered `Issue`s, compose-only SnakeYAML (no construction, aliases refused), secrets via `${secret:NAME}` (never plaintext credentials). `ConfigStore` keeps the parsed `DashboardConfig`. `DashboardController` exposes a sanitised view (`/api/dashboard`) that never leaks secrets/URLs; the TypeScript type is `DashboardView` in `web/src/shared/types.ts`.
- Auth: `auth/SecurityConfig.java` (single bcrypt(12) password, `HUBSESSION` cookie `HttpOnly; SameSite=Strict`, cookie-to-header CSRF, `/api/**` authenticated in mode `all`, `/api/admin/**` in mode `edit`, CSP `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`), `HostHeaderFilter` + `HostAllowlist` (DNS-rebinding protection; a wrong Host gets 400), `LoginRateLimiter` (per-client escalating lockout plus a global cap), `AuthController` (`/api/auth/login|logout|status`), `Clock` bean.
- One-time-token precedent: `actions/ConfirmationTokens.java` (token bound to one action and one browser session, 30 s TTL, expiry swept lazily). Model the ticket store on it.
- Tests: `hub-internal/src/test/java/com/homelabhub/internal/TestSupport.java`; real-HTTP tests like `auth/AuthApiTest.java` (`@SpringBootTest(RANDOM_PORT)`, cookies + CSRF); `@MockitoBean ProxmoxClient` is used in Spring tests.
- Frontend: `web/src/internal/App.tsx` (`type Route`, `useRoute()` parsing the hash, nav array, `lazy()` for `ConfigPage` inside `Suspense`, palette items), pages in `web/src/internal/pages/`, `web/src/internal/components/`, shared UI in `web/src/shared/` (`api.ts` with `api()`/`apiWithHeaders()`/`primeCsrf()`, `styles.css` tokens `--bg --surface --surface2 --ink --muted --line --accent --accent-ink --accent-soft --ok --warn --bad`, `--f-mono` = IBM Plex Mono self-hosted via fontsource). Dialog CSS (`.overlay .dialog .modal .mrow`) scrolls inside the window and keeps a sticky button row: do not regress it. `Modal` is currently exported from `ConfigPage.tsx` (which imports CodeMirror): if you need it, first move it to `web/src/internal/components/Modal.tsx` (the Budget feature may already have done this).
- **Lesson:** a generic CSS class (`.w`) once collided with another rule and broke dashboard tiles: **prefix all new classes with `term-`**.
- PWA: the service worker (`web/src/internal/public/sw.js`) only handles `/assets/*` and page navigations; WebSocket traffic is not affected.
- Deployment facts: Pi ssh alias `rpi4`; app in `~/homelab-hub` (compose `deploy/docker-compose.yml`; `deploy/.env` is secret: never rsync it); images are built on the Pi (`docker build -f deploy/Dockerfile.internal`); the Pi kernel has no memory cgroup so heap is explicit (`HUB_INTERNAL_JAVA_OPTS`, default `-Xmx256m -XX:+UseSerialGC -Xss512k`); container: read-only root FS, user `hub` (uid 10001), `/tmp` tmpfs `exec`, `cap_drop: ALL` (+`NET_RAW`), `no-new-privileges`, host network, data volume `~/homelab-hub/data/hub` mounted at `/data` (read-write), Watchtower opt-out label. Startup about 30 s; health `GET /actuator/health`.

## 5. Design / approach

### 5.1 Components
```
Browser (xterm.js, key row)                 hub-internal                                   Pi sshd
  POST /api/admin/terminal/sessions  ---->  verify password (shared rate limiter)
       {hostId, password}            <----  one-time ticket (30 s, bound to login session + host)
  WS   /api/admin/terminal/ws        ---->  handshake: session cookie + Origin check + Host allowlist
       first msg {type:auth,ticket}  ---->  redeem ticket, check limits, open SSH (pinned host key, key file) ---> hubshell shell (PTY)
       input/resize (text JSON)      ---->  relay  <----------------------------------------------------------->  output (binary frames)
```

### 5.2 Config
`application.yml` (all env-overridable; add to `deploy/docker-compose.yml` environment with the same defaults and to `deploy/.env.example`):
```yaml
hub:
  terminal:
    enabled: ${HUB_TERMINAL_ENABLED:false}
    idle-seconds: ${HUB_TERMINAL_IDLE_SECONDS:900}
    warn-seconds: ${HUB_TERMINAL_WARN_SECONDS:60}
    max-seconds: ${HUB_TERMINAL_MAX_SECONDS:7200}
    max-sessions: ${HUB_TERMINAL_MAX_SESSIONS:2}
    connect-timeout-seconds: 10
    max-message-bytes: 65536
    max-queued-output-bytes: 524288
```
`dashboard.yaml` gets a new optional root key (add it to `known(root, ...)` in `ConfigParser`, to the `DashboardConfig` model, and to `default-dashboard.yaml` as a commented example only):
```yaml
terminals:
  - id: pi                          # ^[a-z0-9][a-z0-9_-]{0,31}$, unique
    label: Raspberry Pi
    host: 127.0.0.1                 # hostname or IP literal
    port: 22                        # 1..65535, default 22
    user: hubshell                  # ^[a-z_][a-z0-9_-]{0,31}$
    key: hub_terminal_ed25519       # a FILE NAME inside <data>/ssh/, ^[A-Za-z0-9._-]{1,64}$, never a path
    hostKey: "SHA256:AbC...43chars" # required, ^SHA256:[A-Za-z0-9+/]{43}$
```
Validation errors (with line numbers, like the other keys): unknown keys, bad id/host/port/user/key/hostKey, duplicate ids, more than 10 entries, `key` containing `/`, `\` or `..`. The parsed config exposes to the browser only `id`, `label`, `user`, `host`, `port` (never `key` or `hostKey`).

`DashboardView` (`/api/dashboard`) gains `features: { terminal: boolean }` (true when enabled, login mode is not `none`, and at least one terminal is configured); the UI uses it to show the nav entry. Add the field to `web/src/shared/types.ts`.

Startup rule: if `hub.terminal.enabled=true` and `hub.auth.mode=none`, log an ERROR and treat the feature as disabled.

### 5.3 REST and WebSocket API (all under `/api/admin/terminal`, all require the login session; writes need CSRF)
| Route | Purpose |
|---|---|
| `GET /config` | `{enabled, idleSeconds, warnSeconds, maxSeconds, maxSessions, terminals:[{id,label,user,host,port}]}` |
| `POST /sessions` `{hostId, password}` | step-up: verifies the dashboard password (bcrypt, same `LoginRateLimiter` so failures share the lockout budget), checks the session limit, returns `{ticket, expiresInSeconds}`. Errors: 401 wrong password (generic), 429 lockout/limit (`Retry-After`), 404 unknown host, 409 limit reached, 503 feature disabled |
| `GET /audit?limit=20` | recent terminal audit rows (no content) |
| `WS /ws` | the terminal channel (below) |

WebSocket protocol:
- Handshake (Spring `HandshakeInterceptor`): the HTTP session must be authenticated (Spring Security already protects `/api/admin/**`), `Origin` must be present and its host (and port) must equal the request `Host` and be in the allowed-hosts list (blocks cross-site WebSocket hijacking; SameSite=Strict on the cookie is a second layer). Reject otherwise with 403 and an audit `denied` row.
- Client -> server text frames (JSON, max 64 KiB): `{"type":"auth","ticket":"..."}` (must be the first message, within 5 s, else close), `{"type":"resize","cols":N,"rows":M}` (clamped 10..500 / 3..200), `{"type":"input","data":"..."}`, `{"type":"ping"}`.
- Server -> client: **binary frames = raw terminal output bytes**; text frames JSON: `{"type":"ready","host":"pi","user":"hubshell"}`, `{"type":"warn","secondsLeft":60}`, `{"type":"exit","reason":"closed|idle|max|error|logout|too_slow|denied","message":"...","exitCode":N?}`, `{"type":"pong"}`. After `exit` the server closes the socket.
- Backpressure: use Spring's `ConcurrentWebSocketSessionDecorator` (send time limit 10 s, buffer limit `max-queued-output-bytes`); on overflow close with reason `too_slow`. Stop reading the SSH channel while the queue is full, do not buffer unboundedly.
- Ticket: 32 random bytes URL-safe, in memory (`TerminalTickets`, modelled on `ConfirmationTokens`), one-time, 30 s TTL, bound to the HTTP session id and host id, swept lazily on issue/redeem (no scheduler). Never log tickets or passwords.
- Session manager: enforces `max-sessions` (counting active + unredeemed tickets is not required; count active sessions at redeem time and at issue time), tracks sessions per login session so logout (R9) can close them (hook into the logout handler in `AuthController`), owns the lazily created single-thread `ScheduledExecutorService` for idle/max/warn timers (`shutdown()` when the last session ends), and closes everything on application shutdown (`@PreDestroy`).
- Audit: new Flyway migration (**next free version number**, e.g. `V<n>__terminal_audit.sql`):
```sql
CREATE TABLE terminal_audit (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    event      TEXT    NOT NULL CHECK (event IN ('open', 'close', 'denied')),
    host_id    TEXT,
    client_ip  TEXT,
    user_agent TEXT,
    detail     TEXT               -- e.g. reason, duration_s, bytes_in, bytes_out; NEVER content
);
CREATE INDEX idx_terminal_audit_ts ON terminal_audit (ts);
```
Keep at most the last 1000 rows (delete older ones on insert). A failing audit write must never break a session (same principle as `AuditLog`).

### 5.4 SSH layer (`terminal` package, pure logic separated from Spring)
- `TerminalHost` (id, label, host, port, user, key file name, host-key fingerprint).
- `KeyStore`: resolves `<data>/ssh/<name>`; refuses anything that is not a regular file inside that directory after `toRealPath()` (no symlink escapes); a clear error if missing or unreadable; never logs the path contents.
- `SshConnector` (library chosen in the spike): connect timeout 10 s, **host key verification against the pinned fingerprint before authenticating** (compute `SHA256:` + base64 without padding of the host key blob; compare constant-time), public-key auth only (no password, no keyboard-interactive), no agent/port/X11 forwarding, PTY `xterm-256color` with the requested size, `SSH keepalive` every 30 s, algorithms left at the library's safe defaults (do not enable legacy ciphers/KEX).
- `TerminalConnection` interface (input bytes in, output bytes out, resize, close, exit status) so the WebSocket handler is testable with a fake.
- Errors are translated to the R10 messages; never expose stack traces, key paths or fingerprints of other hosts to the browser (the mismatch message may show the pinned and the offered fingerprints).

### 5.5 CLI
`java -jar hub-internal.jar terminal-hostkey <host>[:port]` connects, prints the host key type and `SHA256:` fingerprint (and reminds the owner to compare it with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on that machine **before** pasting it into `dashboard.yaml`), and exits. Model on `FingerprintCli`; add to the usage text and README.

### 5.6 Frontend
- `web/src/internal/pages/TerminalPage.tsx` (default export, `React.lazy` in `App.tsx`, wrapped in `Suspense` like `ConfigPage`), route `#/terminal[/<id>]`, nav entry "Terminal" only when `features.terminal`, palette item "Terminal". Adds `@xterm/xterm` and `@xterm/addon-fit` (MIT) via npm; import `@xterm/xterm/css/xterm.css` in the page module so Vite bundles it into the lazy chunk. Use the DOM renderer (default; no canvas/WebGL addon), font `var(--f-mono)`, scrollback 5000, `cursorBlink`, theme built from the CSS tokens (re-applied when the theme changes). No auto-connect: opening the page shows the host selector, a "Connect" button and the recent-sessions card; nothing else happens.
- Connect flow: Connect -> step-up dialog (password field, `autocomplete="current-password"`, Enter submits) -> `POST /sessions` -> on success open `wss://` (or `ws://` when the page is http) `<origin>/api/admin/terminal/ws` -> send `{type:auth,ticket}` -> on `ready` show the terminal and focus it. Show precise errors (R10) in the dialog/status bar. The password is kept in memory only while submitting, then cleared.
- Wiring: `term.onData` -> `input` frames (with the sticky Ctrl/Alt transformation), `term.onBinary` -> `input` (as latin-1 string is not safe: send as text of the raw string xterm provides; binary mouse reports are optional and may be ignored), `term.onResize`/`fit` -> `resize`, binary frames -> `term.write(new Uint8Array(...))`, ping every 20 s. Close the socket and dispose the terminal on page unmount, on `exit`, and on `visibilitychange` only after the server closes (do not force-close when the tab is hidden; Android may freeze it, the server idle timer handles it).
- Key row (`term-keys`): buttons Esc, Tab, Ctrl (sticky, next typed letter becomes the control code, e.g. `c` -> `\x03`, `d` -> `\x04`, `[` -> `\x1b`), Alt (sticky, prefixes `\x1b`), arrows (`\x1b[A/B/C/D`), Home/End (`\x1b[H`/`\x1b[F`), PgUp/PgDn (`\x1b[5~`/`\x1b[6~`), and `| ~ / -`. The row scrolls horizontally on narrow screens (its own `overflow-x:auto`). Buttons use `onPointerDown` + `preventDefault()` so the on-screen keyboard and the terminal keep focus. Paste button (`navigator.clipboard.readText()` on a user gesture, with a fallback message), Copy button (`term.getSelection()` + `navigator.clipboard.writeText`), font-size +/- (stored in `localStorage` in try/catch, per-viewer convenience only).
- Keyboard/viewport: on phones set `term.textarea` attributes `autocapitalize="off" autocorrect="off" spellcheck="false" inputmode="text"`; size the terminal container from `window.visualViewport` (listen to `resize`/`scroll`) so the on-screen keyboard does not cover the cursor, then `fit()` and send a `resize`. Terminal container uses the remaining viewport height (`100dvh` minus header/status/key rows), no page-level scrolling.
- Status bar: `user@host`, connected time, and an idle countdown that appears on the server's `warn` message (any key press resets it on the server side; the client just displays). Disconnect button. After an `exit`, show the reason and a Reconnect button (which goes through the step-up again).
- "Recent sessions" card from `GET /audit` (time, host, event, reason, duration).
- Empty/edge states: feature disabled or no terminals (not reachable via nav, but handle a direct URL with a clear message), sshd unreachable, session limit reached, lockout (`Retry-After`), offline.
- Styles: a section `/* ---- terminal ---- */` in `web/src/shared/styles.css`, classes prefixed `term-`, tokens only, light and dark verified, phone first.
- Accessibility: labelled inputs and buttons, `role="alert"` on errors, key-row buttons are real `<button type="button">` with `aria-label`s and `aria-pressed` for sticky Ctrl/Alt.

### 5.7 Deploy and Pi-side setup (owner's approval required for every step on the Pi)
Compose: pass `HUB_TERMINAL_ENABLED`, `HUB_TERMINAL_IDLE_SECONDS`, `HUB_TERMINAL_MAX_SECONDS`, `HUB_TERMINAL_MAX_SESSIONS` into `hub-internal` (defaults as above, feature **off**), add them to `deploy/.env.example`. No new volumes: the key lives in the existing data volume under `data/hub/ssh/`. The container has host networking, so it reaches `127.0.0.1:22` and sshd sees the source address `127.0.0.1` (verify).

Owner steps on the Pi (documented in README; give them to the owner as exact commands, and run them yourself over ssh **only after asking**):
```bash
# 1. the unprivileged account (no sudo group); the owner types a password (used only for sudo, if granted)
sudo adduser --disabled-password --gecos "" hubshell
sudo passwd hubshell
# 2. OPTIONAL, only if the owner wants root inside the terminal: sudo that ASKS for hubshell's password
echo 'hubshell ALL=(ALL:ALL) ALL' | sudo tee /etc/sudoers.d/hubshell >/dev/null
sudo chmod 440 /etc/sudoers.d/hubshell && sudo visudo -cf /etc/sudoers.d/hubshell
# 3. a dedicated key for the hub, restricted to connections from the Pi itself
ssh-keygen -t ed25519 -N "" -C homelab-hub-terminal -f /tmp/hub_terminal_ed25519
sudo install -d -m 700 -o hubshell -g hubshell /home/hubshell/.ssh
echo "from=\"127.0.0.1,::1\",no-agent-forwarding,no-port-forwarding,no-X11-forwarding $(cat /tmp/hub_terminal_ed25519.pub)" \
  | sudo tee /home/hubshell/.ssh/authorized_keys >/dev/null
sudo chown hubshell:hubshell /home/hubshell/.ssh/authorized_keys && sudo chmod 600 /home/hubshell/.ssh/authorized_keys
sudo install -d -m 700 -o 10001 -g 10001 ~/homelab-hub/data/hub/ssh
sudo install -m 600 -o 10001 -g 10001 /tmp/hub_terminal_ed25519 ~/homelab-hub/data/hub/ssh/hub_terminal_ed25519
shred -u /tmp/hub_terminal_ed25519 /tmp/hub_terminal_ed25519.pub
# 4. the host key fingerprint to pin: compare BOTH before trusting
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub          # on the Pi
docker exec hub-internal java -jar /app/hub-internal.jar terminal-hostkey 127.0.0.1:22   # see 5.5; the two fingerprints must match
# 5. add the `terminals:` entry to dashboard.yaml (Config page), set HUB_TERMINAL_ENABLED=true in deploy/.env, restart hub-internal
```
Note (`hubshell` owns nothing important, cannot use Docker or sudo unless the owner adds it): tell the owner that giving `hubshell` Docker-group membership would be root-equivalent; do not do it.

### 5.8 Security notes for review (write the review into the final report)
Threats and controls: stolen session cookie -> step-up password + ticket; cross-site WebSocket hijack -> Origin check + SameSite=Strict + ticket; DNS rebinding -> existing Host allowlist applies to the upgrade request (verify by test); brute force through the step-up -> shared `LoginRateLimiter`; rogue host/SSRF via the YAML -> only declared hosts, no free-typed host, host key pinned, key referenced by name inside `<data>/ssh/`; key theft from the data dir -> file mode 600, owner uid 10001, key only valid from 127.0.0.1 for a no-sudo user; resource abuse -> session/idle/max/message/queue limits; secrets in logs -> never log tickets, passwords, keys or terminal content; logout -> sessions closed. Residual risk (accepted by the owner): anyone who obtains the dashboard password gets a `hubshell` shell; if the hub container were compromised, the key in `/data/ssh` gives a `hubshell` shell.

### 5.9 Test tooling
- **Java tests**: Apache MINA SSHD `sshd-core` as a **test-scope** dependency to run an in-process SSH server with a fixed host key, an authorised test public key and a shell that echoes / reports PTY size / handles window-change / exits with a code. Use `@SpringBootTest(webEnvironment=RANDOM_PORT)` with the test server's port and `java.net.http.HttpClient` (cookies, CSRF) plus `HttpClient.newWebSocketBuilder()` for the WebSocket.
- **Browser tests**: a tiny SSH server for the local stack, `tools/mock-ssh/mock_ssh.py`, bound to `127.0.0.1` on a random or fixed test port (e.g. 2222), generating a throwaway host key and client key into `.scratch/e2e-data/ssh/` per run, spawning a real `sh` PTY (loopback only, throwaway environment, started/stopped by `scripts/run-local.sh` / `stop-local.sh`, PID in the existing `pids` file). Check which of `asyncssh` or `paramiko` is available or installable with pip and use that (document the choice and the dependency in the tool's docstring; the browser tests must skip with a clear message, not fail, if it is unavailable). `run-local.sh` must set `HUB_TERMINAL_ENABLED=true` (plus short idle values for tests, e.g. `HUB_TERMINAL_IDLE_SECONDS=12`, `HUB_TERMINAL_WARN_SECONDS=5`) and write a `terminals:` entry (with the mock's real fingerprint) into the e2e dashboard config (`web/e2e/dashboard.e2e.yaml` is copied to the data dir; generate the fingerprint at start-up and substitute it; do not hard-code it).

## 6. Implementation tasks

### Phase 0: Set-up, baseline and spike
- [ ] 0.1 `git status`, `git log --oneline | head -3`, then `git switch -c feature/terminal`.
  - Acceptance: on the branch, tree clean.
- [ ] 0.2 Read `README.md`, `docs/plan.md` (sections 5, 9 and 18) and the files named in section 4.
- [ ] 0.3 Baseline: `scripts/build-all.sh` then `scripts/e2e.sh`; record counts. Everything must pass before you change anything; if not, stop and tell the owner.
- [ ] 0.4 **Library spike** (throwaway code, not committed): with each candidate SSH client library, verify against a local test server or a local `sshd` on this machine: ed25519 client key on JDK 17 without BouncyCastle, host-key verification hook to pin a fingerprint, PTY with `xterm-256color`, window-change (resize), keepalive, clean close, jar size, last release date / maintenance. Pick one and write the choice and the evidence into the final report and README.
- [ ] 0.5 **WebSocket spike**: a trivial Spring WebSocket echo endpoint under `/api/admin/terminal/ws` behind the real security chain; verify in a real Chromium (Playwright) that (a) the CSP `connect-src 'self'` allows the same-origin `ws://` connection (and `wss://` when served over https), (b) unauthenticated upgrade is rejected, (c) the Host allowlist applies. If `connect-src 'self'` blocks it, add a minimal explicit origin (derived from the request Host, never a wildcard) and note it.
- [ ] 0.6 **Tailscale spike (owner approval needed for anything on the Pi)**: ask the owner to open the deployed app through `https://<pi>.<tailnet>.ts.net` and confirm a WebSocket works through `tailscale serve`; if you deploy a temporary echo build for this, follow 0.4 rules (ask first, remove afterwards). If WebSockets do not pass, stop and report before building further.
  - Acceptance: library chosen with evidence; WebSocket and CSP behaviour known; blocker reported if any.

### Phase 1: Config, settings and feature flag
- [ ] 1.1 Add the `hub.terminal.*` settings to `application.yml` and a `TerminalProperties` record bound with `@ConfigurationProperties`; startup rule for `auth.mode=none` (log ERROR, feature disabled).
- [ ] 1.2 Extend `ConfigParser` / `DashboardConfig` with `terminals:` (5.2), incl. validation messages with line numbers; expose `id,label,user,host,port` only. Extend `default-dashboard.yaml` with a **commented** example (do not enable one).
  - Tests (`ConfigParserTest`-style): valid entry; missing/invalid id, host, port, user, key, hostKey; duplicate ids; > 10 entries; `key: ../../etc/passwd`, `key: /etc/passwd`, `key: a\b` rejected; unknown keys; `terminals: []` and absent key both fine; other existing config tests unchanged.
- [ ] 1.3 `DashboardController`/`DashboardView` + `web/src/shared/types.ts`: add `features.terminal`. Tests: false when disabled, when mode `none`, and when no terminals; true otherwise; still no secrets/keys/host keys in the JSON.
  - Acceptance: tests pass.

### Phase 2: SSH layer
- [ ] 2.1 Add the chosen SSH client library to `hub-internal/pom.xml` and `sshd-core` (test scope). Confirm `hub-public` is unaffected (its Maven enforcer rules stay green).
- [ ] 2.2 `TerminalHost`, `KeyStore` (5.4), `SshConnector`, `TerminalConnection`, fingerprint helper (`SHA256:` + base64 no padding). 
  - Tests against the embedded MINA test server: connects and echoes; PTY size honoured and updated on resize; exit code reported; **wrong host key is refused before authentication**; wrong/unauthorised client key -> auth error; unreachable port -> timeout/refused error within `connect-timeout-seconds`; missing key file; key path escapes (symlink to another directory) refused; no password/keyboard-interactive fallback; fingerprint helper matches `ssh-keygen -lf` output for a known key (hard-code a known key/fingerprint pair generated for the test).
  - Acceptance: tests pass.

### Phase 3: Sessions, tickets, WebSocket, audit
- [ ] 3.1 Migration for `terminal_audit` (next free version number, see 0.2 of section 0) + `TerminalAudit` repository with the 1000-row cap. Extend `SchemaAndPragmasTest` (or a new test) for the table and the CHECK on `event`.
- [ ] 3.2 `TerminalTickets` (one-time, 30 s, bound to session id + host id, lazy sweep). Unit tests: redeem once, wrong session, wrong host, expiry (fixed `Clock`), randomness/length.
- [ ] 3.3 `TerminalSessionManager`: limits, per-login-session tracking, lazily created/shut-down scheduler for idle/warn/max timers, `closeForLoginSession(sessionId, reason)`, `@PreDestroy` close-all. Unit tests with a fake `TerminalConnection`, fake `Clock`/scheduler: idle warn at `idle - warn`, idle close, activity resets (input and resize but **not** ping), max-time close, concurrency limit (the third session refused), logout closes sessions, scheduler shut down when no sessions remain (N4).
- [ ] 3.4 REST: `GET /config`, `POST /sessions` (verify password with the existing `PasswordEncoder` and hash from `AuthProperties`; use the shared `LoginRateLimiter` keyed like login failures; constant-ish response for wrong password; 429 with `Retry-After`; 409 limit; 404 unknown host; 503 disabled), `GET /audit`. Audit `denied` rows for failures.
- [ ] 3.5 WebSocket: `HandshakeInterceptor` (session + Origin/Host), `TerminalWebSocketHandler` (protocol in 5.3; first-message auth within 5 s; message size cap; binary output relay with `ConcurrentWebSocketSessionDecorator` backpressure; resize clamp; ping/pong; close and audit on all paths; hook logout in `AuthController`). Add `spring-boot-starter-websocket` to `hub-internal/pom.xml` (do not use STOMP/SockJS).
- [ ] 3.6 Integration tests (real HTTP + WebSocket against the embedded SSH server, `hub.auth.mode=all`, `hub.terminal.enabled=true`, short idle/max values): unauthenticated `GET /config` -> 401 in modes `all` and `edit`; feature disabled -> 503 and no WebSocket; wrong password -> 401 and audit `denied`; correct password -> ticket -> WebSocket -> echo and exit code -> audit `open` and `close` with byte counts and **no content**; ticket reuse rejected; expired ticket rejected; ticket from another login session rejected; no auth message within 5 s -> closed; oversized message -> closed; bad `Origin` (`https://evil.example`) -> 403 and audit; missing `Origin` -> 403; wrong `Host` -> 400 (existing filter); idle timeout and warning frames arrive; third concurrent session refused; logout closes an open session; dropping the WebSocket closes the SSH connection (verify server side); slow-consumer overflow closes with `too_slow`; step-up brute force triggers the lockout shared with login; nothing sensitive in captured logs (assert the log output of the test contains neither the password, the ticket nor typed input).
- [ ] 3.7 Test that the terminal package has no `@Scheduled` methods (N4).
  - Acceptance: all pass and `scripts/build-all.sh` is green.

### Phase 4: CLI
- [ ] 4.1 `terminal-hostkey <host>[:port]` (5.5) wired into `InternalApplication`'s command dispatch and usage text. Test against the embedded server: prints the expected fingerprint and exits non-zero on connection failure.

### Phase 5: Frontend
- [ ] 5.1 `npm install @xterm/xterm @xterm/addon-fit` in `web/` (check licences: MIT; note them in the README). Commit `package.json` and `package-lock.json` changes only.
- [ ] 5.2 `TerminalPage.tsx` and small components (`StepUpDialog`, `KeyRow`, `StatusBar`, `RecentSessions`) per 5.6; pure helpers in a testable module (`web/src/internal/terminal/keys.ts`: the Ctrl/Alt transformations and escape sequences; `protocol.ts`: message builders/parsers) with Vitest tests: control-code mapping for a-z, `[`, `\`, `]`, `^`, `_`, space; Alt prefix; unknown keys pass through; message size limit; JSON parsing of server frames including malformed input.
- [ ] 5.3 `App.tsx`: route `terminal`, nav entry gated by `features.terminal`, lazy import + `Suspense`, palette item, `#/terminal/<id>`. `styles.css`: `term-` classes in a marked section, phone-first, light/dark.
- [ ] 5.4 Build (`scripts/build-all.sh`): the public-bundle leak check passes; `web/dist/internal` has a separate Terminal chunk containing xterm, and the main chunk did not grow noticeably (record before/after sizes).
  - Acceptance: page works locally against the mock SSH server (Phase 6).

### Phase 6: Browser tests and visual review
- [ ] 6.1 `tools/mock-ssh/mock_ssh.py` (5.9) + `scripts/run-local.sh` / `scripts/stop-local.sh` changes (start/stop it; set the terminal env; generate keys/fingerprint per run into `.scratch/e2e-data/ssh/`); `.gitignore` entries for generated keys. Add a small Python test for the tool (like `tools/mock-proxmox/test_mock_proxmox.py`).
- [ ] 6.2 Add a `describe("terminal", ...)` block to `web/e2e/e2e.mjs` (specific locators; the DOM renderer exposes text in `.xterm-rows`). Tests: nav entry visible; opening the page starts nothing (no `/api/admin/terminal` request except `GET /config` and `/audit`, and the xterm chunk is fetched only after navigating to `#/terminal`; on the dashboard route no terminal chunk/request); wrong password -> error, no session; correct password -> connected, run `echo hello-$((6*7))` -> `hello-42` appears; `stty size` reflects the terminal size and changes after a viewport resize; sticky **Ctrl** on the key row + `c` interrupts `sleep 30`; Tab/arrow keys work (history recall); Esc button; idle warning banner then automatic close, then Reconnect works; Disconnect closes; a third session in another context is refused with a clear message; logout in another tab closes the terminal; phone viewport 390x800: key row visible and scrollable, no horizontal page overflow, terminal fits, Connect dialog fully reachable; short screen 1280x480: dialog buttons reachable; unauthenticated context: `GET /api/admin/terminal/config` -> 401; `expectNoProblems()` at the end of each test (console errors, failed requests, 5xx/403/404), allowing only the expected 401/429 where a test provokes them.
- [ ] 6.3 Screenshots (light/dark, 390 px and 1280 px, connected terminal, step-up dialog, session ended state). **Look at them** and fix layout problems (past rounds found clipped tables and broken glyphs this way).
- [ ] 6.4 Run the full `scripts/e2e.sh`: all 44 existing tests plus the new ones must pass.

### Phase 7: Docs and housekeeping
- [ ] 7.1 `README.md`: Features (Terminal), Security (controls table rows + the residual risk), Configuration (`terminals:` example and the `HUB_TERMINAL_*` variables), a "Terminal setup" section with the Pi steps from 5.7, tests table counts, known limitations (sessions end on disconnect, use `tmux`; Android/Gboard behaviour untested until the owner tries; no recording by design), the SSH library and xterm.js (MIT) credits.
- [ ] 7.2 `docs/plan.md`: dated progress-log entry (what was built, deviations, test counts, measurements, what is unverified).
- [ ] 7.3 `deploy/docker-compose.yml`, `deploy/.env.example`: the new variables (defaults keep the feature off).

### Phase 8: Final verification, then ask before touching the Pi
- [ ] 8.1 `scripts/build-all.sh` and `scripts/e2e.sh` fully green; record counts.
- [ ] 8.2 Review your own diff for security (5.8): every route under `/api/admin/terminal`; no secrets/keys/tickets/passwords/terminal content in logs or the audit table; Origin/Host/ticket checks proven by tests; the key file cannot be chosen by the browser; caps enforced.
- [ ] 8.3 Report to the owner (section 9) and **ask permission to deploy and to change the Pi** (user, key, config).
- [ ] 8.4 After approval, deploy `hub-internal` (see below), do the Pi-side setup with the owner (5.7, one step at a time, showing what each command does), pin the host key after comparing fingerprints, enable the feature, and have the owner test from the phone. Measure memory. Commit/push only if asked.

**Deploy steps (only after approval).** From the repo root after a green build:
```
rsync -a hub-internal/target/hub-internal-0.1.0-SNAPSHOT.jar rpi4:homelab-hub/hub-internal/target/
rsync -a --exclude .env deploy/ rpi4:homelab-hub/deploy/
ssh rpi4 'cd ~/homelab-hub && docker build -q -f deploy/Dockerfile.internal -t homelab-hub-internal:local . >/dev/null && docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d hub-internal'
```
Run it in the background (the image build takes minutes) and wait until `curl -sf localhost:3010/actuator/health` on the Pi answers (30-60 s). Only `hub-internal` is rebuilt. The migration runs at startup on the existing `data/hub/hub.db`: take a backup first (`POST /api/admin/backups` in the app, or copy `data/hub/hub.db*` while stopped) and say so. Add the new variables to the Pi's `deploy/.env` **only with the owner's consent** (`.env` holds secrets: edit it in place with `sed`/an editor, never print it). Measure RSS via `/proc/<pid>/status` of the container's main process (`docker stats` shows 0 B on this Pi): idle, with 1 session, with 2 sessions; compare with 233/243 MiB and report honestly.

## 7. Testing and verification checklist
- [ ] Java unit and integration tests (Phases 1-4), including embedded-SSH end-to-end and the security cases in 3.6.
- [ ] Vitest for key mapping and protocol helpers; existing suites still green.
- [ ] Playwright terminal suite (6.2) plus all previous browser tests.
- [ ] Public bundle leak check passes; terminal chunk is separate (5.4).
- [ ] Manual: `scripts/run-local.sh`, open `http://127.0.0.1:3910` (password `local-test-pass`), use the Terminal page on desktop and at phone width; light and dark.
- [ ] Idle check: with the page never opened there is no terminal request or chunk load (6.2) and no `@Scheduled` in the package (3.7).
- [ ] State clearly what could not be verified: behaviour through `tailscale serve` on the real Pi, Android Chrome/Gboard input quirks, the installed PWA on the phone, until the owner tests.

## 8. Risks, edge cases and gotchas
- **`tailscale serve` and WebSockets**: unverified (0.5/0.6); a blocker if it fails.
- **CSP and `ws:`**: `connect-src 'self'` may or may not cover `ws://` in every browser: test in real Chromium; never add wildcards.
- **Partial UTF-8 across frames**: send output as binary and let xterm decode; never convert output to JS strings on the server.
- **Android soft keyboard**: predictive text/autocorrect can send composition events; xterm handles most, set the textarea attributes (5.6); Ctrl/Alt via the sticky key row because soft keyboards have no Ctrl. The virtual keyboard shrinks the visual viewport: size the terminal from `visualViewport`.
- **User activation and clipboard**: `navigator.clipboard.readText()` needs a user gesture and may prompt once; provide the fallback message.
- **Background tabs / PWA suspension**: connections may drop when the phone sleeps; the session ends and Reconnect works; the server idle timer still cleans up. Recommend `tmux`.
- **Read-only container**: the key is read from `/data/ssh` (read-write volume); no writes are needed elsewhere. Do not write keys to `/tmp`.
- **File permissions**: the key file must be `600` and owned by uid 10001 or the hub cannot read it; report a clear error otherwise.
- **`from=` restriction**: relies on sshd seeing `127.0.0.1`; verify on the Pi.
- **Ticket and password handling**: never log; clear the password in the UI after submitting; the ticket is one-time and short-lived.
- **Config edits**: the browser Config editor can add terminals, but only with a key name from `/data/ssh` and a matching pinned host key; an editor error must never take the dashboard down (existing last-good-config behaviour applies).
- **Threads**: one reader thread per session while it exists; nothing when idle; the scheduler is lazily created and shut down (N4).
- **CSS collisions**: prefix `term-`. Dialogs must still scroll inside short windows with a sticky button row.
- **Playwright**: strict-mode locators must be specific; the xterm DOM renderer text lives in `.xterm-rows`.
- **Java 17**: no newer APIs (N1). **Static resources** come from `web/dist` copied by `scripts/build-all.sh`: always rebuild before e2e or deploy.
- **Merge conflicts** with a parallel Budget feature (0.2) and the **migration number** clash risk.
- The owner's real Pi is never a test target; the local stack and the mock SSH server are.

## 9. Final report to the owner (what to say when done)
Summarise: what was built and where; test counts vs the baseline; the library chosen and why; the WebSocket/CSP/Tailscale findings; the **refinements** they have not seen (config-driven hosts with key-by-name and pinned host key, ticket-over-first-message protocol, idle definition that ignores pings, warning 60 s before closing, audit table with recent-sessions card, logout closes sessions, refusing to enable in no-login mode); measurements; what is unverified; and ask permission to deploy and to change the Pi.

## 10. Open questions (defaults above are safe; change only if the owner says so)
- [ ] Should `hubshell` get a password-requiring sudo rule (5.7 step 2) or stay fully unprivileged? (Default: owner decides during setup; the plan documents both.)
- [ ] Should sessions persist server-side (like `tmux` built in)? (Default: no; use `tmux` in the shell.)
- [ ] Should a "Terminal" shortcut be added to the installed app's manifest shortcuts? (Default: no.)

## 11. References
- Conventions to copy: `hub-internal/src/main/java/com/homelabhub/internal/actions/ConfirmationTokens.java` (one-time tokens), `auth/LoginRateLimiter.java`, `auth/SecurityConfig.java`, `auth/HostAllowlist.java` / `HostHeaderFilter.java`, `proxmox/FingerprintCli.java` and `InternalApplication.java` (CLI), `config/ConfigParser.java` (strict keys, line-numbered issues), `audit/AuditLog.java` (never break the action on audit failure), `hub-internal/src/test/java/com/homelabhub/internal/TestSupport.java`, `auth/AuthApiTest.java` (real HTTP + cookies + CSRF), `web/src/internal/pages/ConfigPage.tsx` (lazy page, dialogs), `web/src/shared/api.ts`, `web/e2e/e2e.mjs`, `tools/mock-proxmox/` (mock tool + Python test style), `scripts/run-local.sh` / `stop-local.sh`.
- External: xterm.js (`@xterm/xterm`, `@xterm/addon-fit`, MIT), Spring WebSocket (`spring-boot-starter-websocket`, `HandshakeInterceptor`, `ConcurrentWebSocketSessionDecorator`), OpenSSH `authorized_keys` options (`from=`, `no-port-forwarding`...), `ssh-keygen -lf` fingerprint format, the chosen SSH client library (Phase 0.4), Apache MINA SSHD (test server).
