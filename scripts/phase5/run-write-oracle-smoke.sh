#!/usr/bin/env bash
# Phase 5g-1a legacy write-oracle smoke: run the capture lane, then score it.
#
# Split from run-write-oracle-lane.sh on purpose. The lane's job is to produce
# two isolated captures; this script's job is to decide whether they are the
# oracle. Keeping them in one file would make it easy for a future change to let
# the capture influence the verdict, which is exactly the failure mode the Phase
# 5g ADR forbids.
set -euo pipefail

if [[ $# -ne 8 ]]; then
  cat >&2 <<'USAGE'
Usage: run-write-oracle-smoke.sh <host> <port> <db> <user> <system-user> <marker> <evidence-root> <freeze>

<freeze> is "true" only for the run that first produces the oracle. That run is
not an acceptance run, and the gate never sets it.
USAGE
  exit 64
fi

db_host=$1 db_port=$2 db_name=$3 db_user=$4 system_user=$5 marker=$6
evidence_root=$7 freeze=$8

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
scripts_dir=$repo_root/scripts/phase5
contract_dir=$repo_root/contracts/legacy-web-write-v1

mkdir -p "$evidence_root"

bash "$scripts_dir/run-write-oracle-lane.sh" \
  "$db_host" "$db_port" "$db_name" "$db_user" "$system_user" "$marker" "$evidence_root"

# Provenance mirrors the Phase 5f convention so a downloaded CI artifact can be
# replayed locally: rewrite git_head to the local HEAD and re-run the scorer.
cat >"$evidence_root/provenance.json" <<JSON
{
  "phase": "5g-1a",
  "git_head": "$(cd "$repo_root" && git rev-parse HEAD)",
  "captured_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "mode": "$freeze"
}
JSON

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
