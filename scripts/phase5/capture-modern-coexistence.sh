#!/usr/bin/env bash
#
# Phase 5d: prove the modern ZK slice and the Phase 4 CXF API coexist in one JVM.
#
# Called by the modern browser capture WHILE an authenticated ZK session is open,
# so the SOAP corpus is replayed against a JVM that is genuinely holding modern
# UI state rather than against an idle container. Running the same corpus before
# or after the session would prove nothing about coexistence.
#
# usage:
#   capture-modern-coexistence.sh soap    <evidence_dir>
#   capture-modern-coexistence.sh runtime <evidence_dir> <label>
#   capture-modern-coexistence.sh isolation <evidence_dir> <legacy_port> \
#       <db_host> <db_port> <db_name> <db_user> <db_marker>
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
operation=${1:?operation is required}
evidence_dir=${2:?evidence directory is required}

properties_file="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
	"$properties_file")
pid_file="$repo_root/build/phase5d/tomcat10-modern-web.pid"

mkdir -p "$evidence_dir"

modern_pid() {
	if [[ ! -f "$pid_file" ]]; then
		echo "The Phase 5d modern lane PID file is missing: $pid_file" >&2
		exit 70
	fi
	cat "$pid_file"
}

case "$operation" in
soap)
	# The complete Phase 4 contract gate: 4 WSDLs compared byte for byte, all 33
	# frozen operation baselines, and the 11 additional valid-credential and
	# security scenarios.
	"$repo_root/scripts/phase4/smoke-cxf-api.sh" \
		"http://127.0.0.1:$api_port/ADInterface/services" full \
		>"$evidence_dir/phase4-soap-during-modern-session.log" 2>&1
	printf 'phase4_soap_corpus\tpass\twhile-modern-session-authenticated\n' \
		>"$evidence_dir/phase4-soap-during-modern-session.tsv"
	;;
