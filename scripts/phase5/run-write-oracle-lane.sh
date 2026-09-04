#!/usr/bin/env bash
# Phase 5g-1a legacy write-oracle capture lane.
#
# WHAT THIS ORCHESTRATES
#
# One capture is: a quiesced, freshly restored, fixture-loaded database behind a
# freshly restarted container; a browser driver that performs exactly one
# business operation at a time through the public /webui origin; and a snapshot
# taken between every pair of operations. The driver and this script take strict
# turns through a file rendezvous, because a per-step effect that is measured
# after the next step has started is not a per-step effect.
#
# THE ORDERING TRAP THIS SCRIPT EXISTS TO AVOID
#
# The obvious lifecycle -- quiesce, then restore, then apply the fixture -- is
# wrong, and wrong in a way that produces a plausible green run rather than an
# error. quiesce-phase5f-background-processors.sh disables the timer-driven
# processors and automatic error reporting by UPDATING the database, while
# reset-write-oracle-fixture.sh restore DROPS and recreates it from the golden
# archive. Restoring therefore UNDOES the quiescence, and the next capture runs
# with live background processors writing into the very tables being measured.
#
# So the order here is: install, quiesce, VERIFY the quiescence, and only then
# capture the golden archive -- from the already-quiesced state. Every later
# restore reinstates quiescence as a property of the archive rather than as a
# step somebody has to remember. Quiescence is re-verified after every restore,
# because "we believe it is baked in" is not a measurement.
#
# WHY THE FIXTURE IS APPLIED AFTER THE RESTORE AND AFTER THE CONTAINER STARTS
#
# ADempiere caches dictionary and context state in the application, so the
# RESTORE must happen with the container down -- rows restored under a running
# container are read from a cache that no longer matches the database. That is
# what reset-write-oracle-fixture.sh restore does: stop, pg_restore, start.
#
# The fixture itself is applied afterwards, against the running container, and
# that is safe because the reviewed fixture is assertion-only. It creates and
# updates nothing, so there is no state for a cache to be stale about. If it
# ever starts writing, it must move ahead of the container start.
#
# Safety: every mutating path delegates to the marker-guarded scripts, so this
# lane cannot touch anything that is not the exact local Phase 3 disposable
# target.
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD=... ADEMPIERE_PHASE5D_DB_PASSWORD=...
       run-write-oracle-lane.sh <host> <port> <db> <user> <system-user> <marker> <evidence-root>

Runs the complete Phase 5g-1a legacy write capture lane: prepare, capture A,
capture B, and the A/B self-diff inputs. Evidence is written under
<evidence-root>/A and <evidence-root>/B.
USAGE
  exit 64
}

