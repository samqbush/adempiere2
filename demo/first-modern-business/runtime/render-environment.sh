#!/usr/bin/env bash
set -euo pipefail

: "${ADEMPIERE_DB_SERVER:?}"
: "${ADEMPIERE_DB_PORT:?}"
: "${ADEMPIERE_DB_NAME:?}"
: "${ADEMPIERE_DB_USER:?}"
: "${ADEMPIERE_DB_PASSWORD:?}"
: "${ADEMPIERE_DB_SYSTEM_PASSWORD:?}"
: "${ADEMPIERE_KEYSTORE_PASSWORD:?}"

home=/opt/Adempiere
template="$home/AdempiereEnvTemplate.properties"
target="$home/AdempiereEnv.properties"

[[ -r "$template" ]] || {
  echo "Missing environment template: $template" >&2
  exit 66
}

escape_replacement() {
  printf '%s' "$1" | sed 's/[&|]/\\&/g'
}

cp "$template" "$target"
replace() {
  local key=$1 value
  value=$(escape_replacement "$2")
  sed -i "s|^${key}=.*$|${key}=${value}|" "$target"
}

replace ADEMPIERE_HOME "$home"
replace JAVA_HOME "$JAVA_HOME"
replace ADEMPIERE_DB_SERVER "$ADEMPIERE_DB_SERVER"
replace ADEMPIERE_DB_PORT "$ADEMPIERE_DB_PORT"
replace ADEMPIERE_DB_NAME "$ADEMPIERE_DB_NAME"
replace ADEMPIERE_DB_SYSTEM "$ADEMPIERE_DB_SYSTEM_PASSWORD"
replace ADEMPIERE_DB_USER "$ADEMPIERE_DB_USER"
replace ADEMPIERE_DB_PASSWORD "$ADEMPIERE_DB_PASSWORD"
replace ADEMPIERE_APPS_PATH "/opt/tomcat"
replace ADEMPIERE_APPS_SERVER "0.0.0.0"
replace ADEMPIERE_WEB_PORT "8888"
replace ADEMPIERE_KEYSTORE "$home/keystore/myKeystore"
replace ADEMPIERE_KEYSTOREPASS "$ADEMPIERE_KEYSTORE_PASSWORD"

chmod 600 "$target"
