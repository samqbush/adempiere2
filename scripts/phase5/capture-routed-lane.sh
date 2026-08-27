#!/usr/bin/env bash
# Phase 5e: capture routed-lane evidence and inject the reviewed faults.
#
# The browser drives the public origin; everything that cannot be observed or
# caused from a browser lives here.
#
# usage:
#   capture-routed-lane.sh soap        <evidence_dir>
#   capture-routed-lane.sh sessions    <evidence_dir> <label>
#   capture-routed-lane.sh lifecycle   mark    <evidence_dir> <label>
#   capture-routed-lane.sh lifecycle   observe <evidence_dir> <label> <mark_label> [public_session_sha256]
#   capture-routed-lane.sh lifecycle   await   <evidence_dir> <mark_label> <runtimes> <seconds> [public_session_sha256]
#   capture-routed-lane.sh ephemeral   set <seconds> | restore
#   capture-routed-lane.sh backend     stop|start
#   capture-routed-lane.sh interceptor disable|enable
#   capture-routed-lane.sh secrets     <evidence_dir>
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
operation=${1:?operation is required}

properties_file="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
  "$properties_file")
public_port=${PHASE5E_PUBLIC_PORT:-8888}
adempiere_home="$repo_root/build/phase3/runtime/Adempiere"
tomcat10_dir="$repo_root/build/phase5e/tomcat10"
public_log="$adempiere_home/tomcat/logs/catalina.out"
modern_log="$tomcat10_dir/logs/catalina.out"
parser="$repo_root/scripts/phase5/session-cache-census.py"

# A complete row set for a runtime whose log cannot be read at all, so an
# unreadable log is recorded as "no record" rather than as a missing row a
# consumer has to interpret. `absent` and `unknown` are deliberately not `none`
# and `no-census`: those mean "this action's own destruction record carries no
# cache lines", which is a different fact.
unreadable_census() {
  local runtime=$1
  printf '%s\tcensus\tabsent\n' "$runtime"
  for cache in Session-Cache Session-Context-Cache Application-Cache \
    Desktop-Cache Execution-CarryOver-Cache User-Preference-Cache \
    User-Authentication-Cache; do
    printf '%s\t%s\tunknown\n' "$runtime" "$cache"
  done
}

case "$operation" in
soap)
  evidence_dir=${2:?evidence directory is required}
  mkdir -p "$evidence_dir"
  # The complete Phase 4 corpus, run while the browser is holding authenticated
  # modern sessions open through the public router.
  "$repo_root/scripts/phase4/smoke-cxf-api.sh" \
    "http://127.0.0.1:$api_port/ADInterface/services" full \
    >"$evidence_dir/phase4-soap-during-routed-session.log" 2>&1
  printf 'phase4_soap_corpus\tpass\twhile-routed-modern-sessions-authenticated\n' \
    >"$evidence_dir/phase4-soap-during-routed-session.tsv"
  ;;

sessions|baseline)
  evidence_dir=${2:?evidence directory is required}
  label=${3:?label is required}
  mkdir -p "$evidence_dir"
  target="$evidence_dir/session-caches-$label.tsv"
  : >"$target"
  # A plain snapshot of the most recent POST-MUTATION cache record in each
  # runtime log. It is deliberately not used for the lifecycle comparison - see
  # the `lifecycle` operation, which anchors both readings to a recorded mark.
  for runtime_log in "$public_log" "$modern_log"; do
    [[ -r "$runtime_log" ]] || continue
    runtime=public
    [[ "$runtime_log" == "$modern_log" ]] && runtime=modern
    python3 "$parser" snapshot "$runtime" "$runtime_log" 0 >>"$target"
  done
  printf 'label\t%s\n' "$label" >>"$target"
  ;;

