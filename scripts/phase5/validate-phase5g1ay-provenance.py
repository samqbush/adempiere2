#!/usr/bin/env python3
"""Validate corrected-legacy runtime provenance for Phase 5g-1a-y."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
from typing import Any


HEX_40 = re.compile(r"^[0-9a-f]{40}$")
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_RUNTIME_ARTIFACT_PATHS = [
    "build/phase5g1ay/corrected-runtime/Adempiere.jar",
    "build/phase5g1ay/corrected-runtime/classes/org/compiere/wf/DocWorkflowManager.class",
    "build/phase5g1ay/corrected-runtime/classes/org/compiere/wf/MWorkflow.class",
    "build/phase5g1ay/corrected-runtime/classes/org/compiere/wf/MWFProcess.class",
]


class ValidationError(RuntimeError):
    pass


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_contract(contract_dir: pathlib.Path) -> dict[str, str]:
    rows: dict[str, str] = {}
    for line in (contract_dir / "capture-contract.tsv").read_text(
        encoding="utf-8"
    ).splitlines():
        if not line or line.startswith("#"):
            continue
        key, value = line.split("\t")
        rows[key] = value
    return rows


def allowed_paths(contract_dir: pathlib.Path) -> list[str]:
    return [
        line
        for line in (contract_dir / "allowed-patched-paths.txt")
        .read_text(encoding="utf-8")
        .splitlines()
        if line and not line.startswith("#")
    ]


def repository_head(repo_root: pathlib.Path) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        raise ValidationError(
            f"git rev-parse HEAD failed: {completed.stderr.strip()}"
        )
    head = completed.stdout.strip()
    if not HEX_40.fullmatch(head):
        raise ValidationError(f"git rev-parse HEAD returned an invalid object id: {head}")
    return head


def validate_provenance(
    data: Any,
    contract_dir: pathlib.Path,
    repo_root: pathlib.Path,
    verify_artifacts: bool = False,
) -> int:
    if not isinstance(data, dict):
        raise ValidationError("provenance root must be an object")
    required = {
        "schema_version",
        "phase",
        "mode",
        "source_commit",
        "patch_sha256",
        "patched_paths",
        "runtime_artifacts",
        "repository_head",
        "oracle_operation",
        "isolated_source_worktree",
        "ordinary_repository_unchanged",
    }
    missing = required - set(data)
    if missing:
        raise ValidationError(f"provenance is missing fields: {sorted(missing)}")

    contract = load_contract(contract_dir)
    expected_paths = allowed_paths(contract_dir)
    exact = {
        "schema_version": int(contract["provenance_schema_version"]),
        "phase": "5g-1a-y",
        "mode": contract["mode"],
        "source_commit": contract["source_commit"],
        "patch_sha256": contract["patch_sha256"],
        "patched_paths": expected_paths,
        "isolated_source_worktree": True,
        "ordinary_repository_unchanged": True,
    }
    differences = {
        key: (expected, data.get(key))
        for key, expected in exact.items()
        if data.get(key) != expected
    }
    if differences:
        raise ValidationError(f"provenance contract fields differ: {differences}")
    if not HEX_40.fullmatch(str(data["repository_head"])):
        raise ValidationError("repository_head must be a full lowercase Git object id")
    expected_head = repository_head(repo_root)
    if data["repository_head"] != expected_head:
        raise ValidationError(
            "repository_head differs from git rev-parse HEAD: "
            f"expected={expected_head}, actual={data['repository_head']}"
        )
    if data["oracle_operation"] not in {"freeze", "acceptance"}:
        raise ValidationError("oracle_operation must be freeze or acceptance")

    artifacts = data["runtime_artifacts"]
    if not isinstance(artifacts, list):
        raise ValidationError("runtime_artifacts must be a list")
    artifact_paths = [
        artifact.get("path") if isinstance(artifact, dict) else None
        for artifact in artifacts
    ]
    if artifact_paths != EXPECTED_RUNTIME_ARTIFACT_PATHS:
        raise ValidationError(
            "runtime_artifacts differ from the exact corrected-runtime inventory: "
            f"expected={EXPECTED_RUNTIME_ARTIFACT_PATHS}, actual={artifact_paths}"
        )
    paths: set[str] = set()
    for artifact in artifacts:
        if not isinstance(artifact, dict) or set(artifact) != {"path", "sha256"}:
            raise ValidationError(f"invalid runtime artifact entry: {artifact}")
        relative = artifact["path"]
        digest = artifact["sha256"]
        if (
            not isinstance(relative, str)
            or not relative.startswith("build/phase5g1ay/corrected-runtime/")
            or relative in paths
        ):
            raise ValidationError(f"invalid or duplicate runtime artifact path: {relative}")
        if not isinstance(digest, str) or not HEX_64.fullmatch(digest):
            raise ValidationError(f"invalid runtime artifact digest: {digest}")
        paths.add(relative)
        if verify_artifacts:
            artifact_path = (repo_root / relative).resolve()
            expected_root = (repo_root / "build/phase5g1ay/corrected-runtime").resolve()
            if expected_root not in artifact_path.parents:
                raise ValidationError(f"runtime artifact escaped corrected root: {relative}")
            if not artifact_path.is_file():
                raise ValidationError(f"runtime artifact is missing: {relative}")
            if sha256(artifact_path) != digest:
                raise ValidationError(f"runtime artifact digest differs: {relative}")
    return len(artifacts)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--provenance", required=True, type=pathlib.Path)
    parser.add_argument("--contract-dir", required=True, type=pathlib.Path)
    parser.add_argument("--repo-root", required=True, type=pathlib.Path)
    parser.add_argument("--verify-artifacts", action="store_true")
    parser.add_argument(
        "--schema-fixture",
        action="store_true",
        help="replace the fixture-only $GIT_HEAD token before strict validation",
    )
    args = parser.parse_args()
    try:
        data = json.loads(args.provenance.read_text(encoding="utf-8"))
        repo_root = args.repo_root.resolve()
        if args.schema_fixture:
            if data.get("repository_head") != "$GIT_HEAD":
                raise ValidationError(
                    "schema fixture repository_head must be the $GIT_HEAD token"
                )
            data["repository_head"] = repository_head(repo_root)
        count = validate_provenance(
            data,
            args.contract_dir.resolve(),
            repo_root,
            args.verify_artifacts,
        )
        print(f"Phase 5g-1a-y provenance verified: {count} runtime artifacts")
        return 0
    except (OSError, ValueError, ValidationError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
