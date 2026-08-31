#!/usr/bin/env bash
set -euo pipefail

repo_root=${1:?repository root is required}
evidence_root=${2:?evidence root is required}
db_host=${3:?database host is required}
db_port=${4:?database port is required}
db_name=${5:?database name is required}
db_user=${6:?database user is required}
database_marker=${7:?database marker is required}
properties="$repo_root/gradle/phase4/runtime.properties"
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' "$properties")
mkdir -p "$evidence_root"

# The Phase 4 SOAP corpus authenticates as the deterministic POS fixture, which
# rewrites GardenAdmin's password. The Phase 5f route shards run against the
# seeded oracle credential instead, so the fixture must be applied here, after
# every shard and after the switch baseline has been verified, exactly as the
# Phase 5d capture does (gradle/phase5/zk-functional-slice.gradle:81-85).
# Applying it earlier would make the shards observe a credential the frozen
# legacy oracle never used.
ADEMPIERE_PHASE5D_DB_PASSWORD=${ADEMPIERE_PHASE5F_DB_PASSWORD:?database password environment variable is required} \
  "$repo_root/scripts/phase4/prepare-operation-scenarios.sh" \
  "$db_host" "$db_port" "$db_name" "$db_user" "$database_marker" \
  >"$evidence_root/phase4-soap-fixture.log" 2>&1

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
