#!/usr/bin/env python3
"""Generate the reviewed Phase 5f route and database-effect ledgers."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path


CONTEXT_COUNTS = {
    "/": 8,
    "/adempiere": 21,
    "/admin": 4,
    "/mobile": 14,
    "/webui": 6,
    "/wstore": 29,
}


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def write_tsv(path: Path, fieldnames: list[str], rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream, fieldnames=fieldnames, delimiter="\t", lineterminator="\n"
        )
        writer.writeheader()
        writer.writerows(rows)


def route_contracts(path: Path) -> dict[str, dict[str, str]]:
    rows: dict[str, dict[str, str]] = {}
    with path.open(encoding="utf-8") as stream:
        reader = csv.reader(
            (line for line in stream if line.strip() and not line.startswith("#")),
            delimiter="\t",
        )
        for values in reader:
            if len(values) != 15:
                raise SystemExit(f"malformed Phase 5 route row: {values}")
            (
                _descriptor,
                _kind,
                name,
                _implementation,
                pattern,
                _configured_security,
                traffic_class,
                _behavioral_gate,
                _owner,
                deployment_status,
                _artifact,
                context,
                auth_enforcement,
                _disposition,
                _closing_gate,
            ) = values
            if deployment_status != "deployed" or context == "/ADInterface":
                continue
            route_id = f"{context}::{name.strip()}::{pattern}"
            if route_id in rows:
                raise SystemExit(f"duplicate deployed route contract: {route_id}")
            rows[route_id] = {
                "traffic_class": traffic_class,
                "auth_enforcement": auth_enforcement,
            }
    return rows


def effect_for(route_id: str, context: str) -> tuple[str, str, str]:
    name = route_id.split("::")[1]
    if route_id in {
        "/::AdRedirector::/AdRedirector",
        "/::Community::/communityServlet",
        "/::XMLBroadcast::/xml/*",
        "/wstore::PaymentServlet::/paymentServlet",
    }:
        return ("no-new-write", "none", "Reviewed corrected-error probe.")
    if context == "/webui":
        if name == "dspLoader":
            return ("no-write", "none", "Static compatibility resource.")
        if name == "timelineFeed":
            return ("read-only", "AD_RecentItem", "Timeline reads recent items.")
        return (
            "existing-phase5d-session-contract",
            "AD_Session,AD_Preference,AD_RecentItem,AD_ChangeLog",
            "Inherited modern UI session and read-only-window effect.",
        )
    if context == "/admin":
        if name == "JnlpDownloadServlet":
            return ("no-write", "none", "Artifact delivery.")
        return ("read-only", "application-dictionary", "Status and monitor reads.")
    if context == "/":
        return (
            "content-read-plus-owned-access-metadata",
            "web-content,web-access-log",
            "Content route may record reviewed access metadata.",
        )
    if context in {"/mobile", "/adempiere"}:
        mutating = {
            "WAttachment",
            "WChat",
            "WProcess",
            "WRequest",
            "WTask",
            "WValuePreference",
            "WWorkflow",
        }
        if name in mutating:
            return (
                "method-specific-fixture-required",
                "AD_Session,AD_Preference,business-table-owned-by-route",
                "GET probe permits no new business write; mutating methods require a named fixture delta.",
            )
        if name in {"WLogin", "LoginDynUpdate"}:
            return (
                "session-bootstrap-owned",
                "AD_Session,AD_Preference",
                "Login and role selection own only the reviewed session/preferences delta.",
            )
        return (
            "read-only-after-session-bootstrap",
            "AD_Session",
            "Route reads application state; only session bootstrap may write.",
        )
    if context == "/wstore":
        mutating = {
            "BasketServlet",
            "CheckOutServlet",
            "EMailServlet",
            "ExpenseServlet",
            "InfoServlet",
            "IssueReportServlet",
            "LocationServlet",
            "LoginServlet",
            "NoteServlet",
            "OrderServlet",
            "PaymentServlet",
            "RegistrationServlet",
            "RequestServlet",
            "RfQServlet",
            "UpdateServlet",
            "WorkflowServlet",
        }
        if name in mutating:
            return (
                "method-specific-fixture-required",
                "AD_Session,web-basket,business-table-owned-by-route",
                "GET probe permits no new business write; form methods require a named fixture delta.",
            )
        if name in {"AssetServlet", "InvoiceServlet"}:
            return (
                "download-read-only",
                "web-asset,invoice",
                "Download response may be large but owns no write.",
            )
        return (
            "read-only-plus-session-basket",
            "AD_Session,web-basket",
            "Read route may initialize the context-local session basket only.",
        )
    raise SystemExit(f"unclassified context: {context}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    contracts = root / "contracts/phase5f-jakarta-web-v1"

    deployed = read_tsv(contracts / "deployed-routes.tsv")
    vectors = {
        row["route_id"]: row
        for row in read_tsv(root / "contracts/legacy-web-v1/context-request-vectors.tsv")
    }
    route_metadata = route_contracts(root / "gradle/phase5/route-contracts.tsv")
    deviations = {
        row["deviation_id"]: row
        for row in read_tsv(contracts / "known-deviations.tsv")
    }

    route_rows: list[dict[str, str]] = []
    effect_rows: list[dict[str, str]] = []
    counts = {context: 0 for context in CONTEXT_COUNTS}
    for deployed_row in sorted(deployed, key=lambda row: row["route_id"]):
        route_id = deployed_row["route_id"]
        context = deployed_row["context"]
        if route_id not in vectors or route_id not in route_metadata:
            raise SystemExit(f"route is not in both Phase 5b ledgers: {route_id}")
        counts[context] += 1
        vector = vectors[route_id]
        metadata = route_metadata[route_id]
        deviation_id = deployed_row["deviation_id"]
        modern_status = (
            deviations[deviation_id]["phase5f_rule"]
            if deviation_id != "none"
            else f"preserve-legacy-status:{vector['expected_status']}"
        )
        effect_contract, owned_tables, rationale = effect_for(route_id, context)
        route_rows.append(
            {
                "route_id": route_id,
                "context": context,
                "method": vector["method"],
                "path": vector["path"],
                "legacy_status": vector["expected_status"],
                "legacy_proof_strength": vector["proof_strength"],
                "traffic_class": metadata["traffic_class"],
                "auth_enforcement": metadata["auth_enforcement"],
                "phase5f_rule": deployed_row["phase5f_rule"],
                "modern_status_contract": modern_status,
                "policy_id": deployed_row["policy_id"],
                "deviation_id": deviation_id,
                "enable_state_id": deployed_row["enable_state_id"],
                "database_effect_contract": effect_contract,
                "runtime_observation": "pending-phase5f-database-smoke",
            }
        )
        effect_rows.append(
            {
                "route_id": route_id,
                "context": context,
                "probe_method": vector["method"],
                "effect_contract": effect_contract,
                "owned_tables_or_group": owned_tables,
                "unowned_write_rule": "fail",
                "fixture_reset_rule": "marker-owned-reset-before-each-shard",
                "evidence_state": "contract-only-runtime-observation-pending",
                "rationale": rationale,
            }
        )

    if counts != CONTEXT_COUNTS:
        raise SystemExit(f"deployed context counts changed: {counts}")

    write_tsv(
        args.output_dir / "route-validation.tsv",
        list(route_rows[0]),
        route_rows,
    )
    write_tsv(
        args.output_dir / "database-effect-ownership.tsv",
        list(effect_rows[0]),
        effect_rows,
    )
    print(
        "generated Phase 5f oracle ledgers: "
        f"{len(route_rows)} routes, {len(effect_rows)} database-effect rows"
    )


if __name__ == "__main__":
    main()
