#!/usr/bin/env python3
"""Derive the Phase 5g-1a fact classes the browser driver must not produce.

WHY THESE ARE DERIVED HERE AND NOT EMITTED BY THE DRIVER

The driver is deliberately ignorant of the expected answer: it never reads
`effect-model.tsv` and never asserts a business value. Three of the frozen fact
classes -- the business values, the foreign-key graph and the concurrency
outcome -- are statements ABOUT the database the flow produced. Emitting them
from the browser would mean the browser both performed the operation and
reported what it believed the operation did, and a mistaken belief would be
frozen as the oracle.

So they are derived from the captured snapshots instead: the same measurement
layer the effect model uses, normalized by the same reviewed policy that is
already mutation-proved in both directions.

The fourth class, `network-classes.tsv`, is derived here for a different reason.
The driver records raw request lines, which carry ordering and repetition that
are properties of the browser's scheduler rather than of the application.
Classification is a normalization policy decision, and normalization policy
belongs in a reviewable script, not in a test method.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))

from normalize_write_capture import (  # noqa: E402
    IdentityMap,
    normalize_row,
    render_row,
)

# Key columns are rendered in the key field of every row, so repeating them in
# the body would double every identity without adding a fact.
SKIP_COLUMNS: frozenset[str] = frozenset()


def primary_component(key: str) -> str:
    """The first component of a possibly-composite captured key.

    Duplicated from measure-write-effect.py rather than imported: that file's
    name is not a legal module name, and reaching for importlib to share four
    lines would cost more than it saves. Kept identical on purpose.
    """
    return key.split("+", 1)[0]


def read_rows(path: Path) -> list[str]:
    if not path.is_file():
        return []
    return [
        line
        for line in path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    ]


def load_scope(path: Path) -> list[dict[str, str]]:
    scope: list[dict[str, str]] = []
    for line in read_rows(path):
        fields = line.split("\t")
        if fields[0] == "table":
            continue
        if len(fields) < 3:
            raise SystemExit(f"malformed measurement scope row: {line!r}")
        scope.append({
            "table": fields[0].strip(),
            # Possibly composite ("a+b"); see measure-write-effect.py.
            "key_column": fields[1].strip().split("+", 1)[0],
        })
    if not scope:
        raise SystemExit(f"no tables declared in {path}")
    return scope


def load_table_ids(path: Path) -> dict[str, int]:
    table_ids: dict[str, int] = {}
    for line in read_rows(path):
        fields = line.split("\t")
        if fields[0] == "ad_table_id" or len(fields) < 2:
            continue
        try:
            table_ids[fields[1].strip().lower()] = int(fields[0].strip())
        except ValueError:
            continue
    return table_ids


def ordered_snapshots(capture: Path) -> list[Path]:
    """Snapshots in step order, not in string order.

    `step-10.json` sorts before `step-2.json` as text. The lane will not produce
    ten steps today, but a comparison that silently reorders itself when it does
    is the kind of defect that surfaces as an unexplained diff much later.
    """
    paths = list((capture / "snapshots").glob("step-*.json"))
    return sorted(paths, key=lambda path: int(path.stem.split("-")[1]))


def build_identities(
    snapshots: list[Path], scope: list[dict[str, str]], table_ids: dict[str, int]
) -> IdentityMap:
    """Symbols for every key this capture created.

    A row is "created" if it is absent from the first snapshot and present in a
    later one. Taking the baseline as the reference -- rather than treating every
    row in the final snapshot as new -- is what keeps a seeded row from being
    handed a capture-local symbol.
    """
    identities = IdentityMap()
    for table, table_id in table_ids.items():
        identities.declare_table_id(table, table_id)
    if not snapshots:
        return identities

    baseline = json.loads(snapshots[0].read_text(encoding="utf-8"))["scope"]
    for path in snapshots[1:]:
        document = json.loads(path.read_text(encoding="utf-8"))["scope"]
        for entry in scope:
            table = entry["table"].lower()
            known = set(baseline.get(table) or {})
            for key in sorted((document.get(table) or {})):
                if key not in known:
                    identities.declare(table, f"{table}_id", primary_component(key))
    return identities


def business_values(
    snapshots: list[Path], scope: list[dict[str, str]], identities: IdentityMap
) -> list[str]:
    """The final normalized state of every row inside the measurement scope.

    The effect model says what each step CHANGED; this says what the flow left
    behind. Both are needed: a pair of steps that cancel each other out produces
    a plausible per-step effect sequence and the wrong final row.
    """
    if not snapshots:
        return []
    document = json.loads(snapshots[-1].read_text(encoding="utf-8"))["scope"]
    rows: list[str] = []
    for entry in scope:
        table = entry["table"].lower()
        for key, row in sorted((document.get(table) or {}).items()):
            # Looked up under the same synthetic `<table>_id` column the
            # declaration used, so a composite-keyed child gets its OWN symbol
            # instead of collapsing onto the parent whose id is its first key
            # component. measure-write-effect.py resolves identically.
            symbol = identities.symbol_for(
                f"{table}_id", primary_component(key)
            ) or key
            rows.append(
                render_row(
                    table, symbol, normalize_row(table, row, identities, SKIP_COLUMNS)
                )
            )
    return rows


def foreign_key_graph(
    snapshots: list[Path], scope: list[dict[str, str]], identities: IdentityMap
) -> list[str]:
    """Edges between rows this capture created.

    Derived from resolved symbols rather than from `pg_constraint`: a declared
    constraint says an edge is POSSIBLE, while a resolved symbol says this flow
    actually wired one row to another. The latter is the fact 5g-1b must
    reproduce.
    """
    if not snapshots:
        return []
    document = json.loads(snapshots[-1].read_text(encoding="utf-8"))["scope"]
    edges: set[str] = set()
    for entry in scope:
        table = entry["table"].lower()
        # Only the table's OWN identity column is excluded from the edge scan.
        # Excluding the whole declared key would be wrong for a composite one:
        # C_BP_Customer_Acct is keyed on (C_BPartner_ID, C_AcctSchema_ID), so its
        # reference to the business partner IS a key component, and skipping key
        # components dropped exactly the fan-out edges this file exists to record.
        self_column = f"{table}_id"
        for key, row in (document.get(table) or {}).items():
            child = identities.symbol_for(
                f"{table}_id", primary_component(key))
            if child is None:
                continue
            for column, value in row.items():
                name = column.lower()
                if name == self_column or value is None:
                    continue
                if not (name.endswith("_id") or name in ("record_id", "node_id")):
                    continue
                # `container` is what lets a generic Node_ID resolve; without it
                # the tree-node edge -- the reason the mechanism exists -- is
                # silently unreachable here while business-values.tsv shows it.
                parent = identities.symbol_for(name, str(value), row, table)
                if parent is None or parent == child:
                    continue
                target = identities.table_of(parent) or "?"
                edges.add(f"{table}\t{name}\t{target}")
    return sorted(edges)


def network_classes(capture: Path) -> list[str]:
    """Raw request lines reduced to the reviewed class/method/target shape.

    Ordering and repetition are dropped on purpose: they are properties of the
    browser's request scheduler, and a capture that kept them would diff against
    itself between two runs of the same flow.
    """
    classes: set[str] = set()
    for line in read_rows(capture / "network-requests.tsv"):
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        method, url = fields[0], fields[1]
        if "/zkau" in url:
            # The AU channel carries every ZK interaction, so its individual
            # targets are a transcript of the flow rather than a route fact.
            classes.add(f"zkau\t{method}\t")
            continue
        split = urlsplit(url)
        if split.scheme and split.netloc:
            classes.add(f"external\t{method}\t{split.netloc}")
            continue
        segments = [segment for segment in split.path.split("/") if segment]
        context = f"/{segments[0]}/" if segments else "/"
        classes.add(f"context\t{method}\t{context}")
    return sorted(classes)


def concurrency_facts(capture: Path) -> list[str]:
    """What the legacy runtime did when two editors raced the same row.

    Read from the semantic facts the driver RECORDED rather than judged: the
    conflicting save is the one save in the flow that is not asserted to
    succeed, because whether it is refused, overwritten or reloaded is precisely
    the answer being captured.
    """
    facts: list[str] = []
    for line in read_rows(capture / "semantic-facts.tsv"):
        if line.startswith("concurrency-"):
            facts.append(line)
    return facts


def write(path: Path, header: str, rows: list[str]) -> None:
    path.write_text(header + "\n".join(rows) + ("\n" if rows else ""), encoding="utf-8")
    print(f"derived {path.name}: {len(rows)} row(s)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", required=True, type=Path)
    parser.add_argument("--scope", required=True, type=Path)
    parser.add_argument("--attribution-scope", required=True, type=Path)
    args = parser.parse_args()

    scope = load_scope(args.scope)
    table_ids = load_table_ids(args.attribution_scope)
    snapshots = ordered_snapshots(args.capture)
    if not snapshots:
        raise SystemExit(
            f"{args.capture} contains no snapshot. Deriving facts from nothing "
            "would emit empty files that compare equal to each other and prove "
            "nothing, so this is a failure rather than an empty result."
        )

    identities = build_identities(snapshots, scope, table_ids)

    write(
        args.capture / "business-values.tsv",
        "# table\tkey\tcolumn=value,...\n",
        business_values(snapshots, scope, identities),
    )
    write(
        args.capture / "foreign-key-graph.tsv",
        "# child-table\tcolumn\tparent-table\n",
        foreign_key_graph(snapshots, scope, identities),
    )
    write(
        args.capture / "network-classes.tsv",
        "# class\tmethod\ttarget\n",
        network_classes(args.capture),
    )
    write(
        args.capture / "concurrency-facts.tsv",
        "# fact\tvalue\n",
        concurrency_facts(args.capture),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
