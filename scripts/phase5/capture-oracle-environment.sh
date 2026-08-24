#!/usr/bin/env bash
# Phase 5b capture-environment recorder (RD-8 / H3).
#
# The oracle is only valid against one runtime combination. Freezing responses
# without freezing the runtime that produced them would let a Phase 5c change of
# JDK, Tomcat, PostgreSQL, seed, or locale silently invalidate every comparison
# while the gate still reported green.
#
# Every coordinate recorded here is asserted by verifyPhase5OracleEnvironment.
# Drift fails the gate rather than being absorbed.
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: capture-oracle-environment.sh <output-file> <tomcat-home> <db-host> <db-port> <db-name>" >&2
  exit 64
fi

output=$1
tomcat_home=$2
db_host=$3
db_port=$4
db_name=$5
repo_root=$(git rev-parse --show-toplevel)

mkdir -p "$(dirname "$output")"

emit() { printf '%s\t%s\t%s\n' "$1" "$2" "$3"; }

{
  printf 'coordinate\tvalue\tsource\n'

  # Source provenance. Without the commit, "rebuild from source and compare
  # digests" is not an executable rollback instruction.
  emit source_commit "$(git -C "$repo_root" rev-parse HEAD)" git
  emit source_tree_dirty \
    "$(git -C "$repo_root" diff --quiet && git -C "$repo_root" diff --cached --quiet && echo no || echo yes)" git

  # Runtime coordinates.
  #
  # The JDK that matters is the one Tomcat is running under, not whatever `java`
  # the capture shell happens to resolve. Recording the shell's JDK would pin a
  # coordinate that has no effect on the oracle while leaving the real one free
  # to drift.
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    runtime_java="$JAVA_HOME/bin/java"
  else
    runtime_java="$(command -v java)"
  fi
  emit jdk_version "$("$runtime_java" -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')" "$runtime_java -version"
  emit jdk_vendor "$("$runtime_java" -version 2>&1 | sed -n '2p' | sed -E 's/ \(build.*//')" "$runtime_java -version"

  # CATALINA_BASE holds the instance (conf, webapps); CATALINA_HOME holds the
  # distribution (lib, RELEASE-NOTES). The Phase 3 lane splits them, so the
  # version and content pins must read the distribution, not the instance.
  catalina_home="${CATALINA_HOME:-$tomcat_home}"
  if [[ ! -r "$catalina_home/lib/catalina.jar" && -r "$repo_root/build/phase3/tomcat/lib/catalina.jar" ]]; then
    catalina_home="$repo_root/build/phase3/tomcat"
  fi

  if [[ -r "$catalina_home/RELEASE-NOTES" ]]; then
    emit tomcat_version \
      "$(sed -nE 's/.*Apache Tomcat Version ([0-9.]+).*/\1/p' "$catalina_home/RELEASE-NOTES" | head -1)" \
      'tomcat RELEASE-NOTES'
  else
    emit tomcat_version unknown 'tomcat RELEASE-NOTES absent'
  fi

  # The Tomcat distribution is pinned by content, not by version string, so a
  # repackaged 9.x cannot pass as the frozen one.
  if [[ -r "$catalina_home/lib/catalina.jar" ]]; then
    emit tomcat_sha512 \
      "$(shasum -a 512 "$catalina_home/lib/catalina.jar" | awk '{print $1}')" \
      'shasum of lib/catalina.jar'
  else
    emit tomcat_sha512 unknown "catalina.jar absent under $catalina_home"
  fi

  emit postgresql_version \
    "$(PGPASSWORD="${ADEMPIERE_DB_PASSWORD:-$db_name}" psql --host="$db_host" --port="$db_port" \
        --username="$db_name" --dbname="$db_name" --tuples-only --no-align \
        --command='SHOW server_version' 2>/dev/null | tr -d ' ')" \
    'SHOW server_version'

  # Application dictionary state. A different migration level or a different
  # seed produces different menus, roles, and windows, which would change the
  # oracle without changing any code.
  emit migration_release \
    "$(PGPASSWORD="${ADEMPIERE_DB_PASSWORD:-$db_name}" psql --host="$db_host" --port="$db_port" \
        --username="$db_name" --dbname="$db_name" --tuples-only --no-align \
        --command="SELECT ReleaseNo || '/' || Version FROM AD_System" 2>/dev/null | tr -d ' ')" \
    'AD_System.ReleaseNo/Version'

  # The id generator is load-bearing for normalization: component uuids are
  # compared byte-literally precisely because this delegate makes them
  # deterministic. If it changes, the normalization policy is invalid.
  emit zk_id_generator \
    "$(PGPASSWORD="${ADEMPIERE_DB_PASSWORD:-$db_name}" psql --host="$db_host" --port="$db_port" \
        --username="$db_name" --dbname="$db_name" --tuples-only --no-align \
        --command="SELECT Value FROM AD_SysConfig WHERE Name = 'org.adempiere.webui.IdGenerator'" 2>/dev/null | tr -d ' ')" \
    'AD_SysConfig'

  # Locale and timezone change rendered labels, number formats, and dates.
  # The timezone that shapes the oracle is the server JVM's, not the capture
  # shell's: server-rendered dates come from Tomcat. The lane pins it explicitly,
  # so the pinned value is recorded in preference to the shell's.
  if [[ "${CATALINA_OPTS:-}" == *-Duser.timezone=* ]]; then
    server_tz="${CATALINA_OPTS##*-Duser.timezone=}"
    server_tz="${server_tz%% *}"
    emit timezone "$server_tz" 'CATALINA_OPTS -Duser.timezone'
  else
    emit timezone "${TZ:-$(date +%Z)}" 'capture shell (server timezone not pinned)'
  fi
  emit shell_timezone "${TZ:-$(date +%Z)}" environment
  emit locale "${LC_ALL:-${LANG:-unset}}" environment
  emit os_name "$(uname -s)" uname
  emit os_arch "$(uname -m)" uname

  # The seed defines every menu, role, window and label the oracle observes. A
  # different seed changes the oracle without changing a line of code.
  if [[ -r "$repo_root/data/seed/Adempiere_pg.jar" ]]; then
    emit seed_sha512 \
      "$(shasum -a 512 "$repo_root/data/seed/Adempiere_pg.jar" | awk '{print $1}')" \
      'shasum of data/seed/Adempiere_pg.jar'
  else
    emit seed_sha512 unknown 'data/seed/Adempiere_pg.jar absent'
  fi

  # The packaged ZK runtime is the subject of the whole Phase 5 migration, so
  # the exact bytes the oracle was captured against are pinned here.
  zk_jars=""
  for zk_jar in zk.jar zkex.jar zkmax.jar zkplus.jar; do
    zk_path="$tomcat_home/webapps/webui/WEB-INF/lib/$zk_jar"
    if [[ -r "$zk_path" ]]; then
      zk_jars+="$(shasum -a 512 "$zk_path" | awk '{print $1}')"
    fi
  done
  emit zk_packaged_jar_digest \
    "$(printf '%s' "$zk_jars" | shasum -a 512 | awk '{print $1}')" \
    'shasum over zk.jar, zkex.jar, zkmax.jar, zkplus.jar'

  # The capture scripts are part of the oracle's definition: changing how the
  # flow is driven changes what the oracle means.
  emit client_library_sha512 \
    "$(shasum -a 512 "$repo_root/scripts/phase5/zk-au-client.sh" | awk '{print $1}')" \
    'shasum of scripts/phase5/zk-au-client.sh'
  emit capture_script_sha512 \
    "$(shasum -a 512 "$repo_root/scripts/phase5/capture-legacy-web-oracle.sh" | awk '{print $1}')" \
    'shasum of scripts/phase5/capture-legacy-web-oracle.sh'
  emit normalizer_script_sha512 \
    "$(shasum -a 512 "$repo_root/scripts/phase5/normalize-web-capture.sh" | awk '{print $1}')" \
    'shasum of scripts/phase5/normalize-web-capture.sh'
  emit static_asset_script_sha512 \
    "$(shasum -a 512 "$repo_root/scripts/phase5/generate-static-asset-contract.sh" | awk '{print $1}')" \
    'shasum of scripts/phase5/generate-static-asset-contract.sh'
  # The fixture script defines the database precondition the oracle was captured
  # under, so it is pinned alongside the capture and normalization scripts.
  emit fixture_script_sha512 \
    "$(shasum -a 512 "$repo_root/scripts/phase5/reset-oracle-fixture.sh" | awk '{print $1}')" \
    'shasum of scripts/phase5/reset-oracle-fixture.sh'

  # The pinned client-information vector. Screen and timezone values reach the
  # server and can alter rendering, so they are frozen alongside everything else.
  # shellcheck source=scripts/phase5/zk-au-client.sh
  source "$repo_root/scripts/phase5/zk-au-client.sh"
  emit client_info_vector \
    "$ZKAU_TZ_OFFSET,$ZKAU_SCREEN_WIDTH,$ZKAU_SCREEN_HEIGHT,$ZKAU_COLOR_DEPTH,$ZKAU_INNER_WIDTH,$ZKAU_INNER_HEIGHT,$ZKAU_INNER_X,$ZKAU_INNER_Y" \
    'scripts/phase5/zk-au-client.sh'
  emit client_accept_language "$ZKAU_ACCEPT_LANGUAGE" 'scripts/phase5/zk-au-client.sh'
  emit client_user_agent "$ZKAU_USER_AGENT" 'scripts/phase5/zk-au-client.sh'
} >"$output"

echo "Wrote $(($(wc -l <"$output") - 1)) environment coordinates to $output"
