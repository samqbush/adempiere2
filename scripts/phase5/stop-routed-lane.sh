#!/usr/bin/env bash
# Phase 5e: stop the routed lane and restore the installed legacy deployment.
#
# Both shutdowns are deterministic rather than kills, so SessionManagerListener,
# WebUIServlet.destroy() and CohortBridgeStartupListener.contextDestroyed all
# run. That matters: the lifecycle baseline the gate asserts is only meaningful
# if the container was given the chance to clean up.
set -euo pipefail

repo_root=${1:?repository root is required}
adempiere_home=${2:?installed ADEMPIERE_HOME is required}

public_port=${PHASE5E_PUBLIC_PORT:-8888}
properties_file="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
  "$properties_file")
tomcat10_dir="$repo_root/build/phase5e/tomcat10"
pid_file="$repo_root/build/phase5e/tomcat10-routed.pid"
catalina_base="$adempiere_home/tomcat"
public_pid_file="$catalina_base/temp/phase5e-public.pid"

stop_public() {
  local env_script="$adempiere_home/utils/myEnvironment.sh"
  [[ -r "$env_script" ]] || return 0
  set +u
  # shellcheck source=/dev/null
  source "$env_script" nosave
  set -u
  export CATALINA_BASE="$catalina_base"
  export CATALINA_PID="$public_pid_file"
  "$CATALINA_HOME/bin/shutdown.sh" >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    curl -sS -o /dev/null "http://127.0.0.1:$public_port/" 2>/dev/null || break
    sleep 1
  done
  if [[ -f "$public_pid_file" ]]; then
    local pid
    pid=$(cat "$public_pid_file")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
    rm -f "$public_pid_file"
  fi
}

stop_modern() {
  [[ -x "$tomcat10_dir/bin/catalina.sh" ]] || return 0
  export CATALINA_PID="$pid_file"
  "$tomcat10_dir/bin/catalina.sh" stop 30 -force >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    curl -sS -o /dev/null "http://127.0.0.1:$api_port/" 2>/dev/null || break
    sleep 1
  done
  if [[ -f "$pid_file" ]]; then
    local pid
    pid=$(cat "$pid_file")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
    rm -f "$pid_file"
  fi
}

stop_public || true
stop_modern || true

# The installed Tomcat 9 deployment is restored to the PRISTINE artifact, which
# is what lib/webuiOriginal.war holds. Restoring from lib/webui.war would put
# the routed archive back: the next unrelated Tomcat 9 start would then serve a
# router with no modern backend to route to, and every session would fail
# closed with no obvious cause.
#
# The exploded directory has to go with it. Tomcat expands a WAR once and then
# serves the expansion; leaving the routed expansion in place makes the restored
# archive cosmetic.
if [[ -d "$catalina_base/webapps" ]]; then
  rm -rf "$catalina_base/webapps/webui"
  if [[ -f "$adempiere_home/lib/webuiOriginal.war" ]]; then
    cp "$adempiere_home/lib/webuiOriginal.war" "$catalina_base/webapps/webui.war"
  elif [[ -f "$catalina_base/webapps/webui.war" ]]; then
    echo "No pristine lib/webuiOriginal.war to restore; removing the routed deployment" >&2
    rm -f "$catalina_base/webapps/webui.war"
  fi
fi

if curl -sS -o /dev/null "http://127.0.0.1:$public_port/" 2>/dev/null; then
  echo "The Phase 5e public ingress is still serving on 127.0.0.1:$public_port" >&2
  exit 70
fi
if curl -sS -o /dev/null "http://127.0.0.1:$api_port/" 2>/dev/null; then
  echo "The Phase 5e modern runtime is still serving on 127.0.0.1:$api_port" >&2
  exit 70
fi
