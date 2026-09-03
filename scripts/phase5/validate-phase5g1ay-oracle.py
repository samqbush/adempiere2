#!/usr/bin/env python3
"""Validate the Phase 5g-1a-y corrected-legacy capture contract."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import tempfile
from typing import Iterable


HEX_40 = re.compile(r"^[0-9a-f]{40}$")
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
MANIFEST_NAME = "manifest.sha256"
WORKTREE_MUTATION_EXCLUSIONS = (
    "lib/ADInterface-1.0.war",
    "lib/mysql-connector-java-5.1.13-bin.jar",
)
EXPECTED_PATCH_HUNKS = [
    (
        "base/src/org/compiere/wf/DocWorkflowManager.java",
        "@@ -110,7 +110,7 @@ public class DocWorkflowManager implements DocWorkflowMgr",
        [
            "-\t\t\tif (wf.start(pi) != null)",
            "+\t\t\tif (wf.start(document.getCtx(), pi) != null)",
        ],
    ),
    (
        "base/src/org/compiere/wf/MWorkflow.java",
        "@@ -706,12 +706,23 @@ public class MWorkflow extends X_AD_Workflow",
        [
            "+\t\treturn start(getCtx(), processInfo);",
            "+\t}",
            "+\tpublic MWFProcess start (Properties invocationCtx, ProcessInfo processInfo)",
            "+\t{",
            "-\t\t\tworkflowProcess = new MWFProcess (this, processInfo, null);",
            "+\t\t\tworkflowProcess = new MWFProcess (this, invocationCtx, processInfo, null);",
        ],
    ),
    (
        "base/src/org/compiere/wf/MWFProcess.java",
        "@@ -93,7 +93,20 @@ public class MWFProcess extends X_AD_WF_Process",
        [
            "-\t\tsuper (wf.getCtx(), 0, trxName);",
            "+\t\tthis(wf, wf.getCtx(), pi, trxName);",
            "+\t}",
            "+\tpublic MWFProcess (MWorkflow wf, Properties invocationCtx, ProcessInfo pi, String trxName) throws Exception",
            "+\t{",
            "+\t\tsuper (invocationCtx, 0, trxName);",
        ],
    ),
]
EVENT_AUDIT_POLICY_FILE = "workflow-attribution-policy.tsv"
EVENT_AUDIT_TABLE = "ad_wf_eventaudit"
EVENT_AUDIT_KEY_COLUMN = "ad_wf_eventaudit_id"
EVENT_AUDIT_PREDICATE = (
    "AD_WF_Process_ID IN (SELECT AD_WF_Process_ID FROM AD_WF_Process "
    "WHERE AD_Table_ID = 291 AND Record_ID IN (SELECT C_BPartner_ID FROM "
    "C_BPartner WHERE Value LIKE 'P5G1A-%')) AND EXISTS (SELECT 1 FROM "
    "AD_WF_Activity a WHERE a.AD_WF_Process_ID = "
    "AD_WF_EventAudit.AD_WF_Process_ID AND a.AD_WF_Node_ID = "
    "AD_WF_EventAudit.AD_WF_Node_ID AND a.AD_Table_ID = 291 AND "
    "a.Record_ID IN (SELECT C_BPartner_ID FROM C_BPartner WHERE Value LIKE "
    "'P5G1A-%'))"
)
EVENT_AUDIT_REQUIRED_COLUMNS = (
    "ad_client_id",
    "ad_org_id",
    "createdby",
    "updatedby",
    "ad_user_id",
    "ad_wf_responsible_id",
    "ad_wf_process_id",
    "ad_wf_node_id",
    "ad_table_id",
    "record_id",
    "eventtype",
    "wfstate",
)
EVENT_AUDIT_REQUIRED_EDGE = (
    "ad_wf_eventaudit\tad_wf_process_id\tad_wf_process"
)
EVENT_AUDIT_ACTIVITY_JOIN = "ad_wf_process_id+ad_wf_node_id"
PENDING_WORKFLOW_ATTRIBUTION = {
    "ad_client_id": "0",
    "ad_org_id": "0",
    "createdby": "0",
    "updatedby": "0",
}


class ValidationError(RuntimeError):
    pass


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def data_lines(path: pathlib.Path) -> list[str]:
    return [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]


def data_lines_text(text: str) -> list[str]:
    return [
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.startswith("#")
    ]


def load_event_audit_policy(policy_text: str) -> dict[str, str]:
    lines = data_lines_text(policy_text)
    expected_header = [
        "table_name",
        "key_column",
        "measurement_predicate",
        "required_business_columns",
        "required_foreign_key_edges",
        "required_activity_join",
    ]
    if len(lines) != 2 or lines[0].split("\t") != expected_header:
        raise ValidationError(
            f"{EVENT_AUDIT_POLICY_FILE} must carry one exact policy row"
        )
    fields = lines[1].split("\t")
    if len(fields) != len(expected_header):
        raise ValidationError(
            f"{EVENT_AUDIT_POLICY_FILE} has an invalid policy row: {lines[1]}"
        )
    policy = dict(zip(expected_header, fields))
    expected = {
        "table_name": "AD_WF_EventAudit",
        "key_column": EVENT_AUDIT_KEY_COLUMN,
        "measurement_predicate": EVENT_AUDIT_PREDICATE,
        "required_business_columns": "+".join(EVENT_AUDIT_REQUIRED_COLUMNS),
        "required_foreign_key_edges": "ad_wf_process_id:ad_wf_process",
        "required_activity_join": EVENT_AUDIT_ACTIVITY_JOIN,
    }
    differences = {
        key: (value, policy.get(key))
        for key, value in expected.items()
        if policy.get(key) != value
    }
    if differences:
        raise ValidationError(
            f"{EVENT_AUDIT_POLICY_FILE} weakens the reviewed requirement: "
            f"{differences}"
        )
    return policy


def parse_rendered_rows(text: str, label: str) -> list[tuple[str, str, dict[str, str]]]:
    rows: list[tuple[str, str, dict[str, str]]] = []
    for line in data_lines_text(text):
        if line.startswith("["):
            continue
        fields = line.split("\t", 2)
        if len(fields) != 3:
            raise ValidationError(f"{label} has an invalid rendered row: {line}")
        table, key, body = fields
        columns: dict[str, str] = {}
        for item in body.split(","):
            if "=" not in item:
                raise ValidationError(
                    f"{label} has an invalid column rendering: {item}"
                )
            column, value = item.split("=", 1)
            if column in columns:
                raise ValidationError(
                    f"{label} repeats column {column!r} in {table}/{key}"
                )
            columns[column] = value
        rows.append((table.lower(), key, columns))
    return rows


def section_text(text: str, section: str) -> str:
    lines: list[str] = []
    active = False
    for line in text.splitlines():
        if line.startswith("["):
            active = line == f"[{section}]"
            continue
        if active and line and not line.startswith("#"):
            lines.append(line)
    return "\n".join(lines)


def rows_for_table(
    rows: list[tuple[str, str, dict[str, str]]], table: str
) -> list[tuple[str, dict[str, str]]]:
    return [(key, columns) for name, key, columns in rows if name == table]


def require_event_audit_fact_shape(
    business_values_text: str,
    foreign_key_graph_text: str,
    create_effect_text: str,
) -> int:
    business_rows = parse_rendered_rows(business_values_text, "business-values.tsv")
    event_rows = rows_for_table(business_rows, EVENT_AUDIT_TABLE)
    if len(event_rows) != 1:
        raise ValidationError(
            "a frozen workflow-attribution answer must carry exactly one "
            "AD_WF_EventAudit business-values row"
        )

    event_key, event_columns = event_rows[0]
    missing = sorted(set(EVENT_AUDIT_REQUIRED_COLUMNS) - set(event_columns))
    if missing:
        raise ValidationError(
            "the frozen AD_WF_EventAudit row omits required attribution "
            f"columns: {missing}"
        )
    if not event_key.startswith("@ad_wf_eventaudit#"):
        raise ValidationError(
            "the frozen AD_WF_EventAudit identity is not capture-symbolized"
        )
    if event_columns.get(EVENT_AUDIT_KEY_COLUMN) != event_key:
        raise ValidationError(
            "the AD_WF_EventAudit body does not retain its symbolic identity"
        )
    process_symbol = event_columns["ad_wf_process_id"]
    if not process_symbol.startswith("@ad_wf_process#"):
        raise ValidationError(
            "AD_WF_EventAudit.AD_WF_Process_ID is not a symbolic process edge"
        )

    activity_rows = rows_for_table(business_rows, "ad_wf_activity")
    join = (process_symbol, event_columns["ad_wf_node_id"])
    if not any(
        (columns.get("ad_wf_process_id"), columns.get("ad_wf_node_id")) == join
        for _key, columns in activity_rows
    ):
        raise ValidationError(
            "AD_WF_EventAudit has no AD_WF_Activity with the same "
            "AD_WF_Process_ID/AD_WF_Node_ID"
        )

    graph_rows = set(data_lines_text(foreign_key_graph_text))
    if EVENT_AUDIT_REQUIRED_EDGE not in graph_rows:
        raise ValidationError(
            "foreign-key-graph.tsv omits the AD_WF_EventAudit process edge"
        )

    created_rows = parse_rendered_rows(
        section_text(create_effect_text, "created"),
        "effect-model/create.txt [created]",
    )
    created_event_rows = rows_for_table(created_rows, EVENT_AUDIT_TABLE)
    if len(created_event_rows) != 1:
        raise ValidationError(
            "effect-model/create.txt must compare the keyed AD_WF_EventAudit row"
        )
    created_key, created_columns = created_event_rows[0]
    if created_key != event_key:
        raise ValidationError(
            "the event-audit identity differs between create effect and final facts"
        )
    created_missing = sorted(
        set(EVENT_AUDIT_REQUIRED_COLUMNS) - set(created_columns)
    )
    if created_missing:
        raise ValidationError(
            "the create effect omits AD_WF_EventAudit attribution columns: "
            f"{created_missing}"
        )
    return len(event_columns)


def validate_event_audit_contract_texts(
    policy_text: str,
    measurement_scope_text: str,
    ambient_text: str,
    normalization_policy_text: str,
    business_values_text: str,
    foreign_key_graph_text: str,
    create_effect_text: str,
    amendment_readme_text: str,
) -> dict[str, int]:
    policy = load_event_audit_policy(policy_text)

    measurement_rows = [
        line.split("\t", 2)
        for line in data_lines_text(measurement_scope_text)
        if not line.startswith("table\t")
    ]
    event_scope = [
        row for row in measurement_rows if row[0].lower() == EVENT_AUDIT_TABLE
    ]
    expected_scope = [
        policy["table_name"],
        policy["key_column"],
        policy["measurement_predicate"],
    ]
    if event_scope != [expected_scope]:
        raise ValidationError(
            "measurement-scope.tsv must carry the exact keyed "
            f"AD_WF_EventAudit row: expected={expected_scope}, actual={event_scope}"
        )

    ambient_tables = {
        line.split("\t", 1)[0].lower()
        for line in data_lines_text(ambient_text)
        if not line.startswith("table_name\t")
    }
    if EVENT_AUDIT_TABLE in ambient_tables:
        raise ValidationError(
            "AD_WF_EventAudit attribution may not be classified ambient"
        )

    event_policy_lines = [
        line.lower()
        for line in normalization_policy_text.splitlines()
        if line.startswith("| `AD_WF_EventAudit` attribution:")
    ]
    if len(event_policy_lines) != 1:
        raise ValidationError(
            "normalization-policy.md must carry one AD_WF_EventAudit policy row"
        )
    policy_lower = event_policy_lines[0]
    missing_policy_columns = [
        column for column in EVENT_AUDIT_REQUIRED_COLUMNS if column not in policy_lower
    ]
    if missing_policy_columns:
        raise ValidationError(
            "normalization-policy.md omits required AD_WF_EventAudit columns: "
            f"{missing_policy_columns}"
        )
    for token in (
        "`ad_wf_eventaudit` attribution:",
        "treating the audit row as ambient",
        "complete normalized row",
    ):
        if token not in policy_lower:
            raise ValidationError(
                f"normalization-policy.md omits the event-audit control: {token}"
            )

    readme_lower = " ".join(amendment_readme_text.lower().split())
    for token in (
        EVENT_AUDIT_POLICY_FILE,
        "corrected-legacy candidate run",
        "exact generated facts are committed",
        "not accepted until",
        "separate freeze-off acceptance run",
    ):
        if token not in readme_lower:
            raise ValidationError(
                f"the amendment README weakens pending-acceptance status: {token}"
            )

    business_rows = parse_rendered_rows(business_values_text, "business-values.tsv")
    event_rows = rows_for_table(business_rows, EVENT_AUDIT_TABLE)
    graph_has_event = EVENT_AUDIT_REQUIRED_EDGE in set(
        data_lines_text(foreign_key_graph_text)
    )
    create_has_event = bool(
        rows_for_table(
            parse_rendered_rows(
                section_text(create_effect_text, "created"),
                "effect-model/create.txt [created]",
            ),
            EVENT_AUDIT_TABLE,
        )
    )

    if event_rows:
        fact_columns = require_event_audit_fact_shape(
            business_values_text,
            foreign_key_graph_text,
            create_effect_text,
        )
        return {
            "event_audit_scope_rows": len(event_scope),
            "event_audit_required_columns": len(EVENT_AUDIT_REQUIRED_COLUMNS),
            "event_audit_frozen_rows": len(event_rows),
            "event_audit_fact_columns": fact_columns,
        }

    if graph_has_event or create_has_event:
        raise ValidationError(
            "the pending answer has partial keyed AD_WF_EventAudit facts"
        )
    for table in ("ad_wf_process", "ad_wf_activity"):
        rows = rows_for_table(business_rows, table)
        if len(rows) != 1:
            raise ValidationError(
                f"the pending answer must retain exactly one {table} row"
            )
        _key, columns = rows[0]
        changed = {
            column: (expected, columns.get(column))
            for column, expected in PENDING_WORKFLOW_ATTRIBUTION.items()
            if columns.get(column) != expected
        }
        if changed:
            raise ValidationError(
                "workflow attribution changed without a keyed "
                f"AD_WF_EventAudit fact: table={table}, differences={changed}"
            )

    return {
        "event_audit_scope_rows": len(event_scope),
        "event_audit_required_columns": len(EVENT_AUDIT_REQUIRED_COLUMNS),
        "event_audit_frozen_rows": 0,
        "event_audit_fact_columns": 0,
    }


def load_python_module(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ValidationError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_event_audit_generator(
    repo_root: pathlib.Path, measurement_scope: pathlib.Path
) -> int:
    scripts_dir = repo_root / "scripts/phase5"
    derive = load_python_module(
        "phase5g1ay_derive_write_oracle_facts",
        scripts_dir / "derive-write-oracle-facts.py",
    )
    scope = derive.load_scope(measurement_scope)
    table_ids = derive.load_table_ids(
        repo_root / "contracts/legacy-web-write-v1/attribution-scope.tsv"
    )
    empty_scope = {entry["table"].lower(): {} for entry in scope}
    final_scope = {table: dict(rows) for table, rows in empty_scope.items()}
    final_scope["c_bpartner"]["800001"] = {
        "c_bpartner_id": 800001,
        "value": "P5G1A-GENERATOR-PROBE",
    }
    final_scope["ad_wf_process"]["700001"] = {
        "ad_wf_process_id": 700001,
        "ad_table_id": 291,
        "record_id": 800001,
    }
    final_scope["ad_wf_activity"]["700002"] = {
        "ad_wf_activity_id": 700002,
        "ad_wf_process_id": 700001,
        "ad_wf_node_id": 244,
        "ad_table_id": 291,
        "record_id": 800001,
    }
    final_scope[EVENT_AUDIT_TABLE]["700003"] = {
        EVENT_AUDIT_KEY_COLUMN: 700003,
        "ad_client_id": 11,
        "ad_org_id": 50001,
        "createdby": 101,
        "updatedby": 101,
        "ad_user_id": 101,
        "ad_wf_responsible_id": 100,
        "ad_wf_process_id": 700001,
        "ad_wf_node_id": 244,
        "ad_table_id": 291,
        "record_id": 800001,
        "eventtype": "PC",
        "wfstate": "OS",
        "created": "2026-09-03 09:40:08",
        "updated": "2026-09-03 09:40:08",
        "uuid": "11111111-2222-4333-8444-555555555555",
    }

    with tempfile.TemporaryDirectory(prefix="phase5g1ay-event-audit-") as temporary:
        root = pathlib.Path(temporary)
        snapshots = root / "snapshots"
        snapshots.mkdir()
        baseline = snapshots / "step-0.json"
        final = snapshots / "step-1.json"
        baseline.write_text(
            json.dumps({"scope": empty_scope, "sentinel": {}}, sort_keys=True),
            encoding="utf-8",
        )
        final.write_text(
            json.dumps({"scope": final_scope, "sentinel": {}}, sort_keys=True),
            encoding="utf-8",
        )
        ordered = [baseline, final]
        identities = derive.build_identities(ordered, scope, table_ids)
        business_rows = derive.business_values(ordered, scope, identities)
        graph_rows = derive.foreign_key_graph(ordered, scope, identities)
        event_rows = [
            row for row in business_rows if row.startswith(f"{EVENT_AUDIT_TABLE}\t")
        ]
        if len(event_rows) != 1:
            raise ValidationError(
                "the existing fact generator cannot render AD_WF_EventAudit"
            )
        create_effect = "[created]\n" + event_rows[0] + "\n[updated]\n"
        return require_event_audit_fact_shape(
            "\n".join(business_rows) + "\n",
            "\n".join(graph_rows) + "\n",
            create_effect,
        )


def load_contract(contract_dir: pathlib.Path) -> dict[str, str]:
    rows: dict[str, str] = {}
    for line in data_lines(contract_dir / "capture-contract.tsv"):
        fields = line.split("\t")
        if len(fields) != 2:
            raise ValidationError(f"invalid capture-contract row: {line}")
        key, value = fields
        if key in rows:
            raise ValidationError(f"duplicate capture-contract key: {key}")
        rows[key] = value
    required = {
        "source_commit",
        "patch_file",
        "patch_sha256",
        "mode",
        "consumer_pr",
        "provenance_schema_version",
    }
    if set(rows) != required:
        raise ValidationError(
            "capture-contract keys differ: "
            f"missing={sorted(required - set(rows))}, "
            f"unexpected={sorted(set(rows) - required)}"
        )
    if not HEX_40.fullmatch(rows["source_commit"]):
        raise ValidationError("source_commit must be a full lowercase Git object id")
    if not HEX_64.fullmatch(rows["patch_sha256"]):
        raise ValidationError("patch_sha256 must be a lowercase SHA-256")
    if rows["mode"] != "corrected-legacy-workflow-attribution":
        raise ValidationError("the corrected-legacy mode changed")
    if rows["consumer_pr"] != "https://github.com/samqbush/adempiere2/pull/18":
        raise ValidationError("the production consumer must remain PR 18")
    if rows["provenance_schema_version"] != "1":
        raise ValidationError("unsupported provenance schema version")
    return rows


def expected_manifest(contract_dir: pathlib.Path) -> dict[str, str]:
    return {
        path.relative_to(contract_dir).as_posix(): sha256(path)
        for path in sorted(contract_dir.rglob("*"))
        if path.is_file() and path.name != MANIFEST_NAME
    }


def read_manifest(contract_dir: pathlib.Path) -> dict[str, str]:
    manifest = contract_dir / MANIFEST_NAME
    if not manifest.is_file():
        raise ValidationError(f"missing {manifest}")
    rows: dict[str, str] = {}
    for line in data_lines(manifest):
        fields = line.split("\t")
        if len(fields) != 2 or not HEX_64.fullmatch(fields[0]):
            raise ValidationError(f"invalid manifest row: {line}")
        digest, relative = fields
        if relative in rows:
            raise ValidationError(f"duplicate manifest path: {relative}")
        rows[relative] = digest
    return rows


def verify_manifest(contract_dir: pathlib.Path) -> int:
    expected = expected_manifest(contract_dir)
    recorded = read_manifest(contract_dir)
    if expected != recorded:
        missing = sorted(set(expected) - set(recorded))
        unexpected = sorted(set(recorded) - set(expected))
        altered = sorted(
            path
            for path in set(expected) & set(recorded)
            if expected[path] != recorded[path]
        )
        raise ValidationError(
            "contract manifest differs: "
            f"missing={missing}, unexpected={unexpected}, altered={altered}"
        )
    return len(expected)


def write_manifest(contract_dir: pathlib.Path) -> None:
    rows = expected_manifest(contract_dir)
    manifest = contract_dir / MANIFEST_NAME
    lines = [
        "# Phase 5g-1a-y corrected-legacy workflow-attribution contract manifest",
        "# SHA-256 over every file in this directory except this file.",
        "# sha256\tpath",
    ]
    lines.extend(f"{digest}\t{path}" for path, digest in rows.items())
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_patch_paths(patch_text: str) -> list[str]:
    paths: list[str] = []
    for line in patch_text.splitlines():
        if not line.startswith("diff --git a/"):
            continue
        match = re.fullmatch(r"diff --git a/(\S+) b/(\S+)", line)
        if not match or match.group(1) != match.group(2):
            raise ValidationError(f"invalid or renaming patch header: {line}")
        paths.append(match.group(1))
    if not paths:
        raise ValidationError("patch contains no diff headers")
    if len(paths) != len(set(paths)):
        raise ValidationError("patch contains duplicate path headers")
    return paths


def parse_patch_hunks(patch_text: str) -> list[tuple[str, str, list[str]]]:
    hunks: list[tuple[str, str, list[str]]] = []
    current_path: str | None = None
    current_changes: list[str] | None = None
    for line in patch_text.splitlines():
        if line.startswith("diff --git a/"):
            match = re.fullmatch(r"diff --git a/(\S+) b/(\S+)", line)
            if not match or match.group(1) != match.group(2):
                raise ValidationError(f"invalid or renaming patch header: {line}")
            current_path = match.group(1)
            current_changes = None
            continue
        if line.startswith("@@ "):
            if current_path is None:
                raise ValidationError(f"patch hunk has no path: {line}")
            current_changes = []
            hunks.append((current_path, line, current_changes))
            continue
        if (
            current_changes is not None
            and (line.startswith("+") or line.startswith("-"))
            and not line.startswith("+++")
            and not line.startswith("---")
        ):
            payload = line[1:].strip()
            if payload and not payload.startswith(("*", "/**", "*/")):
                current_changes.append(line)
    return hunks


def validate_patch_semantics(patch_text: str, allowed_paths: Iterable[str]) -> None:
    paths = parse_patch_paths(patch_text)
    allowed = list(allowed_paths)
    if paths != allowed:
        raise ValidationError(f"patch paths {paths} do not equal allowed paths {allowed}")

    hunks = parse_patch_hunks(patch_text)
    if hunks != EXPECTED_PATCH_HUNKS:
        raise ValidationError(
            "patch hunks or executable changed-line set differ from the reviewed "
            f"workflow-attribution patch: expected={EXPECTED_PATCH_HUNKS}, actual={hunks}"
        )

    transaction_terms = (
        "setTransactionName",
        "getTransactionName",
        "setSavepoint",
        "releaseSavepoint",
        "commit(",
        "rollback(",
        "lockDocument",
        "unlockDocument",
    )
    changed_lines = [
        line
        for line in patch_text.splitlines()
        if (line.startswith("+") or line.startswith("-"))
        and not line.startswith("+++")
        and not line.startswith("---")
    ]
    transaction_changes = [
        line for line in changed_lines if any(term in line for term in transaction_terms)
    ]
    if transaction_changes:
        raise ValidationError(
            "the capture patch must not change transaction propagation: "
            f"{transaction_changes}"
        )


def require_ordered_tokens(label: str, text: str, tokens: Iterable[str]) -> None:
    cursor = 0
    for token in tokens:
        position = text.find(token, cursor)
        if position < 0:
            raise ValidationError(f"{label} is missing ordered control: {token}")
        cursor = position + len(token)


def validate_materializer_semantics(materializer_text: str) -> None:
    required = [
        "build/phase5g1ay/corrected-source",
        "build/phase5g1ay/corrected-runtime",
        "git apply --ignore-whitespace",
        "removed-signature-entries.txt",
        'zip -q -d "$corrected_jar" "${expected_signatures[@]}"',
        "stale signature entries remain in corrected Adempiere.jar",
        "ordinary_repository_unchanged",
    ]
    absent = [token for token in required if token not in materializer_text]
    if absent:
        raise ValidationError(f"corrected runtime materializer is incomplete: {absent}")
    require_ordered_tokens(
        "corrected runtime signature removal",
        materializer_text,
        [
            'done <"$contract_dir/removed-signature-entries.txt"',
            'actual_signatures+=("$signature")',
            'if [[ "${actual_signatures[*]}" != "${expected_signatures[*]}" ]]; then',
            'zip -q -d "$corrected_jar" "${expected_signatures[@]}"',
            '''if "$java_home/bin/jar" --list --file "$corrected_jar" \\
  | grep -Eq '^META-INF/[^/]+\\.(SF|RSA|DSA|EC)$'
then''',
            "stale signature entries remain in corrected Adempiere.jar",
        ],
    )
    require_ordered_tokens(
        "corrected runtime materializer",
        materializer_text,
        [
            'if ! git -C "$repo_root" cat-file -e "${source_commit}^{commit}"',
            'git -C "$repo_root" fetch --no-tags --depth=1 origin "$source_commit"',
            'git -C "$repo_root" cat-file -e "${source_commit}^{commit}"',
            'python3 "$repo_root/scripts/phase5/validate-phase5g1ay-oracle.py"',
        ],
    )
    required_cleanup = [
        'owner_marker="$repo_root/build/phase5g1ay/corrected-source.owner"',
        "trap cleanup_on_exit EXIT",
        "worktree_owned=1",
        "owner_marker_matches",
        'if [[ "$worktree_owned" -ne 1 ]] && ! owner_marker_matches; then',
    ]
    absent_cleanup = [
        token for token in required_cleanup if token not in materializer_text
    ]
    if absent_cleanup:
        raise ValidationError(
            f"corrected runtime cleanup is not ownership-bounded: {absent_cleanup}"
        )
    remove_lines = [
        line.strip()
        for line in materializer_text.splitlines()
        if "worktree remove" in line
    ]
    expected_remove_line = (
        'if ! git -C "$repo_root" worktree remove --force "$worktree_root"; then'
    )
    if remove_lines != [expected_remove_line]:
        raise ValidationError(
            "corrected runtime cleanup must fail closed around one exact worktree "
            f"unregister command: {remove_lines}"
        )
    forbidden_cleanup = [
        "worktree prune",
        'rm -rf "$worktree_root"',
    ]
    unsafe_cleanup = [
        token for token in forbidden_cleanup if token in materializer_text
    ]
    if unsafe_cleanup:
        raise ValidationError(
            f"corrected runtime cleanup suppresses or widens failure: {unsafe_cleanup}"
        )
    require_ordered_tokens(
        "corrected runtime ownership",
        materializer_text,
        [
            "cleanup_owned_worktree",
            "trap cleanup_on_exit EXIT",
            'git -C "$repo_root" worktree add --detach --force "$worktree_root" "$source_commit"',
            "worktree_owned=1",
            '>"$owner_marker"',
        ],
    )


def validate_runtime_guard_semantics(
    smoke_text: str, guard_text: str, gradle_text: str
) -> None:
    if smoke_text.count('bash "$runtime_guard_script" activate') != 1:
        raise ValidationError("write-oracle smoke must activate through the guard once")
    if smoke_text.count('bash "$runtime_guard_script" restore') != 2:
        raise ValidationError(
            "write-oracle smoke must restore through both the EXIT trap and pre-score path"
        )
    require_ordered_tokens(
        "write-oracle smoke",
        smoke_text,
        [
            "trap restore_corrected_runtime EXIT",
            'bash "$runtime_guard_script" activate',
            'bash "$scripts_dir/run-write-oracle-lane.sh"',
            'validate_corrected_workflow_capture "$evidence_root/A/business-values.tsv"',
            'validate_corrected_workflow_capture "$evidence_root/B/business-values.tsv"',
            'bash "$runtime_guard_script" restore',
            "trap - EXIT",
            'python3 "$scripts_dir/score-write-oracle-capture.py"',
        ],
    )
    required_helper_tokens = [
        """rows=$(awk -F'\\t' -v table="$table" '$1 == table { count++ } END { print count + 0 }' "$facts")""",
        'if [[ "$rows" -ne 1 ]]; then',
        """identity=$(awk -F'\\t' -v table="$table" '$1 == table { print $2 }' "$facts")""",
        'if [[ "$identity" != "$expected_identity" ]]; then',
        """if [[ ",$row," != *",$field,"* ]]; then""",
    ]
    require_ordered_tokens(
        "corrected workflow capture helper",
        smoke_text,
        required_helper_tokens,
    )
    required_capture_blocks = [
        """require_fact_fields "$facts" ad_wf_process @ad_wf_process#1 \\
    ad_client_id=11 createdby=101 updatedby=101 ad_user_id=101 \\
    ad_wf_process_id=@ad_wf_process#1 record_id=@c_bpartner#1""",
        """require_fact_fields "$facts" ad_wf_activity @ad_wf_activity#1 \\
    ad_client_id=11 createdby=101 updatedby=101 \\
    ad_wf_activity_id=@ad_wf_activity#1 \\
    ad_wf_process_id=@ad_wf_process#1 record_id=@c_bpartner#1""",
        """require_fact_fields "$facts" ad_wf_eventaudit @ad_wf_eventaudit#1 \\
    ad_client_id=11 createdby=101 updatedby=101 \\
    ad_wf_eventaudit_id=@ad_wf_eventaudit#1 \\
    ad_wf_process_id=@ad_wf_process#1 record_id=@c_bpartner#1""",
    ]
    absent_capture_blocks = [
        block for block in required_capture_blocks if block not in smoke_text
    ]
    if absent_capture_blocks:
        raise ValidationError(
            "corrected workflow capture guard is incomplete: "
            f"{absent_capture_blocks}"
        )
    if 'cp "$corrected_jar" "$target.tmp"' in smoke_text:
        raise ValidationError("write-oracle smoke bypasses the independent runtime guard")

    required_guard = [
        'sha256_of "$target" >"$guard_dir/$index/sha256"',
        'if [[ ! -f "$expected" || "$(sha256_of "$target")" != "$(cat "$expected")" ]]; then',
        'cp "$corrected_jar" "$target.tmp"',
        'cp "$backup" "$target.tmp"',
        'ordinary runtime was not restored byte-for-byte',
        'if [[ "$cleanup" == "--cleanup" ]]',
    ]
    absent_guard = [token for token in required_guard if token not in guard_text]
    if absent_guard:
        raise ValidationError(
            f"independent ordinary-runtime guard is incomplete: {absent_guard}"
        )

    require_ordered_tokens(
        "Gradle ordinary-runtime guard",
        gradle_text,
        [
            "tasks.register('snapshotPhase5g1ayOrdinaryRuntime', Exec)",
            "dependsOn tasks.named('phase3AntDatabaseBuild')",
            "tasks.register('restorePhase5g1ayOrdinaryRuntime', Exec)",
            "tasks.register('phase5g1aLegacyWriteOracleSmoke', Exec)",
            "dependsOn snapshotPhase5g1ayOrdinaryRuntime",
            "finalizedBy restorePhase5g1ayOrdinaryRuntime",
        ],
    )


