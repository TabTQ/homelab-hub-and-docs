-- Budget page: shared monthly expenses (docs/budget-feature-plan.md 5.2). Money is integer paise everywhere (D1).
-- Numbered V4 on purpose: V3 is left for the Terminal feature's audit table.

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
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0 AND amount_paise <= 10000000000),
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

-- A generated entry the owner deleted: it is not created again.
CREATE TABLE budget_recurring_skips (
    recurring_id INTEGER NOT NULL REFERENCES budget_recurring (id) ON DELETE CASCADE,
    month        TEXT    NOT NULL,
    PRIMARY KEY (recurring_id, month)
);

CREATE TABLE budget_expenses (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,                               -- "payment name", 1..100
    amount_paise    INTEGER NOT NULL CHECK (amount_paise > 0 AND amount_paise <= 10000000000),
    paid_by         INTEGER NOT NULL REFERENCES budget_people (id),
    expense_date    TEXT    NOT NULL,                               -- 'YYYY-MM-DD', the 15th when day_set = 0
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
CREATE INDEX idx_budget_shares_person ON budget_expense_shares (person_id);

CREATE TABLE budget_settlements (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    from_person  INTEGER NOT NULL REFERENCES budget_people (id),
    to_person    INTEGER NOT NULL REFERENCES budget_people (id),
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0 AND amount_paise <= 10000000000),
    settled_on   TEXT    NOT NULL,                                  -- 'YYYY-MM-DD'
    note         TEXT,                                              -- optional, up to 200
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    CHECK (from_person <> to_person)
);
CREATE INDEX idx_budget_settlements_date ON budget_settlements (settled_on);
