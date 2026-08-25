#!/usr/bin/env bash
# Phase 5d read-only window database-effect measurement.
#
# contracts/legacy-web-v1/database-effects.tsv owns the allowed LOGIN and ROLE
# deltas, and scripts/phase5/reset-oracle-fixture.sh enforces them. That script
# is digest-pinned by contracts/legacy-web-v1/capture-environment.tsv and is
# deliberately NOT modified here: the Phase 5b oracle would be silently rebased
# by any edit to it.
#
# This script adds only the Phase 5d claim, which is a different one: opening the
# "Error Message" window must write nothing at all. The claim is needed because
# the window is not dictionary-read-only. AD_Tab 314 carries IsReadOnly='N' and
# IsInsertRecord='Y', AD_Error is empty on a restored seed, and the window
# therefore renders an unsaved auto-new record with New Record and Save changes
# enabled. Measuring zero writes is the only honest way to assert that the
# browser flow read the window instead of editing it.
#
# The reviewed table set and its allowed deltas are read from
# contracts/legacy-web-browser-v1/window-readonly-effects.tsv rather than
# hardcoded, so the assertion and the reviewed contract cannot drift apart.
#
# Safety: this script only ever reads, but it still refuses to connect to
# anything that is not the exact local Phase 3 disposable target carrying the
# Phase 3 database marker comment. The password is environment-only so it is
# not exposed in a process argument list.
set -euo pipefail

if [[ $# -lt 6 ]]; then
  echo "Usage: ADEMPIERE_PHASE5D_DB_PASSWORD=... measure-window-readonly-effect.sh <host> <port> <database> <user> <marker> <command> [args]" >&2
  echo "  commands: counts <file> | compare <before-file> <after-file>" >&2
  exit 64
fi

db_host=$1
db_port=$2
db_name=$3
db_user=$4
db_password=${ADEMPIERE_PHASE5D_DB_PASSWORD:?database password environment variable is required}
database_marker=$5
command=$6
shift 6

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly EFFECTS="${WINDOW_READONLY_EFFECTS:-$repo_root/contracts/legacy-web-browser-v1/window-readonly-effects.tsv}"

[[ -f "$EFFECTS" ]] || {
  echo "window read-only effect contract not found: $EFFECTS" >&2
  exit 66
}

if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ||
      "$db_name" != "adempiere_phase3_ci" ||
      "$db_user" != "adempiere_phase3_ci" ]]; then
  echo "Refusing to read the oracle fixture outside the exact local Phase 3 database target." >&2
  exit 65
fi

run_psql() {
  PGPASSWORD=$db_password psql \
    --host="$db_host" \
    --port="$db_port" \
    --username="$db_user" \
    --dbname="$db_name" \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    "$@"
}

actual_marker=$(run_psql --command="
  SELECT shobj_description(oid, 'pg_database')
  FROM pg_database
  WHERE datname = current_database()")
if [[ "$actual_marker" != "$database_marker" ]]; then
  echo "Refusing to read unmarked database $db_name." >&2
  exit 65
fi

# Reviewed tables, in contract order. Read once so a malformed contract fails
# before any measurement is taken rather than half way through a comparison.
contract_tables() {
  awk -F'\t' '$0 !~ /^#/ && NF >= 3 { print $1 }' "$EFFECTS"
}

allowed_delta_for() {
  local table=$1 value
  value=$(awk -F'\t' -v t="$table" '$1 == t { print $3; exit }' "$EFFECTS")
  case "$value" in
    exactly:*) printf '%s' "${value#exactly:}" ;;
    *) echo "no exact allowed_delta declared for $table in $EFFECTS" >&2; exit 65 ;;
  esac
}

case "$command" in
  counts)
    [[ $# -eq 1 ]] || { echo "counts requires <file>" >&2; exit 64; }
    out=$1
    mkdir -p "$(dirname "$out")"
    tables=$(contract_tables)
    [[ -n "$tables" ]] || { echo "No reviewed tables declared in $EFFECTS" >&2; exit 65; }
    : >"$out"
    while IFS= read -r table; do
      [[ -n "$table" ]] || continue
      # The table name comes from a reviewed, in-repository contract, and each
      # candidate is checked against the catalogue before it is ever used in a
      # statement, so an unreviewed identifier can never reach the server.
      exists=$(run_psql --command="
        SELECT count(*) FROM pg_catalog.pg_tables
        WHERE schemaname = current_schema() AND lower(tablename) = lower('$table')")
      if [[ "$exists" != "1" ]]; then
        echo "Reviewed table $table does not exist in $db_name." >&2
        exit 65
      fi
      count=$(run_psql --command="SELECT count(*) FROM $table")
      digest=$(run_psql --command="
        SELECT to_jsonb(row_data)::text
        FROM $table AS row_data
        ORDER BY to_jsonb(row_data)::text" |
        shasum -a 256 | awk '{print $1}')
      printf '%s\t%s\t%s\n' "$table" "$count" "$digest" >>"$out"
    done <<<"$tables"
    echo "Recorded read-only window counts for $(wc -l <"$out" | tr -d ' ') reviewed table(s) in $out"
    ;;

  compare)
    [[ $# -eq 2 ]] || { echo "compare requires <before-file> <after-file>" >&2; exit 64; }
    before=$1
    after=$2
    for file in "$before" "$after"; do
      [[ -f "$file" ]] || { echo "No such counts file: $file" >&2; exit 66; }
    done

    status=0
    total=0
    while IFS= read -r table; do
      [[ -n "$table" ]] || continue
      before_count=$(awk -F'\t' -v t="$table" '$1 == t { print $2 }' "$before")
      after_count=$(awk -F'\t' -v t="$table" '$1 == t { print $2 }' "$after")
      before_digest=$(awk -F'\t' -v t="$table" '$1 == t { print $3 }' "$before")
      after_digest=$(awk -F'\t' -v t="$table" '$1 == t { print $3 }' "$after")
      if [[ -z "$before_count" || -z "$after_count" ||
            -z "$before_digest" || -z "$after_digest" ]]; then
        echo "FAIL: $table was not measured on both sides of the capture." >&2
        status=1
        continue
      fi
      delta=$((after_count - before_count))
      allowed=$(allowed_delta_for "$table")
      total=$((total + delta))
      if [[ "$delta" != "$allowed" || "$before_digest" != "$after_digest" ]]; then
        echo "FAIL: $table changed (row delta $delta, content digest ${before_digest} -> ${after_digest}); window-readonly-effects.tsv allows exactly $allowed and no content change." >&2
        echo "      The browser flow wrote to a table the read-only window step must only read." >&2
        status=1
      else
        echo "ok   $table unchanged at $after_count row(s), content sha256 $after_digest (reviewed allowed delta $allowed)"
      fi
    done < <(contract_tables)

    if (( status == 0 )); then
      # Printed on the last line so the harness can read the measured delta back
      # and record it as a semantic fact instead of assuming it.
      echo "window-readonly-delta=$total"
    fi
    exit $status
    ;;

  *)
    echo "Unknown command: $command" >&2
    exit 64
    ;;
esac
