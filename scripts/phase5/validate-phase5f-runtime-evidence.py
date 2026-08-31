#!/usr/bin/env python3
"""Fail closed unless Phase 5f runtime evidence is complete and provenance-bound."""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
from pathlib import Path


EXPECTED = {"/": 8, "/adempiere": 21, "/admin": 4, "/mobile": 14, "/webui": 6, "/wstore": 29}
DIRS = {"/": "ROOT", "/adempiere": "adempiere", "/admin": "admin", "/mobile": "mobile", "/webui": "webui", "/wstore": "wstore"}
ELIGIBLE = {"/", "/wstore"}
CONFIDENTIAL_WSTORE = {
    "/wstore/login.jsp", "/wstore/loginServlet",
    "/wstore/checkOutServlet", "/wstore/orderServlet",
}


def confidential(path: str) -> bool:
    return any(path == owned or path.startswith(owned + "/")
               for owned in CONFIDENTIAL_WSTORE)


def expected_modern_status(value: str) -> str:
    if value.startswith("preserve-legacy-status:"):
        return value.rsplit(":", 1)[1]
    if value.startswith("public-http="):
        parts = dict(
            part.split("=", 1) for part in value.split(";") if "=" in part
        )
        if set(parts) != {"public-http", "public-https"} or not all(
                item.isdigit() for item in parts.values()):
            raise SystemExit(f"unresolved modern status contract: {value}")
        return parts["public-https"]
    tail = value.rsplit("=", 1)[-1]
    if tail.isdigit():
        return tail
    raise SystemExit(f"unresolved modern status contract: {value}")


def normalized_table(value: str) -> str:
    return value.replace('"', "").split(".")[-1].lower()


def permitted_write_tables(effect: dict[str, str]) -> set[str]:
    contract = effect["effect_contract"]
    owned = {
        value.strip().lower()
        for value in effect["owned_tables_or_group"].split(",")
        if value.strip() and value.strip().lower() != "none"
    }
    direct = {
        value for value in owned
        if value.startswith(("ad_", "cm_", "w_", "c_", "a_"))
    }
    groups = {
        "web-access-log": {"cm_webaccesslog"},
        "web-basket": {"w_basket", "w_basketline"},
    }
    if contract in {
        "no-write", "no-new-write", "read-only", "download-read-only",
    }:
        return set()
    if contract == "content-read-plus-owned-access-metadata":
        return groups["web-access-log"] if "web-access-log" in owned else set()
    if contract == "read-only-after-session-bootstrap":
        return {"ad_session"} if "ad_session" in direct else set()
    if contract == "session-bootstrap-owned":
        return direct & {"ad_session", "ad_preference"}
    if contract == "read-only-plus-session-basket":
        return (direct & {"ad_session"}) | (
            groups["web-basket"] if "web-basket" in owned else set())
    if contract == "method-specific-fixture-required":
        return direct & {"ad_session", "ad_preference"}
    if contract == "existing-phase5d-session-contract":
        return direct & {
            "ad_session", "ad_preference", "ad_recentitem", "ad_changelog"
        }
    raise SystemExit(f"unknown database effect contract: {contract}")


def validate_observation(
    row: dict[str, str],
    contract_row: dict[str, str],
    effect_row: dict[str, str],
    expected: str,
    *,
    public_required: bool,
) -> None:
    route_id = row["route_id"]
    if public_required and row["public_origin_only"] != "true":
        raise SystemExit(f"{route_id}: non-public-origin evidence")
    for owned in (
        "auth_enforcement", "traffic_class", "header_contract",
        "cookie_contract", "tls_contract", "body_contract",
        "session_contract",
    ):
        if not row.get(owned):
            raise SystemExit(f"{route_id}: missing {owned} ownership")
    if not row["status"].isdigit() or len(row["body_sha256"]) != 64:
        raise SystemExit(f"{route_id}: synthetic or incomplete observation")
    if row["expected_status"] != expected or row["status"] != expected:
        raise SystemExit(
            f"{route_id}: status {row['status']} / expected field "
            f"{row['expected_status']} != contract {expected}"
        )
    validate_database_effect(row, effect_row)
    if row["method"] != contract_row["method"]:
        raise SystemExit(f"{route_id}: observed method drift")


