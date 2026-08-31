#!/usr/bin/env python3
"""Phase 5g write-capture normalizer mutation proof.

The A/B self-diff proves the normalizer removes enough. It cannot prove the
normalizer removes only enough. A normalizer that erased business values,
foreign-key edges or duplicate rows would make every capture agree with every
other, and the self-diff would go green precisely because the comparison had
stopped comparing anything.

This proof scores both directions against the committed raw fixture:

  * **must-detect** mutations change a business fact. Each one must change the
    normalized effect. A mutation that does not is over-normalization: the
    oracle is blind to that class of defect.
  * **must-normalize** mutations change something volatile. Each one must leave
    the normalized effect identical. A mutation that does change it is
    under-normalization: the oracle will be flaky, and a flaky oracle gets
    weakened until it is green.

WHY THIS IS NOT SCORED THROUGH A JUnit REPORT

Phase 5e reads mutation detection from a named test's own JUnit report because
its mutants are Java source: they must compile before they can be scored, and a
compile failure must never count as a detection. That reasoning does not
transfer here. These mutations are applied to committed capture DATA, so there
is nothing to compile and no per-mutant test class to report.

The equivalent guarantee is provided instead by a structured report that is
itself validated: every declared mutation must appear in it, must be recorded as
applied, and must be recorded with the outcome its direction requires. A
non-zero exit status alone is never sufficient, for exactly the reason it was
not sufficient in Phase 5e -- an infrastructure failure and a detection are
indistinguishable from the outside.
"""

from __future__ import annotations

import argparse
import copy
import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
MEASURE = SCRIPT_DIR / "measure-write-effect.py"


def mutate_business_value(doc: dict) -> None:
    doc["scope"]["c_bpartner"]["1000123"]["name"] = "A Different Partner Name"


def mutate_break_foreign_key(doc: dict) -> None:
    # The location stops pointing at the created business partner. If the
    # normalizer dropped generated identities, both the correct and the broken
    # graph would render identically and this defect would be invisible.
    doc["scope"]["c_bpartner_location"]["1000456"]["c_bpartner_id"] = 999999


def mutate_duplicate_effect(doc: dict) -> None:
    # The same logical row created twice. Dropping identities would collapse the
    # duplicate into the original.
    duplicate = copy.deepcopy(doc["scope"]["c_bpartner"]["1000123"])
    duplicate["c_bpartner_id"] = 1000124
    doc["scope"]["c_bpartner"]["1000124"] = duplicate
    doc["sentinel"]["c_bpartner"] += 1


def mutate_isactive(doc: dict) -> None:
    # The deactivate step's entire observable effect is this column.
    doc["scope"]["c_bpartner"]["1000123"]["isactive"] = "N"


def mutate_drop_changelog(doc: dict) -> None:
    del doc["scope"]["ad_changelog"]["900001"]
    doc["sentinel"]["ad_changelog"] -= 1


def mutate_updatedby(doc: dict) -> None:
    # The concurrency capture's winner. If UpdatedBy were normalized away the
    # oracle could not say which of two editors won.
    doc["scope"]["c_bpartner"]["1000123"]["updatedby"] = 102


def mutate_numeric_value(doc: dict) -> None:
    doc["scope"]["c_bpartner"]["1000123"]["so_creditlimit"] = "1000.01"


def mutate_null_to_empty(doc: dict) -> None:
    # A column that stopped being populated. Collapsing NULL and '' would hide it.
    doc["scope"]["c_bpartner"]["1000123"]["totalopenbalance"] = ""


def mutate_undeclared_table(doc: dict) -> None:
    # A write to a table the keyed layer does not look at. Only the sentinel can
    # see this, which is the entire reason the sentinel exists.
    doc["sentinel"]["c_order"] += 1


def mutate_timestamps(doc: dict) -> None:
    doc["scope"]["c_bpartner"]["1000123"]["created"] = "2027-11-09 23:59:59.999"
    doc["scope"]["c_bpartner"]["1000123"]["updated"] = "2027-11-09 23:59:59.999"


def mutate_numeric_scale(doc: dict) -> None:
    doc["scope"]["c_bpartner"]["1000123"]["so_creditlimit"] = "1000.0000"


def mutate_whitespace(doc: dict) -> None:
    doc["scope"]["c_bpartner"]["1000123"]["name"] = "  Phase 5g-1a\u00a0 Fixture   Partner \n"


def mutate_colliding_unrelated_identity(doc: dict) -> None:
    """An unrelated `*_id` column that merely COLLIDES with a created row's key.

    ADempiere allocates every table's primary key from that table's own
    `AD_Sequence`, so distinct tables routinely hand out identical integers. If
    the normalizer resolved identities by value alone, this would be rewritten
    to the created business partner's symbol -- a foreign-key edge that does not
    exist -- and would then be flaky, because the collision holds in one capture
    and not the next.

    `AD_ChangeLog.AD_Session_ID` is the realistic carrier: it is a large
    sequence-allocated value in the same range as `C_BPartner_ID`.

    This is `must-detect`: the colliding value must survive into the comparison
    as a literal, so changing it changes the effect.
    """
    doc["scope"]["ad_changelog"]["900001"]["ad_session_id"] = 1000123


def mutate_generated_identity(doc: dict) -> None:
    # The identity VALUES move, as they would between two real captures, but the
    # graph shape is unchanged. This must normalize away, or every capture would
    # differ from every other for no business reason.
    doc["scope"]["c_bpartner"]["2000999"] = doc["scope"]["c_bpartner"].pop("1000123")
    doc["scope"]["c_bpartner"]["2000999"]["c_bpartner_id"] = 2000999
    doc["scope"]["c_bpartner_location"]["1000456"]["c_bpartner_id"] = 2000999
    doc["scope"]["ad_changelog"]["900001"]["record_id"] = 2000999


