# Fixes and new requirements after live testing (Docker page, branch `ccr-b05a2855-8wk87g`)

> Created: 2026-10-03 · Status: **implemented** (branch `claude/docker-budget-fix-plan-oekxuf`) · Found during live testing on the Pi after deploying this branch

### Implementation notes (where the code differs from the plan below)

- **Item 1:** `.dk-stats` already had fixed columns. The numbers drifted because the actions column to their right changed
  width (1 to 4 buttons). The actions column now has a fixed width (`--dk-act-w`), and each group head carries a CPU / Mem
  header on the same grid. "Memory n/a" shows when `MemUsage`'s limit is 0 (`0B / 0B`), which is what Docker reports
  without a memory cgroup. The Pi itself was not checked from here: confirm with `docker stats --no-stream`.
- **Item 2:** Stop / Down / Delete… sit in the stack editor (left of its action bar), and Stop stack / Down sit in the
  Containers tab's group head. `StackDelete` moved to `docker/StackDelete.tsx`.
- **Item 3:** besides the dialog default (last day of the viewed month, with a warning when the date is in another
  month), `POST /settlements` takes an optional `month`. With no date and a month that is not the current one, the payment
  is dated that month's last day, so the API is safe on its own too.
- **Item 4:** `docker ps --format` has no image-id field, so the ids in use come from
  `docker inspect --type container --format '{{.Image}}'` over `docker ps -aq` (running and stopped). An image is removed
  by all its tags, or by id when it has none, never with `-f`. Prepare re-runs the preview and refuses (409
  `no_longer_unused`) anything no longer offered. More than 10 items need `cleanup` typed. The button is per host (in
  each host section), not in the shared toolbar.

## Context

The Docker page was deployed to the Pi for a live check before merging. Testing (run container, a two-service
stack, the existing Budget page) surfaced three defects and one new requirement:

1. **Docker:** the Live CPU/Memory columns have no header and do not line up between rows.
2. **Docker:** stopping or removing a *whole* stack is only possible from the separate "Stacks" tab — not from
   "Edit stack" (where you land right after creating one) or from the stack's group in the Containers tab.
3. **Budget:** "Mark paid" on a transfer while viewing a past month can record the payment against *today's* date
   instead of a date in the month being viewed, so the viewed month still looks unpaid and can be marked paid again.
4. **New:** a "Clean up this host" action on the Docker page: preview unused images and networks, pick which ones
   to remove, then remove only those.

None of this is fixed yet — this plan is the handoff for doing that work.

## 1. Docker: CPU/Memory column headers and alignment

**Where:** `web/src/internal/pages/DockerPage.tsx`, `Row` (around line 231) and `HostSection`; `web/src/shared/styles.css`
(`.dk-stats` and friends).

**Root cause:** `Row` renders `.dk-stats` as two bare `<span>`s (CPU, then memory) with only a `title` attribute for
context — no header row is rendered anywhere above the list, and `.dk-stats` has no fixed column widths, so the two
numbers drift left/right depending on how long the container name and port chips on that row are.

**Also found while looking at this:** the screenshot shows memory at "0 B" for *every* container, including
`hub-internal`, which is clearly using real memory. `docker stats`' `MemUsage` is almost certainly reading as `0B`
on every container because **the Pi kernel does not have the memory cgroup enabled** — `deploy/docker-compose.yml`
already has a comment about this for a different reason ("the Pi kernel has no memory cgroup, so container limits
and percentage-based JVM sizing do nothing"). `memUsed()` in `web/src/internal/docker/format.ts:91` is parsing
correctly; the input from the host is the problem. Confirm with `cat /sys/fs/cgroup/memory.max` or
`docker info --format '{{.MemoryLimit}}'` on the Pi before assuming the frontend is at fault.

**Fix:**
- Add a real header row above `.dk-list` (or per `.dk-group`) with "CPU" / "Mem" labels, sized identically to
  `.dk-stats`'s two cells (a small CSS grid or fixed-width flex, not ad hoc spans).
- Right-align both numbers in fixed-width cells so they line up regardless of row content.
- If `docker info` / a quick manual check confirms memory accounting is unavailable on this host, show
  "memory n/a" (with a tooltip explaining why) instead of a literal "0 B" that looks like real data — a flag
  worth raising to the owner, since the real fix (enabling the memory cgroup via `cgroup_enable=memory` in
  `/boot/firmware/cmdline.txt`) is a Pi kernel change outside this app, and he may not want to do it.
- Vitest: a snapshot/DOM test for the header + alignment at a couple of widths (reuse the existing phone-width
  Docker Playwright test as a base).

## 2. Docker: stack Stop/Down/Delete not reachable from where you work with a stack

**Where:** `web/src/internal/pages/DockerPage.tsx` — `HostSection`'s "Edit stack" button (line 199) opens
`StackEditor`, and `StackEditor.tsx`'s action bar only has Close / History / Save / Save-and-deploy / Deploy. The
Stop / Down / Delete buttons exist only in `StacksSection` (lines ~280-287), on the separate **Stacks** tab.

**Root cause:** this isn't a missing backend capability — `prepareStack`/`confirm` in `DockerService.java` and the
`/hosts/{h}/stacks/{name}/{op}/prepare` route already support `stop`, `down` and `delete` and were exercised by
`DockerApiTest`. It's a pure discoverability gap: the natural flow (create or open a stack from the Containers tab)
dead-ends without offering the actions you need once it's running, forcing a hunt through a different tab, or (as
reported) stopping each container by hand.

