# Budget page: implementation report

Branch: `claude/budget-page-implementation-4we39o` · Date: 2026-09-26 · Plan: [`budget-feature-plan.md`](budget-feature-plan.md)

## Summary

The Budget page from the plan is built: people, expenses with a required month and an optional day, recurring expenses,
"who pays whom", balances that carry over between months, **Mark paid** with part payments, and a PDF export that can be
shared from a phone. It lives only in `hub-internal`, under `/api/admin/budget`, and nothing of it reaches the public site.

Nothing was deployed and nothing on the Pi was touched.

| Commit | What |
|---|---|
| `9d117cd` | Backend: migration `V4`, domain logic, service, REST API, PDF export, Java tests |
| `2091a2e` | Frontend page and dialogs, browser tests, docs, compose setting, `commons-logging` fix |

## What was built

### Backend (`hub-internal`, package `com.homelabhub.internal.budget`)

| File | Role |
|---|---|
| `V4__budget.sql` | Tables `budget_people`, `budget_recurring` (+ `_people`, `_skips`), `budget_expenses`, `budget_expense_shares`, `budget_settlements`. Money is integer paise, with CHECK constraints for the amount cap and a unique index so a recurring entry cannot be created twice. |
| `Money` | Indian digit grouping (`₹1,25,000.50`) and exact rupee-text parsing, written by hand (no floats, no locale data needed). |
| `Splitter` | Equal split in paise. Leftover paise go to the lowest person ids, so shares always add up to the amount. |
| `TransferPlanner` | Who pays whom: a greedy match of the largest debtor with the largest creditor. Deterministic, at most n-1 transfers. |
| `RecurringPlanner` | Whether a recurring expense applies in a month, and its date (no day = the 15th; the 31st becomes the month's last day). |
| `BalanceCalculator` | Statement for any period: paid, share, this period, carried in, settled, to settle, plus totals. |
| `BudgetLedger` | Creates recurring entries when a month is opened (no scheduler), builds statements, the month view and history. |
| `BudgetService`, `Check` | Validation field by field (422), optimistic locking (409), the archive rule, settlement dates in the budget time zone. |
| `BudgetController` | The REST API (below). Its error handlers apply only to this controller. |
| `BudgetPdf` | A4 PDF with Apache PDFBox 3.0.8 and embedded Noto Sans (OFL license included), so the ₹ sign renders. Multi-page, with the table header repeated on each page. |

API (all amounts integer paise): `GET/POST /people`, `PUT /people/{id}`, `POST /months/{yyyy-MM}/open`, `GET /history`,
`POST /expenses`, `PUT/DELETE /expenses/{id}`, `GET/POST /recurring`, `PUT/DELETE /recurring/{id}`, `POST /settlements`,
`DELETE /settlements/{id}`, `GET /statement?from&to`, `GET /export.pdf?from&to`.

### Frontend (`web/src/internal`)

- `pages/BudgetPage.tsx` (route `#/budget/YYYY-MM`) is loaded only when the page is opened: a separate 41 kB chunk with no
  polling or timers. It shows cards for Who pays whom, People, Settlements, History and Expenses. On phones the tables
  become two-line rows.
- `budget/ExpenseSheet.tsx` adds and edits expenses: No day / Pick day, a live split preview, and **+ Add person** inline.
- `budget/Panels.tsx` has the People, Recurring, Mark paid and Export dialogs. Export offers This month, Choose month or
  Choose range, shows a live preview, and shares the PDF through the Web Share API, downloading it when sharing is not
  possible.
- `budget/money.ts`, `budget/dates.ts` and `budget/types.ts` hold the helpers. `money.ts` is tested with the same
  examples as the Java code, so both sides agree.
- `components/Modal.tsx` holds `Modal`, moved out of `ConfigPage.tsx` so the budget code does not pull in CodeMirror. It
  now takes an optional class, used for the bottom sheet on phones.
- `App.tsx` gets the route, the nav link, the lazy import and a palette entry.
- `styles.css` gets a `/* ---- budget ---- */` section. Every class is prefixed `bud-` and uses the existing colour tokens.

### Configuration and docs

- `application.yml`: `hub.budget.zone` (`HUB_BUDGET_ZONE`, default `Asia/Kolkata`), and Flyway `out-of-order: true`.
- `deploy/docker-compose.yml` and `deploy/.env.example`: `HUB_BUDGET_ZONE`.
- `README.md`: the features, the data-model diagram, the API, security, configuration, test counts, known limitations,
  the font license and two screenshots (`docs/screenshots/budget*.png`, sample data only).
- `docs/plan.md`: progress log entry 18.u. `budget-feature-plan.md`: status set to implemented.

## Changes from the plan

- **The migration is `V4`, not `V3`**, as the owner asked, so `V3` stays free for the Terminal feature. Flyway now runs
  with `out-of-order: true`, so a `V3` added later still applies to a database that already has `V4`. A test shows that
  Flyway fails without this setting and succeeds with it. The Terminal plan says "use the next free number", which would
  now be `V5`, so tell it to use `V3` if that is wanted.
- **No arrow in the PDF.** Noto Sans has no → (U+2192); the glyph test caught this. Settlements in the PDF therefore read
  "Ravi paid Anu".
- **The month view returns `today`** in the budget's time zone, so "This month" and the default payment date follow the
  server's zone rather than the phone's clock.

## Decisions taken with the plan's defaults (not yet seen by the owner)

- "This month" in the export means today's month, not the month being viewed.
- The Settled column and the Settlements card appear only when the period has payments.
- The carried-in balance is shown as one line under the transfers, e.g. "Carried in from before: Ravi owes Meera ₹300.00".
- Paying more than the suggested amount shows a warning but is allowed.
- A person cannot be archived while they are on a recurring expense that has not ended.
- Limits: ₹10,00,00,000.00 per amount, 50 people, months from 2000-01 to 2100-12, statements of at most 5 years, months
  at most 24 ahead.

## Defect found and fixed

PDFBox brings in the `commons-logging` jar. With that jar present, Spring prints a warning to **stdout**, so the first
line of `java -jar hub-internal.jar hash-password` was no longer the hash, and local login failed. The jar is now
excluded in `hub-internal/pom.xml`, and a test fails if it comes back.

## Tests

| Suite | Before | After | Notes |
|---|---|---|---|
| Java `hub-internal` | 380 | 425 | Plus 45 new tests: the plan's worked example, random-input checks (shares always add up, balances sum to zero, transfers settle everything), recurring rules, validation, 409 conflicts, login in both `all` and `edit` modes, CSRF, the backup containing budget data, no `@Scheduled` code, schema constraints, the PDF (glyphs, figures, multi-page), Flyway order |
| Java `hub-public` | 26 | 26 | The public site's rule against privileged libraries still passes |
| Vitest | 62 | 70 | Money and date helpers |
| Playwright | 44 | 52 | Nothing loads before the page is opened, forms, part payment and undo, carry-over, recurring entries, export preview and download, phone and short screens, 401 without login |

Known failures, all present before the change (checked by running the suite on the unchanged commit in a separate
checkout):

- `CheckersTest.tcpHonoursItsTimeout` fails because the sandbox network accepts connections to `10.255.255.1`.
- 13 browser tests need the owner's private portfolio export and images in `.scratch/`. They fail the same way on the
  unchanged commit (31/44 pass there, 39/52 with the Budget page).

Also checked: the public bundle leak check passes, the budget chunk does not contain CodeMirror, and the main script
grew by only 0.5 kB. Screenshots at 390 and 1280 px in light and dark were reviewed, and the September PDF was rendered
to an image and looked at: the ₹ sign renders and the layout matches mockup 7.

## Not verified

- Sharing the PDF through the phone's share sheet on a real phone, and inside the installed app.
- Creating the PDF in the real container on the Pi, which has a read-only filesystem. PDFBox wrote no font cache locally,
  so no JVM options were added.
- Memory use on the Pi after this change.
- Deployment: not done. Take a database backup first, because the `V4` migration runs at startup.
