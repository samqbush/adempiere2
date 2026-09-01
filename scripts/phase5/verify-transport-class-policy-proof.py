#!/usr/bin/env python3
"""Proves the 5g-1a-x transport-class relaxation is bounded.

WHY THIS GATE EXISTS

Increment 5g-1a-x relaxed two fact classes from exact list equality to an
allowlist, because their frozen content was a ZK 3.6 theme artifact whose row
count tracked how many browser sessions the flow opened rather than anything the
product did. A relaxation is the cheapest way to make a failing oracle pass, so
it does not get to be taken on trust: this asserts, against the REAL committed
policy, both what the relaxed classes still reject and what the unrelaxed
classes still reject.

It is database-neutral and runs in about a second, so a reviewer can reproduce
it without a capture lane.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
import tempfile
from pathlib import Path


def load_scorer(repo_root: Path):
    path = repo_root / "scripts" / "phase5" / "score-write-oracle-capture.py"
    spec = importlib.util.spec_from_file_location("write_oracle_scorer", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    root = args.repo_root.resolve()
    contract = root / "contracts" / "legacy-web-write-v1"
    scorer = load_scorer(root)

    policy, policy_problems = scorer.load_policy(contract)
    if policy_problems:
        print(
            "the committed transport-class policy does not load cleanly: "
            + "; ".join(policy_problems),
            file=sys.stderr,
        )
        return 1

    frozen_network = scorer.read(contract / "network-classes.tsv")
    frozen_errors = scorer.read(contract / "allowed-browser-errors.tsv")

    def network(rows: list[str]) -> list[str]:
        return scorer.compare_fact_file(
            "network-classes.tsv", "network-classes.tsv", rows, frozen_network, policy
        )

    def errors(rows: list[str]) -> list[str]:
        return scorer.compare_fact_file(
            "browser-errors.tsv", "allowed-browser-errors.tsv",
            rows, frozen_errors, policy,
        )

    exact_rows = [row for row in frozen_network if row.split("\t")[0] != "external"]
    external_rows = [row for row in frozen_network if row.split("\t")[0] == "external"]

    cases: list[tuple[str, str, list[str]]] = []

    def case(name: str, expectation: str, problems: list[str]) -> None:
        cases.append((name, expectation, problems))

    # The unrelaxed classes must still reject every way of losing them.
    case("unchanged-capture-passes", "must-pass", network(list(frozen_network)))
    case(
        "exact-class-row-dropped", "must-fail",
        network([r for r in frozen_network if r != exact_rows[0]]),
    )
    case(
        "exact-class-row-altered", "must-fail",
        network([
            r.replace("/webui/", "/webui-modern/") if r == exact_rows[0] else r
            for r in frozen_network
        ]),
    )
    case(
        "exact-class-reordered", "must-fail",
        network([r for r in frozen_network if r.split("\t")[0] != "zkau"]
                + list(reversed([r for r in frozen_network
                                 if r.split("\t")[0] == "zkau"]))),
    )
    case(
        "exact-class-transport-lost", "must-fail",
        network([r for r in frozen_network if r.split("\t")[0] != "zkau"]),
    )

    # The relaxation is deliberately one-directional: fewer occurrences of a
    # declared artifact pass, an undeclared one does not.
    case(
        "subset-class-fewer-occurrences", "must-pass",
        network(exact_rows + external_rows[:1]),
    )
    case("subset-class-absent-entirely", "must-pass", network(exact_rows))
    case(
        "subset-class-undeclared-row", "must-fail",
        network(frozen_network + ["external\tGET\tevil.example.com"]),
    )
    case(
        "subset-class-empty-target", "must-fail",
        network(frozen_network + ["external\tGET\t"]),
    )

    # Browser errors: the legacy theme 404 is forgiven, a new error is not.
    case("browser-errors-unchanged", "must-pass", errors(list(frozen_errors)))
    case("browser-errors-none-observed", "must-pass", errors([]))
    case(
        "browser-errors-new-error", "must-fail",
        errors(frozen_errors + ["http\t500\t/webui/zkau"]),
    )
    case(
        "browser-errors-undeclared-class", "must-fail",
        errors(frozen_errors + ["console\terror\tuncaught TypeError"]),
    )

    # The policy itself must fail closed.
    case(
        "undeclared-class-in-scored-file", "must-fail",
        network(frozen_network + ["websocket\tGET\t/webui/zkau/comet"]),
    )
    case(
        "empty-policy-rejects-everything", "must-fail",
        scorer.compare_fact_file(
            "network-classes.tsv", "network-classes.tsv",
            frozen_network, frozen_network, {},
        ),
    )

    # The regression that matters most: a capture that is byte-identical to the
    # frozen contract must score clean. The first version of this relaxation
    # routed ALL seven fact files through the class-aware comparison, so a
    # perfect capture failed in five of them at once -- and a proof that only
    # exercised the two transport files was green throughout.
    identical: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        capture = Path(tmp) / "capture"
        (capture / "effects").mkdir(parents=True)
        for name, contract_name in scorer.FACT_FILES.items():
            source = contract / contract_name
            if source.is_file():
                (capture / name).write_text(
                    source.read_text(encoding="utf-8"), encoding="utf-8"
                )
        for document in (contract / "effect-model").glob("*.txt"):
            (capture / "effects" / document.name).write_text(
                document.read_text(encoding="utf-8"), encoding="utf-8"
            )
        identical = scorer.score_against_contract(
            capture, contract, contract / "ambient-tables.tsv"
        )
    case("identical-capture-scores-clean", "must-pass", identical)

    results: list[tuple[str, str, str]] = []
    failures: list[str] = []
    for name, expectation, problems in cases:
        actual = "failed" if problems else "passed"
        wanted = "failed" if expectation == "must-fail" else "passed"
        verdict = "ok" if actual == wanted else "WRONG"
        if verdict == "WRONG":
            failures.append(
                f"{name}: expected the comparison to have {wanted}, it {actual}"
                + (f" ({problems})" if problems else "")
            )
        results.append((name, expectation, verdict))

    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("w", encoding="utf-8") as handle:
        handle.write("case\texpectation\tverdict\n")
        for row in results:
            handle.write("\t".join(row) + "\n")

    # Validate the report rather than trusting the run: a proof that silently
    # scored nothing would otherwise look identical to a proof that passed.
    if len(results) != len(cases) or not cases:
        failures.append("the proof scored no case, so it asserts nothing")
    if not any(e == "must-pass" for _n, e, _v in results):
        failures.append(
            "every case was a must-fail, so a comparison that rejected "
            "everything would score green"
        )
    if not any(e == "must-fail" for _n, e, _v in results):
        failures.append(
            "every case was a must-pass, so a comparison that accepted "
            "everything would score green"
        )

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1

    relaxed = sum(1 for _n, e, _v in results if e == "must-fail")
    print(
        f"transport-class policy proof: {len(results)} case(s) scored, "
        f"{relaxed} rejection(s) still enforced; report at {args.report}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