def run_git(repo_root: pathlib.Path, *args: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repo_root), *args],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and completed.returncode != 0:
        raise ValidationError(
            f"git {' '.join(args)} failed: {completed.stderr.strip()}"
        )
    return completed.stdout


def parse_porcelain_v1_z(output: str) -> set[str]:
    paths: set[str] = set()
    for record in output.split("\0"):
        if not record:
            continue
        if len(record) < 4 or record[2] != " ":
            raise ValidationError(f"invalid git status record: {record!r}")
        status = record[:2]
        relative = record[3:]
        if "R" in status or "C" in status:
            raise ValidationError(
                f"oracle branch working tree may not rename or copy paths: {record!r}"
            )
        if relative in paths:
            raise ValidationError(f"duplicate git status path: {relative}")
        paths.add(relative)
    return paths


def validate_repository_scope(
    committed_paths: set[str],
    committed_protected_paths: set[str],
    working_tree_paths: set[str],
    allowed_branch_paths: set[str],
    working_tree_exclusions: Iterable[str] = WORKTREE_MUTATION_EXCLUSIONS,
) -> tuple[set[str], set[str]]:
    exclusions = tuple(working_tree_exclusions)
    if exclusions != WORKTREE_MUTATION_EXCLUSIONS:
        raise ValidationError(
            "working-tree mutation exclusions must remain exactly the two "
            f"documented Ant-regenerated outputs: {list(WORKTREE_MUTATION_EXCLUSIONS)}"
        )
    if committed_protected_paths:
        raise ValidationError(
            "the committed oracle branch changes protected production or artifact "
            f"paths: {sorted(committed_protected_paths)}"
        )

    excluded_worktree_paths = working_tree_paths & set(exclusions)
    visible_worktree_paths = working_tree_paths - set(exclusions)
    actual_branch_paths = committed_paths | visible_worktree_paths
    if actual_branch_paths != allowed_branch_paths:
        raise ValidationError(
            "oracle branch path set differs from its exact allowlist: "
            f"missing={sorted(allowed_branch_paths - actual_branch_paths)}, "
            f"unexpected={sorted(actual_branch_paths - allowed_branch_paths)}"
        )
    return visible_worktree_paths, excluded_worktree_paths


