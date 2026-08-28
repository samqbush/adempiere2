#!/usr/bin/env bash
set -euo pipefail

repo_root=${1:?repository root is required}
evidence_root=${2:?evidence root is required}
properties="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' "$properties")
mkdir -p "$evidence_root"
"$repo_root/scripts/phase4/smoke-cxf-api.sh" \
  "http://127.0.0.1:$api_port/ADInterface/services" full \
  >"$evidence_root/phase4-soap-coexistence.log" 2>&1
printf 'phase4_soap_corpus\tpass\twhile-phase5f-route-shards-active\n' \
  >"$evidence_root/phase4-soap-coexistence.tsv"

if grep -R -E -i '(password|secret|token|handoff)[[:space:]]*[=:][[:space:]]*[^[:space:]]+' \
    "$evidence_root" --include='*.tsv' --include='*.json' >/dev/null; then
  echo "Phase 5f evidence contains a credential-shaped value" >&2
  exit 65
fi
printf 'secret_hygiene\tpass\n' >"$evidence_root/secret-hygiene.tsv"
