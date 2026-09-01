#!/usr/bin/env bash
# Phase 5g write-oracle per-capture isolation (the reseed primitive).
#
# WHY THIS EXISTS
#
# scripts/phase5/reset-oracle-fixture.sh cannot reset a write workload. It is
# hard-wired to AD_User_ID=101 and removes only capture-created AD_ChangeLog,
# AD_Session and that user's AD_RecentItem rows. It restores no business
# partner, order, tax, reservation, AD_PInstance or accounting state. Reusing it
# between write captures would let capture A's business rows leak into capture
# B, which makes the oracle nondeterministic or falsely green.
#
# The Phase 5g ADR therefore requires FULL restore per capture, not surgical
# rollback: a rollback that misses one table silently makes the next capture
# start from a different state than the previous one, and the whole point of an
# A/B self-diff is that both captures start identical.
#
# WHY A GOLDEN SNAPSHOT RATHER THAN RE-RUNNING THE ANT BUILD
#
# The obvious reading of "restore from the seed" is to re-run the Phase 3
# database build. That is not available and would not be affordable:
#
#   * `phase3AntDatabaseBuild` (gradle/phase3/distribution.gradle:325-328) is one
#     Exec that performs the whole build/install/database operation, and Gradle
#     runs a task at most once per task graph. It cannot restore before capture A
#     and again before capture B.
#   * It is `finalizedBy cleanupPhase3Database` (:330-348), which DROPS the
#     database and role. Invoking it mid-lane would destroy the lane.
#   * Phase 5f evidence puts initial setup at roughly 22 minutes. Paying that per
#     capture would exhaust the CI job budget outright.
#
# So the product is built and installed ONCE, and this script captures a golden
# archive of the resulting fully-migrated database at a known-good point. Every
# later reseed restores that archive. The golden archive is the installed
# product's own post-migration state, so restoring it is equivalent to a fresh
# install for every purpose the oracle cares about, at a fraction of the cost.
#
# WHY IT ALSO RESTARTS TOMCAT
#
# ADempiere caches dictionary and context state in the application. Restoring the
# database underneath a running container leaves those caches populated from the
# previous capture, so capture B would read rows that no longer exist. Restarting
# is part of isolation, not an optimisation, and it is done here rather than left
# to the caller because a forgotten restart produces a plausible-looking green
# run.
#
# Safety: every mutating path refuses to touch anything that is not the exact
# local Phase 3 disposable target carrying the Phase 3 database marker.
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD=... [ADEMPIERE_PHASE5D_DB_PASSWORD=...]
       reset-write-oracle-fixture.sh <host> <port> <db> <user> <system-user> <marker> <command> [args]

Commands:
  baseline <archive>            Capture the golden archive from the installed database.
  restore  <archive>            Reseed: stop Tomcat, restore the archive, restart Tomcat.
  fixture                       Apply the reviewed write fixture to a freshly restored database.
  state                         Report whether the database currently matches the golden archive.

Options (environment):
  PHASE5G_LANE_PORT             Tomcat port for the lane scripts (default 8888).
  PHASE5G_SKIP_CONTAINER=1      Do not stop/start Tomcat. Diagnostics only; never in a gate.
USAGE
  exit 64
}

[[ $# -ge 7 ]] || usage

db_host=$1
db_port=$2
db_name=$3
db_user=$4
system_user=$5
database_marker=$6
command=$7
shift 7

system_password=${ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD:?system password environment variable is required}
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
lane_port=${PHASE5G_LANE_PORT:-8888}

# The same exact-target guard the Phase 3 and Phase 5b/5d scripts use. This is
# what makes the script safe to run against a developer's shared local
# PostgreSQL instance: it can only ever act on the one disposable database.
if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ||
      "$db_name" != "adempiere_phase3_ci" ||
      "$db_user" != "adempiere_phase3_ci" ]]; then
  echo "Refusing to reseed outside the exact local Phase 3 database target." >&2
  exit 65
fi

run_system_psql() {
  PGPASSWORD=$system_password psql \
    --host="$db_host" --port="$db_port" --username="$system_user" \
    --set=ON_ERROR_STOP=1 "$@"
}

marker_of() {
  run_system_psql --dbname=postgres --tuples-only --no-align --command="
    SELECT COALESCE(shobj_description(oid, 'pg_database'), '__UNTAGGED__')
    FROM pg_database WHERE datname = '$db_name'"
}

# The expected marker is a CONSTANT, not whatever the caller passed. The other
# parameterized Phase 3/5 scripts take it from argv, but this is the first new
# script to combine `dropdb` with a caller-supplied marker, and a marker the
# caller chooses is not a guard: passing `__UNTAGGED__` -- the sentinel
# `marker_of` returns for a database carrying no comment at all -- would satisfy
# an argv comparison and drop an unmarked database. The positional argument is
# kept only as a redundant cross-check, so a caller that disagrees with the
# constant fails loudly instead of silently widening the guard.
readonly EXPECTED_MARKER="ADempiere Phase 3 disposable database"

require_marked_database() {
  local actual
  if [[ "$database_marker" != "$EXPECTED_MARKER" ]]; then
    echo "Refusing to reseed: caller supplied marker '$database_marker'," >&2
    echo "which is not the disposable Phase 3 marker." >&2
    exit 65
  fi
  actual=$(marker_of)
  if [[ -z "$actual" ]]; then
    echo "Database $db_name does not exist; nothing to reseed." >&2
    exit 66
  fi
  if [[ "$actual" != "$EXPECTED_MARKER" ]]; then
    echo "Refusing to act on unmarked database $db_name (found: $actual)." >&2
    exit 65
  fi
}

