#!/usr/bin/env python3
"""Validate the reviewed Phase 5g table-scoped write-attribution inventory.

This gate has two halves, and it needs both.

**Half one: regenerate and match.** The committed `attribution.tsv` must equal
what the generator derives from the seed dictionary and the reactor sources
today, commentary preamble included. That catches drift between the contract and
the repository it describes.

**Half two: the claim assertions.** Regenerate-and-match alone is NOT sufficient,
and this is the failure mode the gate exists to close. If someone registers a
model validator on `C_BPartner` and then regenerates the contract, the two files
agree perfectly and the gate goes green -- while the Phase 5g-1a claim that the
Business Partner write path fires no callout and no validator has silently
become false. The reviewed claims are therefore asserted here, in code, where
regeneration cannot satisfy them.

Both halves read only committed files. Neither starts a container or needs a
database.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

CONTRACT_DIR = "contracts/legacy-web-write-v1"
ATTRIBUTION = "attribution.tsv"

# The reviewed Phase 5g-1a attribution claims, asserted independently of the
# generated content.
#
# `C_BPartner` carrying zero callouts and zero registered validators is the
# entire reason Business Partner was chosen as the first write oracle: its
# database effect is attributable to the window and the model layer with no
# extension arithmetic in between. If that stops being true, the increment's
# premise is gone and the gate must fail loudly rather than absorb the change.
REQUIRED_CLAIMS = {
    # (ad_table_id, hook_kind): exact expected number of rows
    ("291", "callout"): 1,          # exactly one row, and it must declare 0 hooks
    ("291", "model-validator"): 0,  # no registered validator subscribes
}

# Scope tables that MUST appear, so the claim cannot be made vacuously true by
# quietly narrowing the scope to nothing.
REQUIRED_SCOPE_TABLES = {"291", "293", "259", "260"}

# Tables that are known to carry hooks. Their presence proves the analyzer is
# actually looking: a run that reports zero hooks everywhere would otherwise be
# indistinguishable from a correct zero result on the fixture table.
REQUIRED_NONZERO = {
    ("293", "callout"),
    ("259", "callout"),
    ("259", "model-validator"),
    ("260", "callout"),
}


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def data_rows(lines: list[str]) -> list[list[str]]:
    rows = []
    for line in lines:
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0] == "ad_table_id":
            continue
        rows.append(fields)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", type=Path)
    parser.add_argument("--generated-dir", required=True, type=Path)
    parser.add_argument("--summary", type=Path, default=None)
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    reviewed = repo_root / CONTRACT_DIR / ATTRIBUTION
    generated = args.generated_dir.resolve() / ATTRIBUTION

    problems: list[str] = []

    if not reviewed.is_file():
        print(f"reviewed attribution not found: {reviewed}", file=sys.stderr)
        return 2
    if not generated.is_file():
        print(f"generated attribution not found: {generated}", file=sys.stderr)
        return 2

    reviewed_lines = read_lines(reviewed)
    generated_lines = read_lines(generated)

    # Half one. The commentary preamble is compared too: it carries the reasoning
    # a reviewer signed off on, and letting it drift turns a reviewed contract
    # into an unreviewed one without changing a single data row.
    if reviewed_lines != generated_lines:
        first = next(
            (
                i
                for i, (a, b) in enumerate(
                    zip(reviewed_lines, generated_lines)
                )
                if a != b
            ),
            min(len(reviewed_lines), len(generated_lines)),
        )
        problems.append(
            f"{ATTRIBUTION} does not match the repository it describes; "
            f"first difference at line {first + 1}:\n"
            f"  reviewed:  {reviewed_lines[first] if first < len(reviewed_lines) else '<eof>'}\n"
            f"  generated: {generated_lines[first] if first < len(generated_lines) else '<eof>'}"
        )

    # Half two. Asserted against the REVIEWED file, because that is the artifact
    # downstream increments read.
    rows = data_rows(reviewed_lines)
    scope_tables = {r[0] for r in rows if r[0] != "0"}
    missing_scope = REQUIRED_SCOPE_TABLES - scope_tables
    if missing_scope:
        problems.append(
            "the attribution scope no longer covers required table(s): "
            f"{sorted(missing_scope)}. The claim cannot be narrowed to nothing."
        )

    for (table_id, hook_kind), expected in sorted(REQUIRED_CLAIMS.items()):
        actual = [r for r in rows if r[0] == table_id and r[2] == hook_kind]
        if len(actual) != expected:
            problems.append(
                f"table {table_id} has {len(actual)} {hook_kind} row(s); the reviewed "
                f"Phase 5g-1a claim requires exactly {expected}. "
                f"Found: {[r[4] for r in actual]}"
            )
        for row in actual:
            if hook_kind == "callout" and row[3] != "0":
                problems.append(
                    f"table {table_id} now declares {row[3]} callout(s) ({row[4]}). "
                    "The Phase 5g-1a fixture depends on the Business Partner write "
                    "path firing no callout; re-scope the increment rather than "
                    "regenerating this contract."
                )

    for table_id, hook_kind in sorted(REQUIRED_NONZERO):
        present = [
            r
            for r in rows
            if r[0] == table_id and r[2] == hook_kind and r[3] != "0"
        ]
        if not present:
            problems.append(
                f"table {table_id} reports no {hook_kind}, but it is known to carry "
                "at least one. The analyzer is not looking where it claims to look, "
                "so a zero result on the fixture table proves nothing."
            )

    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        with args.summary.open("w", encoding="utf-8") as handle:
            handle.write("check\tresult\tdetail\n")
            handle.write(
                f"regenerate-and-match\t{'fail' if reviewed_lines != generated_lines else 'pass'}"
                f"\t{len(rows)} reviewed row(s)\n"
            )
            for (table_id, hook_kind), expected in sorted(REQUIRED_CLAIMS.items()):
                actual = [r for r in rows if r[0] == table_id and r[2] == hook_kind]
                handle.write(
                    f"claim:{table_id}:{hook_kind}\t"
                    f"{'pass' if len(actual) == expected else 'fail'}\t"
                    f"expected {expected}, found {len(actual)}\n"
                )
            for table_id, hook_kind in sorted(REQUIRED_NONZERO):
                present = [
                    r for r in rows
                    if r[0] == table_id and r[2] == hook_kind and r[3] != "0"
                ]
                handle.write(
                    f"analyzer-liveness:{table_id}:{hook_kind}\t"
                    f"{'pass' if present else 'fail'}\t"
                    f"{present[0][3] if present else '0'} hook(s)\n"
                )

    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 1

    print(
        f"validated Phase 5g write attribution: {len(rows)} reviewed row(s) over "
        f"{len(scope_tables)} scope table(s); C_BPartner carries no callout and no "
        "registered model validator"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
