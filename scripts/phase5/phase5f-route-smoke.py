#!/usr/bin/env python3
"""Replay one Phase 5f context exclusively through the public Tomcat 9 origin."""

from __future__ import annotations

import argparse
import csv
import hashlib
import http.cookiejar
import json
import os
import re
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path


ELIGIBLE = {"/", "/wstore"}
# Marks an observation taken where no frozen legacy baseline exists, so the
# observation is the baseline rather than something scored against one.
RECORD_ONLY = "record-only"
# Seconds to let a response-committed-before-write servlet finish committing
# before the post-request snapshot is taken. See the citation at its use.
WRITE_SETTLE_SECONDS = 0.5
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
    try:
        result = subprocess.run(
            command, check=True, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, env=env
        )
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, end="", file=sys.stderr)
        raise
    return result.stdout


def database_snapshot(args: argparse.Namespace) -> tuple[str, dict[str, str]]:
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ["ADEMPIERE_PHASE5F_DB_PASSWORD"]
    try:
        content = run(
            [
                "pg_dump", "--data-only", "--no-owner", "--no-privileges",
                "--column-inserts", "-h", args.db_host, "-p", args.db_port,
                "-U", args.db_user, args.db_name,
            ],
            env=env,
        )
    except (subprocess.CalledProcessError, OSError) as unreadable:
        # Losing the database invalidates every effect observation that would
        # follow, so this can never be downgraded to a route failure.
        raise InfrastructureFailure(
            f"database snapshot failed: {unreadable}"
        ) from unreadable
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


# A failure detail reaches public CI logs and the published evidence artifact,
# so it stays inside the Phase 5e secret-hygiene perimeter: session cookies,
# credentials and handoff tickets are redacted by name, and any session id
# embedded in a body preview is scrubbed.
REDACTED_HEADERS = ("set-cookie", "cookie", "authorization")


def redact_headers(headers: list[tuple[str, str]]) -> str:
    return "\n".join(
        f"  {name}: "
        + ("<redacted>"
           if name.lower() in REDACTED_HEADERS
           or name.lower().startswith("x-adempiere-handoff")
           else value)
        for name, value in headers
    )


def redact_body(body: bytes, limit: int = 2048) -> str:
    return re.sub(
        r"(?i)(jsessionid=)[^\s\"'&;<]+", r"\1<redacted>",
        body[:limit].decode("utf-8", "replace"))


# Emitted by ContextRoutingFilter and CohortRoutingFilter when a proxied
# exchange fails closed. A fail-closed proxy answers 502 with no indication of
# why, so these lines are the only attribution a route failure has.
PROXY_FAILURE_PREFIX = "PHASE5F-PROXY-FAIL"


def harvest_proxy_failures(args: argparse.Namespace) -> None:
    """Copies routing-proxy failure lines into this shard's evidence.

    Without this the explanation exists only in a Tomcat log inside a
    30k-line CI job transcript, which is not reviewable and is not part of the
    published evidence artifact.
    """
    if args.container_log is None or not args.container_log.exists():
        return
    try:
        lines = [
            line for line in args.container_log.read_text(
                encoding="utf-8", errors="replace").splitlines()
            if PROXY_FAILURE_PREFIX in line
        ]
    except OSError:
        return
    if lines:
        (args.evidence_dir / "proxy-failures.log").write_text(
            "\n".join(lines) + "\n", encoding="utf-8")


class InfrastructureFailure(Exception):
    """A lane, database or switch failure that invalidates the whole shard.

    Distinguished from a route assertion: continuing after one of these would
    produce observations that no longer describe the system under test.
    """


class VectorFailure(Exception):
    """One route assertion that failed.

    Recorded with full attribution and detail, then survived, so that a single
    expensive CI run reports every actionable route failure in the shard
    instead of only the first.
    """

    def __init__(self, route_id: str, mode: str, kind: str, detail: str):
        super().__init__(f"{route_id} {mode}: {kind}")
        self.route_id = route_id
        self.mode = mode
        self.kind = kind
        self.detail = detail


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
    try:
        run(
            [
                "bash", str(args.switch_script), args.db_host, args.db_port,
                args.db_name, args.db_user, args.db_marker, args.context,
                action,
                str(args.evidence_dir / "switch-original.tsv"),
            ]
        )
    except (subprocess.CalledProcessError, OSError) as failed:
        # AD_SysConfig is shared across shards. A half-applied switch would
        # silently mis-attribute every later shard in a --continue run.
        raise InfrastructureFailure(
            f"context switch '{action}' failed for {args.context}: {failed}"
        ) from failed