def validate_database_effect(
    row: dict[str, str], effect_row: dict[str, str],
) -> None:
    route_id = row["route_id"]
    if row["database_effect_contract"] != effect_row["effect_contract"]:
        raise SystemExit(f"{route_id}: database effect contract drift")
    if row["owned_tables_or_group"] != effect_row["owned_tables_or_group"]:
        raise SystemExit(f"{route_id}: database ownership group drift")
    try:
        before = json.loads(row["database_tables_before"])
        after = json.loads(row["database_tables_after"])
    except (KeyError, json.JSONDecodeError) as invalid:
        raise SystemExit(f"{route_id}: invalid table-grain database snapshot") from invalid
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise SystemExit(f"{route_id}: table-grain database snapshot is not a map")
    changed = {
        normalized_table(table)
        for table in set(before) | set(after)
        if before.get(table) != after.get(table)
    }
    recorded = set() if row["database_changed_tables"] == "none" else {
        normalized_table(table)
        for table in row["database_changed_tables"].split(",")
        if table
    }
    if changed != recorded:
        raise SystemExit(f"{route_id}: changed-table evidence does not match snapshots")
    unowned = changed - permitted_write_tables(effect_row)
    if unowned:
        raise SystemExit(
            f"{route_id}: unowned database writes: {sorted(unowned)}")
    if (row["database_before"] == row["database_after"]) != (not changed):
        raise SystemExit(f"{route_id}: database aggregate and table snapshots disagree")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--effects", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    args = parser.parse_args()
    contract = {
        row["route_id"]: row for row in csv.DictReader(
            args.contract.open(encoding="utf-8", newline=""), delimiter="\t"
        )
    }
    effects = {
        row["route_id"]: row for row in csv.DictReader(
            args.effects.open(encoding="utf-8", newline=""), delimiter="\t"
        )
    }
    if set(effects) != set(contract):
        raise SystemExit("database-effect ownership does not cover the route contract")
    for route_id, route in contract.items():
        effect = effects[route_id]
        if (
            route["context"] != effect["context"]
            or route["method"] != effect["probe_method"]
            or route["database_effect_contract"] != effect["effect_contract"]
            or effect["unowned_write_rule"] != "fail"
        ):
            raise SystemExit(
                f"{route_id}: route/database-effect contract join drift")
    # Read once, from the tree this validation is running against, so that a
    # shard's recorded commit is compared with a real value rather than merely
    # asserted to be present. Stale evidence left by an earlier attempt is
    # otherwise indistinguishable from evidence this run produced.
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"], check=True, capture_output=True,
        text=True,
    ).stdout.strip()

    seen: set[str] = set()
    total = 0
    for context, count in EXPECTED.items():
        directory = args.evidence_root / DIRS[context]
        provenance = json.loads((directory / "provenance.json").read_text())
        if provenance != {**provenance, "context": context, "route_count": count, "client": "python-urllib-public-origin"}:
            raise SystemExit(f"{context}: invalid evidence provenance")
        if provenance.get("git_head") != head:
            raise SystemExit(
                f"{context}: evidence was produced at commit "
                f"{provenance.get('git_head')!r}, not the checked-out {head!r}")
        if provenance.get("failure_count") != 0:
            raise SystemExit(
                f"{context}: shard recorded "
                f"{provenance.get('failure_count')} route failure(s); see "
                f"{directory / 'route-failures.tsv'}")
        failures = directory / "route-failures.tsv"
        if failures.exists() and failures.read_text(encoding="utf-8").strip():
            raise SystemExit(
                f"{context}: route failure ledger is not empty: {failures}")
        if provenance.get("legacy_cookie_isolation") != "fresh-cookie-jar-per-route":
            raise SystemExit(f"{context}: independent legacy vectors reused cookies")
        rows = list(csv.DictReader(
            (directory / "route-observations.tsv").open(encoding="utf-8", newline=""),
            delimiter="\t",
        ))
        for row in rows:
            if row["route_id"] not in effects:
                raise SystemExit(
                    f"unknown route in database evidence: {row['route_id']}")
            validate_database_effect(row, effects[row["route_id"]])
        legacy = [row for row in rows if row["mode"] == "legacy-public"]
        legacy_https_rows = [
            row for row in rows
            if row["mode"] == "legacy-public-confidential"
        ]
        legacy_https = {row["route_id"]: row for row in legacy_https_rows}
        modern = [
            row for row in rows
            if row["mode"] in {"modern-public", "modern-public-confidential"}
        ]
        if len(legacy) != count:
            raise SystemExit(f"{context}: expected {count} route observations, got {len(legacy)}")
        for row in legacy:
            route_id = row["route_id"]
            if route_id in seen or route_id not in contract:
                raise SystemExit(f"duplicate or unknown route observation: {route_id}")
            seen.add(route_id)
            validate_observation(
                row, contract[route_id], effects[route_id],
                contract[route_id]["legacy_status"],
                public_required=True,
            )
        if context in ELIGIBLE:
            if provenance.get("modern_cookie_isolation") != (
                    "fresh-cookie-jar-per-route"):
                raise SystemExit(
                    f"{context}: independent modern vectors reused cookies")
            if provenance.get("modern_execution") != (
                "all-routes-public-http-or-https"
            ):
                raise SystemExit(f"{context}: modern execution provenance is absent")
            if not provenance.get("public_https_origin", "").startswith(
                    "https://"):
                raise SystemExit(f"{context}: public HTTPS provenance is absent")
            modern_ids = [row["route_id"] for row in modern]
            expected_ids = {
                route_id for route_id, route in contract.items()
                if route["context"] == context
            }
            if len(modern) != count or set(modern_ids) != expected_ids:
                raise SystemExit(
                    f"{context}: modern route coverage is incomplete or duplicated"
                )
            for row in modern:
                route = contract[row["route_id"]]
                is_confidential = (
                    context == "/wstore" and confidential(route["path"])
                )
                expected_mode = (
                    "modern-public-confidential"
                    if is_confidential
                    else "modern-public"
                )
                if row["mode"] != expected_mode:
                    raise SystemExit(
                        f"{row['route_id']}: mode {row['mode']} != {expected_mode}"
                    )
                if is_confidential:
                    # The frozen oracle captured these routes over public HTTP
                    # only, so the contract status governs the transport
                    # redirect. The protected resource is held to the legacy
                    # HTTPS response observed in this same run.
                    baseline = legacy_https.get(row["route_id"])
                    if baseline is None:
                        raise SystemExit(
                            f"{row['route_id']}: no legacy HTTPS baseline "
                            "was recorded for a CONFIDENTIAL route"
                        )
                    expected = baseline["status"]
                else:
                    expected = expected_modern_status(
                        route["modern_status_contract"])
                validate_observation(
                    row, route, effects[row["route_id"]],
                    expected,
                    public_required=True,
                )
            if context == "/wstore":
                expected_https_baselines = {
                    route_id for route_id, route in contract.items()
                    if route["context"] == "/wstore"
                    and confidential(route["path"])
                }
                # Keyed by route, so a duplicate would silently shadow the
                # genuine row and hand modern parity to whichever was written
                # last. Compare the row count, not just the key set.
                if (set(legacy_https) != expected_https_baselines
                        or len(legacy_https_rows) != len(
                            expected_https_baselines)):
                    raise SystemExit(
                        "/wstore: legacy HTTPS baseline coverage for the "
                        "CONFIDENTIAL routes is incomplete or duplicated"
                    )
                for route_id, row in legacy_https.items():
                    # A baseline that was itself scored against something is
                    # not a baseline, and one taken off the public origin
                    # would not be legacy public behaviour.
                    if (row["expected_status"] != "record-only"
                            or row["public_origin_only"] != "true"
                            or not row["status"].isdigit()
                            or len(row["body_sha256"]) != 64):
                        raise SystemExit(
                            f"{route_id}: legacy HTTPS baseline is not a "
                            "public record-only observation"
                        )
                    # Both legs cross the same ingress, so an ingress failure
                    # is identical on both and parity alone cannot see it.
                    if not 200 <= int(row["status"]) < 400:
                        raise SystemExit(
                            f"{route_id}: legacy HTTPS baseline status "
                            f"{row['status']} is not a served response, so it "
                            "cannot be the modern parity baseline"
                        )
                    validate_database_effect(row, effects[route_id])
                redirects = [
                    row for row in rows
                    if row["mode"] == "modern-public-tls-redirect"
                ]
                expected_confidential = {
                    route_id for route_id, route in contract.items()
                    if route["context"] == "/wstore"
                    and confidential(route["path"])
                }
                if (
                    {row["route_id"] for row in redirects}
                    != expected_confidential
                    or any(
                        row["status"] != "302"
                        or not row["location"].startswith("https://")
                        or "127.0.0.1:8890" in row["location"]
                        or "localhost:8890" in row["location"]
                        for row in redirects
                    )
                ):
                    raise SystemExit(
                        "/wstore: public CONFIDENTIAL redirect evidence is incomplete"
                    )
                lifecycle = [
                    row for row in rows if row["mode"] in {
                        "modern-session-bootstrap", "modern-session-follow-up"
                    }
                ]
                if [row["mode"] for row in lifecycle] != [
                    "modern-session-bootstrap", "modern-session-follow-up"
                ]:
                    raise SystemExit(
                        "/wstore: stateful modern bootstrap/follow-up is absent"
                    )
                if lifecycle[0]["set_cookie"] != "true":
                    raise SystemExit(
                        "/wstore: stateful modern bootstrap created no public cookie"
                    )
        else:
            if modern:
                raise SystemExit(
                    f"{context}: disabled/deferred context was counted as modern"
                )
            unexecuted = directory / "modern-unexecuted.tsv"
            if not unexecuted.is_file():
                raise SystemExit(
                    f"{context}: modern routes are neither executed nor reported"
                )
            unexecuted_rows = list(csv.DictReader(
                unexecuted.open(encoding="utf-8", newline=""), delimiter="\t"
            ))
            if (
                len(unexecuted_rows) != 1
                or unexecuted_rows[0]["context"] != context
                or unexecuted_rows[0]["route_count"] != str(count)
                or not unexecuted_rows[0]["reason"]
                or provenance.get("modern_execution") != "explicitly-unexecuted"
            ):
                raise SystemExit(f"{context}: invalid unexecuted-modern evidence")
        total += len(legacy)
    if seen != set(contract) or total != 82:
        raise SystemExit("runtime evidence does not exactly cover the 82-route contract")
    soap = args.evidence_root / "phase4-soap-coexistence.tsv"
    if soap.read_text(encoding="utf-8").strip() != "phase4_soap_corpus\tpass\twhile-phase5f-route-shards-active":
        raise SystemExit("Phase 4 SOAP coexistence evidence is absent")
    secret_scan = args.evidence_root / "secret-hygiene.tsv"
    if secret_scan.read_text(encoding="utf-8").strip() != "secret_hygiene\tpass":
        raise SystemExit("secret-hygiene evidence is absent")
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(
        "metric\tvalue\n"
        "legacy_route_observations\t82\n"
        "eligible_modern_route_observations\t37\n"
        "explicitly_unexecuted_modern_routes\t45\n"
        "stateful_modern_session\tbootstrap-and-follow-up\n"
        "contexts\t6\nsoap_coexistence\tpass\nsecret_hygiene\tpass\n",
        encoding="utf-8",
    )
    print(
        "validated 82 legacy routes, all 37 eligible modern routes, "
        "and 45 explicitly unexecuted modern routes"
    )


if __name__ == "__main__":
    main()
