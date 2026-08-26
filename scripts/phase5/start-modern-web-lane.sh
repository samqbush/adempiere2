#!/usr/bin/env bash
#
# Phase 5d: start the loopback-only modern runtime lane.
#
# The lane is the SAME Tomcat 10.1.59 JVM the Phase 4 CXF API already runs in.
# That is deliberate: requirement "no classloader ambiguity with the colocated
# CXF WAR" is only testable if the two really are colocated, so the modern ZK
# slice is deployed beside ADInterface.war rather than in its own container.
#
# Nothing here creates a public route. prepare-tomcat10.sh binds the connector to
# 127.0.0.1 and disables the shutdown port, and this script refuses to continue
# if either changes.
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
modern_war=${1:?modern web WAR is required}
api_war=${2:?modern API WAR is required}
adempiere_home=${3:?ADEMPIERE_HOME is required}
tomcat_dir=${4:-"$repo_root/build/phase5d/tomcat10"}

properties_file="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
	"$properties_file")
log_file="$repo_root/build/phase5d/tomcat10-modern-web.log"
pid_file="$repo_root/build/phase5d/tomcat10-modern-web.pid"
evidence_dir="$repo_root/build/phase5d/evidence"

mkdir -p "$(dirname "$log_file")" "$evidence_dir"

if [[ ! -f "$modern_war" ]]; then
	echo "Modern web WAR is missing: $modern_war" >&2
	exit 66
fi

# Refuse to reuse a listener this script did not create. Attaching to an unknown
# process would make every measurement below meaningless.
if curl -sS -o /dev/null "http://127.0.0.1:$api_port/" 2>/dev/null; then
	echo "Port $api_port is already serving HTTP; refusing to reuse an unknown lane" >&2
	exit 70
fi

"$repo_root/scripts/phase4/prepare-tomcat10.sh" \
	"$tomcat_dir" "$api_war" "$adempiere_home"
cp "$modern_war" "$tomcat_dir/webapps/webui-modern.war"

grep -Fq 'address="127.0.0.1"' "$tomcat_dir/conf/server.xml"
grep -Fq '<Server port="-1"' "$tomcat_dir/conf/server.xml"

# The ZK desktop and the browser assertions are locale and timezone sensitive,
# and the frozen legacy oracle was captured under exactly these settings
# (scripts/phase5/start-legacy-browser-lane.sh).
export CATALINA_PID="$pid_file"
export CATALINA_OPTS="${CATALINA_OPTS:-} -Duser.timezone=UTC -Duser.language=en -Duser.country=US"

rm -f "$pid_file"
"$tomcat_dir/bin/catalina.sh" start >/dev/null 2>&1

ready=no
for _ in $(seq 1 180); do
	api_status=$(curl -sS -o /dev/null -w '%{http_code}' \
		"http://127.0.0.1:$api_port/ADInterface/services/ADService?wsdl" \
		2>/dev/null || true)
	web_status=$(curl -sS -o /dev/null -w '%{http_code}' \
		"http://127.0.0.1:$api_port/webui-modern/" 2>/dev/null || true)
	if [[ "$api_status" == 200 && "$web_status" == 200 ]]; then
		ready=yes
		break
	fi
	if [[ -f "$pid_file" ]] && ! kill -0 "$(cat "$pid_file")" 2>/dev/null; then
		break
	fi
	sleep 1
done

if [[ "$ready" != yes ]]; then
	echo "The Phase 5d modern web lane did not become ready" >&2
	echo "  /ADInterface/services/ADService?wsdl -> ${api_status:-none}" >&2
	echo "  /webui-modern/ -> ${web_status:-none}" >&2
	tail -200 "$log_file" 2>/dev/null >&2 || true
	tail -200 "$tomcat_dir/logs/catalina.out" 2>/dev/null >&2 || true
	"$repo_root/scripts/phase5/stop-modern-web-lane.sh" "$tomcat_dir" || true
	exit 70
fi

{
	printf 'modern_web\t200\thttp://127.0.0.1:%s/webui-modern/\n' "$api_port"
	printf 'modern_api\t200\thttp://127.0.0.1:%s/ADInterface/services/ADService?wsdl\n' \
		"$api_port"
	printf 'listener\t127.0.0.1:%s\n' "$api_port"
	printf 'shutdown_port\tdisabled\n'
	printf 'tomcat_pid\t%s\n' "$(cat "$pid_file")"
	printf 'catalina_home\t%s\n' "$tomcat_dir"
	printf 'adempiere_home\t%s\n' "$adempiere_home"
} >"$evidence_dir/modern-lane.tsv"

printf 'Phase 5d modern web lane ready on 127.0.0.1:%s (pid %s)\n' \
	"$api_port" "$(cat "$pid_file")"