lifecycle)
  action=${2:?mark, observe or await is required}
  case "$action" in
    mark)
      evidence_dir=${3:?evidence directory is required}
      label=${4:?label is required}
      mkdir -p "$evidence_dir"
      target="$evidence_dir/session-caches-$label.tsv"
      : >"$target"
      # The mark records TWO things: the at-rest cache census, and the exact log
      # offset it was taken at. Everything the `observe` step reads has to come
      # from after that offset, which is what makes "a destruction happened
      # because of this lifecycle action" a statement rather than a hope.
      #
      # The baseline is read in `baseline` mode, NOT `snapshot`: `baseline` and
      # `observe` both read the newest DESTRUCTION record, so the mark and the
      # observation taken against it are two readings of the same point in the
      # lifecycle. A `snapshot` baseline could land on an after-create census -
      # a reading of a session that had just been inserted - and comparing that
      # with a post-removal reading is the mismatch this whole capture exists to
      # avoid.
      for runtime_log in "$public_log" "$modern_log"; do
        runtime=public
        [[ "$runtime_log" == "$modern_log" ]] && runtime=modern
        offset=0
        [[ -r "$runtime_log" ]] && offset=$(wc -l <"$runtime_log" | tr -d ' ')
        printf '%s\toffset\t%s\n' "$runtime" "$offset" >>"$target"
        if [[ -r "$runtime_log" ]]; then
          python3 "$parser" baseline "$runtime" "$runtime_log" 0 >>"$target"
        else
          unreadable_census "$runtime" >>"$target"
        fi
      done
      printf 'label\t%s\n' "$label" >>"$target"
      ;;
    observe)
      evidence_dir=${3:?evidence directory is required}
      label=${4:?label is required}
      mark_label=${5:?mark label is required}
      public_session_digest=${6:-}
      mark_file="$evidence_dir/session-caches-$mark_label.tsv"
      [[ -r "$mark_file" ]] || { echo "No mark $mark_label to observe against" >&2; exit 66; }
      target="$evidence_dir/session-caches-$label.tsv"
      : >"$target"
      for runtime_log in "$public_log" "$modern_log"; do
        runtime=public
        [[ "$runtime_log" == "$modern_log" ]] && runtime=modern
        offset=$(awk -F'\t' -v r="$runtime" \
          '$1 == r && $2 == "offset" { print $3 }' "$mark_file")
        offset=${offset:-0}
        if [[ -r "$runtime_log" ]]; then
          parser_args=(observe "$runtime" "$runtime_log" "$offset")
          if [[ "$runtime" == public && -n "$public_session_digest" ]]; then
            parser_args+=("$public_session_digest")
          fi
          python3 "$parser" "${parser_args[@]}" >>"$target"
        else
          printf '%s\tdestruction\tabsent\n' "$runtime" >>"$target"
          unreadable_census "$runtime" >>"$target"
        fi
      done
      printf 'label\t%s\n' "$label" >>"$target"
      ;;
    await)
      evidence_dir=${3:?evidence directory is required}
      mark_label=${4:?mark label is required}
      runtimes=${5:?runtimes are required}
      seconds=${6:?timeout in seconds is required}
      public_session_digest=${7:-}
      mark_file="$evidence_dir/session-caches-$mark_label.tsv"
      [[ -r "$mark_file" ]] || { echo "No mark $mark_label to await against" >&2; exit 66; }
      deadline=$(( $(date +%s) + seconds ))
      while :; do
        pending=""
        for runtime in ${runtimes//,/ }; do
          runtime_log="$public_log"
          [[ "$runtime" == modern ]] && runtime_log="$modern_log"
          offset=$(awk -F'\t' -v r="$runtime" \
            '$1 == r && $2 == "offset" { print $3 }' "$mark_file")
          offset=${offset:-0}
          seen=absent
          if [[ -r "$runtime_log" ]]; then
            parser_args=(observe "$runtime" "$runtime_log" "$offset")
            if [[ "$runtime" == public && -n "$public_session_digest" ]]; then
              parser_args+=("$public_session_digest")
            fi
            seen=$(python3 "$parser" "${parser_args[@]}" |
              awk -F'\t' '$2 == "destruction" { print $3 }')
          fi
          [[ "$seen" == observed ]] || pending="$pending $runtime"
        done
        [[ -z "$pending" ]] && break
        if (( $(date +%s) >= deadline )); then
          echo "No session destruction was recorded on:$pending within ${seconds}s" >&2
          exit 70
        fi
        sleep 2
      done
      echo "Session destruction observed on: $runtimes"
      ;;
    *)
      echo "lifecycle takes mark, observe or await" >&2
      exit 64
      ;;
  esac
  ;;

