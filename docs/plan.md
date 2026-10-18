# Homelab Hub — Development Plan

> This is the project's working plan and progress log. "The owner" is the repository author; dates are when things happened.

Working name: **homelab-hub** (rename freely). One app replacing three services on the Raspberry Pi:
`wol-server` (Spring Boot), `homepage` (gethomepage), and the portfolio (Next.js + FastAPI + external Postgres).

Status of this document: **plan plus a progress log (§18).** Phases P0 (partly) and P1 are done locally, and the Postgres export has been run. **Nothing has been deployed to the Pi.** Every claim is tagged:

| Tag | Meaning |
|---|---|
| **[V]** | Verified during this session by reading the real system (SSH, files, queries) |
| **[A]** | Assumption I made and have not verified. Each has a verification step in the plan |
| **[D]** | Decision you made in conversation |
| **[?]** | Open question that needs your answer before or during the build |

---

## 1. Decisions already made

| # | Decision | Source |
|---|---|---|
| D1 | Database: **SQLite**; data migrated from the external Postgres by an export script | [D] |
| D2 | Backend: **Java 17 + Spring Boot**. Pi resource measurement comes after the app works locally; if the numbers are bad we may rewrite the backend in FastAPI (see §13) | [D] |
| D3 | Frontend: **Vite + React SPA** (no Next.js, no SSR); public SEO does not matter | [D] |
| D4 | **Two containers**: `internal` (dashboard, WOL/Proxmox actions, editors) and `public` (read-only portfolio) on **separate ports** | [D] |
| D5 | Graph replaced by **(A) universal command palette** + **(B) skills-to-evidence explorer**. Timeline scrubber (C) and resume lens (D) are deferred. No LLM chat | [D] |
| D6 | Dashboard reachable **only from LAN and Tailscale**. Only the read-only portfolio goes through Cloudflare Tunnel | [D] |
| D7 | ~~No login for dashboard actions~~ **Revised:** the **dashboard requires the single password** (view and actions), plus a **confirmation step** for destructive actions in the UI. The legacy wol-server routes stay open (D15) | [D] |
| D8 | Dashboard config is a **YAML file editable in the browser** (not import/export) | [D] |
| D9 | Email, phone and IPs in the public portfolio are acceptable | [D] |
| D10 | Run **alongside** the old services on new ports first; deprecate old ones after verification | [D] |
| D11 | Scale: about 2× the current service count; one admin | [D] |
| D12 | **Single password** protects the dashboard and everything in it: viewing, power actions in the UI, and editing (portfolio + YAML). One login, one session. `AUTH_MODE` default is therefore `all` (was `edit`) | [D] |
| D16 | Tunnel routes for old hostnames are considered stale and out of scope. Residual risk of the open legacy routes (§9.5) is **accepted** | [D] |
| D17 | Postgres export uses the existing `public-user`. **No new role is created** | [D] |
| D18 | Skill-mapping decisions accepted as proposed (§18) | [D] |
| D19 | **Visual direction A "Signal"** chosen. **The portfolio is ONE page** (About, Experience, Projects, Education, Homelab, Contact) with a **collapsible skill panel on the right** that filters the whole page. It replaces the separate "skills explorer" page and the separate homelab pages (§8.2, §8.5, §8.6) | [D] |
| D13 | Keep the **existing hard `stop`** for guest stop. No switch to graceful `shutdown` | [D] |
| D14 | Portfolio **homelab pages are redesigned** to match the design of all other pages, so the whole app looks consistent | [D] |
| D15 | The **wol-server URLs stay open and working unchanged** (same paths, same methods, no login, no confirmation) so other apps can call them in future without any change. The confirmation step applies to the UI only. See §9.5 | [D] |

---

## 2. What exists today (verified)

### 2.1 Raspberry Pi 4 (`rpi4`, 192.168.10.101)
- aarch64, 7.6 GiB RAM, 39 GiB free disk, ufw **active**, Tailscale IP 100.64.0.5. **[V]**
- 12 containers: homepage, wol-server, cloudflared, grafana, prometheus, node-exporter, cadvisor, watchtower, portainer, guacamole, guacd, guacdb. **[V]**
- **Docker `stats` reports 0B memory** on this Pi (cgroup memory accounting looks disabled), so measurements must use `ps` / `/proc`, not `docker stats`. **[V]**
- wol-server JVM measured at **203 MiB RSS** (Java 17.0.2). **[V]**
- Ports 3010 and 3011 are not listening. Only 2000 and 3002 were found among the ports I checked. **[V]** (a full port audit is still needed, see §14 P0)

### 2.2 wol-server (Spring Boot, ~1,670 lines incl. tests) **[V]**
- Runs with `network_mode: host`, port 3002, image `arm64v8/openjdk:17`, watchtower disabled.
- **No authentication anywhere.**
- Endpoints:
  - `GET /wake/mac/{mac}?target=` and `POST /wake` (UDP broadcast to 255.255.255.255:9)
  - `GET|POST /toggle/{node}/{lxc|qemu}/{id}`
  - `GET|POST /node/{node}/shutdown` and `/reboot`
  - `/actuator/health`
- State-changing actions are reachable by plain `GET` (prefetching and cross-site requests can trigger them).
- Toggle only works when the guest is `running` or `stopped`, otherwise it returns 409.
- The toggle's "stop" uses Proxmox action string `stop`, and the response reports a **predicted** new status (`RUNNING`/`STOPPED`) rather than the real one. **[V]** from code. That Proxmox `stop` is a hard power-off and `shutdown` is graceful is my knowledge of the Proxmox API, **[A]**, to be verified in P0.
- Proxmox is called with an API token from `.env`, with TLS verification bypassed.
- `Dockerfile` does `COPY .env ./`, so **the Proxmox token is baked into the image layers**. **[V]** The new app must not do this.
- `logging.level.org.springframework.web=DEBUG` is on in production config. **[V]**

### 2.3 homepage (gethomepage) **[V]**
- Container on port 2000, config in `~/homepage/config` (YAML), no auth.
- `services.yaml` groups: **Proxmox** (Proxmox VE, PBS, nested *Raspberry Pi* group with 5 links), **Applications** (portfolio, XWiki, Trilium, Open WebUI, nested *Developer Tools*), **Media & Downloads** (5), **Power Management** (WOL and shutdown/reboot links, nested *Container Toggles* with 6 entries).
- About 35 entries in total. Nesting goes 2 levels deep.
- Widgets in use: `proxmox`, `proxmoxbackupserver`, `filebrowser`, `qbittorrent`. Info widgets: `resources` (cpu, memory, disk, cputemp, uptime, network) and `search` (Google). **[V]**
- Status uses the `ping:` field (host-only, so ICMP-style checks).
- **Power buttons are just links that call wol-server GET endpoints** from the browser.
- **Credentials for four widgets are in plaintext in `services.yaml`.** **[V]**
- `HOMEPAGE_ALLOWED_HOSTS` includes `homepage.example.com`. **[V]** This suggests homepage may already be reachable through the Cloudflare tunnel. **[?]** I cannot see the tunnel's public hostnames (token mode, rules live in the Cloudflare dashboard). If it is exposed, an unauthenticated dashboard is public today. Check this in P0.
- Old backup files accumulate in the config dir (`services.yaml.bak`, dated `.bak` files). The new app should version these properly.

### 2.4 cloudflared **[V]**
- Container in token mode on its own bridge network `cloudflared`; metrics port 60123 published on the host.
- Ingress rules are not visible from the Pi. **[?]**

### 2.5 Portfolio (LXC 100 on pve, 192.168.10.130) **[V]**
- Compose project at `/root/tabishtariq`: `frontend` (Next.js 16 + React 19 + Tailwind 4, port 3000, 115 MiB) and `backend` (FastAPI, port 8000, 101 MiB). No volumes.
- Backend: FastAPI + SQLAlchemy 2 + Alembic. Auth is a bcrypt password check that issues a JWT stored in an httpOnly cookie. The frontend proxies login through Server Actions.
- Public routers (`/api/...`) and admin routers (`/api/admin/...`) are already separate. The admin UI is driven by a generic registry (`resources.ts`).
- Frontend pages: home, about, contact, academics, skills, experience(+detail), projects(+detail+deliverable), homelab (proxmox, raspberry-pi, network), admin.
- The graph is `components/diagram/*` (React Flow + dagre, ~580 lines) plus a `diagrams` table.
- The frontend `AGENTS.md` warns that this Next.js version has breaking changes. The SPA choice avoids that dependency.

### 2.6 Portfolio database (external Postgres) **[V]**
- Host `192.168.10.198:5432` (LXC 102, PostgreSQL 15.18), database **`webapps.prod`**, schema **`portfolio`**, user `public-user`.
- The portfolio README says `webapps.test` / `dev-user`. **The README is stale.** The running container uses the values above.
- The password is in `backend/.env` on LXC 100 and is **not** recorded here.
- Row counts: profile 1, experiences 3, projects 4, project_case_studies 1, project_deliverables 8, academic_items 7, skill_groups 8, skills 48, homelab_devices 3, homelab_containers 7, homelab_services 10, homelab_network_entities 7, diagrams 8, admin_users 1.
- **The whole dataset is about 110 rows.** Anything that treats it as "big data" is over-engineering.
- `experiences.skills`, `projects.tags` and `project_case_studies.platform` are Postgres `text[]` arrays, and `project_deliverables` has a single `tag` text plus a `body` (no skill list). **[V]** from `information_schema`.
- Experience `duration` is a display string (`'May 2025 – Present'`), not structured dates.
- **Skills are only half-normalized.** A `skills` table exists (48 rows in 8 groups), but experiences store skills as free strings (`'AWS S3 / Glue'`, `'MERGE REMOVE DUPLICATES'`) and projects store `tags` as free strings. These do not match the `skills.label` values (`'AWS S3'`, `'AWS Glue (Catalog & Jobs)'`). The skills explorer (§8.2) therefore needs a reviewed mapping step, not just a foreign-key migration.

---

## 3. Assumptions (unverified, each with how it gets checked)

