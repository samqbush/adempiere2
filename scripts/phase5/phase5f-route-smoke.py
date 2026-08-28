#!/usr/bin/env python3
"""Replay one Phase 5f context exclusively through the public Tomcat 9 origin."""

from __future__ import annotations

import argparse
import csv
import hashlib
import http.cookiejar
import json
import os
import ssl
import subprocess
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path


ELIGIBLE = {"/", "/wstore"}
CONFIDENTIAL_WSTORE = {
    "/wstore/login.jsp", "/wstore/loginServlet",
    "/wstore/checkOutServlet", "/wstore/orderServlet",
}
CONTRACT_OWNERS = {
    "/webui": ("P5F-HDR-WEBUI", "JSESSIONID:/webui", "secure-when-public-https", "8388608/67108864", "phase5e-sticky-session"),
    "/admin": ("P5F-HDR-ADMIN", "JSESSIONID:/admin", "public-origin-only", "1048576/67108864", "context-sticky-session"),
    "/": ("P5F-HDR-ROOT", "JSESSIONID:/", "public-origin-only", "1048576/16777216", "context-sticky-session"),
    "/mobile": ("P5F-HDR-MOBILE", "JSESSIONID+adempiereInfo:/mobile", "public-origin-only", "8388608/67108864", "context-sticky-session"),
    "/adempiere": ("P5F-HDR-ADEMPIERE", "JSESSIONID+AdempiereWebUser:/adempiere", "public-origin-only", "67108864/268435456", "context-sticky-session"),
    "/wstore": ("P5F-HDR-WSTORE", "JSESSIONID+AdempiereWebUser:/wstore", "four-confidential-routes", "33554432/268435456", "context-sticky-session"),
}


def run(command: list[str], *, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(
        command, check=True, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, env=env
    )
    return result.stdout


def database_snapshot(args: argparse.Namespace) -> tuple[str, dict[str, str]]:
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ["ADEMPIERE_PHASE5F_DB_PASSWORD"]
    content = run(
        [
            "pg_dump", "--data-only", "--no-owner", "--no-privileges",
            "--column-inserts", "-h", args.db_host, "-p", args.db_port,
            "-U", args.db_user, args.db_name,
        ],
        env=env,
    )
    table_lines: dict[str, list[str]] = defaultdict(list)
    all_lines: list[str] = []
    for line in content.splitlines():
        if line.startswith("--") or not line.strip():
            continue
        all_lines.append(line)
        if line.startswith("INSERT INTO "):
            table = line[len("INSERT INTO "):].split(" ", 1)[0]
            table_lines[table.lower()].append(line)
    return (
        hashlib.sha256("\n".join(all_lines).encode()).hexdigest(),
        {
            table: hashlib.sha256("\n".join(lines).encode()).hexdigest()
            for table, lines in sorted(table_lines.items())
        },
    )


def request(
    opener: urllib.request.OpenerDirector,
    url: str,
    method: str,
    headers: dict[str, str] | None = None,
) -> tuple[int, list[tuple[str, str]], bytes]:
    target = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        response = opener.open(target, timeout=130)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, list(response.headers.items()), response.read()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, url):
        return None


def set_switch(args: argparse.Namespace, action: str) -> None:
    if args.context not in ELIGIBLE:
        return
    run(
        [
            "bash", str(args.switch_script), args.db_host, args.db_port, args.db_name,
            args.db_user, args.db_marker, args.context, action,
            str(args.evidence_dir / "switch-original.tsv"),
        ]
    )


def expected_modern_status(contract: str) -> str:
    if contract.startswith("preserve-legacy-status:"):
        return contract.rsplit(":", 1)[1]
    tail = contract.rsplit("=", 1)[-1]
    if tail.isdigit():
        return tail
    raise SystemExit(f"unresolved modern status contract: {contract}")


