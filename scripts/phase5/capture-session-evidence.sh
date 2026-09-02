#!/usr/bin/env bash
# Phase 5g-1b: the AD_Session evidence model.
#
# WHY THIS EXISTS AND WHY IT IS SEPARATE FROM THE BUSINESS ORACLE
#
# AD_Session is listed in contracts/legacy-web-write-v1/ambient-tables.tsv, so a
# modern change to an EXISTING session row does not fail the business oracle's
# "changed but undeclared" backstop -- the row is exempted by design, because
# session bookkeeping is written for any logged-in flow whether the Business
# Partner write was correct or not. That exemption is correct for the business
# oracle and wrong for the session lifecycle, which is exactly the thing Phase
# 5g-1b's H6 matrix has to observe rather than assume. This script is that
# separate, non-business model: it records AD_Session explicitly so the login,
# in-flight and logout phases of a routed modern session are evidence rather
# than an ambient blur inside the sentinel.
#
# WHY IT ONLY READS
#
# The business oracle is measured through this same database. A capture-time
# script that wrote to AD_Session -- even a harmless probe row -- would show up
# in the very sentinel the parity captures are scored against, so this script
# never issues anything but SELECT. The marker guard below is the same one the
# read-mostly cohort fixture uses, kept even though nothing here mutates, so a
# future edit that adds a write cannot silently run against a real database.
set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "Usage: ADEMPIERE_PHASE5D_DB_PASSWORD=... capture-session-evidence.sh <host> <port> <db> <user> <marker> <label> <out-file>" >&2
  exit 64
fi

db_host=$1
db_port=$2
db_name=$3
db_user=$4
database_marker=$5
label=$6

# psql does not interpolate :'var' inside a --command string, so the label is
# embedded as a SQL literal by the shell instead. It is constrained to a
# conservative character class first: the callers pass fixed lane phase names
# ('pre-A', 'post-logout', ...), and refusing anything else keeps a value that
# reaches a SQL statement from ever being able to carry quoting with it.
if [[ ! $label =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "session evidence label '$label' must match ^[A-Za-z0-9_-]+$" >&2
  exit 64
fi
out_file=$7

db_password=${ADEMPIERE_PHASE5D_DB_PASSWORD:?application database password environment variable is required}

# The same exact-target guard the Phase 3 and Phase 5b/5d/5e scripts use. It is
# what makes this safe to run against a developer's shared local PostgreSQL: it
# can only ever read the one disposable Phase 3 database.
if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ||
      "$db_name" != "adempiere_phase3_ci" ||
      "$db_user" != "adempiere_phase3_ci" ]]; then
  echo "Refusing to read session evidence outside the exact local Phase 3 database target." >&2
  exit 65
fi

run_psql() {
  PGPASSWORD=$db_password psql \
    --host="$db_host" \
    --port="$db_port" \
    --username="$db_user" \
    --dbname="$db_name" \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    "$@"
}

# The marker check is a database COMMENT, not an argument the caller controls, so
# a caller that points this at an unmarked production-looking database is refused
# rather than trusted. Read-mostly it may be, but reading a live customer's
# AD_Session is itself a disclosure this guard exists to prevent.
actual_marker=$(run_psql --command="
  SELECT shobj_description(oid, 'pg_database')
  FROM pg_database
  WHERE datname = current_database()")
if [[ "$actual_marker" != "$database_marker" ]]; then
  echo "Refusing to read session evidence from an unmarked database $db_name." >&2
  exit 65
fi

mkdir -p "$(dirname "$out_file")"

# A commented column header, so a downloaded evidence file is self-describing.
# It is a comment because the surrounding smoke concatenates every
# session-evidence/*.tsv into one lifecycle file and the evidence validator's
# row reader skips '#' lines: a bare header line would otherwise become a bogus
# data row once several files are catted together.
printf '# label\trecord_type\tad_session_id\tad_client_id\tad_org_id\tcreated_by\tad_role_id\tprocessed\tisactive\twebsession\tremote_addr\tupdated_after_created\tmetric_value\n' \
  >"$out_file"

# WHY THE COLUMNS ARE WHAT THEY ARE
#
# AD_Session has no AD_User_ID column: the acting user of a session is carried in
# CreatedBy (and UpdatedBy), so `created_by` IS the session's user identity here.
# Recording a non-existent AD_Session.AD_User_ID would have been a guess; the
# schema was read (db/ddlutils/model/AD_SESSION.xml) before this query was
# written.
#
# Created and Updated are deliberately NOT emitted as raw timestamps.
# SessionManager refreshes Updated while a session is alive, so a raw timestamp
# is volatile between two otherwise identical captures and would fail the A/B
# self-diff for a reason that has nothing to do with the runtime. What actually
# carries lifecycle meaning is the RELATIVE order of the two: `updated_after_created`
# is `t` once the row has been touched after it was inserted (a live or
# refreshed session) and `f` for a row still at its creation instant. That is
# stable across runs and is the fact the lifecycle observation needs.
#
# WebSession and Remote_Addr are both real columns and both recorded; a session
# with neither is a server-side (non-web) session, which the '-' placeholder
# preserves rather than hiding.
#
# Every row is prefixed with <label> so the before-login, after-login and
# after-logout phases of one lifecycle -- captured by separate calls -- land in
# one comparable, greppable file set.
#
# The rows are ordered by AD_Session_ID so the file is byte-stable: an unordered
# read would reorder between captures and, again, break the self-diff for no
# runtime reason.
run_psql \
  --field-separator=$'\t' \
  --command="
    SELECT
      '$label',
      'session',
      AD_Session_ID,
      AD_Client_ID,
      AD_Org_ID,
      CreatedBy,
      AD_Role_ID,
      Processed,
      IsActive,
      COALESCE(WebSession, '-'),
      COALESCE(Remote_Addr, '-'),
      CASE WHEN Updated > Created THEN 't' ELSE 'f' END,
      '-'
    FROM AD_Session
    ORDER BY AD_Session_ID" >>"$out_file"

# The AD_ChangeLog total.
#
# 5g-1b must assert explicitly that AD_ChangeLog stays as 5g-1a froze it, so the
# session model carries the count as its own aggregate record rather than
# leaving it to a table nobody re-reads. The count occupies metric_value (the
# last field); the session-specific fields are '-' because this row is an
# aggregate, not a session. record_type disambiguates the two shapes so a reader
# never has to guess which a row is.
run_psql \
  --field-separator=$'\t' \
  --command="
    SELECT '$label', 'ad_changelog_count',
      '-', '-', '-', '-', '-', '-', '-', '-', '-', '-',
      (SELECT count(*) FROM AD_ChangeLog)" >>"$out_file"

echo "Session evidence for '$label' written to $out_file"
