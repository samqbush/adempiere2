#!/usr/bin/env python3
"""Generate the Phase 5g-0 discovery inventories.

Phase 5g must migrate the ADempiere web UI's *functional* surface: writes,
processes, reports, uploads, dashboards, server push, POS and extensions. Two
facts have to be established before any write fixture can be designed, and
neither can be established by reading source alone:

  1. Which extension-owned code actually runs during a web write. Callouts and
     model validators fire on every write and document transition, forms execute
     processes, and extension processes emit reports and accept files. A fixture
     designed without knowing which hooks fire is a fixture whose result cannot
     be attributed.

  2. What a dictionary process actually *is*. `AD_Process` covers reports,
     workflows, Java classes and SQL procedures, and `ProcessCtl` runs
     asynchronously when it has a parent. "A dictionary process" is not a
     fixture until one is named with a fixed ID, a known execution class and an
     observable completion signal.

This script derives both inventories from two sources that cannot disagree with
the shipped product: the application dictionary in the seed database, and the
Java sources in the reactor. It writes generated TSVs which a later gate
compares against the reviewed, committed copies, following the Phase 5a/5f
generate-then-validate pattern.

It reads only committed files. It starts no container and needs no database.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# The trees scanned for classes that extend or render the web UI.
#
# The list lives in `gradle/phase5/phase5g-scan-roots.tsv` rather than here so
# that this generator and the Gradle gate that declares its inputs cannot
# disagree. A hand-copied second list is how a scanned tree ends up undeclared
# as a Gradle input, which lets a stale UP-TO-DATE result hide a new extension
# surface while the gate stays green.
SCAN_ROOTS_TSV = "gradle/phase5/phase5g-scan-roots.tsv"


def load_scan_roots(repo_root: Path) -> list[str]:
    """Read the shared scan-root list. Order is taken from the file."""
    listing = repo_root / SCAN_ROOTS_TSV
    roots: list[str] = []
    with listing.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if fields[0] == "path":
                continue
            roots.append(fields[0])
    if not roots:
        raise SystemExit(f"no scan roots declared in {listing}")
    return roots


def text_of(value: str | None) -> str:
    return (value or "").strip()


def iter_rows(seed: Path, wanted: set[str]):
    """Stream the seed dictionary, yielding (tag, attrib) for wanted tags.

    The seed is ~45 MiB. `iterparse` with an explicit `clear()` keeps peak
    memory flat rather than materialising the whole tree.
    """
    for _event, element in ET.iterparse(seed, events=("end",)):
        if element.tag in wanted:
            yield element.tag, dict(element.attrib)
        element.clear()


def classify_process(row: dict[str, str]) -> tuple[str, str]:
    """Classify one AD_Process row by how it executes.

    Returns (execution_class, reason). The classification decides Phase 5g
    ownership: only `java-process` is eligible for the 5g-1e write/process
    fixture. Everything a report engine renders belongs to 5g-2, and everything
    a workflow drives belongs with the document increments.
    """
    classname = text_of(row.get("CLASSNAME"))
    procedure = text_of(row.get("PROCEDURENAME"))
    workflow = text_of(row.get("AD_WORKFLOW_ID"))
    report = text_of(row.get("ISREPORT")) == "Y"
    jasper = text_of(row.get("JASPERREPORT"))

    if report or jasper:
        return "report", "IsReport=Y or a JasperReport is declared"
    if workflow:
        return "workflow", "AD_Workflow_ID is set, so ProcessCtl starts a workflow"
    if classname and procedure:
        return "java-and-procedure", "both Classname and ProcedureName are set"
    if classname:
        return "java-process", "Classname is set and no report/workflow overrides it"
    if procedure:
        return "sql-procedure", "ProcedureName is set with no Java class"
    return "declaration-only", "no Classname, ProcedureName, workflow or report"


def collect_processes(seed: Path) -> list[list[str]]:
    rows: list[list[str]] = []
    for _tag, row in iter_rows(seed, {"AD_PROCESS"}):
        execution_class, reason = classify_process(row)
        rows.append(
            [
                text_of(row.get("AD_PROCESS_ID")),
                text_of(row.get("VALUE")),
                text_of(row.get("NAME")),
                text_of(row.get("ENTITYTYPE")),
                "Y" if text_of(row.get("ISACTIVE")) == "Y" else "N",
                execution_class,
                text_of(row.get("CLASSNAME")) or "-",
                text_of(row.get("PROCEDURENAME")) or "-",
                reason,
            ]
        )
    rows.sort(key=lambda r: int(r[0]) if r[0].isdigit() else 0)
    return rows


def collect_forms(seed: Path) -> dict[str, dict[str, str]]:
    forms: dict[str, dict[str, str]] = {}
    for _tag, row in iter_rows(seed, {"AD_FORM"}):
        forms[text_of(row.get("AD_FORM_ID"))] = row
    return forms


def collect_callout_columns(seed: Path) -> list[list[str]]:
    """Every AD_Column that declares a callout.

    A callout fires on field change during a write. If an extension owns one on
    a column a Phase 5g fixture touches, the fixture's database effect is partly
    that extension's, and the fixture must say so.
    """
    rows: list[list[str]] = []
    for _tag, row in iter_rows(seed, {"AD_COLUMN"}):
        callout = text_of(row.get("CALLOUT"))
        if not callout:
            continue
        rows.append(
            [
                text_of(row.get("AD_COLUMN_ID")),
                text_of(row.get("AD_TABLE_ID")),
                text_of(row.get("COLUMNNAME")),
                text_of(row.get("ENTITYTYPE")),
                "Y" if text_of(row.get("ISACTIVE")) == "Y" else "N",
                callout,
            ]
        )
    rows.sort(key=lambda r: int(r[0]) if r[0].isdigit() else 0)
    return rows


JAVA_SURFACE_PATTERNS = (
    # (surface, compiled pattern). Each pattern is anchored on a declaration, not
    # on an arbitrary mention, so a comment or an import cannot create a row.
    ("model-validator", re.compile(r"\bimplements\b[^{]*\bModelValidator\b")),
    ("callout", re.compile(r"\b(extends\s+CalloutEngine|implements\b[^{]*\bCallout\b)")),
    ("process", re.compile(r"\bextends\s+SvrProcess\b")),
    ("zk-form", re.compile(r"\bimplements\b[^{]*\bIFormController\b")),
)


def scan_java_surfaces(repo_root: Path) -> list[list[str]]:
    """Scan extension projects for classes that extend the web UI at runtime."""
    rows: list[list[str]] = []
    roots = [repo_root / prefix for prefix in load_scan_roots(repo_root)]

    for root in roots:
        if not root.is_dir():
            continue
        owner = root.relative_to(repo_root).parts[0]
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in {"build", "bin", ".git"}]
            for filename in sorted(filenames):
                if not filename.endswith(".java"):
                    continue
                path = Path(dirpath) / filename
                try:
                    source = path.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    continue
                # Strip block and line comments so a commented-out declaration
                # cannot manufacture a surface row.
                stripped = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
                stripped = re.sub(r"//[^\n]*", " ", stripped)
                for surface, pattern in JAVA_SURFACE_PATTERNS:
                    if pattern.search(stripped):
                        rows.append(
                            [
                                owner,
                                str(path.relative_to(repo_root)),
                                surface,
                            ]
                        )
    rows.sort(key=lambda r: (r[0], r[1], r[2]))
    return rows


def write_tsv(path: Path, header: list[str], rows: list[list[str]], preamble: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for line in preamble:
            handle.write(f"# {line}\n" if line else "#\n")
        handle.write("\t".join(header) + "\n")
        for row in rows:
            handle.write("\t".join(row) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    seed = repo_root / "db" / "ddlutils" / "adempiere-data.xml"
    if not seed.is_file():
        print(f"seed dictionary not found: {seed}", file=sys.stderr)
        return 2

    out = args.output_dir.resolve()

    processes = collect_processes(seed)
    callouts = collect_callout_columns(seed)
    surfaces = scan_java_surfaces(repo_root)

    write_tsv(
        out / "phase5g-process-classification.tsv",
        [
            "ad_process_id",
            "value",
            "name",
            "entity_type",
            "is_active",
            "execution_class",
            "classname",
            "procedure_name",
            "reason",
        ],
        processes,
        [
            "Phase 5g-0: every AD_Process in the seed dictionary, classified by how",
            "it executes.",
            "",
            "Only `java-process` rows are eligible for the Phase 5g-1e write/process",
            "fixture. `report` rows belong to Phase 5g-2, `workflow` rows to the",
            "document increments, and `declaration-only` rows execute nothing.",
            "",
            "Generated by scripts/phase5/generate-phase5g-inventories.py from",
            "db/ddlutils/adempiere-data.xml. Do not hand-edit.",
        ],
    )

    write_tsv(
        out / "phase5g-callout-columns.tsv",
        [
            "ad_column_id",
            "ad_table_id",
            "column_name",
            "entity_type",
            "is_active",
            "callout",
        ],
        callouts,
        [
            "Phase 5g-0: every AD_Column that declares a callout.",
            "",
            "A callout fires on field change during a write. If a Phase 5g fixture",
            "touches one of these columns, part of the fixture's database effect is",
            "owned by the callout, and the fixture must say so rather than attribute",
            "the whole effect to the window.",
            "",
            "Generated by scripts/phase5/generate-phase5g-inventories.py from",
            "db/ddlutils/adempiere-data.xml. Do not hand-edit.",
        ],
    )

    write_tsv(
        out / "phase5g-extension-surfaces.tsv",
        ["owner", "source_path", "surface"],
        surfaces,
        [
            "Phase 5g-0: Java classes that extend or render the web UI at",
            "runtime, owned by owner. Three kinds of owner appear:",
            "extension reactor projects; the per-client MODELVALIDATIONCLASSES",
            "overlay project `extend`, which the GardenWorld seed registers",
            "and which is therefore on a Phase 5g fixture path; and zkwebui",
            "itself, scanned only for its ZK form controllers.",
            "",
            "The scanned trees are declared in",
            "gradle/phase5/phase5g-scan-roots.tsv, which the Gradle gate reads",
            "as well so that a scanned tree cannot go undeclared as a build",
            "input.",
            "",
            "Surfaces:",
            "  model-validator  implements ModelValidator; fires on every write and",
            "                   document transition in its client.",
            "  callout          extends CalloutEngine or implements Callout; fires on",
            "                   field change during a write.",
            "  process          extends SvrProcess; the executable body behind an",
            "                   AD_Process row.",
            "  zk-form          implements IFormController; a ZK form, which can in",
            "                   turn execute processes and reports.",
            "",
            "Declarations are matched after block and line comments are stripped, so",
            "a commented-out or merely mentioned type cannot create a row.",
            "",
            "Generated by scripts/phase5/generate-phase5g-inventories.py. Do not",
            "hand-edit.",
        ],
    )

    print(
        f"generated Phase 5g-0 inventories: {len(processes)} processes, "
        f"{len(callouts)} callout columns, {len(surfaces)} extension surfaces"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
