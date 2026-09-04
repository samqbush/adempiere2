#!/usr/bin/env bash
set -euo pipefail

port=${1:-8888}
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
adempiere_home="$repo_root/build/phase3/runtime/Adempiere"
env_script="$adempiere_home/utils/myEnvironment.sh"

if [[ ! -r "$env_script" ]]; then
	echo "Installed Phase 3 environment is missing: $env_script" >&2
	exit 66
fi

set +u
# shellcheck source=/dev/null
source "$env_script"
set -u

if [[ -z "${ADEMPIERE_APPS_TYPE:-}" || -z "${ADEMPIERE_JAVA_OPTIONS:-}" ]]; then
	echo "Installed environment lacks application-server configuration" >&2
	exit 70
fi

export CATALINA_BASE="$adempiere_home/tomcat"
export CATALINA_PID="$CATALINA_BASE/temp/phase5c-browser.pid"
export CATALINA_TMPDIR="$CATALINA_BASE/temp"
export CATALINA_OPTS="$ADEMPIERE_JAVA_OPTIONS -Duser.timezone=UTC -Duser.language=en -Duser.country=US"
mkdir -p "$CATALINA_TMPDIR"

if curl -sS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null; then
	echo "Port $port is already serving HTTP; refusing to reuse an unknown lane" >&2
	exit 70
fi

"$CATALINA_HOME/bin/startup.sh" >/dev/null
for _ in $(seq 1 120); do
	status=$(curl -sS -o /dev/null -w '%{http_code}' \
		"http://127.0.0.1:$port/webui/" 2>/dev/null || true)
	if [[ "$status" == 200 ]]; then
		exit 0
	fi
	if [[ -f "$CATALINA_PID" ]]; then
		pid=$(cat "$CATALINA_PID")
		if ! kill -0 "$pid" 2>/dev/null; then
			break
		fi
	fi
	sleep 1
done

echo "Tomcat 9 did not serve a healthy /webui/ response" >&2
tail -80 "$CATALINA_BASE/logs/catalina.out" 2>/dev/null >&2 || true
bash "$repo_root/scripts/phase5/stop-legacy-browser-lane.sh" "$port" || true
exit 70