def confidential(path: str) -> bool:
    return any(path == owned or path.startswith(owned + "/")
               for owned in CONFIDENTIAL_WSTORE)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--public-origin", required=True)
    parser.add_argument("--public-https-origin", required=True)
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--effects", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--switch-script", type=Path, required=True)
    parser.add_argument("--db-host", required=True)
    parser.add_argument("--db-port", required=True)
    parser.add_argument("--db-name", required=True)
    parser.add_argument("--db-user", required=True)
    parser.add_argument("--db-marker", required=True)
    args = parser.parse_args()

    if "ADEMPIERE_PHASE5F_DB_PASSWORD" not in os.environ:
        raise SystemExit("ADEMPIERE_PHASE5F_DB_PASSWORD is required")
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    routes = [
        row for row in csv.DictReader(
            args.contract.open(encoding="utf-8", newline=""), delimiter="\t"
        )
        if row["context"] == args.context
    ]
    effects = {
        row["route_id"]: row for row in csv.DictReader(
            args.effects.open(encoding="utf-8", newline=""), delimiter="\t"
        )
    }
    if not routes:
        raise SystemExit(f"no routes for {args.context}")

    def fresh_http() -> tuple[
            http.cookiejar.CookieJar, urllib.request.OpenerDirector]:
        jar = http.cookiejar.CookieJar()
        return jar, urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(jar), NoRedirect())

    cookie_jar, opener = fresh_http()
    https_opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(cookie_jar),
        urllib.request.HTTPSHandler(
            context=ssl._create_unverified_context()),
        NoRedirect(),
    )
    header_contract, cookie_contract, tls_contract, body_contract, session_contract = (
        CONTRACT_OWNERS[args.context]
    )
    observations: list[dict[str, str]] = []

    def observe(
        route: dict[str, str],
        mode: str,
        target_opener: urllib.request.OpenerDirector,
        origin: str,
        expected_status: str,
        public_origin_only: bool,
        request_headers: dict[str, str] | None = None,
    ) -> dict[str, str]:
        before, tables_before = database_snapshot(args)
        status, headers, body = request(
            target_opener, origin.rstrip("/") + route["path"], route["method"],
            request_headers,
        )
        after, tables_after = database_snapshot(args)
        changed_tables = sorted(
            table for table in set(tables_before) | set(tables_after)
            if tables_before.get(table) != tables_after.get(table)
        )
        row = {
            "route_id": route["route_id"],
            "context": args.context,
            "mode": mode,
            "method": route["method"],
            "auth_enforcement": route["auth_enforcement"],
            "traffic_class": route["traffic_class"],
            "status": str(status),
            "expected_status": expected_status,
            "headers_sha256": hashlib.sha256(
                json.dumps(headers, sort_keys=True).encode()
            ).hexdigest(),
            "body_sha256": hashlib.sha256(body).hexdigest(),
            "set_cookie": "true" if any(
                name.lower() == "set-cookie" for name, _ in headers
            ) else "false",
            "location": next(
                (value for name, value in headers if name.lower() == "location"),
                "none",
            ),
            "header_contract": header_contract,
            "cookie_contract": cookie_contract,
            "tls_contract": tls_contract,
            "body_contract": body_contract,
            "session_contract": session_contract,
            "database_before": before,
            "database_after": after,
            "database_tables_before": json.dumps(
                tables_before, sort_keys=True, separators=(",", ":")),
            "database_tables_after": json.dumps(
                tables_after, sort_keys=True, separators=(",", ":")),
            "database_changed_tables": ",".join(changed_tables) or "none",
            "database_effect_contract": effects[route["route_id"]][
                "effect_contract"
            ],
            "owned_tables_or_group": effects[route["route_id"]][
                "owned_tables_or_group"
            ],
            "public_origin_only": "true" if public_origin_only else "false",
        }
        if row["status"] != expected_status:
            raise SystemExit(
                f"{route['route_id']} {mode}: status {status} != {expected_status}"
            )
        observations.append(row)
        return row

    set_switch(args, "disable")
    try:
        for route in routes:
            _, legacy_opener = fresh_http()
            observe(
                route, "legacy-public", legacy_opener, args.public_origin,
                route["legacy_status"], True
            )

        reserved_status, _, _ = request(
            opener,
            args.public_origin.rstrip("/") + routes[0]["path"],
            routes[0]["method"],
            {"X-Adempiere-Handoff-Ticket": "browser-forbidden"},
        )
        if reserved_status != 400:
            raise SystemExit(
                f"{args.context}: reserved browser header returned {reserved_status}"
            )

        if args.context in ELIGIBLE:
            cookie_jar.clear()
            set_switch(args, "enable")
            if args.context == "/wstore":
                bootstrap = next(
                    row for row in routes
                    if row["route_id"].startswith("/wstore::Index::")
                )
                first = observe(
                    bootstrap, "modern-session-bootstrap", opener,
                    args.public_origin,
                    expected_modern_status(bootstrap["modern_status_contract"]),
                    True,
                )
                public_sessions = [
                    cookie for cookie in cookie_jar
                    if cookie.name == "JSESSIONID" and cookie.path == "/wstore"
                ]
                if first["set_cookie"] != "true" or len(public_sessions) != 1:
                    raise SystemExit(
                        "/wstore: modern bootstrap did not establish one public session"
                    )
                observe(
                    bootstrap, "modern-session-follow-up", opener,
                    args.public_origin,
                    expected_modern_status(bootstrap["modern_status_contract"]),
                    True,
                )

            cookie_jar.clear()
            for route in routes:
                is_confidential = (
                    args.context == "/wstore" and confidential(route["path"])
                )
                if is_confidential:
                    redirect = observe(
                        route, "modern-public-tls-redirect", opener,
                        args.public_origin, "302", True,
                    )
                    if (
                        not redirect["location"].startswith(
                            args.public_https_origin.rstrip("/") + "/")
                        or "127.0.0.1:8890" in redirect["location"]
                        or "localhost:8890" in redirect["location"]
                    ):
                        raise SystemExit(
                            f"{route['route_id']}: confidential redirect leaked "
                            "or omitted the public HTTPS origin"
                        )
                observe(
                    route,
                    "modern-public-confidential"
                    if is_confidential else "modern-public",
                    https_opener if is_confidential else opener,
                    args.public_https_origin
                    if is_confidential else args.public_origin,
                    expected_modern_status(route["modern_status_contract"]),
                    True,
                )
        else:
            reason = {
                "/admin": "legacy-without-consumer-ownership",
                "/mobile": "disabled-until-phase5g",
                "/adempiere": "disabled-until-phase5g",
                "/webui": "covered-by-phase5e-authenticated-cohort-gate",
            }[args.context]
            (args.evidence_dir / "modern-unexecuted.tsv").write_text(
                "context\troute_count\treason\n"
                f"{args.context}\t{len(routes)}\t{reason}\n",
                encoding="utf-8",
            )
    finally:
        set_switch(args, "clear")

    output = args.evidence_dir / "route-observations.tsv"
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream, fieldnames=list(observations[0]), delimiter="\t",
            lineterminator="\n"
        )
        writer.writeheader()
        writer.writerows(observations)
    provenance = {
        "context": args.context,
        "public_origin": args.public_origin,
        "public_https_origin": args.public_https_origin,
        "git_head": run(["git", "rev-parse", "HEAD"]).strip(),
        "database_marker": args.db_marker,
        "route_count": len(routes),
        "observation_count": len(observations),
        "modern_execution": (
            "all-routes-public-http-or-https"
            if args.context in ELIGIBLE else "explicitly-unexecuted"
        ),
        "client": "python-urllib-public-origin",
        "legacy_cookie_isolation": "fresh-cookie-jar-per-route",
    }
    (args.evidence_dir / "provenance.json").write_text(
        json.dumps(provenance, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    print(f"{args.context}: recorded {len(observations)} public-origin observations")


if __name__ == "__main__":
    main()