ephemeral)
  # ADempiere's OWN session-lifetime knob, which SessionManagerListener already
  # applies to every session it creates on both runtimes. Using it - rather than
  # adding an endpoint that expires a session on demand - is what keeps the
  # timeout and container-destruction cases inside the normal application and
  # container lifecycle: no diagnostic route exists, nothing is reachable from
  # the public origin, and the value is ordinary configuration that ships with
  # the product.
  action=${2:?set or restore is required}
  properties="$adempiere_home/Adempiere.properties"
  saved="$repo_root/build/phase5e/Adempiere.properties.pre-phase5e"
  [[ -r "$properties" ]] || {
    echo "The installed $properties is missing" >&2
    exit 66
  }
  case "$action" in
    set)
      seconds=${3:?interval in seconds is required}
      mkdir -p "$(dirname "$saved")"
      [[ -f "$saved" ]] || cp "$properties" "$saved"
      # Written in the product's own clear-value form (`xyz<value>`), which is
      # what Ini.getProperty decrypts; a bare value would be handed to the
      # cipher and read back as nonsense.
      python3 - "$properties" "$seconds" <<'PY'
import sys
path, seconds = sys.argv[1], sys.argv[2]
key = 'EphemeralSessionMaxInactiveInterval'
lines = [l for l in open(path, encoding='ISO-8859-1').read().splitlines()
         if not l.startswith(key + '=')]
lines.append('%s=xyz%s' % (key, seconds))
open(path, 'w', encoding='ISO-8859-1').write('\n'.join(lines) + '\n')
PY
      echo "ADempiere ephemeral session interval set to ${seconds}s"
      ;;
    restore)
      [[ -f "$saved" ]] || { echo "No saved properties to restore"; exit 0; }
      cp "$saved" "$properties"
      rm -f "$saved"
      echo "ADempiere properties restored"
      ;;
    *)
      echo "ephemeral takes set or restore" >&2
      exit 64
      ;;
  esac
  ;;

backend)
  action=${2:?stop or start is required}
  export CATALINA_PID="$repo_root/build/phase5e/tomcat10-routed.pid"
  case "$action" in
    stop)
      # A deliberate backend outage. The contract is that an established modern
      # session gets an explicit failure and is NEVER served the legacy
      # application instead.
      "$tomcat10_dir/bin/catalina.sh" stop 20 -force >/dev/null 2>&1 || true
      for _ in $(seq 1 30); do
        curl -sS -o /dev/null "http://127.0.0.1:$api_port/" 2>/dev/null || break
        sleep 1
      done
      ;;
    start)
      handoff_key="$repo_root/build/phase5e/keys/handoff.key"
      if [[ ! -f "$handoff_key" ]]; then
        echo "The Phase 5e handoff key is missing: $handoff_key" >&2
        exit 66
      fi
      key_mode=$(stat -f '%Lp' "$handoff_key" 2>/dev/null || stat -c '%a' "$handoff_key")
      if [[ "$key_mode" != "600" ]]; then
        echo "The Phase 5e handoff key is mode $key_mode; 600 is required" >&2
        exit 65
      fi
      export CATALINA_OPTS="${CATALINA_OPTS:-} -Duser.timezone=UTC -Duser.language=en -Duser.country=US -Dadempiere.phase5e.handoffKey=$handoff_key"
      "$tomcat10_dir/bin/catalina.sh" start >/dev/null 2>&1
      for _ in $(seq 1 120); do
        status=$(curl -sS -o /dev/null -w '%{http_code}' \
          "http://127.0.0.1:$api_port/ADInterface/services/ADService?wsdl" \
          2>/dev/null || true)
        [[ "$status" == 200 ]] && break
        sleep 1
      done
      ;;
    *)
      echo "backend takes stop or start" >&2
      exit 64
      ;;
  esac
  ;;

