#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 8 ]]; then
  echo "Usage: reset-phase5f-context-switch.sh <host> <port> <db> <user> <marker> <context> <baseline|verify|enable|disable|clear> <state-file>" >&2
  exit 64
fi

host=$1 port=$2 database=$3 user=$4 marker=$5 context=$6 action=$7 state_file=$8
: "${ADEMPIERE_PHASE5F_DB_PASSWORD:?ADEMPIERE_PHASE5F_DB_PASSWORD is required}"
export PGPASSWORD=$ADEMPIERE_PHASE5F_DB_PASSWORD

all_keys=(MODERN_WEB_ROOT_ENABLED MODERN_WEB_WSTORE_ENABLED \
  MODERN_WEB_ADMIN_ENABLED MODERN_WEB_MOBILE_ENABLED \
  MODERN_WEB_ADEMPIERE_ENABLED)

if [[ "$action" == baseline || "$action" == verify ]]; then
  key=
else
  case "$context" in
    /) key=MODERN_WEB_ROOT_ENABLED ;;
    /wstore) key=MODERN_WEB_WSTORE_ENABLED ;;
    /admin) key=MODERN_WEB_ADMIN_ENABLED ;;
    /mobile) key=MODERN_WEB_MOBILE_ENABLED ;;
    /adempiere) key=MODERN_WEB_ADEMPIERE_ENABLED ;;
    *) echo "No Phase 5f switch for $context" >&2; exit 64 ;;
  esac
fi

psql_cmd=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -d "$database" -U "$user" -At)
actual=$("${psql_cmd[@]}" -c "SELECT coalesce(shobj_description(oid, 'pg_database'),'') FROM pg_database WHERE datname=current_database()")
[[ "$actual" == "$marker" ]] || {
  echo "Refusing Phase 5f switch mutation: database marker mismatch" >&2
  exit 65
}

mkdir -p "$(dirname "$state_file")"

# All five Phase 5f keys, in a stable shape, so that a drifted or leaked switch
# row is detectable rather than silently contaminating a later shard. AD_SysConfig
# is shared mutable state and every shard of a --continue run reads it.
all_keys_sql=$(printf "'%s'," "${all_keys[@]}")
all_keys_sql=${all_keys_sql%,}
snapshot_all() {
  "${psql_cmd[@]}" -F $'\t' -c \
    "SELECT AD_SysConfig_ID,Name,Value,AD_Client_ID,AD_Org_ID,IsActive FROM AD_SysConfig WHERE Name IN ($all_keys_sql) ORDER BY Name,AD_SysConfig_ID"
}

if [[ "$action" == baseline ]]; then
  snapshot_all >"$state_file"
  exit 0
fi

if [[ "$action" == verify ]]; then
  [[ -f "$state_file" ]] || {
    echo "Refusing Phase 5f switch verification: no baseline at $state_file" >&2
    exit 65
  }
  if ! diff -u "$state_file" <(snapshot_all) >&2; then
    echo "Phase 5f switch state drifted from its captured baseline" >&2
    exit 66
  fi
  exit 0
fi

if [[ "$action" != clear && ! -f "$state_file" ]]; then
  "${psql_cmd[@]}" -F $'\t' -c \
    "SELECT AD_SysConfig_ID,Name,Value,AD_Client_ID,AD_Org_ID,IsActive FROM AD_SysConfig WHERE Name='$key' ORDER BY AD_SysConfig_ID" \
    >"$state_file"
fi

# The delete and the replacement rows are applied as one transaction. Split
# across separate psql invocations, an abort between them leaves the key absent
# rather than restored, which is indistinguishable from a deliberate cohort
# decision and would silently mis-route every subsequent shard.
apply_sql() {
  "${psql_cmd[@]}" --single-transaction -f - >/dev/null
}

case "$action" in
  enable|disable)
    value=N
    [[ "$action" == enable ]] && value=Y
    apply_sql <<SQL
DELETE FROM AD_SysConfig WHERE Name='$key';
INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value)
SELECT coalesce(max(AD_SysConfig_ID),1000000)+1,0,0,'Y',now(),100,now(),100,'$key','$value' FROM AD_SysConfig;
SQL
    ;;
  clear)
    {
      printf "DELETE FROM AD_SysConfig WHERE Name='%s';\n" "$key"
      if [[ -s "$state_file" ]]; then
        while IFS=$'\t' read -r id name value client org active; do
          [[ -n "$id" ]] || continue
          printf "INSERT INTO AD_SysConfig (AD_SysConfig_ID,AD_Client_ID,AD_Org_ID,IsActive,Created,CreatedBy,Updated,UpdatedBy,Name,Value) VALUES (%s,%s,%s,'%s',now(),100,now(),100,'%s','%s');\n" \
            "$id" "$client" "$org" "$active" "$name" "$value"
        done <"$state_file"
      fi
    } | apply_sql
    rm -f "$state_file"
    ;;
  *) echo "Action must be baseline, verify, enable, disable, or clear" >&2; exit 64 ;;
esac
