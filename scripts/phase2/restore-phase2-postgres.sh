#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 10 ]]; then
  echo "Usage: restore-phase2-postgres.sh <restore|drop> <seed-file> <db-host> <db-port> <db-name> <db-user> <db-password> <system-user> <system-password> <expected-version>" >&2
  exit 64
fi

mode=$1
seed_file=$2
db_host=$3
db_port=$4
db_name=$5
db_user=$6
db_password=$7
system_user=$8
system_password=$9
expected_version=${10}
database_marker='ADempiere Phase 2 disposable database'
role_marker='ADempiere Phase 2 disposable database role'

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 69
  }
}

guard_local_target() {
  if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ]]; then
    echo "Phase 2 smoke database target must stay local. Refusing host: $db_host" >&2
    exit 65
  fi
  if [[ ! "$db_name" =~ (phase2|smoke|test|ci) ]]; then
    echo "Phase 2 smoke database name must remain explicitly test-only. Refusing name: $db_name" >&2
    exit 65
  fi
  if [[ ! "$db_user" =~ (phase2|smoke|test|ci) ]]; then
    echo "Phase 2 smoke database user must remain explicitly test-only. Refusing user: $db_user" >&2
    exit 65
  fi
  if [[ ! "$db_name" =~ ^[A-Za-z0-9_]+$ || ! "$db_user" =~ ^[A-Za-z0-9_]+$ || ! "$system_user" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "Database identifiers must stay simple and shell-safe for the guarded restore path." >&2
    exit 65
  fi
}

run_psql() {
  local password=$1
  shift
  if [[ -n "$password" ]]; then
    PGPASSWORD=$password psql "$@"
  else
    psql "$@"
  fi
}

run_dropdb() {
  local password=$1
  shift
  if [[ -n "$password" ]]; then
    PGPASSWORD=$password dropdb "$@"
  else
    dropdb "$@"
  fi
}

run_dropuser() {
  local password=$1
  shift
  if [[ -n "$password" ]]; then
    PGPASSWORD=$password dropuser "$@"
  else
    dropuser "$@"
  fi
}

run_createdb() {
  local password=$1
  shift
  if [[ -n "$password" ]]; then
    PGPASSWORD=$password createdb "$@"
  else
    createdb "$@"
  fi
}

drop_phase2_compatibility_role() {
  local compatibility_role_comment
  compatibility_role_comment=$(run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres -Atqc \
    "SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname='adempiere'")
  if [[ "$compatibility_role_comment" == "ADempiere Phase 2 disposable compatibility role" ]]; then
    run_dropuser "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" adempiere >/dev/null
  fi
}

drop_phase2_database() {
  local current_marker
  current_marker=$(run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres -Atqc \
    "SELECT COALESCE(shobj_description(oid, 'pg_database'), '__UNTAGGED__') FROM pg_database WHERE datname='$db_name'")
  if [[ -z "$current_marker" ]]; then
    return
  fi
  if [[ "$current_marker" != "$database_marker" ]]; then
    echo "Refusing to drop database $db_name because it is not owned by the Phase 2 disposable runtime." >&2
    exit 65
  fi
  run_dropdb "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" "$db_name" >/dev/null
}

drop_phase2_database_role() {
  local current_marker
  current_marker=$(run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres -Atqc \
    "SELECT COALESCE(shobj_description(oid, 'pg_authid'), '__UNTAGGED__') FROM pg_roles WHERE rolname='$db_user'")
  if [[ -z "$current_marker" ]]; then
    return
  fi
  if [[ "$current_marker" != "$role_marker" ]]; then
    echo "Refusing to drop role $db_user because it is not owned by the Phase 2 disposable runtime." >&2
    exit 65
  fi
  run_dropuser "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" "$db_user" >/dev/null
}

guard_local_target
require_command psql
require_command dropdb
require_command dropuser
require_command createdb

if [[ "$mode" != "restore" && "$mode" != "drop" ]]; then
  echo "Unsupported mode: $mode" >&2
  exit 64
fi

version_output=$(run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres -Atqc "SHOW server_version")
if [[ "$version_output" != "$expected_version"* ]]; then
  echo "Phase 2 smoke restore requires PostgreSQL $expected_version but found $version_output" >&2
  exit 65
fi

if [[ "$mode" == "drop" ]]; then
  drop_phase2_database
  drop_phase2_database_role
  drop_phase2_compatibility_role
  exit 0
fi

if [[ ! -f "$seed_file" ]]; then
  echo "Phase 2 smoke seed file does not exist: $seed_file" >&2
  exit 66
fi

drop_phase2_database
drop_phase2_database_role
drop_phase2_compatibility_role

if [[ $(run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres -Atqc \
  "SELECT count(*) FROM pg_roles WHERE rolname='adempiere'") != "0" ]]; then
  echo "The seed requires role adempiere, but that role already exists and is not owned by this disposable run." >&2
  exit 65
fi

run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres \
  -v ON_ERROR_STOP=1 -c "CREATE ROLE adempiere NOLOGIN" \
  -c "COMMENT ON ROLE adempiere IS 'ADempiere Phase 2 disposable compatibility role'" >/dev/null
run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres \
  -v ON_ERROR_STOP=1 -v "role_password=$db_password" >/dev/null <<SQL
CREATE ROLE $db_user SUPERUSER LOGIN PASSWORD :'role_password';
COMMENT ON ROLE $db_user IS '$role_marker';
SQL
run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres \
  -v ON_ERROR_STOP=1 -c "GRANT adempiere TO $db_user" >/dev/null
run_createdb "$db_password" -h "$db_host" -p "$db_port" -E UNICODE -O "$db_user" -U "$db_user" "$db_name"
run_psql "$system_password" -h "$db_host" -p "$db_port" -U "$system_user" -d postgres \
  -v ON_ERROR_STOP=1 -c "COMMENT ON DATABASE $db_name IS '$database_marker'" >/dev/null
run_psql "$db_password" -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
  -v ON_ERROR_STOP=1 -c "CREATE LANGUAGE plpgsql" >/dev/null 2>&1 || true
run_psql "$db_password" -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
  -v ON_ERROR_STOP=1 -f "$seed_file" >/dev/null
run_psql "$db_password" -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
  -v ON_ERROR_STOP=1 -c "ALTER ROLE $db_user SET search_path TO adempiere, pg_catalog" >/dev/null
