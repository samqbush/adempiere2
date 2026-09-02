#!/usr/bin/env bash
# Phase 5e: the AD_SysConfig cohort fixture.
#
# The public-origin matrix needs to put the configuration into each reviewed
# state and then put it back, on a database that other captures share. So this
# script owns exactly the three Phase 5e rows and nothing else: it never
# rewrites, deletes or reads any other AD_SysConfig name, and `clear` restores
# the pre-Phase-5e state rather than a guess at it.
#
# Safety: it mutates a database, so it refuses to run against anything that is
# not the exact local Phase 3 disposable target carrying the Phase 3 marker
# comment - the same guard scripts/phase5/reset-oracle-fixture.sh uses.
set -euo pipefail

if [[ $# -lt 6 ]]; then
  echo "Usage: ADEMPIERE_PHASE5E_DB_PASSWORD=... reset-cohort-config.sh <host> <port> <database> <user> <marker> <command> [args]" >&2
  echo "  commands: state | apply <preset> | clear | snapshot <file> | verify <file>" >&2
  echo "  presets:  master-off user-allowlisted write-parity-users role-allowlisted role-unselected" >&2
  echo "            not-allowlisted duplicate malformed client-scoped" >&2
  echo "            inactive-duplicate unreadable readable" >&2
  exit 64
fi

db_host=$1
db_port=$2
db_name=$3
db_user=$4
db_password=${ADEMPIERE_PHASE5E_DB_PASSWORD:?database password environment variable is required}
database_marker=$5
command=$6
shift 6

# The Phase 5e cohort identities.
#
# GardenAdmin (AD_User_ID 101) is the Phase 5b oracle user; GardenUser
# (AD_User_ID 102) is the second seeded GardenWorld account the concurrency
# proof uses, so neither is operator-created.
#
# The role allowlist is the role the ACTING identity actually logs in with, not
# merely a role it is entitled to. GardenAdmin holds both AD_Role_ID 102
# (GardenWorld Admin) and 103 (GardenWorld User) in the seed, and its login
# selects 102. The first version of this fixture allowlisted 103, so
# `role-allowlisted` asserted that a role the acting login never selects would
# route it to the modern runtime - a case that could only pass by accident.
#
# 103 is kept, as COHORT_ROLE_UNSELECTED, for the negative row: a role the user
# genuinely holds but does not select must NOT select the modern cohort, which
# is what proves the decision reads the selected role rather than the user's
# role list.
COHORT_USER_A=101
# GardenUser, the second acting identity. The write-parity capture drives FOUR
# sessions and two of them log in as this user, so a preset that allowlists only
# GardenAdmin routes half the capture to the LEGACY application. Run 33626582558
# is what that looks like: the legacy Tomcat's own log carried the second
# editor's and the duplicate submitter's lookups, inside a capture claiming
# modern parity.
COHORT_USER_B=102
COHORT_ROLE_A=102
COHORT_ROLE_UNSELECTED=103

if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ||
      "$db_name" != "adempiere_phase3_ci" ||
      "$db_user" != "adempiere_phase3_ci" ]]; then
  echo "Refusing to touch the cohort fixture outside the exact local Phase 3 database target." >&2
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

restore_sysconfig() {
  run_psql --command='
    ALTER TABLE IF EXISTS AD_SysConfig_Phase5e_Unreadable
      RENAME TO AD_SysConfig' >/dev/null
}

