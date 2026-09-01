#!/usr/bin/env python3
"""Phase 5g-1a write-oracle capture scoring.

WHAT AN A/B SELF-DIFF DOES AND DOES NOT PROVE

Comparing capture A with capture B proves the capture is REPRODUCIBLE. It does
not prove the capture is what the frozen oracle says, and it never can: two
captures of a broken flow agree with each other perfectly. Byte floors and a
header-plus-one-row check do not close that either -- a frozen file can satisfy
both while asserting nothing about the runtime.

So this scores three things, and a gate that runs only the first is not a gate:

  1. A against B, for every fact class -- effects, business values, the
     foreign-key graph, semantic facts, network classes, concurrency facts and
     allowed browser errors. Not effects alone: a driver that stopped reaching
     the window would produce identical empty effects twice.
  2. A against the frozen contract.
  3. B against the frozen contract.

TWO MODES, AND WHY THE FREEZE MODE IS NOT A GATE

`--freeze` writes the captured facts out as candidate contract files. It exists
because the oracle has to come from somewhere, and it is explicitly NOT an
acceptance run: the run that produces the expected answer cannot also be the run
that verifies it. The gate runs in the default strict mode, against files that
were frozen and domain-reviewed in a previous run.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
MEASURE = SCRIPT_DIR / "measure-write-effect.py"

# capture-relative path -> frozen contract-relative path
FACT_FILES = {
    "semantic-facts.tsv": "semantic-facts.tsv",
    "network-classes.tsv": "network-classes.tsv",
    "browser-errors.tsv": "allowed-browser-errors.tsv",
    "write-flow.tsv": "write-flow.tsv",
    "business-values.tsv": "business-values.tsv",
    "foreign-key-graph.tsv": "foreign-key-graph.tsv",
    "concurrency-facts.tsv": "concurrency-facts.tsv",
}


def read(path: Path) -> list[str]:
    if not path.is_file():
        return []
    return [
        line
        for line in path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    ]


def steps(capture: Path) -> list[tuple[str, Path]]:
    effects = capture / "effects"
    if not effects.is_dir():
        return []
    return sorted((path.stem, path) for path in effects.glob("*.txt"))


def self_diff(a: Path, b: Path) -> list[str]:
    problems: list[str] = []

    a_steps = [name for name, _ in steps(a)]
    b_steps = [name for name, _ in steps(b)]
    if a_steps != b_steps:
        problems.append(f"captures measured different steps: {a_steps} vs {b_steps}")
    # An empty step list would make every comparison below vacuously equal, which
    # is the shape a silently broken driver produces.
    if not a_steps:
        problems.append(
            f"{a} recorded no per-step effect at all. Two captures of a flow that "
            "never ran agree perfectly, so an empty self-diff is not evidence."
        )

    for name, path in steps(a):
        other = b / "effects" / path.name
        if read(path) != read(other):
            problems.append(f"step {name}: the effect diverged between captures A and B")

    for name in FACT_FILES:
        rows = read(a / name)
        # A missing file reads as [], and a header-only file reads as [] too,
        # so without this floor an entire fact class that was never captured
        # compares equal to itself, equal to the other capture, and equal to a
        # header-only frozen contract. That is a vacuously green oracle, which
        # is the exact failure this increment exists to prevent.
        if not rows:
            problems.append(
                f"{name} carries no data row in capture A. An empty fact class "
                "compares equal to everything, so it is a failure rather than "
                "an empty result."
            )
        if rows != read(b / name):
            problems.append(f"{name} diverged between captures A and B")
    return problems


def score_against_contract(capture: Path, contract: Path, ambient: Path) -> list[str]:
    problems: list[str] = []
    index = contract / "effect-model.tsv"
    if not index.is_file():
        return [
            f"{index} is absent, so this capture cannot be scored against a frozen "
            "answer. Run the lane with --freeze, review the result, commit it, and "
            "score in a later run."
        ]

    declared: dict[str, str] = {}
    for line in read(index):
        fields = line.split("\t")
        if fields[0] == "step_id":
            continue
        if len(fields) < 3:
            problems.append(f"malformed effect-model row: {line!r}")
            continue
        declared[fields[0]] = fields[2]

    observed = dict(steps(capture))
    missing = sorted(set(declared) - set(observed))
    extra = sorted(set(observed) - set(declared))
    if missing:
        problems.append(f"steps declared in the effect model but not captured: {missing}")
    if extra:
        problems.append(f"steps captured but not declared in the effect model: {extra}")

    for step_id, relative in declared.items():
        if step_id not in observed:
            continue
        result = subprocess.run(
            [
                sys.executable, str(MEASURE), "score",
                "--effect", str(observed[step_id]),
                "--contract", str(contract / relative),
                "--ambient", str(ambient),
            ],
            capture_output=True, text=True, check=False,
        )
        if result.returncode != 0:
            problems.append(
                f"step {step_id} did not match {relative}:\n"
                + (result.stdout + result.stderr).strip()
            )

    for capture_name, contract_name in FACT_FILES.items():
        frozen = contract / contract_name
        if not frozen.is_file():
            problems.append(f"{contract_name} is absent from the frozen contract")
            continue
        frozen_rows = read(frozen)
        if not frozen_rows:
            problems.append(
                f"the frozen {contract_name} carries no data row. A header-only "
                "expectation is satisfied by any capture, so it asserts nothing."
            )
        if read(capture / capture_name) != frozen_rows:
            problems.append(f"{capture_name} diverged from the frozen {contract_name}")
    return problems


def freeze(capture: Path, contract: Path) -> None:
    contract.mkdir(parents=True, exist_ok=True)
    index_rows = ["step_id\toperation\tdocument"]
    effect_dir = contract / "effect-model"
    effect_dir.mkdir(exist_ok=True)
    for step_id, path in steps(capture):
        target = effect_dir / f"{step_id}.txt"
        target.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")
        index_rows.append(f"{step_id}\t{step_id}\teffect-model/{step_id}.txt")
    (contract / "effect-model.tsv").write_text(
        "# Phase 5g-1a frozen effect model index.\n"
        "# One row per measured step. The frozen answer for a step is the named\n"
        "# document, which keeps the emitted section format exactly, so what is\n"
        "# reviewed is what is compared.\n"
        + "\n".join(index_rows) + "\n",
        encoding="utf-8",
    )
    for capture_name, contract_name in FACT_FILES.items():
        source = capture / capture_name
        if source.is_file():
            (contract / contract_name).write_text(
                source.read_text(encoding="utf-8"), encoding="utf-8"
            )
    print(f"froze candidate oracle facts from {capture} into {contract}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", required=True, type=Path)
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--ambient", required=True, type=Path)
    parser.add_argument(
        "--freeze",
        action="store_true",
        help="write candidate contract files instead of scoring; NOT an acceptance run",
    )
    parser.add_argument("--summary", required=True, type=Path)
    args = parser.parse_args()

    a = args.evidence_root / "A"
    b = args.evidence_root / "B"
    problems = self_diff(a, b)

    if args.freeze:
        if problems:
            for problem in problems:
                print(f"FAIL: {problem}", file=sys.stderr)
            print(
                "refusing to freeze a capture that is not reproducible: the two "
                "captures disagree, so neither is the expected answer.",
                file=sys.stderr,
            )
            return 1
        empty = sorted(name for name in FACT_FILES if not read(a / name))
        if empty:
            print(
                "FAIL: refusing to freeze empty fact class(es) as the expected "
                f"answer: {empty}. They would be satisfied by any later capture.",
                file=sys.stderr,
            )
            return 1
        freeze(a, args.contract)
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text("mode\tfreeze\nself-diff\tpass\n", encoding="utf-8")
        return 0

    problems.extend(f"capture A: {p}" for p in score_against_contract(a, args.contract, args.ambient))
    problems.extend(f"capture B: {p}" for p in score_against_contract(b, args.contract, args.ambient))

    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(
        "mode\tscore\n"
        f"self-diff\t{'fail' if any(not p.startswith('capture ') for p in problems) else 'pass'}\n"
        f"problems\t{len(problems)}\n",
        encoding="utf-8",
    )
    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 1
    print("write oracle: A/B self-diff clean and both captures match the frozen contract")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
