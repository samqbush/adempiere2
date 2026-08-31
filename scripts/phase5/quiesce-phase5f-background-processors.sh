#!/usr/bin/env bash
set -euo pipefail

# ADempiere boots a set of timer-driven background processors (accounting,
# request, workflow, alert, scheduler). They write to AD_/C_/M_ business tables
# on their own cadence, with no relationship to any HTTP route.
#
# Phase 5f attributes a database effect to the route vector that was in flight,
# by diffing a full data dump taken immediately before and after each request.
# A processor that fires inside that window is therefore recorded as an unowned
# write of whichever route happened to be observed. Run 33360842891 shows this
# directly: 13 of 129 observations carried changes, and the dominant tables were
# C_AcctProcessor(+Log), R_RequestProcessor(+Log), AD_WorkflowProcessor(+Log)
# and AD_AlertProcessor(+Log), plus one accounting posting burst that touched
# Fact_Acct, C_Invoice, GL_Journal, M_InOut and eleven more tables under a
# `no-new-write` banner-redirect route.
#
# Widening the ownership contract to accept those tables would make a genuine
# unowned write invisible, which validate-phase5f-runtime-evidence.py requires
# detecting. So the ambient writer is removed instead of the check: the
# processors are deactivated before the container boots, so AdempiereServerMgr
# never schedules them, and the observed effects belong to the routes.
#
# This narrows the runtime under test in one specific, recorded way, and it
# narrows it identically for the legacy and the modern leg, so route parity is
# unaffected. The marker guard and the state file keep the mutation confined to
# the disposable Phase 3 database and reversible.

if [[ $# -ne 7 ]]; then
  echo "Usage: quiesce-phase5f-background-processors.sh <host> <port> <db> <user> <marker> <quiesce|verify|restore> <state-file>" >&2
  exit 64
fi

host=$1 port=$2 database=$3 user=$4 marker=$5 action=$6 state_file=$7
: "${ADEMPIERE_PHASE5F_DB_PASSWORD:?ADEMPIERE_PHASE5F_DB_PASSWORD is required}"
export PGPASSWORD=$ADEMPIERE_PHASE5F_DB_PASSWORD

psql_cmd=(psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -d "$database" -U "$user" -At)

actual=$("${psql_cmd[@]}" -c "SELECT coalesce(shobj_description(oid, 'pg_database'),'') FROM pg_database WHERE datname=current_database()")
[[ "$actual" == "$marker" ]] || {
  echo "Refusing Phase 5f processor quiesce: database marker mismatch" >&2
  exit 65
}

# The eight timer sources AdempiereServerMgr.startServers() actually schedules
# (serverRoot/src/main/server/org/compiere/server/AdempiereServerMgr.java:114,
# 124, 134, 144, 154, 164, 174, 184). Only these are deactivated.
scheduler_sources=(ad_alertprocessor ad_ldapprocessor ad_scheduler \
  ad_workflowprocessor c_acctprocessor c_projectprocessor imp_processor \
  r_requestprocessor)

# Tables whose name matches the discovery pattern but which are configuration
# read by the routes under test, not timer sources. C_PaymentProcessor is
# selected by MPaymentProcessor.find() with IsActive='Y'
# (base/src/org/compiere/model/MPaymentProcessor.java:71), so deactivating it
# would silently change the /wstore checkout and payment routes rather than
# merely removing an ambient writer.
reviewed_non_scheduler=(c_paymentprocessor exp_processor)

# Discovery exists to detect drift in both directions: a scheduler source that
# disappeared, and a new processor-shaped table that nobody has classified.
discover_sql="SELECT c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND lower(a.attname) = 'isactive'
WHERE c.relkind = 'r'
  AND n.nspname = ANY (current_schemas(false))
  AND (c.relname LIKE '%processor' OR c.relname = 'ad_scheduler')
ORDER BY c.relname"

# Assigned first so that set -e observes psql's own exit status: a process
# substitution would hide a connection failure behind mapfile's status and the
# truncated result would be reported as schema drift.
discovered_text=$("${psql_cmd[@]}" -c "$discover_sql")
discovered=()
while IFS= read -r line; do
  [[ -n "$line" ]] && discovered+=("$line")
done <<<"$discovered_text"

contains() {
  local needle=$1 item
  shift
  for item in "$@"; do
    [[ "$item" == "$needle" ]] && return 0
  done
  return 1
}

for want in "${scheduler_sources[@]}"; do
  contains "$want" "${discovered[@]}" || {
    echo "Refusing Phase 5f processor quiesce: scheduler source $want was not discovered" >&2
    exit 66
  }
done

for have in "${discovered[@]}"; do
  [[ -n "$have" ]] || continue
  contains "$have" "${scheduler_sources[@]}" && continue
  contains "$have" "${reviewed_non_scheduler[@]}" && continue
  echo "Refusing Phase 5f processor quiesce: unclassified processor table $have" >&2
  exit 66
done

tables=("${scheduler_sources[@]}")

mkdir -p "$(dirname "$state_file")"

snapshot() {
  for table in "${tables[@]}"; do
    "${psql_cmd[@]}" -F $'\t' -c \
      "SELECT '$table', ${table}_ID, IsActive FROM $table ORDER BY ${table}_ID"
  done
}

assert_all_inactive() {
  local table count
  for table in "${tables[@]}"; do
    count=$("${psql_cmd[@]}" -c "SELECT count(*) FROM $table WHERE IsActive='Y'")
    [[ "$count" == "0" ]] || {
      echo "Phase 5f background processor table $table has $count active definitions" >&2
      exit 67
    }
  done
}

case "$action" in
  quiesce)
    snapshot >"$state_file"
    {
      for table in "${tables[@]}"; do
        printf "UPDATE %s SET IsActive='N', Updated=now(), UpdatedBy=100 WHERE IsActive='Y';\n" "$table"
      done
    } | "${psql_cmd[@]}" --single-transaction -f - >/dev/null
    assert_all_inactive
    printf 'Quiesced %d Phase 5f background processor tables\n' "${#tables[@]}"
    ;;
  verify)
    assert_all_inactive
    ;;
  restore)
    [[ -f "$state_file" ]] || {
      echo "Refusing Phase 5f processor restore: no state at $state_file" >&2
      exit 65
    }
    {
      while IFS=$'\t' read -r table id active; do
        [[ -n "$table" ]] || continue
        printf "UPDATE %s SET IsActive='%s' WHERE %s_ID=%s;\n" \
          "$table" "$active" "$table" "$id"
      done <"$state_file"
    } | "${psql_cmd[@]}" --single-transaction -f - >/dev/null
    rm -f "$state_file"
    ;;
  *) echo "Action must be quiesce, verify, or restore" >&2; exit 64 ;;
esac
