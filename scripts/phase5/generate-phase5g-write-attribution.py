#!/usr/bin/env python3
"""Generate the Phase 5g table-scoped write-attribution inventory.

The Phase 5g-0 inventories record *declarations*: which classes implement
`ModelValidator`, and which `AD_Column` rows name a callout. That is the right
granularity for discovery, but it is the wrong granularity for an attribution
*claim*.

`contracts/legacy-web-write-v1/` claims that the Business Partner write path
fires **no callout and no registered model validator**, and that its database
effect is therefore attributable to the window and the model layer with no
extension arithmetic in between. A gate that proves that claim by
cross-referencing `phase5g-extension-surfaces.tsv` would not actually prove it:
that file records "this class implements ModelValidator", not "this validator
subscribes to this table". Editing an already-inventoried validator so that it
registers `C_BPartner` changes no inventory row at all, and the claim would
quietly become false behind a green gate.

This analyzer is therefore table-scoped. For every table in the reviewed scope
it derives, from the seed dictionary and the reactor sources:

  * the callout columns declared on that table;
  * every model validator class that is actually *registered* in the seed,
    either as an active `AD_ModelValidator` row or through a client's
    `ModelValidationClasses`;
  * for each registered validator, the tables it subscribes to via
    `addModelChange` / `addDocValidate`, followed through its superclass chain,
    because the registered class is frequently not the class that subscribes
    (`LiberoValidator extends Libero`, and `Libero` holds the calls);
  * whether the validator's `EntityType` is itself active, since an inactive
    entity type makes a registered validator inert.

An unregistered validator is inert, and Phase 5g scores what runs, not what
could run. That distinction is the whole point of this file.

It reads only committed files. It starts no container and needs no database.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SCAN_ROOTS_TSV = "gradle/phase5/phase5g-scan-roots.tsv"

# Core source trees. `phase5g-scan-roots.tsv` deliberately excludes `base` and
# `client` because the 5g-0 inventories are about *extension* surfaces. An
# attribution claim cannot exclude them: a core validator on the fixture table
# would be just as much a part of the effect as an extension one, and
# `CalloutBPartnerLocation` and `CalloutOrder` both live in `base`.
CORE_SOURCE_ROOTS = (
    "base/src",
    "client/src",
)

# `addModelChange(MOrder.Table_Name, this)` / `addDocValidate("C_Order", ...)`.
#
# `MOrder.Table_Name` is NOT `Order`: the constant is declared on the generated
# superclass `X_C_Order` as `Table_Name = "C_Order"`. Deriving the table by
# stripping the `M`/`X_` prefix silently produces a name that matches nothing,
# which reports zero validators on every table and makes the attribution claim
# vacuously true. The model class is therefore resolved to its declared
# `Table_Name` literal instead.
SUBSCRIBE_RE = re.compile(
    r"\b(?P<hook>addModelChange|addDocValidate)\s*\(\s*"
    r"(?:(?P<cls>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*Table_Name"
    r"|\"(?P<lit>[A-Za-z0-9_]+)\""
    r"|(?P<dyn>[^,)]+))"
)

TABLE_NAME_CONST_RE = re.compile(
    r"\bString\s+Table_Name\s*=\s*\"(?P<name>[A-Za-z0-9_]+)\""
)

# Both `class` and `interface`, with their `extends` and `implements` lists.
#
# Following `extends` alone is not enough. `MOrder extends X_C_Order`, but
# `X_C_Order` does not declare `Table_Name` either -- it `implements I_C_Order`,
# and the constant lives on that interface. A resolver that follows only
# `extends` therefore reports every model class as unresolved.
TYPE_DECL_RE = re.compile(
    r"\b(?:public\s+|abstract\s+|final\s+|static\s+)*"
    r"(?P<kind>class|interface)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
    r"(?:\s*<[^>]*>)?"
    r"(?P<rest>(?:\s+(?:extends|implements)\s+[^{]*)?)\{"
)

SUPERTYPE_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_.]*)\s*(?:<[^>]*>)?")


def supertypes_of(source: str, type_name: str) -> list[str]:
    """The simple names this type extends or implements, in declaration order."""
    for match in TYPE_DECL_RE.finditer(source):
        if match.group("name") != type_name:
            continue
        rest = match.group("rest") or ""
        rest = re.sub(r"\b(extends|implements)\b", " ", rest)
        names = []
        for candidate in SUPERTYPE_RE.findall(rest):
            simple = candidate.rsplit(".", 1)[-1]
            if simple and simple not in names:
                names.append(simple)
        return names
    return []


def strip_comments(source: str) -> str:
    """Remove block and line comments.

    A commented-out `addModelChange` must not manufacture a subscription, and a
    commented-out one must not hide a real registration either -- so this is
    applied uniformly before any pattern is matched.
    """
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
    return re.sub(r"//[^\n]*", " ", source)


def text_of(value: str | None) -> str:
    return (value or "").strip()


def load_scan_roots(repo_root: Path) -> list[str]:
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


def load_scope(scope_file: Path) -> list[tuple[str, str]]:
    """Read the reviewed attribution scope as (ad_table_id, table_name)."""
    scope: list[tuple[str, str]] = []
    with scope_file.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if fields[0] == "ad_table_id":
                continue
            if len(fields) < 2:
                raise SystemExit(f"malformed scope row in {scope_file}: {line!r}")
            scope.append((fields[0].strip(), fields[1].strip()))
    if not scope:
        raise SystemExit(f"no tables declared in {scope_file}")
    return scope


def read_seed(seed: Path):
    """Single streaming pass over the seed for every row class we need.

    The seed is ~45 MiB, so it is read once and `clear()`ed per element rather
    than parsed repeatedly per question.
    """
    tables: dict[str, str] = {}
    callouts: list[dict[str, str]] = []
    validators: list[dict[str, str]] = []
    clients: list[dict[str, str]] = []
    entity_types: dict[str, str] = {}

    wanted = {
        "AD_TABLE",
        "AD_COLUMN",
        "AD_MODELVALIDATOR",
        "AD_CLIENT",
        "AD_ENTITYTYPE",
    }
    for _event, element in ET.iterparse(seed, events=("end",)):
        tag = element.tag
        if tag in wanted:
            row = dict(element.attrib)
            if tag == "AD_TABLE":
                tables[text_of(row.get("AD_TABLE_ID"))] = text_of(row.get("TABLENAME"))
            elif tag == "AD_COLUMN":
                if text_of(row.get("CALLOUT")):
                    callouts.append(row)
            elif tag == "AD_MODELVALIDATOR":
                validators.append(row)
            elif tag == "AD_CLIENT":
                clients.append(row)
            elif tag == "AD_ENTITYTYPE":
                entity_types[text_of(row.get("ENTITYTYPE"))] = (
                    "Y" if text_of(row.get("ISACTIVE")) == "Y" else "N"
                )
        element.clear()
    return tables, callouts, validators, clients, entity_types


def index_java_sources(repo_root: Path) -> dict[str, Path]:
    """Map simple class name -> source path across the core and scanned trees.

    Simple names are sufficient here and deliberately so: a validator's
    superclass is written as a simple name at the `extends` site, and resolving
    it through imports would add a package resolver for no gain on this corpus.
    A duplicate simple name keeps the first path and is reported by the caller
    through the `resolved_from` column, so an ambiguous resolution is visible in
    the reviewed contract rather than silent.
    """
    index: dict[str, Path] = {}
    roots = [repo_root / r for r in (*CORE_SOURCE_ROOTS, *load_scan_roots(repo_root))]
    for root in roots:
        if not root.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in {"build", "bin", ".git"}]
            for filename in sorted(filenames):
                if not filename.endswith(".java"):
                    continue
                index.setdefault(filename[: -len(".java")], Path(dirpath) / filename)
    return index


def resolve_table_name(
    model_class: str,
    index: dict[str, Path],
    cache: dict[str, str | None],
) -> str | None:
    """Resolve an ADempiere model class to its declared `Table_Name` literal.

    `MOrder` does not declare the constant, and neither does its superclass
    `X_C_Order`; it is declared on the `I_C_Order` interface that `X_C_Order`
    implements. The walk is therefore breadth-first over both `extends` and
    `implements`.
    """
    if model_class in cache:
        return cache[model_class]

    seen: set[str] = set()
    queue: list[str] = [model_class.rsplit(".", 1)[-1]]
    resolved: str | None = None
    while queue:
        current = queue.pop(0)
        if current in seen:
            continue
        seen.add(current)
        path = index.get(current)
        if path is None:
            continue
        source = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        match = TABLE_NAME_CONST_RE.search(source)
        if match:
            resolved = match.group("name")
            break
        queue.extend(supertypes_of(source, current))

    cache[model_class] = resolved
    return resolved


def subscriptions_for(
    class_name: str,
    index: dict[str, Path],
    repo_root: Path,
    table_cache: dict[str, str | None],
) -> tuple[set[str], list[str], set[str]]:
    """Tables a validator subscribes to, following its superclass chain.

    The registered class is frequently not the subscribing class:
    `AD_ModelValidator` row 50000 registers `LiberoValidator`, but the
    `addDocValidate(MOrder.Table_Name, this)` call lives in its superclass
    `Libero`. Stopping at the registered class would report zero subscriptions
    and produce exactly the false "no validator on this path" claim this
    analyzer exists to prevent.
    """
    tables: set[str] = set()
    dynamic: set[str] = set()
    chain: list[str] = []
    seen: set[str] = set()
    # Breadth-first over ALL in-index supertypes, not just the first one. A
    # validator can inherit its subscriptions from a base class while also
    # implementing an interface, and following only `parents[0]` would divert
    # into whichever supertype happened to be listed first -- typically the
    # empty `ModelValidator` interface -- and report the class as inert.
    queue: list[str] = [class_name.rsplit(".", 1)[-1]]

    while queue:
        current = queue.pop(0)
        if current in seen:
            continue
        seen.add(current)
        path = index.get(current)
        if path is None:
            chain.append(f"{current}=unresolved")
            # An unresolvable class is NOT the same fact as a class with no
            # subscriptions, and collapsing the two is the exact falsely-green
            # failure this generator exists to prevent: a validator whose source
            # lives outside the indexed roots would be reported as subscribing
            # to nothing, and the "zero validators on C_BPartner" claim would
            # become true by accident. It is surfaced into `dynamic`, which the
            # validator asserts is empty apart from the reviewed entries.
            dynamic.add(f"{current}:unresolved-class")
            continue
        chain.append(f"{current}={path.relative_to(repo_root)}")
        source = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for match in SUBSCRIBE_RE.finditer(source):
            literal = match.group("lit")
            model = match.group("cls")
            if literal:
                tables.add(literal)
            elif model:
                resolved = resolve_table_name(model, index, table_cache)
                if resolved:
                    tables.add(resolved)
                else:
                    dynamic.add(f"{current}:{model}.Table_Name=unresolved")
            else:
                # A subscription whose table is computed at runtime, such as
                # Libero's `engine.addModelChange(mrpTableName, this)`. It cannot
                # be resolved statically, and dropping it silently would let a
                # dynamic registration on a fixture table go unreported. It is
                # surfaced for review instead.
                expression = " ".join((match.group("dyn") or "").split())
                dynamic.add(f"{current}:{match.group('hook')}({expression})")

        # Continue up this class's own supertypes. `LiberoValidator extends
        # Libero` and the subscribing calls live on `Libero`, so stopping at the
        # registered class reports zero subscriptions.
        #
        # A supertype that is not in the index is recorded rather than dropped,
        # for the same reason an unresolvable registered class is: silently
        # skipping it makes "found nothing" indistinguishable from "could not
        # look".
        for parent in supertypes_of(source, current):
            if parent in index:
                queue.append(parent)
            elif parent not in EXTERNAL_SUPERTYPES:
                dynamic.add(f"{current}:unresolved-supertype={parent}")
    return tables, chain, dynamic


# Supertypes that are expected to be outside the indexed source roots. Every
# other unresolved supertype is reported, because "outside the roots" and "does
# not subscribe to anything" must not be the same answer.
EXTERNAL_SUPERTYPES = frozenset(
    {
        "Object",
        "Serializable",
        "Cloneable",
        "Comparable",
        "Runnable",
        "EventListener",
    }
)


def registered_validators(
    validators: list[dict[str, str]],
    clients: list[dict[str, str]],
    entity_types: dict[str, str],
) -> list[dict[str, str]]:
    """Every validator the seed actually registers, with its registration source.

    Two independent registration mechanisms exist and both must be read. A gate
    that read only `AD_ModelValidator` would miss `compiere.model.MyValidator`,
    which GardenWorld registers through `AD_Client.ModelValidationClasses` and
    which is demonstrably on a Phase 5g fixture path.
    """
    found: list[dict[str, str]] = []
    for row in validators:
        if text_of(row.get("ISACTIVE")) != "Y":
            continue
        entity_type = text_of(row.get("ENTITYTYPE"))
        found.append(
            {
                "classname": text_of(row.get("MODELVALIDATIONCLASS")),
                "registration": "AD_ModelValidator",
                "registration_id": text_of(row.get("AD_MODELVALIDATOR_ID")),
                "ad_client_id": text_of(row.get("AD_CLIENT_ID")),
                "entity_type": entity_type,
                "entity_type_active": entity_types.get(entity_type, "unknown"),
            }
        )
    for row in clients:
        classes = text_of(row.get("MODELVALIDATIONCLASSES"))
        if not classes:
            continue
        for classname in [c.strip() for c in classes.split(";") if c.strip()]:
            found.append(
                {
                    "classname": classname,
                    "registration": "AD_Client.ModelValidationClasses",
                    "registration_id": text_of(row.get("AD_CLIENT_ID")),
                    "ad_client_id": text_of(row.get("AD_CLIENT_ID")),
                    "entity_type": "",
                    # A client-registered class carries no EntityType gate.
                    "entity_type_active": "n/a",
                }
            )
    found.sort(key=lambda r: (r["classname"], r["registration"], r["registration_id"]))
    return found


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
    parser.add_argument(
        "--scope",
        type=Path,
        default=None,
        help="reviewed attribution scope TSV; defaults to the 5g-1a contract",
    )
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    seed = repo_root / "db" / "ddlutils" / "adempiere-data.xml"
    if not seed.is_file():
        print(f"seed dictionary not found: {seed}", file=sys.stderr)
        return 2

    scope_file = args.scope or (
        repo_root / "contracts" / "legacy-web-write-v1" / "attribution-scope.tsv"
    )
    if not scope_file.is_file():
        print(f"attribution scope not found: {scope_file}", file=sys.stderr)
        return 2

    scope = load_scope(scope_file)
    tables, callouts, validators, clients, entity_types = read_seed(seed)

    # A scope row naming a table the dictionary does not have, or naming it
    # inconsistently, is a stale contract rather than a tolerable mismatch.
    for table_id, table_name in scope:
        actual = tables.get(table_id)
        if actual is None:
            print(f"scope table {table_id} is not in the seed dictionary", file=sys.stderr)
            return 2
        if actual != table_name:
            print(
                f"scope table {table_id} is {actual} in the seed, not {table_name}",
                file=sys.stderr,
            )
            return 2

    index = index_java_sources(repo_root)
    registered = registered_validators(validators, clients, entity_types)

    subscription_cache: dict[str, tuple[set[str], list[str], set[str]]] = {}
    table_cache: dict[str, str | None] = {}
    for entry in registered:
        name = entry["classname"]
        if name not in subscription_cache:
            subscription_cache[name] = subscriptions_for(
                name, index, repo_root, table_cache
            )

    rows: list[list[str]] = []
    for table_id, table_name in scope:
        table_callouts = sorted(
            {
                f"{text_of(c.get('COLUMNNAME'))}:{text_of(c.get('CALLOUT'))}"
                for c in callouts
                if text_of(c.get("AD_TABLE_ID")) == table_id
                and text_of(c.get("ISACTIVE")) == "Y"
            }
        )
        rows.append(
            [
                table_id,
                table_name,
                "callout",
                str(len(table_callouts)),
                ";".join(table_callouts) if table_callouts else "-",
                "-",
                "-",
            ]
        )
        for entry in registered:
            subscribed, chain, _dynamic = subscription_cache[entry["classname"]]
            if table_name not in subscribed:
                continue
            rows.append(
                [
                    table_id,
                    table_name,
                    "model-validator",
                    "1",
                    entry["classname"],
                    f"{entry['registration']}:{entry['registration_id']}"
                    f":client={entry['ad_client_id']}"
                    f":entitytype={entry['entity_type'] or '-'}"
                    f":entitytype_active={entry['entity_type_active']}",
                    ";".join(chain),
                ]
            )

    # A subscription whose table is computed at runtime cannot be attributed to a
    # scope table statically. It is emitted under the sentinel table id 0 so it
    # is REVIEWED rather than dropped: an unreported dynamic registration is
    # exactly how a validator ends up on a fixture path behind a green gate.
    for entry in registered:
        _subscribed, _chain, dynamic = subscription_cache[entry["classname"]]
        for expression in sorted(dynamic):
            rows.append(
                [
                    "0",
                    "-",
                    "dynamic-subscription",
                    "1",
                    entry["classname"],
                    expression,
                    "-",
                ]
            )

    rows.sort(key=lambda r: (int(r[0]), r[2], r[4], r[5]))

    write_tsv(
        args.output_dir.resolve() / "attribution.tsv",
        [
            "ad_table_id",
            "table_name",
            "hook_kind",
            "hook_count",
            "hook",
            "registration",
            "resolved_from",
        ],
        rows,
        [
            "Phase 5g table-scoped write attribution.",
            "",
            "For every table in the reviewed attribution scope: the active callout",
            "columns declared on it, and every model validator the seed actually",
            "REGISTERS that subscribes to it.",
            "",
            "Registration is read from BOTH AD_ModelValidator rows and each client's",
            "AD_Client.ModelValidationClasses. Subscriptions are followed through the",
            "registered class's superclass chain, because the registered class is",
            "frequently not the subscribing class: AD_ModelValidator row 50000",
            "registers LiberoValidator, but the addDocValidate call lives in Libero.",
            "",
            "A `callout` row with hook_count 0 is a positive finding, not an absence of",
            "data: it is the claim that the table fires no callout.",
            "",
            "An unregistered validator is inert and is deliberately absent. Phase 5g",
            "scores what runs, not what could run.",
            "",
            "Generated by scripts/phase5/generate-phase5g-write-attribution.py from",
            "db/ddlutils/adempiere-data.xml and the reactor sources. Do not hand-edit.",
        ],
    )
    print(
        f"generated write attribution for {len(scope)} scope table(s): "
        f"{len(rows)} row(s) from {len(registered)} registered validator(s)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