**Fix:**
- Add Stop / Down / Delete (reusing the existing `StackDelete` sub-component) to `StackEditor`'s action bar when
  `!isNew`, calling the same `onAction`-style handler `DockerPage` already passes to `StacksSection` — thread an
  `onStackAction: (op: string, volumes?: boolean) => void` prop through `StackEditor`'s props and `DockerPage`'s
  `editing` wiring.
- Also add a compact Stop/Down button pair to the Containers tab's `.dk-group-head` next to "Edit stack" (line
  199), so you don't have to open the editor just to stop a stack.
- Playwright: extend the existing stack lifecycle test to drive Stop and Delete from inside `StackEditor`, not just
  from the Stacks tab.

## 3. Budget: a transfer can be marked paid twice (wrong month, duplicate settlement)

**Where:** `web/src/internal/budget/Panels.tsx`, `SettleDialog` (line 256-300); `BudgetService.addSettlement`
(`hub-internal/.../budget/BudgetService.java:342`).

**Confirmed root cause (reproduced by reading the code, not guessed):** `SettleDialog`'s date field defaults to
empty with the label "Date (optional)" and help text "Empty = today". `addSettlement` honors that literally:
`LocalDate on = date == null ? ledger.today() : date;` — "today" means the real wall-clock date, **not** a date
inside the month you're currently viewing. If you open a *past* month (e.g. September, while today's real date is
in October) and click "Mark paid" without picking a date, the settlement is recorded against October. September's
balance statement then still shows the same transfer as outstanding (the settlement landed in October's `inPeriod`
sums, not September's — see `BalanceCalculator.statement()`, which buckets by date range as designed and is working
correctly here), so "Mark paid" is still offered, and a second click records a second settlement — this time
correctly dated in October, right alongside the first one. That matches exactly what was reported: two settlements
showing up together in October.

So: `BalanceCalculator` is correct; `addSettlement`'s "no date = today" default is correct for the current month and
wrong for every other month. This is a UX trap, not a calculation bug.

**Fix:**
- `SettleDialog` already receives `today` as a prop; pass the **viewed month** down too (`BudgetPage.tsx` already
  has `month` in scope) and change the default: if the viewed month is not the current real-world month, default
  the date input to the last day of the viewed month instead of leaving it blank, and change the help text from
  "Empty = today" to something explicit like "Defaults to the last day of <Month>". Keep "today" as the default
  only when the viewed month is the current month.
- Still allow picking any explicit date (part-payments made on a specific day should keep working as before).
- Add a warning banner in `SettleDialog` when the chosen (or defaulted) date falls outside the month being viewed,
  so a deliberate cross-month settlement is still possible but never silent.
- New test, `BudgetApiTest` or a dedicated `BalanceCalculatorTest` case, that encodes the exact reported scenario:
  mark a transfer paid while viewing a past month with no date chosen → the transfer must disappear from that
  month's statement, and the same transfer must not be offered as outstanding a second time without a new balance
  appearing.
- Vitest/Playwright: drive `SettleDialog` from a non-current month and assert the date input is pre-filled inside
  that month, not blank.

## 4. New: "Clean up this host" — unused images and networks, with preview and selection

**Approach:** a dry-run preview endpoint lists exactly what *could* be removed (with sizes where available); the
owner picks some or all of it; a second call removes only the selected items. No bulk `docker system prune` (it
can't be scoped to a selection and its output isn't structured), so the hub asks Docker for the removable items
directly and removes them one at a time.

