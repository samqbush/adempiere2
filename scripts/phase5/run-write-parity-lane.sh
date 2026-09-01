#!/usr/bin/env bash
# Phase 5g-1b modern write-parity capture lane.
#
# WHAT THIS IS AND IS NOT
#
# This is run-write-oracle-lane.sh's sibling, not its replacement. The two lanes
# are deliberately parallel in structure -- same golden-dump/quiesce/restore/
# fixture cycle, same file rendezvous, same per-step snapshot boundaries -- and
# they differ in exactly two places:
#
#   1. the deployment behind the public origin, and
#   2. the browser dialect the driver binds.
#
# Everything downstream of the capture is shared and unmodified:
# measure-write-effect.py, normalize_write_capture.py,
# derive-write-oracle-facts.py and score-write-oracle-capture.py all run here
# exactly as they run for the legacy lane, against the SAME frozen contract in
# contracts/legacy-web-write-v1/. There is no modern contract tree and no
# runtime-divergence list: a policy that decides after the fact which
# differences are acceptable would be an oracle fact wearing a parity
# increment's clothes.
#
# WHY THE PUBLIC ORIGIN, NOT /webui-modern
#
# The base URL is http://127.0.0.1:8888/webui -- the public Tomcat 9 ingress --
# because ADR decision 6 forbids scoring on the direct loopback modern origin.
# What is under test is the modern runtime AS A USER REACHES IT: through the
# router, the cohort decision, the handoff and the proxy. Scoring on
# /webui-modern would prove that the modern application works when the whole
# routing layer is bypassed, which is not the claim.
#
# WHAT THE ROUTED LANE ADDS OVER THE LEGACY LANE
#
#   * a cohort fixture, so the write session is routed MODERN rather than served
#     by the legacy application that already has a frozen answer;
#   * two container lifecycles instead of one, passed to the reseed primitive as
#     an adapter rather than as a lane name;
#   * an ambient census after every restore, because quiescence is a statement
#     about configuration and the modern runtime is new to this database.
#
# Safety: every mutating path delegates to the marker-guarded scripts, so this
# lane cannot touch anything that is not the exact local Phase 3 disposable
# target.
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD=... ADEMPIERE_PHASE5D_DB_PASSWORD=...
       run-write-parity-lane.sh <host> <port> <db> <user> <system-user> <marker>
                                <evidence-root> <installed-home> <handoff-key>

Runs the complete Phase 5g-1b modern write parity capture lane: prepare,
capture A, capture B. Evidence is written under <evidence-root>/A and
<evidence-root>/B.
USAGE
  exit 64
}

