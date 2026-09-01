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
import re
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
#
# This classification is NOT held here. It is a reviewed contract file,
# contracts/legacy-web-write-v1/ambient-tables.tsv, covered by the oracle
# manifest and the domain review, and `score` requires it to be passed in.
# Holding it in this script would let anyone widen the exemption -- and thereby
# make an unexpected business write acceptable -- without touching the frozen
# oracle or asking a reviewer.
def load_ambient_tables(path: Path) -> frozenset[str]:
    tables: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0].lower() == "table_name":
            continue
        if len(fields) < 2 or not fields[1].strip():
            raise SystemExit(
                f"{path}: row {fields[0]!r} carries no reason. An ambient "
                "classification without a stated reason is not reviewable."
            )
        tables.add(fields[0].strip().lower())
    if not tables:
        raise SystemExit(f"{path} classifies no table at all.")
    return frozenset(tables)


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

    def script_json(self, sql: str) -> dict:
        """Run one statement inside one REPEATABLE READ READ ONLY transaction.

        Every part of a snapshot must come from ONE database snapshot. Running a
        separate `psql` per query cannot promise that: a capture racing a save
        could take pre-commit counts for some tables and post-commit rows for
        others, and per-step measurement is precisely this increment's claim.

        `READ ONLY` is not decoration either -- it makes the server reject a
        write from the measurement path rather than trusting this script to
        contain only SELECTs.
        """
        script = (
            "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
            f"{sql};\n"
            "COMMIT;\n"
        )
        command = list(self._base) + ["--tuples-only", "--quiet", "--file", "-"]
        result = subprocess.run(
            command,
            env=self._env,
            input=script,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise SystemExit(f"psql failed: {result.stderr.strip()}")
        # Parse the WHOLE buffer, not the first `{` line. `json_agg` renders an
        # array with a literal newline between elements, so the document spans
        # several lines as soon as any scope table matches more than one row --
        # which the real create/update/deactivate flow does by its second step.
        # Line-wise parsing would truncate the document and raise a bare
        # JSONDecodeError instead of anything diagnosable. `--tuples-only
        # --quiet` means the buffer is the document and nothing else.
        text = result.stdout.strip()
        if not text.startswith("{"):
            raise SystemExit(
                "the consistent snapshot statement returned no JSON document; "
                f"psql said: {text[:400]!r}"
            )
        return json.loads(text)

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

# Layer 1 counts every ordinary base table in `current_schema()` -- those are the
# tables ADempiere writes. It is not literally every relation in the database,
# and the contract wording must not claim that it is.
#
# The counts are exact, not estimated: the sentinel's whole job is to notice a
# single unexpected row, and `reltuples` is an estimate that would miss it. They
# are produced by ONE query rather than one query per table, via `query_to_xml`,
# because the previous shape launched a `psql` process for each of a thousand-odd
# tables -- which was both slow in the blocking CI lane and, far worse, unable to
# promise that the counts came from one database snapshot.
_SENTINEL_SQL = (
    "SELECT COALESCE(json_object_agg(lower(t.table_name), t.n), '{}'::json)"
    " FROM ("
    "  SELECT c.relname AS table_name,"
    "   (xpath('/row/c/text()',"
    "     query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),"
    "                  false, true, '')"
    "   ))[1]::text::bigint AS n"
    "  FROM pg_catalog.pg_class c"
    "  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
    "  WHERE c.relkind = 'r' AND n.nspname = current_schema()"
    " ) t"
)


def consistent_snapshot(db: Database, scope: list[dict]) -> dict:
    """Both layers, from one REPEATABLE READ READ ONLY transaction."""
    scope_pairs: list[str] = []
    for entry in scope:
        table = entry["table"]
        predicate = entry.get("predicate") or "TRUE"
        # PostgreSQL folds unquoted identifiers to lower case, so ADempiere's
        # relations exist as `c_bpartner`, not `C_BPartner`. The scope contract
        # spells them in the dictionary's CamelCase, so the identifier must be
        # lowered BEFORE it is quoted -- quoting the contract spelling verbatim
        # asked for a relation that does not exist. Quoting is retained rather
        # than dropped so a table name can never be read as SQL syntax.
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", table):
            raise SystemExit(
                f"measurement-scope.tsv declares {table!r}, which is not a plain "
                "SQL identifier. Refusing to interpolate it into a query."
            )
        literal = table.lower().replace("'", "''")
        scope_pairs.append(
            f"'{literal}', (SELECT COALESCE(json_agg(x), '[]'::json) FROM"
            f' (SELECT * FROM "{literal}" WHERE {predicate}) x)'
        )
    statement = (
        "SELECT json_build_object("
        f"'sentinel', ({_SENTINEL_SQL}),"
        f"'scope', json_build_object({', '.join(scope_pairs)})"
        ")::text"
    )
    document = db.script_json(statement)

    sentinel = {name: int(value) for name, value in document["sentinel"].items()}
    # An empty sentinel makes the undeclared-table completeness backstop
    # vacuous, which is precisely the falsely-green failure layer 1 exists to
    # close. The installed ADempiere seed has well over a thousand base tables,
    # so a schema with a handful means the connection landed somewhere
    # unexpected and the capture must not be trusted.
    if len(sentinel) < SENTINEL_TABLE_FLOOR:
        raise SystemExit(
            f"the changed-table sentinel found only {len(sentinel)} base table(s) in "
            f"current_schema(); at least {SENTINEL_TABLE_FLOOR} are expected in "
            "an installed ADempiere schema. Refusing to capture: an empty "
            "sentinel would report every undeclared write as no write at all."
        )

    captured: dict[str, dict[str, dict]] = {}
    for entry in scope:
        table = entry["table"].lower()
        # A composite key is declared as `a+b`. AD_ChangeLog needs one:
        # AD_ChangeLog_ID alone is NOT unique, because PO.save reuses a single
        # id for every column changed in one save (PO.java feeds the first
        # MChangeLog's id back into each subsequent column). Keying on it alone
        # collapsed an N-column change into one arbitrarily chosen row, which
        # would have frozen a single column-change per save as the oracle and
        # scored a runtime that logged a different subset as green.
        columns = [part.strip().lower() for part in entry["key_column"].split("+")]
        rows = document["scope"].get(table) or []
        keyed: dict[str, dict] = {}
        for row in rows:
            identity = "+".join(str(row[column]) for column in columns)
            if identity in keyed:
                raise SystemExit(
                    f"two rows in {table} share the declared key {identity!r}. "
                    "Silently keeping one of them would drop a real write from "
                    "the measurement, so this is a failure: widen key_column in "
                    "measurement-scope.tsv to the table's real identity."
                )
            keyed[identity] = row
        captured[table] = keyed
    return {"sentinel": sentinel, "scope": captured}


def primary_component(key: str) -> str:
    """The first component of a possibly-composite captured key.

    A composite key exists so that two rows sharing an id are not collapsed.
    Identity SYMBOLS, though, are keyed on the generated primary-key value alone,
    because that is the value foreign keys in other rows actually carry -- a
    foreign key never points at `900001+3499`.
    """
    return key.split("+", 1)[0]


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
    document = consistent_snapshot(db, scope)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=1, sort_keys=True), encoding="utf-8")
    print(
        f"snapshot: {len(document['sentinel'])} table(s) counted, "
        f"{sum(len(v) for v in document['scope'].values())} scope row(s) captured"
    )
    return 0


