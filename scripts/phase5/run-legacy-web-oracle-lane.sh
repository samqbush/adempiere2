#!/usr/bin/env bash
# Phase 5b - boot the installed Tomcat 9 lane, run an oracle command against it,
# and always shut it down again.
#
# The capture and replay scripts deliberately do not manage the server: keeping
# lifecycle in one place means the oracle is always produced against an
# identically configured runtime, and means a failed capture can never leave a
# stray Tomcat holding the port or the disposable database open.
#
# Two JVM settings are load-bearing:
#
#   -Duser.timezone=UTC   Server-rendered dates come from Tomcat's JVM, not from
#                         the capture shell, so exporting TZ in the capture
#                         cannot make them reproducible. Pinning the server JVM
#                         is what makes the oracle replay identically on a
#                         developer machine in MDT and in CI in UTC.
#   -Duser.language/-Duser.country
#                         Locale reaches rendered labels and number formats.
#
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: run-legacy-web-oracle-lane.sh <http-port> <command> [args...]" >&2
  exit 64
fi

port=$1
shift

repo_root=$(git rev-parse --show-toplevel)
adempiere_home="$repo_root/build/phase3/runtime/Adempiere"
env_script="$adempiere_home/utils/myEnvironment.sh"

if [[ ! -r "$env_script" ]]; then
  echo "Phase 5b requires the installed Phase 3 product at $adempiere_home." >&2
  echo "Run phase3AntDatabaseBuild first." >&2
  exit 66
fi

# myEnvironment.sh references optional app-server variables that may be unset
# (WILDFLY_HOME, JBOSS_HOME), so nounset is relaxed only across the source.
set +u
# shellcheck source=/dev/null
source "$env_script"
set -u

# ADEMPIERE_APPS_TYPE is not optional: without it every database-backed page
# returns HTTP 500, and the capture would freeze an oracle of error pages.
if [[ -z "${ADEMPIERE_APPS_TYPE:-}" ]]; then
  echo "myEnvironment.sh did not set ADEMPIERE_APPS_TYPE; refusing to capture." >&2
  exit 70
fi

export CATALINA_BASE="$adempiere_home/tomcat"
export CATALINA_PID="$CATALINA_BASE/temp/phase5b.pid"
export CATALINA_TMPDIR="$CATALINA_BASE/temp"
# ADEMPIERE_JAVA_OPTIONS carries -DADEMPIERE_HOME, -DPropertyFile and the JDK 21
# --add-opens the product needs. Replacing CATALINA_OPTS instead of extending it
# leaves the server without a property file, and every database-backed context
# then fails to start.
if [[ -z "${ADEMPIERE_JAVA_OPTIONS:-}" ]]; then
  echo "myEnvironment.sh did not set ADEMPIERE_JAVA_OPTIONS; refusing to capture." >&2
  exit 70
fi
export CATALINA_OPTS="$ADEMPIERE_JAVA_OPTIONS -Duser.timezone=UTC -Duser.language=en -Duser.country=US"
mkdir -p "$CATALINA_TMPDIR"

started_here=0

shutdown_tomcat() {
  if (( started_here == 0 )); then
    return
  fi
  "$CATALINA_HOME/bin/shutdown.sh" >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    if ! curl -sS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if [[ -f "$CATALINA_PID" ]]; then
    pid=$(cat "$CATALINA_PID")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$CATALINA_PID"
  fi
}
trap shutdown_tomcat EXIT

if curl -sS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null; then
  echo "Reusing the Tomcat 9 lane already listening on port $port"
else
  echo "Starting the Tomcat 9 lane on port $port"
  "$CATALINA_HOME/bin/startup.sh" >/dev/null
  started_here=1
  ready=0
  for _ in $(seq 1 120); do
    if curl -sS -o /dev/null "http://127.0.0.1:$port/webui/" 2>/dev/null; then
      ready=1
      break
    fi
    sleep 1
  done
  if (( ready == 0 )); then
    echo "Tomcat did not serve /webui/ on port $port within 120s." >&2
    tail -40 "$CATALINA_BASE/logs/catalina.out" 2>/dev/null >&2 || true
    exit 70
  fi
fi

"$@"
