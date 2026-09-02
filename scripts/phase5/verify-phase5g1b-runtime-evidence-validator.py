#!/usr/bin/env python3
"""Prove the Phase 5g-1b evidence validator actually rejects what it claims to.

A fail-closed validator is only worth its name if each of its refusals fires. A
check that silently never triggers -- a key read from the wrong column, a
comparison against a value that is always absent, a loop over a directory that
is always empty -- is indistinguishable from a check that passes, and it fails
in the one direction that matters: it accepts evidence that should have been
refused, and it does so quietly, forever.

So this builds a synthetic evidence tree that the validator MUST accept, then
mutates it once per defect class and requires a rejection each time. The
mutations are the real failure modes of the routed parity lane, not arbitrary
byte corruption: a capture served by the legacy application, a capture taken
against the loopback modern origin, a scorer left in freeze mode, an edited
frozen contract, a shared restore, a missing H6 row.

This runs in about a second and needs no database, so the validator's own
correctness is checked on every pull request rather than only when the hour-long
capture lane happens to run.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
VALIDATOR = HERE / "validate-phase5g1b-runtime-evidence.py"
REPO_ROOT = HERE.parent.parent
BASE_URL = "http://127.0.0.1:8888/webui"
MODERN_PORT = "8443"

H6_ROWS = (
    "h6-loopback-origin-unreached",
    "h6-cohort-decision-modern",
    "h6-no-legacy-fallback-mid-write",
    "h6-ticket-replay-controls",
    "h6-session-cleanup-after-inflight-write",
    "h6-duplicate-submit",
)

FACT_CLASSES = (
    "semantic-facts.tsv",
    "business-values.tsv",
    "foreign-key-graph.tsv",
    "concurrency-facts.tsv",
    "network-classes.tsv",
    "browser-errors.tsv",
)


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def build_contract(contract: Path, steps: list[str]) -> None:
    write(contract / "write-flow.tsv",
          "".join(f"{index}\t{step}\n" for index, step in enumerate(steps)))
    write(contract / "ambient-tables.tsv", "ad_session\n")
    # The manifest is the thing every other Phase 5g gate hashes, so the
    # synthetic contract carries a real one -- in the REAL generator's format,
    # comment headers and tab separators included (write-oracle.gradle). A
    # fixture in a format the generator never emits would let the validator's
    # manifest parser drift away from the real contract undetected, which is
    # exactly how a comment-line parsing defect survived earlier review.
    import hashlib
    rows = []
    for name in sorted(p.name for p in contract.iterdir() if p.is_file()):
        digest = hashlib.sha256((contract / name).read_bytes()).hexdigest()
        rows.append(f"{digest}\t{name}")
    write(contract / "manifest.sha256",
          "# Phase 5g-1a legacy Business Partner write oracle manifest\n"
          "# SHA-256 over every file in contracts/legacy-web-write-v1/ except this file.\n"
          "# sha256\tpath\n"
          + "".join(f"{row}\n" for row in rows))


def manifest_sha256(contract: Path) -> str:
    import hashlib
    return hashlib.sha256((contract / "manifest.sha256").read_bytes()).hexdigest()


def build_evidence(root: Path, contract: Path, head: str, steps: list[str]) -> None:
    write(root / "provenance.json", json.dumps({
        "phase": "5g-1b",
        "git_head": head,
        "captured_at": "2026-01-01T00:00:00Z",
        "mode": "false",
        "base_url": BASE_URL,
    }) + "\n")
    write(root / "score-summary.tsv", "mode\tscore\nself-diff\tpass\nproblems\t0\n")
    write(root / "quiesce-state.tsv", "verified\ttrue\n")
    write(root / "goal-quiesce-state.tsv", "pa_goal\t100\tIsActive\tY\n")
    write(root / "seed-goal-quiesce-state.tsv", "pa_goal\t100\tIsActive\tY\n")
    write(root / "cohort-config.tsv", "preset\tuser-allowlisted\n")
    write(root / "topology" / "topology.tsv",
          "public_listener\t127.0.0.1:8888\n"
          f"modern_listener\t127.0.0.1:{MODERN_PORT}\n"
          "public_artifact\tsha256:aaa\n"
          "modern_artifact\tsha256:bbb\n"
          "modern_context_descriptor\tsha256:ccc\n"
          "modern_default_context\tabsent\n")
    write(root / "session-evidence" / "session-lifecycle.tsv",
          "pre-A\t1000\t11\t0\t100\t50\tN\tY\tfalse\n"
          "post-A\t1000\t11\t0\t100\t50\tN\tY\ttrue\n")
    write(root / "h6" / "h6-matrix.tsv",
          "".join(f"{row}\tpass\tsynthetic\n" for row in H6_ROWS))

    for label in ("A", "B"):
        capture = root / label
        write(capture / "runtime-identification.tsv",
              "expected\tmodern\nserved\tmodern\ndialect\tZkCe10Dialect\n")
        write(capture / "network-requests.tsv",
              f"GET\t{BASE_URL}/index.zul\n"
              f"POST\t{BASE_URL}/zkau\n")
        write(capture / "write-flow.tsv",
              "".join(f"{index}\t{step}\n" for index, step in enumerate(steps)))
        for name in FACT_CLASSES:
            write(capture / name, "key\tvalue\n")
        # Bracketed, disjoint, and long enough to look like a real restore.
        started = 1000 if label == "A" else 5000
        write(capture / "restore.tsv",
              f"label\t{label}\n"
              f"restore_started_at\t{started}\n"
              f"restored_at\t{started + 600}\n"
              "golden_sha256\tdeadbeef\n")
        # The lane copies each capture's JUnit report into the capture itself,
        # so the fixture does too. Reading the shared Gradle build directory
        # would let a stale report from an earlier run stand in for this one.
        write(capture / "junit" / "results.xml",
              '<testsuite tests="1" failures="0" errors="0"/>\n')
        write(capture / "census" / "census.tsv",
              "label\t%s\nquiet_seconds\t20\nverdict\tpass\n" % label)


def run(root: Path, contract: Path, junit: Path,
        expect_manifest: str | None = None) -> tuple[int, str]:
    result = subprocess.run(
        [sys.executable, str(VALIDATOR),
         "--evidence-root", str(root),
         "--contract", str(contract),
         "--repo-root", str(junit),
         "--base-url", BASE_URL,
         "--modern-port", MODERN_PORT,
         # The synthetic contract is not the frozen one, so the proof states
         # its expected manifest digest explicitly rather than disabling the
         # pinned check -- which would leave the increment's central
         # anti-self-scoring guard unexercised.
         "--expect-manifest-sha256", expect_manifest or manifest_sha256(contract),
         "--summary", str(root / "validation-summary.tsv")],
        capture_output=True, text=True)
    return result.returncode, result.stdout + result.stderr


def main() -> int:
    # The pinned constant is the increment's central governance guard, and the
    # mutation proof always overrides it (it scores a synthetic contract), so
    # nothing else on a pull request ever exercises its real value. Check it
    # against the tree here. Otherwise a constant left stale by a legitimate
    # re-freeze -- or a mistyped one -- stays green through every fast gate and
    # surfaces only at the END of the hour-long lane, after both captures, the
    # scorer and the whole H6 matrix have already run: exactly the failure
    # shape this proof exists to catch, one level up.
    import hashlib
    import re
    frozen_manifest = REPO_ROOT / "contracts" / "legacy-web-write-v1" / "manifest.sha256"
    actual = hashlib.sha256(frozen_manifest.read_bytes()).hexdigest()
    # Read the constant from the source text rather than importing the module.
    # Importing it goes through __pycache__, which can serve a previously
    # compiled copy of the very constant this check exists to re-read.
    match = re.search(r'^FROZEN_MANIFEST_SHA256 = "([0-9a-f]+)"',
                      VALIDATOR.read_text(encoding="utf-8"), re.MULTILINE)
    if match is None:
        print(f"{VALIDATOR.name} no longer pins FROZEN_MANIFEST_SHA256")
        return 1
    pinned = match.group(1)
    if actual != pinned:
        print("the pinned FROZEN_MANIFEST_SHA256 does not match the frozen contract:")
        print(f"  contracts/legacy-web-write-v1/manifest.sha256 is {actual}")
        print(f"  validate-phase5g1b-runtime-evidence.py pins  {pinned}")
        print("Re-pin the constant in the same commit that re-freezes the oracle.")
        return 1

    steps = ["baseline", "create", "update", "conflicting-save"]
    head = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD"],
        capture_output=True, text=True, check=True).stdout.strip()

    with tempfile.TemporaryDirectory() as scratch:
        base = Path(scratch)
        contract = base / "contract"
        pristine = base / "pristine"
        # The validator resolves the modern port from a repository-shaped tree
        # and pins evidence to its git HEAD, so the fixture supplies one.
        junit_root = base / "repo"
        write(junit_root / "gradle" / "phase4" / "runtime.properties",
              f"api.port={MODERN_PORT}\n")
        subprocess.run(["git", "init", "-q", str(junit_root)], check=True)
        subprocess.run(["git", "-C", str(junit_root), "add", "-A"], check=True)
        subprocess.run(
            ["git", "-C", str(junit_root), "-c", "user.email=t@t", "-c", "user.name=t",
             "commit", "-qm", "fixture"], check=True)
        head = subprocess.run(
            ["git", "-C", str(junit_root), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip()

        build_contract(contract, steps)
        build_evidence(pristine, contract, head, steps)

        status, output = run(pristine, contract, junit_root)
        if status != 0:
            print("the pristine synthetic evidence was rejected, so every mutation "
                  "below would 'fail' for the wrong reason:")
            print(output)
            return 1

        mutations: list[tuple[str, object]] = [
            ("a capture served by the LEGACY application",
             lambda root: write(root / "A" / "runtime-identification.tsv",
                                "expected\tmodern\nserved\tlegacy\ndialect\tZk36Dialect\n")),
            ("a capture that reached the loopback modern origin directly",
             lambda root: write(root / "A" / "network-requests.tsv",
                                f"GET\thttp://127.0.0.1:{MODERN_PORT}/webui-modern/index.zul\n")),
            ("evidence captured against an origin other than the public one",
             lambda root: write(root / "provenance.json", json.dumps({
                 "phase": "5g-1b", "git_head": head, "captured_at": "x",
                 "mode": "false",
                 "base_url": f"http://127.0.0.1:{MODERN_PORT}/webui-modern"}) + "\n")),
            ("a scorer left in FREEZE mode",
             lambda root: write(root / "score-summary.tsv", "mode\tfreeze\nself-diff\tpass\n")),
            ("a capture whose step ledger is short of the frozen one",
             lambda root: write(root / "B" / "write-flow.tsv", "0\tbaseline\n1\tcreate\n")),
            ("a capture with no ambient census",
             lambda root: (root / "A" / "census" / "census.tsv").unlink()),
            ("an ambient census that did not pass",
             lambda root: write(root / "A" / "census" / "census.tsv", "verdict\tfail\n")),
            ("a capture that does not record the archive it restored",
             lambda root: write(root / "A" / "restore.tsv", "label\tA\n")),
            ("two captures whose restore intervals overlap, so the second reused "
             "the first's restore",
             lambda root: write(root / "B" / "restore.tsv",
                                "label\tB\nrestore_started_at\t1200\n"
                                "restored_at\t1800\ngolden_sha256\tdeadbeef\n")),
            ("a capture whose restore was too short to have happened",
             lambda root: write(root / "B" / "restore.tsv",
                                "label\tB\nrestore_started_at\t5000\n"
                                "restored_at\t5001\ngolden_sha256\tdeadbeef\n")),
            ("a capture that does not bracket its restore",
             lambda root: write(root / "B" / "restore.tsv",
                                "label\tB\nrestored_at\t5600\n"
                                "golden_sha256\tdeadbeef\n")),
            ("captures restored from different archives",
             lambda root: write(root / "B" / "restore.tsv",
                                "label\tB\nrestore_started_at\t5000\n"
                                "restored_at\t5600\ngolden_sha256\tfeedface\n")),
            ("a missing H6 row",
             lambda root: write(root / "h6" / "h6-matrix.tsv", "".join(
                 f"{row}\tpass\tsynthetic\n" for row in H6_ROWS[:-1]))),
            ("a failing H6 row",
             lambda root: write(root / "h6" / "h6-matrix.tsv", "".join(
                 f"{row}\t{'fail' if index == 0 else 'pass'}\tsynthetic\n"
                 for index, row in enumerate(H6_ROWS)))),
            ("an unquiesced performance-goal recalculation",
             lambda root: (root / "goal-quiesce-state.tsv").unlink()),
            ("a seed captured from an unquiesced legacy lane",
             lambda root: (root / "seed-goal-quiesce-state.tsv").unlink()),
            ("an unobserved session lifecycle",
             lambda root: (root / "session-evidence" / "session-lifecycle.tsv").unlink()),
            ("a second modern deployment",
             lambda root: write(root / "topology" / "topology.tsv",
                                "public_listener\t127.0.0.1:8888\n"
                                f"modern_listener\t127.0.0.1:{MODERN_PORT}\n"
                                "public_artifact\tsha256:aaa\n"
                                "modern_artifact\tsha256:bbb\n"
                                "modern_context_descriptor\tsha256:ccc\n"
                                "modern_default_context\tpresent\n")),
            ("a scorer that reported problems",
             lambda root: write(root / "score-summary.tsv",
                                "mode\tscore\nself-diff\tpass\nproblems\t3\n")),
            ("a non-reproducible A/B self-diff",
             lambda root: write(root / "score-summary.tsv",
                                "mode\tscore\nself-diff\tfail\nproblems\t0\n")),
            ("evidence captured at a different commit",
             lambda root: write(root / "provenance.json", json.dumps({
                 "phase": "5g-1b", "git_head": "0" * 40, "captured_at": "x",
                 "mode": "false", "base_url": BASE_URL}) + "\n")),
        ]

        undetected: list[str] = []
        scored = 0
        for description, mutate in mutations:
            scratch_root = base / "mutant"
            shutil.rmtree(scratch_root, ignore_errors=True)
            shutil.copytree(pristine, scratch_root)
            mutate(scratch_root)
            status, _ = run(scratch_root, contract, junit_root)
            scored += 1
            if status == 0:
                undetected.append(description)

        # The edited frozen contract is mutated on the CONTRACT side, not the
        # evidence side, because that is where the failure actually lives: a
        # branch that relaxes the frozen legacy answer and then scores the
        # modern runtime against the relaxation leaves the runtime evidence
        # entirely intact.
        edited = base / "edited-contract"
        shutil.copytree(contract, edited)
        (edited / "write-flow.tsv").write_text("0\tbaseline\n", encoding="utf-8")
        scratch_root = base / "mutant"
        shutil.rmtree(scratch_root, ignore_errors=True)
        shutil.copytree(pristine, scratch_root)
        status, _ = run(scratch_root, edited, junit_root)
        scored += 1
        if status == 0:
            undetected.append("a frozen legacy contract edited out from under the scorer")

        # ISOLATING the two manifest guards. The mutation above trips the step
        # ledger as well as the manifest walk, so on its own it would stay
        # green even if both manifest guards were dead code. These two do not:
        # each is rejectable ONLY by the guard it names.
        #
        # (a) A re-freeze: the contract and its manifest agree perfectly, so
        # the walk is satisfied, and every step, fact and row still matches.
        # Only the pinned digest can notice.
        refrozen = base / "refrozen-contract"
        shutil.copytree(contract, refrozen)
        (refrozen / "manifest.sha256").write_text(
            (refrozen / "manifest.sha256").read_text(encoding="utf-8")
            + "# regenerated in a parity increment\n", encoding="utf-8")
        scratch_root = base / "mutant"
        shutil.rmtree(scratch_root, ignore_errors=True)
        shutil.copytree(pristine, scratch_root)
        status, _ = run(scratch_root, refrozen, junit_root,
                        expect_manifest=manifest_sha256(contract))
        scored += 1
        if status == 0:
            undetected.append(
                "a frozen legacy contract re-frozen so that it agrees with its "
                "own regenerated manifest")

        # (b) A manifest-listed file deleted: exercises the walk's `missing:`
        # branch, which the edited-contract case never reaches.
        truncated = base / "truncated-contract"
        shutil.copytree(contract, truncated)
        (truncated / "ambient-tables.tsv").unlink()
        scratch_root = base / "mutant"
        shutil.rmtree(scratch_root, ignore_errors=True)
        shutil.copytree(pristine, scratch_root)
        status, _ = run(scratch_root, truncated, junit_root,
                        expect_manifest=manifest_sha256(truncated))
        scored += 1
        if status == 0:
            undetected.append("a frozen legacy contract file the manifest still lists")

        # A gate that reports success while executing no test is not coverage.
        # These are capture-level now, because the lane binds each report to
        # the capture that produced it. All three shapes below would satisfy a
        # mere "a file exists and is non-empty" check.
        junit_mutations: list[tuple[str, object]] = [
            ("a capture that produced no JUnit report",
             lambda root: shutil.rmtree(root / "A" / "junit")),
            ("a capture whose JUnit suite executed zero tests",
             lambda root: write(root / "A" / "junit" / "results.xml",
                                '<testsuite tests="0" failures="0" errors="0"/>\n')),
            ("a capture whose JUnit suite reported a failure",
             lambda root: write(root / "A" / "junit" / "results.xml",
                                '<testsuite tests="1" failures="1" errors="0"/>\n')),
        ]
        for description, mutate in junit_mutations:
            scratch_root = base / "mutant"
            shutil.rmtree(scratch_root, ignore_errors=True)
            shutil.copytree(pristine, scratch_root)
            mutate(scratch_root)
            status, _ = run(scratch_root, contract, junit_root)
            scored += 1
            if status == 0:
                undetected.append(description)

    if undetected:
        print("the Phase 5g-1b evidence validator ACCEPTED evidence it must refuse:")
        for description in undetected:
            print(f"  - {description}")
        return 1

    print(f"Phase 5g-1b evidence validator rejected all "
          f"{scored} injected defect classes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
