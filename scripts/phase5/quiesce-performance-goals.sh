#!/usr/bin/env bash
#
# Quiesce the performance-goal recalculation for the Phase 5g write lanes.
#
# PA_Goal is not a timer source, so quiesce-phase5f-background-processors.sh
# does not cover it: it is a LAZY, WALL-CLOCK-TRIGGERED writer. MGoal.updateGoal
# (base/src/org/compiere/model/MGoal.java:373-389) recalculates and SAVES the
# goal whenever
#
#     force || getDateLastRun() == null || !TimeUtil.isSameHour(getDateLastRun(), null)
#
# and MGoal.getUserGoals (MGoal.java:62-90) calls it once per goal at login,
# for the performance indicator panel. So whether a login writes to PA_Goal
# depends on nothing but which clock hour it happens in.
#
# That is invisible until a lane straddles an hour boundary, and then it is a
# divergence between two captures of the SAME runtime. Run 33580195848 hit
# exactly that: capture A and capture B disagreed on
# `duplicate-submit-editor-authenticated` with `pa_goal +0 content`, and B also
# failed against the frozen model. The frozen answer does not declare pa_goal
# because the 5g-1a freeze run happened not to cross an hour.
#
# The fix is to remove the nondeterminism, not to forgive it. Widening
# ambient-tables.tsv would teach the contract to accept a real write set it
# cannot otherwise explain, and contracts/legacy-web-write-v1 is read-only in
# 5g-1b in any case. Deactivating the goals instead makes getUserGoals' own
# `WHERE IsActive='Y'` return no rows, so updateGoal is never reached and the
# observation is deterministic in every hour.
#
# Quiescence runs BEFORE the golden archive is captured, so every capture
# restores an already-quiesced database and the deactivation is invisible to
# the effect diffs. The marker guard confines the mutation to the disposable
# Phase 3 database, and the state file makes it reversible.

set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "Usage: quiesce-performance-goals.sh <host> <port> <db> <user> <marker> <quiesce|verify|restore> <state-file>" >&2
  exit 64
fi

host=$1 port=$2 database=$3 user=$4 marker=$5 action=$6 state_file=$7
: "${ADEMPIERE_PHASE5F_DB_PASSWORD:?ADEMPIERE_PHASE5F_DB_PASSWORD is required}"
export PGPASSWORD=$ADEMPIERE_PHASE5F_DB_PASSWORD

psql_cmd=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -d "$database" -U "$user" -At)

actual=$("${psql_cmd[@]}" -c "SELECT coalesce(shobj_description(oid, 'pg_database'),'') FROM pg_database WHERE datname=current_database()")
[[ "$actual" == "$marker" ]] || {
  echo "Refusing performance-goal quiesce: database marker mismatch" >&2
  exit 65
}

# PA_Goal only. PA_GoalRestriction, PA_Measure and PA_Benchmark are read by the
# recalculation but are not written by it, and PA_MeasureCalc is configuration
# the routes under test read; deactivating those would change what the product
# does rather than remove an ambient writer.
goal_table=pa_goal

record_state() {
  "${psql_cmd[@]}" -F$'\t' -c \
    "SELECT '$goal_table', PA_Goal_ID, 'IsActive', IsActive FROM $goal_table ORDER BY PA_Goal_ID" \
    >"$state_file"
}

assert_quiesced() {
  local count
  count=$("${psql_cmd[@]}" -c "SELECT count(*) FROM $goal_table WHERE IsActive='Y'")
  if [[ "$count" != 0 ]]; then
    echo "Performance goal table $goal_table has $count active definition(s)" >&2
    exit 66
  fi
}

case "$action" in
  quiesce)
    record_state
    "${psql_cmd[@]}" <<SQL
UPDATE $goal_table SET IsActive='N', Updated=now(), UpdatedBy=100 WHERE IsActive='Y';
SQL
    assert_quiesced
    printf 'Quiesced performance goal recalculation (%s)\n' "$goal_table"
    ;;
  verify)
    assert_quiesced
    ;;
  restore)
    [[ -f "$state_file" ]] || { echo "No state file at $state_file" >&2; exit 66; }
    while IFS=$'\t' read -r table id column value; do
      [[ -n "$table" && -n "$column" ]] || continue
      "${psql_cmd[@]}" -c \
        "UPDATE $table SET $column='$value' WHERE PA_Goal_ID=$id"
    done <"$state_file"
    ;;
  *) echo "Action must be quiesce, verify, or restore" >&2; exit 64 ;;
esac