stop_container() {
  [[ "${PHASE5G_SKIP_CONTAINER:-0}" == "1" ]] && return 0
  "$repo_root/scripts/phase5/stop-legacy-browser-lane.sh" "$lane_port" >/dev/null 2>&1 || true
}

start_container() {
  [[ "${PHASE5G_SKIP_CONTAINER:-0}" == "1" ]] && return 0
  "$repo_root/scripts/phase5/start-legacy-browser-lane.sh" "$lane_port"
}

# Any open connection blocks DROP DATABASE. Terminating them is done explicitly
# rather than by retrying the drop, so a connection that outlives the container
# shutdown is reported instead of turning into an intermittent failure.
terminate_sessions() {
  run_system_psql --dbname=postgres --tuples-only --no-align --command="
    SELECT pg_terminate_backend(pid)
    FROM pg_stat_activity
    WHERE datname = '$db_name' AND pid <> pg_backend_pid()" >/dev/null
}

case "$command" in
  baseline)
    [[ $# -eq 1 ]] || { echo "baseline requires <archive>" >&2; exit 64; }
    archive=$1
    require_marked_database
    mkdir -p "$(dirname "$archive")"
    # Custom format so the restore is a single parallelisable operation and the
    # archive is not a 100 MiB text file the CI log could accidentally echo.
    PGPASSWORD=$system_password pg_dump \
      --host="$db_host" --port="$db_port" --username="$system_user" \
      --dbname="$db_name" --format=custom --no-owner --no-privileges \
      --file="$archive"
    # Record what the archive is OF. An archive with no provenance is one that a
    # later run cannot prove was taken from the installed product rather than
    # from a database some earlier capture had already written to.
    run_system_psql --dbname="$db_name" --tuples-only --no-align --command="
      SELECT 'ad_table_count=' || count(*) FROM AD_Table" >"$archive.provenance"
    {
      printf 'captured_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'database=%s\n' "$db_name"
      printf 'marker=%s\n' "$EXPECTED_MARKER"
      printf 'sha256=%s\n' "$(shasum -a 256 "$archive" | awk '{print $1}')"
    } >>"$archive.provenance"
    echo "Golden archive written to $archive"
    ;;

  restore)
    [[ $# -eq 1 ]] || { echo "restore requires <archive>" >&2; exit 64; }
    archive=$1
    [[ -f "$archive" ]] || { echo "No such golden archive: $archive" >&2; exit 66; }
    [[ -f "$archive.provenance" ]] || {
      echo "Golden archive $archive has no provenance file; refusing to restore an unattributed archive." >&2
      exit 65
    }
    recorded=$(awk -F= '$1 == "sha256" { print $2 }' "$archive.provenance")
    actual=$(shasum -a 256 "$archive" | awk '{print $1}')
    if [[ "$recorded" != "$actual" ]]; then
      echo "Golden archive digest mismatch: recorded $recorded, found $actual." >&2
      exit 65
    fi

    require_marked_database
    stop_container
    terminate_sessions

    PGPASSWORD=$system_password dropdb \
      --host="$db_host" --port="$db_port" --username="$system_user" "$db_name"
    PGPASSWORD=$system_password createdb \
      --host="$db_host" --port="$db_port" --username="$system_user" \
      --owner="$db_user" "$db_name"
    # pg_dump does not carry the database-level comment, so the marker is
    # re-applied explicitly. A restored database that lost its marker would make
    # every later guard refuse to run -- a confusing failure a long way from its
    # cause.
    run_system_psql --dbname=postgres --command="
      COMMENT ON DATABASE $db_name IS '$EXPECTED_MARKER'"

    PGPASSWORD=$system_password pg_restore \
      --host="$db_host" --port="$db_port" --username="$system_user" \
      --dbname="$db_name" --no-owner --no-privileges --exit-on-error "$archive"
    run_system_psql --dbname="$db_name" --command="
      GRANT ALL ON SCHEMA public TO $db_user;
      GRANT ALL ON ALL TABLES IN SCHEMA public TO $db_user;
      GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO $db_user;
      GRANT ALL ON ALL FUNCTIONS IN SCHEMA public TO $db_user" >/dev/null

    start_container
    echo "Reseeded $db_name from $archive"
    ;;

  fixture)
    # The reviewed write fixture.
    #
    # 5g-1a deliberately applies almost nothing: the Business Partner flow is
    # driven entirely through the UI, and pre-creating its rows here would score
    # the fixture instead of the window. What DOES belong here is state the flow
    # depends on but does not itself create.
    #
    # The fixture is assertion-only: it creates nothing and primes nothing. The
    # second editor's first-login writes -- AD_Session, AD_Preference and their
    # change logs -- are therefore real, and they are handled where they belong,
    # by giving that login its own step boundary in the driver
    # ("concurrency-second-editor-authenticated") so they are never attributed
    # to the concurrency update. Priming them away in SQL would have meant
    # reproducing application behaviour in the fixture, which is the one thing
    # this file exists not to do.
    require_marked_database
    db_password=${ADEMPIERE_PHASE5D_DB_PASSWORD:?fixture requires the application database password}
    PGPASSWORD=$db_password psql \
      --host="$db_host" --port="$db_port" --username="$db_user" \
      --dbname="$db_name" --tuples-only --no-align --set=ON_ERROR_STOP=1 \
      --file="$repo_root/contracts/legacy-web-write-v1/fixture.sql"
    echo "Applied the reviewed Phase 5g-1a write fixture"
    ;;

  state)
    require_marked_database
    echo "marked"
    ;;

  *)
    echo "Unknown command: $command" >&2
    usage
    ;;
esac
