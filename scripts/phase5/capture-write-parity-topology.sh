#!/usr/bin/env bash
# Phase 5g-1b: record what the routed lane actually IS, from the running lane.
#
# The evidence validator refuses a green status that cannot say what produced
# it. Two of the things it must be able to say are "the modern runtime was
# reachable only on loopback" and "these were the artifacts serving the two
# origins". Neither can be taken from the Gradle tasks that staged them: a task
# that succeeded is not a running process, and the whole point of a parity claim
# is that it names the runtime it was measured against.
#
# So every fact here is read from the LANE -- bound sockets, deployed files --
# and never from the configuration that was supposed to produce it.
set -euo pipefail

repo_root=${1:?repository root is required}
adempiere_home=${2:?installed ADEMPIERE_HOME is required}
out_dir=${3:?output directory is required}

mkdir -p "$out_dir"

public_port=${PHASE5E_PUBLIC_PORT:-8888}
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
  "$repo_root/gradle/phase4/runtime.properties")
lane_phase=${ADEMPIERE_ROUTED_LANE_PHASE:-phase5e}
tomcat10_dir="$repo_root/build/$lane_phase/tomcat10"

# `lsof` exits 1 when nothing matches, and under `pipefail` a command
# substitution inherits that status even though `awk` succeeded -- so on a host
# without `ss`, a port with NO listener would abort the whole capture instead of
# recording `absent`, which is precisely the case this file exists to record.
listeners_on() {
  local port=$1 found=''
  if command -v ss >/dev/null 2>&1; then
    found=$(ss -ltnH "sport = :$port" 2>/dev/null | awk '{print $4}') || found=''
  else
    found=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null \
      | awk 'NR > 1 {print $9}') || found=''
  fi
  printf '%s' "$found"
}

digest_of() {
  if [[ -f "$1" ]]; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo absent
  fi
}

{
  printf 'public_port\t%s\n' "$public_port"
  printf 'modern_port\t%s\n' "$api_port"

  # Loopback containment is asserted per listener, not once. A runtime bound to
  # both 127.0.0.1 and 0.0.0.0 is reachable off-host, and a check that looked
  # only at the first line would call it contained.
  modern_listeners=$(listeners_on "$api_port")
  if [[ -z "$modern_listeners" ]]; then
    printf 'modern_listener\tabsent\n'
  else
    while read -r listener; do
      [[ -n "$listener" ]] || continue
      printf 'modern_listener\t%s\n' "$listener"
    done <<<"$modern_listeners"
  fi

  public_listeners=$(listeners_on "$public_port")
  if [[ -z "$public_listeners" ]]; then
    printf 'public_listener\tabsent\n'
  else
    while read -r listener; do
      [[ -n "$listener" ]] || continue
      printf 'public_listener\t%s\n' "$listener"
    done <<<"$public_listeners"
  fi

  # The artifacts, by digest. `webui.war` under the installed home is the
  # DERIVED legacy war carrying the cohort router; the modern application is
  # deployed by a Context descriptor rather than by a war in webapps, which is
  # what makes "exactly one modern context" checkable.
  printf 'public_artifact\t%s\n' \
    "$(digest_of "$adempiere_home/tomcat/webapps/webui.war")"
  printf 'modern_artifact\t%s\n' \
    "$(digest_of "$tomcat10_dir/$lane_phase/webui-modern.war")"
  printf 'modern_context_descriptor\t%s\n' \
    "$(digest_of "$tomcat10_dir/conf/Catalina/localhost/webui.xml")"
  # A second modern deployment would mean the capture cannot say WHICH modern
  # application answered it. start-routed-lane.sh moves the default deployment
  # aside precisely so the Context descriptor is the only one; this records
  # that it stayed moved aside for the whole lane, not only at start.
  if [[ -e "$tomcat10_dir/webapps/webui-modern.war" \
      || -e "$tomcat10_dir/webapps/webui-modern" ]]; then
    printf 'modern_default_context\tpresent\n'
  else
    printf 'modern_default_context\tabsent\n'
  fi
} >"$out_dir/topology.tsv"

echo "== routed topology recorded in $out_dir/topology.tsv =="
