#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 8 ]]; then
  echo "Usage: reset-phase5f-context-switch.sh <host> <port> <db> <user> <marker> <context> <enable|disable|clear> <state-file>" >&2
  exit 64
fi

host=$1 port=$2 database=$3 user=$4 marker=$5 context=$6 action=$7 state_file=$8
: "${ADEMPIERE_PHASE5F_DB_PASSWORD:?ADEMPIERE_PHASE5F_DB_PASSWORD is required}"
export PGPASSWORD=$ADEMPIERE_PHASE5F_DB_PASSWORD

case "$context" in
  /) key=MODERN_WEB_ROOT_ENABLED ;;
  /wstore) key=MODERN_WEB_WSTORE_ENABLED ;;
  /admin) key=MODERN_WEB_ADMIN_ENABLED ;;
  /mobile) key=MODERN_WEB_MOBILE_ENABLED ;;
  /adempiere) key=MODERN_WEB_ADEMPIERE_ENABLED ;;
  *) echo "No Phase 5f switch for $context" >&2; exit 64 ;;
esac

psql_cmd=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -d "$database" -U "$user" -At)
actual=$("${psql_cmd[@]}" -c "SELECT coalesce(shobj_description(oid, 'pg_database'),'') FROM pg_database WHERE datname=current_database()")
[[ "$actual" == "$marker" ]] || {
  echo "Refusing Phase 5f switch mutation: database marker mismatch" >&2
  exit 65
}

mkdir -p "$(dirname "$state_file")"
if [[ "$action" != clear && ! -f "$state_file" ]]; then
  "${psql_cmd[@]}" -F $'\t' -c \
    "SELECT AD_SysConfig_ID,Name,Value,AD_Client_ID,AD_Org_ID,IsActive FROM AD_SysConfig WHERE Name='$key' ORDER BY AD_SysConfig_ID" \
    >"$state_file"
fi

"${psql_cmd[@]}" -c "DELETE FROM AD_SysConfig WHERE Name='$key'" >/dev/null
case "$action" in
  enable|disable)
    value=N
    [[ "$action" == enable ]] && value=Y
    next=$("${psql_cmd[@]}" -c "SELECT coalesce(max(AD_SysConfig_ID),1000000)+1 FROM AD_SysConfig")
    "${psql_cmd[@]}" -c \
      "INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value) VALUES ($next,0,0,'Y',now(),100,now(),100,'$key','$value')" >/dev/null
    ;;
  clear)
    if [[ -s "$state_file" ]]; then
      while IFS=$'\t' read -r id name value client org active; do
        "${psql_cmd[@]}" -v id="$id" -v name="$name" -v value="$value" \
          -v client="$client" -v org="$org" -v active="$active" -c \
          "INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value) VALUES (:id,:client,:org,:'active',now(),100,now(),100,:'name',:'value')" >/dev/null
      done <"$state_file"
    fi
    rm -f "$state_file"
    ;;
  *) echo "Action must be enable, disable, or clear" >&2; exit 64 ;;
esac
