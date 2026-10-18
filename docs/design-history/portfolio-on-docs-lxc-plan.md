# Portfolio on the Docs LXC: implementation plan

> Created: 2026-10-18 · Base: `claude/charming-brahmagupta-g1sb4t` at `1c9d203` · Status: **Planned, not implemented.**
> Related: [`docs-feature-plan.md`](docs-feature-plan.md), [`docs-setup-guide.md`](docs-setup-guide.md).

## Context
Today the portfolio lives on the Raspberry Pi:
- **Editing:** `hub-internal` edits it (the Portfolio page and `/api/admin/portfolio/**`, with the data in `hub.db`) and writes
  `snapshot.json`.
- **Serving:** `hub-public` serves it at port 3011 through the Pi's own Cloudflare tunnel.

The Docs app now runs on its own Proxmox LXC, with its own tunnel and a compose `edge` network where only `cloudflared` reaches
the public containers.

**New requirement (owner's decisions, 2026-10-18):**
1. Portfolio **editing moves to the Docs app** (docs-internal on the LXC). There is no connection between the Pi and the LXC.
2. The **public portfolio is served from the LXC**, exposed the Docs way: a public hostname on the docs tunnel, a container with
   no host port, and the `edge` network.
3. **Switch immediately:** after cutover, the Pi stops serving the portfolio. The Pi's `hub-public` is removed, and its tunnel is
   stopped if nothing else uses it.

**Constraint:** nothing else may break. The hub keeps working:
- dashboard, actions, Config, Budget, Terminal, Docker, legacy routes, login.
- Docs keeps working, and Docs' public pages keep their isolation guarantees.
- The portfolio data survives, with a tested copy and a rollback path.

**Independence requirement (owner, 2026-10-18).** The Pi and the Docs LXC must each run on their own.
- The owner will sometimes stop the Docs containers while the Pi stays on.
- Nothing on the Pi may break or slow down when Docs is down, and Docs must not need the Pi either.
- See "Independence" below; it is a gate for every phase.

## Independence (both sides run alone)

**Rule.** No runtime call in either direction. The hub never fetches anything from the LXC, and nothing on the LXC fetches from
the Pi. The only links between them are:
- `<a href>` links (the hub's **Docs** link and Portfolio card, and Docs' **← Hub**), which the browser follows;
- the one-time manual data copy at cutover.

The hub's CSP stays `connect-src 'self'`, which enforces this in the browser.

**What happens when something is down:**

| What is down | The Pi (hub) | The portfolio (public) | Docs |
|---|---|---|---|
| `docs-internal` stopped (`docker compose stop docs-internal`) | Unaffected. The Docs link/card just opens a page that does not load. | **Still served:** `hub-public` keeps serving the last `snapshot.json` from its volume, and `cloudflared` keeps running. Editing is unavailable until Docs is back. | Editor down; public Docs pages still served by `docs-public`. |
| `docs-internal` + `docs-public` stopped | Unaffected | Still served | Public Docs pages give Cloudflare 502 |
| The whole LXC (or its compose) down | Unaffected: dashboard, actions, Config, Budget, Terminal, Docker, legacy routes, login | **Offline** (it lives on the LXC now; by design of the switch) | Offline |
| The Pi down | n/a | Unaffected | Unaffected (the **← Hub** link just fails) |

**What the code must guarantee (built into the phases):**
- **I1:** `deploy/docs/docker-compose.yml` has **no `depends_on` on `docs-internal`** for `hub-public`, `docs-public` or
  `cloudflared`, so each one starts, restarts and stays up on its own.
  - The named volumes get their ownership from the images instead: `mkdir` + `chown 10003` in the `hub-public` and
    `docs-public` targets, as already done for `docs-public`.
  - This replaces the existing `depends_on` lines.
- **I2:** `hub-public` reads only its volume and keeps its last good copy (existing `SnapshotStore`). It does not care whether
  docs-internal is running. docs-internal's `SnapshotPublisher` writes atomically, so a stop in the middle of a write cannot
  leave a broken file.
- **I3:** The hub's portfolio card and Docs link are static: no fetch, no polling, no health check against the LXC. The
  dashboard never waits for the LXC.
  - If the owner adds a status check for Docs in `dashboard.yaml`, it is an ordinary item that shows "down".
  - That is an existing feature with timeouts, so it cannot block the page.
- **I4:** The Pi's compose no longer has `hub-public` or a snapshot volume, so nothing on the Pi waits for, mounts or pings
  anything that moved.
  - `hub-internal` starts with no portfolio settings at all.
  - Removing `HUB_SNAPSHOT_DIR` must not leave a required variable behind; compose `config` checks this.
- **I5:** docs-internal never calls the hub. `DOCS_HUB_URL` is only rendered as a link.
- **I6:** The guide gives the commands for "stop Docs but keep the portfolio online":
  `docker compose -f deploy/docs/docker-compose.yml stop docs-internal docs-public`.
  - It also gives the matching `start` command.
  - It warns that stopping the **whole** LXC also takes the portfolio offline.

**Tests for independence:**
- **Hub (Pi) side:** a Spring test and an e2e test with **no Docs stack running**.
  - The dashboard renders.
  - `/api/dashboard` returns `docsUrl` without any outbound call (assert no HTTP client is used).
  - The Docs link and portfolio card are present, and no request goes to 3920/3911 (Playwright checks request URLs).
  - The whole hub e2e suite runs with the Docs stack **stopped** (the `run-local.sh` order is independent of
    `run-local-docs.sh`).
- **Docs side:**
  - `run-local-docs.sh` and the Docs e2e run with the hub stack stopped. They already do; the plan keeps it that way.
  - New e2e: stop `docs-internal` (kill its process), then `hub-public` on 3911 still serves the portfolio and `docs-public`
    still serves a published page. Start it again and editing works.
- **Deploy files:**
  - `docker compose config` passes with only the Pi `.env` (no Docs variables).
  - A check (script or test) greps `deploy/docs/docker-compose.yml` and fails if any service `depends_on: docs-internal`.

## Key findings (from the code map)
- **The Java package moves cleanly.**
  - `hub-internal/.../portfolio/` (Controller, Crud, Reader, Resources, ResourceSpec, SnapshotPublisher, Importer, ImportCli)
    has one outside reference: `InternalApplication` L18-20 (`import` CLI).
  - Its Spring needs are `JdbcClient`, `TransactionTemplate` and `Clock` (from hub-auth `AuthConfiguration`). docs-internal
    already has all three, plus `@EnableScheduling`.
- **The portfolio tables are self-contained.**
  - V1: `profile`, `experiences`, `projects`, `project_case_studies`, `project_deliverables`, `academic_items`, `skill_groups`,
    `skills`, `skill_aliases`, `experience_skills`, `project_skills`, `unlinked_tags`, `homelab_devices`, `homelab_containers`,
    `homelab_services`, `homelab_network_entities`.
  - V2: `device_skills`, `container_skills`, `service_skills`, and `version` on `skill_groups`/`skills`.
  - No other hub table references them, and none of their names collide with `docs.db`.
- **The snapshot is self-contained.** `snapshot.json` is one file with no image files. `hub-public` only reads it
  (`SnapshotStore`), so it can run unchanged on the LXC.
- **Places that would break:**
  - The hub's `App.tsx` polls `/api/admin/portfolio/publish-status` and imports `openPalette` from `PortfolioPage`.
  - `Dashboard.tsx` has the portfolio card.
  - The PWA `manifest.json` has a "Portfolio editor" shortcut.
  - `run-local.sh` runs the import, and the e2e portfolio tests use ports 3910/3911.
  - `DocsPublicTest` L121 and `check-public-bundle.mjs` L20 forbid `/api/public/portfolio` in docs-public. That is fine as long
    as docs-public is **not** the server (see D2).

## Decisions
| # | Decision | Why |
|---|---|---|
| P-D1 | Move the portfolio Java package into docs-internal as `com.homelabhub.docs.portfolio`, with `git mv` and only the edits the move needs. The API paths stay the same (`/api/portfolio`, `/api/admin/portfolio/**`). | Same code and same tests; history kept. The paths do not clash with `/api/admin/docs/**`. |
| P-D2 | Run the **existing `hub-public` container unchanged** on the LXC. No host port, on the `edge` network, reading a new named volume `portfolio-snapshot` (written by docs-internal, read-only for hub-public). | Reuses a tested, boundary-enforced app. docs-public, its tests and its bundle check stay untouched. The portfolio and Docs pages stay in separate containers. |
| P-D3 | Expose it the Docs way: an **explicit public hostname** on the docs tunnel, `<portfolio-host>` → `http://hub-public:3011`, plus a proxied CNAME to the docs tunnel. An explicit hostname wins over `*.domain.com`. | One tunnel and one exposure method for everything public. |
| P-D4 | Copy the data with a new CLI, `docs-internal.jar migrate-portfolio --from <hub-backup.db>`. It attaches the hub DB **read-only** and copies every portfolio table in foreign-key order in one transaction. It checks row counts table by table and refuses a non-empty target unless `--replace` is given. `--dry-run` reports only. | Exact copy, including ids and `version`. Safe to re-run. It never writes to the hub DB. |
| P-D5 | The `docs.db` schema comes from a new migration `V2__portfolio.sql` that copies the portfolio DDL **verbatim** from the hub's V1/V2. A test compares the two schemas (`sqlite_master` of the portfolio tables) so they cannot drift. | The CLI copies like for like. |
| P-D6 | **No destructive change to `hub.db`.** The portfolio tables stay there, unused (Flyway is forward-only). Dropping them is a later, separate step once the owner is happy. | Rollback = redeploy the previous hub release and point the hostname back. |
| P-D7 | The hub keeps a small **Portfolio card** on the dashboard: "Edit in Docs" opens `docs.url + "#/portfolio"`, plus the public link. It gets no data from the LXC: the hub CSP stays `connect-src 'self'`, and there is no new link between the Pi and the LXC. The Portfolio nav item, page, palette entries, publish-status polling and PWA shortcut are removed. | Keeps the hub as the landing page without coupling the two machines. |
| P-D8 | Web: move `openPalette` from `PortfolioPage.tsx` to `shared/components/Palette.tsx`. Move `PortfolioAdmin.tsx` and `EditorDrawer.tsx` to `web/src/shared/portfolio/` so docs-internal can use them. `PortfolioPage`, `SkillPanel`, `filter` and `types` stay in `shared` (the public bundle is unchanged). | The smallest diff to the public bundle and its leak check. |
| P-D9 | Settings: docs-internal gets `docs.portfolio.snapshot-dir` (`DOCS_PORTFOLIO_SNAPSHOT_DIR`, default `./portfolio-snapshot`, `/portfolio-snapshot` in the container) and `docs.portfolio.public-url` (`DOCS_PORTFOLIO_URL`, shown in the editor as "Open public site"). `SnapshotPublisher` reads these instead of `HUB_SNAPSHOT_DIR`/`HUB_DATA_DIR`. | Matches the `DOCS_*` style. The hub's env names disappear from the hub. |

## Phases (each ends green; commit per phase)

**P0. Baseline.** Record the test counts:
- `scripts/build-all.sh`;
- `scripts/e2e.sh` (hub and Docs);
- terminal tests, which crash the JVM here and are run separately where possible.

**P1. Web: refactor with no behaviour change.**
- Move `openPalette` into `Palette.tsx` and update its imports (hub `App.tsx`, `PortfolioPage`, public `main.tsx`).
- `git mv` `PortfolioAdmin.tsx` and `EditorDrawer.tsx` into `web/src/shared/portfolio/` and fix their imports.
- Gate: Vitest, both public bundle checks, and the hub e2e portfolio tests, all still green.

**P2. docs-internal backend.**
- `docs-internal/pom.xml` gets the `hub-snapshot` dependency.
- Add `V2__portfolio.sql` (P-D5).
- `git mv` the portfolio package and its tests (`PortfolioCrudApiTest`, `PortfolioImporterTest`, `ImportCliTest`) into
  docs-internal. Make them use `DocsClient`/temporary-directory helpers. Fix the importer test's `../.scratch/...` path.
- Move `SnapshotPublisher` onto the docs settings (P-D9). It keeps its write order, retry loop and `X-Public-Site` header.
- Add the CLI commands to `DocsApplication`:
  - `import` (the moved `ImportCli`, with `DOCS_DATA_DIR`);
  - `migrate-portfolio` (P-D4, new `PortfolioMigrator`).
- Security: nothing new. The `AuthSecurity` rules already cover `/api/**` and `/api/admin/**`; tests assert login and CSRF on
  both paths in every auth mode.
- New tests:
  - `PortfolioSchemaParityTest` builds a hub DB with the hub's migrations (copied as test resources) and compares the
    portfolio tables with `docs.db`.
  - `PortfolioMigratorTest`: copies every table and every column; ids and versions preserved; foreign-key order; counts
    reconciled; refuses a non-empty target; `--replace`; `--dry-run`; the source opened read-only (the file's hash is
    unchanged); a missing or corrupt source fails cleanly.
  - `DocsAuthModesTest`: covers `/api/portfolio` in edit mode.
- Gate: all docs-internal tests green.

**P3. docs-internal UI.**
- A `#/portfolio` route in `web/src/docs-internal/App.tsx` renders the shared `PortfolioAdmin`.
- A "Portfolio" link in the Docs header and a palette entry.
- The header's "public site out of date" warning also uses `/api/admin/portfolio/publish-status`.
- Add the portfolio styles that `PortfolioPage` needs. They already live in `shared/styles.css`, which docs-internal imports.
- Gate: Vitest, and a docs-internal bundle check that `PortfolioPage` stays out of the main chunk (lazy route).

**P4. Deployment files (LXC).**
- `deploy/docs/Dockerfile`:
  - a `hub-public` target (web `build:public` with its leak check, then `mvn -pl hub-public -am`, uid 10002);
  - `Dockerfile.dockerignore` keeps `hub-public/` sources.
- `deploy/docs/docker-compose.yml`:
  - a new `hub-public` service: no ports, `edge` network, volume `portfolio-snapshot:/snapshot:ro`, same hardening and
    healthcheck as on the Pi;
  - docs-internal mounts `portfolio-snapshot:/portfolio-snapshot`;
  - **no `depends_on`** between the services; remove the existing ones (I1).
  - Volume ownership: images pre-create the mount points owned by uid 10003 (I1).
- `.env.example`: `DOCS_PORTFOLIO_URL`, `HUB_PUBLIC_JAVA_OPTS` (-Xmx160m).
- Gate: `docker compose config` passes.

**P5. Hub: remove the portfolio (only after P2-P4 are green).**
- `hub-internal`:
  - `git rm` of the moved package is already done by P2.
  - `InternalApplication` `import` prints "the portfolio moved to Docs: use `docs-internal.jar import`" and exits 2.
  - Remove `HUB_SNAPSHOT_DIR`/`hub.snapshot.*` and the `hub-snapshot` dependency (the hub no longer writes the snapshot).
- Web (`web/src/internal`):
  - remove the Portfolio route, nav link, palette entries, publish-status polling and the `publishInfo` prop;
  - replace the portfolio card with the link card (P-D7);
  - remove the manifest shortcut;
  - `Dashboard` test updates.
- `deploy/docker-compose.yml`: remove the `hub-public` service and the snapshot volume line; `.env.example` drops
  `HUB_PUBLIC_PORT`.
  - `hub-public/` and `deploy/Dockerfile.public` **stay in the repo** (the LXC builds them). README says the Pi no longer runs them.
- Hub tests:
  - `SchemaAndPragmasTest` and `BackupServiceTest` keep working (the tables still exist, P-D6).
  - Add a test that `/api/admin/portfolio/**` now returns 404 on the hub.
  - Add a test that `hub.db` migrations are unchanged (no V6).
- Gate: the full hub Java and Vitest suites.

**P6. Local stack and browser tests.**
- `scripts/run-local-docs.sh`:
  - also starts `hub-public` on **3911**, reading `.scratch/docs-e2e/portfolio-snapshot`;
  - runs `docs-internal.jar import` when `.scratch/export-out/portfolio-export.v1.json` exists (same files as today).
- `scripts/run-local.sh`: stops starting `hub-public` and the import.
- `web/e2e/e2e.mjs`:
  - the "public portfolio" and "portfolio editing" suites and the portfolio layout/phone tests move into `docs.e2e.mjs`;
  - INT becomes `127.0.0.1:3920/#/portfolio`; PUB stays 3911;
  - the hub dashboard e2e drops the "Public portfolio" tile and checks the new link card.
  - New e2e: editing in Docs updates the public portfolio, and `migrate-portfolio` from a hub DB gives an identical public JSON.
- Gate: `scripts/e2e.sh` green (both stacks).

**P7. Docs.**
- README: architecture diagram, modules, the "Publishing the portfolio" section, security rows, the Deploy and import commands,
  operations, and the Pi no longer exposes anything publicly.
- `docs/plan.md`: entry §18.q.
- Update `docs/docs-feature-plan.md` D4/D-L6 notes.
- Extend `docs/docs-setup-guide.md` with the **cutover runbook** below.

## Cutover runbook (owner, after the code is merged; goes into the setup guide)
1. **Freeze:** do not edit the portfolio from here on.
   - On the hub, make a fresh backup (Config/Backup → "Back up now", or the nightly file in `data/hub/backups/`).
   - Copy that `.db` file to the LXC: `/opt/homelab-docs/data/internal/import/hub-backup.db`. Copy it via your laptop with
     `scp`; the LXC firewall blocks the Pi.
2. On the LXC:
   - `git pull`, then `docker compose ... up -d --build`. docs-internal now has empty portfolio tables, and hub-public starts.
   - Dry run:
     `docker compose ... run --rm docs-internal migrate-portfolio --from /data/import/hub-backup.db --dry-run`
   - Then run the same command without `--dry-run`. It prints the per-table counts.
   - Restart docs-internal so it publishes the snapshot.
3. **Check before switching:**
   - In Docs → Portfolio, the content is all there.
   - From the LXC:
     `docker compose exec docs-internal curl -s http://hub-public:3011/api/public/portfolio | head -c 300`
     returns JSON (curl runs inside docs-internal; hub-public publishes no port).
4. **Cloudflare:**
   1. Pi tunnel → remove the `<portfolio-host>` public hostname.
   2. Docs tunnel → add the public hostname `<portfolio-host>` → `http://hub-public:3011`, with HTTP Host Header empty.
   3. DNS: the `<portfolio-host>` CNAME now points to `<docs-tunnel-id>.cfargotunnel.com` (proxied).
5. Check `https://<portfolio-host>` over mobile data. Check `https://random.domain.com` is still a 404 from docs-public.
6. **Pi:**
   - Deploy the new hub; it has no portfolio and no hub-public.
   - `docker rm -f hub-public` if the old container is left over.
   - If the Pi tunnel serves no other hostname: `sudo systemctl disable --now cloudflared` (or remove its container).
     Otherwise leave it running.
7. **Rollback:** the Pi's `hub.db` still holds the portfolio unchanged.
   - Redeploy the previous hub release with `hub-public`.
   - Point the hostname back to the Pi tunnel.
   - Any edits made in Docs after the cutover would have to be redone.

## Risks
- **Edits during cutover are lost** (the freeze in step 1). The CLI's `--replace` allows a clean re-run.
- **Schema drift** between the hub and Docs copies of the DDL is caught by `PortfolioSchemaParityTest`.
- **A wildcard swallowing the portfolio host:** an explicit hostname and DNS record always win. The guide calls out adding both.
- **The whole LXC off means the portfolio is offline.** This follows from "switch immediately"; stopping only the Docs
  containers keeps it online (I6).
- **LXC memory:** a third JVM (hub-public, -Xmx160m). Measure with `docker stats`; 2 GB is enough on paper.
- **Old links:** the hub's PWA shortcut and bookmarks to `hub/#/portfolio` now land on the dashboard, whose card links to Docs.
- **Backups:** portfolio data now lives in `docs.db` (nightly `VACUUM INTO`, plus the LXC backup). The hub backups no longer
  contain current portfolio data.

## Critical files
- Move: `hub-internal/src/main/java/com/homelabhub/internal/portfolio/*` → `docs-internal/src/main/java/com/homelabhub/docs/portfolio/`.
  Tests likewise.
- New: `docs-internal/.../db/migration/V2__portfolio.sql`, `PortfolioMigrator.java`, `PortfolioSchemaParityTest`, `PortfolioMigratorTest`.
- Edit:
  - Java: `DocsApplication.java`, `docs-internal/pom.xml`, `application.yml` (docs), `InternalApplication.java`, `hub-internal/pom.xml`.
  - Web: `web/src/internal/App.tsx`, `Dashboard.tsx`, `public/manifest.json`, `web/src/docs-internal/App.tsx`,
    `shared/components/Palette.tsx`, `PortfolioPage.tsx`.
  - Deploy: `deploy/docker-compose.yml`, `deploy/.env.example`, `deploy/docs/Dockerfile`, `docker-compose.yml`, `.env.example`.
  - Scripts and tests: `scripts/run-local.sh`, `run-local-docs.sh`, `web/e2e/e2e.mjs`, `docs.e2e.mjs`, `dashboard.e2e.yaml`.
  - Docs: README, `docs/*`.
- Reuse unchanged: `hub-public` (all of it), `hub-snapshot`, `SnapshotPublisher` logic, `PortfolioCrud`/`Reader`/`Resources`,
  `PortfolioPage`/`SkillPanel`/`filter`, `AuthSecurity`.

## Verification
- `scripts/build-all.sh` is green:
  - all modules, the hub-public enforcer rule, both public bundle checks, Vitest;
  - the moved portfolio tests (18 + 15 + 4) pass in docs-internal;
  - new parity and migrator tests;
  - the hub suite minus the moved tests, plus the new 404 tests.
- `scripts/e2e.sh` is green:
  - hub: no Portfolio nav, the link card works, every other suite unchanged;
  - Docs: the portfolio editing and public-portfolio suites against 3920/3911, and migrate from a real hub DB copy.
- By hand, locally:
  - `curl http://127.0.0.1:3911/api/public/portfolio` returns the same JSON (apart from `generatedAt`) as the hub produced
    before, for the same data.
- Independence: the tests in "Independence" pass. That means the hub suites run with the Docs stack down, the Docs suites run
  with the hub stack down, and the portfolio is still served with docs-internal stopped.
- On the LXC (owner, after cutover):
  - `ss -ltn` shows no 3011;
  - the portfolio opens on its hostname over mobile data;
  - Docs pages are still fine; the Pi no longer serves anything public.
