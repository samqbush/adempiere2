#!/usr/bin/env bash
# Phase 5b oracle database fixture (RD-3).
#
# The legacy web flows are not read-only. Login creates an MSession row
# (AdempiereWebUI.java:194-205) and role completion writes user preferences
# (RolePanel.java:500-507), which are then read back as the role/client/org/
# warehouse defaults on the next login (RolePanel.java:304-419). Capture A can
# therefore change what capture B observes, so the determinism proof needs a
# defined fixture rather than a hope.
#
# Observed behaviour on the frozen runtime:
#   * AD_Preference rows for the oracle user already exist in the seed and are
#     UPDATED in place with identical values when the same selections are made.
#     The write is idempotent; the preference digest is unchanged across a
#     capture.
#   * AD_Session gains exactly one row per capture. That is the only delta.
#
# `snapshot` records the fixture state, `verify` asserts the reviewed delta set,
# and `reset` returns the database to the snapshot so capture B starts where
# capture A did.
#
# Safety: this script mutates a database, so it refuses to run against anything
# that is not the exact local Phase 3 disposable target carrying the Phase 3
# database marker comment. That guard is what makes it safe to run against a
# developer's shared local PostgreSQL instance.
set -euo pipefail

if [[ $# -lt 7 ]]; then
  echo "Usage: reset-oracle-fixture.sh <host> <port> <database> <user> <password> <marker> <command> [args]" >&2
  echo "  commands: snapshot <file> | verify <file> | reset <file>" >&2
  exit 64
fi

db_host=$1
db_port=$2
db_name=$3
db_user=$4
db_password=$5
database_marker=$6
command=$7
shift 7

# The oracle user. GardenAdmin (AD_User_ID 101) is a seeded GardenWorld client
# administrator with deterministic role/client/org/warehouse defaults, so the
# capture never depends on an operator-created account.
ORACLE_USER_ID=101

if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ||
      "$db_name" != "adempiere_phase3_ci" ||
      "$db_user" != "adempiere_phase3_ci" ]]; then
  echo "Refusing to touch the oracle fixture outside the exact local Phase 3 database target." >&2
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
  echo "Refusing to modify unmarked database $db_name." >&2
  exit 65
fi

preference_digest() {
  run_psql --command="
    SELECT coalesce(md5(string_agg(attribute || '=' || coalesce(value, ''), ',' ORDER BY AD_Preference_ID)), '')
    FROM AD_Preference
    WHERE AD_User_ID = $ORACLE_USER_ID"
}

max_session_id() {
  run_psql --command="SELECT coalesce(max(AD_Session_ID), 0) FROM AD_Session"
}

session_count() {
  run_psql --command="SELECT count(*) FROM AD_Session"
}

write_snapshot() {
  local file=$1
  mkdir -p "$(dirname "$file")"
  {
    printf 'oracle_user_id\t%s\n' "$ORACLE_USER_ID"
    printf 'preference_digest\t%s\n' "$(preference_digest)"
    printf 'max_session_id\t%s\n' "$(max_session_id)"
    printf 'session_count\t%s\n' "$(session_count)"
  } >"$file"
}

read_snapshot_field() {
  awk -v key="$1" -F'\t' '$1 == key { print $2 }' "$2"
}

# The allowed delta set is declared in the frozen contract, not duplicated here,
# so the assertion and the reviewed contract cannot drift apart.
readonly DB_EFFECTS="${DB_EFFECTS:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/contracts/legacy-web-v1/database-effects.tsv}"

allowed_exact_delta() {
  local table="$1" value
  [[ -f "$DB_EFFECTS" ]] || { echo "database-effects contract not found: $DB_EFFECTS" >&2; exit 66; }
  value=$(awk -F'\t' -v t="$table" '$1 == t { print $3; exit }' "$DB_EFFECTS")
  case "$value" in
    exactly:*) printf '%s' "${value#exactly:}" ;;
    *) echo "no exact allowed_delta declared for $table in $DB_EFFECTS" >&2; exit 65 ;;
  esac
}

case "$command" in
  snapshot)
    [[ $# -eq 1 ]] || { echo "snapshot requires <file>" >&2; exit 64; }
    write_snapshot "$1"
    echo "Oracle fixture snapshot written to $1"
    ;;

  verify)
    # Asserts that a capture produced only the reviewed delta set. A preference
    # digest change means the capture selected a different role/client/org/
    # warehouse than the oracle was frozen under, which silently rebases the
    # oracle and must fail.
    [[ $# -eq 1 ]] || { echo "verify requires <snapshot-file>" >&2; exit 64; }
    snapshot=$1
    [[ -f "$snapshot" ]] || { echo "No such snapshot: $snapshot" >&2; exit 66; }

    before_pref=$(read_snapshot_field preference_digest "$snapshot")
    before_sessions=$(read_snapshot_field session_count "$snapshot")
    after_pref=$(preference_digest)
    after_sessions=$(session_count)

    status=0
    if [[ "$before_pref" != "$after_pref" ]]; then
      echo "FAIL: oracle user preferences changed during capture ($before_pref -> $after_pref)." >&2
      status=1
    else
      echo "ok   AD_Preference unchanged for user $ORACLE_USER_ID (idempotent write)"
    fi

    delta=$((after_sessions - before_sessions))
    allowed_session_delta=$(allowed_exact_delta AD_Session)
    if [[ "$delta" != "$allowed_session_delta" ]]; then
      echo "FAIL: AD_Session delta was $delta; database-effects.tsv allows exactly $allowed_session_delta." >&2
      status=1
    else
      echo "ok   AD_Session grew by $delta row(s) (reviewed allowed delta)"
    fi

    exit $status
    ;;

  reset)
    # Removes only the AD_Session rows this capture created, identified by id
    # range rather than by time, so a concurrent row could never be swept up.
    [[ $# -eq 1 ]] || { echo "reset requires <snapshot-file>" >&2; exit 64; }
    snapshot=$1
    [[ -f "$snapshot" ]] || { echo "No such snapshot: $snapshot" >&2; exit 66; }

    baseline_session_id=$(read_snapshot_field max_session_id "$snapshot")
    if [[ ! "$baseline_session_id" =~ ^[0-9]+$ ]]; then
      echo "Snapshot has no usable max_session_id." >&2
      exit 65
    fi

    removed=$(run_psql --command="
      WITH deleted AS (
        DELETE FROM AD_Session
        WHERE AD_Session_ID > $baseline_session_id
        RETURNING 1
      )
      SELECT count(*) FROM deleted")
    echo "Reset removed $removed capture-created AD_Session row(s)."

    # Preference rows are updated in place with identical values, so there is
    # nothing to restore while the digest still matches. If it does not, the
    # fixture is not in its frozen state and the caller must not proceed.
    expected_pref=$(read_snapshot_field preference_digest "$snapshot")
    actual_pref=$(preference_digest)
    if [[ "$expected_pref" != "$actual_pref" ]]; then
      echo "FAIL: oracle user preferences drifted from the snapshot and cannot be auto-restored." >&2
      echo "  expected $expected_pref, found $actual_pref" >&2
      exit 1
    fi
    echo "Oracle fixture restored to snapshot $snapshot"
    ;;

  *)
    echo "Unknown command: $command" >&2
    exit 64
    ;;
esac
