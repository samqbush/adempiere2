#!/usr/bin/env python3
"""Validate the committed Phase 5g-0 inventories against freshly generated ones.

The generate-then-validate pattern from Phase 5a and 5f: the committed TSVs in
`contracts/phase5g-web-parity-v1/` are the reviewed inventories, and this script
proves they still describe the repository. A new extension callout, a new model
validator, or a dictionary process whose execution class changes must fail here
rather than silently widening a Phase 5g fixture's blast radius.

The comparison is exact, including the commentary preamble, so a reviewed reason
cannot drift away from the row it explains.

It reads only committed files. It starts no container and needs no database.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

INVENTORIES = (
    "phase5g-process-classification.tsv",
    "phase5g-callout-columns.tsv",
    "phase5g-extension-surfaces.tsv",
)


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def diff_report(name: str, committed: list[str], generated: list[str]) -> list[str]:
    """Report the first differences in a form a reviewer can act on."""
    problems: list[str] = []
    committed_rows = [line for line in committed if not line.startswith("#")]
    generated_rows = [line for line in generated if not line.startswith("#")]

    committed_set = set(committed_rows)
    generated_set = set(generated_rows)

    for row in sorted(generated_set - committed_set)[:20]:
        problems.append(f"{name}: row present in the repository but not reviewed: {row}")
    for row in sorted(committed_set - generated_set)[:20]:
        problems.append(f"{name}: reviewed row no longer present in the repository: {row}")

    if not problems and committed_rows != generated_rows:
        problems.append(f"{name}: reviewed rows are correct but out of generated order")
    if committed != generated and not problems:
        problems.append(f"{name}: commentary preamble differs from the generator")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", type=Path)
    parser.add_argument(
        "--contract-dir",
        default=Path("contracts/phase5g-web-parity-v1"),
        type=Path,
    )
    parser.add_argument(
        "--generated-dir",
        type=Path,
        help="Reuse an existing generated tree instead of regenerating.",
    )
    parser.add_argument(
        "--summary",
        type=Path,
        help="Write a per-inventory row-count summary for the build to retain.",
    )
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    contract_dir = (repo_root / args.contract_dir).resolve()

    with tempfile.TemporaryDirectory() as scratch:
        if args.generated_dir:
            generated_dir = args.generated_dir.resolve()
        else:
            generated_dir = Path(scratch)
            generator = repo_root / "scripts" / "phase5" / "generate-phase5g-inventories.py"
            result = subprocess.run(
                [
                    sys.executable,
                    str(generator),
                    "--repo-root",
                    str(repo_root),
                    "--output-dir",
                    str(generated_dir),
                ],
                capture_output=True,
                text=True,
            )
            if result.returncode != 0:
                print(result.stdout, file=sys.stderr)
                print(result.stderr, file=sys.stderr)
                print("Phase 5g inventory generation failed", file=sys.stderr)
                return 2

        problems: list[str] = []
        counts: dict[str, int] = {}
        for name in INVENTORIES:
            committed_path = contract_dir / name
            generated_path = generated_dir / name
            if not committed_path.is_file():
                problems.append(f"{name}: reviewed inventory is missing from {contract_dir}")
                continue
            if not generated_path.is_file():
                problems.append(f"{name}: the generator produced no such file")
                continue
            committed_lines = read_lines(committed_path)
            counts[name] = max(0, len([l for l in committed_lines if l and not l.startswith("#")]) - 1)
            problems.extend(
                diff_report(name, committed_lines, read_lines(generated_path))
            )

        # An unreviewed file must never be able to join the contract directory
        # unnoticed, so the listing is closed. The walk is recursive and rejects
        # subdirectories outright: a top-level-only check would let an
        # unreviewed file hide one level down.
        allowed = set(INVENTORIES) | {"README.md"}
        actual: set[str] = set()
        for entry in sorted(contract_dir.rglob("*")):
            relative = entry.relative_to(contract_dir).as_posix()
            if entry.is_dir():
                problems.append(
                    f"{relative}: unexpected subdirectory in {contract_dir}; "
                    "the contract directory is flat by contract"
                )
                continue
            if not entry.is_file():
                problems.append(f"{relative}: unexpected non-regular entry in {contract_dir}")
                continue
            actual.add(relative)
        for unexpected in sorted(actual - allowed):
            problems.append(f"{unexpected}: unexpected file in {contract_dir}")
        for missing in sorted(allowed - actual):
            problems.append(f"{missing}: required file missing from {contract_dir}")

    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        print(
            f"Phase 5g inventory validation failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1

    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        lines = ["inventory\trow_count"]
        lines += [f"{name}\t{counts[name]}" for name in INVENTORIES]
        args.summary.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(
        "validated Phase 5g-0 inventories: process classification, callout columns "
        "and extension surfaces all match the repository"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
