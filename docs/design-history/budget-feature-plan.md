# Budget page for Homelab Hub: implementation plan

> Created: 2026-09-27 · Status: Implemented (see `docs/plan.md` 18.t; migration is `V4`, not `V3`)

This plan is self-contained. It was written from a long design conversation with the owner (Tabish). You (the implementing agent) have not seen that conversation, so everything you need is here. Where something was decided, it says **Decided**. Where it is a refinement the owner has not seen, it says **Refinement** and you must mention it in your final report.

## 0. Read this first: project, git and working rules

### 0.1 The project
"Homelab Hub" is one self-hosted app for the owner's Raspberry Pi 4 (arm64, Docker). Two containers:
- `hub-internal` (port 3010, host networking, reachable from LAN and Tailscale only): dashboard, power actions, YAML config editor, portfolio editor, legacy routes. **The budget feature lives only here.**
- `hub-public` (port 3011): read-only public portfolio. **Do not touch it.** Nothing from the budget feature may reach it (a build script checks the public bundle for leaks).

Stack: Java 17, Spring Boot 3.5, plain JDBC (`JdbcClient`, no Hibernate), Flyway, SQLite (WAL). Maven multi-module (`hub-snapshot`, `hub-public`, `hub-internal`). Frontend: Vite + React 19 + TypeScript in `web/` (hash routing, no router library), CodeMirror only on the Config page (lazy loaded). Tests: JUnit 5 + AssertJ + Spring test, Vitest, Playwright browser tests (`web/e2e/e2e.mjs`).

Read these in the repo before starting: `README.md` (architecture, security, config, API overview), `docs/plan.md` (decisions and a dated progress log in section 18, including lessons and defects), and open `docs/mockups/budget.html` in a browser (the approved mockups, see section 5).

### 0.2 Git
- Repo: `https://github.com/TabTQ/homelab-hub-and-docs`, branch `main`, HEAD at time of writing: `566d5bd` ("Add installable app (PWA) support and optional Secure cookies").
- `docs/mockups/budget.html` is currently **untracked**; commit it together with the feature.
- **Work on a feature branch** `feature/budget`. **Do not commit, push or merge unless the owner asks** (standing rule). When asked to commit, end the message with the line `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` (or the model that made the commit).
- `.gitignore` already excludes `secrets.env`, `.env`, `*.db`, `.scratch/`, `node_modules`, `dist`, `hub-*/src/main/resources/static/`, `__pycache__`. Never commit anything from `.scratch/` (real portfolio export, real config). Before any commit: `git status`, and scan staged files for secrets (passwords, tokens, keys). The owner asked earlier that real hostnames (Tailscale name, company domain) are **not** in the repo: use placeholders like `<pi>.<tailnet>.ts.net`, `example.com`. Sample people in tests and docs are placeholders (Anu, Ravi, Meera).

### 0.3 Environment
- Build tools live outside PATH: JDK `~/tools/jdk-17.0.20.1+1`, Maven `~/tools/apache-maven-3.9.11/bin`. `scripts/build-all.sh` sets them; for ad-hoc Maven use:
  `export JAVA_HOME="$HOME/tools/jdk-17.0.20.1+1" PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.11/bin:$PATH"`.
- Node 22+, Python 3, Chromium at `/usr/bin/chromium` (Playwright uses `playwright-core` with `executablePath` and `--no-sandbox`).
- **Docker is NOT usable on this dev machine** (socket permission denied). Images are built on the Pi.
- The Maven cache is offline-capable, but **Apache PDFBox is not in `~/.m2` yet**: the first build after adding it needs network (run Maven without `-o` once).
- Commands: `scripts/build-all.sh` (web unit tests + build, copies both SPAs into the jars, `mvn verify`), `scripts/e2e.sh` (starts the stack on ports 3910/3911 with a mock Proxmox and runs all browser tests; needs a prior build), `scripts/run-local.sh` / `scripts/stop-local.sh` (manual testing; dashboard password for local runs is `local-test-pass`, data in `.scratch/e2e-data`, wiped on each run).
- Baseline at HEAD `566d5bd` (re-run to confirm, Phase 0): about 410 Java tests, 62 Vitest tests, 44 Playwright tests, all passing.
- Tool timeouts: a foreground shell command is limited to 10 minutes; run long builds/deploys in the background and wait with a monitor. Do not chain sleeps.

### 0.4 Rules you must follow (owner's standing instructions)
- Do **not** print, copy or commit credentials. The Pi's `deploy/.env` (password hash, Proxmox token) and `data/hub/secrets.env` exist only on the Pi.
- Never run a real Proxmox shutdown/reboot/start/stop or send real Wake-on-LAN without the owner's explicit go-ahead. This feature does not need any of that.
- **Ask before deploying to the Pi**, restarting Pi containers, changing the Pi firewall, or touching other containers there. Keep ports 3010/3011. The owner has an installed phone app (PWA) and uses `https://<pi>.<tailnet>.ts.net` through `tailscale serve`; do not change that setup.
- State assumptions, do not claim anything is verified unless you verified it, handle non-happy paths, and say plainly what is untested.
- Use they/them for people unless told otherwise.
- Write code in the style of the surrounding code (comment density, naming, idiom). Add tests for everything and run the relevant suites before saying something is done.
- To ask the owner for a secret, use a graphical prompt (`pinentry-qt`, or the `pass-entry` skill), never the chat. (This feature needs none.)

## 1. Goal and background

Add a **Budget** page (top-nav entry next to Dashboard / Portfolio / Config) for tracking **shared monthly expenses** among a few people, Splitwise-style:
- Pick a month; add expenses one at a time: what it was, how much (INR), **who paid**, and **who it is split among** (equal split among the ticked people).
- People are set up once (a People list) and appear as choices in every form. A person can be added at any time, even mid-month, including from inside the add-expense form.
- **Recurring expenses** (rent, bills): defined once with payer, split, amount, start month, optional end month, optional day of month; each month's entry is created automatically when that month is opened.
- A **month summary**: per person paid / share / net, and **who pays whom** (a short list of transfers). Unpaid balances **carry over** to later months. The owner marks payments as done (**Mark paid**), including **part payments** by entering the amount actually paid.
- **Export a PDF** (for a chosen month, or a custom date range) from the owner's **phone browser**, then share it with the other people (WhatsApp, email...) via the phone's share sheet, with a download fallback.

Who uses it: only the owner. **No separate login**: it is protected by the existing dashboard login (session), like the Config and Portfolio editors.

**It must only consume resources while its page is open**: the page's JavaScript is lazy-loaded; the server side has no scheduler, no polling, no cache, no background thread; recurring entries are created when a month is opened, not by a job.

Currency: **INR only**. The owner asked for rupees with decimals. Internally, money is stored exactly as **integer paise** (see decision D1).

## 2. Requirements

