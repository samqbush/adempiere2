#!/usr/bin/env bash
set -euo pipefail

: "${ADEMPIERE_DB_USER:?}"
: "${ADEMPIERE_DB_PASSWORD:?}"

PGPASSWORD="$ADEMPIERE_DB_PASSWORD" psql \
  --variable ON_ERROR_STOP=1 \
  --username "$ADEMPIERE_DB_USER" \
  --dbname "$POSTGRES_DB" \
  --file /opt/adempiere-demo/Adempiere_pg.dmp
