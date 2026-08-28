#!/usr/bin/env python3
"""Prove the runtime-evidence validator rejects incomplete or synthetic ledgers."""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
from pathlib import Path


DIRS = {"/": "ROOT", "/adempiere": "adempiere", "/admin": "admin", "/mobile": "mobile", "/webui": "webui", "/wstore": "wstore"}
ELIGIBLE = {"/", "/wstore"}
CONFIDENTIAL_WSTORE = {
    "/wstore/login.jsp", "/wstore/loginServlet",
    "/wstore/checkOutServlet", "/wstore/orderServlet",
}
FIELDS = [
    "route_id", "context", "mode", "method", "auth_enforcement", "traffic_class",
    "status", "expected_status", "headers_sha256", "body_sha256", "set_cookie",
    "location", "header_contract", "cookie_contract", "tls_contract",
    "body_contract", "session_contract", "database_before",
    "database_after", "database_tables_before", "database_tables_after",
    "database_changed_tables", "database_effect_contract",
    "owned_tables_or_group",
    "public_origin_only",
]


def invoke(
    validator: Path, root: Path, contract: Path, effects: Path,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            "python3", str(validator), "--evidence-root", str(root),
            "--contract", str(contract), "--effects", str(effects),
            "--summary", str(root / "summary.tsv"),
        ],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False,
    )


def expected_modern_status(value: str) -> str:
    if value.startswith("preserve-legacy-status:"):
        return value.rsplit(":", 1)[1]
    return value.rsplit("=", 1)[-1]


def confidential(path: str) -> bool:
    return any(path == owned or path.startswith(owned + "/")
               for owned in CONFIDENTIAL_WSTORE)


def fixture_row(
    route: dict[str, str], effect: dict[str, str],
    context: str, mode: str, status: str,
    *, public: bool = True, set_cookie: str = "false",
    location: str = "none",
) -> dict[str, str]:
    return {
        "route_id": route["route_id"], "context": context,
        "mode": mode, "method": route["method"],
        "auth_enforcement": route["auth_enforcement"],
        "traffic_class": route["traffic_class"],
        "status": status, "expected_status": status,
        "headers_sha256": "1" * 64, "body_sha256": "2" * 64,
        "set_cookie": set_cookie, "database_before": "3" * 64,
        "location": location, "header_contract": "fixture-header",
        "cookie_contract": "fixture-cookie",
        "tls_contract": "fixture-tls",
        "body_contract": "fixture-body",
        "session_contract": "fixture-session",
        "database_after": "3" * 64,
        "database_tables_before": "{}",
        "database_tables_after": "{}",
        "database_changed_tables": "none",
        "database_effect_contract": effect["effect_contract"],
        "owned_tables_or_group": effect["owned_tables_or_group"],
        "public_origin_only": "true" if public else "false",
    }


def mark_mode_non_public(root: Path, directory: str, mode: str) -> None:
    path = root / directory / "route-observations.tsv"
    lines = path.read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        if f"\t{mode}\t" in line:
            fields = line.split("\t")
            fields[-1] = "false"
            lines[index] = "\t".join(fields)
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            return
    raise RuntimeError(f"fixture has no {mode} observation")


