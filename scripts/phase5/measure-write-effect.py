#!/usr/bin/env python3
"""Phase 5g write-effect measurement: keyed relational facts, two layers.

WHY NOT THE EXISTING TOOLS

`scripts/phase5/measure-window-readonly-effect.sh:114-151` requires table
digests to be UNCHANGED. It is a zero-write model by construction and cannot
express a write at all. `scripts/phase5/phase5f-route-smoke.py:65-112` works at
table-digest granularity, which proves *that* a route wrote but never *that a
business transition is correct*: a create that stored the wrong value and a
create that stored the right one produce equally "changed" digests.

WHY TWO LAYERS

Replacing digests with keyed facts is necessary but not sufficient. A
measurement that queries only contract-declared tables cannot see a write to an
undeclared one, which is a falsely-green gate and exactly what the "complete
transitive write set" rule exists to prevent. So:

  Layer 1, the sentinel: row counts for EVERY table in the database. It answers
    "which tables changed at all", and the score fails when a changed table is
    neither declared in the effect model nor classified as reviewed ambient
    state.

  Layer 2, the keyed facts: full row content for the declared scope tables,
    keyed by fixture identity, so created / updated / deleted rows, the
    foreign-key graph between them, and before/after business values are all
    comparable.

WHY PER STEP

Effects are measured around EACH operation, not once around the whole flow.
Create then update then deactivate cannot be reconstructed from a single
before/after pair: the final row shows only the deactivated state, so a create
that wrote the wrong value and an update that corrected it is indistinguishable
from a correct run.

Safety: this script only ever reads, and it still refuses to connect to anything
that is not the exact local Phase 3 disposable target carrying the Phase 3
database marker. The password is environment-only so it never appears in a
process argument list.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from normalize_write_capture import (  # noqa: E402
    IdentityMap,
    normalize_row,
    render_row,
)

MARKER = "ADempiere Phase 3 disposable database"

# Tables whose churn is session/audit state rather than a business effect. They
# are still MEASURED -- they appear in the sentinel and in the scored output --
# but a change in them does not by itself fail the "undeclared table" check.
# They are listed here, once, rather than being silently skipped, so the set is
# reviewable.
AMBIENT_TABLES = frozenset(
    {
        "ad_session",
        "ad_changelog",
        "ad_recentitem",
        "ad_preference",
        "ad_issue",
        "ad_sequence_no",
        "ad_wf_process",
        "ad_wf_activity",
        "ad_wf_eventaudit",
    }
)


class Database:
    def __init__(self, host: str, port: str, name: str, user: str, password: str):
        if host not in {"127.0.0.1", "localhost", "::1"} or name != "adempiere_phase3_ci" \
                or user != "adempiere_phase3_ci":
            raise SystemExit(
                "Refusing to measure outside the exact local Phase 3 database target."
            )
        self._base = [
            "psql",
            f"--host={host}",
            f"--port={port}",
            f"--username={user}",
            f"--dbname={name}",
            "--no-align",
            "--set=ON_ERROR_STOP=1",
        ]
        self._env = dict(os.environ, PGPASSWORD=password)
        marker = self.scalar(
            "SELECT COALESCE(shobj_description(oid, 'pg_database'), '')"
            " FROM pg_database WHERE datname = current_database()"
        )
        if marker != MARKER:
            raise SystemExit(f"Refusing to measure unmarked database (found {marker!r}).")

    def _run(self, sql: str, tuples_only: bool) -> str:
        command = list(self._base)
        if tuples_only:
            command.append("--tuples-only")
        command.extend(["--command", sql])
        result = subprocess.run(
            command, env=self._env, capture_output=True, text=True, check=False
        )
        if result.returncode != 0:
            raise SystemExit(f"psql failed: {result.stderr.strip()}")
        return result.stdout

    def scalar(self, sql: str) -> str:
        return self._run(sql, True).strip()

    def json_rows(self, sql: str) -> list[dict]:
        """Run a query and return its rows as dictionaries.

        The query is wrapped so PostgreSQL renders each row as JSON. That keeps
        NULL distinguishable from the empty string, which a psql text render
        does not, and a collapsed NULL would hide a column that stopped being
        populated.
        """
        wrapped = f"SELECT COALESCE(json_agg(t)::text, '[]') FROM ({sql}) AS t"
        return json.loads(self._run(wrapped, True).strip() or "[]")


SENTINEL_TABLE_FLOOR = 500


def all_table_counts(db: Database) -> dict[str, int]:
    """Layer 1. Row counts for every ordinary base table in `current_schema()`.

    Scoped to `current_schema()` and `relkind = 'r'` deliberately: those are the
    tables ADempiere writes. It is not literally every relation in the database,
    and the contract wording must not claim that it is.
    """
    rows = db.json_rows(
        "SELECT c.relname AS table_name"
        " FROM pg_catalog.pg_class c"
        " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
        " WHERE c.relkind = 'r' AND n.nspname = current_schema()"
    )
    # An empty sentinel makes the undeclared-table completeness backstop
    # vacuous, which is precisely the falsely-green failure layer 1 exists to
    # close. The installed ADempiere seed has well over a thousand base tables,
    # so a schema with a handful means the connection landed somewhere
    # unexpected and the capture must not be trusted.
    if len(rows) < SENTINEL_TABLE_FLOOR:
        raise SystemExit(
            f"the changed-table sentinel found only {len(rows)} base table(s) in "
            f"current_schema(); at least {SENTINEL_TABLE_FLOOR} are expected in "
            "an installed ADempiere schema. Refusing to capture: an empty "
            "sentinel would report every undeclared write as no write at all."
        )
    counts: dict[str, int] = {}
    for row in rows:
        table = row["table_name"]
        # Counted individually rather than estimated: the sentinel's whole job is
        # to notice a single unexpected row, and reltuples is an estimate that
        # would miss it.
        counts[table.lower()] = int(db.scalar(f'SELECT count(*) FROM "{table}"'))
    return counts


def scope_rows(db: Database, scope: list[dict]) -> dict[str, dict[str, dict]]:
    """Layer 2. Full row content for each declared scope table, keyed."""
    captured: dict[str, dict[str, dict]] = {}
    for entry in scope:
        table = entry["table"]
        key = entry["key_column"]
        predicate = entry.get("predicate") or "TRUE"
        rows = db.json_rows(f'SELECT * FROM "{table}" WHERE {predicate}')
        captured[table.lower()] = {str(row[key.lower()]): row for row in rows}
    return captured


def load_table_ids(path: Path) -> dict[str, int]:
    """table name -> AD_Table_ID, from the reviewed attribution scope.

    Read from the reviewed contract rather than queried from `AD_Table`, so that
    a seed whose dictionary shifted cannot silently change which table a
    `Record_ID` is taken to point into.
    """
    table_ids: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0] == "ad_table_id" or len(fields) < 2:
            continue
        try:
            table_ids[fields[1].strip().lower()] = int(fields[0].strip())
        except ValueError:
            continue
    if not table_ids:
        raise SystemExit(f"{path} declares no table id")
    return table_ids


def load_scope(path: Path) -> list[dict]:
    """Reviewed measurement scope: which tables are captured row-by-row.

    Separate from the effect model on purpose. The scope says WHERE to look; the
    effect model says WHAT the answer must be. Conflating them would let a
    shrinking expectation quietly shrink the measurement too.
    """
    scope: list[dict] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0] == "table":
            continue
        if len(fields) < 3:
            raise SystemExit(f"malformed measurement scope row: {line!r}")
        scope.append(
            {
                "table": fields[0].strip(),
                "key_column": fields[1].strip(),
                "predicate": fields[2].strip(),
            }
        )
    if not scope:
        raise SystemExit(f"no tables declared in {path}")
    return scope


def snapshot(args) -> int:
    db = Database(args.host, args.port, args.database, args.user, args.password)
    scope = load_scope(args.scope)
    document = {
        "sentinel": all_table_counts(db),
        "scope": scope_rows(db, scope),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=1, sort_keys=True), encoding="utf-8")
    print(
        f"snapshot: {len(document['sentinel'])} table(s) counted, "
        f"{sum(len(v) for v in document['scope'].values())} scope row(s) captured"
    )
    return 0


def diff(args) -> int:
    """Normalized keyed effect for one step, plus the changed-table sentinel."""
    before = json.loads(args.before.read_text(encoding="utf-8"))
    after = json.loads(args.after.read_text(encoding="utf-8"))

    changed: list[str] = []
    # The UNION of both sides, not just `after`. Iterating `after` alone would
    # miss a table that existed before the step and is gone after it, which is
    # the largest effect a step could possibly have.
    for table in sorted(set(before["sentinel"]) | set(after["sentinel"])):
        if before["sentinel"].get(table) != after["sentinel"].get(table):
            delta = after["sentinel"].get(table, 0) - before["sentinel"].get(table, 0)
            changed.append(f"{table}\t{delta:+d}")

    identities = IdentityMap()
    for table, table_id in load_table_ids(args.attribution_scope).items():
        identities.declare_table_id(table, table_id)
    created: list[tuple[str, str, dict]] = []
    updated: list[tuple[str, str, dict, dict]] = []
    deleted: list[tuple[str, str]] = []

    # Declare every created row's identity FIRST, across all tables, so a
    # foreign key in one created row can resolve to another created row's
    # symbol regardless of the order the tables happen to be captured in.
    for table in sorted(after["scope"]):
        before_rows = before["scope"].get(table, {})
        for key in sorted(after["scope"][table]):
            if key not in before_rows:
                identities.declare(table, f"{table}_id", key)

    for table in sorted(set(before["scope"]) | set(after["scope"])):
        before_rows = before["scope"].get(table, {})
        after_rows = after["scope"].get(table, {})
        for key in sorted(after_rows):
            symbol = identities.symbol_for(f"{table}_id", key) or key
            if key not in before_rows:
                created.append((table, symbol, normalize_row(table, after_rows[key], identities)))
            else:
                old = normalize_row(table, before_rows[key], identities)
                new = normalize_row(table, after_rows[key], identities)
                if old != new:
                    updated.append((table, symbol, old, new))
        for key in sorted(before_rows):
            if key not in after_rows:
                deleted.append((table, key))

    lines: list[str] = [
        f"# Phase 5g write effect for step: {args.step}",
        "#",
        "# Generated identities are normalized THROUGH the mapping below and are",
        "# never dropped: dropping them would erase a broken foreign-key edge and",
        "# a duplicated effect, which are the two defects this comparison exists",
        "# to catch.",
        "",
        "[identities]",
    ]
    for table, raw, symbol in identities.rows():
        # The raw value is recorded but is NOT part of the comparison. It makes a
        # failing capture diagnosable without making the diff volatile.
        lines.append(f"# {symbol}\t{table}\t{raw}")
    lines.append("")
    lines.append("[changed-tables]")
    lines.extend(changed)
    lines.append("")
    lines.append("[created]")
    lines.extend(render_row(t, s, r) for t, s, r in created)
    lines.append("")
    lines.append("[updated]")
    for table, symbol, old, new in updated:
        for column in sorted(set(old) | set(new)):
            if old.get(column) != new.get(column):
                lines.append(
                    f"{table}\t{symbol}\t{column}\t"
                    f"{old.get(column, '<absent>')}\t{new.get(column, '<absent>')}"
                )
    lines.append("")
    lines.append("[deleted]")
    lines.extend(f"{t}\t{k}" for t, k in deleted)
    lines.append("")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines), encoding="utf-8")
    print(
        f"effect[{args.step}]: {len(created)} created, {len(updated)} updated, "
        f"{len(deleted)} deleted, {len(changed)} changed table(s)"
    )
    return 0


def score(args) -> int:
    """Compare a captured effect against the frozen effect model."""
    observed = args.effect.read_text(encoding="utf-8").splitlines()
    expected = args.contract.read_text(encoding="utf-8").splitlines()

    def payload(lines: list[str]) -> list[str]:
        # Comment lines carry the identity mapping's raw values, which are
        # volatile by design. They are excluded from the comparison and retained
        # in the file for diagnosis.
        return [line for line in lines if line and not line.startswith("#")]

    observed_body = payload(observed)
    expected_body = payload(expected)

    problems: list[str] = []

    # A frozen model that contains only the five section headers would "match" a
    # run in which the write never happened at all. Scoring is what decides
    # parity in 5g-1b, so it must refuse a model that asserts nothing.
    effect_rows = [
        line
        for line in expected_body
        if not line.startswith("[") and "\t" in line
    ]
    if not effect_rows:
        problems.append(
            f"{args.contract} declares no effect row at all. A model containing "
            "only section headers matches a run in which nothing was written, "
            "so it is not an oracle."
        )
    if observed_body != expected_body:
        for index in range(max(len(observed_body), len(expected_body))):
            got = observed_body[index] if index < len(observed_body) else "<eof>"
            want = expected_body[index] if index < len(expected_body) else "<eof>"
            if got != want:
                problems.append(
                    f"effect diverged from the frozen model at line {index + 1}:\n"
                    f"  expected: {want}\n  observed: {got}"
                )
                break

    # The undeclared-table check. This is the completeness backstop: a write to a
    # table nobody declared is invisible to the keyed comparison, and it is
    # exactly the falsely-green failure the sentinel exists to close.
    declared = {
        line.split("\t")[0].lower()
        for line in expected_body
        if "\t" in line and not line.startswith("[")
    }
    in_changed = False
    for line in observed_body:
        if line.startswith("["):
            in_changed = line == "[changed-tables]"
            continue
        if not in_changed:
            continue
        table = line.split("\t")[0].lower()
        if table not in declared and table not in AMBIENT_TABLES:
            problems.append(
                f"table {table} changed but is neither declared in the effect model "
                "nor classified as reviewed ambient state. The write set is larger "
                "than the contract claims."
            )

    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 1
    print(f"scored effect {args.effect.name} against the frozen model: match")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    def add_db(p):
        p.add_argument("--host", default="127.0.0.1")
        p.add_argument("--port", default="5432")
        p.add_argument("--database", default="adempiere_phase3_ci")
        p.add_argument("--user", default="adempiere_phase3_ci")

    snap = sub.add_parser("snapshot")
    add_db(snap)
    snap.add_argument("--scope", required=True, type=Path)
    snap.add_argument("--out", required=True, type=Path)
    snap.set_defaults(func=snapshot)

    d = sub.add_parser("diff")
    d.add_argument("--before", required=True, type=Path)
    d.add_argument("--after", required=True, type=Path)
    d.add_argument("--step", required=True)
    # Required, not optional: without it a generic `Record_ID` cannot be
    # qualified, and an unqualified one would either stay raw (flaky) or be
    # guessed (a fabricated foreign-key edge).
    d.add_argument("--attribution-scope", required=True, type=Path)
    d.add_argument("--out", required=True, type=Path)
    d.set_defaults(func=diff)

    s = sub.add_parser("score")
    s.add_argument("--effect", required=True, type=Path)
    s.add_argument("--contract", required=True, type=Path)
    s.set_defaults(func=score)

    args = parser.parse_args()
    if args.command == "snapshot":
        args.password = os.environ.get("ADEMPIERE_PHASE5D_DB_PASSWORD")
        if not args.password:
            print("ADEMPIERE_PHASE5D_DB_PASSWORD is required", file=sys.stderr)
            return 2
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
