#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
tomcat_dir="$repo_root/build/phase4/tomcat10"
log_file="$repo_root/build/phase4/tomcat10-smoke.log"
base_url="http://127.0.0.1:8890/ADInterface/services"
mode=${1:-boot-only}

"$repo_root/scripts/phase4/prepare-tomcat10.sh"
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
	if curl --fail --silent --show-error --output /dev/null \
			"$base_url/ADService?wsdl"; then
		"$repo_root/scripts/phase4/smoke-cxf-api.sh" \
			"$base_url" "$mode"
		exit 0
	fi
	if ! kill -0 "$tomcat_pid" 2>/dev/null; then
		echo "Tomcat 10 exited before the CXF API became ready" >&2
		tail -100 "$log_file" >&2
		exit 1
	fi
	sleep 1
done

echo "Timed out waiting for the CXF API" >&2
tail -100 "$log_file" >&2
exit 1