actual_marker=$(run_psql --command="
  SELECT shobj_description(oid, 'pg_database')
  FROM pg_database
  WHERE datname = current_database()")
if [[ "$actual_marker" != "$database_marker" ]]; then
  echo "Refusing to modify an unmarked database $db_name." >&2
  exit 65
fi

NAMES="'MODERN_WEB_UI_ENABLED','MODERN_WEB_UI_USER_IDS','MODERN_WEB_UI_ROLE_IDS'"

delete_all() {
  run_psql --command="DELETE FROM AD_SysConfig WHERE Name IN ($NAMES)" >/dev/null
}

next_id() {
  run_psql --command="
    SELECT coalesce(max(AD_SysConfig_ID), 1000000) + 1 FROM AD_SysConfig"
}

insert_row() {
  local name=$1 value=$2 client=$3 org=$4 active=$5 id
  id=$(next_id)
  if [[ "$value" == "NULL" ]]; then
    run_psql --command="
      INSERT INTO AD_SysConfig (AD_SysConfig_ID, AD_Client_ID, AD_Org_ID,
        IsActive, Created, CreatedBy, Updated, UpdatedBy, Name, Value, EntityType)
      VALUES ($id, $client, $org, '$active', now(), 100, now(), 100,
        '$name', NULL, 'D')" >/dev/null
  else
    run_psql --command="
      INSERT INTO AD_SysConfig (AD_SysConfig_ID, AD_Client_ID, AD_Org_ID,
        IsActive, Created, CreatedBy, Updated, UpdatedBy, Name, Value, EntityType)
      VALUES ($id, $client, $org, '$active', now(), 100, now(), 100,
        '$name', '$value', 'D')" >/dev/null
  fi
}

case "$command" in
  state)
    run_psql --command="
      SELECT Name || '=' || coalesce(Value, '<null>') || '@' ||
             AD_Client_ID || ',' || AD_Org_ID || ',' || IsActive
      FROM AD_SysConfig WHERE Name IN ($NAMES)
      ORDER BY Name, AD_SysConfig_ID"
    ;;

  snapshot)
    [[ $# -eq 1 ]] || { echo "snapshot requires <file>" >&2; exit 64; }
    mkdir -p "$(dirname "$1")"
    {
      printf 'cohort_rows\t%s\n' \
        "$(run_psql --command="SELECT count(*) FROM AD_SysConfig WHERE Name IN ($NAMES)")"
      printf 'sysconfig_rows\t%s\n' \
        "$(run_psql --command='SELECT count(*) FROM AD_SysConfig')"
    } >"$1"
    echo "Cohort fixture snapshot written to $1"
    ;;

  verify)
    [[ $# -eq 1 ]] || { echo "verify requires <snapshot-file>" >&2; exit 64; }
    before=$(awk -F'\t' '$1 == "sysconfig_rows" { print $2 }' "$1")
    after=$(run_psql --command='SELECT count(*) FROM AD_SysConfig')
    if [[ "$before" != "$after" ]]; then
      echo "FAIL: AD_SysConfig had $before rows before the matrix and $after after; the fixture leaked" >&2
      exit 1
    fi
    echo "ok   AD_SysConfig returned to its exact pre-matrix row count"
    ;;

  clear)
    restore_sysconfig
    delete_all
    echo "Cohort configuration cleared"
    ;;

  readable)
    restore_sysconfig
    echo "AD_SysConfig is readable again"
    ;;

  apply)
    [[ $# -eq 1 ]] || { echo "apply requires <preset>" >&2; exit 64; }
    preset=$1
    delete_all
    case "$preset" in
      master-off)
        # Present and explicitly disabled: the allowlists are populated so the
        # case proves the master switch beats them rather than that the
        # allowlists happened to be empty.
        insert_row MODERN_WEB_UI_ENABLED N 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A" 0 0 Y
        insert_row MODERN_WEB_UI_ROLE_IDS "$COHORT_ROLE_A" 0 0 Y
        ;;
      master-absent)
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A" 0 0 Y
        ;;
      user-allowlisted)
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A" 0 0 Y
        insert_row MODERN_WEB_UI_ROLE_IDS "" 0 0 Y
        ;;
      write-parity-users)
        # Every acting identity in the write-parity flow, and ONLY because every
        # one of them must be served the modern application for the capture to
        # mean what it claims. This is deliberately a separate preset rather than
        # a widening of user-allowlisted, whose whole point in the H6 matrix is
        # that exactly one identity is allowlisted.
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A,$COHORT_USER_B" 0 0 Y
        insert_row MODERN_WEB_UI_ROLE_IDS "" 0 0 Y
        ;;
      role-allowlisted)
        # The user allowlist deliberately excludes the acting user, so a pass
        # can only come from the role allowlist - and the allowlisted role is
        # the one the acting login actually selects.
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "999999" 0 0 Y
        insert_row MODERN_WEB_UI_ROLE_IDS "$COHORT_ROLE_A" 0 0 Y
        ;;
      role-unselected)
        # The negative twin of role-allowlisted. The allowlisted role is one the
        # acting user genuinely holds but does not select at login, so a pass
        # here would mean the decision reads the user's role LIST rather than
        # the role the session is actually running as.
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "999999" 0 0 Y
        insert_row MODERN_WEB_UI_ROLE_IDS "$COHORT_ROLE_UNSELECTED" 0 0 Y
        ;;
      not-allowlisted)
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "999999" 0 0 Y
        insert_row MODERN_WEB_UI_ROLE_IDS "999998" 0 0 Y
        ;;
      duplicate)
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A" 0 0 Y
        ;;
      inactive-duplicate)
        # The inactive row must NOT count as a duplicate.
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_ENABLED N 0 0 N
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A" 0 0 Y
        ;;
      malformed)
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "101, 102" 0 0 Y
        ;;
      client-scoped)
        # Only client-scoped rows exist. They must be ignored, so the session
        # stays legacy even though a client-scoped master switch says Y.
        insert_row MODERN_WEB_UI_ENABLED Y 11 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A" 11 0 Y
        ;;
      unreadable)
        insert_row MODERN_WEB_UI_ENABLED Y 0 0 Y
        insert_row MODERN_WEB_UI_USER_IDS "$COHORT_USER_A" 0 0 Y
        # The disposable database user owns the restored table, so revoking
        # SELECT cannot make it unreadable: PostgreSQL owners retain implicit
        # access. A reversible rename preserves the correct rows while making
        # the repository's next atomic read fail exactly as a missing schema
        # object or unavailable database would.
        run_psql --command='
          ALTER TABLE AD_SysConfig
            RENAME TO AD_SysConfig_Phase5e_Unreadable' >/dev/null
        ;;
      *)
        echo "Unknown preset: $preset" >&2
        exit 64
        ;;
    esac
    echo "Cohort configuration preset applied: $preset"
    ;;

  *)
    echo "Unknown command: $command" >&2
    exit 64
    ;;
esac