[[ $# -eq 9 ]] || usage

db_host=$1
db_port=$2
db_name=$3
db_user=$4
system_user=$5
marker=$6
evidence_root=$7
installed_home=$8
handoff_key=$9

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
contract_dir=$repo_root/contracts/legacy-web-write-v1
scripts_dir=$repo_root/scripts/phase5
gradlew=$repo_root/gradlew

: "${ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD:?system password environment variable is required}"
: "${ADEMPIERE_PHASE5D_DB_PASSWORD:?application password environment variable is required}"
# Three scripts, three variable names, one value. Bridging here rather than
# asking the caller to set identical values three times keeps one source of
# truth; the cohort fixture and the quiesce verifier read their own names.
export ADEMPIERE_PHASE5F_DB_PASSWORD=$ADEMPIERE_PHASE5D_DB_PASSWORD
export ADEMPIERE_PHASE5E_DB_PASSWORD=$ADEMPIERE_PHASE5D_DB_PASSWORD

public_port=${PHASE5E_PUBLIC_PORT:-8888}
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
  "$repo_root/gradle/phase4/runtime.properties")

# The reseed primitive's container lifecycle, as an adapter. See
# write-parity-container-adapter.sh for why this is not a lane-name parameter.
export PHASE5G_CONTAINER_ADAPTER=$scripts_dir/write-parity-container-adapter.sh
export PHASE5G1B_REPO_ROOT=$repo_root
export PHASE5G1B_INSTALLED_HOME=$installed_home
export PHASE5G1B_HANDOFF_KEY=$handoff_key
# BOTH runtimes, not only the ingress. A stop that left the loopback modern
# runtime up would leave it holding -- and writing into -- the database the
# reseed is about to drop and the next capture is about to measure.
export PHASE5G_CONFIRM_PORTS="$public_port $api_port"

golden_archive=$evidence_root/golden.dump
quiesce_state=$evidence_root/quiesce-state.tsv
mkdir -p "$evidence_root"

reset() {
  bash "$scripts_dir/reset-write-oracle-fixture.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$system_user" "$marker" "$@"
}

quiesce() {
  bash "$scripts_dir/quiesce-phase5f-background-processors.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$1" "$quiesce_state"
}

cohort() {
  bash "$scripts_dir/reset-cohort-config.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$@"
}

census() {
  bash "$scripts_dir/routed-ambient-census.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$1" "$2"
}

measure() {
  python3 "$scripts_dir/measure-write-effect.py" "$@"
}

teardown() {
  local status=$?
  bash "$scripts_dir/stop-routed-lane.sh" "$repo_root" "$installed_home" \
    >/dev/null 2>&1 || true
  return "$status"
}
trap teardown EXIT

# ---------------------------------------------------------------------------
# Prepare.
# ---------------------------------------------------------------------------
#
# The ordering trap documented at length in run-write-oracle-lane.sh applies
# here unchanged: quiescence is applied and VERIFIED before the golden archive
# is taken, so every later restore reinstates it as a property of the bytes
# rather than as a step somebody has to remember.
#
# The cohort fixture is applied at the SAME point, and for the same reason. It
# is AD_SysConfig state, so a restore would undo it; baking it into the archive
# is what makes "this capture was routed modern" true of every capture rather
# than of the first one. The lane still records the runtime cohort decision per
# capture, because a configuration is not a decision.
prepare() {
  echo "== preparing the modern write-parity lane =="
  quiesce quiesce
  quiesce verify
  cohort apply user-allowlisted
  # A tamper record, NOT a proof of routing. `reset-cohort-config.sh verify`
  # compares the AD_SysConfig row count against the snapshot; since every
  # capture restores the archive this snapshot was taken from, a match is close
  # to a tautology and says nothing about which runtime served anybody. What
  # actually proves the routing is the driver's browser-observed runtime
  # identification, asserted per capture below.
  cohort snapshot "$evidence_root/cohort-config.tsv"
  reset baseline "$golden_archive"
  reset restore "$golden_archive"
  quiesce verify
  cohort verify "$evidence_root/cohort-config.tsv"
  # The deployment the captures will run against, read from the running
  # processes and the installed tree rather than from the tasks that were
  # supposed to produce them. Taken once, after the restore that both captures
  # will repeat, so it describes the lane every capture actually used.
  bash "$scripts_dir/capture-write-parity-topology.sh" \
    "$repo_root" "$installed_home" "$evidence_root/topology"
  echo "== golden archive captured from the verified quiesced, cohort-routed state =="
}

# ---------------------------------------------------------------------------
# One capture.
# ---------------------------------------------------------------------------
capture() {
  local label=$1
  local capture_dir=$evidence_root/$label
  local rendezvous=$capture_dir/rendezvous
  local snapshots=$capture_dir/snapshots
  local effects=$capture_dir/effects
  local token
  token="$label-$(date +%s)-$$"
  # The SAME constant the legacy lane uses. The record value appears verbatim in
  # business-values.tsv, which is frozen, so a modern-specific value would fail
  # parity for a reason that has nothing to do with the runtime.
  local record_value="P5G1A-0001"

  rm -rf "$capture_dir"
  mkdir -p "$rendezvous" "$snapshots" "$effects"

  echo "== capture $label: restoring, re-verifying quiescence and cohort, censusing =="
  reset restore "$golden_archive"
  quiesce verify
  cohort verify "$evidence_root/cohort-config.tsv"
  census "$label" "$capture_dir/census"
  reset fixture

  echo "== capture $label: driving the browser through the public routed origin =="

  printf '%s\n' "$$" >"$rendezvous/orchestrator.pid.partial"
  mv "$rendezvous/orchestrator.pid.partial" "$rendezvous/orchestrator.pid"

  set +e
  "$gradlew" --project-dir "$repo_root" :zkwebui:phase5g1bModernWriteParityCapture \
    -Pphase5g1aEvidenceDir="$capture_dir" \
    -Pphase5g1aRendezvousDir="$rendezvous" \
    -Pphase5g1aToken="$token" \
    -Pphase5g1aRecordValue="$record_value" \
    --dependency-verification=strict >"$capture_dir/driver.log" 2>&1 &
  local driver_pid=$!
  set -e

  snapshot_loop "$label" "$rendezvous" "$snapshots" "$effects" "$token" "$driver_pid"

  wait "$driver_pid" || {
    echo "the modern write driver failed; see $capture_dir/driver.log" >&2
    tail -60 "$capture_dir/driver.log" >&2
    return 1
  }

  # WHICH application served this capture.
  #
  # Observed by the DRIVER, from the browser that performed the write, because
  # every other observation in the capture is runtime-blind: the browser only
  # ever sees the public origin, the recorded URLs are normalized against it,
  # and the database effects are the product's. A lane-side reading of
  # AD_SysConfig would only restate the configuration this lane itself applied.
  #
  # So this is the one check that can see a routed lane whose cohort decision,
  # handoff or proxy failed closed and served the LEGACY application -- which
  # would score a perfect green against the legacy oracle and report modern
  # parity.
  local identification=$capture_dir/runtime-identification.tsv
  if [[ ! -f "$identification" ]]; then
    echo "capture $label recorded no runtime identification, so it cannot say" >&2
    echo "which application served the write session." >&2
    return 1
  fi
  local expected served
  expected=$(awk -F'\t' '$1 == "expected" { print $2 }' "$identification")
  served=$(awk -F'\t' '$1 == "served" { print $2 }' "$identification")
  if [[ "$expected" != "modern" || "$served" != "modern" ]]; then
    echo "capture $label was not served by the modern runtime:" >&2
    cat "$identification" >&2
    return 1
  fi

  python3 "$scripts_dir/derive-write-oracle-facts.py" \
    --capture "$capture_dir" \
    --scope "$contract_dir/measurement-scope.tsv" \
    --attribution-scope "$contract_dir/attribution-scope.tsv"

  echo "== capture $label complete =="
}

# ---------------------------------------------------------------------------
# The orchestrator half of the rendezvous.
# ---------------------------------------------------------------------------
#
# Identical to the legacy lane's. It is duplicated rather than shared because
# the two lanes must be able to drift apart in their DEPLOYMENT without either
# one being able to change the other's step boundaries -- and a shared
# rendezvous helper is the natural place for a later "just this once" modern
# special case to land.
snapshot_loop() {
  local label=$1 rendezvous=$2 snapshots=$3 effects=$4 token=$5 driver_pid=$6
  local sequence=0
  local previous=""
  local deadline_seconds=600

  while :; do
    local request=$rendezvous/step-$sequence.request
    local waited=0
    while [[ ! -f $request ]]; do
      if ! kill -0 "$driver_pid" 2>/dev/null; then
        echo "driver exited before requesting step $sequence; ending the loop"
        return 0
      fi
      if [[ -f $rendezvous/driver.failed ]]; then
        echo "the driver reported a failure: $(cat "$rendezvous/driver.failed")" >&2
        return 1
      fi
      sleep 0.1
      waited=$((waited + 1))
      if (( waited > deadline_seconds * 10 )); then
        printf 'orchestrator timed out waiting for step %s\n' "$sequence" \
          >"$rendezvous/orchestrator.failed.partial"
        mv "$rendezvous/orchestrator.failed.partial" "$rendezvous/orchestrator.failed"
        echo "orchestrator timed out waiting for step $sequence" >&2
        return 1
      fi
    done

    local marker_token step_id
    marker_token=$(head -1 "$request")
    step_id=$(tail -n +2 "$request")
    if [[ "$marker_token" != "$token" ]]; then
      echo "rendezvous token mismatch at step $sequence: a marker from an earlier capture survived" >&2
      return 1
    fi

    echo "-- $label step $sequence ($step_id): snapshotting"
    local current=$snapshots/step-$sequence.json
    measure snapshot \
      --host "$db_host" --port "$db_port" --database "$db_name" --user "$db_user" \
      --scope "$contract_dir/measurement-scope.tsv" \
      --out "$current"

    if [[ -n "$previous" ]]; then
      measure diff \
        --before "$previous" --after "$current" \
        --step "$step_id" \
        --attribution-scope "$contract_dir/attribution-scope.tsv" \
        --ambient "$contract_dir/ambient-tables.tsv" \
        --baseline "$snapshots/step-0.json" \
        --out "$effects/$step_id.txt"
    fi
    previous=$current

    printf '%s\n%s' "$token" "$step_id" >"$rendezvous/step-$sequence.ack.partial"
    mv "$rendezvous/step-$sequence.ack.partial" "$rendezvous/step-$sequence.ack"
    sequence=$((sequence + 1))
  done
}

prepare
capture A
capture B

echo "== parity lane complete: evidence under $evidence_root =="
