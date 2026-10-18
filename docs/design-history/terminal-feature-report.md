# Terminal page: implementation report

Branch `claude/homelab-terminal-feature-d8m1w1` · built from [`terminal-feature-plan.md`](terminal-feature-plan.md) · 2026-09-26

**Status: built and tested locally, off by default, not deployed. Nothing on the Pi was changed.**

## Summary

The dashboard (`hub-internal`) has a new **Terminal** page that opens a shell on the Pi from the browser, including from the installed
phone app. The browser renders the terminal with xterm.js. The hub opens an SSH session to `127.0.0.1:22` as an unprivileged user
`hubshell` and relays the bytes over a WebSocket. The feature stays off until `HUB_TERMINAL_ENABLED=true` is set, a `terminals:`
entry is added to `dashboard.yaml`, and the Pi-side setup in the README is done.

`hub-public` is untouched: its dependency enforcer and the public-bundle leak check both pass.

| Commit | Content |
|---|---|
| `229f243` | Backend: `terminal` package, config, migration, CLI, Java tests |
| `130aff0` | Frontend: lazy Terminal page, key row, Vitest and browser tests |
| `08c222e` | Mock SSH tool, local stack, deploy settings, README and progress log |

64 files changed, about 5,000 lines added (most of them tests).

## What the user gets

- A **Terminal** nav entry and command-palette item, shown only when the feature is on and at least one host is configured.
- A **Connect** button that asks for the dashboard password again, every time (step-up), then opens the shell.
- An interactive shell: `xterm-256color`, UTF-8, resize, 5,000 lines of scrollback, copy and paste.
- A **phone key row**: Esc, Tab, sticky Ctrl and Alt, arrows, Home/End, PgUp/PgDn, `| ~ / -`. Text size buttons. On a phone,
  a live session goes full screen and follows the on-screen keyboard.
- **Limits:** idle close after 15 minutes (with a warning 60 seconds before), 2 hours at most, 2 sessions at once. All configurable.
- **Sign-out**, or the login expiring, closes that login's terminals immediately.
- Clear messages for: wrong password, lockout, session limit, sshd unreachable, key missing or unreadable, host key mismatch,
  key refused, idle close, max-time close, connection lost.
- A **Recent sessions** card (last 20 opens, closes and denied attempts). Only metadata is kept; typed input and output never are.

## How it works

```
Browser (xterm.js)                          hub-internal                              sshd on the Pi
POST /api/admin/terminal/sessions  ───────▶ check password (login rate limiter)
     {hostId, password}            ◀─────── one-time ticket (30 s, bound to login + host)
WS   /api/admin/terminal/ws        ───────▶ login cookie + Origin + Host checks
     first message {auth, ticket}  ───────▶ redeem ticket, check limits, SSH connect ──▶ hubshell shell (PTY)
     input / resize (JSON text)    ───────▶ relay ◀──────────────────────────────────▶ output (binary frames)
```

### Backend (`hub-internal/.../terminal/`)

| Class | Role |
|---|---|
| `TerminalProperties` | `hub.terminal.*` settings (`HUB_TERMINAL_*` environment variables) |
| `TerminalFeature` | The on/off decision. Refuses to turn on when `HUB_AUTH_MODE=none` or no password is set (logs an ERROR) |
| `TerminalController` | `GET /config`, `POST /sessions` (step-up, returns a ticket), `GET /audit` |
| `TerminalTickets` | One-time tickets: 32 random bytes, 30 s, bound to one login session and one host, swept lazily |
| `TerminalSessionManager` | Concurrency limit, idle warning and close, max length, closing a login's sessions, close-all on shutdown. Its one-second watchdog thread exists only while a session or handshake exists |
| `TerminalWebSocketHandler` | The protocol: ticket first within 5 s, binary output, JSON control, size limits, backpressure, `too_slow` detection |
| `TerminalHandshakeInterceptor`, `OriginCheck` | Feature switch and Origin check on the upgrade request |
| `TerminalLoginListener` | Closes terminals when the HTTP session ends (sign-out or the 12-hour expiry) |
| `SshConnector` | JSch connection: pinned host key checked **before** authentication, public-key auth only, no agent, port or X11 forwarding, keepalive |
| `KeyStore` | Reads the private key by **file name** from `{data}/ssh/`. Paths, `..` and symlinks leading out of the folder are refused |
| `HostKeyFingerprint` | OpenSSH `SHA256:` fingerprints, constant-time comparison |
| `TerminalAudit` | `terminal_audit` table (migration `V3__terminal_audit.sql`), last 1,000 rows, a failed write never breaks a session |
| `HostKeyCli` | `java -jar hub-internal.jar terminal-hostkey <host>[:port]` prints the fingerprint to pin |