[[ $# -eq 7 ]] || usage

db_host=$1
db_port=$2
db_name=$3
db_user=$4
system_user=$5
marker=$6
evidence_root=$7

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
contract_dir=$repo_root/contracts/legacy-web-write-v1
scripts_dir=$repo_root/scripts/phase5
gradlew=$repo_root/gradlew

: "${ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD:?system password environment variable is required}"
: "${ADEMPIERE_PHASE5D_DB_PASSWORD:?application password environment variable is required}"
# The quiesce script reads its own variable name. Bridging it here rather than
# requiring the caller to set two identical values keeps one source of truth.
export ADEMPIERE_PHASE5F_DB_PASSWORD=$ADEMPIERE_PHASE5D_DB_PASSWORD

golden_archive=$evidence_root/golden.dump
quiesce_state=$evidence_root/quiesce-state.tsv
goal_quiesce_state=$evidence_root/goal-quiesce-state.tsv
mkdir -p "$evidence_root"

reset() {
  # Invoked through `bash` rather than directly: depending on a sibling
  # script's execute bit means a lane that installs a database, quiesces it and
  # only then fails with "Permission denied" -- eight minutes in, for a reason
  # that has nothing to do with the oracle.
  bash "$scripts_dir/reset-write-oracle-fixture.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$system_user" "$marker" "$@"
}

quiesce() {
  bash "$scripts_dir/quiesce-phase5f-background-processors.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$1" "$quiesce_state"
  # PA_Goal is a lazy, WALL-CLOCK-triggered writer rather than a timer source,
  # so the Phase 5f processor quiesce does not cover it: MGoal.updateGoal saves
  # whenever DateLastRun is not in the current hour, and getUserGoals calls it
  # at every login. Two captures of the same runtime that straddle an hour
  # boundary therefore diverge. See quiesce-performance-goals.sh.
  bash "$scripts_dir/quiesce-performance-goals.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$1" "$goal_quiesce_state"
}

measure() {
  python3 "$scripts_dir/measure-write-effect.py" "$@"
}

# The lane leaves a Tomcat container running between phases by design, so an
# abnormal exit must still take it down. Without this, a lane failure is
# followed by cleanupPhase3Database failing too --
# `database "adempiere_phase3_ci" is being accessed by other users` -- which
# leaks a database and buries the real diagnosis under a second error.
teardown() {
  local status=$?
  bash "$scripts_dir/stop-legacy-browser-lane.sh" \
    "${PHASE5G_LANE_PORT:-8888}" >/dev/null 2>&1 || true
  return "$status"
}
trap teardown EXIT

# ---------------------------------------------------------------------------
# Prepare: quiesce the installed database, prove it, and freeze it as the
# archive every capture restores.
# ---------------------------------------------------------------------------
prepare() {
  echo "== preparing the write-oracle lane =="
  quiesce quiesce
  quiesce verify
  reset baseline "$golden_archive"
  # Proving it again after the dump is not redundant: it establishes that the
  # bytes on disk -- not merely the live database -- carry the quiesced state,
  # which is the whole premise of restoring instead of re-quiescing.
  reset restore "$golden_archive"
  quiesce verify
  echo "== golden archive captured from the verified quiesced state =="
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
  # Constant across captures A and B, NOT derived from the label. The captures
  # run against independently restored databases, so there is no collision to
  # avoid -- and a per-capture value would appear verbatim in business-values.tsv
  # and make the A/B self-diff fail for a reason that has nothing to do with the
  # runtime.
  local record_value="P5G1A-0001"

  rm -rf "$capture_dir"
  mkdir -p "$rendezvous" "$snapshots" "$effects"

  echo "== capture $label: restoring, re-verifying quiescence, applying fixture =="
  reset restore "$golden_archive"
  quiesce verify
  reset fixture

  # The restore stopped and restarted the container, which is what clears the
  # dictionary caches. See the header for why applying the assertion-only
  # fixture afterwards is safe.
  echo "== capture $label: driving the browser =="

  # Publish this side's liveness BEFORE the driver starts, so a driver that
  # comes up and immediately waits can see an orchestrator to monitor.
  printf '%s\n' "$$" >"$rendezvous/orchestrator.pid.partial"
  mv "$rendezvous/orchestrator.pid.partial" "$rendezvous/orchestrator.pid"

  set +e
  "$gradlew" --project-dir "$repo_root" :zkwebui:phase5g1aWriteOracleCapture \
    -Pphase5g1aEvidenceDir="$capture_dir" \
    -Pphase5g1aRendezvousDir="$rendezvous" \
    -Pphase5g1aToken="$token" \
    -Pphase5g1aRecordValue="$record_value" \
    --dependency-verification=strict >"$capture_dir/driver.log" 2>&1 &
  local driver_pid=$!
  set -e

  snapshot_loop "$label" "$rendezvous" "$snapshots" "$effects" "$token" "$driver_pid"

  wait "$driver_pid" || {
    echo "the write driver failed; see $capture_dir/driver.log" >&2
    tail -60 "$capture_dir/driver.log" >&2
    return 1
  }

  # Three fact classes are derived from the snapshots rather than reported by
  # the browser, so that the driver never states what it believes its own writes
  # did. A fourth, the network classification, is derived because reducing raw
  # request lines to classes is a normalization policy decision and belongs in a
  # reviewed script rather than in a test method.
  python3 "$scripts_dir/derive-write-oracle-facts.py" \
    --capture "$capture_dir" \
    --scope "$contract_dir/measurement-scope.tsv" \
    --attribution-scope "$contract_dir/attribution-scope.tsv"

  echo "== capture $label complete =="
}

# ---------------------------------------------------------------------------
# The orchestrator half of the rendezvous.
# ---------------------------------------------------------------------------
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
        # The driver exited. If it exited having finished the flow, every step
        # it requested has been served and there is nothing left to wait for.
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

    # The first snapshot is a baseline only: there is no preceding operation to
    # attribute an effect to. Diffing it against nothing would invent a step.
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

echo "== lane complete: evidence under $evidence_root =="
