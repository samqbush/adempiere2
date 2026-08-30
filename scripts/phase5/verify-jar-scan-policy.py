#!/usr/bin/env python3
"""Prove the reviewed JarScanFilter policy against the built Phase 5e/5f WARs.

The context descriptors suppress Tomcat's TLD and pluggability JAR walks (see
gradle/phase5/jar-scan-policy.tsv for why). That suppression is safe only while
the archives contain nothing those walks exist to discover. This gate is what
makes it safe: it opens every nested JAR of every deployed WAR and fails closed
when reality and the reviewed policy disagree, in either direction.

Two distinct failures are caught:

  * a JAR that ships a TLD but is not in the reviewed allowlist would silently
    lose its tag library at runtime;
  * a JAR that ships META-INF/web-fragment.xml, a ServletContainerInitializer
    service entry, or META-INF/resources/** would silently lose a servlet
    registration or a static web resource, because
    ContextConfig.processResourceJARs() builds from the pluggability fragment
    map this policy empties.

An allowlist entry that is no longer justified fails too, so the policy cannot
accumulate stale exceptions.

Scope: WEB-INF/lib of every deployed WAR. That is the complete set the
filter applies to, because the generated <JarScanner> sets
scanClassPath="false"; if it did not, tldSkip="*.jar" would also reach
$CATALINA_BASE/lib, which this gate cannot see.
"""

from __future__ import annotations

import argparse
import io
import posixpath
import sys
import zipfile
from pathlib import Path

TLD_SUFFIX = ".tld"
FRAGMENT = "META-INF/web-fragment.xml"
SCI = "META-INF/services/jakarta.servlet.ServletContainerInitializer"
RESOURCES = "META-INF/resources/"

# Metadata kinds that make blanket pluggability suppression unsafe. Unlike a
# missing TLD, none of these has an allowlist: if one ever appears the filter
# itself has to be reconsidered, so the reviewed answer must be a human one.
PLUGGABILITY_KINDS = ("fragment", "sci", "resources")


def classify(names: list[str]) -> set[str]:
    found: set[str] = set()
    for name in names:
        if not name.startswith("META-INF/"):
            continue
        if name.lower().endswith(TLD_SUFFIX):
            found.add("tld")
        elif name == FRAGMENT:
            found.add("fragment")
        elif name == SCI:
            found.add("sci")
        elif name.startswith(RESOURCES) and not name.endswith("/"):
            found.add("resources")
    return found


def inventory_war(war: Path) -> tuple[dict[str, set[str]], list[str], int]:
    """Per-JAR metadata kinds, loose /WEB-INF TLDs, and the JAR entry total."""
    jars: dict[str, set[str]] = {}
    total_entries = 0
    with zipfile.ZipFile(war) as archive:
        names = archive.namelist()
        webinf_tlds = sorted(
            name for name in names
            if name.startswith("WEB-INF/")
            and not name.startswith("WEB-INF/lib/")
            and name.lower().endswith(TLD_SUFFIX))
        for name in sorted(names):
            if not name.startswith("WEB-INF/lib/") or not name.endswith(".jar"):
                continue
            with archive.open(name) as handle:
                payload = handle.read()
            try:
                with zipfile.ZipFile(io.BytesIO(payload)) as nested:
                    nested_names = nested.namelist()
            except zipfile.BadZipFile:
                raise SystemExit(f"{war.name}: {name} is not a readable JAR")
            total_entries += len(nested_names)
            jars[posixpath.basename(name)] = classify(nested_names)
    return jars, webinf_tlds, total_entries


def read_policy(path: Path) -> dict[tuple[str, str], str]:
    allowed: dict[tuple[str, str], str] = {}
    header_seen = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if not header_seen:
            if fields[:4] != ["context", "jar", "scan_type", "reason"]:
                raise SystemExit(f"{path}: unexpected header {fields!r}")
            header_seen = True
            continue
        if len(fields) != 4:
            raise SystemExit(f"{path}: expected 4 columns, got {len(fields)}")
        context, jar, scan_type, reason = fields
        if scan_type != "tld":
            raise SystemExit(
                f"{path}: only 'tld' allowlist entries are supported, "
                f"got {scan_type!r}")
        if not reason.strip():
            raise SystemExit(f"{path}: {context}/{jar} has no reason")
        allowed[(context, jar)] = scan_type
    if not header_seen:
        raise SystemExit(f"{path}: no header row")
    return allowed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--war", action="append", required=True, metavar="CONTEXT=PATH",
        help="a deployed context name and its WAR, repeatable")
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    allowed = read_policy(args.policy)
    problems: list[str] = []
    rows = [
        "# Generated by scripts/phase5/verify-jar-scan-policy.py; do not "
        "hand-edit.",
        "# context\tjars\tnested_entries\ttld_jars\tpluggability_findings"
        "\twebinf_tlds",
    ]
    observed_allowlist: set[tuple[str, str]] = set()

    for spec in args.war:
        context, _, path = spec.partition("=")
        if not path:
            raise SystemExit(f"--war expects CONTEXT=PATH, got {spec!r}")
        war = Path(path)
        if not war.is_file():
            raise SystemExit(f"missing WAR for context {context}: {war}")

        jars, webinf_tlds, total_entries = inventory_war(war)
        tld_jars = sorted(name for name, kinds in jars.items() if "tld" in kinds)
        findings = sorted(
            f"{name}:{kind}"
            for name, kinds in jars.items()
            for kind in PLUGGABILITY_KINDS if kind in kinds)

        for jar in tld_jars:
            observed_allowlist.add((context, jar))
            if (context, jar) not in allowed:
                problems.append(
                    f"{context}: {jar} ships a TLD but is not allowlisted in "
                    f"{args.policy.name}; tldSkip would silently drop its tag "
                    "library")
        for finding in findings:
            problems.append(
                f"{context}: {finding} is present, so pluggabilitySkip=\"*.jar\" "
                "would silently drop a servlet registration or web resource")

        rows.append(
            f"{context}\t{len(jars)}\t{total_entries}\t"
            f"{','.join(tld_jars) if tld_jars else '-'}\t"
            f"{','.join(findings) if findings else '-'}\t"
            f"{','.join(webinf_tlds) if webinf_tlds else '-'}")

    for context, jar in sorted(set(allowed) - observed_allowlist):
        problems.append(
            f"{context}: {jar} is allowlisted in {args.policy.name} but ships "
            "no TLD; remove the stale exception")

    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text("\n".join(rows) + "\n", encoding="utf-8")

    if problems:
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        raise SystemExit(
            f"The reviewed JAR scan policy does not match the built WARs "
            f"({len(problems)} problems)")

    print(
        f"JAR scan policy verified across {len(args.war)} deployed WARs: "
        f"{len(allowed)} reviewed TLD exception(s), no fragment, "
        f"ServletContainerInitializer or META-INF/resources finding",
        file=sys.stderr)


if __name__ == "__main__":
    main()