def expected_modern_status(contract: str) -> str:
    if contract.startswith("preserve-legacy-status:"):
        return contract.rsplit(":", 1)[1]
    if contract.startswith("public-http="):
        return split_transport_status(contract)
    tail = contract.rsplit("=", 1)[-1]
    if tail.isdigit():
        return tail
    raise SystemExit(f"unresolved modern status contract: {contract}")


def split_transport_status(contract: str) -> str:
    parts = dict(
        part.split("=", 1) for part in contract.split(";") if "=" in part
    )
    if set(parts) != {"public-http", "public-https"} or not all(
            value.isdigit() for value in parts.values()):
        raise SystemExit(f"unresolved modern status contract: {contract}")
    return parts["public-https"]


def served_confidential(status: str) -> bool:
    """True when a CONFIDENTIAL route actually served or redirected.

    A 4xx or 5xx here is a broken lane, not legacy behaviour, and must never
    become the baseline the modern runtime is compared against.
    """
    return status.isdigit() and 200 <= int(status) < 400


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
    parser.add_argument(
        "--container-log", type=Path, default=None,
        help="Tomcat 9 catalina.out to harvest routing-proxy failures from")
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

    def https_for(
            jar: http.cookiejar.CookieJar) -> urllib.request.OpenerDirector:
        return urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(jar),
            urllib.request.HTTPSHandler(
                context=ssl._create_unverified_context()),
            NoRedirect(),
        )
    header_contract, cookie_contract, tls_contract, body_contract, session_contract = (
        CONTRACT_OWNERS[args.context]
    )
    observations: list[dict[str, str]] = []
    failures: list[dict[str, str]] = []
    legacy_https_status: dict[str, str] = {}

    def publish() -> None:
        """Republishes both ledgers after every vector.

        A shard that aborts must still upload what it observed. Writing only at
        shard completion is exactly the defect this replaces: run 33327217266
        lost every passing ROOT observation because the shard died on its first
        modern vector. Each file is written to a sibling temporary path and
        atomically renamed, so a reader never sees a half-written ledger.
        """
        for name, rows in (
            ("route-observations.tsv", observations),
            ("route-failures.tsv", failures),
        ):
            target = args.evidence_dir / name
            if not rows:
                continue
            scratch = target.with_suffix(target.suffix + ".partial")
            with scratch.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(
                    stream, fieldnames=list(rows[0]), delimiter="\t",
                    lineterminator="\n"
                )
                writer.writeheader()
                writer.writerows(rows)
            scratch.replace(target)

    def record_failure(failure: VectorFailure) -> None:
        failures.append(
            {
                "route_id": failure.route_id,
                "context": args.context,
                "mode": failure.mode,
                "kind": failure.kind,
                "detail": failure.detail.replace("\t", " ").replace(
                    "\n", "\\n"),
            }
        )
        print(f"FAIL {failure.route_id} {failure.mode}: {failure.kind}",
              file=sys.stderr)
        print(failure.detail, file=sys.stderr)
        publish()

    def observe(
        route: dict[str, str],
        mode: str,
        target_opener: urllib.request.OpenerDirector,
        origin: str,
        expected_status: str | None,
        public_origin_only: bool,
        request_headers: dict[str, str] | None = None,
    ) -> dict[str, str]:
        """Observes one vector. A None expected_status records without asserting.

        Record-only is used where no frozen legacy baseline exists, so the
        observation itself becomes the baseline a later vector is scored
        against. It must never be used where a baseline does exist.
        """
        before, tables_before = database_snapshot(args)
        target_url = origin.rstrip("/") + route["path"]
        try:
            status, headers, body = request(
                target_opener, target_url, route["method"], request_headers,
            )
        except OSError as unreachable:
            # A transport error is attributable to this vector, so it is
            # recorded like a status mismatch rather than ending the shard.
            database_snapshot(args)
            raise VectorFailure(
                route["route_id"], mode, "transport-error",
                f"request: {route['method']} {target_url}\n"
                f"error: {type(unreachable).__name__}: {unreachable}",
            ) from unreachable
        # A servlet may commit its response before it finishes writing. Click
        # is the proven case: serverApps/src/main/servlet/org/compiere/wstore/
        # Click.java flushes the redirect at line 115 and only then persists
        # the MClick row at line 119, so the client can be served before the
        # W_Click insert commits. Without a settle the write can land after
        # this vector's `after` snapshot and inside the next vector's window,
        # where it is misattributed to a route that does not own it. A fixed
        # bounded wait is used rather than polling for a stable snapshot,
        # because each snapshot is a full data dump and a second one per
        # observation would roughly double the matrix runtime.
        time.sleep(WRITE_SETTLE_SECONDS)
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
            "expected_status": (
                RECORD_ONLY if expected_status is None else expected_status
            ),
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
        observations.append(row)
        publish()
        if expected_status is not None and row["status"] != expected_status:
            raise VectorFailure(
                route["route_id"], mode,
                f"status {status} != {expected_status}",
                f"request: {route['method']} {target_url}\n"
                f"response headers:\n{redact_headers(headers)}\n"
                f"response body (first 2048 bytes):\n{redact_body(body)}",
            )
        return row

    def attempt(action) -> None:
        """Runs one route assertion, recording a vector failure and going on."""
        try:
            action()
        except VectorFailure as failure:
            record_failure(failure)

    try:
        set_switch(args, "disable")

        for route in routes:
            _, legacy_opener = fresh_http()
            attempt(lambda route=route, opener=legacy_opener: observe(
                route, "legacy-public", opener, args.public_origin,
                route["legacy_status"], True
            ))

        # The frozen Phase 5b oracle captured the CONFIDENTIAL /wstore routes
        # over public HTTP only, so their legacy_status is the container's
        # transport redirect and says nothing about the protected resource
        # behind it. Observe the legacy runtime over public HTTPS as well, so
        # the modern HTTPS response is scored against a legacy response taken
        # in this same run rather than against a literal nobody has observed.
        if args.context == "/wstore":
            def legacy_confidential_vector(route: dict[str, str]) -> None:
                jar, _ = fresh_http()
                row = observe(
                    route, "legacy-public-confidential", https_for(jar),
                    args.public_https_origin, None, True,
                )
                # A record-only observation is about to become the oracle the
                # modern runtime is scored against, so it needs a floor of its
                # own. Both legs cross the same public HTTPS ingress: if that
                # ingress were broken, both would fail identically and parity
                # alone would call a dead lane green.
                if not served_confidential(row["status"]):
                    raise VectorFailure(
                        route["route_id"], "legacy-public-confidential",
                        f"status {row['status']} is not a served response",
                        "a CONFIDENTIAL route must serve or redirect over "
                        "public HTTPS; this status means the ingress or the "
                        "legacy runtime is broken, so it cannot be used as "
                        "the parity baseline",
                    )
                legacy_https_status[route["route_id"]] = row["status"]

            for route in routes:
                if confidential(route["path"]):
                    attempt(
                        lambda route=route: legacy_confidential_vector(route)
                    )

        def reserved_header_vector() -> None:
            reserved_status, _, _ = request(
                opener,
                args.public_origin.rstrip("/") + routes[0]["path"],
                routes[0]["method"],
                {"X-Adempiere-Handoff-Ticket": "browser-forbidden"},
            )
            if reserved_status != 400:
                raise VectorFailure(
                    routes[0]["route_id"], "reserved-browser-header",
                    f"status {reserved_status} != 400",
                    "a browser-supplied handoff header must be refused",
                )
        attempt(reserved_header_vector)

        if args.context in ELIGIBLE:
            cookie_jar.clear()
            set_switch(args, "enable")
            if args.context == "/wstore":
                bootstrap = next(
                    row for row in routes
                    if row["route_id"].startswith("/wstore::Index::")
                )

                def bootstrap_vector() -> None:
                    first = observe(
                        bootstrap, "modern-session-bootstrap", opener,
                        args.public_origin,
                        expected_modern_status(
                            bootstrap["modern_status_contract"]),
                        True,
                    )
                    public_sessions = [
                        cookie for cookie in cookie_jar
                        if cookie.name == "JSESSIONID"
                        and cookie.path == "/wstore"
                    ]
                    if (first["set_cookie"] != "true"
                            or len(public_sessions) != 1):
                        raise VectorFailure(
                            bootstrap["route_id"], "modern-session-bootstrap",
                            "bootstrap did not establish exactly one "
                            "public session",
                            f"set_cookie={first['set_cookie']} "
                            f"public_wstore_sessions={len(public_sessions)}",
                        )
                    observe(
                        bootstrap, "modern-session-follow-up", opener,
                        args.public_origin,
                        expected_modern_status(
                            bootstrap["modern_status_contract"]),
                        True,
                    )
                attempt(bootstrap_vector)

            cookie_jar.clear()

            def modern_vector(route: dict[str, str]) -> None:
                is_confidential = (
                    args.context == "/wstore" and confidential(route["path"])
                )
                # Each modern route is observed from its own session so that
                # one route's session state cannot decide another's status.
                route_jar, route_opener = fresh_http()
                if is_confidential:
                    redirect = observe(
                        route, "modern-public-tls-redirect", route_opener,
                        args.public_origin, "302", True,
                    )
                    if (
                        not redirect["location"].startswith(
                            args.public_https_origin.rstrip("/") + "/")
                        or "127.0.0.1:8890" in redirect["location"]
                        or "localhost:8890" in redirect["location"]
                    ):
                        raise VectorFailure(
                            route["route_id"], "modern-public-tls-redirect",
                            "confidential redirect leaked or omitted the "
                            "public HTTPS origin",
                            f"location: {redirect['location']}",
                        )
                if is_confidential:
                    # Parity with the same-run legacy HTTPS observation. A
                    # missing baseline means its vector failed, and scoring
                    # this one against a guess would hide that.
                    expected = legacy_https_status.get(route["route_id"])
                    if expected is None:
                        raise VectorFailure(
                            route["route_id"], "modern-public-confidential",
                            "no legacy HTTPS baseline was observed",
                            "the legacy-public-confidential vector for this "
                            "route did not complete, so modern parity cannot "
                            "be scored",
                        )
                else:
                    expected = expected_modern_status(
                        route["modern_status_contract"])
                observe(
                    route,
                    "modern-public-confidential"
                    if is_confidential else "modern-public",
                    https_for(route_jar) if is_confidential else route_opener,
                    args.public_https_origin
                    if is_confidential else args.public_origin,
                    expected,
                    True,
                )

            for route in routes:
                attempt(lambda route=route: modern_vector(route))
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
        # Independent scopes, in this order, because each of the three can
        # fail and none may suppress the others. Publication and the container
        # log harvest are the only surviving explanation of an infrastructure
        # abort, and set_switch itself raises InfrastructureFailure - running
        # it first inside a shared scope would lose exactly the evidence that
        # failure needs, and would replace the original exception with its own.
        try:
            publish()
        finally:
            try:
                harvest_proxy_failures(args)
            finally:
                # The clear must run even after an infrastructure failure:
                # AD_SysConfig is shared, and leaving a context enabled would
                # mis-attribute every later shard of a --continue run.
                set_switch(args, "clear")

    provenance = {
        "context": args.context,
        "public_origin": args.public_origin,
        "public_https_origin": args.public_https_origin,
        "git_head": run(["git", "rev-parse", "HEAD"]).strip(),
        "ci_run_id": os.environ.get("GITHUB_RUN_ID", "local"),
        "ci_run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", "local"),
        "database_marker": args.db_marker,
        "route_count": len(routes),
        "observation_count": len(observations),
        "failure_count": len(failures),
        "modern_execution": (
            "all-routes-public-http-or-https"
            if args.context in ELIGIBLE else "explicitly-unexecuted"
        ),
        "client": "python-urllib-public-origin",
        "legacy_cookie_isolation": "fresh-cookie-jar-per-route",
        "modern_cookie_isolation": (
            "fresh-cookie-jar-per-route"
            if args.context in ELIGIBLE else "not-applicable"
        ),
    }
    (args.evidence_dir / "provenance.json").write_text(
        json.dumps(provenance, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    print(f"{args.context}: recorded {len(observations)} public-origin "
          f"observations, {len(failures)} failed")
    if failures:
        raise SystemExit(
            f"{args.context}: {len(failures)} route vector(s) failed; see "
            f"{args.evidence_dir / 'route-failures.tsv'}"
        )


if __name__ == "__main__":
    main()