| # | Assumption | Verified in |
|---|---|---|
| A1 | ~~A bridge container cannot deliver WOL broadcast~~ **Verified in P0** (see P0 results) | done |
| A2 | An ICMP ping from a non-root JVM user is not available. `InetAddress.isReachable` falls back to a TCP echo probe, which is unreliable. ICMP would need the `ping` binary and `NET_RAW` | P0 spike |
| A3 | Docker-published ports bypass ufw rules, while host-network listeners are governed by ufw | P0, inspect iptables and test from another host |
| A4 | Flyway community supports SQLite, and `sqlite-jdbc` + HikariCP behave with a small pool | P1 spike |
| A5 | `eclipse-temurin:17-jre` has an arm64 image that runs on the Pi | P0 |
| A6 | Building the arm64 image with buildx from the dev machine works (wol-server's README says the JAR must be built locally "for ARM64"; I did not verify why) | P0 |
| A7 | cloudflared (bridge network) can reach `192.168.10.101:3011`, so the tunnel needs no change to the cloudflared container | P8 |
| A8 | Java's `WatchService` picks up changes to a bind-mounted YAML file on the Pi's filesystem | P2 |
| A9 | Root disk `/dev/sda2` is not an SD card, so SQLite write wear is not a concern | P0 (check `lsblk`) |
| A10 | The existing Proxmox API token has power and guest-control permissions, and may have far more than needed | P0, inspect token ACLs in Proxmox |
| A11 | The Proxmox node name is `pve` and the guests are LXC 100, 101, 102, 103, 210, 999 (from `pct list` and the current toggle links) | already largely verified, re-check in P0 |
| A12 | ~~Nothing outside homepage calls wol-server URLs~~ **Resolved by D15.** We don't need to find callers, because the URLs keep working. The remaining assumption is that a browser sends `Sec-Fetch-Site: cross-site` on cross-site requests and non-browser clients do not send it (used by the §9.5 mitigation) | P4 test with a real browser and with `curl` |

---

## 4. Goals and non-goals

**Goals**
1. One deployment (2 containers) replacing wol-server, homepage and the portfolio.
2. A landing page with all current homepage functionality: groups, links, icons, status, widgets, search, power actions. Better design, and easy to add services.
3. Portfolio content editable from the same app.
4. A public, strictly read-only portfolio on its own port, for Cloudflare Tunnel.
5. Command palette and skills-to-evidence explorer in place of the graph.
6. Safe power actions with confirmation, honest results, and clear failure states.
7. Portability. If Pi measurements are bad, only the backend is replaced (§13).

**Non-goals (v1)**
- Multi-user, roles, SSO.
- LLM chat, timeline scrubber, resume lens.
- Replacing Grafana, Prometheus, Portainer or Guacamole (they stay as linked services).
- Docker socket integration (homepage does not use it today, so there is no parity requirement). **[V]** `docker.yaml` is all commented out.

---

## 5. Architecture

### 5.1 Overview

```
                     Internet
                        │  (Cloudflare Tunnel, only this path)
                        ▼
              cloudflared (existing, unchanged)
                        │  http://192.168.10.101:3011   [A7]
                        ▼
┌───────────────────────────────────────────────────────────────────────┐
│ Raspberry Pi 4                                                        │
│                                                                       │
│  ┌──────────────┐  no DB, no secrets, no Proxmox                      │
│  │ hub-public   │  serves public SPA bundle + /api/public/*           │
│  │ :3011 (bridge│  reads snapshot dir (read-only mount)               │
│  └──────▲───────┘                                                     │
│         │ snapshot.json (atomic rename)                               │
│  ┌──────┴───────────────────────────────┐                             │
│  │ hub-internal   :3010                 │◄── LAN 192.168.10.0/24      │
│  │  internal SPA + /api/*               │◄── Tailscale 100.x          │
│  │  SQLite (WAL)  dashboard.yaml        │                             │
│  │  Proxmox client, WOL sender          │── UDP 9 broadcast ─► LAN    │
│  │  status poller, audit log            │── HTTPS ─► Proxmox :8006    │
│  └──────────────────────────────────────┘                             │
└───────────────────────────────────────────────────────────────────────┘
```

The important property: **the public container physically lacks the dangerous capability.** It has no Proxmox token, no SQLite file, no YAML with credentials, and no admin routes compiled in. It only sees a sanitized snapshot file.

### 5.2 Why a snapshot instead of a shared DB
- SQLite in WAL mode needs write access to its `-shm` file even for readers, and behaves badly on read-only mounts. **[A]**, so I avoid the problem altogether.
- The dataset is tiny (~110 rows), so a whole-portfolio JSON snapshot is small (expected tens of KB, to be measured).
- Every portfolio write in `internal` regenerates `snapshot.json` (write temp file, then atomic `rename`). `public` reloads it when the mtime changes, and serves it from memory.
- Trade-off: public content lags by the reload interval after an edit. Acceptable, and I will target under 5 seconds.
- Mount a **directory**, not a single file. An atomic rename replaces the inode, and a single-file bind mount would keep pointing at the old one.

### 5.3 Ports and network exposure
- `internal` on **3010**, `public` on **3011**. Both are proposals. The old services stay on 3002 and 2000 during the parallel run.
- `internal` uses **host networking** (needed for WOL broadcast, assumption A1). This means it is not protected by Docker's port publishing, so exposure is governed by **ufw**: allow 3010 only from `192.168.10.0/24` and `tailscale0`, deny otherwise. **[V]** ufw is active. The exact current rules have not been read (needs sudo).
- Alternative if the spike shows a sidecar works: keep the app on a bridge network and put only a tiny WOL sender in host mode. This shrinks the host-network surface. Decided in P0.
- `public` runs on a bridge network with `3011` published.
- Application-level guards on `internal`, even though the network is trusted:
  - `Host` header allowlist (LAN IP, Tailscale IP and MagicDNS name, `localhost`). This stops DNS-rebinding attacks from a page open in your browser. Homepage has the same idea via `HOMEPAGE_ALLOWED_HOSTS`.
  - `Origin` check on all state-changing requests.
  - A custom required header on `POST` requests.
- HTTP over LAN means the `Secure` cookie flag is unusable. Cookies are `HttpOnly; SameSite=Strict`. Over Tailscale HTTPS (via `tailscale serve`) this can be tightened. That is optional.

### 5.4 Maven multi-module layout

```
homelab-hub/
├─ pom.xml
├─ hub-snapshot/         DTO records for the snapshot + Jackson only. No Spring, no JDBC.
├─ hub-internal/         Spring Boot app: everything privileged
├─ hub-public/           Spring Boot app: depends ONLY on hub-snapshot + spring-web
├─ web/                  npm workspaces
│  ├─ packages/ui        shared components, tokens, palette, skills explorer
│  ├─ apps/internal      dashboard, editors, actions
│  └─ apps/public        portfolio views only
├─ tools/export-portfolio/   one-off Python Postgres → JSON exporter
├─ contract/             language-neutral HTTP contract tests (§13)
├─ deploy/               compose.yml, ufw notes, env examples
└─ docs/
```

- The public module's dependency list is enforced in CI: the build fails if `hub-public` pulls in JDBC, Proxmox client, Spring Security or the YAML libs.
- The public bundle is built from `apps/public` and **does not contain** admin, dashboard or action code.

### 5.5 Technology choices

| Concern | Choice | Notes |
|---|---|---|
| Runtime | Java 17, Spring Boot 3.x | Java 17 by your decision. Spring Boot 3 requires 17+ |
| Base image | `eclipse-temurin:17-jre` (arm64) | Replaces the old `arm64v8/openjdk:17` (17.0.2, **[V]** in use today). Verify in A5 |
| Persistence | Spring `JdbcClient` + `sqlite-jdbc` + Flyway | I chose plain JDBC over Hibernate, because Hibernate's SQLite support is a community dialect and this schema is small. JSON columns are stored as TEXT with Jackson. **[A4]** |
| SQLite settings | WAL, `foreign_keys=ON`, `busy_timeout`, `synchronous=NORMAL`, pool size small | Set per connection via Hikari init SQL, since `foreign_keys` is per-connection |
| HTTP client | Spring `RestClient` with a custom `SSLContext` | Pin the Proxmox certificate fingerprint instead of trust-all |
| Security | Spring Security only in `internal`: CSRF, security headers, optional edit password | See §9 |
| Scheduling / push | `@Scheduled` status poller + Server-Sent Events to the SPA | SSE is simpler than WebSocket for one-way status |
| Frontend | Vite, React 19, TypeScript, Tailwind 4 | Reuses the portfolio's tokens and `ui/*` components where they fit |
| Editor | CodeMirror 6 + the `yaml` npm library | The `yaml` library's Document API preserves comments, so form-assisted edits do not destroy them |
| Search | A small client-side fuzzy library (MiniSearch or Fuse) | ~100 portfolio docs + ~70 service/action docs |
| Tests | JUnit 5, WireMock (Proxmox), Playwright (e2e), Hurl (contract) | See §12 |

---

## 6. Data model (SQLite)

Managed by Flyway. The schema lives in exactly one place (the Java app). The Python exporter emits JSON and never writes SQLite.

### 6.1 Portfolio
```
profile                 singleton (CHECK id = 1): name, title, tagline, location, email,
                        phone, brand, status, summary, bio[], socials{}, stats[]  (JSON as TEXT)
experiences             id, slug UNIQUE, role, company, client, duration_label, start_date NULL,
                        end_date NULL, location, summary, highlights[], sort_order
projects                id, slug UNIQUE, kind (flagship|small), title, description, repo_url,
                        live_url, type, sort_order
project_case_studies    id, project_id FK, ...(as source)
project_deliverables    id, project_id FK, deliverable_key, title, tag, ...(as source)
academic_items          id, ...(as source)
skill_groups            id, category, icon, sort_order
skills                  id, group_id FK, label UNIQUE, sort_order
skill_aliases           alias UNIQUE, skill_id FK             -- from the reviewed mapping
experience_skills       experience_id, skill_id               -- PK on both
project_skills          project_id, skill_id
unlinked_tags           owner_type, owner_id, text            -- strings with no skill match
homelab_devices / homelab_containers / homelab_services / homelab_network_entities
                        as source (JSON columns become TEXT)
```
- There is **no `deliverable_skills` table**: deliverables carry no skill data in the source **[V]**, so I dropped it from the plan.
- `start_date` / `end_date` are nullable and unused in v1. They leave room for the deferred timeline (option C) without a later migration.
- **`diagrams` is not migrated.** The 8 diagrams (`hub, experience, redshift, proxmox, rpi, network, skills, homelab`) are archived once as JSON in the export for reference. The graph pages are replaced (§8).
- **`admin_users` is not migrated.** Password hashes are not exported. The new app bootstraps its own admin credential from env if edit-password mode is enabled (§9.2).
- Homelab pages (proxmox / raspberry-pi / network) previously relied on diagrams. They are **redesigned** (D14) as views over the `homelab_*` rows. See §8.6.

### 6.2 Operational tables
```
audit_log        id, ts, action_id, target, result (ok|failed|denied|timeout), detail,
                 client_ip, user_agent
config_versions  id, ts, sha256, content TEXT, source (ui|file|import), note
                 -- last N=50 versions of dashboard.yaml
kv               key, value                            -- small state (e.g. last snapshot hash)
```

### 6.3 Backup and durability
- Nightly SQLite online backup (`VACUUM INTO` to a dated file) with 14-day retention on the data volume.
- The public snapshot is also a full plain-JSON export of the portfolio, giving a second, human-readable backup.
- Document restore in `docs/`. Test a restore in P5, not "someday".
- Volume: a named Docker volume or a host directory. Put it on the same disk as your other data. Wear check in A9.

---

## 7. Dashboard configuration (YAML)

### 7.1 Source of truth
- One file, `dashboard.yaml`, on the data volume. The backend treats it as **opaque text plus validation**, so comments and formatting survive editing.
- Structure is a superset of homepage's `services.yaml` so your existing content converts by paste. Groups nest to arbitrary depth (your current data uses 2 levels **[V]**).

### 7.2 Sketch (illustrative, unvalidated)
```yaml
version: 1
title: "Tabish's Homelab"
theme: { mode: auto, accent: terracotta }
portfolio: { href: /portfolio, label: "Portfolio" }

widgets:
  - resources: { cpu: true, memory: true, disk: /, cputemp: true, uptime: true, network: true }
  - search:   { provider: google }

groups:
  - name: Proxmox
    items:
      - name: Proxmox VE
        icon: proxmox
        href: https://proxmox-pve.example-tailnet.ts.net
        widget: { type: proxmox, url: https://192.168.10.102:8006, node: pve,
                  username: "${secret:PVE_USER}", password: "${secret:PVE_PASS}" }
      - name: Raspberry Pi
        group:                       # nested group
          - { name: Grafana, icon: grafana, href: "http://192.168.10.101:3000/" }

actions:                             # replaces the wol-server URLs
  - id: wol-proxmox
    label: Wake Proxmox
    type: wol
    mac: "AA:BB:CC:DD:EE:01"
    status: { icmp: 192.168.10.102 }
  - id: pve-shutdown
    type: proxmox-node
    node: pve
    command: shutdown
    confirm: type-name               # click | hold | type-name
  - id: lxc-100
    label: Apps LXC
    type: proxmox-guest
    node: pve
    kind: lxc
    vmid: 100
    stop_mode: stop                  # stop (hard, existing behavior, default) | shutdown (graceful)
    status: { icmp: 192.168.10.130 }
```
The exact schema is finalized in P2 and published as a JSON Schema. The JSON Schema drives editor validation, backend validation, and the contract tests.

### 7.3 Editing in the browser
- CodeMirror 6 editor with schema-aware validation, inline errors, and a **diff view before save**.
- **Form-assisted "Add service" / "Add action"** dialogs. They edit the YAML text in the editor buffer via the `yaml` Document API (comments preserved), so adding a service does not require hand-writing YAML. They also do not silently reformat the file.
- Save flow: `POST /api/config/validate` (dry run) → `PUT /api/config` with the hash of the version you loaded (`If-Match`). A mismatch means someone else changed it (e.g. via SSH), and the UI offers a diff and merge instead of overwriting.
- Backend writes atomically (temp file + `fsync` + rename) and records a `config_versions` row. Rollback UI restores any of the last 50 versions.
- **File edited by hand or by another tool:** a file watcher (A8) reloads it, and validates it. If the new file is invalid, the app keeps serving the **last-known-good** config, shows a red banner with the parse error and line, and never crashes or blanks the dashboard.
- Unknown keys are warnings, not errors, so a typo doesn't take the whole dashboard down.

### 7.4 Secrets
- Today four widgets keep credentials in plaintext YAML **[V]**. In the browser editor that would show them on screen.
- YAML references secrets by name: `${secret:NAME}`. Values come from environment variables or a `secrets.env` file (mode 600, outside the editable YAML, not in the image and not in git).
- The editor and the version history never contain secret values. The API never returns them.
- Secrets are resolved server-side only. Widget calls are made by the backend, never by the browser.
- **Migration note:** the existing plaintext credentials must be moved to `secrets.env` by hand during P2. I will not copy them into any file myself.

### 7.5 Widgets and status
- Widget types to implement for parity: `proxmox`, `proxmoxbackupserver`, `filebrowser`, `qbittorrent`, `resources` (+ `search`). Adding a new type is one Java class implementing a `Widget` interface plus a React renderer.
- **Resources widget:** needs host CPU, memory, disk, CPU temperature, uptime and network. Inside a container that requires read-only mounts of `/proc` and `/sys` (host-mounted, e.g. `/host/proc`), plus a thermal zone path. Verify the temperature path on the Pi in P4. **[A]**
- **Status checks:** a per-item `status:` with `http`, `tcp` or `icmp`. Backend poller with per-check timeout (3 s), bounded concurrency, and a cache. Results reach the UI over SSE.
  - `icmp` needs a decision from the A2 spike: the `ping` binary plus `NET_RAW`, or a fallback to a TCP probe of a known port (e.g. Proxmox 8006).
  - States are `up`, `down`, `unknown` (never checked or the check errored) and `degraded` (e.g. HTTP 5xx). `unknown` is distinct from `down`, so a broken poller doesn't look like everything is offline.
- **Icons:** homepage resolves names like `proxmox.svg` and `mdi-download-box` from CDN icon sets. The new app supports local `/images/*`, and cached lookups of the same icon sets, and falls back to a letter avatar offline. **[?]** whether a build-time bundled subset is preferable.

---

## 8. Features

### 8.1 Command palette (Ctrl/Cmd+K)
- Index built server-side and cached in the client:
  - **Public build:** portfolio entities only (profile sections, experiences, projects, deliverables, skills, academics, homelab items).
  - **Internal build:** the above plus services, groups and **actions** ("Wake Predator", "Toggle Media LXC").
- Actions launched from the palette go through the **same confirmation flow** as buttons (§9.1). There is no shortcut around it.
- Fuzzy matching with recency, keyboard-only operation, screen-reader labels, and an empty state.

### 8.2 Skill panel (revised by D19: a panel on the single portfolio page, not a separate page)
**Current design (mockup `docs/mockups/portfolio-single-page.html`):**
- Right-hand panel, **collapsible** to a slim rail that shows the active-filter count. On phones it becomes a slide-in drawer opened by a floating "Skills" button.
- One search box filters both the skill list and the page text ("deadlock" finds the matching highlights and deliverables). Skills are grouped in accordions. **Match all / Match any**, and **Hide others / Dim others** are user options.
- The whole page reacts: non-matching roles, projects, deliverables, homelab items and education entries hide (or dim). Sections with no match collapse to a single "no matches" header line. The section nav shows per-section counts.
- Homelab items are filtered at the container/service level, so picking "Docker / Compose" shows the Raspberry Pi services that use it.
- **Proposed data addition (not decided):** homelab devices, containers and services have **no skill links in the source data**. The mockup links them by a hand-written mapping (e.g. Grafana → Prometheus / Grafana). That raises coverage from 27 to 37 of 62 skills and makes the Infrastructure & DevOps group usable. It needs a `homelab_skills` link table (or three) plus your review of the mapping.
- Education and About/Contact are not linked to skills, so any skill filter hides them (text search still finds them). Say if you want education items tagged too.

**Original design (superseded):** a separate explorer page with evidence cards.
- Picking one or more skills (AND filter) shows the experiences, projects and deliverables that used them, and **co-occurring skills** as further chips. Chips are grouped by `skill_groups`.
- Honest about sparse data: with 3 experiences, 4 projects and 8 deliverables **[V]**, many of the 48 skills will have no linked evidence. Those skills are shown dimmed as "listed, no linked project" instead of hidden, and there is a visible "evidence coverage" indicator per skill. It should not look richer than the data is.
- **Data prerequisite (from §2.6):** experience skills and project tags are free text and do not match `skills.label`. The export script therefore produces a **review file** (§10). You approve the mapping before import. Nothing is auto-merged silently.

### 8.3 Portfolio editing (internal only)
- Generic CRUD screens driven by a registry, the same pattern as the current `resources.ts` (adding a content type is one registry entry). Skills use a picker against `skills` and update the link tables.
- Every successful write regenerates the public snapshot (§5.2) inside the same request. If the snapshot write fails, the DB change **still commits**, an alert is logged and shown in the UI ("public site out of date, retry"), and the snapshot job retries with backoff.
- Validation: server-side bean validation, unique slugs, and a delete guard (deleting a skill that has links asks for confirmation and states the count).
- Concurrency: optimistic locking via an `updated_at`/version column. A second tab editing the same record gets a conflict message, not silent overwrite.

### 8.4 Landing page
- Sections, top to bottom: title and search, quick-action strip (pinned actions), widget row, service groups (collapsible, nested), portfolio card linking to the portfolio view, footer with app version and config status.
- Card states: up, down, unknown, and a loading skeleton. Each card shows the last-checked time on hover.
- Light and dark themes, responsive down to a phone. Keyboard navigable.
- **Before building the UI:** produce two or three static mockups for you to pick from (P3). I have not designed anything yet, and I do not want to invent the visual design in this document.

### 8.6 Homelab section (redesign, D14; now a section of the single page, D19)
- The three pages (Proxmox, Raspberry Pi, Network) stop being diagram-driven. They are rebuilt from `homelab_devices`, `homelab_containers`, `homelab_services` and `homelab_network_entities` using **the same shared components and tokens as every other page** (`packages/ui`): same page header, cards, chips, spacing, typography and theme. No page-specific one-off styles.
- Consistency is enforced structurally, not by eye: these pages may only use components exported from `packages/ui`. A lint rule blocks ad-hoc colors and spacing values that are not design tokens.
- Content per page: device summary and specs, containers/guests with purpose, resources and status, services with host ports and function, network entities (ISP, VLAN, switch). Every field that exists in the source data is shown. Nothing is dropped silently.
- The homelab portfolio content is static data edited by you. It is **separate from live dashboard status** (§7.5). I am not merging the two in v1, and won't show live status on the public pages, since that would leak liveness information to the internet.
- The mockup review (P3) covers these pages together with the landing page and portfolio pages, so all pages are chosen as one design system, not separately.

### 8.5 Public portfolio
- Read-only SPA from `hub-public`: **one page** with sections About, Experience, Projects, Education, Homelab, Contact, a sticky section nav, the collapsible skill panel (§8.2) and the portfolio-only command palette. The internal app renders the same page with edit buttons (§8.3).
- Only `GET /api/public/portfolio` (whole snapshot, ETag + `Cache-Control`), `GET /api/public/health` and static assets. No other routes exist in that app.
- Abuse controls: request rate limiting in-app (bucket per IP using `CF-Connecting-IP` only when the request comes from the tunnel **[A]**), small request-size limits, security headers (CSP, `X-Content-Type-Options`, `Referrer-Policy`). Cloudflare-side rate limiting is an additional **[?]** setting to enable in the dashboard.
- Contact details are exposed by decision D9.

---

## 9. Security design

### 9.1 Power actions: confirmation flow
The old model, "click a link → immediate `GET`", goes away.
1. UI calls `POST /api/actions/{id}/prepare`. The server returns a **single-use confirmation token** (random, bound to the action id, expires in 30 s) plus a **plain description of the impact** (target host, current state, and for a Proxmox node shutdown/reboot, the count and names of guests currently running).
2. UI shows a modal with that description. Confirmation strength comes from config: `click`, `hold` (press and hold ~1.5 s) or `type-name` (type the target name). Node shutdown and reboot default to `type-name`.
3. UI calls `POST /api/actions/{id}/confirm` with the token. The server executes, and records `audit_log`.
- **The new API and UI never change state on `GET`.** The single exception is the legacy compatibility API in §9.5, which is deliberately kept open by your decision (D15).
- The token is one-time and short-lived, held in memory. A restart invalidates outstanding tokens (harmless).
- WOL is low-impact and defaults to `click`. Actions marked `confirm: none` are allowed only for WOL.

### 9.2 Who can do what
- **The whole dashboard requires the single password** (D7 revised, D12): viewing, power actions in the UI, and editing. Network location (D6) is a second layer, not the only one. Reason: the YAML defines actions and references secrets, and the portfolio is publicly published, so anyone on your LAN (a guest, a compromised IoT device) could otherwise rewrite the public site or trigger an action.
  - Implemented as `AUTH_MODE = none | edit | all`, default **`all`**. The other modes remain a config change, not a code change.
  - One password, stored as a bcrypt hash supplied by env. Login is rate limited, and the session cookie is `HttpOnly; SameSite=Strict`. There is no username, since there is one admin.
  - Password reset is done by replacing the hash in the env file and restarting. There is no email flow.
  - An expired session while editing keeps the unsaved form or YAML text in the browser and asks you to log in again before saving. It does not discard the work.
- The **legacy wol-server URLs** are outside this login by design (§9.5).
- CSRF protection on all state-changing requests, plus the `Origin`/`Host` checks from §5.3.

### 9.5 Legacy wol-server compatibility API (D15)
**What is kept, unchanged, on the internal port only:**
```
GET|POST /wake/mac/{mac}?target=   and   POST /wake  {macAddress, target}
GET|POST /toggle/{node}/{lxc|qemu}/{id}   and   POST /toggle {node,type,id}
GET|POST /node/{node}/shutdown        GET|POST /node/{node}/reboot
GET      /actuator/health
```
- Same paths, methods, parameters and JSON response shapes, so other apps can call them with no change. These prefixes are reserved and never used by SPA routes.
- **No login, no confirmation token, no confirmation modal.** Behavior matches wol-server, including the hard `stop` (D13) and the 409 for a guest that is not `running`/`stopped`. The compatible `toggle` route keeps its blind-toggle semantics, whereas the UI uses desired-state (§11).
- They operate on any node/VMID/MAC the caller names, not only ones listed in `dashboard.yaml`, exactly like today **[V]**. The Proxmox token's own permissions are the real limit.
- They are **never** served by `hub-public`, and Cloudflare must never route to port 3010 (§5.3, P7, P8).

**What this decision costs (stated plainly, because it weakens the earlier design):**
- Anything that can send an HTTP request to the legacy routes can shut down or reboot Proxmox, with no authentication. **Correction (P0 measurement):** today wol-server's port 3002 is **not reachable from other LAN hosts**. ufw's default is deny-incoming and there is no allow rule for 3002, and I confirmed no response from my laptop (192.168.10.133) and from the Proxmox host. It answers on the Pi itself. Tailscale traffic is accepted by the `ts-input` chain, but my one Tailscale test from the laptop also got no response, so how you reach 3002 from your own devices is **[?]** unresolved. So opening these routes on a new port to the whole LAN **is a widening of exposure compared to today**, not equal to it. The ufw rule for that port is therefore a deliberate, explicit step (P6/P9), scoped to `192.168.10.0/24` and Tailscale, and never to "Anywhere".
- Because they work on `GET`, a **web page open in your browser** could trigger them with an image tag or a redirect, from any website, if your browser can reach the Pi. This exists today too. Host-header checks stop DNS-rebinding, but do not stop this.

**Mitigations that do not break other apps (none of them add a login):**
1. **Reject cross-site browser requests:** refuse requests with `Sec-Fetch-Site: cross-site`. Browsers add this header to cross-site requests. `curl`, n8n, scripts and other server-side callers do not send it, so they are unaffected. A link clicked on another port of the same host is `same-site` and still works. **[A12]** must be verified with a real browser and with `curl` in P4. It does not protect very old browsers that lack the header.
2. **Every legacy call is written to `audit_log`** with source IP, user-agent and result. So an unexpected shutdown is traceable.
3. **Per-target in-flight lock and a short rate limit** on the power routes, to stop rapid repeat calls.
4. **A config switch** `legacy_api: { enabled: true, allow_get_mutations: true }` lets you turn part or all of it off later without a code change. The default matches your decision (everything on).
5. Network exposure stays limited by ufw and Tailscale (§5.3).

**Port continuity:** at cutover (P9), after the old wol-server is stopped, the internal app can open a **second listener on 3002 that serves only the legacy routes**. URLs such as `http://192.168.10.101:3002/toggle/pve/lxc/100` then keep working unchanged for any future app. Until then, the legacy routes are available on 3010 and the old wol-server keeps 3002.

### 9.3 Proxmox access
- Token via env/secret file only. **Never** `COPY`ed into an image (the current wol-server image does this **[V]**).
- Recommend a **new least-privilege API token** for the hub with guest power permissions and node power management only. I have not inspected the current token's ACLs (A10).
- TLS: pin the certificate fingerprint (config value), instead of disabling verification. If the certificate is regenerated, actions fail closed with a clear "certificate changed" message and how to update the pin.

### 9.4 Other
- Disable the debug logging that wol-server ships with. Log to stdout in structured form. Never log secrets, tokens or full YAML.
- Run as non-root. Read-only root filesystem where possible, writable paths only for the data volume.
- `hub-public`: no secrets in its environment. The threat-model test: compromise the public container fully and you still get only the already-public snapshot.
- Dependency and image scanning in CI. Pin base image digests.
- **Existing exposure to check in P0:** whether `homepage.example.com` (or anything else internal) is routed through the tunnel today (§2.3).

---

## 10. Postgres → SQLite migration

### 10.1 Export script (`tools/export-portfolio/`, Python, one-off)
- **Runs where:** inside the existing backend container on LXC 100 (it already has SQLAlchemy and the credentials in its environment, so no password is copied anywhere), or on a dev machine over an SSH tunnel. Same code either way. I already ran a read-only probe this way in this session.
- **Credentials:** read from the container's own env at runtime (`POSTGRES_HOST/PORT/DB/USER/PASSWORD/SCHEMA`, current values in §2.6). The script never prints or writes them. Strongly recommended: a dedicated **read-only** Postgres role for the export. I do not know whether `public-user` is read-only. **[?]**
- **Read-only, repeatable:** `SET TRANSACTION READ ONLY`, a single consistent snapshot, no writes to Postgres.
- **Outputs (all git-ignored):**
  - `portfolio-export.v1.json`: versioned schema, all tables listed in §6.1 except `admin_users` and `diagrams`.
  - `diagrams-archive.json`: the 8 diagrams, kept only for reference.
  - `skill-mapping-review.csv`: every free-text skill and tag, with `exact_match`, `fuzzy_candidates` (with scores) and `unmatched` status.
  - `export-report.txt`: per-table row counts.
- The export file contains email and phone (D9 accepts them being public). Still treat the raw file as private and don't commit it.

### 10.2 Skill mapping review
- The review CSV is the interface. You mark each row `accept / map-to <skill> / new-skill / keep-as-free-text`. Near-duplicates like "AWS S3" and "AWS S3 / Glue" get flagged. A combined string like `'AWS S3 / Glue'` is proposed as a **split** into two skills, and never guessed silently.
- Strings that are not skills at all can be kept as free-text tags via `unlinked_tags` (decision `free`). Correction: I earlier guessed `'MERGE REMOVE DUPLICATES'` was not a skill. **It is a canonical skill label** (Data Engineering group) and matches exactly.
- **Real result of the run (§18):** 33 distinct strings: 7 exact, 0 split-exact, 9 fuzzy, 17 unmatched. Many project tags (Java, Spring, Hibernate, NumPy, Pandas, MATLAB, Arduino, ...) have **no canonical skill at all**, so the explorer needs new skills and probably new groups, which is a content decision for you.

### 10.3 Importer (Java, in `hub-internal`, as a CLI mode)
- `java -jar hub-internal.jar import portfolio-export.v1.json --mapping reviewed.csv [--dry-run]`
- Validates the schema version, runs in **one transaction**, idempotent (re-runs upsert by slug/key), and refuses to run into a non-empty DB without `--replace`.
- **Reconciliation:** compares the row counts from `export-report.txt` against SQLite after import, and fails loudly on any mismatch. Also prints a checksum per table.
- Failure handling: malformed JSON, unknown schema version, duplicate slugs, dangling foreign keys, invalid JSON fields. Each produces a specific message, and the transaction rolls back to leave the DB unchanged.
- The old Postgres and portfolio stay untouched throughout, so **rollback = do nothing**.

---

## 11. API surface (sketch; OpenAPI is the source of truth)

**hub-internal (:3010)**
```
GET  /api/dashboard                 parsed + secret-free config, resolved items
GET  /api/status/stream             SSE: status changes
GET  /api/widgets/{id}              widget data (server-side calls)
GET  /api/config                    raw YAML + hash        PUT /api/config  (If-Match)
POST /api/config/validate           dry-run
GET  /api/config/versions           POST .../{id}/restore
POST /api/actions/{id}/prepare      POST /api/actions/{id}/confirm
GET  /api/actions/{id}/state        live guest/host state (real, not predicted)
GET  /api/audit
GET  /api/portfolio/...             read (same shapes as public)
POST|PUT|DELETE /api/admin/portfolio/{resource}[/{id}]
POST /api/admin/portfolio/republish force snapshot regeneration
GET  /api/health                    liveness (process) and readiness (DB + config + snapshot)
POST /api/auth/login | logout       single-password edit session (D12)
+ legacy wol-server routes (§9.5)   /wake/**  /toggle/**  /node/**  /actuator/health
```
**hub-public (:3011)**
```
GET /api/public/portfolio      GET /api/public/health     static SPA
```

Proxmox guest control returns the **task id** (UPID) from Proxmox and polls the task to completion, instead of predicting the result as wol-server does today **[V]**. Outcomes surfaced to the UI: `succeeded`, `failed(reason)`, `timeout(still running)`, `host-unreachable`, `unauthorized`, `cert-changed`.

Idempotency (UI and new API): guest actions take a **desired state** (`running` / `stopped`) rather than a blind toggle. The "toggle" button is rendered from the observed state. Double-clicks and two-browser races cannot flip a guest twice. The legacy `/toggle` route (§9.5) keeps blind-toggle semantics for compatibility, but shares the same per-target in-flight lock.

**Stop behavior (D13):** guest stop keeps the existing action string `stop`, as wol-server does today **[V]** in code. `stop_mode` in the YAML defaults to `stop`. `shutdown` stays available as an opt-in per action, so it can be chosen later without code changes. Because a hard stop can lose data inside the guest, the confirmation modal for stop names the guest and says "immediate power-off". This is a warning only, not a behavior change.

---

## 12. Failure modes and edge cases

| Situation | Designed behavior |
|---|---|
| Proxmox host is asleep or off (normal after a shutdown) | Connect timeout 3 s, read timeout 10 s. Guests and PVE widgets show `host-unreachable`, not errors. The UI offers "Wake Proxmox". No retry storms (backoff and a circuit breaker) |
| Proxmox returns 401/403 | Distinct "token invalid or lacks permission" state. Never retried automatically |
| Proxmox certificate changed | Fail closed with "certificate changed", and how to update the pin |
| Guest in transitional state (`suspended`, `paused`, migrating) | Action refused with the state named. Wol-server also returns 409 here **[V]** |
| Proxmox task fails or hangs | Poll with timeout, report `failed` with the task log line or `timeout`, and audit it |
| Double-click, or two browsers acting at once | Desired-state semantics plus a per-target in-flight lock. The second request is rejected with "already in progress" |
| WOL sent but device never wakes | The packet send is reported as "sent", **not** as "woken". The UI then watches the status check and shows "no response after N seconds" (configurable). It never claims success it can't observe |
| WOL target on a different subnet or through Tailscale | Broadcast won't cross it. Documented limitation. Optional directed-broadcast or unicast target address per action |
| MAC address malformed | Rejected at config validation, not at click time |
| YAML invalid (from the UI, or edited over SSH) | UI save is blocked with line-level errors. A bad file on disk means last-known-good is kept, and a banner is shown |
| YAML edited concurrently in two places | `If-Match` hash conflict → diff and resolve. No silent overwrite |
| `${secret:X}` missing | The item renders with a "missing secret X" badge. The rest of the dashboard works |
| Widget upstream slow or down | Per-widget timeout and cached last value marked stale. One dead widget never blocks the page or the poller |
| Status poller overload (about 70 checks) | Bounded thread pool, per-check timeouts, jitter, and a cap on checks per interval |
| SQLite locked or busy | `busy_timeout` and short transactions. A write failure returns a clear 503 and the UI keeps the user's edit in the form |
| SQLite file corrupt or missing at start | Refuse to start with a clear message (no silent re-init). The restore path is documented. Readiness endpoint fails |
| Snapshot write fails / public reads a partial file | Atomic rename prevents partial reads. `public` keeps its last good in-memory copy, and logs. Internal shows "public out of date" |
| `hub-public` started before any snapshot exists | Serves 503 with a clear message, and readiness stays failing |
| Disk full | Detected, writes fail cleanly, and a dashboard banner appears. Backups and audit retention are bounded so they don't cause it |
| Clock skew / DST | Timestamps stored in UTC. Only the UI localizes them |
| Power loss mid-write | WAL and atomic renames protect the DB, YAML and snapshot. Verified by kill tests in P5 |
| Browser offline or backend restarting | The SPA shows a reconnecting state, and SSE auto-reconnects with backoff |
| Very long names, emoji, RTL text in service names | Layout tests (Playwright) for truncation and wrapping |
| Legacy route called by a browser from another website | Blocked when the browser sends `Sec-Fetch-Site: cross-site` (§9.5). Not blocked for clients that don't send it, by design, so other apps keep working |
| Legacy route hit repeatedly (loop bug in a calling app) | Per-target lock and short rate limit return 429 or 409 instead of hammering Proxmox. Every call is in the audit log |
| Legacy route for a node/VMID that doesn't exist | Same error shapes as wol-server (404 / 4xx-5xx with the JSON body), tested against recorded wol-server responses in P4 |
| Password session expires mid-edit | Unsaved form or YAML text is kept in the browser. Re-login, then save. Never silently drops edits |
| Wrong password repeatedly | Rate limit with backoff. A lockout must not block the legacy routes or power actions, which don't use the login |
| Public abuse: large request bursts | In-app and Cloudflare rate limiting. The endpoint is a cached in-memory read, so cost per request is minimal |

---

## 13. Portability and the resource-measurement gate

You want to measure on the Pi first and then decide whether to keep Java or move to FastAPI. To keep that switch cheap:
- **The HTTP contract is language-neutral.** OpenAPI spec is the source of truth. Contract tests (Hurl scripts in `contract/`) run against any backend implementation.
- **The data formats are language-neutral.** SQLite schema (plain SQL migrations), the snapshot JSON schema, and the `dashboard.yaml` JSON Schema. None depend on Java.
- **The SPA is unchanged** by a backend swap. It only speaks the OpenAPI contract.
- So a rewrite replaces `hub-internal` and `hub-public` only. Everything else carries over. The realistic cost of the switch is about the backend code, not the project.

### 13.1 Measurement plan (run after P5, on the Pi)
Baselines are captured **before** the new app runs (old stack: wol-server 203 MiB RSS **[V]**; homepage measured in P6; portfolio 101 + 115 MiB **[V]** but on a different host).

| Metric | How | Notes |
|---|---|---|
| Resident memory (idle, after warm-up, after load, after 24–72 h) | `ps -o rss` and `/proc/<pid>/status` (not `docker stats`, which reports 0B **[V]**) | Track drift over the soak for leaks |
| JVM heap and metaspace | JFR or `jcmd` (`VM.native_memory`) | Separates JVM overhead from live data |
| Startup time to ready | Timestamp from `docker start` to readiness OK | Cold and warm |
| CPU (idle, status poller active, under load) | `pidstat` or `/proc/<pid>/stat` sampled | Also check the Pi's temperature and `vcgencmd get_throttled` |
| Request latency p50/p95/p99 | `hey` or k6 against key endpoints (dashboard load, snapshot, palette index) | Internal and public separately |
| SSE cost | N concurrent SSE clients, memory and CPU | Realistic N is 1–3 |
| SQLite write latency and lock behavior | Scripted edits during polling | WAL checkpoints |
| Image sizes and pull/build time | `docker images` | |
| Recovery | Reboot the Pi, power-cut test, restart order | Time until healthy, no data loss |
| Combined footprint vs. the 3 services it replaces | Sum of both hub containers vs. the old sum | The headline comparison |

Tuning to try **before** concluding Java is too heavy: `-Xmx`, `-Xss`, `-XX:+UseSerialGC`, `-XX:MaxMetaspaceSize`, `-XX:TieredStopAtLevel=1`, AppCDS, and trimming Spring auto-configuration. GraalVM native image is a third option (built off-Pi, arm64 target), and I would not evaluate it unless the tuned JVM misses the thresholds.

### 13.2 Decision thresholds
These are **my proposals for you to change**, not measured facts:
- `hub-internal` steady RSS ≤ 350 MiB, `hub-public` ≤ 250 MiB.
- Both healthy within 60 s of a cold start.
- p95 read latency ≤ 200 ms on the Pi over LAN.
- No memory growth beyond 10% across a 48 h soak.
- No CPU throttling events on the Pi under normal load.

Outcome rubric: all met → keep Java. Missed only with default flags but met after tuning → keep Java with tuned flags. Still missed → decide on FastAPI rewrite of the two backend modules (or native image), using the contract tests to prove parity.

---

## 14. Delivery phases

Sizes are relative effort (S/M/L), not calendar promises. Each phase has an exit criterion. Nothing on the Pi is changed until P6.

**P0 — Verification spikes (S).** Read-only or throwaway, and answers the assumptions in §3.
- Test WOL broadcast from a bridge container vs host mode vs macvlan (A1). Wake a real device you are OK waking. Choose host-mode app vs sidecar.
- ICMP options from a JVM container (A2). Full port audit, ufw rules, and iptables behavior (A3). `lsblk` for storage type (A9).
- Temurin arm64 image and buildx cross-build (A5, A6). Flyway + SQLite + Hikari (A4).
- Inspect the Proxmox token's ACLs (A10). Check the Cloudflare dashboard for what hostnames are routed (§2.3).
- **Record wol-server's real responses** (status codes and JSON bodies) for success and each error case (bad MAC, unknown guest, non-toggleable state, Proxmox unreachable). They become the fixtures the legacy compatibility routes are tested against. Only actions that are safe to run may be captured (e.g. WOL of a device you are fine waking, and toggling a non-critical guest). Never a shutdown or reboot of the Proxmox host without your say-so.
- **Exit:** each assumption marked verified or refuted, and this document updated.

**P0 results so far (measured 2026-09-24):**

| Assumption | Result |
|---|---|
| A1 bridge can't broadcast to the LAN | **Verified.** A container on Docker's default bridge sent UDP broadcasts to `255.255.255.255` and `192.168.10.255`, and neither appeared on the Pi's `eth0`. The same script in host-network mode appeared on `eth0` for both addresses (captured with a raw-socket sniffer on the Pi). I did not confirm delivery to a receiver: my laptop received nothing even from the host-mode control, most likely Wi-Fi or its own firewall, which I could not inspect (no sudo). **Decision: `hub-internal` uses host networking** (same as wol-server today). The sidecar option is dropped |
| A2 ICMP from a container | **Partly verified.** Host `net.ipv4.ping_group_range` is `0 2147483647`, so unprivileged ICMP sockets are allowed, and the `ping` binary should work as non-root in a host-network container. Not yet tested inside the real image. Java's own `isReachable` is not relied on |
| A3 ufw vs Docker | **Verified.** Docker-published port 2000 answered from my laptop. Host-mode port 3002 did not. So host-mode listeners obey ufw and published ports bypass it. Consequence: `hub-internal` on 3010 needs an explicit ufw allow rule, and `hub-public` on 3011 (published) will bypass ufw, so it is reachable from the whole LAN by design |
| A5 Temurin arm64 image | **Verified.** `eclipse-temurin:17-jre` lists `linux/arm64/v8` |
| A9 disk type | **Verified.** Root is on a USB disk (`sda`), not the SD card. `/var/log` is on the SD card (`mmcblk0p2`), so avoid chatty logging there |
| Ports 3010/3011 | **Verified free** |
| Pi health baseline | 51 °C, `get_throttled=0x0` (no throttling) at the time of measurement |
| Local toolchain | Docker 29, Node 26, Python 3.14 present. **No local Java or Maven.** Java builds will run in a Maven container |
| Not yet done | A4 (Flyway + SQLite), A6 (buildx cross-build), A10 (Proxmox token ACLs), Cloudflare check (ignored per D16), wol-server response fixtures |

**P1 — Foundations (M).** Repo and Maven modules, Flyway schema, JDBC layer, CI (build, test, dependency-boundary check for `hub-public`), OpenAPI generation, contract test harness skeleton.

**P2 — Config engine (L).** YAML schema and validation, secrets resolution, file watcher and last-known-good, versions and rollback, `dashboard.yaml` converted from your current `services.yaml` (credentials moved to `secrets.env` by you).
- **Exit:** your real config loads, and 20+ malformed-config cases behave per §12.

**P3 — Dashboard UI (L).** Mockups for your pick first, covering the landing page, one portfolio page and a homelab page together, so the design is consistent across the app (D14). Then landing page, groups, cards, icons, themes, status over SSE, widgets, YAML editor with add-service forms, command palette (internal scope).
- **Exit:** feature parity checklist against §2.3, item by item.

**P4 — Actions (M).** WOL, Proxmox client (cert pinning), node power, guest desired-state with task polling, confirmation flow, audit log, mock Proxmox server tests covering every §12 row that concerns Proxmox.
- Legacy compatibility routes (§9.5) with the `Sec-Fetch-Site` guard, audit logging and rate limit.
- **Exit:** every current Power Management link has an equivalent UI action, **and** every legacy route returns the same status codes and JSON shapes as the recorded wol-server fixtures (P0), tested against a mock Proxmox. The cross-site guard is proven with a real browser and with `curl` (A12).

**P5 — Portfolio (L).** Export script, review CSV flow, importer with reconciliation, portfolio views, editor screens, skills explorer, snapshot publisher, `hub-public` with its own bundle, backup/restore drill, kill-during-write tests.
- **Exit:** the imported portfolio content matches the source row-for-row (report attached), and the public app serves it with the admin code absent from its bundle (checked by a test that greps the bundle and enumerates routes).

**P6 — Pi deployment, parallel run, and measurement (M).** Deploy as a separate compose project on 3010/3011 and apply the ufw rules. Capture the baselines and run the §13 measurements including the soak. Use it daily alongside homepage.
- **Exit:** measurements recorded against §13.2 thresholds, a written keep-Java or move-to-FastAPI decision.

**P7 — Hardening (S).** Security checklist (§9), a Host/Origin rejection test from another device, external port scan from outside the LAN against 3010, confirm nothing but 3011 is reachable via the tunnel path.

**P8 — Public exposure (S).** Add a public hostname in Cloudflare pointing to `http://192.168.10.101:3011` (A7). Enable Cloudflare rate limiting **[?]**. Test from the internet: portfolio works, and every internal path and port is unreachable.

**P9 — Cutover and deprecation (S).**
- Update your bookmarks. **Callers of the wol-server URLs need no change** (D15). After stopping the old wol-server, enable the legacy-only listener on port 3002 (§9.5) and confirm an unchanged URL such as `/toggle/pve/lxc/100` still works.
- Stop the old wol-server, homepage and the portfolio containers, but **keep the images, configs, and Postgres data** for a defined retention period (e.g. 30 days) before deleting anything.
- Update the Cloudflare route if the portfolio hostname changes.
- **Rollback at any point before deletion:** start the old containers again.

---

## 15. Testing strategy

- **Unit:** WOL magic packet bytes (6 × 0xFF + 16 × MAC) and MAC parsing. YAML validation (including a malformed corpus). Confirmation-token lifecycle (expiry, reuse, wrong action). Skill mapping logic.
- **Integration:** WireMock Proxmox (401, 403, 500, slow responses, TLS mismatch, task failure and timeout, guest in odd states). Flyway migrate-from-empty. Importer against the real export (P5), with a fixture set for the failure cases in §10.3.
- **UDP:** a loopback listener on port 9 verifies that the packet is actually sent. Real-network wake is manual in P0 and P6, because it cannot be proven from a unit test.
- **Contract:** Hurl scripts against the OpenAPI spec, runnable against any backend (§13).
- **Security:** route enumeration proving `hub-public` exposes only the three documented routes. Outside the legacy routes (§9.5), `GET` never mutates. Legacy routes are absent from `hub-public` and unreachable via the tunnel path. Host and Origin rejection. CSRF. Secrets never appear in API responses, logs or `config_versions`.
- **E2E (Playwright):** the dashboard, the editor conflict case, the confirmation modal for each level, the command palette, the skills explorer with sparse data, and layouts at phone width.
- **Resilience:** kill -9 during a config save and during a portfolio write. Pull the Proxmox host offline. Fill the disk. Corrupt the YAML.
- I will report what actually passed and failed. Anything not runnable in my environment (e.g. real WOL, real Pi metrics) will be marked as such, never assumed.

---

## 16. Open decisions for you

| # | Question | My default |
|---|---|---|
| ~~Q1~~ | ~~Edit password~~ | **Resolved (D12):** single password for editing |
| ~~Q2~~ | ~~Graceful shutdown~~ | **Resolved (D13):** keep hard `stop` |
| ~~Q3~~ | ~~Homelab pages~~ | **Resolved (D14):** redesigned, consistent with all pages |
| Q4 | Is `homepage.example.com` (or anything else) currently routed through the tunnel? I can't see it | You check in the Cloudflare dashboard. **Still open**, and now more important because of D15: the legacy routes must never be reachable from the tunnel |
| ~~Q5~~ | ~~Who calls wol-server URLs~~ | **Resolved (D15):** URLs kept working, so callers don't matter |
| Q12 | Do you accept the residual risk in §9.5 (open, unauthenticated legacy routes, with the `Sec-Fetch-Site` guard as the only browser-side defense)? | Yes, since you chose it, but this is stated explicitly so it is a conscious choice |
| Q13 | Should the legacy routes also work over Tailscale, or LAN only? | Both, same as the dashboard |
| Q6 | Is `public-user` read-only in Postgres? Create a read-only role for the export? | Create one |
| Q7 | Icon strategy: cached CDN sets with offline fallback, or a bundled subset? | Cached with fallback |
| Q8 | Resource thresholds in §13.2 | Use my proposals unless you change them |
| Q9 | Mockup review before UI build (P3)? | Yes, 2–3 options |
| Q10 | Project name and repo location (new git repo?) | `homelab-hub`, new repo |
| Q11 | Retention period for the old services after cutover | 30 days |

---

## 17. What this plan does not claim
- It does not claim any of the [A] items work. They are unverified until P0.
- The memory and latency figures for the new app are **unknown**. The only measured numbers are for today's services (§2.1, §2.5).
- The YAML schema, API paths and compose layout above are sketches and will change during P2 and P3.
- I have not read every source file: the portfolio page components, the schemas beyond the ones I opened, the seed script, and `ProxmoxApiServiceImpl` beyond its action and status handling. I've read enough to plan, but P1 starts with reading those in full.
- I have not designed the UI. Section 8.4 lists content and behavior only.

---

## 18. Progress log

### Current status (updated 2026-09-24, end of day)
**Done:** P0–P8 built and tested locally (Java, Vitest, Python, 31 browser tests); deployed to the Pi as a parallel run (internal `:3010`, public `:3011`, LAN-only ufw rules, Proxmox TLS pinned); measured on the Pi (§18.y): RSS 243 MiB internal / 177 MiB public, startup ~30 s, no case for FastAPI; icons fixed (§18.z); dashboard password changed by the owner.
**Pending / unverified:**
1. Owner's manual testing of the dashboard, portfolio editing and config editor on the Pi.
2. File Browser and qBittorrent widgets against live services (host 192.168.10.131 was powered off).
3. Any real Proxmox action or Wake-on-LAN packet, and the token's start/stop/shutdown/reboot rights (A10): never tested, mock only.
4. Inotify on the Pi bind mount (A8; the 10 s poll is the fallback): not observed.
5. Long-run behaviour: measurements are minutes, not days (memory growth unchecked).
6. Cutover (P9): tunnel to `:3011`, legacy wol-server URLs to port 3002 with an explicit ufw rule, retire old services. Needs the owner's go-ahead and a rollback plan.
7. Open questions: why `cloudflared` restarted at 16:30 UTC (not by me; possibly watchtower); no request rate limit on power routes beyond the per-target lock; icons must be fetched with `tools/fetch-icons` when new ones are added.
**Owner's manual testing on the Pi, 2026-09-25 (reported by the owner, not re-verified by me):** public portfolio as expected; dashboard works with all features; adding a service through the config editor works; adding a portfolio skill (AWS Lambda, group Cloud & Data Warehousing) and a project works.
**Fixes made after that test (2026-09-25, all deployed to the Pi on 3010/3011; 40 browser tests, Java, Vitest all pass):**
- D-UI-1 fixed: dialogs are height-limited to the window, scroll inside, with a sticky button row. Test: 1280x480 viewport, buttons and last field reachable.
- D-UI-2 fixed: the slug is suggested from the title (never overwrites one the user typed), an invalid slug is explained beside the field with a one-click fix.
- Config page: "Remove service" and "Remove action" added. Text-level removal (comments and other lines untouched, also inside flow lists); removing an action also removes its dashboard buttons and its pin so nothing dangles; only the last entry of a list falls back to re-formatting (the UI says so). Nothing is saved until the owner reviews the diff.
- Dashboard tiles: two fixed heights (54 px plain, 88 px with a widget), name always one line with ellipsis (full name in tooltip), widget text at most two lines. Verified at 390/900/1280/1920/2560 px. A CSS class clash (`.w`) was found by looking at the screenshot and renamed.
- Cutover, partly done at the owner's request: `homepage` and `wol-server` containers stopped and removed (`docker compose down`, no volumes touched) and their images deleted; compose files, Dockerfile, jar, `.env`, homepage config and images kept on disk in `~/homepage` and `~/wol-server`, so `docker compose up -d` (with `--build` for wol-server) restores them. Checked before deleting that the base images are still pullable (arm64v8/openjdk:17, ghcr.io/gethomepage/homepage:latest; note `latest` may be a newer homepage version than before). **Consequence: nothing listens on 2000 or 3002 any more**; the owner chose to keep 3010/3011, so old wol-server URLs on port 3002 no longer work (the earlier decision D15 to keep them open applies to the routes, which still work on 3010).
- **Old portfolio stopped and cleaned up (2026-09-25, after Proxmox came back):** in LXC 100 only the `tabishtariq` compose project (frontend + backend; its Postgres is external in LXC 102, which is stopped and untouched) was taken down (`docker compose down`, no volumes) and its two images removed. Source, `docker-compose.yml`, both Dockerfiles and `backend/.env` remain in `/root/tabishtariq`, so `docker compose up -d --build` recreates it. The other services in that LXC (xwiki, trilium, open-webui, which the dashboard links to) were not touched. The unused old image `tabishtariq_web:latest` (May 2025, not built by the current compose) and the `hello-world` container were left alone because they cannot be recreated from the current sources.
- **Still the owner's step:** point the Cloudflare Tunnel public hostname at `http://localhost:3011`; until then the public hostname has nothing behind it (the old frontend on LXC 100 port 3000 is down).
**Nothing is committed to git** (the directory is not a git repository).

**2026-09-24**
- **P0 (partly):** A1, A3, A5, A9 verified on the Pi and A2 partly (see §14 "P0 results"). A4 verified locally (below). Still open: A6 (buildx cross-build), A8 (file watcher on the Pi), A10 (Proxmox token ACLs), wol-server response fixtures.
- **Toolchain:** no Java locally and no Docker access (permission denied on the socket, no sudo). Installed Temurin 17.0.20.1 and Maven 3.9.11 under `~/tools` (user space).
- **P1 (foundations):** repo created locally (no commits yet). Maven modules `hub-snapshot`, `hub-internal`, `hub-public` on Spring Boot 3.5.16. `V1__initial_schema.sql` (SQLite). `hub-internal` starts and serves `/actuator/health`.
- **A4 verified:** Flyway (core, Spring-managed version) migrates SQLite, and `foreign_keys`, `journal_mode=WAL` set as **JDBC URL parameters** apply on every pooled connection. Checked by `SchemaAndPragmasTest` (8 tests: tables, WAL, FK enforcement, JSON `CHECK`, profile singleton, cascade delete, case-insensitive unique skill, health).
- **`hub-public` built** (delegated to the opencode agent, then reviewed by me): snapshot store, controller, security-header filter, route-surface guard. **My review found and fixed two defects:** a JSON `null` snapshot threw a NullPointerException (500, and the last good copy was lost), and there was no size cap. Also added weak/list/`*` `If-None-Match` handling. Regression tests written first and seen failing (2 errors, 1 failure) before the fix.
- **Boundary check verified to bite:** adding `sqlite-jdbc` to `hub-public` fails the build (`banned via the exclude/include list`); the restored pom passes.
- **Test totals:** 34 tests across modules, all passing (`mvn verify`).
- **Export tool** (delegated, reviewed, one fix: repeatable-read isolation for a consistent snapshot) **run against the live database, read-only.** Row counts match the earlier probe for every table. No password or hash in the output (checked by searching for the actual password string). Temporary files removed from the container. Output is in `.scratch/export-out/` (git-ignored, contains personal data).
- **opencode agent tested:** works (free hosted models only, no credentials configured). It is slow on larger tasks (about 10 minutes for the public app), and its output needed review both times: the first test accepted mixed separators in a MAC parser; the public app had the null-snapshot bug. Use for small, well-specified, independently testable tasks, never unreviewed.
- **Correction to an earlier claim:** legacy wol-server port 3002 is not reachable from the LAN today (§9.5).
- **Skill mapping accepted** by you (D18). Saved as `tools/export-portfolio/skill-mapping-decisions.accepted.csv`. Applying it gives **62 skills** (48 existing + 14 new, 3 new groups: Databases & BI, Java Ecosystem, Embedded & Control Systems), of which only **27 have linked evidence**. So about 56% of skills will show as "listed, no linked project" in the explorer until more content is linked.
- **Proposed import extension (not yet decided):** the flagship project has no tags, but its case study lists 7 platform skills. Linking those gives the flagship 7 skills instead of 0. The mockup already shows it that way.
- **Mockups built:** `docs/mockups/index.html` (local file, not published anywhere, since it shows your internal IPs). Three directions (A Signal, B Ledger, C Lattice), each in light and dark, over four screens: dashboard, skills explorer (live filtering on your real data), homelab pages (real content), and the type-to-confirm shutdown dialog. Ctrl/Cmd+K opens a working command palette. Rendered and reviewed in headless Chromium. One review pass found and fixed: a class-name collision that broke the control bar, a foggy dark-mode modal scrim, dead space from the group grid (now masonry columns), and a too-harsh portfolio card in Ledger.
- **Data inconsistencies found in your own content (not fixed):** the portfolio's homelab data puts Proxmox at `192.168.10.100`, while the homepage widget uses `192.168.10.102`. It lists container `lxc-211 Fabric` which does not exist in `pct list`. It also describes homepage as "public via Cloudflare", which conflicts with D6 and with your "ignore the tunnel" answer (D16), so it is worth confirming that entry is stale.
- **Not yet designed:** the YAML editor screen and the mobile layout have not been checked at phone width in a browser (the CSS is written to wrap, but I did not verify it).
- **Direction A chosen (D19). Second mockup built:** `docs/mockups/portfolio-single-page.html`. Public and internal (editing) views from the same page, light/dark, panel expanded/collapsed, and a phone layout with a drawer. Rendered in headless Chromium at 1280 px and 400 px; the review found one flaw (empty sections took a whole screen of placeholder boxes on a phone), fixed once without re-rendering, so **that last fix is unchecked visually**. Edit buttons show a toast only. No API calls exist.
- **Still not designed:** the YAML editor and the actual editing forms behind the "Edit" buttons.
- **Layout fixes after your feedback (design defects, not mockup artifacts):** the page was capped at 1280 px and centred, leaving wide blank gutters on large monitors, and the About text had nothing beside it. Now the cap is 2200 px with fluid side padding, About is text left and stats right (stats drop below the bio under 1500 px), and roles sit in two columns when there is room. **Rendered and checked at 1280, 1920 and 2560 px** for the portfolio page. The dashboard mockup got the same width cap but **has not been re-rendered**. Known leftovers: at 2560 px there is still a gap between the bio and the stats, and with 3 roles in 2 columns the third sits alone. Phone layout was checked before these changes and not after.
- **Lesson for the real build:** every page must be tested at 1280, 1920 and 2560 px wide plus a phone, as an explicit acceptance check (add to §15 E2E).

**2026-09-24 (later): real backend development started. Tests: 150 (124 hub-internal + 26 hub-public), all passing. Nothing deployed to the Pi.**
- **Legacy wol-server contract recorded from the live service** (`contract/legacy-fixtures/recorded-2026-09-24.txt`) using only side-effect-free calls (nonexistent guest, invalid MAC, invalid type, bad id, malformed bodies). Findings that differ from what I assumed or from the README:
  - The controller's 409 "Cannot toggle" branch is **dead code**: the service swallows the exception first, so a non-toggleable guest really answers **500** with `error":"Operation failed"`. Kept as recorded.
  - POST `/toggle` with `"type":"lxc"` (as the README documents) **fails with a generic 500**; only `"LXC"` works. The new app accepts both (a superset).
  - Unknown paths answer a generic 500 in wol-server; the new app answers 404. Success message contains the typo `"stoped"`; kept byte-identical.
  - The live Proxmox token can at least read guest status (a real Proxmox error came back for a nonexistent guest). Its permissions for start/stop/shutdown/reboot are **still not inspected (A10)**, and I did not test any real action.
- **Built and tested** (`hub-internal`):
  - `wol` package (opencode, reviewed): MAC parsing per the legacy regex (mixed separators accepted on purpose), magic packet, sender.
  - `proxmox` package (mine): HTTP client with **certificate pinning** (default), `system` and explicit `insecure` modes, 3 s connect / 10 s request timeouts, no redirects, node-name validation, legacy-identical error wording, token never in messages. Tested against a real HTTPS mock: pinning, wrong fingerprint sends nothing, timeouts, refused connections, 403 classification.
  - `legacy` package: all wol-server routes, CSRF-exempt and login-exempt by decision D15, with the cross-site guard (`Sec-Fetch-Site`), per-target in-flight lock (409), audit log, and `hub.legacy.enabled` switch. Tested against the recorded responses, comparing raw JSON for failures.
  - `auth` package: single-password login (bcrypt, cost 12), session cookie `HttpOnly; SameSite=Strict`, CSRF cookie/header, login rate limiter (opencode, reviewed), Host-header allowlist against DNS rebinding (opencode, reviewed), `hash-password` command, `AUTH_MODE` all/edit/none.
- **Mutation checks:** removing the cross-site guard and "fixing" the `stoped` typo were both caught by the tests.
- **Defects found by testing my own code, all fixed:** (1) requests with **no Host header** were refused, which would break HTTP/1.0 legacy callers: a missing Host is now allowed, only a wrong one is refused; (2) an invented "dummy hash" was not a valid bcrypt string, which would have leaked timing: now generated at startup; (3) Spring logged a **generated default password** at startup: removed, with a test.
- **Real run (not just tests):** the packaged jar started, login/logout/legacy/cross-site/host checks all behaved, audit rows were written, the password hash never appeared in the log. On this **x86 desktop** (JDK 17.0.20, `-Xmx256m`): startup **4.3 s**, RSS **~223 MiB after start, ~232 MiB after a few requests**. **These are not Pi numbers** and say nothing yet about the §13.2 thresholds.
- **Known limitations, stated plainly:**
  - The global login lockout (30 failures per 10 min) lets anyone on the LAN lock you out for up to 10 minutes. Accepted trade-off.
  - No request rate limit on the power routes beyond the per-target lock (the plan's "short rate limit" is not built).
  - `PROXMOX_TLS_MODE` defaults to `pinned`, so the first deployment needs the certificate fingerprint (or `insecure` to match today's behaviour). A helper to fetch the fingerprint is not built yet.
  - Real WOL delivery to a device and real Proxmox power actions are untested (mock/loopback only).
  - Proxmox task polling (UPID), the UI `prepare`/`confirm` confirmation API, the config engine, status poller, widgets, portfolio import/CRUD/snapshot, and the whole frontend are **not built yet**.

### 18.x Local build and browser verification (2026-09-24)
- **Everything in the plan for the local build is now built:** config engine, status poller, widgets, confirmed UI actions, portfolio import/CRUD/snapshot/backup, both SPAs, deploy files (`deploy/Dockerfile.*`, `deploy/docker-compose.yml`, `deploy/.env.example`), `README.md`.
- **Tests:** Java (`mvn verify`), Vitest, Python, and **31 Playwright browser tests** (`scripts/e2e.sh`) all pass against the locally running stack with a mock Proxmox and a local UDP listener. Browser tests cover: public portfolio sections, skill filter (hide/dim/collapse/persist), palette, no internal routes on the public port; login/logout; dashboard status/widgets/filter/theme/palette; WOL packet actually received; shutdown typed confirmation; guest stop/start following the task; confirmation expiry; config editor (errors block save, insert-only diff, conflict, broken file on disk keeps last good, restore); portfolio edit → publish → visible on public; field validation; skill linking; delete with dependents; no horizontal overflow at 390/1280/1920/2560 px; phone drawer. Screenshots reviewed by me for the public page (1920, 390) and dashboard (1920).
- **Defects found this round:** a test in `FetchersTest` depended on `Map.of` iteration order (form-field order), fixed; dashboard polled its config health only every 60 s, now 15 s. Locators in my own browser tests were ambiguous, fixed.
- **Still unverified:** Docker builds (daemon permission denied on this machine; the `curl`-based healthchecks assume the temurin image ships curl), real WOL, real Proxmox actions/token rights (A10), PBS/File Browser/qBittorrent widgets on live services, Pi inotify (A8), resource use on the Pi (§13), screenshots at 1280/2560 and the config/portfolio-edit pages were captured but I have not looked at each one.

### 18.y Pi deployment and measurements (2026-09-24, parallel run on 3010/3011)
- **Deployed** to `~/homelab-hub` on the Pi (images built there, `docker compose`), pinned TLS to Proxmox (fingerprint `B9:9C:B5:E9:…:A0:24`, cross-checked against `pvenode cert info` on the PVE host). Old services untouched (ports 2000, 3002 etc. answer as before). Only change outside the new directory: ufw allows 3010 and 3011 from 192.168.10.0/24. Not exposed to the tunnel.
- **Defects only the Pi showed, fixed:** (1) Pi kernel has **no memory cgroup**, so `mem_limit` and `MaxRAMPercentage` do nothing: explicit `-Xmx` now (256m / 160m). (2) `read_only` + `/tmp` tmpfs is `noexec`, sqlite-jdbc could not load its native library: tmpfs now `exec`. (3) `ping` missing in the image, every icmp check was "unknown": iputils-ping + `NET_RAW` (works with no-new-privileges). (4) Docker build context included the data dir: `.dockerignore`. (5) My own mistake: secrets.env was written with a `HUB_SECRET_` prefix, which is only for env vars.
- **Verified live:** login; dashboard health ok; Proxmox widget (6 LXC / 3 running, matches reality) and PBS widget (datastore 24.9 %) against the real services with the real credentials; resources widget; legacy `/toggle` for a nonexistent guest returns a byte-identical body to the old wol-server; public site serves the snapshot, `/api/admin/config` is 404 on the public port; unauthenticated dashboard 401. File Browser and qBittorrent widgets answer "did not answer in time": 192.168.10.131 is currently down (ping fails), so **these two widgets are still unverified**.
- **Not tested on purpose:** real power actions, real WOL.
- **Measurements (Pi 4, 8 GB, JDK 17 in Docker, sample of one):** startup internal **30 s** (public ~20 s). RSS internal **233 MiB idle → 243 MiB after ~1 000 requests** (66 threads), public **157 → 177 MiB**. Old wol-server 205 MiB, homepage 183 MiB for comparison, so the two new containers use about 420 MiB vs 390 MiB for the two old ones, but they also replace the portfolio's three services. Idle CPU ~0.6 % (internal), ~0.1 % (public) of one core. Throughput/latency at concurrency 4: public ~200 req/s p50 19 ms p95 34 ms; internal dashboard 92 req/s p50 38 ms p95 85 ms; c=16 public 271 req/s p95 92 ms, 0 errors. Images 364/340 MB.
- **Against §13 thresholds (internal ≤350 MiB, public ≤250 MiB RSS):** both pass with margin. On these numbers there is **no case for moving to FastAPI**; the cost is the 30 s start and ~420 MiB. Caveat: short test, no long-run leak check, one Pi.
- `cloudflared` shows "Up 26 min" (started 16:30 UTC, before my first change); I did not touch it, cause not determined (possibly watchtower).

### 18.z Icons (2026-09-24)
Homepage-style icon names now render. `tools/fetch-icons/fetch_icons.py` downloads the icons a dashboard.yaml uses (dashboard-icons for `x.svg/x.png/x`, Material Design Icons for `mdi-*`) into `data/hub/images/icons/`; the UI serves them from the app itself (no runtime CDN); mdi icons are masks so they follow the theme. All 17 icons of the real config fetched and checked as valid PNG/SVG (no `<script>`); browser check in light and dark: no broken images. Deployed to the Pi (icons + new internal jar); the icon files are served with the right content types there.

### 18.w Cutover closed and watchtower (2026-09-25)
- **Cutover marked complete by the owner.** Old homepage, wol-server and portfolio retired safely (containers and images removed, sources and a `readme-to-setup-again.md` kept next to each compose file). The owner will change the Cloudflare Tunnel hostname to `http://localhost:3011` themselves. Legacy URLs on port 3002 are not needed and are not provided (routes still work on 3010).
- **Watchtower:** it scans every container every 30 min and tried to pull the locally built `homelab-hub-*:local` images from Docker Hub ("pull access denied"; harmless, `Updated=0`, but noisy and it emailed). Both services now carry `com.centurylinklabs.watchtower.enable=false` in `deploy/docker-compose.yml`, containers recreated on the Pi. Confirmation at the next watchtower run was still pending when this was written.

### 18.v Installable app (PWA) over Tailscale HTTPS (2026-09-25)
- The internal dashboard is now an installable web app: `manifest.json`, icons (any + maskable, generated from SVG by `web/scripts/make-pwa-icons.mjs`), app shortcuts (Config, Portfolio), `offline.html`, and a service worker (`web/src/internal/public/sw.js`) that caches **only** fingerprinted `/assets/*` and the offline page. It never stores API responses or HTML, and it is registered only in a secure context.
- New optional setting `HUB_SECURE_COOKIES` (default false) marks the session and CSRF cookies `Secure`; it must stay false unless every way of opening the dashboard is https.
- Tests: 7 Vitest tests of the service worker's decisions against a fake scope (mutation-checked), 4 browser tests (manifest and every icon's real size, installability in a normal Chrome profile via CDP, the worker takes control, nothing private in the caches), 2 Java tests for the Secure cookies plus a default-off assertion. Totals then: Playwright 44, Vitest 62.
- Deployed to the Pi. On the Pi, `sudo tailscale serve --bg http://127.0.0.1:3010` publishes the dashboard to the tailnet only at `https://tabishrpi4.<tailnet>.ts.net` (Tailscale certificates were already enabled for the tailnet); the MagicDNS name was added to `HUB_ALLOWED_HOSTS`. Checked from the Pi: index, manifest, sw.js and icon load over https; the API still answers 401 without login; a wrong Host header gets 400; the plain LAN address still works.
- Installed and confirmed working on the owner's Android phone. **Not verified:** Tailscale ACLs were not reviewed, so any device on the tailnet that the ACLs allow can reach the login page; the running Pi memory after this change.


### 18.u Terminal page (2026-09-26, branch, not deployed)
Built from `docs/design-history/terminal-feature-plan.md`. **Off by default and not deployed; nothing on the Pi was changed.**
- **What:** `hub-internal` package `terminal` (feature switch, tickets, session manager, SSH connector, WebSocket handler, audit, `terminal-hostkey` CLI), migration `V3__terminal_audit.sql`, `terminals:` in `dashboard.yaml`, `features.terminal` in `/api/dashboard`, lazy `TerminalPage` (xterm.js) with the phone key row, `tools/mock-ssh`, and the Terminal setup section in the README.
- **Library:** SSH client `com.github.mwiede:jsch` 2.28.7 (maintained fork, released Aug 2026, ~700 KB, pure Java). Verified here: it reads an OpenSSH-format ed25519 key with the plain JDK (no BouncyCastle), its `HostKeyRepository` hook sees the host key before authentication (used for pinning; a test proves no auth attempt follows a mismatch), PTY type/size, window-change, exit status and keepalive all work against two independent servers (Apache MINA SSHD in the Java tests, paramiko in the browser tests). MINA SSHD 2.19 (+ `net.i2p.crypto:eddsa`) is test scope only.
- **WebSocket/CSP:** the same-origin `ws://` connection works under the existing CSP (`connect-src 'self'`) in real Chromium; no CSP change. Spring's built-in WebSocket origin check compares the scheme and would refuse everything behind `tailscale serve` (https outside, http inside), so it is switched off for this endpoint and `OriginCheck` compares host (and port when the Host header has one), also accepting names from `HUB_ALLOWED_HOSTS`.
- **Deviations from the plan:** logout is detected with an `HttpSessionListener` (covers sign-out and the 12 h expiry; `AuthController` untouched). Tomcat itself closes sockets of an ended authenticated session (1008), which is treated as `logout`. Message size limits are set per socket rather than with `ServletServerContainerFactoryBean`: the bean needs a real servlet container and broke ~140 existing MockMvc tests when first tried. `GET /config` answers 200 `{enabled:false}` when off (the page can explain), `POST /sessions` 503. `Modal` moved to `web/src/internal/components/Modal.tsx` (re-exported from `ConfigPage`). `run-local.sh` now skips the portfolio import when the export file is missing instead of aborting.
- **Defects found while testing, fixed:** the idle warning banner changed the terminal's size, the resize counted as activity and reset the idle timer (idle close came at ~21 s instead of 12 s): the banner is now an overlay. Disconnect's own message was overwritten by the socket-close handler. On a phone the header took a third of the screen: a live session is now full screen there. Ctrl+C did not work in the mock SSH server (SIGINT inherited as ignored from the background start): fixed in the tool. Ctrl+K opened the command palette inside the terminal: disabled on that page.
- **Tests:** Java 442 (baseline 380), Vitest 82 (62), Playwright terminal suite 7 new (51 in total), Python +3 (mock SSH). All pass here except `CheckersTest.tcpHonoursItsTimeout`, which also failed in the baseline in this environment (10.255.255.1:80 is reachable here). The browser run here had no portfolio export, so the 13 portfolio-dependent browser tests could not pass here; every other existing browser test passed. `TerminalApiTest.signingOutClosesThatLoginsTerminal` failed once in a full run and never again in several runs; the helper now records every frame to explain it if it recurs.
- **Bundle:** main internal chunk 282.3 → 283.1 KB; xterm is only in the lazy `TerminalPage` chunk (348 KB, 89 KB gzip). Public bundle leak check passes.
- **Not verified:** the WebSocket through the real `tailscale serve`, Android Chrome/Gboard input and the installed app, OpenSSH's sshd on the Pi (the `from=` restriction, source address 127.0.0.1), memory on the Pi idle / with 1 and 2 sessions (§N6), Docker image build. These need the owner's go-ahead for the Pi steps in the README.

### 18.t Budget page (2026-09-26)
- **Built** from `docs/design-history/budget-feature-plan.md`: people, expenses (month required, day optional = the 15th), equal splits in integer paise, recurring expenses materialised when a month is opened, running-ledger statements with carry-over, who pays whom, Mark paid with part payments, a Settlements card, and a PDF export (This month / Choose month / Choose range) shared through the Web Share API with a download fallback. Backend in `com.homelabhub.internal.budget`, frontend in `web/src/internal/budget/` and `pages/BudgetPage.tsx` (lazy chunk, ~41 kB, no CodeMirror). `Modal` moved from `ConfigPage.tsx` to `components/Modal.tsx`.
- **Deviations from the plan:** the migration is **`V4__budget.sql`** (owner's choice: `V3` is left for the Terminal feature) and Flyway now runs with `out-of-order: true` so that a later `V3` still applies on a database that already has `V4` (a test proves both the failure without it and the fix). Noto Sans has no arrow (U+2192), which the glyph test caught, so the PDF says "Ravi paid Anu" for settlements. The month view also returns `today` (in the budget zone) so "This month" and "Today" follow the server's zone, not the browser's.
- **Defect found and fixed:** PDFBox pulls in `commons-logging`; with it on the classpath `spring-jcl` prints a warning to **stdout**, which corrupted `java -jar hub-internal.jar hash-password` (its first output line was no longer the hash, so `run-local.sh` set an invalid hash and login failed). The jar is excluded in the pom, and a test fails if it comes back.
- **Tests:** hub-internal 425 Java tests (+45 budget) and hub-public 26, Vitest 70 (+8), Playwright 52 (+8). In this environment one existing Java test, `CheckersTest.tcpHonoursItsTimeout`, fails before and after the change because the sandbox network accepts TCP connections to `10.255.255.1`. 13 existing browser tests need the owner's private portfolio export and images (`.scratch/`) and fail identically on the unchanged baseline commit (31/44 there, 39/52 with the budget).
- **Looked at:** screenshots at 390 and 1280 px, light and dark (month view, add/edit sheet, export dialog, the Settled column), and the September PDF rendered to an image (₹ renders, layout as mockup 7). No horizontal overflow at 390 px.
- **Not verified:** Web Share with a file on a real phone and inside the installed app; PDF generation inside the real container on the Pi (no font cache was written locally since only embedded fonts are used, so no JVM option was added); memory on the Pi after the change; deployment (not done).


### 18.s Merging Budget and Terminal (2026-09-27, branch `merge/budget-terminal`, not merged to `main`)
Both feature branches (`claude/budget-page-implementation-4we39o`, `claude/homelab-terminal-feature-d8m1w1`) were analysed for a safe merge, then actually merged into a new branch on top of `main` (`d219018`) and pushed. **`main` itself was not touched.**
- **Conflicts, all mechanical (two features adding side-by-side lines):** `README.md`, `docs/plan.md` (this file; Budget's `18.u` renamed to `18.t` to avoid clashing with Terminal's), `hub-internal/src/main/resources/application.yml`, `web/src/internal/App.tsx`, `web/src/internal/components/Modal.tsx`, `web/src/shared/styles.css`.
- **Real defects the naive merge would have introduced, all fixed:**
  1. `web/src/internal/pages/ConfigPage.tsx` ended up importing `Modal` twice (one import per branch) — would not compile. Removed the duplicate.
  2. Budget's `out-of-order: true` Flyway setting and its `BudgetSchemaTest.aLaterV3StillAppliesAfterV4` test existed only because Budget (`V4`) was built and tested before Terminal's `V3` existed. With both merged, migrations run in natural order; the setting and the test (which manufactured a conflicting fake `V3`) were removed.
  3. **Phone header overflow:** with both "Budget" and "Terminal" nav links present, the top bar (5 links) no longer fits at 390 px — 4 browser tests failed (`layout at real screen sizes`, `removing from the config...`, `works on a phone and on a short screen` (terminal), `on a phone the key row scrolls...` (terminal)). Fixed with a small CSS rule: on screens ≤700px the nav wraps to its own row and scrolls horizontally instead of overflowing the page.
  4. The merge branch needed `npm ci` (Terminal's `@xterm/xterm`/`@xterm/addon-fit`) and, only for local testing, `pip install paramiko` (Terminal's mock SSH tool) — neither is a code defect, just environment setup omitted from the plans.
- **Verified on the merge branch:** `scripts/build-all.sh` and `scripts/e2e.sh` both green. Java 508 (482 `hub-internal` + 26 `hub-public`), Vitest 90, Playwright 59/59, Python 28. README's test-count table updated with the real combined figures (both branches had written placeholder/individual counts assuming they'd land alone).
- **Not done:** no code review of either feature's internals beyond what compiling and testing surfaces; nothing deployed; Web Share on a phone, the PDF in the read-only Pi container, `tailscale serve` + WebSocket, and combined Pi memory use are all still unverified (see each feature's own report, `docs/design-history/budget-implementation-report.md` on its branch and `docs/design-history/terminal-feature-report.md` on its branch).
- **Recommendation:** merge `merge/budget-terminal` into `main` after review; deploy once, backing up `data/hub/hub.db` first (`V3` then `V4` apply in order); keep Terminal `HUB_TERMINAL_ENABLED=false` until its Pi-side user/key setup (see `docs/design-history/terminal-feature-plan.md` §5.7) is done with the owner.

### 18.r Docs app (2026-10-17, branch `claude/charming-brahmagupta-g1sb4t`, not deployed)
Built from `docs/design-history/docs-feature-plan.md` (P0-P10). **Nothing was deployed and no LXC, tunnel or DNS record was created.** The
post-deployment steps are in `docs/docs-setup-guide.md`.
- **What:** new Maven modules `hub-auth` (the login moved out of `hub-internal`), `docs-internal`, `docs-public`; web entries
  `src/docs-internal`, `src/docs-public` and `src/shared/docs`; `deploy/docs/` (one multi-stage Dockerfile with two targets, compose
  with `cloudflared`, `.env.example`); `scripts/run-local-docs.sh`, `stop-local-docs.sh`, `e2e.sh docs`; the hub's `docs.url` link.
- **P1 (hub-auth):** the hub sends the same headers, cookie names and error bodies as before (`HubSecurityUnchangedTest`, literals
  copied from the old `SecurityConfig`). The Spring auth tests stay in `hub-internal` (they test the hub); the two pure ones moved.
- **Deviations from the plan:** one `deploy/docs/Dockerfile` with targets instead of two Dockerfiles, and it builds the jars itself
  (multi-stage), so the LXC needs only Docker. Embeds of a webapp page that was never published use its current draft bundle (the
  publish check says so). `PUT /pages/{id}/host` added for "Save settings only". Embeds are keyed by bundle hash in the public JSON,
  so no page ids reach the public side. The Trash restores a page as internal. Mermaid renders with `htmlLabels: false` so the SVG
  looks the same through `<img>`. Text above a framed app is plain paragraphs (a textarea), not the full editor.
- **Defects found by the tests and fixed:** a page-link href from the render context was not re-checked (now `safeHref` again);
  after inserting a diagram the atom stayed selected and the next insert replaced it (block inserts are now followed by a paragraph).
- **Tests:** see README "Build and test". In this environment the terminal SSH tests kill the test JVM (also on the base commit) and
  `CheckersTest.tcpHonoursItsTimeout` fails (the sandbox proxy answers the "unreachable" address), as before.
- **Not verified:** the Docker images (no Docker daemon usable here), Docker and Tailscale inside an LXC, the Proxmox firewall, a real
  Cloudflare wildcard public hostname, memory on the LXC.

### 18.q Portfolio moved to the Docs LXC (2026-10-18, branch `feature/portfolio-on-docs-lxc`, not deployed)
Built from `docs/design-history/portfolio-on-docs-lxc-plan.md` (P0-P7). **Nothing was deployed and no Cloudflare record was changed.** The cutover
runbook is `docs/docs-setup-guide.md` section 17.
- **What:** the portfolio package moved from `hub-internal` to `docs-internal` (`com.homelabhub.docs.portfolio`, `git mv`, same API
  paths); `docs-internal` gets `V2__portfolio.sql` (the hub's portfolio DDL verbatim), the `import` and `migrate-portfolio` commands and
  `docs.portfolio.*` settings; the existing `hub-public` runs on the LXC (`deploy/docs/`: new image target, no host port, `edge`
  network, `portfolio-snapshot` volume); the Docs UI gets a lazy `#/portfolio` route; the hub loses the Portfolio page, nav item,
  palette entries, publish-status polling, PWA shortcut, `hub-snapshot` dependency and the Pi's `hub-public` service, and gets a link
  card (`docs.url` plus the optional `docs.portfolioUrl`).
- **Independence:** no `depends_on` in `deploy/docs/docker-compose.yml` (`scripts/check-deploy.sh` enforces it); the hub makes no call to
  the LXC and the LXC none to the Pi; the e2e suite stops `docs-internal` and checks that both public sites keep answering.
- **No destructive change to `hub.db`:** the portfolio tables stay (the rollback copy); hub migrations are unchanged (a test asserts V1-V5 only).
- **Deviations from the plan:** `migrate-portfolio` opens the hub backup with a second read-only connection (`mode=ro`) instead of
  `ATTACH` (same guarantee, simpler with sqlite-jdbc); the schema parity test compares `PRAGMA` table, foreign-key and index info (the
  stored `CREATE` text differs by comments) and reads the hub's real migration files from the sibling module instead of a copy;
  `GET /api/admin/portfolio/config` gives the editor the public URL; the hub card's public link comes from the optional
  `docs.portfolioUrl` key in `dashboard.yaml` (plan P-D7 said "plus the public link" without naming a source).

