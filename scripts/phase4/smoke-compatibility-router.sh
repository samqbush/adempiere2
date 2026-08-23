#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 7 && $# -ne 10 ]]; then
	echo "Usage: smoke-compatibility-router.sh <db-host> <db-port> <db-name> <db-user> <db-password> <db-system-password> <database-marker> [<installed-home> <modern-home> <modern-war>]" >&2
	exit 64
fi

db_host=$1
db_port=$2
db_name=$3
db_user=$4
db_password=$5
db_system_password=$6
database_marker=$7
repo_root=$(git rev-parse --show-toplevel)
phase3_root="$repo_root/build/phase3"
phase4_root="$repo_root/build/phase4"
legacy_home="$phase3_root/tomcat"
legacy_base="$phase3_root/runtime/Adempiere/tomcat"
adempiere_home="$phase3_root/runtime/Adempiere"
modern_home="$phase4_root/tomcat10"
modern_war="$repo_root/org.adempiere.webservice/build/libs/ADInterface-Modern-1.0.war"
if [[ $# -eq 10 ]]; then
	adempiere_home=$8
	legacy_base="$adempiere_home/tomcat"
	modern_home=$9
	modern_war=${10}
fi
evidence_dir="$phase4_root/evidence/compatibility-router"
legacy_log="$evidence_dir/tomcat9.log"
modern_log="$evidence_dir/tomcat10.log"

if [[ "$db_host" != "127.0.0.1" && "$db_host" != "localhost" ]]; then
	echo "Compatibility-router smoke requires a loopback database." >&2
	exit 65
fi
if [[ "$db_name" != "adempiere_phase3_ci" || "$db_user" != "$db_name" ]]; then
	echo "Compatibility-router smoke requires the named disposable Phase 3 database and role." >&2
	exit 65
fi
if [[ ! "$db_port" =~ ^[0-9]+$ ]]; then
	echo "Database port must be numeric." >&2
	exit 65
fi

actual_marker=$(PGPASSWORD="$db_system_password" \
	psql -h "$db_host" -p "$db_port" -U postgres -d postgres \
	-Atc "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname='$db_name'")
if [[ "$actual_marker" != "$database_marker" ]]; then
	echo "Refusing an unmarked database: expected '$database_marker', got '$actual_marker'." >&2
	exit 65
fi
export PGPASSWORD="$db_password"
server_version=$(psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
	-Atc "SHOW server_version")
if [[ "$server_version" != 14.6* ]]; then
	echo "Compatibility-router smoke requires PostgreSQL 14.6, got $server_version." >&2
	exit 65
fi

mkdir -p "$evidence_dir"
rm -f "$legacy_log" "$modern_log"

legacy_pid=
modern_pid=
cleanup() {
	if [[ -n "$legacy_pid" ]] && kill -0 "$legacy_pid" 2>/dev/null; then
		kill "$legacy_pid"
		wait "$legacy_pid" || true
	fi
	if [[ -n "$modern_pid" ]] && kill -0 "$modern_pid" 2>/dev/null; then
		kill "$modern_pid"
		wait "$modern_pid" || true
	fi
}
trap cleanup EXIT

"$repo_root/scripts/phase4/prepare-tomcat10.sh" \
	"$modern_home" \
	"$modern_war" \
	"$adempiere_home"

set +u
source "$adempiere_home/utils/myEnvironment.sh" >/dev/null
set -u
export ADEMPIERE_APPS_TYPE=tomcat
export CATALINA_HOME="$legacy_home"
export CATALINA_BASE="$legacy_base"
export CATALINA_OPTS="-DADEMPIERE_HOME=$adempiere_home -DPropertyFile=$adempiere_home/AdempiereEnv.properties --add-opens java.base/java.lang=ALL-UNNAMED"
"$legacy_home/bin/catalina.sh" run >"$legacy_log" 2>&1 &
legacy_pid=$!

CATALINA_HOME="$modern_home" CATALINA_BASE="$modern_home" \
	"$modern_home/bin/catalina.sh" run >"$modern_log" 2>&1 &
modern_pid=$!

wait_for() {
	local url=$1
	local pid=$2
	local log=$3
	for _ in $(seq 1 90); do
		if curl --fail --silent --output /dev/null "$url"; then
			return
		fi
		if ! kill -0 "$pid" 2>/dev/null; then
			tail -100 "$log" >&2
			exit 1
		fi
		sleep 1
	done
	echo "Timed out waiting for $url" >&2
	tail -100 "$log" >&2
	exit 1
}
wait_for "http://127.0.0.1:8888/statusInfo" "$legacy_pid" "$legacy_log"
wait_for "http://127.0.0.1:8890/ADInterface/services/ADService?wsdl" \
	"$modern_pid" "$modern_log"

audit_start=$(($(wc -l <"$legacy_log") + 1))
"$repo_root/scripts/phase4/smoke-cxf-api.sh" \
	"http://127.0.0.1:8888/ADInterface/servlet/XFireServlet" baseline-only
"$repo_root/scripts/phase4/smoke-cxf-api.sh" \
	"http://127.0.0.1:8888/ADInterface/services" full

assert_modern_route() {
	local service=$1
	local operation=$2
	local audit="SOAP route service=$service operation=$operation target=MODERN"
	for _ in $(seq 1 20); do
		if awk -v start="$audit_start" -v audit="$audit" \
			'NR >= start && index($0, audit) { found = 1 } END { exit !found }' \
			"$legacy_log"; then
			return
		fi
		sleep 0.25
	done
	echo "Missing CXF audit event for $service.$operation." >&2
	tail -100 "$legacy_log" >&2
	exit 1
}

operation_count=0
while IFS=$'\t' read -r service operation _; do
	if [[ "$service" == \#* || -z "$service" ]]; then
		continue
	fi
	assert_modern_route "$service" "$operation"
	operation_count=$((operation_count + 1))
done <"$repo_root/org.adempiere.webservice/contracts/xfire-v1/operation-round-trips.tsv"
if [[ "$operation_count" -ne 33 ]]; then
	echo "Verified $operation_count CXF routes, expected 33." >&2
	exit 1
fi
if awk -v start="$audit_start" \
	'NR >= start && index($0, "target=LEGACY") { found = 1 } END { exit !found }' \
	"$legacy_log"; then
	echo "The retired XFire route was selected after final cutover." >&2
	exit 1
fi

printf '%s\n' \
	'Both historical URL forms preserve all 33 operation baselines through CXF' \
	'The primary historical path preserves all 11 additional scenarios and state deltas' \
	'All 33 operations emit fresh CXF-only routing audit events' \
	'No request selects the retired XFire route' \
	>"$evidence_dir/result.txt"
echo "Compatibility router preserves both public paths with CXF-only routing"
