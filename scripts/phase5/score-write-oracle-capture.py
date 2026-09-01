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


POLICY_FILE = "transport-class-policy.tsv"
SEMANTICS = {"exact", "declared-subset"}


def load_policy(contract: Path) -> tuple[dict[tuple[str, str], str], list[str]]:
    """Reads the declared comparison semantics for each fact class.

    Two of the frozen fact classes are not product facts and cannot be compared
    by list equality without making the oracle unscoreable by construction:

      * `allowed-browser-errors.tsv` froze six repetitions of a 404 for
        `/webui/theme/default/css/themesaf.css.dsp`. The legacy theme directory
        ships `theme.css.dsp`, `themeie.css.dsp` and `thememoz.css.dsp` but no
        Safari variant, so Chromium asks for a stylesheet the deployment does not
        contain. That is a packaging defect of the ZK 3.6 theme, and its ROW
        COUNT is a function of how many browser sessions the flow opens -- so
        even the legacy answer changes when a step is added, which is what a
        stable product fact never does.
      * the `external` rows of `network-classes.tsv` name hosts referenced by
        that same legacy theme. Their identity is a branding artifact; the fact
        worth asserting is that every external origin the browser reached for was
        one the capture had declared.

    Making these classes an allowlist is what the file name
    `allowed-browser-errors.tsv` always claimed and the comparison never
    implemented. Everything else stays exact, because `context` and `zkau` are
    real product facts about which origin was used and which transport carried
    the writes, and a runtime that stopped using them must fail.

    The policy is fail-closed in both directions: an undeclared class is an
    error rather than a pass, and a `declared-subset` row must carry a reason and
    a concrete target, so the allowlist cannot quietly become a wildcard.
    """
    path = contract / POLICY_FILE
    if not path.is_file():
        return {}, [
            f"{POLICY_FILE} is absent from the frozen contract, so no fact class "
            "has declared comparison semantics."
        ]

    policy: dict[tuple[str, str], str] = {}
    problems: list[str] = []
    for line in read(path):
        fields = line.split("\t")
        if fields[0] == "fact_file":
            continue
        if len(fields) < 4:
            problems.append(f"malformed {POLICY_FILE} row: {line!r}")
            continue
        fact_file, klass, semantics, reason = (
            fields[0], fields[1], fields[2], fields[3].strip()
        )
        if semantics not in SEMANTICS:
            problems.append(
                f"{POLICY_FILE} declares unknown semantics {semantics!r} for "
                f"{fact_file}/{klass}; expected one of {sorted(SEMANTICS)}"
            )
            continue
        if semantics == "declared-subset" and not reason:
            problems.append(
                f"{POLICY_FILE} relaxes {fact_file}/{klass} to an allowlist "
                "without recording why. A relaxation nobody had to justify is "
                "the one that gets abused."
            )
            continue
        policy[(fact_file, klass)] = semantics
    return policy, problems


def compare_fact_file(
    name: str,
    contract_name: str,
    captured: list[str],
    frozen: list[str],
    policy: dict[tuple[str, str], str],
) -> list[str]:
    """Compares one fact class-by-class under its declared semantics."""
    problems: list[str] = []

    def by_class(rows: list[str]) -> dict[str, list[str]]:
        grouped: dict[str, list[str]] = {}
        for row in rows:
            grouped.setdefault(row.split("\t")[0], []).append(row)
        return grouped

    captured_classes = by_class(captured)
    frozen_classes = by_class(frozen)

    for klass in sorted(set(captured_classes) | set(frozen_classes)):
        semantics = policy.get((contract_name, klass))
        if semantics is None:
            problems.append(
                f"{contract_name} carries class {klass!r}, which {POLICY_FILE} "
                "does not declare. An undeclared class has no reviewed "
                "comparison semantics, so it cannot be scored."
            )
            continue
        observed = captured_classes.get(klass, [])
        expected = frozen_classes.get(klass, [])
        if semantics == "exact":
            if observed != expected:
                problems.append(
                    f"{name} class {klass!r} diverged from the frozen "
                    f"{contract_name}: captured {observed}, frozen {expected}"
                )
            continue

        # declared-subset. Every observed row must have been declared; a
        # declared row that did not recur is not a failure, because these
        # classes are artifacts whose multiplicity tracks how many sessions the
        # flow happened to open.
        for row in observed:
            fields = row.split("\t")
            if len(fields) < 2 or not fields[-1].strip():
                problems.append(
                    f"{name} class {klass!r} carries a row with no concrete "
                    f"target: {row!r}. An allowlist entry that matches anything "
                    "asserts nothing."
                )
                continue
            if row not in expected:
                problems.append(
                    f"{name} class {klass!r} observed an undeclared row: {row!r}. "
                    f"Add it to {contract_name} with a reviewed reason, or fix "
                    "the runtime that produced it."
                )
    return problems


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

    policy, policy_problems = load_policy(contract)
    problems.extend(policy_problems)

    # Only the fact files the policy actually governs go through the class-aware
    # comparison. Everything else keeps whole-file list equality, which is what
    # it always had: routing all seven through the class-aware path would derive
    # a "class" from a step number, a table name or a fact name, find no policy
    # row for it, and fail a byte-identical capture in five files at once.
    class_aware = {fact_file for fact_file, _klass in policy}
    unknown = class_aware - set(FACT_FILES.values())
    if unknown:
        problems.append(
            f"{POLICY_FILE} declares semantics for {sorted(unknown)}, which is not "
            "a scored fact file. A policy row that governs nothing is either a "
            "typo or a relaxation aimed at the wrong file."
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
        captured_rows = read(capture / capture_name)
        if contract_name in class_aware:
            problems.extend(compare_fact_file(
                capture_name, contract_name, captured_rows, frozen_rows, policy,
            ))
        elif captured_rows != frozen_rows:
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
    policy, _ = load_policy(contract)
    for capture_name, contract_name in FACT_FILES.items():
        source = capture / capture_name
        if not source.is_file():
            continue
        text = source.read_text(encoding="utf-8")
        # A class compared as an allowlist is frozen as a SET of declared rows,
        # not as the run's repetition list. Freezing the repetitions would freeze
        # a count that tracks how many browser sessions the flow opened, so
        # adding a step would invalidate an answer that did not change.
        subset_classes = {
            klass for (fact_file, klass), semantics in policy.items()
            if fact_file == contract_name and semantics == "declared-subset"
        }
        if subset_classes:
            kept: list[str] = []
            seen: set[str] = set()
            for line in text.splitlines():
                if not line or line.startswith("#"):
                    kept.append(line)
                    continue
                if line.split("\t")[0] in subset_classes:
                    if line in seen:
                        continue
                    seen.add(line)
                kept.append(line)
            text = "\n".join(kept) + "\n"
        (contract / contract_name).write_text(text, encoding="utf-8")
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
