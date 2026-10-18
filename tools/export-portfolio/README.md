# export-portfolio

Reads the homelab PostgreSQL schema **READ ONLY** (`SET TRANSACTION READ ONLY`,
single snapshot) and writes:

- `portfolio-export.v1.json` — the export (schemaVersion/exportedAt/source/tables)
- `diagrams-archive.json` — reference copy of the `diagrams` table
- `skill-mapping-review.csv` — per free-text skill, an automated status
  (`exact` / `split-exact` / `fuzzy` / `unmatched`) and proposed/candidate
  canonical skills; the `decision` column is left empty for a human
- `export-report.txt` — sources, per-table row counts, status counts, SHA-256s

Two Python files are all the tool needs: `export_portfolio.py` (the CLI) and
`skillmap.py` (pure matching logic). The only third-party runtime dependency is
`psycopg` (v3), imported lazily so `skillmap.py` and the tests run with no
external packages.

## Environment

All connection settings come from env vars (never hardcoded):

```
POSTGRES_HOST POSTGRES_PORT POSTGRES_DB
POSTGRES_USER POSTGRES_PASSWORD POSTGRES_SCHEMA
```

If any are missing the tool exits with an error listing the missing variable
*names*. The password and full connection string are never printed or written.

## Run

The database is only reachable from the already-running container that has
Python 3.12 + `psycopg` and the `POSTGRES_*` env vars. Copy the tool over and
run it there in a generic form:

1. Copy both `.py` files onto the Proxmox host, then into the LXC container:

   ```
   scp tools/export-portfolio/export_portfolio.py \
       tools/export-portfolio/skillmap.py <pve>:/tmp/
   ssh pve 'pct push 100 /tmp/export_portfolio.py /tmp/export_portfolio.py'
   ssh pve 'pct push 100 /tmp/skillmap.py /tmp/skillmap.py'
   ```

2. From the LXC node push the files into the Docker container and run:

   ```
   docker cp /tmp/export_portfolio.py tabishtariq-backend-1:/tmp/export/
   docker cp /tmp/skillmap.py tabishtariq-backend-1:/tmp/export/
   docker exec tabishtariq-backend-1 \
       python /tmp/export/export_portfolio.py --out-dir /tmp/export/out
   ```

3. Copy the results back:

   ```
   docker cp tabishtariq-backend-1:/tmp/export/out/. <local-dir>/
   ```

Output files are written via a temp file + atomic rename with mode `0600`; the
out directory is created with mode `0700`. No database writes are ever issued.

## Tests

```
cd tools/export-portfolio && python3 -m unittest -v
```

## Security

The output contains personal data (email, phone, private homelab internals).
**Do not commit these files** — treat them as sensitive, transfer them only over
trusted channels, and delete local copies once reviewed.