MUTATIONS = [
    ("business-value-changed", "must-detect", mutate_business_value),
    ("foreign-key-broken", "must-detect", mutate_break_foreign_key),
    ("effect-duplicated", "must-detect", mutate_duplicate_effect),
    ("isactive-flipped", "must-detect", mutate_isactive),
    ("changelog-row-dropped", "must-detect", mutate_drop_changelog),
    ("updatedby-changed", "must-detect", mutate_updatedby),
    ("numeric-value-changed", "must-detect", mutate_numeric_value),
    ("null-became-empty", "must-detect", mutate_null_to_empty),
    ("undeclared-table-written", "must-detect", mutate_undeclared_table),
    ("colliding-unrelated-identity", "must-detect", mutate_colliding_unrelated_identity),
    ("timestamps-moved", "must-normalize", mutate_timestamps),
    ("numeric-scale-changed", "must-normalize", mutate_numeric_scale),
    ("whitespace-volatility", "must-normalize", mutate_whitespace),
    ("generated-identities-moved", "must-normalize", mutate_generated_identity),
]


def effect_for(
    before: Path, after_doc: dict, workdir: Path, tag: str, scope: Path
) -> str:
    after = workdir / f"{tag}-after.json"
    after.write_text(json.dumps(after_doc, indent=1, sort_keys=True), encoding="utf-8")
    out = workdir / f"{tag}-effect.tsv"
    result = subprocess.run(
        [
            sys.executable,
            str(MEASURE),
            "diff",
            "--before", str(before),
            "--after", str(after),
            "--step", "mutation-proof",
            "--attribution-scope",
            str(scope),
            "--out", str(out),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit(
            f"the effect measurement itself failed for mutation {tag}: {result.stderr.strip()}"
        )
    # Comment lines carry the raw identity values, which are volatile by design
    # and are excluded from scoring everywhere else too.
    return "\n".join(
        line
        for line in out.read_text(encoding="utf-8").splitlines()
        if not line.startswith("#")
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    contract = args.repo_root.resolve() / "contracts" / "legacy-web-write-v1"
    scope = contract / "attribution-scope.tsv"
    raw = contract / "raw"
    before = raw / "before.json"
    after = raw / "after.json"
    for path in (before, after):
        if not path.is_file():
            print(f"raw normalizer fixture not found: {path}", file=sys.stderr)
            return 2

    baseline_doc = json.loads(after.read_text(encoding="utf-8"))
    results: list[tuple[str, str, str, str]] = []

    with tempfile.TemporaryDirectory() as tmp:
        workdir = Path(tmp)
        baseline = effect_for(before, baseline_doc, workdir, "baseline", scope)

        # A baseline that produced nothing would make every must-detect mutation
        # trivially "detected" and every must-normalize mutation trivially
        # "normalized". Assert the fixture actually produces an effect first.
        if "[created]" not in baseline or not any(
            line.startswith("c_bpartner\t") for line in baseline.splitlines()
        ):
            print(
                "FAIL: the raw fixture produced no created rows, so no mutation "
                "could be meaningfully scored against it.",
                file=sys.stderr,
            )
            return 1

        for name, direction, apply_mutation in MUTATIONS:
            mutated_doc = json.loads(after.read_text(encoding="utf-8"))
            apply_mutation(mutated_doc)
            if mutated_doc == baseline_doc:
                results.append((name, direction, "not-applied", "the mutation changed nothing"))
                continue
            mutated = effect_for(before, mutated_doc, workdir, name, scope)
            differs = mutated != baseline
            if direction == "must-detect":
                outcome = "detected" if differs else "MISSED"
                detail = (
                    "the normalized effect changed"
                    if differs
                    else "the normalized effect was identical: this defect class is invisible"
                )
            else:
                outcome = "normalized" if not differs else "LEAKED"
                detail = (
                    "the normalized effect was identical"
                    if not differs
                    else "volatile data survived into the comparison"
                )
            results.append((name, direction, outcome, detail))

    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("w", encoding="utf-8") as handle:
        handle.write("# Phase 5g-1a normalizer mutation proof\n")
        handle.write("mutation\tdirection\toutcome\tdetail\n")
        for row in results:
            handle.write("\t".join(row) + "\n")

    # Validate the report rather than trusting the run. Every declared mutation
    # must be present, must have been applied, and must carry the outcome its
    # direction requires.
    declared = {name for name, _d, _f in MUTATIONS}
    reported = {name for name, _d, _o, _x in results}
    problems: list[str] = []
    missing = declared - reported
    if missing:
        problems.append(f"declared mutations absent from the report: {sorted(missing)}")
    for name, direction, outcome, detail in results:
        if outcome == "not-applied":
            problems.append(f"{name}: {detail}")
        elif direction == "must-detect" and outcome != "detected":
            problems.append(f"{name}: over-normalization -- {detail}")
        elif direction == "must-normalize" and outcome != "normalized":
            problems.append(f"{name}: under-normalization -- {detail}")

    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        print(f"report written to {args.report}", file=sys.stderr)
        return 1

    detected = sum(1 for _n, d, o, _x in results if d == "must-detect" and o == "detected")
    normalized = sum(1 for _n, d, o, _x in results if d == "must-normalize" and o == "normalized")
    print(
        f"normalizer mutation proof: {detected} defect class(es) detected, "
        f"{normalized} volatility class(es) normalized; report at {args.report}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