Changes to existing code were kept small:

- `ConfigParser` / `DashboardConfig`: the optional `terminals:` key, validated with line-numbered messages.
- `DashboardController`: `/api/dashboard` gains `features.terminal`.
- `HostAllowlist`: new `isConfiguredName()`.
- `InternalApplication`: the new CLI command.
- `pom.xml`: `spring-boot-starter-websocket` and JSch; MINA SSHD and eddsa in test scope only.

### Configuration

```yaml
# dashboard.yaml
terminals:
  - id: pi
    label: Raspberry Pi
    host: 127.0.0.1
    port: 22
    user: hubshell
    key: hub_terminal_ed25519   # file name inside $HUB_DATA/hub/ssh/, never a path
    hostKey: "SHA256:..."       # pinned SSH host key
```

`deploy/docker-compose.yml` and `deploy/.env.example` gain `HUB_TERMINAL_ENABLED` (default `false`), `HUB_TERMINAL_IDLE_SECONDS`
(900), `HUB_TERMINAL_MAX_SECONDS` (7200) and `HUB_TERMINAL_MAX_SESSIONS` (2). No new volume: the key lives in the existing data volume.

### Frontend (`web/`)

- `src/internal/pages/TerminalPage.tsx`: loaded with `React.lazy`, so xterm.js is only downloaded when the page is opened. Nothing
  connects until Connect is clicked.
- `src/internal/terminal/keys.ts` and `protocol.ts`: pure key-mapping and protocol helpers, with tests.
- `App.tsx`: the route, nav entry and palette item, plus **Ctrl+K left to the shell** on this page (it is readline's
  "delete to end of line").
- `Modal` moved to `src/internal/components/Modal.tsx`, so the terminal page does not pull in CodeMirror. It is still re-exported from `ConfigPage`.
- Styles in a marked `terminal` section of `styles.css`; every class is prefixed `term-`. Light and dark themes both follow the app tokens.
- Bundle: the main internal chunk went from 282.3 to 283.1 KB. xterm is only in the `TerminalPage` chunk (348 KB, 89 KB gzipped).

### Tooling

- `tools/mock-ssh/mock_ssh.py`: a loopback-only SSH server (paramiko) that runs a real `bash` in a PTY. Its keys are generated per run
  in the same OpenSSH format `ssh-keygen` writes. It has its own tests (`test_mock_ssh.py`).
- `scripts/run-local.sh` starts the mock SSH server, pins its host key into the e2e config and enables the terminal with short idle
  times. It also no longer aborts when the portfolio export file is missing.
- `.gitignore`: `*.key` and `hub_terminal_ed25519*`.

## Security

| Threat | Control |
|---|---|
| Stolen session cookie | Password asked again for every session; the ticket is one-time, short-lived and bound to the login |
| Cross-site WebSocket hijacking | Same-site `Origin` required (plus `SameSite=Strict` and the ticket) |
| DNS rebinding | The existing Host allowlist also applies to the upgrade request (tested) |
| Password guessing through the step-up | Shares the login's rate limiter and lockout (tested) |
| Connecting somewhere unintended via the YAML | Only declared hosts; host key pinned; key referenced by name inside `ssh/` |
| Resource abuse | Session, idle, max-time, message-size (64 KiB) and output-queue limits |
| Secrets in logs | Tickets, passwords, keys and terminal content are never logged (a test checks the captured log output) |
| Running without a login | The feature refuses to turn on in `HUB_AUTH_MODE=none` |

**Accepted residual risk (as agreed in the plan):** anyone with the dashboard password gets a `hubshell` shell. If the
`hub-internal` container itself were compromised, the key in `/data/ssh` would give the same. `hubshell` has no sudo and no Docker access.

## Decisions made during the build

