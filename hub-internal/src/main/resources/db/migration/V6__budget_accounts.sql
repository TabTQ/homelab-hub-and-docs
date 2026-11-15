-- Ledger accounts: a login for a person on the Budget page (members). Only a bcrypt hash is stored, never the password.
CREATE TABLE budget_accounts (
    person_id          INTEGER PRIMARY KEY REFERENCES budget_people (id),
    password_hash      TEXT    NOT NULL,
    must_change        INTEGER NOT NULL DEFAULT 1 CHECK (must_change IN (0, 1)),  -- 1 = the next login must set a new password
    can_add            INTEGER NOT NULL DEFAULT 0 CHECK (can_add IN (0, 1)),      -- 0 = view only
    disabled           INTEGER NOT NULL DEFAULT 0 CHECK (disabled IN (0, 1)),
    reset_requested_at TEXT,                                                      -- set by "Forgot password", cleared by a reset
    last_login_at      TEXT,
    created_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

-- The person who added the entry when it was a member; NULL for the admin and for generated entries.
ALTER TABLE budget_expenses ADD COLUMN created_by INTEGER;
