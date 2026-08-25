#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
modern_war=${1:?modern web WAR is required}
tomcat_dir="$repo_root/build/phase5c/tomcat10"
log_file="$repo_root/build/phase5c/tomcat10-packaging.log"
api_war="$repo_root/org.adempiere.webservice/build/libs/ADInterface-Modern-1.0.war"
adempiere_home="$repo_root/build/phase3/runtime/Adempiere"
api_url="http://127.0.0.1:8890/ADInterface/services/ADService?wsdl"
marker_url="http://127.0.0.1:8890/webui-modern/__phase5c/packaging"

"$repo_root/scripts/phase4/prepare-tomcat10.sh" \
	"$tomcat_dir" "$api_war" "$adempiere_home"
cp "$modern_war" "$tomcat_dir/webapps/webui-modern.war"

"$tomcat_dir/bin/catalina.sh" run >"$log_file" 2>&1 &
tomcat_pid=$!
cleanup() {
	if kill -0 "$tomcat_pid" 2>/dev/null; then
		kill "$tomcat_pid"
		wait "$tomcat_pid" || true
	fi
}
trap cleanup EXIT

for attempt in $(seq 1 60); do
	if curl --fail --silent --show-error --output /dev/null "$api_url"; then
		status=$(curl --silent --show-error --output "$repo_root/build/phase5c/marker.txt" \
			--write-out '%{http_code}' "$marker_url")
		if [[ "$status" != 503 ]]; then
			echo "Phase 5c packaging marker returned HTTP $status" >&2
			exit 1
		fi
		grep -Fq 'Phase 5c packaging only; modern web UI unavailable' \
			"$repo_root/build/phase5c/marker.txt"
		if grep -E 'SEVERE.*(webui-modern|Phase 5c)' "$log_file"; then
			echo "Tomcat logged a severe Phase 5c deployment error" >&2
			exit 1
		fi
		printf 'api\t200\nmarker\t503\nlistener\t127.0.0.1:8890\n' \
			>"$repo_root/build/phase5c/packaging-smoke.tsv"
		exit 0
	fi
	if ! kill -0 "$tomcat_pid" 2>/dev/null; then
		echo "Tomcat 10 exited before the Phase 5c contexts became ready" >&2
		tail -100 "$log_file" >&2
		exit 1
	fi
	sleep 1
done

echo "Timed out waiting for the Phase 5c runtime" >&2
tail -100 "$log_file" >&2
exit 1