- **SSH library:** `com.github.mwiede:jsch` 2.28.7, a maintained fork released in August 2026, about 700 KB, pure Java. Verified here:
  it reads OpenSSH ed25519 keys with the plain JDK, its host-key hook runs before authentication, and PTY, resize, exit status and
  keepalive work against two independent servers (MINA SSHD and paramiko).
- **Origin check:** Spring's built-in WebSocket check compares the scheme too. It would refuse every connection behind
  `tailscale serve` (https outside, http inside), so it is replaced by `OriginCheck`. That check compares the host (and the port when
  the Host header has one) and also accepts names listed in `HUB_ALLOWED_HOSTS`.
- **Sign-out detection:** an `HttpSessionListener` instead of editing `AuthController`. This also covers the session expiring.
  Tomcat's own close (code 1008) for an ended login is recorded as `logout`.
- **Message size limits:** set per socket, not with a container bean. The bean needs a real servlet container, and it broke about
  140 existing MockMvc tests when first tried.
- **Disabled state:** `GET /config` answers 200 with `enabled:false`, so the page can explain why. `POST /sessions` answers 503.
- **Phone layout:** a live session is full screen on narrow screens.

## Defects found by the tests and fixed

1. The idle warning banner resized the terminal. The resize counted as activity and reset the idle timer, so the close came at about
   21 seconds instead of 12 in testing. The banner is now an overlay.
2. The container-bean approach broke the existing MockMvc tests (see above). Replaced before committing.
3. After Disconnect, the socket's close handler overwrote the "You closed the terminal." message.
4. On a phone the app header took about a third of the screen away from the terminal.
5. Ctrl+K opened the command palette instead of reaching the shell.
6. The terminal toolbar was centred instead of spanning the width.
7. In the mock tool only: Ctrl+C did nothing, because the mock inherited SIGINT as "ignored" from being started in the background.

## Test results

| Suite | Before | After | Result |
|---|---|---|---|
| Java (`mvn verify`) | 380 | 442 | All pass except `CheckersTest.tcpHonoursItsTimeout`, which fails identically before this branch in this environment (its "unreachable" address answers here) |
| `hub-public` | 26 | 26 | Pass, enforcer rule passes |
| Vitest | 62 | 82 | All pass |
| Playwright | 44 | 51 | The 7 terminal tests and every existing test that does not need portfolio data pass. The 13 portfolio-dependent tests could not run meaningfully here: the portfolio export lives in the git-ignored `.scratch/` |
| Python | ~20 | ~23 | Mock SSH tests pass |

New Java tests cover, against a real WebSocket and a real in-process SSH server:
- the step-up, ticket reuse, tickets from another login and expired tickets;
- a missing or late first message, malformed and oversized messages;
- bad or missing `Origin`, a wrong `Host`, and the upgrade without a login;
- the session limit, sign-out, a dropped socket closing SSH, the idle warning and close, a slow client (`too_slow`);
- host-key mismatch (no authentication attempted), audit without content, and no secrets in the logs;
- the disabled, `none` and `edit` login modes, and brute force sharing the login lockout.

One test (`TerminalApiTest.signingOutClosesThatLoginsTerminal`) failed once in a full run and passed in every later run. Its helper now
records every frame received, so a repeat will show the cause.

## Not verified yet

- The WebSocket through the real `tailscale serve` on the Pi, and over the LAN address.
- The real OpenSSH sshd on the Pi, including the `from="127.0.0.1"` key restriction.
- Android Chrome and Gboard input, and the installed phone app.
- Memory use on the Pi when idle and with 1 and 2 sessions (baseline 233 MiB idle).
- The Docker image build.
- The 13 portfolio-dependent browser tests (run `scripts/e2e.sh` on a machine with the export).

## Next steps (need the owner's approval)

1. Merge. Check the Flyway migration number: the Budget plan also claims `V3`, so whichever branch merges second must renumber.
2. Deploy `hub-internal`, taking a database backup first: the migration runs at startup.
3. On the Pi, follow README → *Terminal setup*: create `hubshell`, create the dedicated key, compare and pin the host key, add the
   `terminals:` entry, set `HUB_TERMINAL_ENABLED=true`.
4. Decide whether `hubshell` stays fully unprivileged or gets a sudo rule that asks for its password.
5. Try it from the phone through the `ts.net` address, and measure memory.