def mutate_first_row(root: Path, directory: str, changes: dict[str, str]) -> None:
    path = root / directory / "route-observations.tsv"
    with path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    rows[0].update(changes)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream, fieldnames=list(rows[0]), delimiter="\t",
            lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo_root.resolve()
    contract = repo / "contracts/phase5f-jakarta-web-v1/route-validation.tsv"
    effects_path = (
        repo / "contracts/phase5f-jakarta-web-v1/database-effect-ownership.tsv"
    )
    routes = list(csv.DictReader(contract.open(encoding="utf-8", newline=""), delimiter="\t"))
    effects = {
        row["route_id"]: row for row in csv.DictReader(
            effects_path.open(encoding="utf-8", newline=""), delimiter="\t"
        )
    }
    validator = repo / "scripts/phase5/validate-phase5f-runtime-evidence.py"
    if args.work_dir.exists():
        shutil.rmtree(args.work_dir)
    fixture = args.work_dir / "complete-fixture"
    for context, directory_name in DIRS.items():
        directory = fixture / directory_name
        directory.mkdir(parents=True)
        selected = [row for row in routes if row["context"] == context]
        evidence_rows = [
            fixture_row(
                route, effects[route["route_id"]], context,
                "legacy-public", route["legacy_status"])
            for route in selected
        ]
        if context in ELIGIBLE:
            for route in selected:
                is_confidential = (
                    context == "/wstore" and confidential(route["path"])
                )
                if is_confidential:
                    evidence_rows.append(fixture_row(
                        route, effects[route["route_id"]], context,
                        "modern-public-tls-redirect", "302",
                        location="https://public.example" + route["path"],
                    ))
                evidence_rows.append(fixture_row(
                    route, effects[route["route_id"]], context,
                    "modern-public-confidential"
                    if is_confidential else "modern-public",
                    expected_modern_status(route["modern_status_contract"]),
                    public=True,
                ))
            if context == "/wstore":
                bootstrap = next(
                    route for route in selected
                    if route["route_id"].startswith("/wstore::Index::")
                )
                evidence_rows.extend([
                    fixture_row(
                        bootstrap, effects[bootstrap["route_id"]], context,
                        "modern-session-bootstrap",
                        expected_modern_status(
                            bootstrap["modern_status_contract"]),
                        set_cookie="true",
                    ),
                    fixture_row(
                        bootstrap, effects[bootstrap["route_id"]], context,
                        "modern-session-follow-up",
                        expected_modern_status(
                            bootstrap["modern_status_contract"]),
                    ),
                ])
        else:
            (directory / "modern-unexecuted.tsv").write_text(
                "context\troute_count\treason\n"
                f"{context}\t{len(selected)}\tvalidator-fixture-unexecuted\n",
                encoding="utf-8",
            )
        (directory / "provenance.json").write_text(json.dumps({
            "context": context, "public_origin": "http://127.0.0.1:8888",
            "git_head": "validator-fixture-not-runtime-evidence",
            "database_marker": "ADempiere Phase 3 disposable database",
            "route_count": len(selected),
            "observation_count": len(evidence_rows),
            "client": "python-urllib-public-origin",
            "legacy_cookie_isolation": "fresh-cookie-jar-per-route",
            "public_https_origin": "https://127.0.0.1:8444",
            "modern_execution": (
                "all-routes-public-http-or-https"
                if context in ELIGIBLE else "explicitly-unexecuted"
            ),
        }, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        with (directory / "route-observations.tsv").open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, FIELDS, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(evidence_rows)
    (fixture / "phase4-soap-coexistence.tsv").write_text(
        "phase4_soap_corpus\tpass\twhile-phase5f-route-shards-active\n",
        encoding="utf-8",
    )
    (fixture / "secret-hygiene.tsv").write_text("secret_hygiene\tpass\n", encoding="utf-8")
    if invoke(validator, fixture, contract, effects_path).returncode != 0:
        raise SystemExit("complete validator fixture was rejected")

    mutations = {
        "missing-route": lambda root: (root / "ROOT/route-observations.tsv").write_text(
            "\n".join((root / "ROOT/route-observations.tsv").read_text().splitlines()[:-1]) + "\n",
            encoding="utf-8",
        ),
        "wrong-status": lambda root: (root / "admin/route-observations.tsv").write_text(
            (root / "admin/route-observations.tsv").read_text().replace("\t401\t401\t", "\t200\t401\t", 1),
            encoding="utf-8",
        ),
        "non-public-origin": lambda root: (root / "mobile/route-observations.tsv").write_text(
            (root / "mobile/route-observations.tsv").read_text().replace("\ttrue\n", "\tfalse\n", 1),
            encoding="utf-8",
        ),
        "confidential-non-public-origin": lambda root:
            mark_mode_non_public(
                root, "wstore", "modern-public-confidential"),
        "missing-public-https-provenance": lambda root: (
            root / "wstore/provenance.json"
        ).write_text(
            json.dumps({
                key: value for key, value in json.loads(
                    (root / "wstore/provenance.json").read_text()
                ).items() if key != "public_https_origin"
            }, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        ),
        "database-write": lambda root: (root / "ROOT/route-observations.tsv").write_text(
            (root / "ROOT/route-observations.tsv").read_text().replace(
                "3" * 64 + "\t" + "3" * 64,
                "3" * 64 + "\t" + "4" * 64,
                1,
            ),
            encoding="utf-8",
        ),
        "wrong-database-contract": lambda root: mutate_first_row(
            root, "ROOT",
            {"database_effect_contract": "session-bootstrap-owned"}),
        "wrong-database-group": lambda root: mutate_first_row(
            root, "ROOT", {"owned_tables_or_group": "AD_Session"}),
        "unexpected-owned-snapshot-write": lambda root: mutate_first_row(
            root, "ROOT", {
                "database_after": "4" * 64,
                "database_tables_before": '{"c_order":"a"}',
                "database_tables_after": '{"c_order":"b"}',
                "database_changed_tables": "c_order",
            }),
        "legacy-cookie-reuse": lambda root: (
            root / "ROOT/provenance.json"
        ).write_text(
            (root / "ROOT/provenance.json").read_text().replace(
                "fresh-cookie-jar-per-route", "shared-cookie-jar"),
            encoding="utf-8",
        ),
        "missing-soap": lambda root: (root / "phase4-soap-coexistence.tsv").unlink(),
        "missing-modern-route": lambda root: (root / "ROOT/route-observations.tsv").write_text(
            "\n".join(
                line for line in
                (root / "ROOT/route-observations.tsv").read_text().splitlines()
                if not (
                    "modern-public" in line
                    and "/::AdRedirector::/AdRedirector" in line
                )
            ) + "\n",
            encoding="utf-8",
        ),
        "wrong-modern-status": lambda root: (root / "ROOT/route-observations.tsv").write_text(
            (root / "ROOT/route-observations.tsv").read_text().replace(
                "\tmodern-public\tGET\t", "\tmodern-public\tGET\t", 1
            ).replace("\t400\t400\t", "\t200\t200\t", 1),
            encoding="utf-8",
        ),
        "missing-stateful-follow-up": lambda root: (root / "wstore/route-observations.tsv").write_text(
            "\n".join(
                line for line in
                (root / "wstore/route-observations.tsv").read_text().splitlines()
                if "modern-session-follow-up" not in line
            ) + "\n",
            encoding="utf-8",
        ),
        "disabled-counted-modern": lambda root: (root / "admin/route-observations.tsv").write_text(
            (root / "admin/route-observations.tsv").read_text()
            + (root / "admin/route-observations.tsv").read_text().splitlines()[1]
            .replace("legacy-public", "modern-public") + "\n",
            encoding="utf-8",
        ),
    }
    report = ["mutation\tresult"]
    for name, mutate in mutations.items():
        target = args.work_dir / name
        shutil.copytree(fixture, target)
        mutate(target)
        if invoke(validator, target, contract, effects_path).returncode == 0:
            raise SystemExit(f"runtime evidence mutation was accepted: {name}")
        report.append(f"{name}\tdetected")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text("\n".join(report) + "\n", encoding="utf-8")
    print(f"detected all {len(mutations)} Phase 5f runtime-evidence mutations")


if __name__ == "__main__":
    main()
