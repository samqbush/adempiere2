#!/usr/bin/env bash
# Phase 5e: start the routed lane.
#
# Two runtimes, one public ingress:
#
#   Tomcat 9  (installed product, port 8888)  the ONLY public ingress. It serves
#             the derived webui.war, which carries the cohort router and the
#             decision interceptor.
#
#   Tomcat 10 (loopback only, Phase 4 api.port) serves the Phase 4 CXF API AND
#             the modern ZK application, the latter mounted at the INTERNAL
#             context path /webui by conf/Catalina/localhost/webui.xml.
#
# Three properties this script is responsible for, each of which is asserted
# again by verifyPhase5eRoutedEvidence rather than assumed:
#
#   1. Exactly one modern UI context exists. webapps/webui-modern.war is moved
#      aside before start, so the Context descriptor is the only deployment.
#   2. The modern runtime is loopback only.
#   3. Both runtimes read the same 0600 handoff key, owned by this account, from
#      a path outside every archive under ADEMPIERE_HOME.
set -euo pipefail

repo_root=${1:?repository root is required}
adempiere_home=${2:?installed ADEMPIERE_HOME is required}
handoff_key=${3:?handoff key path is required}

public_port=${PHASE5E_PUBLIC_PORT:-8888}
properties_file="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
  "$properties_file")
tomcat10_dir="$repo_root/build/phase5e/tomcat10"
api_war="$repo_root/org.adempiere.webservice/build/libs/ADInterface-Modern-1.0.war"
evidence_dir="$repo_root/build/phase5e/evidence"
pid_file="$repo_root/build/phase5e/tomcat10-routed.pid"

mkdir -p "$evidence_dir" "$(dirname "$pid_file")"

if [[ ! -f "$handoff_key" ]]; then
  echo "The Phase 5e handoff key is missing: $handoff_key" >&2
  exit 66
fi
key_mode=$(stat -f '%Lp' "$handoff_key" 2>/dev/null || stat -c '%a' "$handoff_key")
if [[ "$key_mode" != "600" ]]; then
  echo "The Phase 5e handoff key is mode $key_mode; 600 is required" >&2
  exit 65