### Functional
- [ ] R1 People list: add, rename, archive, restore. Added any time. Not locked by month.
- [ ] R2 Month selector (arrows + month picker); every month stays editable (no closing/locking).
- [ ] R3 Add/edit/delete an expense: payment name, amount (INR), paid by (one person), split among (checkbox chips of people, All/None), **month required, day optional**; if no day is given the date is stored as the **15th** and shown as "Sep (no day)".
- [ ] R4 "+ Add person" inside the expense (and recurring) form saves a person to the People list and ticks them.
- [ ] R5 Recurring expenses: name, amount, paid by, split among, start month, optional end month (inclusive), optional day of month (blank = 15th; 31st becomes the last day of shorter months). Created once per month when that month is opened. Edit/delete one generated entry without affecting others; a deleted generated entry is not created again. Editing the recurring definition affects only months not yet opened.
- [ ] R6 Month summary: per person paid, share, this-month net, carried in, settled (only when there is a settlement), to settle; totals row; **who pays whom** transfers; expense list; "what everyone paid each month" table (last months).
- [ ] R7 Carry-over: balances accumulate across months; unpaid amounts carry into later months automatically.
- [ ] R8 **Mark paid** on a transfer opens a small dialog: amount (prefilled with the suggested transfer, **editable for a part payment**), date (optional; **if empty the settlement is counted on today's date**), optional note. Saving records a settlement; the transfer list recomputes (a part payment leaves a smaller remainder). Settlements are listed and can be deleted (undo).
- [ ] R9 Export PDF with **three choices**: **This month**, **Choose month** (month picker, defaults to the month being viewed), **Choose range** (From/To dates). Carried-in balances for a custom range are computed properly (balance just before the range start). The export dialog previews carried-in, counts and the resulting transfers before exporting.
- [ ] R10 PDF is shared from the phone: fetch the file, use the Web Share API with the file where supported (`navigator.canShare({files})`), otherwise download. Also a plain Download button.
- [ ] R11 Amounts shown in Indian grouping (`₹1,25,000.50`), two decimals always.
- [ ] R12 Responsive, phone-first: forms are bottom sheets on phones, dialogs on desktop; light and dark themes; works inside the installed PWA.

### Non-functional / constraints
- [ ] N1 Java 17 only. No Java 21 APIs (no pattern-matching `switch`, no `Thread.ofPlatform`, no `List.reversed()`/sequenced collections). Past defects came from this.
- [ ] N2 Money is exact: integer paise everywhere in code, DB and JSON. No `float`/`double` for money.
- [ ] N3 Everything under `/api/admin/budget/**`, so it is authenticated in both `HUB_AUTH_MODE=all` and `edit` modes with **no change to `SecurityConfig`** (mode `none` is open by the owner's choice).
- [ ] N4 No new third-party requests at runtime; the CSP (`default-src 'self'`) must keep working. Fonts self-hosted.
- [ ] N5 No background work for this feature (no `@Scheduled`, no threads, no caches).
- [ ] N6 The public bundle leak check (`web/scripts/check-public-bundle.mjs`) must still pass. Budget code must not enter the public bundle.
- [ ] N7 The nightly SQLite backup (`VACUUM INTO` in `BackupService`) must include the budget tables (it copies the whole DB; verify).
- [ ] N8 Memory: `hub-internal` idle RSS was ~233 MiB (243 MiB after ~1000 requests) with `-Xmx256m`. Re-measure after this feature; PDF generation must fit in that heap.
- [ ] N9 Handle non-happy paths: validation errors shown field by field, optimistic-lock conflicts (409), missing/archived people, empty splits, overflow, invalid dates, server unreachable.

### Out of scope (do not build)
- Categories, budget limits/caps, multi-currency, unequal splits (custom amounts/percentages/shares), weekly/yearly recurring, closing/locking months, per-person logins, importing an existing spreadsheet (there is none), notifications, charts, publishing anything to the public site, an audit log for budget changes (see D12), non-English text/fonts (owner: "all English only").

## 3. Decisions and rationale

| # | Decision | Rationale | Alternatives considered |
|---|---|---|---|
| D1 | Store money as **integer paise** (`amount_paise INTEGER`). Show/enter rupees with up to 2 decimals. The wire format (both directions) is integer paise; the UI converts rupee text to paise by exact string parsing. | Floats cannot represent 33.33 exactly; splits and balances would drift and never sum to exactly zero. The owner asked for rupees-with-decimals in the UI (unchanged) and accepted exact storage being invisible. | Floating point rupees (rejected); decimal strings on the wire (rejected: two representations). |
| D2 | Equal split among ticked people; the **leftover paise are handed out one each in ascending person id order** among the sharers. | Deterministic; shares always sum exactly to the amount. Owner example: ₹1,850.50 ÷ 3 -> Anu 616.84, Ravi 616.83, Meera 616.83 (Anu created first). | Random/last-person remainder. |
| D3 | Shares are **stored per expense** (`budget_expense_shares`), computed at save time. | History stays stable if people are added later; a new person never silently joins old expenses. | Recompute from a member list on read. |
| D4 | Balances are a **running ledger across months**: `net = paid - share + settlements_out - settlements_in`, evaluated **as of a date**. A month view is the statement as of month end; carried-in is the balance as of the day before the period starts. | Carry-over falls out of the maths; a past month never changes because of something marked later. | Per-month closing with manual carry entries. |
| D5 | A **settlement belongs to its own date** (`settled_on`). It defaults to today (in zone `Asia/Kolkata`, configurable) when the owner enters no date. It counts in every statement whose date range/as-of date includes it. | Owner: "settlement should be counted on the date marked paid if no date is entered". | Attach to the viewed month. |
| D6 | **Part payments** allowed: settlement amount is any positive value; the UI prefills the suggested transfer and warns (does not block) if the amount exceeds it. An overpayment simply flips the balance. | Owner asked for part payment. A settlement is a record of a real payment; blocking would make records wrong. | Reject amounts above the suggested transfer. |
| D7 | "Who pays whom" uses a **greedy largest-debtor/largest-creditor** planner, deterministic (ties by person id). At most n-1 transfers, **not guaranteed to be the absolute minimum** in every case (that problem is NP-hard); do not claim it is. | Simple, explainable; fine for a handful of people. | Exact minimum via subset search (overkill). |
| D8 | Recurring occurrences are **materialised as ordinary expense rows when a month is opened** (idempotent), never by a scheduler. Generated rows are independent afterwards. A deleted generated row leaves a **skip record** so it is not re-created. | Owner: service must be idle unless the page is open. Editing/deleting one month's entry must not affect others; opened months must not change when the definition changes. | Virtual occurrences computed on every read (edits per month get complicated). |
| D9 | Opening a month first materialises **all months from the earliest applicable recurring start month through that month** (carry-in must include earlier months' recurring entries). Bounded (see 8). Export does the same for its end month. | Otherwise carried-in balances would miss recurring expenses of months never opened. | Materialise only the opened month (wrong carry-in). |
| D10 | **Archive, never delete, people** with history. Archived people are hidden from new forms but keep all history and any unsettled balance. Archiving is **blocked** while the person is payer or sharer of an active recurring expense (message lists them). | Deleting would break old expenses. Blocking avoids generating entries for someone who left. | Cascade delete; silently drop from recurring splits. |
| D11 | Everything under `/api/admin/budget/**`. | Reuses the existing auth modes without touching security config. | New path prefix with its own rules. |
| D12 | **No audit-log entries** for budget changes. Rely on nightly backups. | Single user; the existing `audit_log` is for power actions (`AuditLog.record(Source{ui,legacy}, ...)`). | Extend audit log. |
| D13 | PDF generated **server-side with Apache PDFBox** with an **embedded Noto Sans** TTF (Regular + Bold) so `₹` renders. Built only when the export endpoint is called. | Consistent output on every phone; the standard PDF fonts have no `₹`. | Client-side jsPDF; browser print (not possible from the installed app). |
| D14 | Frontend loads the page as a **lazy chunk** (`React.lazy`), and the page has **no polling/SSE**. | Owner: not consuming resources unless the page is open. | Keep it in the main bundle. |
| D15 | Export choices: **This month** = the calendar month of "today" in the budget zone; **Choose month** = month picker defaulting to the month currently viewed; **Choose range** = From/To dates. | Owner: "export should have one more option of choosing month". **Refinement:** the exact meaning of "This month" is my reading; keep it easy to change. | "This month" = viewed month. |
| D16 | Month-only entries count as the **15th** in date ranges (a range 1-10 Sep excludes them; 1-20 Sep includes them). The PDF marks them "no day". | Owner: default to the 15th. | Treat as whole-month entries. |
| D17 | **Refinement (not in the mockups):** add a **Settled** column to the people table (screen and PDF) **only when there is at least one settlement in the period**, and add a small **Settlements card** to the month screen listing settlements in the viewed month (date, from -> to, amount, note, Delete). | Without them the identity `carried in + this month = to settle` breaks after a part payment and there would be nowhere to undo a settlement. | Hide settlements. |
| D18 | **Refinement:** the mock said "includes ₹300.00 carried in from August" under one transfer. Real behaviour: transfers are netted across everyone, so show **one line under the transfers**: "Carried in from before: Ravi owes Meera ₹300.00" (produced by running the same planner on the opening balances). | Per-transfer attribution is not well-defined after netting. | Per-transfer notes. |
| D19 | Amount cap: up to ₹10,00,00,000.00 (100,000,000,00 paise = `10_000_000_000` paise) per expense/settlement/recurring. Max 50 people. Month range 2000-01 to 2100-12. Statement range at most 5 years. | Overflow and abuse safety. | No caps. |

## 4. Current state and discoveries (verified in the repo at `566d5bd`)

- `hub-internal/src/main/java/com/homelabhub/internal/` packages: `actions audit auth backup config dashboard legacy portfolio proxmox status widgets wol`, plus `HubBeans.java`, `WebConfig.java`, `InternalApplication.java`. Create the new package `com.homelabhub.internal.budget`.
- Controller style: `@RestController` classes with `@GetMapping("/api/admin/...")` etc. (see `portfolio/PortfolioController.java`, `backup/BackupController.java`, `config/ConfigController.java` with `@RequestMapping("/api/admin/config")`). `PortfolioController` maps `PortfolioCrud.ValidationException` (422 with a field->message map), `ConflictException` (409), `NoSuchElementException` (404), `HttpMessageNotReadableException` (400) and SQLite constraint errors (`UncategorizedSQLException`, message contains `constraint`/`UNIQUE`/`FOREIGN KEY`) via `@ExceptionHandler`s. **Copy this pattern** (a budget-specific `@RestControllerAdvice` limited to the budget controller, or handlers in the controller) so the UI can show field-level errors. SQLite constraint failures do NOT surface as `DataIntegrityViolationException`.
- Persistence: `JdbcClient` and `TransactionTemplate`; SQLite via `sqlite-jdbc`; Flyway migrations in `hub-internal/src/main/resources/db/migration/` (`V1__initial_schema.sql`, `V2__homelab_skills_and_versions.sql`). **The new migration is `V3__budget.sql`.** Existing style: `INTEGER PRIMARY KEY AUTOINCREMENT`, `version INTEGER NOT NULL DEFAULT 0` for optimistic locking, `ON DELETE CASCADE` link tables, `TEXT` timestamps. `foreign_keys=on` is set per connection in the JDBC URL.
- Optimistic locking pattern: PUT bodies carry `version`; mismatch -> 409 (see `PortfolioCrud`).
- Auth: `SecurityConfig` protects `/api/**` in mode `all` and `/api/admin/**` in mode `edit`; CSRF is cookie-to-header (`XSRF-TOKEN` / `X-XSRF-TOKEN`) and the frontend helper `web/src/shared/api.ts` (`api()`, `apiWithHeaders()`, `primeCsrf()`) handles it. CSP: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; object-src 'none'; ...`. Blob downloads via an anchor are fine.
- Tests: `hub-internal/src/test/java/com/homelabhub/internal/TestSupport.java` (`tempDir()`, `useTempDatabase(registry, dir)`); API tests use `@SpringBootTest` (+ `@AutoConfigureMockMvc` or `RANDOM_PORT`), `@MockitoBean ProxmoxClient` (and `HostMetrics` in some), `@DynamicPropertySource` with `hub.auth.password-hash`/`hub.auth.mode`. See `auth/AuthApiTest.java`, `portfolio/PortfolioCrudApiTest.java`, `StaticResourcesTest.java`, `SchemaAndPragmasTest.java`.
- Frontend: `web/src/internal/App.tsx` has `type Route = "dashboard" | "portfolio" | "config"`, `useRoute()` parsing `location.hash` (`#/portfolio`, `#/config`), a nav array `(["dashboard","portfolio","config"] as Route[])` with labels, and `const ConfigPage = lazy(() => import("./pages/ConfigPage"))` wrapped in `Suspense`. Pages are in `web/src/internal/pages/`, components in `web/src/internal/components/`, shared UI in `web/src/shared/`. The command palette (`web/src/shared/components/Palette.tsx`, fed from `App.tsx`) lists pages, services and actions: add a "Budget" page entry.
- **Trap:** the `Modal` component is exported from `web/src/internal/pages/ConfigPage.tsx`, which imports CodeMirror. Importing it from the budget page would drag CodeMirror into the budget chunk. **Move `Modal` to `web/src/internal/components/Modal.tsx`** and import it from both pages (task 5.1).
- CSS: one file `web/src/shared/styles.css` with tokens (`--bg --surface --surface2 --ink --muted --line --accent --accent-ink --accent-soft --ok --warn --bad`, `--radius`, `--f-display/--f-body/--f-mono`), light on `:root`, dark via `prefers-color-scheme` and `[data-theme="dark"]`. Existing classes: `.btn .btn.pri .btn.sm .btn.danger`, `.card`, `.tape`, `.eyebrow`, `.field`, `.help`, `.err`, `.banner`, `.overlay .dialog .modal .mrow` (dialogs: height-limited to the window, scroll inside, sticky button row: keep this behaviour), `.pick`, `.chip`-like styles may not exist (check). **Lesson:** a generic class name `.w` once collided with another rule and drew a stray box: **prefix all new classes with `bud-`**.
- The fonts bundled in the web app are IBM Plex Sans/Mono and Archivo (fontsource). The PDF does NOT use them.
- Noto Sans TTFs exist on this machine at `/usr/share/fonts/noto/NotoSans-Regular.ttf` and `NotoSans-Bold.ttf` (static fonts). **Not yet verified: that they contain U+20B9 (₹) and U+2212 (−).** `fontTools` is not installed; verify with PDFBox itself (`PDType0Font#hasGlyph`) in a unit test (task 4.2).
- Java Locale caveat: do not rely on `NumberFormat` for `en-IN` grouping (a slim JRE may lack locale data). Hand-write the Indian grouping formatter (task 1.1) and its TypeScript twin (task 5.2), and test both with the same vectors.
- `README.md` already has sections Features, Architecture (Mermaid), Data model (ER diagram), Configuration, Security, API overview, Build and test (with suite counts), Deploy, Operations. Update them (Phase 7).
- The PWA service worker (`web/src/internal/public/sw.js`) only touches `/assets/*` and page navigations; API calls, including the PDF download, pass straight through. No change needed.
- Deployment target facts: the Pi is ssh alias `rpi4`; the app lives in `~/homelab-hub` (compose file `deploy/docker-compose.yml`, `.env` there is secret and must be excluded from rsync); images are built on the Pi with `docker build -f deploy/Dockerfile.internal`; the Pi kernel has no memory cgroup, so heap is set explicitly (`HUB_INTERNAL_JAVA_OPTS`, default `-Xmx256m -XX:+UseSerialGC -Xss512k`); the container has a read-only root filesystem, user `hub` (uid 10001, home `/nonexistent`), `/tmp` tmpfs mounted `exec`, `cap_drop: ALL` (+`NET_RAW`), `no-new-privileges`, and a Watchtower opt-out label (images are local-only). Startup takes ~30 s; the health check is `GET /actuator/health`.

## 5. Design / approach

### 5.1 The approved mockups
File: `docs/mockups/budget.html` (self-contained; tabs for 7 screens; light/dark toggle; interactive chips, day toggle, add-person, Mark paid toggle, export mode toggle). It uses the app's design tokens and system-font fallbacks. **Match its look** using the app's real CSS tokens/classes. Screens: (1) Month desktop, (2) Month phone, (3) Add expense sheet, (4) Recurring list + form, (5) People, (6) Export sheet, (7) PDF preview. Sample data there (Anu/Ravi/Meera, September 2026) is verified to add up and is the **reference test data** (see 5.6).

Owner-approved changes to the mockups since they were shown: **(a)** Export gets a third option, choosing a month (D15). **(b)** Mark paid allows a part-payment amount and an optional date defaulting to today (R8, D5, D6). Everything else "looks good as per the mockups". Additions the owner has **not** seen (D17, D18) must be built in the same visual style and called out in your report. Optionally update `budget.html` to show them (Settled column, Settlements card, three-way export choice, Mark paid dialog) so the owner can see them.

### 5.2 Data model (`V3__budget.sql`)
```sql
CREATE TABLE budget_people (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL COLLATE NOCASE UNIQUE,            -- 1..60 chars, trimmed
    archived    INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    version     INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

CREATE TABLE budget_recurring (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT    NOT NULL,                                  -- 1..100
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
    paid_by      INTEGER NOT NULL REFERENCES budget_people (id),
    start_month  TEXT    NOT NULL,                                  -- 'YYYY-MM'
    end_month    TEXT,                                              -- 'YYYY-MM', inclusive, NULL = no end
    day_of_month INTEGER CHECK (day_of_month BETWEEN 1 AND 31),     -- NULL = the 15th ("no day")
    version      INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    CHECK (end_month IS NULL OR end_month >= start_month)
);
CREATE TABLE budget_recurring_people (
    recurring_id INTEGER NOT NULL REFERENCES budget_recurring (id) ON DELETE CASCADE,
    person_id    INTEGER NOT NULL REFERENCES budget_people (id),
    PRIMARY KEY (recurring_id, person_id)
);
CREATE TABLE budget_recurring_skips (           -- generated entry the owner deleted: do not create it again
    recurring_id INTEGER NOT NULL REFERENCES budget_recurring (id) ON DELETE CASCADE,
    month        TEXT    NOT NULL,
    PRIMARY KEY (recurring_id, month)
);

CREATE TABLE budget_expenses (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,                               -- "payment name", 1..100
    amount_paise    INTEGER NOT NULL CHECK (amount_paise > 0),
    paid_by         INTEGER NOT NULL REFERENCES budget_people (id),
    expense_date    TEXT    NOT NULL,                               -- 'YYYY-MM-DD', effective date (15th when day_set = 0)
    day_set         INTEGER NOT NULL CHECK (day_set IN (0, 1)),     -- 0 = the owner gave only a month
    recurring_id    INTEGER REFERENCES budget_recurring (id) ON DELETE SET NULL,
    recurring_month TEXT,                                           -- 'YYYY-MM' for generated rows
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
CREATE INDEX idx_budget_expenses_date ON budget_expenses (expense_date);
CREATE UNIQUE INDEX uq_budget_expense_occurrence ON budget_expenses (recurring_id, recurring_month) WHERE recurring_id IS NOT NULL;
CREATE TABLE budget_expense_shares (
    expense_id  INTEGER NOT NULL REFERENCES budget_expenses (id) ON DELETE CASCADE,
    person_id   INTEGER NOT NULL REFERENCES budget_people (id),
    share_paise INTEGER NOT NULL CHECK (share_paise >= 0),
    PRIMARY KEY (expense_id, person_id)
);

CREATE TABLE budget_settlements (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    from_person  INTEGER NOT NULL REFERENCES budget_people (id),
    to_person    INTEGER NOT NULL REFERENCES budget_people (id),
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
    settled_on   TEXT    NOT NULL,                                  -- 'YYYY-MM-DD'
    note         TEXT,                                              -- optional, up to 200
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    CHECK (from_person <> to_person)
);
CREATE INDEX idx_budget_settlements_date ON budget_settlements (settled_on);
```
Notes: the CHECK on `budget_expenses` for cap (`amount_paise <= 10000000000`) can be added too; validate in code regardless. Deleting a recurring definition keeps its generated expenses (`ON DELETE SET NULL`) and cascades its skips. When a generated expense is deleted, insert its `(recurring_id, recurring_month)` into `budget_recurring_skips` in the same transaction.

### 5.3 Core algorithms (pure Java, no Spring, fully unit-tested)

**Split (D2).** `split(amountPaise, sharerIdsAscending)`: `base = amount / n`, `extra = amount % n`; the first `extra` sharers (ascending person id) get `base + 1`, the rest `base`. Invariants: sum = amount; each share differs from any other by at most 1 paisa; n >= 1.

**Balance as of a date.** For person p and cut-off date D (inclusive):
`net(p, D) = SUM(amount of expenses with date <= D and paid_by = p) - SUM(share_paise of p over expenses with date <= D) + SUM(settlements with settled_on <= D and from_person = p) - SUM(settlements with settled_on <= D and to_person = p)`.
Positive = is owed money; negative = owes. `SUM over all people of net = 0` always. Check: A owes -100, B is owed +100; A pays B 100 -> A: -100 + 100 = 0, B: +100 - 100 = 0.

**Period statement for [from, to] (dates inclusive).** Per person:
- `opening = net(p, from - 1 day)` (carried in)
- `paid`, `share` over expenses dated in the range; `periodNet = paid - share`
- `settledEffect = SUM(settlements in range where from = p) - SUM(settlements in range where to = p)` (shown as the "Settled" column)
- `toSettle = opening + periodNet + settledEffect`, which equals `net(p, to)`.
- Totals row: paid total = share total; the net columns total 0.
- `transfers = planTransfers(toSettle for all people)`; `openingTransfers = planTransfers(opening)` for the "carried in" sentence (D18).
- People shown: all non-archived people, plus archived people with any activity or a non-zero opening/closing balance.
A **month view is the period `[first of month, last of month]`**. Balances of later months never affect it.

**Transfer planner (D7).** Input: map person -> net. Debtors (net < 0) and creditors (net > 0), each sorted by absolute amount descending, ties by person id ascending. While both non-empty: take the largest debtor and largest creditor, `t = min(|debt|, credit)`, emit `from=debtor, to=creditor, t`, subtract, drop zeros. Output is deterministic and has at most n-1 transfers. Invariant test: applying the transfers as settlements zeroes every balance.

**Recurring planner.** For month `M` (YYYY-MM) a definition is **active** if `start_month <= M` and (`end_month` is null or `end_month >= M`). Its date in M: `day_of_month` null -> the 15th with `day_set = 0`; otherwise `min(day_of_month, lengthOfMonth(M))` with `day_set = 1` (Feb in leap years handled by `YearMonth.lengthOfMonth()`).
**Materialise(M)** (D8, D9): for each month `m` from `min(start_month of definitions)` (but not earlier than 2000-01) through `M`, for each active definition without an expense `(recurring_id, recurring_month = m)` and without a skip row: insert the expense (name, amount, payer, date, day_set, recurring ids) and shares = `split(amount, sorted person ids of the definition's people)`. Idempotent (the unique index guards concurrency). Runs in one transaction. Cap: at most 240 months processed per call; reject `M` beyond current month + 24 months or before 2000-01 with a validation error.

**Edit semantics.** Editing a generated expense edits only that row (keeps `recurring_id`/`recurring_month`, so it is not regenerated). Editing a recurring definition changes only months not yet materialised. Deleting a definition keeps generated rows.

**Validation rules (server side, all reported field by field with 422):**
- expense/recurring name: trimmed, 1..100 chars, no control characters; amount `1 .. 10_000_000_000` paise; `paidBy` exists; split non-empty (max 50), all ids exist and are not duplicated; new/edited expenses may not add an archived person, but archived people already on that expense may stay.
- person name: trimmed, 1..60, unique case-insensitively (409 on duplicate), max 50 people.
- month `YYYY-MM` within 2000-01..2100-12; `day` (optional) 1..length of that month; a date given as `day` must be valid.
- settlement: `from != to`, both exist, amount > 0 and within cap, optional `date` valid (default today in the budget zone), note <= 200.
- range: `from <= to`, at most 5 years, dates within 2000-01-01..2100-12-31.
- Optimistic locking on PUT of people/recurring/expense (`version`), 409 on mismatch.
- Archiving a person is refused (422/409 with a clear message) while they are payer or sharer of an active (not ended) recurring expense.

### 5.4 REST API (`/api/admin/budget`, all JSON, all amounts integer paise, dates ISO)
| Method and path | Purpose |
|---|---|
| `GET /people` | list (id, name, archived, version, counts of expenses/recurring for the UI hint) |
| `POST /people` `{name}` | add |
| `PUT /people/{id}` `{name, archived, version}` | rename / archive / restore |
| `POST /months/{yyyy-MM}/open` | **materialise recurring entries through that month (D8/D9) and return the whole month view** |
| `GET /history?to=YYYY-MM&months=6` | paid totals per person per month for the "what everyone paid each month" table |
| `POST /expenses` `{name, amountPaise, paidBy, month, day?, splitIds[]}` | add |
| `PUT /expenses/{id}` `{... , version}` | edit (recomputes shares) |
| `DELETE /expenses/{id}` | delete; adds a skip row if generated |
| `GET /recurring` / `POST /recurring` / `PUT /recurring/{id}` / `DELETE /recurring/{id}` | manage definitions: `{name, amountPaise, paidBy, splitIds[], startMonth, endMonth?, day?, version}` |
| `POST /settlements` `{fromId, toId, amountPaise, date?, note?}` | record a (part) payment; `date` empty = today (budget zone) |
| `DELETE /settlements/{id}` | undo |
| `GET /statement?from=YYYY-MM-DD&to=YYYY-MM-DD` | period statement for any range (materialises through the range's end month); used by the export dialog preview |
| `GET /export.pdf?from=...&to=...` | the PDF; `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="budget-2026-09.pdf"` (month) or `budget-2026-08-10_to_2026-09-20.pdf` (range), `Cache-Control: no-store` |

Month view response (shape to implement):
```json
{
  "month": "2026-09", "from": "2026-09-01", "to": "2026-09-30",
  "people": [{"id": 1, "name": "Anu", "archived": false}],
  "expenses": [{"id": 1, "name": "Rent", "amountPaise": 3000000, "paidBy": 1, "date": "2026-09-01", "daySet": true,
                "recurringId": 3, "version": 0, "shares": [{"personId": 1, "paise": 1000000}]}],
  "statement": {
    "rows": [{"personId": 1, "paidPaise": 3000000, "sharePaise": 1361651, "periodNetPaise": 1638349,
              "carriedInPaise": 0, "settledPaise": 0, "toSettlePaise": 1638349}],
    "totals": {"paidPaise": 3904950, "sharePaise": 3904950, "periodNetPaise": 0, "carriedInPaise": 0, "settledPaise": 0, "toSettlePaise": 0},
    "transfers": [{"fromId": 2, "toId": 1, "paise": 851750}],
    "openingTransfers": [{"fromId": 2, "toId": 3, "paise": 30000}],
    "expenseCount": 5, "expenseTotalPaise": 3904950, "hasSettlements": false
  },
  "settlements": [{"id": 9, "fromId": 2, "toId": 1, "paise": 100000, "date": "2026-09-18", "note": null}],
  "history": [{"month": "2026-06", "paidPaise": {"1": 3000000, "2": 385000, "3": 290000}, "totalPaise": 3675000}]
}
```
Error bodies: follow `PortfolioController` (422 `{errors: {field: message}}`, 409 `{message}`, 404, 400).

Configuration: add `hub.budget.zone` (env `HUB_BUDGET_ZONE`, default `Asia/Kolkata`) to `application.yml` for "today". The container has no TZ set; **never use `LocalDate.now()` without the zone**. Inject a `Clock` (already a bean in `SecurityConfig`) so tests can fix the date.

### 5.5 PDF (Apache PDFBox, server side)
- Dependency in `hub-internal/pom.xml`: `org.apache.pdfbox:pdfbox` (3.0.x, pick the current stable release; it includes `org.apache.pdfbox.text.PDFTextStripper` used by tests). Fonts: copy `/usr/share/fonts/noto/NotoSans-Regular.ttf` and `NotoSans-Bold.ttf` into `hub-internal/src/main/resources/fonts/` together with the SIL OFL license text (`OFL.txt`; Noto Sans is OFL 1.1) and mention the font and license in the README. Load with `PDType0Font.load(document, inputStream)` (embedded, subset). **Never use the standard 14 fonts** (no `₹`).
- Layout per the approved PDF mockup (A4 portrait, margins ~40 pt, body ~10 pt, black on white): title "Budget summary · September 2026" (a range: "10 Aug 2026 – 20 Sep 2026"); subtitle "Period 1 Sep 2026 – 30 Sep 2026 · Generated <date, budget zone> · All amounts in ₹"; **Who pays whom** (rows "Ravi pays Anu ... ₹8,517.50"; when there are none: "Nobody owes anything."), then the carried-in sentence "Carried in from before this period: Ravi owes Meera ₹300.00." (from `openingTransfers`; omitted when none); **People** table (Person, Paid, Share, This period, Carried in, [Settled only if any settlement in the period], To settle; totals row); **Expenses** table (Date, Payment, Paid by, Split among (names, comma-separated), Amount; total row; entries with `day_set = 0` show "Sep (no day)" instead of a day); **Payments marked as settled** (date, from -> to, amount, note; or "None in this period."); footer "Homelab Hub · Budget" and "Page x of y". Multi-page with the table header repeated; wrap long text by measuring with `font.getStringWidth`.
- Sanitise every string before drawing: strip control characters, replace any character the font cannot encode with `?` (`PDType0Font` throws on missing glyphs). Owner: names are English only.
- Symbols used (₹, −, ·, –, →) must all exist in the font: a unit test loops over every character the templates use and asserts `hasGlyph`.
- The container has a **read-only root FS** and `HOME=/nonexistent`. PDFBox may try to write a font cache when it scans system fonts (not when only embedded fonts are used). Verify in the real container; if needed add `-Dpdfbox.fontcache=/tmp` and `-Djava.awt.headless=true` to the default `HUB_INTERNAL_JAVA_OPTS` (in `deploy/docker-compose.yml` and `deploy/.env.example`, and README).
- Do not write PDFs to disk; stream to the response.

### 5.6 Reference data (verified arithmetic, use in tests, README screenshots and the mockup)
People (ids in creation order): Anu(1), Ravi(2), Meera(3). September 2026, opening from August: Ravi owes Meera ₹300.00 (unsettled).
| Expense | Date | Paid by | Split | Amount |
|---|---|---|---|---|
| Rent (recurring, day 1) | 2026-09-01 | Anu | all 3 | 30,000.00 |
| Internet (recurring, day 5) | 2026-09-05 | Ravi | all 3 | 1,199.00 |
| Groceries | 2026-09-12 | Ravi | all 3 | 2,400.00 |
| Electricity bill (no day, counts 15th) | 2026-09-15, `day_set=0` | Meera | all 3 | 1,850.50 |
| Dinner out | 2026-09-20 | Meera | Anu, Meera | 3,600.00 |
Shares (paise-exact): Rent 10,000.00 each; Internet Anu 399.67, Ravi 399.67, Meera 399.66; Groceries 800.00 each; Electricity Anu 616.84, Ravi 616.83, Meera 616.83; Dinner Anu 1,800.00, Meera 1,800.00.
Expected statement: total ₹39,049.50. Anu paid 30,000.00, share 13,616.51, this month +16,383.49, carried in 0.00, to settle +16,383.49. Ravi paid 3,599.00, share 11,816.50, this month −8,217.50, carried in −300.00, to settle −8,517.50. Meera paid 5,450.50, share 13,616.49, this month −8,165.99, carried in +300.00, to settle −7,865.99. Totals: paid = share = 39,049.50; net columns 0.00. Transfers: **Ravi -> Anu ₹8,517.50** and **Meera -> Anu ₹7,865.99**. Opening transfers: Ravi -> Meera ₹300.00.
To get the "carried in" in a test, create an August expense/settlement setup that leaves Ravi owing Meera exactly ₹300.00 (e.g. one August expense of ₹600.00 paid by Meera and split between Ravi and Meera is +300/−300).
Part-payment check: after Ravi pays Anu ₹5,000.00 (settlement), Ravi's to-settle is −3,517.50, and the transfer becomes Ravi -> Anu ₹3,517.50.

### 5.7 Frontend
Files to add/change (all under `web/src/`):
- `internal/budget/money.ts` (+ `money.test.ts`): `parseRupees(text): number | null` (exact string parsing to paise; accepts "1850", "1850.5", "1,850.50", rejects more than 2 decimals, negatives, empty, letters, exponents, > cap), `formatRupees(paise, {sign?}): string` with hand-written Indian grouping (`₹1,25,000.50`; negative uses U+2212 `−`), share-preview helper `splitPaise` mirroring the server (ascending id).
- `internal/budget/types.ts`: TypeScript types for the API shapes above.
- `internal/pages/BudgetPage.tsx` (default export, lazy): month selector (hash `#/budget/2026-09`; default: current month in the browser locale), loads via `POST /api/admin/budget/months/{m}/open`, renders cards per mockup 1/2. No polling, no SSE, no timers.
- `internal/budget/` components: `MonthHeader`, `TransfersCard` (Who pays whom + carried-in line + Mark paid), `PeopleTable` (with conditional Settled column), `HistoryTable`, `ExpenseList` (table on desktop, two-line rows on phones), `ExpenseSheet` (add/edit), `RecurringPanel` (+ form), `PeoplePanel`, `SettlementsCard` (D17), `SettleDialog` (Mark paid), `ExportDialog`.
- `internal/components/Modal.tsx`: moved out of `ConfigPage.tsx` (task 5.1), keeps behaviour (Escape closes, click outside closes, sticky button row, scrolls inside on short screens).
- `internal/App.tsx`: `Route` gets `"budget"`; `useRoute` parses `#/budget...`; nav entry "Budget"; `const BudgetPage = lazy(() => import("./pages/BudgetPage"))` in `Suspense` like ConfigPage; palette item "Budget".
- `shared/styles.css`: budget styles in a clearly marked section, **every class prefixed `bud-`**, tokens only (no hard-coded colours), light and dark verified, sheets on `max-width: 700px`.
- Add-expense form behaviour (mockup 3): payment name; amount with a `₹` prefix (text input `inputmode="decimal"`); **When**: month input (`type="month"`, with a text fallback pattern `YYYY-MM`) and a segmented "No day / Pick day" toggle revealing a date input; a note "Saved as 15 Sep 2026 and shown as “Sep (no day)”"; Paid by (select, active people); Split among chips (all ticked by default, `All`/`None` links, `+ Add person` inline field that creates the person via the API, adds the chip ticked and adds them to Paid by); live split preview line ("₹1,850.50 ÷ 3 → Anu ₹616.84 · Ravi ₹616.83 · Meera ₹616.83"); Cancel / Save. Field-level errors from 422 shown under fields. Sheet on phones, dialog on desktop.
- Mark paid dialog: amount (prefilled with the transfer, editable), date input (optional, placeholder "Today"), note (optional), Save. Warn under the field when amount > suggested transfer ("More than the suggested amount: this will flip the balance").
- Export dialog (three segments per D15): **This month** | **Choose month** (month picker) | **Choose range** (two date inputs). Below: the preview from `GET /statement` ("Balances before <date> (carried in): Ravi owes Meera ₹300.00. In the range: 5 expenses · ₹39,049.50 · 0 settlements. Result: Ravi → Anu ₹8,517.50 · Meera → Anu ₹7,865.99") and the note that month-only entries count as the 15th. Buttons: Cancel, **Download**, **Share PDF…**. Share flow: `fetch('/api/admin/budget/export.pdf?...', {credentials:'same-origin'})` -> `Blob` -> `new File([blob], filename, {type:'application/pdf'})`; if `navigator.canShare?.({files:[file]})` then `navigator.share({files:[file], title})`; if it throws `NotAllowedError` (user activation expired) keep the file in state and show a "Tap again to share" button; if sharing is unsupported or the user cancels (`AbortError`, not an error), fall back to Download (`URL.createObjectURL` + anchor `download`, revoke the URL afterwards). Web Share needs HTTPS (the owner uses the Tailscale https address). **Untested on a real phone until the owner tries it; say so.**
- Empty states: no people yet (prompt to add), no expenses in the month, nobody owes anything.
- Accessibility: labelled inputs, `aria-pressed` on chips and segmented buttons, dialogs `role="dialog"` with `aria-modal`, errors `role="alert"`.

## 6. Implementation tasks

### Phase 0: Set-up and baseline
- [ ] 0.1 `cd homelab-hub && git status && git log --oneline | head -3` (expect HEAD `566d5bd`, only `docs/mockups/budget.html` untracked). `git switch -c feature/budget`.
  - Acceptance: on branch `feature/budget`, tree otherwise clean.
- [ ] 0.2 Read `README.md`, `docs/plan.md` (sections 5, 9, 18), open `docs/mockups/budget.html` in a browser and click through all 7 tabs.
- [ ] 0.3 Baseline: `scripts/build-all.sh` then `scripts/e2e.sh`. Record the counts (Java, Vitest, Playwright).
  - Acceptance: everything passes before you change anything. If not, stop and tell the owner.
- [ ] 0.4 Add the PDFBox dependency early to `hub-internal/pom.xml` and run `mvn -B -q -pl hub-internal -am compile` **online** once so it lands in `~/.m2`. Confirm the built jar size grows sensibly and the Maven enforcer rules for `hub-public` are unaffected.
  - Acceptance: compiles; `hub-public` still has no PDFBox.

### Phase 1: Pure domain logic (no Spring, no DB) with heavy tests
- [ ] 1.1 Package `com.homelabhub.internal.budget`. Add `Money` (paise <-> text, Indian grouping formatter `1,25,000.50`, parse rupee text with the same rules as `money.ts`; unit tests with shared vectors: `0 -> ₹0.00`, `5 -> ₹0.05`, `123456789 -> ₹12,34,567.89`, `10000000000 -> ₹10,00,00,000.00`, negative formatting), `Splitter` (D2), `BalanceCalculator` (period statement per 5.3), `TransferPlanner` (D7), `RecurringPlanner` (active test, date-in-month with clamping and `day_set`, list of months to materialise).
  - Acceptance: reference data in 5.6 reproduces exactly (shares, statement, transfers, opening transfers, part-payment case).
- [ ] 1.2 Property-style tests (seeded `Random`, several thousand cases): shares sum = amount and differ by <= 1; net sums to 0; applying planned transfers as settlements zeroes all balances; transfers count <= n-1; planner is deterministic for equal inputs; splitting among 1..50 people; large amounts near the cap. Recurring: 31st in Feb (28 and 29 days), 30th in Feb, end month inclusive, start = end, start after end rejected, day null -> 15th and `day_set=0`.
  - Acceptance: tests pass; a deliberate bug (e.g. remainder to the last person) makes them fail (mutation check, revert afterwards).

### Phase 2: Database and repository/service layer
- [ ] 2.1 Add `hub-internal/src/main/resources/db/migration/V3__budget.sql` exactly as in 5.2. Extend `SchemaAndPragmasTest` (or add `BudgetSchemaTest`) to assert the tables, the unique occurrence index, `foreign_keys` behaviour (cannot insert a share for a missing person) and CHECK constraints (`amount_paise > 0`, `from_person <> to_person`).
- [ ] 2.2 `BudgetRepository` (`JdbcClient`, plain SQL, no Hibernate) and `BudgetService` (`TransactionTemplate` for multi-row writes: expense + shares, materialise, delete + skip, archive rules). No `@Scheduled`, no caches, no threads. Inject `Clock` and `hub.budget.zone` (add to `application.yml` as `hub.budget.zone: ${HUB_BUDGET_ZONE:Asia/Kolkata}`).
- [ ] 2.3 Implement materialise (5.3, D8/D9) with the 240-month cap and month bounds; idempotent under concurrent calls (rely on the unique index; catch and ignore the specific constraint failure).
- [ ] 2.4 Implement statement/period calculations with SQL aggregation (sum queries by date, per person) feeding `BalanceCalculator`; implement `history(to, months)`.
- [ ] 2.5 Tests (`@SpringBootTest` with `TestSupport.useTempDatabase`, `@MockitoBean ProxmoxClient`, fixed `Clock`): opening a month twice creates entries once; deleting a generated entry then re-opening does not recreate it; editing a recurring definition after opening leaves that month untouched and changes an unopened later month; opening December materialises Oct/Nov recurring entries first (carry-in correct); end month inclusive; archived-person rules; deleting a recurring keeps generated rows; settlement date defaults to "today" in `Asia/Kolkata` (test with a clock at 23:30 UTC, which is already the next day in India); month view and statement match 5.6.
  - Acceptance: all pass.

### Phase 3: REST API
- [ ] 3.1 `BudgetController` (`@RestController`, paths per 5.4, request/response records) plus exception handlers copying `PortfolioController` (422 field map, 409, 404, 400, SQLite constraint mapping). Bodies use integer paise.
- [ ] 3.2 API tests (real HTTP with cookies for CSRF/session realism, like `AuthApiTest`, or MockMvc with `spring-security-test`): unauthenticated -> 401 for every route in `HUB_AUTH_MODE=all` **and** `edit`; CSRF required on writes; validation table (name empty/too long, amount 0/negative/too big, empty split, unknown person, archived person on a new expense, bad month/day, day 31 in April, from == to settlement, range > 5 years); optimistic-lock 409; duplicate person name (case-insensitive); part payment flow reproduces the 5.6 part-payment case; `GET /export.pdf` headers.
- [ ] 3.3 Test that the budget package has no `@Scheduled` methods (scan classes of `com.homelabhub.internal.budget` with Spring's `ClassPathScanningCandidateComponentProvider` or reflection over the known classes) so N5 stays true.
- [ ] 3.4 Test that a nightly backup contains the budget tables (call `BackupService` and open the copy with SQLite, check `budget_expenses` exists and has the inserted rows).
  - Acceptance: all pass; `scripts/build-all.sh` green.

### Phase 4: PDF
- [ ] 4.1 Copy the fonts + `OFL.txt` into `hub-internal/src/main/resources/fonts/`. `BudgetPdfService`: builds the PDF from the same statement object the API returns (no second calculation path).
- [ ] 4.2 Tests: (a) every character used by the templates (`₹ − · – →` and digits/letters/punctuation) has a glyph in both fonts (`hasGlyph`); (b) generate the 5.6 September statement, extract text with `PDFTextStripper`, and assert it contains `₹8,517.50`, `₹7,865.99`, `Ravi`, `Anu`, `Sep (no day)`, `Page 1 of 1`, and does not contain `?` where a symbol should be; (c) a long statement (e.g. 300 expenses with long names) paginates, repeats the table header, numbers pages "Page x of y" correctly and finishes in a reasonable time within a 256 MB heap (measure and record); (d) settlements section and the conditional Settled column; (e) "Nobody owes anything."; (f) a name with characters the font cannot encode does not throw (replaced by `?`).
- [ ] 4.3 `GET /export.pdf`: parse and validate `from`/`to`, materialise through the end month, headers per 5.4. Test the filename for a whole month vs a range. Render page 1 of the September PDF to a PNG (PDFBox `PDFRenderer` works in tests if AWT is available; otherwise convert with a local tool) and **look at it** to confirm layout and the `₹` glyph visually.
  - Acceptance: the PDF opens, `₹` renders, layout matches mockup 7.

### Phase 5: Frontend
- [ ] 5.1 Move `Modal` from `web/src/internal/pages/ConfigPage.tsx` to `web/src/internal/components/Modal.tsx`; update imports (ConfigPage and anything else). Run Vitest and the existing config e2e tests to prove nothing changed. Confirm afterwards that `web/dist/internal` has a separate `BudgetPage` chunk **without** CodeMirror (after 5.6).
- [ ] 5.2 `money.ts` + `money.test.ts` (the same vectors as `Money` in Java, plus `parseRupees` edge cases: `"1,850.50"`, `" 12 "`, `"12."`, `".5"`, `"1e3"`, `"-5"`, `"1.234"`, `"99999999999"`).
- [ ] 5.3 `types.ts` and an API wrapper using `web/src/shared/api.ts` (`api()`); surface 422 field errors and 409 conflicts like `EditorDrawer.tsx` does.
- [ ] 5.4 `BudgetPage.tsx` + components per 5.7. Follow mockups 1-6. Mobile first.
- [ ] 5.5 `App.tsx`: route, nav, lazy import + `Suspense`, palette entry. Hash route `#/budget/2026-09`.
- [ ] 5.6 Styles in `styles.css` under a `/* ---- budget ---- */` section, `bud-` prefixed, tokens only, light + dark. Sheets on phones (`max-width: 700px`), dialogs on desktop; buttons must stay reachable on a short screen (the sticky `.mrow` behaviour).
- [ ] 5.7 Build (`scripts/build-all.sh`) and verify: the public bundle leak check passes; the budget chunk is separate; `web/dist/internal` main chunk did not grow much.
  - Acceptance: page usable locally via `scripts/run-local.sh` (dashboard :3910, password `local-test-pass`).

### Phase 6: Browser tests and visual review
- [ ] 6.1 Add a `describe("budget", ...)` block to `web/e2e/e2e.mjs` (before the `responsive + visual evidence` block; locators must be specific, since Playwright strict mode fails on ambiguous ones). Tests (use unique person names; the e2e DB is fresh per run):
  - add three people; add an expense with **month only** -> list shows "Sep (no day)" and stored as the 15th (check via API); add one with a day; split among a subset; **+ Add person inline** appears as ticked chip and in Paid by;
  - amount validation messages; more than 2 decimals rejected; live split preview line matches;
  - recurring created with an earlier start month -> opening a later month shows the generated entries; delete one generated entry, reload -> not recreated; edit the definition -> already opened month unchanged;
  - Who pays whom matches 5.6 for the reference data (create the data through the API in the test);
  - **Mark paid** part payment (5,000.00) -> the transfer shrinks to ₹3,517.50, a settlement row appears, delete it -> transfer returns; empty date -> settlement dated today;
  - carry-over: unpaid balance shows in the next month as "Carried in";
  - export dialog: three options present; range preview text; **PDF download** (use `page.request.get` with the session or intercept the download) -> status 200, `content-type: application/pdf`, body starts with `%PDF`, filename as expected;
  - phone width 390 px: no horizontal overflow, sheets fully reachable, export dialog usable; short screen (1280x480) buttons reachable;
  - unauthenticated `GET /api/admin/budget/people` -> 401 (fresh context);
  - nothing loads before visiting the page: on the dashboard route, no request to `/api/admin/budget` is made (`page.on('request')`), and the budget JS chunk is fetched only after navigating to `#/budget`.
  - `expectNoProblems()` at the end of each test (console errors, failed requests, 5xx/403/404).
- [ ] 6.2 Take screenshots (light and dark; 390 px and 1280 px) of the month view, add sheet, export sheet; **look at them** and fix layout problems (last time this found a clipped table and a broken glyph). Compare with `docs/mockups/budget.html`.
- [ ] 6.3 Run the full `scripts/e2e.sh`; all previous 44 tests must still pass.

### Phase 7: Docs and housekeeping
- [ ] 7.1 `README.md`: add the Budget feature to Features; add the tables to the data-model ER diagram; add the routes to the API overview; add tests to the counts table; note the PDF export and the embedded Noto Sans (OFL) font; mention `HUB_BUDGET_ZONE`; mention backups include budget data; add one screenshot or two under `docs/screenshots/` (from the local stack with sample data only).
- [ ] 7.2 `docs/plan.md`: add a dated progress-log entry (new section after the latest one in section 18) with what was built, the deviations (D17, D18), test counts, measurements, and what is unverified.
- [ ] 7.3 If you updated `docs/mockups/budget.html` to show D17/D18/the three-way export, keep it self-contained.
- [ ] 7.4 Do not add Docker/compose changes unless needed (only if PDFBox needs `-Djava.awt.headless=true` / `-Dpdfbox.fontcache=/tmp`: then update `deploy/docker-compose.yml` default `HUB_INTERNAL_JAVA_OPTS`, `deploy/.env.example` and README).

### Phase 8: Final local verification, then ask before deploying
- [ ] 8.1 `scripts/build-all.sh` and `scripts/e2e.sh` fully green; record counts.
- [ ] 8.2 Security review of your own diff: every new route under `/api/admin/budget`; no secrets in code/logs; PDF filename fixed by the server (no user input in headers); user text only rendered through React (escaped); no HTML injection possible in the PDF; caps enforced; SQL uses bound parameters only.
- [ ] 8.3 Report to the owner (see 9) and **ask for permission to deploy**.
- [ ] 8.4 After approval, deploy to the Pi (steps below), verify, measure, report. Commit/push only if the owner asks.

**Deploy steps (only after approval).** From the repo root on the dev machine, after a green `scripts/build-all.sh`:
```
rsync -a hub-internal/target/hub-internal-0.1.0-SNAPSHOT.jar rpi4:homelab-hub/hub-internal/target/
rsync -a --exclude .env deploy/ rpi4:homelab-hub/deploy/
ssh rpi4 'cd ~/homelab-hub && docker build -q -f deploy/Dockerfile.internal -t homelab-hub-internal:local . >/dev/null && docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d hub-internal'
```
Run this in the background (the image build on the Pi takes minutes) and wait; then wait until `curl -sf localhost:3010/actuator/health` on the Pi answers (about 30-60 s). Only `hub-internal` is rebuilt; `hub-public` is untouched. The migration `V3` runs at startup (Flyway) on the Pi's existing `data/hub/hub.db`: take a backup first (the app has `POST /api/admin/backups`, or copy `data/hub/hub.db*` while stopped) and mention it. Verify from the Pi: login, `GET /api/admin/budget/people` -> 200 with a session, the page loads, an export returns `%PDF`. Do not enter fake data into the owner's real database without asking; use the local stack for demos.
Measure on the Pi (RSS via `/proc/<pid>/status` of the container's main process; `docker stats` shows 0 B there): idle, after opening the Budget page and after generating a PDF; compare with the 233/243 MiB baseline and report honestly.

## 7. Testing and verification checklist
- [ ] Java unit and property tests (Phase 1), DB tests (2.1, 2.5), API tests (3.2-3.4), PDF tests (4.2).
- [ ] Vitest: `money.test.ts` (and any pure helpers).
- [ ] Playwright suite (Phase 6) plus all pre-existing browser tests.
- [ ] `web/scripts/check-public-bundle.mjs` passes (run by `scripts/build-all.sh`).
- [ ] Manual: `scripts/run-local.sh`, open `http://127.0.0.1:3910` (password `local-test-pass`), use the page on desktop and at phone width; light and dark; export a PDF and open it (check `₹`, alignment, page numbers).
- [ ] Idle check: with the Budget page never opened, confirm there is no budget request and no budget JS fetched (e2e in 6.1); no `@Scheduled` (3.3).
- [ ] State clearly what could not be verified: Web Share on a real Android phone, the PDF in the installed PWA, behaviour on the Pi until deployed.

## 8. Risks, edge cases and gotchas
- **₹ in the PDF**: standard PDF fonts lack it; use the embedded font and test glyphs (`hasGlyph`). `PDType0Font` throws on unencodable characters: sanitise input.
- **Read-only container**: PDFBox font cache/AWT behaviour untested there; see 5.5. Test on the Pi image before declaring done.
- **Heap**: `-Xmx256m`. A 300-expense PDF must fit; measure. Do not build a giant in-memory intermediate; stream the result.
- **Transient user activation**: `navigator.share` after an async `fetch` can fail with `NotAllowedError` on some browsers; the "Tap again to share" fallback covers it.
- **`<input type="month">`** is supported by Chrome/Android/Edge/Safari-iOS but not desktop Firefox: provide the text fallback with a `YYYY-MM` pattern and validate.
- **Timezone**: never use the JVM default zone for "today" (containers run UTC): use the budget zone (`Asia/Kolkata`) via the injected `Clock`. Test around midnight.
- **Month materialisation cost**: a recurring item starting years ago creates many rows on first open (bounded to 240 months per call). Acceptable; mention in docs.
- **Rounding**: only paise integers everywhere; never divide by floating point. The leftover-paise order is by person id (creation order); renaming does not change it.
- **Concurrent tabs**: optimistic locking (`version`) on edits; materialise is idempotent through the unique index.
- **Archiving** blocked while a person is on an active recurring definition (D10); an archived person with a non-zero balance still appears in statements.
- **Deleting a person** is not offered. Deleting an expense with shares cascades; deleting a generated expense adds a skip row.
- **Overpayment** flips balances (D6): warn, do not block.
- **HTTP semantics**: `POST /months/{m}/open` and `GET /statement` / `GET /export.pdf` may create recurring rows (idempotent). This is deliberate (D8/D9); note it in the API docs.
- **CSS collisions**: prefix `bud-` (a stray `.w` rule once broke the dashboard tiles). Also the dialog CSS was fixed so dialogs scroll inside the window and keep the button row sticky (short 1280x480 screens); do not regress it.
- **Playwright locators** must be specific (strict mode); role names can match several elements.
- **Java 17**: no newer APIs (N1).
- **SQLite errors** arrive as `UncategorizedSQLException`; map them (see 4).
- **Static resources** are built into the jar from `web/dist` by `scripts/build-all.sh`; after frontend changes always rebuild before e2e or deploy, otherwise you test stale assets.
- Watchtower is excluded for our containers (label); nothing to do, do not remove the label.
- The owner's real data lives on the Pi; the local stack is throwaway. Never point tests at the Pi.

## 9. Final report to the owner (what to say when done)
Summarise: what was built and where; test counts and results (with the baseline for comparison); the **refinements** they have not seen (D15 "This month" meaning, D17 Settled column + Settlements card, D18 carried-in line, D19 caps, D6 overpayment warning, D10 archive rule); measurements; what is unverified (Web Share on their phone; PDF on the Pi container until deployed); anything that deviated from the mockups; and ask permission to deploy (and later to commit/push; the branch is `feature/budget`).

## 10. Open questions (only if the owner wants them changed; defaults above are safe)
- [ ] Should "This month" in the export mean the calendar month of today (chosen, D15) or the month being viewed?
- [ ] Should overpaying a transfer be blocked instead of warned (D6)?
- [ ] Is the cap of ₹10,00,00,000.00 per amount and 50 people acceptable (D19)?
- [ ] After deploying, does the owner want a "Budget" shortcut in the installed app's shortcuts (manifest `shortcuts`)? Not done by default.

## 11. References
- Mockups: `docs/mockups/budget.html` (7 screens). Screenshots of it were taken to `.scratch/mock-shots/` (local, git-ignored).
- Project docs: `README.md`, `docs/plan.md` (sections 5 architecture, 9 security, 18 progress log).
- Conventions to copy: `hub-internal/src/main/java/com/homelabhub/internal/portfolio/PortfolioController.java` and `PortfolioCrud.java` (validation, 422/409 handling, optimistic locking), `backup/BackupService.java` (`VACUUM INTO`), `auth/SecurityConfig.java` (`Clock` bean, CSRF), `hub-internal/src/test/java/com/homelabhub/internal/TestSupport.java`, `auth/AuthApiTest.java`, `web/src/internal/pages/ConfigPage.tsx` (lazy page, dialogs), `web/src/internal/components/EditorDrawer.tsx` (field errors), `web/src/shared/api.ts`, `web/e2e/e2e.mjs`.
- External: Apache PDFBox 3.x (`PDType0Font.load`, `PDFTextStripper`), Noto Sans (SIL Open Font License 1.1), Web Share API Level 2 (`navigator.canShare({files})`).