def validate_repository(repo_root: pathlib.Path, contract_dir: pathlib.Path) -> dict[str, int]:
    contract = load_contract(contract_dir)
    manifest_count = verify_manifest(contract_dir)
    patch = contract_dir / contract["patch_file"]
    allowed_paths = data_lines(contract_dir / "allowed-patched-paths.txt")
    protected_paths = data_lines(contract_dir / "protected-repository-paths.txt")
    allowed_branch_paths = data_lines(contract_dir / "oracle-branch-allowed-paths.txt")

    if sha256(patch) != contract["patch_sha256"]:
        raise ValidationError("patch SHA-256 does not match capture-contract.tsv")
    patch_text = patch.read_text(encoding="utf-8")
    validate_patch_semantics(patch_text, allowed_paths)

    run_git(repo_root, "cat-file", "-e", f"{contract['source_commit']}^{{commit}}")
    for relative in allowed_paths:
        current = subprocess.run(
            ["git", "-C", str(repo_root), "show", f"HEAD:{relative}"],
            check=True,
            stdout=subprocess.PIPE,
        ).stdout
        source = subprocess.run(
            ["git", "-C", str(repo_root), "show", f"{contract['source_commit']}:{relative}"],
            check=True,
            stdout=subprocess.PIPE,
        ).stdout
        if current != source:
            raise ValidationError(
                f"checked-out production source differs from source_commit: {relative}"
            )

    committed_protected_paths = set(
        data_lines_from_output(
            run_git(
                repo_root,
                "diff",
                "--name-only",
                contract["source_commit"],
                "HEAD",
                "--",
                *protected_paths,
            )
        )
    )
    committed_paths = set(
        data_lines_from_output(
            run_git(
                repo_root,
                "diff",
                "--name-only",
                contract["source_commit"],
                "HEAD",
                "--",
            )
        )
    )
    working_tree_paths = parse_porcelain_v1_z(
        run_git(
            repo_root,
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none",
        )
    )
    visible_worktree_paths, excluded_worktree_paths = validate_repository_scope(
        committed_paths,
        committed_protected_paths,
        working_tree_paths,
        set(allowed_branch_paths),
    )

    apply_check = subprocess.run(
        [
            "git",
            "-C",
            str(repo_root),
            "apply",
            "--check",
            "--ignore-whitespace",
            str(patch),
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if apply_check.returncode != 0:
        raise ValidationError(f"patch does not apply to source_commit: {apply_check.stderr}")

    packaging_files = [
        repo_root / "install/build.xml",
        repo_root / "install/Adempiere/build.xml",
        repo_root / "scripts/phase3/run-isolated-ant-build.sh",
    ]
    packaging_hits = [
        path.relative_to(repo_root).as_posix()
        for path in packaging_files
        if "phase5g1ay" in path.read_text(encoding="utf-8", errors="replace")
        or contract["patch_file"] in path.read_text(encoding="utf-8", errors="replace")
    ]
    if packaging_hits:
        raise ValidationError(
            f"corrected-legacy inputs leaked into ordinary packaging: {packaging_hits}"
        )

    materializer = (
        repo_root / "scripts/phase5/materialize-phase5g1ay-corrected-runtime.sh"
    )
    materializer_text = materializer.read_text(encoding="utf-8")
    validate_materializer_semantics(materializer_text)
    forbidden_materializer = [
        "build/phase3/release",
        "install/build/Adempiere_",
        "data/seed/Adempiere.jar",
    ]
    leaked = [token for token in forbidden_materializer if token in materializer_text]
    if leaked:
        raise ValidationError(
            f"corrected runtime materializer targets ordinary shipped outputs: {leaked}"
        )

    smoke_text = (repo_root / "scripts/phase5/run-write-oracle-smoke.sh").read_text(
        encoding="utf-8"
    )
    guard_text = (
        repo_root / "scripts/phase5/guard-phase5g1ay-ordinary-runtime.sh"
    ).read_text(encoding="utf-8")
    gradle_text = (repo_root / "gradle/phase5/write-oracle.gradle").read_text(
        encoding="utf-8"
    )
    validate_runtime_guard_semantics(smoke_text, guard_text, gradle_text)

    legacy_contract = repo_root / "contracts/legacy-web-write-v1"
    event_audit_counts = validate_event_audit_contract_texts(
        (contract_dir / EVENT_AUDIT_POLICY_FILE).read_text(encoding="utf-8"),
        (legacy_contract / "measurement-scope.tsv").read_text(encoding="utf-8"),
        (legacy_contract / "ambient-tables.tsv").read_text(encoding="utf-8"),
        (legacy_contract / "normalization-policy.md").read_text(encoding="utf-8"),
        (legacy_contract / "business-values.tsv").read_text(encoding="utf-8"),
        (legacy_contract / "foreign-key-graph.tsv").read_text(encoding="utf-8"),
        (legacy_contract / "effect-model/create.txt").read_text(encoding="utf-8"),
        (contract_dir / "README.md").read_text(encoding="utf-8"),
    )
    generator_columns = validate_event_audit_generator(
        repo_root, legacy_contract / "measurement-scope.tsv"
    )

    return {
        "manifest_files": manifest_count,
        "patched_paths": len(allowed_paths),
        "protected_paths": len(protected_paths),
        "oracle_branch_paths": len(allowed_branch_paths),
        "committed_branch_paths": len(committed_paths),
        "working_tree_paths": len(visible_worktree_paths),
        "excluded_worktree_outputs": len(excluded_worktree_paths),
        **event_audit_counts,
        "event_audit_generator_columns": generator_columns,
    }


def data_lines_from_output(output: str) -> list[str]:
    return [line for line in output.splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True, type=pathlib.Path)
    parser.add_argument("--contract-dir", required=True, type=pathlib.Path)
    parser.add_argument("--summary", type=pathlib.Path)
    parser.add_argument("--write-manifest", action="store_true")
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    contract_dir = args.contract_dir.resolve()
    try:
        if args.write_manifest:
            load_contract(contract_dir)
            write_manifest(contract_dir)
            return 0
        counts = validate_repository(repo_root, contract_dir)
        if args.summary:
            args.summary.parent.mkdir(parents=True, exist_ok=True)
            args.summary.write_text(
                "check\tvalue\n"
                + "".join(f"{key}\t{value}\n" for key, value in counts.items()),
                encoding="utf-8",
            )
        print(
            "Phase 5g-1a-y contract verified: "
            f"{counts['manifest_files']} manifest files, "
            f"{counts['patched_paths']} exact patched paths, "
            f"{counts['protected_paths']} protected roots, "
            f"{counts['oracle_branch_paths']} exact branch paths"
        )
        return 0
    except (OSError, subprocess.CalledProcessError, ValidationError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
