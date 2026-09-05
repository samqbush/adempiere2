#!/usr/bin/env bash
set -euo pipefail

: "${ADEMPIERE_DB_USER:?}"
: "${ADEMPIERE_DB_PASSWORD:?}"

psql --variable ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=adempiere_user="$ADEMPIERE_DB_USER" \
  --set=adempiere_password="$ADEMPIERE_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I SUPERUSER LOGIN PASSWORD %L',
              :'adempiere_user', :'adempiere_password')
\gexec
SELECT format('ALTER DATABASE %I OWNER TO %I',
              current_database(), :'adempiere_user')
\gexec
CREATE ROLE adempiere NOLOGIN;
SELECT format('GRANT adempiere TO %I', :'adempiere_user')
\gexec
SQL
