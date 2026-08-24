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
#   * AD_Preference rows for the oracle user do NOT exist on a freshly restored
#     seed. The first capture CREATES them at role completion; every later
#     capture rewrites the same values in place. The post-capture digest is
#     therefore pinned absolutely rather than merely required to be unchanged,
#     which also catches a capture that never reached role completion.
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
  echo "  commands: state | snapshot <file> | verify <file> | reset <file>" >&2
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

changelog_count() {
  run_psql --command="SELECT count(*) FROM AD_ChangeLog"
}

# Any AD_ChangeLog row the capture created that is NOT an AD_Preference row.
# AD_Preference's dictionary table id is resolved rather than hardcoded so the
# assertion cannot silently rot against a dictionary change.
# The allowed table set is read from the contract rather than hardcoded, and the
# failure names the offending tables and counts: a gate that only says "1 row"
# forces a nine-minute rerun to learn anything.
allowed_changelog_tables() {
  local value
  value=$(awk -F'\t' '$1 == "AD_ChangeLog" { print $3; exit }' "$DB_EFFECTS")
  case "$value" in
    first-capture-only:*) printf '%s' "${value#first-capture-only:}" ;;
    *) echo "no first-capture-only allowed_delta declared for AD_ChangeLog in $DB_EFFECTS" >&2; exit 65 ;;
  esac
}

foreign_changelog_after() {
  local baseline_session_id=$1
  local allowed sql_list
  allowed=$(allowed_changelog_tables)
  sql_list=$(printf "'%s'" "$(printf '%s' "$allowed" | sed "s/,/','/g")")
  run_psql --command="
    SELECT t.TableName || ':' || count(*)
    FROM AD_ChangeLog cl
    JOIN AD_Table t ON t.AD_Table_ID = cl.AD_Table_ID
    WHERE cl.AD_Session_ID > $baseline_session_id
      AND t.TableName NOT IN ($sql_list)
    GROUP BY t.TableName
    ORDER BY t.TableName"
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
    printf 'changelog_count\t%s\n' "$(changelog_count)"
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

allowed_digest() {
  local table="$1" value
  [[ -f "$DB_EFFECTS" ]] || { echo "database-effects contract not found: $DB_EFFECTS" >&2; exit 66; }
  value=$(awk -F'\t' -v t="$table" '$1 == t { print $3; exit }' "$DB_EFFECTS")
  case "$value" in
    digest-equals:*) printf '%s' "${value#digest-equals:}" ;;
    *) echo "no digest-equals allowed_delta declared for $table in $DB_EFFECTS" >&2; exit 65 ;;
  esac
}

case "$command" in
  state)
    # Reports whether the oracle user has ever logged in on this database.
    # The frozen oracle was captured against a warm database, so the caller must
    # be able to tell the two apart instead of assuming.
    if [[ -z "$(preference_digest)" ]]; then
      echo cold
    else
      echo warm
    fi
    ;;

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
    expected_pref=$(allowed_digest AD_Preference)
    if [[ "$after_pref" != "$expected_pref" ]]; then
      echo "FAIL: oracle user preferences are $after_pref; database-effects.tsv pins $expected_pref (was $before_pref before the capture)." >&2
      status=1
    elif [[ -z "$before_pref" ]]; then
      echo "ok   AD_Preference created at the pinned digest for user $ORACLE_USER_ID (first capture on a restored seed)"
    else
      echo "ok   AD_Preference at the pinned digest for user $ORACLE_USER_ID (idempotent write)"
    fi

    # AD_ChangeLog is asymmetric by design: the first capture on a restored seed
    # creates the preference rows and logs them, later captures change nothing.
    before_changelog=$(read_snapshot_field changelog_count "$snapshot")
    after_changelog=$(changelog_count)
    changelog_delta=$((after_changelog - before_changelog))
    baseline_session_id=$(read_snapshot_field max_session_id "$snapshot")
    if [[ -z "$before_pref" ]]; then
      foreign=$(foreign_changelog_after "$baseline_session_id" | tr '\n' ' ' | sed 's/ *$//')
      if [[ -n "$foreign" ]]; then
        echo "FAIL: first capture wrote AD_ChangeLog rows for undeclared table(s): $foreign" >&2
        echo "      database-effects.tsv allows only: $(allowed_changelog_tables)" >&2
        status=1
      else
        echo "ok   AD_ChangeLog gained $changelog_delta AD_Preference row(s) (first capture on a restored seed)"
      fi
    elif [[ "$changelog_delta" != "0" ]]; then
      echo "FAIL: AD_ChangeLog delta was $changelog_delta; a repeat capture must log no change." >&2
      status=1
    else
      echo "ok   AD_ChangeLog unchanged (repeat capture rewrote identical values)"
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

    # AD_ChangeLog rows reference AD_Session, so they must go first or the
    # delete fails on the adsession_adchangelog foreign key. Only rows belonging
    # to capture-created sessions are removed; pre-existing history is never
    # touched.
    removed_log=$(run_psql --command="
      WITH deleted AS (
        DELETE FROM AD_ChangeLog
        WHERE AD_Session_ID > $baseline_session_id
        RETURNING 1
      )
      SELECT count(*) FROM deleted")
    echo "Reset removed $removed_log capture-created AD_ChangeLog row(s)."

    removed=$(run_psql --command="
      WITH deleted AS (
        DELETE FROM AD_Session
        WHERE AD_Session_ID > $baseline_session_id
        RETURNING 1
      )
      SELECT count(*) FROM deleted")
    echo "Reset removed $removed capture-created AD_Session row(s)."

    # Recent items are per-user UI state that the desktop menu renders, so they
    # must be cleared before every capture rather than merely counted. Leaving
    # them makes each capture depend on which windows the previous capture
    # opened.
    removed_recent=$(run_psql --command="
      WITH deleted AS (
        DELETE FROM AD_RecentItem
        WHERE AD_User_ID = $ORACLE_USER_ID
        RETURNING 1
      )
      SELECT count(*) FROM deleted")
    echo "Reset removed $removed_recent oracle-user AD_RecentItem row(s)."

    # Preference rows are rewritten with identical values, so there is nothing
    # to restore once they sit at the pinned digest. Reset therefore requires the
    # pinned state, not the pre-capture state: on a restored seed the pre-capture
    # state is "no rows", and rolling back to that would make capture B start
    # from a different fixture than capture A did -- exactly the drift this
    # script exists to prevent.
    expected_pref=$(allowed_digest AD_Preference)
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
