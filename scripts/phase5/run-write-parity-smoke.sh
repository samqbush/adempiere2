#!/usr/bin/env bash
# Phase 5g-1b modern write-parity smoke: run the routed capture lane, then
# decide whether the modern runtime matched the frozen legacy answer.
#
# Split from run-write-parity-lane.sh for the reason run-write-oracle-smoke.sh
# is split from its lane: the lane's job is to produce two isolated captures,
# and this script's job is to reach a verdict about them. Keeping both in one
# file makes it easy for a later change to let the capture influence the
# verdict, which is the failure mode the Phase 5g ADR exists to prevent.
#
# THERE IS NO FREEZE ARGUMENT, AND THAT IS STRUCTURAL.
#
# run-write-oracle-smoke.sh takes a <freeze> parameter because the legacy lane
# is the one that PRODUCES the oracle. This lane may never produce one. Phase
# 5g-1b scores the modern runtime against contracts/legacy-web-write-v1/, which
# is read-only here; a modern capture that could re-freeze the contract would be
# a parity increment inventing the answer it is being scored against, which ADR
# decision 3 forbids. Refusing the parameter -- rather than accepting it and
# defaulting it to false -- is what makes that impossible to reach by passing an
# argument, including from the workflow_dispatch input that the legacy gate
# legitimately honours.
set -euo pipefail

if [[ $# -ne 9 ]]; then
  cat >&2 <<'USAGE'
Usage: run-write-parity-smoke.sh <host> <port> <db> <user> <system-user>
                                 <marker> <evidence-root> <installed-home>
                                 <handoff-key>

Runs the Phase 5g-1b routed modern write-parity lane and scores both captures
against the frozen legacy oracle. There is deliberately no freeze mode.
USAGE
  exit 64
fi

db_host=$1 db_port=$2 db_name=$3 db_user=$4 system_user=$5 marker=$6
evidence_root=$7 installed_home=$8 handoff_key=$9

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
scripts_dir=$repo_root/scripts/phase5
contract_dir=$repo_root/contracts/legacy-web-write-v1

public_port=${PHASE5E_PUBLIC_PORT:-8888}
base_url=http://127.0.0.1:$public_port/webui

mkdir -p "$evidence_root"

bash "$scripts_dir/run-write-parity-lane.sh" \
  "$db_host" "$db_port" "$db_name" "$db_user" "$system_user" "$marker" \
  "$evidence_root" "$installed_home" "$handoff_key"

# Provenance mirrors the Phase 5f/5g-1a convention so a downloaded CI artifact
# can be replayed locally by rewriting git_head to the local HEAD. `base_url` is
# recorded because ADR decision 6 scores only the public routed origin, and the
# validator refuses evidence captured against any other -- including the direct
# loopback modern origin, which would prove the modern application works when
# the entire routing layer is bypassed.
cat >"$evidence_root/provenance.json" <<JSON
{
  "phase": "5g-1b",
  "git_head": "$(cd "$repo_root" && git rev-parse HEAD)",
  "captured_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "mode": "false",
  "base_url": "$base_url"
}
JSON

# Freeze-off, always. The scorer's freeze-off mode already does exactly what
# parity needs and carries no legacy assertion: an A/B self-diff, plus BOTH
# captures scored against the frozen contract. It is reused unmodified.
python3 "$scripts_dir/score-write-oracle-capture.py" \
  --evidence-root "$evidence_root" \
  --contract "$contract_dir" \
  --ambient "$contract_dir/ambient-tables.tsv" \
  --summary "$evidence_root/score-summary.tsv"

# The H6 matrix runs AFTER scoring, and restores the seed before every
# destructive row, so it can neither observe nor disturb the state the parity
# captures were measured in.
bash "$scripts_dir/run-write-parity-h6-matrix.sh" \
  "$db_host" "$db_port" "$db_name" "$db_user" "$system_user" "$marker" \
  "$evidence_root" "$installed_home" "$handoff_key" \
  "$evidence_root/golden.dump"

# One comparable lifecycle model from the phases the lane and the matrix each
# observed separately: before and after each capture's authenticated write, and
# the logout/timeout cases H6 restores the seed for.
cat "$evidence_root"/session-evidence/*.tsv \
  >"$evidence_root/session-evidence/session-lifecycle.tsv.partial"
mv "$evidence_root/session-evidence/session-lifecycle.tsv.partial" \
  "$evidence_root/session-evidence/session-lifecycle.tsv"

python3 "$scripts_dir/validate-phase5g1b-runtime-evidence.py" \
  --evidence-root "$evidence_root" \
  --contract "$contract_dir" \
  --repo-root "$repo_root" \
  --base-url "$base_url" \
  --summary "$evidence_root/validation-summary.tsv"
