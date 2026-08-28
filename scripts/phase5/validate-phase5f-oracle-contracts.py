#!/usr/bin/env python3
"""Fail closed when the reviewed Phase 5f oracle contract is incomplete."""

from __future__ import annotations

import argparse
import csv
import hashlib
from pathlib import Path


EXPECTED_CONTEXT_COUNTS = {
    "/": 8,
    "/adempiere": 21,
    "/admin": 4,
    "/mobile": 14,
    "/webui": 6,
    "/wstore": 29,
}
NORMATIVE_FILES = [
    "README.md",
    "context-policy-schema.tsv",
    "cookie-policy.tsv",
    "database-effect-ownership.tsv",
    "deployed-routes.tsv",
    "enable-state-residuals.tsv",
    "hazard-register.tsv",
    "header-policy.tsv",
    "jsp-precompile-contract.tsv",
    "known-deviations.tsv",
    "mutation-cases.tsv",
    "non-deployed-dispositions.tsv",
    "route-validation.tsv",
    "session-routing-cases.tsv",
    "tls-routes.tsv",
]


def rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        result = list(csv.DictReader(stream, delimiter="\t"))
    if not result or not result[0]:
        raise ValueError(f"empty contract: {path.name}")
    for number, row in enumerate(result, 2):
        if None in row or any(value == "" for value in row.values()):
            raise ValueError(f"malformed or empty field: {path.name}:{number}")
        if any(value != value.strip() for value in row.values()):
            raise ValueError(f"untrimmed field: {path.name}:{number}")
    return result