def diff(args) -> int:
    """Normalized keyed effect for one step, plus the changed-table sentinel."""
    ambient_tables = load_ambient_tables(args.ambient)
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
    #
    # "Created" means created during this CAPTURE, not during this step. The
    # reference is the capture's post-login baseline snapshot, which is why it
    # is a required argument. Using the step's own before-snapshot instead made
    # every step after the create resolve nothing: the row already existed by
    # then, so the update and deactivate effects froze the raw sequence-allocated
    # `c_bpartner  1000000` as their key. Taking the baseline as the reference is
    # also what keeps a SEEDED row from being handed a capture-local symbol --
    # the same rule derive-write-oracle-facts.py applies.
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))["scope"]
    for table in sorted(set(after["scope"]) | set(before["scope"])):
        seeded = baseline.get(table, {})
        for key in sorted(set(after["scope"].get(table, {})) | set(before["scope"].get(table, {}))):
            if key not in seeded:
                identities.declare(table, f"{table}_id", primary_component(key))

    for table in sorted(set(before["scope"]) | set(after["scope"])):
        before_rows = before["scope"].get(table, {})
        after_rows = after["scope"].get(table, {})
        for key in sorted(after_rows):
            # The identity was declared under the key's PRIMARY component, so it
            # must be looked up that way. Passing the full captured key made
            # every composite-keyed row miss its own symbol and fall through to
            # the `or key` branch, freezing a raw sequence-allocated integer --
            # `c_bp_customer_acct  1000000+101` -- into the compared payload.
            # That is under-normalization: a runtime that allocates a different
            # identity for the same transition would fail for a reason that is
            # about identity allocation rather than about the transition.
            symbol = identities.symbol_for(f"{table}_id", primary_component(key)) or key
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
        # The step identity is a DATA row, not a comment. `score` strips comments
        # before comparing, so a step id carried only in the header would never be
        # compared -- and the create effect of step 1 would score cleanly against
        # the frozen model of step 3. The step is what makes per-step measurement
        # mean anything, so it must be in the compared payload.
        "[step]",
        args.step,
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
    # Symbolized on the same terms as [created] and [updated]. A row created
    # earlier in the capture and deleted in this step is capture-created, so
    # rendering its raw key here would reintroduce exactly the sequence-allocated
    # integer the other sections stopped emitting.
    lines.extend(
        f"{t}\t{identities.symbol_for(f'{t}_id', primary_component(k)) or k}"
        for t, k in deleted
    )
    lines.append("")

    # A step that wrote nothing has to say so, in the compared payload.
    #
    # Some steps in this flow genuinely have no database effect, and for one of
    # them -- the conflicting save -- "nothing was written" IS the expected
    # answer and the headline fact of the whole concurrency capture. But a model
    # holding only section headers is also exactly what an accidentally-empty
    # freeze produces, and that would match any run at all.
    #
    # The two are distinguished by declaring the emptiness rather than inferring
    # it. `score` accepts a model with no effect rows only when it carries this
    # marker, and fails a marked model whose observed run DID write, because the
    # marker is compared like any other payload row.
    non_ambient_changed = [
        row for row in changed if row.split("\t")[0].lower() not in ambient_tables
    ]
    if not (created or updated or deleted or non_ambient_changed):
        # The declaration states exactly what was measured, and no more. Layer 1
        # is a row COUNT per table, so an UPDATE to a table outside the
        # measurement scope produces neither a keyed row nor a count delta, and
        # an insert paired with a delete in one step nets to zero. Writing
        # "declared" here would turn that blind spot into a positive claim that
        # nothing at all happened; naming the two measurements keeps the frozen
        # assertion true. Narrowing the blind spot is tracked as residual R12.
        lines.append("[no-effect]")
        lines.append("no-keyed-change-in-scope\tno-row-count-delta-outside-ambient")
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
    ambient_tables = load_ambient_tables(args.ambient)
    observed = args.effect.read_text(encoding="utf-8").splitlines()
    expected = args.contract.read_text(encoding="utf-8").splitlines()

    def payload(lines: list[str]) -> list[str]:
        # Comment lines carry the identity mapping's raw values, which are
        # volatile by design. They are excluded from the comparison and retained
        # in the file for diagnosis.
        #
        # Ambient changed-table rows are excluded for the same reason, and this
        # is what makes the classification mean anything at all. Left in, an
        # ambient table's delta would have to match byte for byte, so a table
        # that churns non-deterministically -- which is precisely what "ambient"
        # describes -- would fail the comparison regardless of being classified,
        # and the exemption below would be unreachable. They stay in the emitted
        # file so a surprising delta is still diagnosable; they are simply not
        # the thing being asserted.
        kept: list[str] = []
        in_changed = False
        for line in lines:
            if not line or line.startswith("#"):
                continue
            if line.startswith("["):
                in_changed = line == "[changed-tables]"
                kept.append(line)
                continue
            if in_changed and line.split("\t")[0].lower() in ambient_tables:
                continue
            kept.append(line)
        return kept

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
    if not effect_rows and "[no-effect]" not in expected_body:
        problems.append(
            f"{args.contract} declares no effect row at all. A model containing "
            "only section headers matches a run in which nothing was written, "
            "so it is not an oracle."
        )

    # The frozen model must name the step it is the answer for. Without it, the
    # model for one step would score cleanly against the capture of another, and
    # per-step measurement would be decorative.
    def step_of(body: list[str]) -> str | None:
        for index, line in enumerate(body):
            if line == "[step]" and index + 1 < len(body):
                candidate = body[index + 1]
                return None if candidate.startswith("[") else candidate
        return None

    expected_step = step_of(expected_body)
    observed_step = step_of(observed_body)
    if expected_step is None:
        problems.append(
            f"{args.contract} carries no [step] section. A frozen effect model "
            "that does not name its step can be satisfied by the capture of a "
            "different step."
        )
    elif observed_step != expected_step:
        problems.append(
            f"step identity diverged: the frozen model is the answer for "
            f"{expected_step!r} but the capture is of {observed_step!r}."
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
    #
    # `declared` is built from the KEYED sections only. Reading it from every
    # tabbed row would include the model's own `[changed-tables]` rows, so a
    # table would declare itself simply by having changed, and the backstop
    # could never be the sole cause of a failure -- it would fire only when the
    # payload comparison had already failed for the same input, making it dead
    # weight dressed as a safety net. Requiring a keyed declaration is also the
    # right rule on its own terms: a table that changed but is outside the
    # measurement scope cannot be examined at all, which is the precise
    # condition this check exists to refuse.
    declared: set[str] = set()
    keyed_sections = {"[created]", "[updated]", "[deleted]"}
    section = ""
    for line in expected_body:
        if line.startswith("["):
            section = line
            continue
        if section in keyed_sections and "\t" in line:
            declared.add(line.split("\t")[0].lower())
    in_changed = False
    for line in observed_body:
        if line.startswith("["):
            in_changed = line == "[changed-tables]"
            continue
        if not in_changed:
            continue
        table = line.split("\t")[0].lower()
        if table not in declared and table not in ambient_tables:
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
    # The capture's post-login baseline snapshot. Identity is capture-scoped, not
    # step-scoped; see diff().
    d.add_argument("--baseline", required=True, type=Path)
    # The ambient classification decides whether a step that touched only
    # session and audit state counts as having written nothing. Required rather
    # than optional: defaulting it would let a step be marked no-effect on a
    # narrower classification than the one it is scored against.
    d.add_argument("--ambient", required=True, type=Path)
    d.add_argument("--out", required=True, type=Path)
    d.set_defaults(func=diff)

    s = sub.add_parser("score")
    s.add_argument("--effect", required=True, type=Path)
    s.add_argument("--contract", required=True, type=Path)
    # Required, not defaulted to a set held in this file: the ambient
    # classification decides which unexpected writes are forgiven, so it belongs
    # in the reviewed, manifest-covered contract and must be supplied explicitly.
    s.add_argument("--ambient", required=True, type=Path)
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
