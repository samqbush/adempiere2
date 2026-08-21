#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "Usage: cleanup-phase3-postgres.sh <host> <port> <db> <user> <system-user> <db-marker> <role-marker>" >&2
  exit 64
fi

db_host=$1
db_port=$2
db_name=$3
db_user=$4
system_user=$5
database_marker=$6
role_marker=$7
system_password=${ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD:-}

if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ||
      "$db_name" != "adempiere_phase3_ci" ||
      "$db_user" != "adempiere_phase3_ci" ]]; then
  echo "Refusing cleanup outside the exact local Phase 3 database target." >&2
  exit 65
fi

run_psql() {
  PGPASSWORD=$system_password psql \
    --host="$db_host" --port="$db_port" --username="$system_user" "$@"
}

current_database_marker=$(run_psql --dbname=postgres --tuples-only --no-align \
  --command="SELECT COALESCE(shobj_description(oid, 'pg_database'), '__UNTAGGED__') FROM pg_database WHERE datname='$db_name'")
current_role_marker=$(run_psql --dbname=postgres --tuples-only --no-align \
  --command="SELECT COALESCE(shobj_description(oid, 'pg_authid'), '__UNTAGGED__') FROM pg_roles WHERE rolname='$db_user'")

if [[ -n "$current_database_marker" ]]; then
  if [[ "$current_database_marker" != "$database_marker" ]]; then
    echo "Refusing to drop unmarked database $db_name." >&2
    exit 65
  fi
fi

if [[ -n "$current_role_marker" ]]; then
  if [[ "$current_role_marker" != "$role_marker" ]]; then
    echo "Refusing to drop unmarked role $db_user." >&2
    exit 65
  fi
fi

if [[ -n "$current_database_marker" ]]; then
  PGPASSWORD=$system_password dropdb \
    --host="$db_host" --port="$db_port" --username="$system_user" "$db_name"
fi

if [[ -n "$current_role_marker" ]]; then
  PGPASSWORD=$system_password dropuser \
    --host="$db_host" --port="$db_port" --username="$system_user" "$db_user"
fi
