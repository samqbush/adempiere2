#!/usr/bin/env python3
"""Fail closed unless Phase 5g-1b modern write-parity evidence proves what produced it.

A green Gradle status is not evidence. `phase5g1bModernWriteParitySmoke` runs a
long chain -- routed lane, cohort fixture, ambient census, two captures, the
scorer, the H6 matrix -- and almost every link in it has a plausible failure
mode that still ends in exit 0: a capture served by the LEGACY application, a
capture taken against the loopback modern origin with the routing layer
bypassed, a scorer run in freeze mode, a contract edited in the same commit
that scored against it, an H6 row silently absent, an empty JUnit report.

This validator exists so that none of those can be cited as parity. Every check
below refuses evidence rather than repairing it, and every check names the
specific thing an operator would otherwise have been able to claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

CAPTURES = ("A", "B")

# The scorer emits one summary shape for freeze mode and another for scoring
# mode. Phase 5g-1b may only ever have run in scoring mode; see
# run-write-parity-smoke.sh for why it cannot even accept the argument.
FREEZE_MARKER = ("mode", "freeze")

H6_ROWS = (
    "h6-loopback-origin-unreached",
    "h6-cohort-decision-modern",
    "h6-no-legacy-fallback-mid-write",
    "h6-ticket-replay-controls",
    "h6-session-cleanup-after-inflight-write",
    "h6-duplicate-submit",
)

# Fact classes the capture must have derived. Scoring an absent file is not a
# failure in the scorer -- an empty capture directory would compare cleanly
# against nothing -- so completeness is asserted here.
FACT_CLASSES = (
    "write-flow.tsv",
    "semantic-facts.tsv",
    "business-values.tsv",
    "foreign-key-graph.tsv",
    "concurrency-facts.tsv",
    "network-classes.tsv",
    "browser-errors.tsv",
)


def rows(path: Path) -> list[list[str]]:
    return [
        line.split("\t")
        for line in path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    ]


def keyed(path: Path) -> dict[str, str]:
    return {row[0]: row[1] for row in rows(path) if len(row) >= 2}


def contract_manifest_digest(contract: Path) -> tuple[str, str]:
    """Recompute the frozen contract's manifest digest from its own bytes.

    The manifest is what every other Phase 5g gate hashes, so re-deriving it
    here catches the case this validator is really guarding: a branch that
    edited the frozen legacy answer and then scored the modern runtime against
    the edit. That is the cheapest possible way to turn a parity failure into a
    parity pass, and it leaves no other trace in the runtime evidence.
    """
    manifest = contract / "manifest.sha256"
    recorded = hashlib.sha256(manifest.read_bytes()).hexdigest()
    digest = hashlib.sha256()
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        expected_hex, name = line.split(None, 1)
        name = name.strip()
        target = contract / name
        if not target.is_file():
            return recorded, f"missing:{name}"
        actual = hashlib.sha256(target.read_bytes()).hexdigest()
        if actual != expected_hex:
            return recorded, f"modified:{name}"
        digest.update(name.encode("utf-8"))
        digest.update(bytes.fromhex(actual))
    return recorded, digest.hexdigest()


def check_provenance(root: Path, repo_root: Path, base_url: str) -> list[str]:
    path = root / "provenance.json"
    if not path.is_file():
        return ["provenance.json is absent, so the evidence cannot say which "
                "commit or which origin produced it"]
    data = json.loads(path.read_text(encoding="utf-8"))
    problems: list[str] = []
    if data.get("phase") != "5g-1b":
        problems.append(f"provenance names phase {data.get('phase')!r}, not 5g-1b")
    head = subprocess.run(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        capture_output=True, text=True, check=True).stdout.strip()
    if data.get("git_head") != head:
        problems.append(
            f"evidence was captured at {data.get('git_head')} but this tree is at "
            f"{head}; a parity claim may only cite a capture of the code it ships")
    # Scoring mode, always. A freeze run invents the expected answer, so it can
    # never also be the run that verifies it.
    if data.get("mode") != "false":
        problems.append(
            f"provenance records mode {data.get('mode')!r}; Phase 5g-1b may only "
            "ever run with freezing off")
    if data.get("base_url") != base_url:
        problems.append(
            f"evidence was captured against {data.get('base_url')!r}, not the "
            f"public routed origin {base_url!r}. ADR decision 6 scores only the "
            "origin a user actually reaches; capturing on the loopback modern "
            "origin would prove the modern application works with the entire "
            "routing layer bypassed, which is not the claim")
    return problems


def check_capture(root: Path, label: str, contract: Path,
                  base_url: str, modern_port: str) -> list[str]:
    capture = root / label
    problems: list[str] = []
    if not capture.is_dir():
        return [f"capture {label} is absent"]

    def missing(name: str) -> bool:
        target = capture / name
        if not target.is_file() or target.stat().st_size == 0:
            problems.append(f"capture {label}: {name} is absent or empty")
            return True
        return False

    # WHICH application served the write. Every other observation in a capture
    # is runtime-blind -- one public origin, normalized URLs, the product's own
    # database effects -- so a routed lane that failed closed to legacy would
    # score a PERFECT green against the legacy oracle and report modern parity.
    if not missing("runtime-identification.tsv"):
        identity = keyed(capture / "runtime-identification.tsv")
        if identity.get("expected") != "modern" or identity.get("served") != "modern":
            problems.append(
                f"capture {label} was not served by the modern runtime: {identity}")

    # The browser must never have reached the loopback modern origin directly.
    # Requests are recorded before routing, so an aborted request still appears
    # here -- which is what makes this an observation rather than a restatement
    # of the blocking rule.
    if not missing("network-requests.tsv"):
        origin = base_url.rsplit("/", 1)[0]
        for row in rows(capture / "network-requests.tsv"):
            url = row[-1]
            if f":{modern_port}" in url or "/webui-modern" in url:
                problems.append(
                    f"capture {label} reached the loopback modern origin directly: {url}")
                break
            if url.startswith("http") and not url.startswith(origin):
                problems.append(
                    f"capture {label} reached a foreign origin: {url}")
                break

    for name in FACT_CLASSES:
        missing(name)

    # The captured step ledger must be the frozen one. A capture that simply
    # stopped early would otherwise present a short, clean, entirely truthful
    # set of matching steps.
    if (capture / "write-flow.tsv").is_file() and (contract / "write-flow.tsv").is_file():
        got = {row[1] for row in rows(capture / "write-flow.tsv") if len(row) >= 2}
        want = {row[1] for row in rows(contract / "write-flow.tsv") if len(row) >= 2}
        if got != want:
            problems.append(
                f"capture {label} step ledger differs from the frozen contract: "
                f"missing {sorted(want - got)}, unexpected {sorted(got - want)}")

    # Independently restored from the same verified archive. Two captures taken
    # from one restore are not an A/B: they share every accident of the state
    # they started in, so their agreement proves nothing about reproducibility.
    if not missing("restore.tsv"):
        restore = keyed(capture / "restore.tsv")
        if not restore.get("golden_sha256"):
            problems.append(f"capture {label} does not record the archive it restored")

    census = capture / "census" / "census.tsv"
    if not census.is_file():
        problems.append(
            f"capture {label} has no ambient census, so nothing rules out a "
            "routed-lane writer other than the browser")
    elif keyed(census).get("verdict") != "pass":
        problems.append(f"capture {label} ambient census did not pass")

    return problems


def check_scoring(root: Path) -> list[str]:
    path = root / "score-summary.tsv"
    if not path.is_file():
        return ["score-summary.tsv is absent, so the captures were never scored"]
    summary = keyed(path)
    problems: list[str] = []
    if (FREEZE_MARKER[0], summary.get(FREEZE_MARKER[0])) == FREEZE_MARKER:
        problems.append(
            "the scorer ran in FREEZE mode. A run that produces the expected "
            "answer cannot also be the run that verifies it")
    if summary.get("self-diff") != "pass":
        problems.append("the A/B self-diff did not pass, so the capture is not reproducible")
    if summary.get("problems") not in (None, "0"):
        problems.append(f"the scorer reported {summary['problems']} problem(s)")
    return problems


def check_h6(root: Path) -> list[str]:
    path = root / "h6" / "h6-matrix.tsv"
    if not path.is_file():
        return ["the H6 write-traffic matrix is absent"]
    observed = {row[0]: row[1] for row in rows(path) if len(row) >= 2}
    problems: list[str] = []
    for row_id in H6_ROWS:
        if row_id not in observed:
            problems.append(f"H6 row {row_id} was not run")
        elif observed[row_id] != "pass":
            problems.append(f"H6 row {row_id} reported {observed[row_id]}")
    for row_id in set(observed) - set(H6_ROWS):
        problems.append(f"H6 matrix carries an undeclared row {row_id}")
    return problems


def check_lane(root: Path) -> list[str]:
    problems: list[str] = []
    for name, why in (
        ("quiesce-state.tsv",
         "nothing records that the configured background processors were quiesced"),
        ("cohort-config.tsv",
         "nothing records the cohort fixture the lane applied"),
        ("topology/topology.tsv",
         "nothing records the deployment the captures ran against"),
        ("session-evidence/session-lifecycle.tsv",
         "AD_Session is ambient and unkeyed, so without this the modern session "
         "lifecycle is unobserved rather than unchanged"),
    ):
        target = root / name
        if not target.is_file() or target.stat().st_size == 0:
            problems.append(f"{name} is absent or empty: {why}")

    topology = root / "topology" / "topology.tsv"
    if topology.is_file():
        observed = [row for row in rows(topology) if len(row) >= 2]
        values = {key: value for key, value in observed}
        for key in ("public_artifact", "modern_artifact", "modern_context_descriptor"):
            if not values.get(key) or values[key] == "absent":
                problems.append(f"topology does not identify {key}")
        if values.get("modern_default_context") != "absent":
            problems.append(
                "a second modern deployment was present, so the capture cannot "
                "say which modern application answered it")
        for key in ("public_listener", "modern_listener"):
            if not any(k == key and v != "absent" for k, v in observed):
                problems.append(f"topology records no {key}")
    return problems


def check_junit(root: Path, repo_root: Path) -> list[str]:
    """A gate that reported success while executing no test is not coverage."""
    reports = list((repo_root / "zkwebui" / "build" / "test-results").glob(
        "phase5g1bModernWriteParityCapture/*.xml"))
    if not reports:
        return ["no JUnit XML was produced by the modern parity capture"]
    if not any(report.stat().st_size > 0 for report in reports):
        return ["the modern parity capture produced only empty JUnit reports"]
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", required=True, type=Path)
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--modern-port", default=None)
    args = parser.parse_args()

    modern_port = args.modern_port
    if modern_port is None:
        runtime = args.repo_root / "gradle" / "phase4" / "runtime.properties"
        modern_port = ""
        for line in runtime.read_text(encoding="utf-8").splitlines():
            if line.startswith("api.port="):
                modern_port = line.split("=", 1)[1].strip()
                break

    problems: list[str] = []
    problems += check_provenance(args.evidence_root, args.repo_root, args.base_url)

    recorded, derived = contract_manifest_digest(args.contract)
    if derived.startswith(("missing:", "modified:")):
        problems.append(
            f"the frozen legacy contract does not match its own manifest ({derived}). "
            "Scoring the modern runtime against an edited answer is the cheapest "
            "possible way to turn a parity failure into a parity pass")

    for label in CAPTURES:
        problems += check_capture(
            args.evidence_root, label, args.contract, args.base_url, modern_port)
    problems += check_scoring(args.evidence_root)
    problems += check_lane(args.evidence_root)
    problems += check_h6(args.evidence_root)
    problems += check_junit(args.evidence_root, args.repo_root)

    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(
        "check\tresult\n"
        f"contract_manifest\t{recorded}\n"
        f"problems\t{len(problems)}\n"
        f"verdict\t{'fail' if problems else 'pass'}\n",
        encoding="utf-8")

    if problems:
        print("Phase 5g-1b runtime evidence REJECTED:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("Phase 5g-1b runtime evidence accepted: modern parity is proven by "
          f"{len(CAPTURES)} independently restored captures through {args.base_url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