interceptor)
  action=${2:?disable or enable is required}
  # The interceptor-omission mutation, applied to the DEPLOYED archive rather
  # than to a source file: the property under test is that a deployment which
  # lost the interceptor fails visibly, and only the deployed descriptor can
  # demonstrate that.
  deployed="$adempiere_home/tomcat/webapps/webui.war"
  saved="$repo_root/build/phase5e/webui-with-interceptor.war"
  # Tomcat 9 serves the EXPANDED context, so rewriting the archive only takes
  # effect when HostConfig notices the newer timestamp and redeploys. Waiting
  # for the redeploy is the difference between testing the mutation and testing
  # a race: without it the browser can observe the pre-mutation context and the
  # row reports on a deployment that was never mutated.
  await_public() {
    local want=$1
    for _ in $(seq 1 60); do
      local seen
      seen=$(curl -sS -o /dev/null -w '%{http_code}' \
        "http://127.0.0.1:$public_port/webui/" 2>/dev/null || true)
      case "$want" in
        refused) [[ -n "$seen" && "$seen" != 200 ]] && return 0 ;;
        serving) [[ "$seen" == 200 ]] && return 0 ;;
      esac
      sleep 1
    done
    echo "The public /webui context did not reach the '$want' state" >&2
    return 70
  }
  case "$action" in
    disable)
      cp "$deployed" "$saved"
      work=$(mktemp -d "$repo_root/build/phase5e/interceptor.XXXXXX")
      unzip -qq -o "$deployed" WEB-INF/zk.xml -d "$work"
      python3 - "$work/WEB-INF/zk.xml" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
text = text.replace('org.adempiere.web.bridge.CohortDecisionInterceptor',
                    'org.adempiere.web.bridge.RemovedByMutation')
open(path, 'w', encoding='utf-8').write(text)
PY
      ( cd "$work" && zip -qr "$deployed" WEB-INF/zk.xml )
      rm -rf "$work"
      # The deployment-completeness listener refuses to start a context whose
      # zk.xml lost the interceptor, so the redeployed context stops serving.
      await_public refused
      ;;
    enable)
      [[ -f "$saved" ]] || { echo "No saved archive to restore" >&2; exit 66; }
      cp "$saved" "$deployed"
      rm -f "$saved"
      await_public serving
      ;;
    *)
      echo "interceptor takes disable or enable" >&2
      exit 64
      ;;
  esac
  ;;

secrets)
  evidence_dir=${2:?evidence directory is required}
  mkdir -p "$evidence_dir"
  target="$evidence_dir/secret-hygiene.tsv"
  : >"$target"
  status=0
  # No log written by either runtime may contain a ticket, an internal session
  # identifier or a URL-rewritten session parameter. The check is over the real
  # logs, not over a sanitised copy.
  for runtime_log in "$public_log" "$modern_log"; do
    [[ -r "$runtime_log" ]] || continue
    runtime=public
    [[ "$runtime_log" == "$modern_log" ]] && runtime=modern
    for pattern in 'X-ADempiere-Handoff-Ticket: ' 'jsessionid=' 'v1\.[A-Za-z0-9_-]\{40,\}\.'; do
      hits=$(grep -c "$pattern" "$runtime_log" 2>/dev/null || true)
      printf '%s\tlog\t%s\t%s\n' "$runtime" \
        "$(printf '%s' "$pattern" | tr ' ' '_')" "${hits:-0}" >>"$target"
      if [[ "${hits:-0}" != "0" ]]; then
        echo "FAIL: $runtime log contains $pattern" >&2
        status=1
      fi
    done
  done
  # Nor may any evidence file.
  hits=$({ grep -rl 'X-ADempiere-Handoff-Ticket: ' "$evidence_dir" 2>/dev/null ||
    true; } | wc -l | tr -d ' ')
  printf 'evidence\tticket\t%s\n' "$hits" >>"$target"
  if [[ "$hits" != "0" ]]; then
    echo "FAIL: an evidence file contains a handoff ticket" >&2
    status=1
  fi
  exit "$status"
  ;;

*)
  echo "Unknown operation: $operation" >&2
  exit 64
  ;;
esac
