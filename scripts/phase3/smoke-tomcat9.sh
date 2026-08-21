#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: smoke-tomcat9.sh <catalina-home> <catalina-base> <adempiere-home> <port> <evidence-dir>" >&2
  exit 64
fi

catalina_home=$1
catalina_base=$2
adempiere_home=$3
port=$4
evidence_dir=$5
repo_root=$(git rev-parse --show-toplevel)
phase3_root="$repo_root/build/phase3"

for target in "$catalina_home" "$catalina_base" "$adempiere_home" "$evidence_dir"; do
  mkdir -p "$(dirname "$target")"
  resolved="$(cd "$(dirname "$target")" && pwd -P)/$(basename "$target")"
  if [[ "$resolved" != "$phase3_root/"* ]]; then
    echo "Phase 3 Tomcat paths must stay below $phase3_root. Refusing: $resolved" >&2
    exit 65
  fi
done
catalina_home="$(cd "$(dirname "$catalina_home")" && pwd -P)/$(basename "$catalina_home")"
catalina_base="$(cd "$(dirname "$catalina_base")" && pwd -P)/$(basename "$catalina_base")"
adempiere_home="$(cd "$(dirname "$adempiere_home")" && pwd -P)/$(basename "$adempiere_home")"
evidence_dir="$(cd "$(dirname "$evidence_dir")" && pwd -P)/$(basename "$evidence_dir")"
if [[ ! "$port" =~ ^[0-9]+$ ]]; then
  echo "Tomcat port must be numeric." >&2
  exit 65
fi

mkdir -p "$evidence_dir"
set +u
source "$adempiere_home/utils/myEnvironment.sh" >/dev/null
set -u
export CATALINA_HOME="$catalina_home"
export CATALINA_BASE="$catalina_base"
export CATALINA_PID="$catalina_base/temp/phase3-tomcat.pid"
export CATALINA_OPTS="-DADEMPIERE_HOME=$adempiere_home -DPropertyFile=$adempiere_home/AdempiereEnv.properties --add-opens java.base/java.lang=ALL-UNNAMED"

cleanup() {
  if [[ -f "$CATALINA_PID" ]]; then
    "$CATALINA_HOME/bin/catalina.sh" stop 30 -force >/dev/null 2>&1 || true
  fi
  if [[ -f "$CATALINA_BASE/logs/catalina.out" ]]; then
    cp "$CATALINA_BASE/logs/catalina.out" "$evidence_dir/catalina.out"
  fi
}
trap cleanup EXIT

: >"$CATALINA_BASE/logs/catalina.out"
"$CATALINA_HOME/bin/catalina.sh" start

status_url="http://127.0.0.1:$port/statusInfo"
status_ready=false
for _ in $(seq 1 90); do
  if curl --fail --silent "$status_url" >"$evidence_dir/statusInfo.html" 2>/dev/null; then
    status_ready=true
    break
  fi
  if ! kill -0 "$(cat "$CATALINA_PID")" 2>/dev/null; then
    cat "$CATALINA_BASE/logs/catalina.out" >&2
    exit 1
  fi
  sleep 2
done

if [[ "$status_ready" != "true" ]]; then
  cat "$CATALINA_BASE/logs/catalina.out" >&2
  exit 1
fi

for context in ROOT adempiere ADInterface admin mobile webui wstore; do
  if [[ ! -d "$CATALINA_BASE/webapps/$context" ]]; then
    echo "Tomcat did not expand context $context" >&2
    exit 1
  fi
  request_path="/$context/"
  [[ "$context" == "ROOT" ]] && request_path="/"
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:$port$request_path" || true)
  [[ -n "$status" ]] || status=000
  if [[ "$context" == "ADInterface" ]]; then
    [[ "$status" == "404" ]] || {
      echo "Expected ADInterface base path HTTP 404, got $status" >&2
      exit 1
    }
  elif [[ ! "$status" =~ ^[23][0-9][0-9]$ ]]; then
    echo "Tomcat context $context failed reachability with HTTP $status" >&2
    exit 1
  fi
  grep -Fq "Deployment of web application archive [$CATALINA_BASE/webapps/$context.war] has finished" \
    "$CATALINA_BASE/logs/catalina.out"
  printf '%s\t%s\n' "$context" "$status"
done >"$evidence_dir/context-status.tsv"

grep -q 'WebUIServlet.init: ADempiere started successfully' "$CATALINA_BASE/logs/catalina.out"
