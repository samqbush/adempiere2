#!/usr/bin/env python3
"""Phase 5g-1a ambient-classification mutation proof.

`contracts/legacy-web-write-v1/ambient-tables.tsv` is the only file in the
oracle that can make an unexpected write acceptable. A table listed there is
exempt from the "changed but undeclared" completeness backstop, which is the
check that catches a write the contract never claimed.

That makes it the single most attractive place to weaken the oracle. When a
future increment finds a business table changing unexpectedly, the cheapest way
to a green run is to add one line here -- and the change would look like
housekeeping. So this proof scores the classification in both directions:

  * **must-detect**: a business table that changed and is neither declared in
    the effect model nor ambient MUST fail. Scored twice, and the second one
    matters most: the first probe adds the table only to the capture, so the
    payload byte-diff would fail it anyway; the second makes capture and frozen
    contract byte-identical, so the undeclared-table backstop is the only thing
    left that can fail the run. Without that isolating case the backstop could
    be deleted outright with every direction still green.
  * **must-forgive**: a table that is legitimately ambient MUST NOT fail, or the
    classification is not being read and the oracle would be flaky.
  * **must-detect-reclassification**: adding a business table to the
    classification MUST turn a previously failing capture green -- and this
    proof asserts exactly that, so the report records, in the repository, that
    widening this file has real consequence. It is the demonstration that the
    manifest and the domain review, not the code, are what defend this list.

The last direction deserves care, because it is the inverse of the usual shape.
We are not proving the tool rejects a widened classification -- it cannot, and
should not: widening it is a legitimate reviewed action. We are proving the
widening is *load-bearing*, so that nobody can argue it is cosmetic. The control
against an unreviewed widening is `manifest.sha256` plus a named reviewer.

As with the normalizer proof, a non-zero exit status alone is never sufficient:
an infrastructure failure and a detection are indistinguishable from the
outside. Every declared mutation must appear in a structured report, must be
recorded as applied, and must carry the outcome its direction requires.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
MEASURE = SCRIPT_DIR / "measure-write-effect.py"

def document(changed: list[str], created: list[str] | None = None) -> str:
    lines = [
        "# Phase 5g write effect for step: create",
        "[step]",
        "create",
        "[identities]",
        "[changed-tables]",
        "c_bpartner\t+1",
        *changed,
        "[created]",
        "c_bpartner\tbp_1\tvalue=P5G1A-1",
        *(created or []),
        "[updated]",
        "[deleted]",
    ]
    return "\n".join(lines) + "\n"


# name, direction, extra changed rows in the CAPTURE, extra changed rows in the
# frozen CONTRACT, extra ambient classifications
MUTATIONS: list[tuple[str, str, list[str], list[str], list[str]]] = [
    (
        "undeclared-business-table-fails",
        "must-detect",
        ["c_order\t+1"],
        [],
        [],
    ),
    (
        # The isolating case. Capture and contract are byte-identical, so the
        # payload comparison passes and the ONLY thing that can fail the run is
        # the undeclared-table backstop. Without this direction, "must-detect"
        # above would be satisfied entirely by the payload byte-diff and the
        # backstop could be deleted with all directions still green.
        "undeclared-business-table-fails-even-when-the-payload-matches",
        "must-detect",
        ["c_order\t+1"],
        ["c_order\t+1"],
        [],
    ),
    (
        "declared-ambient-table-is-forgiven",
        "must-forgive",
        ["ad_session\t+1"],
        [],
        [],
    ),
    (
        "reclassifying-a-business-table-as-ambient-silences-the-backstop",
        "must-detect-reclassification",
        ["c_order\t+1"],
        [],
        ["c_order\treclassified by this mutation, which is the point of the proof"],
    ),
]


def run_score(effect: Path, contract: Path, ambient: Path) -> tuple[int, str]:
    result = subprocess.run(
        [
            sys.executable,
            str(MEASURE),
            "score",
            "--effect",
            str(effect),
            "--contract",
            str(contract),
            "--ambient",
            str(ambient),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.returncode, (result.stdout + result.stderr).strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ambient", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    baseline = args.ambient.read_text(encoding="utf-8")
    if "ad_session" not in baseline:
        print(
            f"FAIL: {args.ambient} does not classify ad_session, so the "
            "must-forgive direction cannot be scored against it.",
            file=sys.stderr,
        )
        return 1
    if "c_order" in baseline:
        print(
            f"FAIL: {args.ambient} already classifies c_order as ambient. This "
            "proof uses c_order as its business-table probe, and a real "
            "classification of it would make both detect directions vacuous.",
            file=sys.stderr,
        )
        return 1

    results: list[tuple[str, str, str, str]] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        for name, direction, changed, contract_changed, extra_ambient in MUTATIONS:
            contract = root / f"{name}.contract.txt"
            contract.write_text(document(contract_changed), encoding="utf-8")

            effect = root / f"{name}.effect.txt"
            effect.write_text(document(changed), encoding="utf-8")

            ambient = root / f"{name}.ambient.tsv"
            text = baseline
            if extra_ambient:
                if not text.endswith("\n"):
                    text += "\n"
                text += "\n".join(extra_ambient) + "\n"
            ambient.write_text(text, encoding="utf-8")

            code, output = run_score(effect, contract, ambient)
            if code not in (0, 1):
                results.append(
                    (name, direction, "not-applied", f"scorer exited {code}: {output[:200]}")
                )
                continue

            if direction == "must-detect":
                outcome = "detected" if code == 1 else "silent"
            elif direction == "must-forgive":
                outcome = "forgiven" if code == 0 else "false-positive"
            else:
                # Same capture as the must-detect probe, but with the probe table
                # reclassified. It must now pass, proving the classification is
                # what decided the earlier failure.
                outcome = "load-bearing" if code == 0 else "inert"
            results.append((name, direction, outcome, output.splitlines()[0] if output else ""))

    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("w", encoding="utf-8") as handle:
        handle.write("# Phase 5g-1a ambient-classification mutation proof\n")
        handle.write("mutation\tdirection\toutcome\tdetail\n")
        for row in results:
            handle.write("\t".join(row) + "\n")

    required = {
        "must-detect": "detected",
        "must-forgive": "forgiven",
        "must-detect-reclassification": "load-bearing",
    }
    # The isolating direction is only isolating if the BACKSTOP is what failed
    # it. A payload byte-diff would produce the same exit status and the same
    # "detected" outcome while proving nothing, so the first reported problem is
    # required to be the backstop's own message.
    required_detail = {
        "undeclared-business-table-fails-even-when-the-payload-matches":
            "changed but is neither declared in the effect model",
    }
    declared = {name for name, _d, _c, _cc, _a in MUTATIONS}
    reported = {name for name, _d, _o, _x in results}
    problems: list[str] = []
    missing = declared - reported
    if missing:
        problems.append(f"declared mutations absent from the report: {sorted(missing)}")
    for name, direction, outcome, detail in results:
        if outcome == "not-applied":
            problems.append(f"{name}: {detail}")
        elif outcome != required[direction]:
            problems.append(
                f"{name}: expected {required[direction]!r} for a {direction} "
                f"mutation but recorded {outcome!r} -- {detail}"
            )
        elif name in required_detail and required_detail[name] not in detail:
            problems.append(
                f"{name}: failed for the wrong reason. Expected the "
                f"undeclared-table backstop, got: {detail!r}"
            )

    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        print(f"report written to {args.report}", file=sys.stderr)
        return 1

    print(
        f"ambient-classification mutation proof: {len(results)} mutation(s) "
        f"scored; report at {args.report}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
