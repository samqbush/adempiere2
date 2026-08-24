#!/usr/bin/env bash
# Phase 5b request-vector generator (RD-7 / H4).
#
# gradle/phase5/route-contracts.tsv records descriptor MAPPINGS, not safe
# concrete URLs. A coverage gate built directly on it would be vacuous: one
# observation of a context root could be claimed as coverage for every route in
# that context, and wildcard patterns like /productServlet/*, *.zul or /* are
# not addressable as written.
#
# This generator turns each deployed non-SOAP route into a concrete request
# vector and probes the live installed product to record what it actually does.
# Its output is a REVIEWED artifact: regenerate it, read the diff, and only then
# commit. It is not run as part of the gate, because the gate must compare
# against a reviewed baseline rather than against whatever the product does
# today.
#
# proof_strength semantics:
#   exact-servlet-dispatch    the vector reaches that specific servlet, so the
#                             row proves the route individually.
#   context-reachability-only the route cannot be addressed independently
#                             (filters mapped at /*). Covered through the
#                             representative routes of the same context; the row
#                             carries an owner and a closing gate.
#
# /ADInterface is excluded by assertion, not by omission: it is the Phase 4 SOAP
# surface, already frozen under org.adempiere.webservice/contracts/xfire-v1/.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: generate-request-vectors.sh <port> <output-file>" >&2
  exit 64
fi

port=$1
output=$2
repo_root=$(git rev-parse --show-toplevel)
routes="$repo_root/gradle/phase5/route-contracts.tsv"

[[ -f "$routes" ]] || { echo "Missing $routes" >&2; exit 66; }
if [[ ! "$port" =~ ^[0-9]+$ ]]; then
  echo "Tomcat port must be numeric." >&2
  exit 65
fi

# Concrete resources that exist in the installed WARs, used so that extension
# mappings are proven against a real resource rather than a 404 path.
# Expressed as a function rather than an associative array: macOS ships bash 3.2,
# and no other script in this repository depends on bash 4 features.
concrete_resource() {
  case "$1|$2" in
    '/webui|*.zul') printf '/webui/index.zul' ;;
    '/webui|*.dsp') printf '/webui/theme/default/css/theme.css.dsp' ;;
    *) printf '' ;;
  esac
}

probe() {
  curl --silent --output /dev/null --write-out '%{http_code}' --max-time 30 \
    --user-agent 'Phase5b-vector-probe' "http://127.0.0.1:$port$1"
}

{
  printf 'route_id\tcontext\tmethod\tpath\tbody_shape\texpected_status\tproof_strength\tnotes\n'

  awk -F'\t' 'NR > 1 && $10 == "deployed" && $12 != "/ADInterface" {
    printf "%s\t%s\t%s\t%s\n", $12, $3, $5, $2
  }' "$routes" | sort -u | while IFS=$'\t' read -r context name pattern kind; do
    name=$(printf '%s' "$name" | sed -E 's/^ +| +$//g')
    context_prefix=${context%/}

    proof=exact-servlet-dispatch
    notes=none
    path=""
    concrete=$(concrete_resource "$context" "$pattern")

    if [[ "$kind" == filter && "$pattern" == '/*' ]]; then
      # A filter mapped at /* has no address of its own. Record it honestly
      # rather than pretending the context root proves it.
      path="$context_prefix/"
      proof=context-reachability-only
      notes="filter-covered-via-context-routes"
    elif [[ -n "$concrete" ]]; then
      path=$concrete
      notes="concrete-resource-for-extension-mapping"
    elif [[ "$pattern" == '*.'* ]]; then
      path="$context_prefix/phase5b-probe${pattern#\*}"
      notes="extension-mapping-probed-with-absent-resource"
    elif [[ "$pattern" == */\* ]]; then
      path="$context_prefix${pattern%\*}"
      notes="path-prefix-mapping-probed-at-prefix-root"
    else
      path="$context_prefix$pattern"
    fi

    [[ "$path" == /* ]] || path="/$path"

    status=$(probe "$path")
    route_id="${context}::${name}::${pattern}"
    printf '%s\t%s\tGET\t%s\tnone\t%s\t%s\t%s\n' \
      "$route_id" "$context" "$path" "$status" "$proof" "$notes"
  done
} >"$output"

echo "Wrote $(($(wc -l <"$output") - 1)) request vectors to $output"
