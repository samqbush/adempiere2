#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_PASSWORD:?}"
: "${POSTGRES_DB:?}"
: "${ADEMPIERE_DB_USER:?}"
: "${ADEMPIERE_DEMO_MARKER:?}"

result=$(
  PGPASSWORD="$POSTGRES_PASSWORD" psql \
    --host 127.0.0.1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --set=adempiere_user="$ADEMPIERE_DB_USER" \
    --set=marker="$ADEMPIERE_DEMO_MARKER" \
    --command="
      SELECT
        shobj_description(d.oid, 'pg_database') = :'marker'
        AND shobj_description(r.oid, 'pg_authid') = :'marker'
      FROM pg_database d
      JOIN pg_roles r ON r.rolname = :'adempiere_user'
      WHERE d.datname = current_database()"
)

[[ "$result" == "t" ]]
