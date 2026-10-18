"""Export the homelab PostgreSQL schema (READ ONLY) into flat artifacts.

Run:  python export_portfolio.py --out-dir DIR

Connection settings come from POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB,
POSTGRES_USER, POSTGRES_PASSWORD and POSTGRES_SCHEMA. The password and the
full connection string are never printed, logged or written.
"""

import argparse
import csv
import hashlib
import json
import os
import sys
import tempfile
from datetime import date, datetime, timezone
from decimal import Decimal

from skillmap import classify

_ENV_VARS = (
    "POSTGRES_HOST",
    "POSTGRES_PORT",
    "POSTGRES_DB",
    "POSTGRES_USER",
    "POSTGRES_PASSWORD",
    "POSTGRES_SCHEMA",
)

_TABLES = (
    "profile",
    "experiences",
    "projects",
    "project_case_studies",
    "project_deliverables",
    "academic_items",
    "skill_groups",
    "skills",
    "homelab_devices",
    "homelab_containers",
    "homelab_services",
    "homelab_network_entities",
)

_SKIPPED_TABLES = ("admin_users", "alembic_version")

_STATUS_ORDER = {"fuzzy": 0, "unmatched": 1, "split-exact": 2, "exact": 3}

_DATA_FILES = (
    "portfolio-export.v1.json",
    "diagrams-archive.json",
    "skill-mapping-review.csv",
)


