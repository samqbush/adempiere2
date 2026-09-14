#!/usr/bin/env python3
"""Validate every repository XFire reference against the reviewed inventory."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

TEXT_EXTENSIONS = {
    "classpath",
    "gradle",
    "iml",
    "java",
    "md",
    "sh",
    "tsv",
    "txt",
    "xml",
}
VALID_DISPOSITIONS = {"remove", "retain-evidence", "migrate"}


def parse_inventory(path: Path) -> list[tuple[str, str, str]]:
    if not path.is_file():
        raise ValueError("Phase 4 XFire removal inventory is missing")

    rows: list[tuple[str, str, str]] = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 3:
            raise ValueError(f"Malformed XFire inventory row: {line}")
        rows.append((fields[0], fields[1], fields[2]))

    paths = [row[0] for row in rows]
    duplicates = sorted({path for path in paths if paths.count(path) != 1})
    if duplicates:
        raise ValueError(f"Duplicate XFire inventory paths: {duplicates}")

    invalid = [
        row
        for row in rows
        if row[1] not in VALID_DISPOSITIONS or not row[2]
    ]
    if invalid:
        raise ValueError(f"Invalid XFire inventory rows: {invalid}")
    return rows


def discover_references(repo_root: Path, inventory: Path) -> set[str]:
    inventory_relative = inventory.relative_to(repo_root).as_posix()
    discovered: set[str] = set()
    for current_root, directories, filenames in os.walk(repo_root):
        directories[:] = [
            directory
            for directory in directories
            if directory not in {".git", ".gradle", "__pycache__", "build", "dist"}
            and not (Path(current_root).name == "WEB-INF" and directory == "classes")
        ]
        for filename in filenames:
            path = Path(current_root, filename)
            relative_text = path.relative_to(repo_root).as_posix()
            if relative_text == inventory_relative:
                continue

            named_artifact = "xfire" in filename.lower()
            contains_reference = False
            last_dot = filename.rfind(".")
            extension = filename[last_dot + 1 :].lower() if last_dot >= 0 else ""
            if extension in TEXT_EXTENSIONS:
                contains_reference = (
                    "xfire"
                    in path.read_text(encoding="utf-8", errors="replace").lower()
                )
            if named_artifact or contains_reference:
                discovered.add(relative_text)
    return discovered


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", type=Path)
    parser.add_argument(
        "--inventory",
        default=Path("gradle/phase4/xfire-removal.tsv"),
        type=Path,
    )
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    inventory = (
        args.inventory.resolve()
        if args.inventory.is_absolute()
        else (repo_root / args.inventory).resolve()
    )

    try:
        rows = parse_inventory(inventory)
        discovered = discover_references(repo_root, inventory)
    except (OSError, UnicodeError, ValueError) as exc:
        print(exc, file=sys.stderr)
        return 1

    expected = {row[0] for row in rows}
    untracked = sorted(discovered - expected)
    stale = sorted(expected - discovered)
    if untracked or stale:
        print(
            f"XFire inventory drifted; untracked={untracked}, stale={stale}",
            file=sys.stderr,
        )
        return 1

    print(
        "validated Phase 4 XFire removal inventory: "
        f"{len(expected)} reviewed references match the repository"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
