#!/usr/bin/env bash
#
# Phase 5d: stop the loopback-only modern runtime lane.
#
# Deterministic shutdown, not a best-effort kill: the lane is torn down through
# Tomcat's own stop path so the ZK session listener and WebUIServlet.destroy()
# run, and only then is the PID checked. A lane that is killed instead of stopped
# would leave SessionManager entries behind and make the next capture's database
# effect measurement wrong.
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
tomcat_dir=${1:-"$repo_root/build/phase5d/tomcat10"}
pid_file="$repo_root/build/phase5d/tomcat10-modern-web.pid"
properties_file="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
	"$properties_file")

if [[ ! -x "$tomcat_dir/bin/catalina.sh" ]]; then
	exit 0
fi

export CATALINA_PID="$pid_file"
"$tomcat_dir/bin/catalina.sh" stop 30 -force >/dev/null 2>&1 || true

for _ in $(seq 1 30); do
	if ! curl -sS -o /dev/null "http://127.0.0.1:$api_port/" 2>/dev/null; then
		break
	fi
	sleep 1
done

if [[ -f "$pid_file" ]]; then
	pid=$(cat "$pid_file")
	if kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
		wait "$pid" 2>/dev/null || true
	fi
	rm -f "$pid_file"
fi

if curl -sS -o /dev/null "http://127.0.0.1:$api_port/" 2>/dev/null; then
	echo "The Phase 5d modern web lane is still serving on 127.0.0.1:$api_port" >&2
	exit 70
fi