def _iso_utc(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _jsonable(value):
    if isinstance(value, dict):
        return {str(key): _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    if isinstance(value, datetime):
        return _iso_utc(value)
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    return value


def _read_env() -> dict[str, str]:
    missing = [name for name in _ENV_VARS if not os.environ.get(name)]
    if missing:
        print(
            "Missing required environment variable(s): " + ", ".join(missing),
            file=sys.stderr,
        )
        raise SystemExit(1)
    return {name: os.environ[name] for name in _ENV_VARS}


def _atomic_write_text(path: str, write_fn) -> None:
    target_dir = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp_path = tempfile.mkstemp(prefix=".export-", suffix=".tmp", dir=target_dir)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as handle:
            write_fn(handle)
        os.replace(tmp_path, path)
    except BaseException:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_table(conn, schema: str, table: str) -> list[dict]:
    from psycopg import sql

    query = sql.SQL("SELECT * FROM {}.{} ORDER BY id").format(
        sql.Identifier(schema), sql.Identifier(table)
    )
    with conn.cursor() as cursor:
        cursor.execute(query)
        return cursor.fetchall()


def _load_all(env: dict[str, str], schema: str):
    import psycopg
    from psycopg.rows import dict_row

    try:
        conn = psycopg.connect(
            host=env["POSTGRES_HOST"],
            port=int(env["POSTGRES_PORT"]),
            dbname=env["POSTGRES_DB"],
            user=env["POSTGRES_USER"],
            password=env["POSTGRES_PASSWORD"],
            row_factory=dict_row,
            connect_timeout=15,
        )
    except (psycopg.Error, OSError, ValueError):
        print(
            "Failed to connect to PostgreSQL "
            f"at {env['POSTGRES_HOST']}:{env['POSTGRES_PORT']} "
            f"(database {env['POSTGRES_DB']}, schema {env['POSTGRES_SCHEMA']}).",
            file=sys.stderr,
        )
        raise SystemExit(2) from None

    try:
        conn.autocommit = True
        conn.execute("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY")
        tables_rows = {table: fetch_table(conn, schema, table) for table in _TABLES}
        diagrams = fetch_table(conn, schema, "diagrams")
    finally:
        conn.close()
    return tables_rows, diagrams


def build_skill_review(experiences, projects, canonical_labels) -> list[dict]:
    rows = []
    seen = set()
    collections = (
        ("experience", experiences, "skills"),
        ("project", projects, "tags"),
    )
    for owner_type, items, text_field in collections:
        for item in items or ():
            slug = str(item.get("slug", item.get("id", "")))
            for source_text in item.get(text_field) or []:
                if source_text is None:
                    source_text = ""
                source_text = str(source_text)
                key = (owner_type, slug, source_text)
                if key in seen:
                    continue
                seen.add(key)
                result = classify(source_text, canonical_labels)
                rows.append(
                    {
                        "owner_type": owner_type,
                        "owner_slug": slug,
                        "source_text": source_text,
                        "status": result.status,
                        "proposed_skills": list(result.proposed),
                        "candidates": list(result.candidates),
                        "decision": "",
                    }
                )
    rows.sort(
        key=lambda row: (
            _STATUS_ORDER.get(row["status"], len(_STATUS_ORDER)),
            row["owner_type"],
            row["owner_slug"],
            row["source_text"],
        )
    )
    return rows


def write_skill_csv(rows, stream) -> None:
    writer = csv.writer(stream)
    writer.writerow(
        [
            "owner_type",
            "owner_slug",
            "source_text",
            "status",
            "proposed_skills",
            "candidates",
            "decision",
        ]
    )
    for row in rows:
        proposed = row.get("proposed_skills") or []
        candidates = row.get("candidates") or []
        writer.writerow(
            [
                row.get("owner_type", ""),
                row.get("owner_slug", ""),
                row.get("source_text", ""),
                row.get("status", ""),
                "; ".join(str(label) for label in proposed),
                "; ".join(f"{label} ({score:.2f})" for label, score in candidates),
                "",
            ]
        )


def build_report(
    exported_at, host, db, schema, row_counts, status_counts, file_hashes
) -> str:
    lines = [
        "Homelab portfolio export report",
        "================================",
        f"Export time (UTC): {exported_at}",
        f"Source host:      {host}",
        f"Source database:  {db}",
        f"Source schema:    {schema}",
        "",
        "Table row counts:",
    ]
    for table in _TABLES:
        lines.append(f"  {table}: {row_counts[table]}")
    lines.append(f"  diagrams: {row_counts['diagrams']}")
    for table in _SKIPPED_TABLES:
        lines.append(f"  {table}: skipped")
    lines.append("")
    lines.append("Skill mapping status counts:")
    for status in ("exact", "split-exact", "fuzzy", "unmatched"):
        lines.append(f"  {status}: {status_counts.get(status, 0)}")
    lines.append("")
    lines.append("SHA-256 of output files:")
    for name in _DATA_FILES:
        lines.append(f"  {name}: {file_hashes[name]}")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Read the homelab PostgreSQL schema (READ ONLY) and export "
        "JSON / CSV / report artifacts."
    )
    parser.add_argument(
        "--out-dir",
        required=True,
        help="directory to write the artifacts into (created with mode 0700)",
    )
    args = parser.parse_args(argv)

    try:
        env = _read_env()
    except SystemExit:
        return 1

    out_dir = os.path.abspath(args.out_dir)
    try:
        os.makedirs(out_dir, mode=0o700, exist_ok=True)
    except OSError as exc:
        print(f"Cannot create output directory {out_dir}: {exc}", file=sys.stderr)
        return 1

    try:
        schema = env["POSTGRES_SCHEMA"]
        raw_tables, raw_diagrams = _load_all(env, schema)
        tables_rows = {
            table: [_jsonable(row) for row in raw] for table, raw in raw_tables.items()
        }
        diagrams_rows = [_jsonable(row) for row in raw_diagrams]

        exported_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
        payload = {
            "schemaVersion": 1,
            "exportedAt": exported_at,
            "source": {
                "host": env["POSTGRES_HOST"],
                "database": env["POSTGRES_DB"],
                "schema": schema,
            },
            "tables": tables_rows,
        }

        portfolio_path = os.path.join(out_dir, "portfolio-export.v1.json")
        diagrams_path = os.path.join(out_dir, "diagrams-archive.json")
        review_csv_path = os.path.join(out_dir, "skill-mapping-review.csv")
        report_path = os.path.join(out_dir, "export-report.txt")

        _atomic_write_text(
            portfolio_path,
            lambda fh: json.dump(payload, fh, ensure_ascii=False, indent=2, sort_keys=False),
        )
        _atomic_write_text(
            diagrams_path,
            lambda fh: json.dump(diagrams_rows, fh, ensure_ascii=False, indent=2, sort_keys=False),
        )

        canonical_labels = [
            row.get("label") for row in tables_rows["skills"] if row.get("label")
        ]
        review_rows = build_skill_review(
            tables_rows["experiences"], tables_rows["projects"], canonical_labels
        )
        _atomic_write_text(review_csv_path, lambda fh: write_skill_csv(review_rows, fh))

        status_counts: dict[str, int] = {}
        for row in review_rows:
            status_counts[row["status"]] = status_counts.get(row["status"], 0) + 1

        row_counts = {table: len(rows) for table, rows in tables_rows.items()}
        row_counts["diagrams"] = len(diagrams_rows)

        file_hashes = {
            name: _sha256(os.path.join(out_dir, name)) for name in _DATA_FILES
        }
        report_text = build_report(
            exported_at,
            env["POSTGRES_HOST"],
            env["POSTGRES_DB"],
            schema,
            row_counts,
            status_counts,
            file_hashes,
        )
        _atomic_write_text(report_path, lambda fh: fh.write(report_text))

        print(f"Wrote export artifacts to {out_dir}:")
        for name in (
            "portfolio-export.v1.json",
            "diagrams-archive.json",
            "skill-mapping-review.csv",
            "export-report.txt",
        ):
            print(f"  {name}")
        return 0
    except SystemExit:
        raise
    except Exception as exc:
        print(f"Export failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())