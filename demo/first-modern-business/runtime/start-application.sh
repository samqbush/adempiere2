#!/usr/bin/env bash
set -euo pipefail

export ADEMPIERE_HOME=/opt/Adempiere
home=$ADEMPIERE_HOME
handoff_key=/run/adempiere-demo/handoff.key
modern_home="$home/tomcat10-api"
public_home=/opt/tomcat
public_base="$home/tomcat"

if [[ $(id -u) -eq 0 ]]; then
  install -o adempiere -g adempiere -m 0600 \
    /run/adempiere-demo-source/handoff.key "$handoff_key"
  exec su --preserve-environment --shell /bin/bash \
    --command /opt/demo/start-application.sh adempiere
fi

file_mode=$(stat -c '%a' "$handoff_key")
[[ "$file_mode" == 600 ]] || {
  echo "Handoff key has unsafe mode $file_mode" >&2
  exit 65
}
[[ -s "$handoff_key" ]] || {
  echo "Handoff key is missing or empty" >&2
  exit 66
}

/opt/demo/render-environment.sh
/opt/demo/demo-database-tool guard

if [[ ! -f "$home/.demo-setup-complete" ]]; then
  bash "$home/RUN_silentsetup.sh"
  ADEMPIERE_NONINTERACTIVE=true bash "$home/utils/RUN_MigrateXML.sh"
  /opt/demo/demo-database-tool configure-cohort
  touch "$home/.demo-setup-complete"
fi

cp /opt/demo/artifacts/webui-routed.war "$home/lib/webui.war"
cp "$home/lib/webui.war" "$public_base/webapps/webui.war"
rm -rf "$public_base/webapps/webui"
rm -f "$modern_home/webapps/webui-modern.war"
rm -rf "$modern_home/webapps/webui-modern"

export CATALINA_HOME="$modern_home"
export CATALINA_BASE="$modern_home"
export CATALINA_PID="$modern_home/temp/demo-modern.pid"
export CATALINA_OPTS="${CATALINA_OPTS:-} -Duser.timezone=UTC -Duser.language=en -Duser.country=US -Dadempiere.phase5e.handoffKey=$handoff_key"
"$modern_home/bin/catalina.sh" start

java -cp /opt/demo/classes org.adempiere.demo.DemoHttpProbe \
  http://127.0.0.1:8890/webui/ 200,403 900

set +u
source "$home/utils/myEnvironment.sh" nosave
set -u
export CATALINA_HOME="$public_home"
export CATALINA_BASE="$public_base"
export CATALINA_PID="$public_base/temp/demo-public.pid"
export CATALINA_TMPDIR="$public_base/temp"
export CATALINA_OPTS="$ADEMPIERE_JAVA_OPTIONS -Duser.timezone=UTC -Duser.language=en -Duser.country=US -Dadempiere.phase5e.handoffKey=$handoff_key -Dadempiere.phase5e.modernBackend=http://127.0.0.1:8890 -Dadempiere.phase5e.configurationTtlMillis=0"

stop_runtimes() {
  "$public_home/bin/shutdown.sh" >/dev/null 2>&1 || true
  CATALINA_HOME="$modern_home" CATALINA_BASE="$modern_home" \
    "$modern_home/bin/catalina.sh" stop >/dev/null 2>&1 || true
}
trap stop_runtimes EXIT TERM INT

exec "$public_home/bin/catalina.sh" run
