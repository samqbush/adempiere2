#!/usr/bin/env bash
set -euo pipefail

port=${1:-8888}
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
adempiere_home="$repo_root/build/phase3/runtime/Adempiere"
env_script="$adempiere_home/utils/myEnvironment.sh"
pid_file="$adempiere_home/tomcat/temp/phase5c-browser.pid"

if [[ ! -r "$env_script" ]]; then
	exit 0
fi

set +u
# shellcheck source=/dev/null
source "$env_script"
set -u

export CATALINA_BASE="$adempiere_home/tomcat"
export CATALINA_PID="$pid_file"
"$CATALINA_HOME/bin/shutdown.sh" >/dev/null 2>&1 || true

for _ in $(seq 1 30); do
	if ! curl -sS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null; then
		break
	fi
	sleep 1
done

if [[ -f "$pid_file" ]]; then
	pid=$(cat "$pid_file")
	if kill -0 "$pid" 2>/dev/null; then
		kill "$pid"
		wait "$pid" 2>/dev/null || true
	fi
	rm -f "$pid_file"
fi