def reviewed_rows(path: Path) -> list[dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    content = [
        (line[2:] if line.startswith("# ") else line)
        for line in lines
        if line.strip() and not (line.startswith("#") and not line.startswith("# "))
    ]
    reader = csv.DictReader(content, delimiter="\t")
    return list(reader)


def unique(data: list[dict[str, str]], key: str, label: str) -> set[str]:
    values = [row[key] for row in data]
    if len(values) != len(set(values)):
        raise ValueError(f"duplicate {label}")
    return set(values)


def manifest_bytes(contract_dir: Path) -> bytes:
    lines = []
    for name in NORMATIVE_FILES:
        digest = hashlib.sha256((contract_dir / name).read_bytes()).hexdigest()
        lines.append(f"{digest}  {name}\n")
    return "".join(lines).encode()


def validate(
    repo_root: Path, contract_dir: Path, generated_dir: Path
) -> dict[str, int]:
    deployed = rows(contract_dir / "deployed-routes.tsv")
    route_ids = unique(deployed, "route_id", "deployed route_id")
    if len(route_ids) != 82:
        raise ValueError(f"expected 82 deployed routes, got {len(route_ids)}")
    counts = {
        context: sum(row["context"] == context for row in deployed)
        for context in EXPECTED_CONTEXT_COUNTS
    }
    if counts != EXPECTED_CONTEXT_COUNTS:
        raise ValueError(f"deployed context counts changed: {counts}")

    non_deployed = rows(contract_dir / "non-deployed-dispositions.tsv")
    unique(non_deployed, "source_mapping_id", "non-deployed source_mapping_id")
    dispositions = {
        disposition: sum(row["disposition"] == disposition for row in non_deployed)
        for disposition in {
            "drop-jboss-http-invoker",
            "drop-superseded-descriptor-duplicate",
            "defer-phase5g-jasper-web",
        }
    }
    expected_dispositions = {
        "drop-jboss-http-invoker": 8,
        "drop-superseded-descriptor-duplicate": 20,
        "defer-phase5g-jasper-web": 2,
    }
    if len(non_deployed) != 30 or dispositions != expected_dispositions:
        raise ValueError(
            f"expected exact 30 non-deployed dispositions, got {dispositions}"
        )

    policies = rows(contract_dir / "context-policy-schema.tsv")
    policy_ids = unique(policies, "policy_id", "policy_id")
    if {row["context"] for row in policies} != set(EXPECTED_CONTEXT_COUNTS):
        raise ValueError("context policy set is not closed")
    if any("required-before-code" in value for row in policies for value in row.values()):
        raise ValueError("unresolved required-before-code marker")
    for row in policies:
        for field in (
            "request_limit_bytes",
            "response_limit_bytes",
            "connect_timeout_ms",
            "read_timeout_ms",
        ):
            if not row[field].isdigit() or int(row[field]) <= 0:
                raise ValueError(f"invalid numeric policy {field}: {row['policy_id']}")
        if row["modern_failure_rule"] != "no-legacy-fallback":
            raise ValueError(f"fallback policy weakened: {row['policy_id']}")

    deviations = rows(contract_dir / "known-deviations.tsv")
    deviation_ids = unique(deviations, "deviation_id", "deviation_id")
    if len(deviation_ids) != 5:
        raise ValueError(f"expected five reviewed deviations, got {len(deviation_ids)}")
    linked_deviations = {row["deviation_id"] for row in deployed} - {"none"}
    if linked_deviations != deviation_ids:
        raise ValueError("deployed route deviation links changed")

    route_validation = rows(contract_dir / "route-validation.tsv")
    if unique(route_validation, "route_id", "route-validation route_id") != route_ids:
        raise ValueError("route-validation inventory does not exactly match 82 routes")
    if any(row["policy_id"] not in policy_ids for row in route_validation):
        raise ValueError("route-validation references an unknown policy")
    if any(
        row["runtime_observation"] != "pending-phase5f-database-smoke"
        for row in route_validation
    ):
        raise ValueError("unexecuted runtime observation was claimed")

    effects = rows(contract_dir / "database-effect-ownership.tsv")
    if unique(effects, "route_id", "database-effect route_id") != route_ids:
        raise ValueError("database-effect ownership does not exactly match 82 routes")
    if any(row["unowned_write_rule"] != "fail" for row in effects):
        raise ValueError("an unowned database write is not fail-closed")
    if any(
        row["evidence_state"] != "contract-only-runtime-observation-pending"
        for row in effects
    ):
        raise ValueError("unexecuted database observation was claimed")

    headers = rows(contract_dir / "header-policy.tsv")
    for context in EXPECTED_CONTEXT_COUNTS:
        if not any(row["context"] == context for row in headers):
            raise ValueError(f"missing context-specific header policy: {context}")
    required_header_rules = {
        ("*", "request", "x-forwarded-proto", "drop"),
        ("*", "request", "x-adempiere-handoff-*", "drop-reserved"),
        ("*", "response", "set-cookie", "parse-policy-only"),
        ("/admin", "request", "authorization", "forward"),
        ("/admin", "response", "www-authenticate", "forward"),
        ("/wstore", "request", "referer", "forward"),
    }
    actual_header_rules = {
        (row["context"], row["direction"], row["header"], row["action"])
        for row in headers
    }
    if not required_header_rules <= actual_header_rules:
        raise ValueError("unsafe header policy or missing context exception")

    cookies = rows(contract_dir / "cookie-policy.tsv")
    required_cookies = {
        ("/mobile", "adempiereInfo"),
        ("/adempiere", "AdempiereWebUser"),
        ("/wstore", "AdempiereWebUser"),
        ("*", "*"),
    }
    if not required_cookies <= {(row["context"], row["cookie"]) for row in cookies}:
        raise ValueError("application-cookie allowlist is incomplete")
    if sum(row["cookie"] == "JSESSIONID" for row in cookies) != 6:
        raise ValueError("expected one context-isolated JSESSIONID rule per context")

    tls = rows(contract_dir / "tls-routes.tsv")
    confidential = [
        row for row in tls if row["public_http_rule"] == "redirect-public-https"
    ]
    if {(row["context"], row["path"]) for row in confidential} != {
        ("/wstore", "/login.jsp"),
        ("/wstore", "/loginServlet"),
        ("/wstore", "/checkOutServlet"),
        ("/wstore", "/orderServlet"),
    }:
        raise ValueError("the four CONFIDENTIAL routes changed")
    if any("loopback" not in row["forwarded_internal_rule"] for row in confidential):
        raise ValueError("TLS forwarding is not bounded to loopback")

    sessions = rows(contract_dir / "session-routing-cases.tsv")
    required_session_cases = {
        "SESSIONLESS-LEGACY",
        "SESSIONLESS-MODERN",
        "PRESWITCH-LEGACY",
        "PRESWITCH-MODERN",
        "MODERN-AFFINITY-MISSING",
        "MODERN-BACKEND-FAILURE",
        "LIVE-ROLLBACK",
        "CROSS-CONTEXT",
    }
    if not required_session_cases <= unique(sessions, "case_id", "session case_id"):
        raise ValueError("sessionless, pre-switch, failure, or isolation case missing")

    jsp_contract = rows(contract_dir / "jsp-precompile-contract.tsv")
    expected_jsps = {
        row["path"]
        for row in reviewed_rows(
            repo_root / "gradle/phase5/web-assets.tsv"
        )
        if row["type"] == "jsp"
        and row["owner"] == "webStore"
        and row["closing_gate"] == "5f"
    }
    if unique(jsp_contract, "path", "JSP precompile path") != expected_jsps:
        raise ValueError("JSP precompile contract does not match the 25 retained JSPs")
    if len(jsp_contract) != 25:
        raise ValueError(f"expected 25 retained JSPs, got {len(jsp_contract)}")
    if any(
        row["evidence_state"] != "contract-only-runtime-observation-pending"
        for row in jsp_contract
    ):
        raise ValueError("unexecuted JSP precompile observation was claimed")

    mutations = rows(contract_dir / "mutation-cases.tsv")
    required_mutation_types = {
        "route-omission",
        "wrong-auth-class",
        "unsafe-request-header",
        "unsafe-response-cookie",
        "internal-redirect-leak",
        "fallback-after-modern-failure",
        "wrong-byte-cap",
        "missing-jsp-compile",
        "stale-deployed-route",
        "wrong-drop-defer-disposition",
    }
    if required_mutation_types != {row["mutation_type"] for row in mutations}:
        raise ValueError("mutation case set is not the reviewed closed set")

    for generated_name in ("route-validation.tsv", "database-effect-ownership.tsv"):
        if (contract_dir / generated_name).read_bytes() != (
            generated_dir / generated_name
        ).read_bytes():
            raise ValueError(f"generated oracle ledger drift: {generated_name}")

    expected_manifest = manifest_bytes(contract_dir)
    if (contract_dir / "manifest.sha256").read_bytes() != expected_manifest:
        raise ValueError("Phase 5f manifest drift")

    return {
        "deployed_routes": len(deployed),
        "non_deployed_dispositions": len(non_deployed),
        "context_policies": len(policies),
        "database_effect_rows": len(effects),
        "mutation_cases": len(mutations),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--contract-dir", type=Path, required=True)
    parser.add_argument("--generated-dir", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    args = parser.parse_args()
    try:
        summary = validate(args.repo_root.resolve(), args.contract_dir, args.generated_dir)
    except (KeyError, OSError, ValueError) as error:
        raise SystemExit(f"Phase 5f contract validation failed: {error}") from error
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    with args.summary.open("w", encoding="utf-8", newline="") as stream:
        stream.write("metric\tvalue\n")
        for key in sorted(summary):
            stream.write(f"{key}\t{summary[key]}\n")
        stream.write("runtime_observations\tnot-executed\n")
    print(
        "validated Phase 5f database-neutral contract: "
        "82 deployed routes, 30 non-deployed dispositions, runtime pending"
    )


if __name__ == "__main__":
    main()
