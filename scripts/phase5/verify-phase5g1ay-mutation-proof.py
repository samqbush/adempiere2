#!/usr/bin/env python3
"""Prove the Phase 5g-1a-y neutral validators fail closed."""

from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import pathlib
import shutil
import tempfile


class MutationConstructionError(RuntimeError):
    pass


def load_module(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True, type=pathlib.Path)
    parser.add_argument("--report", required=True, type=pathlib.Path)
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    scripts = repo_root / "scripts/phase5"
    contract_dir = repo_root / "contracts/phase5g1ay-workflow-attribution-v1"
    oracle = load_module("phase5g1ay_oracle", scripts / "validate-phase5g1ay-oracle.py")
    provenance = load_module(
        "phase5g1ay_provenance", scripts / "validate-phase5g1ay-provenance.py"
    )
    allowed = oracle.data_lines(contract_dir / "allowed-patched-paths.txt")
    allowed_branch_paths = set(
        oracle.data_lines(contract_dir / "oracle-branch-allowed-paths.txt")
    )
    patch = (contract_dir / "corrected-legacy-workflow-attribution.patch").read_text(
        encoding="utf-8"
    )
    materializer_text = (
        scripts / "materialize-phase5g1ay-corrected-runtime.sh"
    ).read_text(encoding="utf-8")
    smoke_text = (scripts / "run-write-oracle-smoke.sh").read_text(encoding="utf-8")
    guard_text = (
        scripts / "guard-phase5g1ay-ordinary-runtime.sh"
    ).read_text(encoding="utf-8")
    gradle_text = (repo_root / "gradle/phase5/write-oracle.gradle").read_text(
        encoding="utf-8"
    )
    legacy_contract = repo_root / "contracts/legacy-web-write-v1"
    policy_text = (
        contract_dir / oracle.EVENT_AUDIT_POLICY_FILE
    ).read_text(encoding="utf-8")
    measurement_scope_text = (
        legacy_contract / "measurement-scope.tsv"
    ).read_text(encoding="utf-8")
    ambient_text = (legacy_contract / "ambient-tables.tsv").read_text(
        encoding="utf-8"
    )
    normalization_policy_text = (
        legacy_contract / "normalization-policy.md"
    ).read_text(encoding="utf-8")
    business_values_text = (legacy_contract / "business-values.tsv").read_text(
        encoding="utf-8"
    )
    foreign_key_graph_text = (
        legacy_contract / "foreign-key-graph.tsv"
    ).read_text(encoding="utf-8")
    create_effect_text = (
        legacy_contract / "effect-model/create.txt"
    ).read_text(encoding="utf-8")
    amendment_readme_text = (contract_dir / "README.md").read_text(
        encoding="utf-8"
    )
    fixture = json.loads(
        (contract_dir / "provenance-schema-fixture.json").read_text(encoding="utf-8")
    )
    fixture["repository_head"] = provenance.repository_head(repo_root)

    rows: list[tuple[str, str]] = []

    def must_fail(name: str, action) -> None:
        try:
            action()
        except MutationConstructionError:
            raise
        except Exception:
            rows.append((name, "detected"))
            return
        raise RuntimeError(f"mutation was not detected: {name}")

    def replace_once(text: str, old: str, new: str) -> str:
        if text.count(old) != 1:
            raise MutationConstructionError(
                f"mutation source must occur exactly once: {old!r}, "
                f"found {text.count(old)}"
            )
        return text.replace(old, new, 1)

    def validate_event_audit(
        *,
        policy: str = policy_text,
        measurement: str = measurement_scope_text,
        ambient: str = ambient_text,
        normalization: str = normalization_policy_text,
        business_values: str = business_values_text,
        foreign_key_graph: str = foreign_key_graph_text,
        create_effect: str = create_effect_text,
        readme: str = amendment_readme_text,
    ) -> None:
        oracle.validate_event_audit_contract_texts(
            policy,
            measurement,
            ambient,
            normalization,
            business_values,
            foreign_key_graph,
            create_effect,
            readme,
        )

    event_scope_line = (
        "AD_WF_EventAudit\t"
        f"{oracle.EVENT_AUDIT_KEY_COLUMN}\t{oracle.EVENT_AUDIT_PREDICATE}"
    )
    event_line = next(
        line
        for line in business_values_text.splitlines()
        if line.startswith("ad_wf_eventaudit\t@ad_wf_eventaudit#1\t")
    )
    validate_event_audit()

    must_fail(
        "document-manager-uses-cached-workflow-context",
        lambda: oracle.validate_patch_semantics(
            patch.replace("wf.start(document.getCtx(), pi)", "wf.start(wf.getCtx(), pi)"),
            allowed,
        ),
    )
    must_fail(
        "existing-start-stops-delegating",
        lambda: oracle.validate_patch_semantics(
            patch.replace(
                "return start(getCtx(), processInfo);", "return start(processInfo);"
            ),
            allowed,
        ),
    )
    must_fail(
        "compatibility-overload-changes-getctx-delegation",
        lambda: oracle.validate_patch_semantics(
            replace_once(
                patch,
                "return start(getCtx(), processInfo);",
                "return start(new Properties(), processInfo);",
            ),
            allowed,
        ),
    )
    must_fail(
        "patch-adds-unreviewed-side-effect",
        lambda: oracle.validate_patch_semantics(
            replace_once(
                patch,
                "+\t\treturn start(getCtx(), processInfo);",
                '+\t\tSystem.setProperty("phase5g1ay", "true");\n'
                "+\t\treturn start(getCtx(), processInfo);",
            ),
            allowed,
        ),
    )
    must_fail(
        "process-superclass-uses-workflow-context",
        lambda: oracle.validate_patch_semantics(
            patch.replace(
                "super (invocationCtx, 0, trxName);",
                "super (wf.getCtx(), 0, trxName);",
            ),
            allowed,
        ),
    )
    must_fail(
        "patch-expands-allowed-source-set",
        lambda: oracle.validate_patch_semantics(
            patch
            + "\ndiff --git a/base/src/org/compiere/wf/MWFActivity.java "
            + "b/base/src/org/compiere/wf/MWFActivity.java\n",
            allowed,
        ),
    )
    must_fail(
        "materializer-validates-before-ensuring-source-commit",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                'git -C "$repo_root" fetch --no-tags --depth=1 origin "$source_commit"',
                ': "source fetch bypassed"',
            )
        ),
    )
    must_fail(
        "materializer-suppresses-worktree-unregister-failure",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                'if ! git -C "$repo_root" worktree remove --force "$worktree_root"; then',
                'git -C "$repo_root" worktree remove --force "$worktree_root" || true',
            )
        ),
    )
    must_fail(
        "materializer-prunes-repository-wide-worktree-state",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                "cleanup_owned_worktree() {",
                'cleanup_owned_worktree() {\n  git -C "$repo_root" worktree prune',
            )
        ),
    )
    must_fail(
        "materializer-recursively-deletes-worktree-residue",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                "cleanup_owned_worktree() {",
                'cleanup_owned_worktree() {\n  rm -rf "$worktree_root"',
            )
        ),
    )
    must_fail(
        "materializer-unregisters-unexpected-worktree-path",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                'if ! git -C "$repo_root" worktree remove --force "$worktree_root"; then',
                'if ! git -C "$repo_root" worktree remove --force '
                '"$(dirname "$worktree_root")"; then',
            )
        ),
    )
    must_fail(
        "materializer-bypasses-worktree-ownership-check",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                'if [[ "$worktree_owned" -ne 1 ]] && ! owner_marker_matches; then',
                'if [[ "$worktree_owned" -ne 1 ]] && false; then',
            )
        ),
    )
    must_fail(
        "materializer-claims-ownership-before-worktree-add",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                """trap cleanup_on_exit EXIT
git -C "$repo_root" worktree add --detach --force "$worktree_root" "$source_commit" >/dev/null
worktree_owned=1""",
                """trap cleanup_on_exit EXIT
worktree_owned=1
git -C "$repo_root" worktree add --detach --force "$worktree_root" "$source_commit" >/dev/null""",
            )
        ),
    )
    must_fail(
        "materializer-leaves-stale-jar-signatures",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                'zip -q -d "$corrected_jar" "${expected_signatures[@]}"',
                ': "signature removal bypassed"',
            )
        ),
    )
    must_fail(
        "materializer-bypasses-signature-inventory-comparison",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                'if [[ "${actual_signatures[*]}" != "${expected_signatures[*]}" ]]; then',
                "if false; then",
            )
        ),
    )
    must_fail(
        "materializer-skips-signature-removal-verification",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                "FAIL: stale signature entries remain in corrected Adempiere.jar",
                "stale signatures ignored",
            )
        ),
    )
    must_fail(
        "materializer-bypasses-signature-postcheck-predicate",
        lambda: oracle.validate_materializer_semantics(
            replace_once(
                materializer_text,
                '''if "$java_home/bin/jar" --list --file "$corrected_jar" \\
  | grep -Eq '^META-INF/[^/]+\\.(SF|RSA|DSA|EC)$'
then''',
                "if false; then",
            )
        ),
    )

    visible, excluded = oracle.validate_repository_scope(
        set(),
        set(),
        allowed_branch_paths | set(oracle.WORKTREE_MUTATION_EXCLUSIONS),
        allowed_branch_paths,
    )
    if visible != allowed_branch_paths or excluded != set(
        oracle.WORKTREE_MUTATION_EXCLUSIONS
    ):
        raise RuntimeError("documented working-tree exclusions were not exact")
    must_fail(
        "working-tree-unreviewed-mutation-is-ignored",
        lambda: oracle.validate_repository_scope(
            set(),
            set(),
            allowed_branch_paths | {"unreviewed-working-tree-file.txt"},
            allowed_branch_paths,
        ),
    )
    must_fail(
        "working-tree-exclusion-set-is-expanded",
        lambda: oracle.validate_repository_scope(
            set(),
            set(),
            allowed_branch_paths | {"unreviewed-working-tree-file.txt"},
            allowed_branch_paths,
            (
                *oracle.WORKTREE_MUTATION_EXCLUSIONS,
                "unreviewed-working-tree-file.txt",
            ),
        ),
    )
    must_fail(
        "committed-regenerated-output-loses-protection",
        lambda: oracle.validate_repository_scope(
            allowed_branch_paths,
            {"lib/ADInterface-1.0.war"},
            set(),
            allowed_branch_paths,
        ),
    )

    must_fail(
        "runtime-exit-restoration-trap-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                "trap restore_corrected_runtime EXIT",
                "trap - EXIT",
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "runtime-pre-score-restoration-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                """if [[ "$runtime_mode" == corrected-legacy-workflow-attribution ]]; then
  validate_corrected_workflow_capture "$evidence_root/A/business-values.tsv"
  validate_corrected_workflow_capture "$evidence_root/B/business-values.tsv"
  bash "$runtime_guard_script" restore \\
    "$repo_root" "$installed_home" "$runtime_guard_dir"
  trap - EXIT""",
                """if [[ "$runtime_mode" == corrected-legacy-workflow-attribution ]]; then
  validate_corrected_workflow_capture "$evidence_root/A/business-values.tsv"
  validate_corrected_workflow_capture "$evidence_root/B/business-values.tsv"
  : "pre-score restoration bypassed"
  trap - EXIT""",
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "runtime-gradle-finalizer-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            smoke_text,
            guard_text,
            replace_once(
                gradle_text,
                "finalizedBy restorePhase5g1ayOrdinaryRuntime",
                "dependsOn restorePhase5g1ayOrdinaryRuntime",
            ),
        ),
    )
    must_fail(
        "runtime-activation-pristine-check-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            smoke_text,
            replace_once(
                guard_text,
                'if [[ ! -f "$expected" || "$(sha256_of "$target")" != "$(cat "$expected")" ]]; then',
                'if [[ "$target" == "$target" ]]; then',
            ),
            gradle_text,
        ),
    )
    must_fail(
        "corrected-capture-workflow-row-guard-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                'validate_corrected_workflow_capture "$evidence_root/A/business-values.tsv"',
                ': "corrected workflow capture guard bypassed"',
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "corrected-capture-row-count-check-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                'if [[ "$rows" -ne 1 ]]; then',
                "if false; then",
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "corrected-capture-identity-check-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                'if [[ "$identity" != "$expected_identity" ]]; then',
                "if false; then",
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "corrected-capture-field-check-bypassed",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                'if [[ ",$row," != *",$field,"* ]]; then',
                "if false; then",
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "corrected-process-attribution-check-weakened",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                "ad_client_id=11 createdby=101 updatedby=101 ad_user_id=101",
                "ad_client_id=0 createdby=0 updatedby=0 ad_user_id=101",
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "corrected-activity-attribution-check-weakened",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                """require_fact_fields "$facts" ad_wf_activity @ad_wf_activity#1 \\
    ad_client_id=11 createdby=101 updatedby=101 \\""",
                """require_fact_fields "$facts" ad_wf_activity @ad_wf_activity#1 \\
    ad_client_id=0 createdby=0 updatedby=0 \\""",
            ),
            guard_text,
            gradle_text,
        ),
    )
    must_fail(
        "corrected-event-audit-attribution-check-weakened",
        lambda: oracle.validate_runtime_guard_semantics(
            replace_once(
                smoke_text,
                """require_fact_fields "$facts" ad_wf_eventaudit @ad_wf_eventaudit#1 \\
    ad_client_id=11 createdby=101 updatedby=101 \\""",
                """require_fact_fields "$facts" ad_wf_eventaudit @ad_wf_eventaudit#1 \\
    ad_client_id=0 createdby=0 updatedby=0 \\""",
            ),
            guard_text,
            gradle_text,
        ),
    )

    must_fail(
        "event-audit-keyed-scope-dropped",
        lambda: validate_event_audit(
            measurement=replace_once(
                measurement_scope_text,
                event_scope_line + "\n",
                "",
            )
        ),
    )
    must_fail(
        "event-audit-key-identity-weakened",
        lambda: validate_event_audit(
            measurement=replace_once(
                measurement_scope_text,
                oracle.EVENT_AUDIT_KEY_COLUMN,
                "ad_wf_process_id",
            )
        ),
    )
    must_fail(
        "event-audit-predicate-loses-activity-join",
        lambda: validate_event_audit(
            measurement=replace_once(
                measurement_scope_text,
                oracle.EVENT_AUDIT_PREDICATE,
                "AD_WF_Process_ID > 0",
            )
        ),
    )
    must_fail(
        "event-audit-reclassified-ambient",
        lambda: validate_event_audit(
            ambient=ambient_text.rstrip()
            + "\nad_wf_eventaudit\treclassified by mutation\n"
        ),
    )
    must_fail(
        "event-audit-required-column-dropped-from-policy",
        lambda: validate_event_audit(
            policy=replace_once(
                policy_text,
                "+createdby",
                "",
            )
        ),
    )
    must_fail(
        "event-audit-normalization-policy-omits-createdby",
        lambda: validate_event_audit(
            normalization=replace_once(
                normalization_policy_text,
                ", `CreatedBy`",
                "",
            )
        ),
    )
    must_fail(
        "frozen-workflow-facts-omit-event-audit",
        lambda: validate_event_audit(
            business_values=replace_once(
                business_values_text,
                event_line + "\n",
                "",
            )
        ),
    )
    must_fail(
        "frozen-event-audit-omits-createdby",
        lambda: validate_event_audit(
            business_values=replace_once(
                business_values_text,
                event_line,
                event_line.replace("createdby=101,", ""),
            ),
        ),
    )
    must_fail(
        "frozen-event-audit-process-edge-omitted",
        lambda: validate_event_audit(
            foreign_key_graph=replace_once(
                foreign_key_graph_text,
                oracle.EVENT_AUDIT_REQUIRED_EDGE + "\n",
                "",
            ),
        ),
    )
    must_fail(
        "frozen-event-audit-activity-join-broken",
        lambda: validate_event_audit(
            business_values=replace_once(
                business_values_text,
                event_line,
                event_line.replace("ad_wf_node_id=244", "ad_wf_node_id=245"),
            ),
        ),
    )
    must_fail(
        "frozen-create-effect-omits-event-audit",
        lambda: validate_event_audit(
            create_effect=replace_once(
                create_effect_text,
                event_line + "\n",
                "",
            ),
        ),
    )

    with tempfile.TemporaryDirectory(prefix="phase5g1ay-contract-") as temporary:
        copied = pathlib.Path(temporary) / "contract"
        shutil.copytree(contract_dir, copied)
        (copied / "README.md").write_text(
            (copied / "README.md").read_text(encoding="utf-8") + "\nmutation\n",
            encoding="utf-8",
        )
        must_fail("manifest-detects-altered-file", lambda: oracle.verify_manifest(copied))

    with tempfile.TemporaryDirectory(prefix="phase5g1ay-contract-") as temporary:
        copied = pathlib.Path(temporary) / "contract"
        shutil.copytree(contract_dir, copied)
        (copied / "unexpected.txt").write_text("unexpected\n", encoding="utf-8")
        must_fail(
            "manifest-detects-unexpected-file", lambda: oracle.verify_manifest(copied)
        )

    def mutate_provenance(name: str, mutation) -> None:
        data = copy.deepcopy(fixture)
        mutation(data)
        must_fail(
            name,
            lambda: provenance.validate_provenance(data, contract_dir, repo_root),
        )

    mutate_provenance("provenance-missing-source-commit", lambda data: data.pop("source_commit"))
    mutate_provenance(
        "provenance-wrong-mode", lambda data: data.__setitem__("mode", "legacy")
    )
    mutate_provenance(
        "provenance-missing-patched-path",
        lambda data: data["patched_paths"].pop(),
    )
    mutate_provenance(
        "provenance-adds-patched-path",
        lambda data: data["patched_paths"].append(
            "base/src/org/compiere/wf/MWFActivity.java"
        ),
    )
    mutate_provenance(
        "provenance-missing-runtime-artifacts",
        lambda data: data.__setitem__("runtime_artifacts", []),
    )
    mutate_provenance(
        "provenance-omits-removed-jar-signature",
        lambda data: data["removed_jar_signatures"].pop(),
    )
    mutate_provenance(
        "provenance-omits-required-runtime-artifact",
        lambda data: data["runtime_artifacts"].pop(),
    )
    mutate_provenance(
        "provenance-wrong-runtime-artifact",
        lambda data: data["runtime_artifacts"][2].__setitem__(
            "path",
            "build/phase5g1ay/corrected-runtime/classes/org/compiere/wf/MWorkflow$1.class",
        ),
    )
    mutate_provenance(
        "provenance-adds-unreviewed-runtime-artifact",
        lambda data: data["runtime_artifacts"].append(
            {
                "path": "build/phase5g1ay/corrected-runtime/classes/org/compiere/wf/MWFActivity.class",
                "sha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            }
        ),
    )
    mutate_provenance(
        "provenance-malformed-runtime-digest",
        lambda data: data["runtime_artifacts"][0].__setitem__("sha256", "not-a-digest"),
    )
    mutate_provenance(
        "provenance-unscoped-runtime-artifact",
        lambda data: data["runtime_artifacts"][0].__setitem__(
            "path", "build/phase3/release/Adempiere.jar"
        ),
    )
    mutate_provenance(
        "provenance-wrong-repository-head",
        lambda data: data.__setitem__(
            "repository_head", "ffffffffffffffffffffffffffffffffffffffff"
        ),
    )

    args.report.parent.mkdir(parents=True, exist_ok=True)
    lines = ["mutation\tresult"]
    lines.extend(f"{name}\t{result}" for name, result in rows)
    args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Phase 5g-1a-y mutation proof detected {len(rows)} mutations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
