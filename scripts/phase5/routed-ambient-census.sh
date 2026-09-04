#!/usr/bin/env bash
# Phase 5g-1b: the routed-lane ambient census.
#
# WHY THIS EXISTS
#
# quiesce-phase5f-background-processors.sh disables the configured timer sources
# and automatic error reporting, and verifies that it did. That is a statement
# about CONFIGURATION. It is not a statement about what the dual-runtime routed
# lane actually writes when nobody is driving it, and Phase 5f already found one
# writer that no configuration disables: first-touch `WebEnv.initWeb`.
#
# 5g-1a ran ONE container. 5g-1b runs two -- the public Tomcat 9 ingress and the
# loopback Tomcat 10 modern runtime -- against the same database, and the modern
# runtime is new to this database. A write it performs on its own would be
# attributed to whichever browser step happened to straddle it, which is a
# capture that fails intermittently or, worse, one that passes while measuring
# something else.
#
# WHAT IT PROVES
#
# Two bounded quiet intervals with NO browser attached, after the restore and
# after both runtimes are up:
#
#   interval 1  (t0 -> t1)  may show ambient settling. It must show no
#                           NON-ambient change: a new writer is a defect, and
#                           ambient-tables.tsv is deliberately not widened in
#                           5g-1b to forgive one.
#   interval 2  (t1 -> t2)  must show NO change at all, ambient included. This
#                           is what makes "the lane is quiet before the
#                           authenticated baseline" a measurement rather than an
#                           assumption -- an ambient writer that never settles
#                           would satisfy interval 1 forever.
#
# The comparison is the reviewed one: measure-write-effect.py's whole-database
# sentinel carries a row count AND a content fingerprint for every table, so a
# content-only update to a table outside the keyed measurement scope is visible
# here for exactly the reason it is visible inside a step.
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: routed-ambient-census.sh <host> <port> <db> <user> <label> <out-dir>

Environment:
  PHASE5G1B_CENSUS_QUIET_SECONDS  Length of each quiet interval (default 45).
USAGE
  exit 64
}

[[ $# -eq 6 ]] || usage

db_host=$1
db_port=$2
db_name=$3
db_user=$4
label=$5
out_dir=$6

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
contract_dir=$repo_root/contracts/legacy-web-write-v1
quiet_seconds=${PHASE5G1B_CENSUS_QUIET_SECONDS:-45}

mkdir -p "$out_dir"

snapshot() {
  python3 "$repo_root/scripts/phase5/measure-write-effect.py" snapshot \
    --host "$db_host" --port "$db_port" --database "$db_name" --user "$db_user" \
    --scope "$contract_dir/measurement-scope.tsv" \
    --out "$1"
}

diff_interval() {
  local before=$1 after=$2 step=$3 out=$4
  python3 "$repo_root/scripts/phase5/measure-write-effect.py" diff \
    --before "$before" --after "$after" \
    --step "$step" \
    --attribution-scope "$contract_dir/attribution-scope.tsv" \
    --ambient "$contract_dir/ambient-tables.tsv" \
    --baseline "$before" \
    --out "$out"
}

echo "== routed ambient census ($label): two ${quiet_seconds}s quiet intervals, no browser attached =="

snapshot "$out_dir/census-t0.json"
sleep "$quiet_seconds"
snapshot "$out_dir/census-t1.json"
sleep "$quiet_seconds"
snapshot "$out_dir/census-t2.json"

diff_interval "$out_dir/census-t0.json" "$out_dir/census-t1.json" \
  "routed-ambient-census-settling" "$out_dir/census-settling.txt"
diff_interval "$out_dir/census-t1.json" "$out_dir/census-t2.json" \
  "routed-ambient-census-quiet" "$out_dir/census-quiet.txt"

# `[no-effect]` is emitted by `diff` exactly when nothing outside the ambient
# classification changed, in rows OR in content. Reading the marker rather than
# re-deriving the verdict here keeps one implementation of "non-ambient change"
# in the repository: a second one could disagree with the scorer and make the
# census green over a change the oracle would have failed.
changed_tables_of() {
  awk '/^\[changed-tables\]$/ { inside = 1; next }
       /^\[/ { inside = 0 }
       inside && NF && $0 !~ /^#/ { print }' "$1"
}

problems=$out_dir/census-problems.txt
: >"$problems"

if ! grep -qx '\[no-effect\]' "$out_dir/census-settling.txt"; then
  {
    echo "the routed lane wrote outside the reviewed ambient classification while idle:"
    changed_tables_of "$out_dir/census-settling.txt"
  } >>"$problems"
fi

# The quiet interval is held to a stricter standard than the settling interval,
# so it is checked against the raw changed-table list rather than the ambient
# marker: an AMBIENT writer that is still running when the browser starts is
# exactly the condition that makes a per-step effect unattributable, and the
# no-effect marker forgives it by design.
quiet_changes=$(changed_tables_of "$out_dir/census-quiet.txt")
if [[ -n "$quiet_changes" ]]; then
  {
    echo "the routed lane was still writing during the second quiet interval, so no"
    echo "per-step effect measured after it can be attributed to the browser:"
    printf '%s\n' "$quiet_changes"
  } >>"$problems"
fi

{
  printf 'label\t%s\n' "$label"
  printf 'quiet_seconds\t%s\n' "$quiet_seconds"
  printf 'settling_non_ambient_change\t%s\n' \
    "$(grep -qx '\[no-effect\]' "$out_dir/census-settling.txt" && echo none || echo present)"
  printf 'quiet_any_change\t%s\n' \
    "$([[ -z "$quiet_changes" ]] && echo none || echo present)"
  printf 'verdict\t%s\n' "$([[ -s "$problems" ]] && echo fail || echo pass)"
} >"$out_dir/census.tsv"

if [[ -s "$problems" ]]; then
  printf 'routed ambient census FAILED (%s):\n' "$label" >&2
  cat "$problems" >&2
  exit 1
fi

echo "== routed ambient census ($label): pass =="
