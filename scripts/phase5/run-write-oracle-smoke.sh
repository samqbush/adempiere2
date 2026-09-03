#!/usr/bin/env bash
# Phase 5g-1a legacy write-oracle smoke: run the capture lane, then score it.
#
# Split from run-write-oracle-lane.sh on purpose. The lane's job is to produce
# two isolated captures; this script's job is to decide whether they are the
# oracle. Keeping them in one file would make it easy for a future change to let
# the capture influence the verdict, which is exactly the failure mode the Phase
# 5g ADR forbids.
set -euo pipefail

if [[ $# -ne 9 ]]; then
  cat >&2 <<'USAGE'
Usage: run-write-oracle-smoke.sh <host> <port> <db> <user> <system-user> <marker> <evidence-root> <freeze> <runtime-mode>

<freeze> is "true" only for the run that first produces the oracle. That run is
not an acceptance run, and the gate never sets it.

<runtime-mode> is either "legacy" or
"corrected-legacy-workflow-attribution". The corrected mode applies the
contract-pinned patch only in a disposable source worktree, activates its
runtime only for this lane, and restores the installed runtime before scoring.
USAGE
  exit 64
fi

db_host=$1 db_port=$2 db_name=$3 db_user=$4 system_user=$5 marker=$6
evidence_root=$7 freeze=$8 runtime_mode=$9

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
scripts_dir=$repo_root/scripts/phase5
contract_dir=$repo_root/contracts/legacy-web-write-v1
corrected_contract_dir=$repo_root/contracts/phase5g1ay-workflow-attribution-v1
corrected_runtime_dir=$repo_root/build/phase5g1ay/corrected-runtime
installed_home=$repo_root/build/phase3/runtime/Adempiere
runtime_guard_dir=$repo_root/build/phase5g1ay/ordinary-runtime-guard
runtime_guard_script=$scripts_dir/guard-phase5g1ay-ordinary-runtime.sh

require_fact_fields() {
  local facts=$1 table=$2 expected_identity=$3
  shift 3
  local rows identity row field
  rows=$(awk -F'\t' -v table="$table" '$1 == table { count++ } END { print count + 0 }' "$facts")
  if [[ "$rows" -ne 1 ]]; then
    echo "FAIL: corrected capture requires exactly one $table row in $facts, found $rows" >&2
    return 1
  fi
  identity=$(awk -F'\t' -v table="$table" '$1 == table { print $2 }' "$facts")
  if [[ "$identity" != "$expected_identity" ]]; then
    echo "FAIL: corrected capture $table identity is $identity, expected $expected_identity" >&2
    return 1
  fi
  row=$(awk -F'\t' -v table="$table" '$1 == table { print $3 }' "$facts")
  for field in "$@"; do
    if [[ ",$row," != *",$field,"* ]]; then
      echo "FAIL: corrected capture $table row is missing $field in $facts" >&2
      return 1
    fi
  done
}

validate_corrected_workflow_capture() {
  local facts=$1
  require_fact_fields "$facts" ad_wf_process @ad_wf_process#1 \
    ad_client_id=11 createdby=101 updatedby=101 ad_user_id=101 \
    ad_wf_process_id=@ad_wf_process#1 record_id=@c_bpartner#1
  require_fact_fields "$facts" ad_wf_activity @ad_wf_activity#1 \
    ad_client_id=11 createdby=101 updatedby=101 \
    ad_wf_activity_id=@ad_wf_activity#1 \
    ad_wf_process_id=@ad_wf_process#1 record_id=@c_bpartner#1
  require_fact_fields "$facts" ad_wf_eventaudit @ad_wf_eventaudit#1 \
    ad_client_id=11 createdby=101 updatedby=101 \
    ad_wf_eventaudit_id=@ad_wf_eventaudit#1 \
    ad_wf_process_id=@ad_wf_process#1 record_id=@c_bpartner#1
}

case "$runtime_mode" in
  legacy|corrected-legacy-workflow-attribution) ;;
  *)
    echo "Unsupported Phase 5g-1a runtime mode: $runtime_mode" >&2
    exit 64
    ;;
esac
case "$freeze" in
  true|false) ;;
  *)
    echo "Freeze must be true or false, found: $freeze" >&2
    exit 64
    ;;
esac

restore_corrected_runtime() {
  local status=$?
  if [[ "$runtime_mode" == corrected-legacy-workflow-attribution ]]; then
    if ! bash "$runtime_guard_script" restore \
      "$repo_root" "$installed_home" "$runtime_guard_dir"
    then
      status=1
    fi
  fi
  return "$status"
}
trap restore_corrected_runtime EXIT

mkdir -p "$evidence_root"

if [[ "$runtime_mode" == corrected-legacy-workflow-attribution ]]; then
  oracle_operation=acceptance
  [[ "$freeze" == true ]] && oracle_operation=freeze
  bash "$scripts_dir/materialize-phase5g1ay-corrected-runtime.sh" \
    "$repo_root" "$installed_home" "$corrected_runtime_dir" "$oracle_operation"

  corrected_jar=$corrected_runtime_dir/Adempiere.jar
  bash "$runtime_guard_script" activate \
    "$repo_root" "$installed_home" "$runtime_guard_dir" "$corrected_jar"
fi

bash "$scripts_dir/run-write-oracle-lane.sh" \
  "$db_host" "$db_port" "$db_name" "$db_user" "$system_user" "$marker" "$evidence_root"

if [[ "$runtime_mode" == corrected-legacy-workflow-attribution ]]; then
  validate_corrected_workflow_capture "$evidence_root/A/business-values.tsv"
  validate_corrected_workflow_capture "$evidence_root/B/business-values.tsv"
  bash "$runtime_guard_script" restore \
    "$repo_root" "$installed_home" "$runtime_guard_dir"
  trap - EXIT
  cp "$corrected_runtime_dir/provenance.json" "$evidence_root/provenance.json"
  python3 "$scripts_dir/validate-phase5g1ay-provenance.py" \
    --provenance "$evidence_root/provenance.json" \
    --contract-dir "$corrected_contract_dir" \
    --repo-root "$repo_root" \
    --verify-artifacts
else
  # Provenance mirrors the Phase 5f convention so a downloaded CI artifact can
  # be replayed locally: rewrite git_head to the local HEAD and re-run the
  # scorer. Preserve the existing legacy-mode shape for 5g-1a/5g-1a-x.
  cat >"$evidence_root/provenance.json" <<JSON
{
  "phase": "5g-1a",
  "git_head": "$(cd "$repo_root" && git rev-parse HEAD)",
  "captured_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "mode": "$freeze"
}
JSON
fi

score_args=(
  --evidence-root "$evidence_root"
  --contract "$contract_dir"
  --ambient "$contract_dir/ambient-tables.tsv"
  --summary "$evidence_root/score-summary.tsv"
)
if [[ "$freeze" == "true" ]]; then
  echo "== FREEZE MODE: producing candidate oracle facts, NOT verifying them =="
  score_args+=(--freeze)
fi

python3 "$scripts_dir/score-write-oracle-capture.py" "${score_args[@]}"