runtime)
	label=${3:?label is required}
	pid=$(modern_pid)
	target="$evidence_dir/runtime-$label.tsv"
	: >"$target"

	# 1. Listener evidence: the modern lane binds loopback only, and the only
	#    listening socket this JVM owns is that one.
	{
		printf 'label\t%s\n' "$label"
		printf 'tomcat_pid\t%s\n' "$pid"
	} >>"$target"
	if command -v lsof >/dev/null 2>&1; then
		lsof -nP -p "$pid" -a -iTCP -sTCP:LISTEN 2>/dev/null \
			| awk 'NR > 1 {print "listener\t" $9}' >>"$target" || true
	fi

	# 2. Heap evidence from the JVM itself.
	if command -v jcmd >/dev/null 2>&1; then
		jcmd "$pid" GC.heap_info 2>/dev/null \
			| awk 'NR > 1 && NF {print "heap\t" $0}' >>"$target" || true

		# 3. Classloader evidence. Two ParallelWebappClassLoader instances must
		#    exist, one per context, and neither may have loaded the other's
		#    classes. That is the concrete meaning of "no classloader ambiguity
		#    with the colocated CXF WAR".
		jcmd "$pid" VM.classloaders show-classes 2>/dev/null \
			| grep -E 'WebappClassLoader|ParallelWebappClassLoader' \
			| sed 's/^[[:space:]]*/classloader\t/' >>"$target" || true
		jcmd "$pid" VM.classloader_stats 2>/dev/null \
			| grep -c 'WebappClassLoader' \
			| awk '{print "webapp_classloaders\t" $1}' >>"$target" || true

		# 4. ADEMPIERE_HOME evidence: exactly one value, shared by both contexts
		#    because it is a JVM system property, and it must be the marker-owned
		#    disposable installed tree.
		jcmd "$pid" VM.system_properties 2>/dev/null \
			| grep -E '^(ADEMPIERE_HOME|PropertyFile|catalina\.(home|base))=' \
			| sed 's/^/property\t/' >>"$target" || true
	fi

	# 5. Both contexts must still answer at this instant.
	for probe in \
		"modern_web|/webui-modern/" \
		"modern_api|/ADInterface/services/ADService?wsdl"; do
		name=${probe%%|*}
		path=${probe#*|}
		status=$(curl -sS -o /dev/null -w '%{http_code}' \
			"http://127.0.0.1:$api_port$path" 2>/dev/null || echo none)
		printf '%s\t%s\n' "$name" "$status" >>"$target"
	done
	;;
isolation)
	legacy_port=${3:?legacy Tomcat 9 port is required}
	db_host=${4:?db host is required}
	db_port=${5:?db port is required}
	db_name=${6:?db name is required}
	db_user=${7:?db user is required}
	db_password=${ADEMPIERE_PHASE5D_DB_PASSWORD:?database password environment variable is required}
	db_marker=${8:?db marker is required}
	pid=$(modern_pid)
	target="$evidence_dir/lane-isolation.tsv"
	: >"$target"

	if [[ "$legacy_port" == "$api_port" ]]; then
		echo "Tomcat 9 and Tomcat 10 would share port $api_port" >&2
		exit 70
	fi
	printf 'legacy_port\t%s\n' "$legacy_port" >>"$target"
	printf 'modern_port\t%s\n' "$api_port" >>"$target"
	printf 'ports_distinct\tyes\n' >>"$target"

	# The legacy lane must actually be running. A "distinct ports" claim taken
	# while only one container exists proves nothing about coexistence, which is
	# why phase5dModernWebSmoke starts the Tomcat 9 browser lane as well.
	legacy_pid_file="$repo_root/build/phase3/runtime/Adempiere/tomcat/temp/phase5c-browser.pid"
	if [[ ! -f "$legacy_pid_file" ]]; then
		echo "The Tomcat 9 lane is not running; lane isolation cannot be measured" >&2
		exit 70
	fi
	legacy_pid=$(cat "$legacy_pid_file")
	if ! kill -0 "$legacy_pid" 2>/dev/null; then
		echo "The Tomcat 9 lane PID $legacy_pid is not alive" >&2
		exit 70
	fi
	printf 'legacy_pid\t%s\n' "$legacy_pid" >>"$target"
	if [[ "$legacy_pid" == "$pid" ]]; then
		echo "Tomcat 9 and Tomcat 10 report the same PID" >&2
		exit 70
	fi
	printf 'jvms_distinct\tyes\n' >>"$target"
	legacy_status=$(curl -sS -o /dev/null -w '%{http_code}' \
		"http://127.0.0.1:$legacy_port/webui/" 2>/dev/null || echo none)
	if [[ "$legacy_status" != 200 ]]; then
		echo "The Tomcat 9 lane did not answer /webui/ (HTTP $legacy_status)" >&2
		exit 70
	fi
	printf 'legacy_web\t%s\thttp://127.0.0.1:%s/webui/\n' \
		"$legacy_status" "$legacy_port" >>"$target"
	printf 'modern_pid\t%s\n' "$pid" >>"$target"

	# Both lanes are only allowed to share the marker-owned disposable database.
	# The marker is read from the database itself so a lane pointed at a real
	# installation fails here instead of writing to it.
	marker=$(PGPASSWORD="$db_password" psql \
		--host "$db_host" --port "$db_port" --dbname "$db_name" \
		--username "$db_user" --no-align --tuples-only \
		--command "select shobj_description(oid, 'pg_database') from pg_database where datname = current_database()" \
		2>/dev/null || true)
	if [[ "$marker" != "$db_marker" ]]; then
		echo "The Phase 5d lanes are not pointed at the marker-owned disposable database" >&2
		echo "  expected: $db_marker" >&2
		echo "  found:    ${marker:-<none>}" >&2
		exit 70
	fi
	printf 'shared_database\t%s@%s:%s\n' "$db_name" "$db_host" "$db_port" >>"$target"
	printf 'shared_database_marker\t%s\n' "$db_marker" >>"$target"

	# Nothing else is shared: separate CATALINA_BASE trees and separate
	# deployed context sets.
	legacy_base="$repo_root/build/phase3/runtime/Adempiere/tomcat"
	modern_base="$repo_root/build/phase5d/tomcat10"
	if [[ "$(cd "$legacy_base" 2>/dev/null && pwd -P)" == \
			"$(cd "$modern_base" 2>/dev/null && pwd -P)" ]]; then
		echo "Tomcat 9 and Tomcat 10 share a CATALINA_BASE" >&2
		exit 70
	fi
	printf 'legacy_catalina_base\t%s\n' "$legacy_base" >>"$target"
	printf 'modern_catalina_base\t%s\n' "$modern_base" >>"$target"
	printf 'catalina_bases_distinct\tyes\n' >>"$target"
	# Deployed archives only. Tomcat also leaves an exploded directory beside each
	# WAR, and counting both would report four contexts where two are deployed.
	for context in $(ls "$modern_base"/webapps/*.war 2>/dev/null); do
		printf 'modern_context\t%s\n' "$(basename "$context")" >>"$target"
	done
	;;
*)
	echo "Unknown operation: $operation" >&2
	exit 64
	;;
esac
