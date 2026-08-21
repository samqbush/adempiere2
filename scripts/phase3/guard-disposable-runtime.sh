#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "Usage: guard-disposable-runtime.sh <repo-root> <runtime-root> <adempiere-home> <install-dir> <db-host> <db-name> <db-user>" >&2
  exit 64
fi

repo_root=$(cd "$1" && pwd -P)
runtime_root=$2
adempiere_home=$3
install_dir=$4
db_host=$5
db_name=$6
db_user=$7
phase3_root="$repo_root/build/phase3"

canonical_parent() {
  local target=$1
  local parent
  parent=$(dirname "$target")
  mkdir -p "$parent"
  printf '%s/%s\n' "$(cd "$parent" && pwd -P)" "$(basename "$target")"
}

runtime_root=$(canonical_parent "$runtime_root")
adempiere_home=$(canonical_parent "$adempiere_home")
install_dir=$(canonical_parent "$install_dir")

for target in "$runtime_root" "$adempiere_home" "$install_dir"; do
  if [[ "$target" != "$phase3_root/"* ]]; then
    echo "Phase 3 paths must stay below $phase3_root. Refusing: $target" >&2
    exit 65
  fi
done

if [[ "$adempiere_home" != "$runtime_root/Adempiere" ]]; then
  echo "Phase 3 ADEMPIERE_HOME must be the Adempiere child of the disposable runtime root." >&2
  exit 65
fi
if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ]]; then
  echo "Phase 3 database target must stay local. Refusing host: $db_host" >&2
  exit 65
fi
if [[ "$db_name" != "adempiere_phase3_ci" || "$db_user" != "adempiere_phase3_ci" ]]; then
  echo "Phase 3 database name and user must both equal adempiere_phase3_ci." >&2
  exit 65
fi

printf 'Guarded Phase 3 runtime: %s\n' "$runtime_root"
