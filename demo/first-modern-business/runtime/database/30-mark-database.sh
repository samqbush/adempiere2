#!/usr/bin/env bash
set -euo pipefail

: "${ADEMPIERE_DB_USER:?}"
: "${ADEMPIERE_DEMO_MARKER:?}"

psql --variable ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=adempiere_user="$ADEMPIERE_DB_USER" \
  --set=marker="$ADEMPIERE_DEMO_MARKER" <<'SQL'
SELECT format('ALTER SCHEMA adempiere OWNER TO %I', :'adempiere_user')
\gexec
SELECT format('REASSIGN OWNED BY adempiere TO %I', :'adempiere_user')
\gexec
DROP OWNED BY adempiere;
SELECT format('REVOKE adempiere FROM %I', :'adempiere_user')
\gexec
DROP ROLE adempiere;
SELECT format('ALTER ROLE %I SET search_path TO adempiere, sqlj, pg_catalog',
              :'adempiere_user')
\gexec
SELECT format('COMMENT ON ROLE %I IS %L', :'adempiere_user', :'marker')
\gexec
SELECT format('COMMENT ON DATABASE %I IS %L', current_database(), :'marker')
\gexec
SQL