fi
case "$handoff_key" in
  "$adempiere_home"/*)
    echo "The handoff key must not live inside ADEMPIERE_HOME: $handoff_key" >&2
    exit 65
    ;;
esac

# ---------------------------------------------------------------------------
# 1. Tomcat 10: the modern application at the INTERNAL /webui path.
# ---------------------------------------------------------------------------
"$repo_root/scripts/phase4/prepare-tomcat10.sh" \
  "$tomcat10_dir" "$api_war" "$adempiere_home"

mkdir -p "$tomcat10_dir/phase5e" "$tomcat10_dir/conf/Catalina/localhost"
# The INSTALLED staged archive, not the build output. startPhase5eRoutedLane
# depends on stagePhase5eInstalledRouting, so the lane serves exactly the file
# the shipped Context descriptor resolves to; booting the build output instead
# would leave "the installed docBase actually works" untested.
installed_modern="$adempiere_home/tomcat10-api/phase5e/webui-modern.war"
if [[ ! -f "$installed_modern" ]]; then
  echo "The installed routed modern archive is missing: $installed_modern" >&2
  exit 66
fi
cp "$installed_modern" "$tomcat10_dir/phase5e/webui-modern.war"
# Exactly one modern UI context: the auto-deployed archive is removed so the
# Context descriptor below is the only deployment of this application.
rm -f "$tomcat10_dir/webapps/webui-modern.war"
rm -rf "$tomcat10_dir/webapps/webui-modern"
sed "s#\${catalina.base}#$tomcat10_dir#g" \
  "$adempiere_home/tomcat10-api/conf/Catalina/localhost/webui.xml" \
  >"$tomcat10_dir/conf/Catalina/localhost/webui.xml"

grep -Fq 'address="127.0.0.1"' "$tomcat10_dir/conf/server.xml"
grep -Fq '<Server port="-1"' "$tomcat10_dir/conf/server.xml"

export CATALINA_PID="$pid_file"
export CATALINA_OPTS="${CATALINA_OPTS:-} -Duser.timezone=UTC -Duser.language=en -Duser.country=US -Dadempiere.phase5e.handoffKey=$handoff_key"
rm -f "$pid_file"
"$tomcat10_dir/bin/catalina.sh" start >/dev/null 2>&1

modern_ready=no
for _ in $(seq 1 180); do
  status=$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:$api_port/webui/" 2>/dev/null || true)
  api_status=$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:$api_port/ADInterface/services/ADService?wsdl" \
    2>/dev/null || true)
  # 403 is a healthy armed modern context: it refuses an unbootstrapped session
  # that presents no ticket, which is exactly the fail-closed contract.
  if [[ ( "$status" == 200 || "$status" == 403 ) && "$api_status" == 200 ]]; then
    modern_ready=yes
    break
  fi
  if [[ -f "$pid_file" ]] && ! kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [[ "$modern_ready" != yes ]]; then
  echo "The Phase 5e modern runtime did not become ready" >&2
  echo "  internal /webui/ -> ${status:-none}" >&2
  echo "  /ADInterface     -> ${api_status:-none}" >&2
  tail -200 "$tomcat10_dir/logs/catalina.out" 2>/dev/null >&2 || true
  "$repo_root/scripts/phase5/stop-routed-lane.sh" "$repo_root" "$adempiere_home" || true
  exit 70
fi

# ---------------------------------------------------------------------------
# 2. Tomcat 9: the public ingress, serving the derived routed webui.war.
# ---------------------------------------------------------------------------
env_script="$adempiere_home/utils/myEnvironment.sh"
if [[ ! -r "$env_script" ]]; then
  echo "Installed Phase 3 environment is missing: $env_script" >&2
  "$repo_root/scripts/phase5/stop-routed-lane.sh" "$repo_root" "$adempiere_home" || true
  exit 66
fi

catalina_base="$adempiere_home/tomcat"
# The installed deployment is replaced with the derived artifact, and the
# exploded directory is removed so Tomcat cannot serve a stale expansion of the
# pristine archive.
cp "$adempiere_home/lib/webui.war" "$catalina_base/webapps/webui.war"
rm -rf "$catalina_base/webapps/webui"

set +u
# shellcheck source=/dev/null
source "$env_script" nosave
set -u

export CATALINA_BASE="$catalina_base"
export CATALINA_PID="$catalina_base/temp/phase5e-public.pid"
export CATALINA_TMPDIR="$catalina_base/temp"
export CATALINA_OPTS="$ADEMPIERE_JAVA_OPTIONS -Duser.timezone=UTC -Duser.language=en -Duser.country=US -Dadempiere.phase5e.handoffKey=$handoff_key -Dadempiere.phase5e.modernBackend=http://127.0.0.1:$api_port -Dadempiere.phase5e.configurationTtlMillis=0"
mkdir -p "$CATALINA_TMPDIR"

if curl -sS -o /dev/null "http://127.0.0.1:$public_port/" 2>/dev/null; then
  echo "Port $public_port is already serving HTTP; refusing to reuse an unknown lane" >&2
  "$repo_root/scripts/phase5/stop-routed-lane.sh" "$repo_root" "$adempiere_home" || true
  exit 70
fi

"$CATALINA_HOME/bin/startup.sh" >/dev/null
public_ready=no
for _ in $(seq 1 180); do
  public_status=$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:$public_port/webui/" 2>/dev/null || true)
  if [[ "$public_status" == 200 ]]; then
    public_ready=yes
    break
  fi
  sleep 1
done

if [[ "$public_ready" != yes ]]; then
  echo "The Phase 5e public /webui ingress did not become ready (${public_status:-none})" >&2
  tail -200 "$catalina_base/logs/catalina.out" 2>/dev/null >&2 || true
  "$repo_root/scripts/phase5/stop-routed-lane.sh" "$repo_root" "$adempiere_home" || true
  exit 70
fi

# ---------------------------------------------------------------------------
# 3. Lane evidence.
#
# Every value below is OBSERVED. Two of them used to be asserted instead, and
# both are exactly the kind of claim that survives the thing it describes
# breaking:
#
#   * the modern listener was printed as the literal `127.0.0.1:<port>` rather
#     than read from the running process, so a connector that had been widened
#     to 0.0.0.0 would still have been recorded as loopback-only;
#   * the key search used `find -name`, which cannot see inside an archive, so a
#     key packaged into a WAR would have been recorded as absent.
# ---------------------------------------------------------------------------
default_context=absent
if [[ -e "$tomcat10_dir/webapps/webui-modern.war" \
    || -e "$tomcat10_dir/webapps/webui-modern" ]]; then
  default_context=present
fi

# The listeners a PID actually holds, one `address:port` per line.
bound_listeners() {
  local pid=$1
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -a -p "$pid" -iTCP -sTCP:LISTEN 2>/dev/null |
      awk 'NR > 1 { print $(NF - 1) }' | sed 's/(LISTEN)//' | sort -u
    return
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -lntpH 2>/dev/null |
      awk -v pid="$pid" '$0 ~ ("pid=" pid ",") { print $4 }' | sort -u
    return
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -lntp 2>/dev/null |
      awk -v pid="$pid" '$NF ~ ("^" pid "/") { print $4 }' | sort -u
    return
  fi
}

modern_pid=$(cat "$pid_file")
public_pid=$(cat "$CATALINA_PID" 2>/dev/null || true)
modern_listeners=$(bound_listeners "$modern_pid")
public_listeners=$(bound_listeners "${public_pid:-0}")
if [[ -z "$modern_listeners" ]]; then
  echo "The bound listeners of the modern runtime could not be observed." >&2
  echo "Install lsof, ss or netstat; the lane will not record an assumed value." >&2
  "$repo_root/scripts/phase5/stop-routed-lane.sh" "$repo_root" "$adempiere_home" || true
  exit 70
fi

# The handoff key, inside archives as well as on the filesystem. The archive
# kinds inspected are named in the evidence so the claim cannot be read as
# wider than the search.
key_basename=$(basename "$handoff_key")
key_in_archive=no
if find "$adempiere_home" -type f -name "$key_basename" | grep -q .; then
  key_in_archive=yes
fi
if [[ "$key_in_archive" == no ]]; then
  while IFS= read -r archive; do
    if unzip -Z1 "$archive" 2>/dev/null |
        grep -Eq "(^|/)($key_basename|[^/]*handoff[^/]*\.key)$"; then
      key_in_archive=yes
      break
    fi
  done < <(find "$adempiere_home" -type f \
    \( -name '*.war' -o -name '*.zip' -o -name '*.ear' -o -name '*.jar' \))
fi
if [[ "$key_in_archive" == no ]]; then
  while IFS= read -r archive; do
    if tar -tzf "$archive" 2>/dev/null |
        grep -Eq "(^|/)($key_basename|[^/]*handoff[^/]*\.key)$"; then
      key_in_archive=yes
      break
    fi
  done < <(find "$adempiere_home" -type f \
    \( -name '*.tar.gz' -o -name '*.tgz' \))
fi

{
  printf 'public_webui\t%s\thttp://127.0.0.1:%s/webui/\n' \
    "$public_status" "$public_port"
  # The observed status, never a normalised one. 403 is the healthy armed state
  # of the modern context - it refuses an unbootstrapped session that presents
  # no ticket - and 200 is the healthy unarmed state. Rewriting 403 to 200 here
  # would erase the difference between "fail-closed and working" and "open".
  printf 'modern_internal\t%s\thttp://127.0.0.1:%s/webui/\n' \
    "$status" "$api_port"
  printf 'modern_api\t%s\thttp://127.0.0.1:%s/ADInterface/services/ADService?wsdl\n' \
    "$api_status" "$api_port"
  printf '%s\n' "$modern_listeners" | while IFS= read -r listener; do
    [[ -n "$listener" ]] && printf 'modern_listener\t%s\n' "$listener"
  done
  printf '%s\n' "$public_listeners" | while IFS= read -r listener; do
    [[ -n "$listener" ]] && printf 'public_listener\t%s\n' "$listener"
  done
  printf 'modern_expected_port\t%s\n' "$api_port"
  printf 'public_expected_port\t%s\n' "$public_port"
  printf 'modern_default_context\t%s\n' "$default_context"
  printf 'handoff_key_mode\t%s\n' "$key_mode"
  printf 'handoff_key_in_archive\t%s\n' "$key_in_archive"
  printf 'handoff_key_search\tfilesystem+zip(war,zip,ear,jar)+tar.gz\n'
  printf 'configuration_cache_ttl_millis\t0\n'
  printf 'modern_pid\t%s\n' "$modern_pid"
  printf 'public_pid\t%s\n' "${public_pid:-unknown}"
  printf 'catalina_base_public\t%s\n' "$catalina_base"
  printf 'catalina_home_modern\t%s\n' "$tomcat10_dir"
} >"$evidence_dir/routed-lane.tsv"

printf 'Phase 5e routed lane ready: public 127.0.0.1:%s, modern 127.0.0.1:%s\n' \
  "$public_port" "$api_port"
