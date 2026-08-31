#!/usr/bin/env python3
"""Prove each reviewed Phase 5f contract mutation is rejected."""

from __future__ import annotations

import argparse
import csv
import shutil
import subprocess
from pathlib import Path


def rewrite(path: Path, transform) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    path.write_text("\n".join(transform(lines)) + "\n", encoding="utf-8")


def mutate(case_id: str, contract_dir: Path) -> None:
    if case_id == "MUT-P5F-01":
        rewrite(contract_dir / "deployed-routes.tsv", lambda lines: lines[:-1])
    elif case_id == "MUT-P5F-02":
        rewrite(
            contract_dir / "route-validation.tsv",
            lambda lines: [
                line.replace(
                    "explicit-public-or-application-check",
                    "application-session-check",
                    1,
                )
                if line.startswith("/admin::JnlpDownloadServlet")
                else line
                for line in lines
            ],
        )
    elif case_id == "MUT-P5F-03":
        rewrite(
            contract_dir / "header-policy.tsv",
            lambda lines: [
                line.replace("\tdrop\t", "\tforward\t", 1)
                if "\trequest\tx-forwarded-proto\t" in line
                else line
                for line in lines
            ],
        )
    elif case_id == "MUT-P5F-04":
        rewrite(
            contract_dir / "cookie-policy.tsv",
            lambda lines: [
                line
                for line in lines
                if not line.startswith("/mobile\tadempiereInfo\t")
            ],
        )
    elif case_id == "MUT-P5F-05":
        rewrite(
            contract_dir / "tls-routes.tsv",
            lambda lines: [
                line.replace("on-loopback", "on-public-connector", 1)
                if line.startswith("/wstore\t/login.jsp\t")
                else line
                for line in lines
            ],
        )
    elif case_id == "MUT-P5F-06":
        rewrite(
            contract_dir / "context-policy-schema.tsv",
            lambda lines: [
                line.replace("no-legacy-fallback", "fallback-to-legacy", 1)
                if line.startswith("POLICY-P5F-ROOT\t")
                else line
                for line in lines
            ],
        )
    elif case_id == "MUT-P5F-07":
        rewrite(
            contract_dir / "context-policy-schema.tsv",
            lambda lines: [
                line.replace("\t1048576\t16777216\t", "\tunbounded\t16777216\t", 1)
                if line.startswith("POLICY-P5F-ROOT\t")
                else line
                for line in lines
            ],
        )
    elif case_id == "MUT-P5F-08":
        rewrite(contract_dir / "jsp-precompile-contract.tsv", lambda lines: lines[:-1])
    elif case_id == "MUT-P5F-09":
        rewrite(
            contract_dir / "deployed-routes.tsv",
            lambda lines: lines
            + [
                "/stale::Route::/stale\t/\tPOLICY-P5F-ROOT\t"
                "migrate-context-wide\tnone\tENABLE-P5F-ROOT\t"
                "phase5fJakartaWebRoutesSmoke"
            ],
        )
    elif case_id == "MUT-P5F-10":
        rewrite(
            contract_dir / "non-deployed-dispositions.tsv",
            lambda lines: [
                line.replace(
                    "defer-phase5g-jasper-web",
                    "drop-superseded-descriptor-duplicate",
                    1,
                )
                if line.startswith("JASPER-DEPLOY-GETMD5\t")
                else line
                for line in lines
            ],
        )
    else:
        raise ValueError(f"unknown mutation: {case_id}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--generated-dir", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    source = root / "contracts/phase5f-jakarta-web-v1"
    with (source / "mutation-cases.tsv").open(encoding="utf-8", newline="") as stream:
        cases = list(csv.DictReader(stream, delimiter="\t"))

    if args.work_dir.exists():
        shutil.rmtree(args.work_dir)
    args.work_dir.mkdir(parents=True)
    report_rows = []
    validator = root / "scripts/phase5/validate-phase5f-oracle-contracts.py"
    for case in cases:
        case_id = case["mutation_id"]
        case_dir = args.work_dir / case_id
        shutil.copytree(source, case_dir)
        mutate(case_id, case_dir)
        result = subprocess.run(
            [
                "python3",
                str(validator),
                "--repo-root",
                str(root),
                "--contract-dir",
                str(case_dir),
                "--generated-dir",
                str(args.generated_dir),
                "--summary",
                str(case_dir / "unexpected-summary.tsv"),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if result.returncode == 0:
            raise SystemExit(f"{case_id} was not detected")
        expected = case["expected_detection"]
        if expected.lower() not in result.stdout.lower():
            raise SystemExit(
                f"{case_id} failed for the wrong reason; expected "
                f"{case['expected_detection']!r}, got: {result.stdout.strip()}"
            )
        report_rows.append(
            {
                "mutation_id": case_id,
                "mutation_type": case["mutation_type"],
                "result": "detected",
            }
        )

    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=["mutation_id", "mutation_type", "result"],
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(report_rows)
    print(f"detected all {len(report_rows)} Phase 5f contract mutations")


if __name__ == "__main__":
    main()