**Backend (`docker/` package):**
- `DockerCommands`: two new read-only commands —
  - `DANGLING_IMAGES = "docker images --filter dangling=true --format '{{json .}}'"` (id, size, created).
  - Unused *tagged* images: `docker image ls --format '{{json .}}'` cross-referenced in Java against the image IDs
    actually used by any container from a fresh `ps` (same pattern `managedStack()` already uses) — only offer
    images used by **no** container, dangling or not.
  - `UNUSED_NETWORKS = "docker network ls --filter dangling=true --format '{{json .}}'"` (dangling already excludes
    bridge/host/none and anything with an attached container).
  - Removal verbs: `docker rmi <id> 2>&1` and `docker network rm <name> 2>&1`, each id/name checked against the
    existing `CONTAINER_ID`-style hex pattern (images) or `NETWORK` pattern (already defined) and single-quoted,
    same discipline as every other command in this file.
- `DockerParser`: `images(String)` → `List<ImageInfo(id, repoTag, size, created)>`; reuse `names()` for the simple
  network listing.
- `DockerService`:
  - `cleanupPreview(DockerHostSpec h)` → runs both listing commands, filters images by cross-referencing running
    *and stopped* containers' image ids (a stopped container's image must not be offered even though nothing is
    currently running from it), returns a combined preview.
  - `prepareCleanup(h, List<String> imageIds, List<String> networkNames, sessionKey)` — same prepare/confirm token
    pattern as every other change here (`ConfirmationTokens`), builds one `Op` per selected item or a single job
    that loops over the selection; given this can be a lot of items, run it as a **job** (reuse `DockerJobs`) so
    progress and partial failures are visible, same as stack deploy.
  - Each removal is audited individually (`docker_audit`, action `cleanup:image` / `cleanup:network`), so a partial
    failure is traceable.
- `DockerController`: new routes.

  | Route | Purpose |
  |---|---|
  | `GET /hosts/{h}/cleanup/preview` | `{images: [...], networks: [...]}`, each with an estimated size |
  | `POST /hosts/{h}/cleanup/prepare` → `/confirm` | body: selected image ids + network names; typed confirmation only when the total count is large (reuse the existing threshold-free two-step flow otherwise — this is non-destructive to running things by construction, since nothing in-use is ever offered) |

**Frontend:**
- A "Clean up…" button in `DockerPage.tsx`'s toolbar (next to Refresh), opening a new `web/src/internal/docker/
  CleanupDialog.tsx`: fetches the preview, lists Images and Networks as two checkbox groups with size/age, a
  "Select all" per group, a running total of reclaimable space, and a Remove button that goes through the existing
  `ActionDialog`/job-progress pattern (`DockerPage.tsx`'s `action.start(...)`, polling `/jobs/{id}` like Update and
  stack deploy already do).
- Disabled/empty state: "Nothing to clean up" when both lists are empty, so it's safe to click any time.

**Tests:**
- Java: `DockerCommandsTest` additions (image/network name validation, quoting), a `DockerParserTest` fixture for
  `docker images --format json`, and a `DockerApiTest` case covering preview + selective removal + that an
  in-use image is never offered even when `docker images` lists it.
- Playwright: open Clean up, see the preview, select one image, remove it, confirm it's gone from the preview on
  reopen.

## Verification

1. `scripts/build-all.sh` (web tests + `mvn verify`) must stay green.
2. `scripts/e2e.sh` — extend the existing Docker Playwright block for items 1, 2 and 4; extend the Budget block for
   item 3.
3. Manual, on the Pi: re-run the exact repro for item 3 (mark paid in a past month, confirm it no longer offers a
   second payment and the next month shows exactly one settlement); re-run item 2 by stopping/removing a demo stack
   entirely from inside the editor; eyeball item 1's header/alignment and whether "memory n/a" is more honest than
   "0 B" on this host; try item 4 against the Pi's real dangling images if any exist, otherwise create one with a
   throwaway `docker build` first.

## Critical files

- `web/src/internal/pages/DockerPage.tsx`, `web/src/internal/docker/StackEditor.tsx`, `web/src/shared/styles.css`
  (items 1, 2)
- `web/src/internal/budget/Panels.tsx`, `hub-internal/.../budget/BudgetService.java` (item 3)
- New: `hub-internal/.../docker/` additions to `DockerCommands`, `DockerParser`, `DockerService`, `DockerController`;
  new `web/src/internal/docker/CleanupDialog.tsx` (item 4)
