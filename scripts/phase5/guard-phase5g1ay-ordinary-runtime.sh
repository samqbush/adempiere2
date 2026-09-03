#!/usr/bin/env bash
# Independently snapshot, activate, and restore the ordinary Phase 5g-1a-y
# installed runtime. Gradle invokes restore as a finalizer even when the smoke
# script fails.
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  cat >&2 <<'USAGE'
Usage: guard-phase5g1ay-ordinary-runtime.sh \
  <snapshot|activate|restore> <repo-root> <installed-home> <guard-dir> \
  [corrected-jar|--cleanup]
USAGE
  exit 64
fi

operation=$1
repo_root=$(cd "$2" && pwd -P)
installed_home=$(cd "$3" && pwd -P)
guard_dir=$4
extra=${5:-}

case "$guard_dir" in
  "$repo_root"/build/phase5g1ay/ordinary-runtime-guard) ;;
  *)
    echo "FAIL: ordinary runtime guard must use build/phase5g1ay/ordinary-runtime-guard" >&2
    exit 65
    ;;
esac

targets=(
  "$installed_home/lib/Adempiere.jar"
  "$installed_home/tomcat/lib/Adempiere.jar"
)

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

snapshot_runtime() {
  rm -rf "$guard_dir"
  mkdir -p "$guard_dir"
  local index=0
  local target
  for target in "${targets[@]}"; do
    if [[ ! -f "$target" ]]; then
      echo "FAIL: ordinary runtime target is missing: $target" >&2
      exit 66
    fi
    mkdir -p "$guard_dir/$index"
    cp "$target" "$guard_dir/$index/Adempiere.jar.tmp"
    mv "$guard_dir/$index/Adempiere.jar.tmp" \
      "$guard_dir/$index/Adempiere.jar"
    sha256_of "$target" >"$guard_dir/$index/sha256"
    index=$((index + 1))
  done
  : >"$guard_dir/complete"
}

activate_runtime() {
  local corrected_jar=$1
  if [[ ! -f "$guard_dir/complete" || ! -f "$corrected_jar" ]]; then
    echo "FAIL: corrected runtime activation requires a complete snapshot and jar" >&2
    exit 66
  fi
  local index=0
  local target
  for target in "${targets[@]}"; do
    local expected="$guard_dir/$index/sha256"
    if [[ ! -f "$expected" || "$(sha256_of "$target")" != "$(cat "$expected")" ]]; then
      echo "FAIL: ordinary runtime changed after its guard snapshot: $target" >&2
      exit 1
    fi
    cp "$corrected_jar" "$target.tmp"
    mv "$target.tmp" "$target"
    if [[ "$(sha256_of "$target")" != "$(sha256_of "$corrected_jar")" ]]; then
      echo "FAIL: corrected runtime activation digest differs: $target" >&2
      exit 1
    fi
    index=$((index + 1))
  done
  : >"$guard_dir/active"
}

restore_runtime() {
  local cleanup=$1
  local status=0
  if [[ ! -f "$guard_dir/complete" ]]; then
    echo "FAIL: ordinary runtime guard snapshot is missing" >&2
    return 1
  fi
  local index=0
  local target
  for target in "${targets[@]}"; do
    local backup="$guard_dir/$index/Adempiere.jar"
    local expected="$guard_dir/$index/sha256"
    if [[ ! -f "$backup" || ! -f "$expected" ]]; then
      echo "FAIL: ordinary runtime guard is incomplete for $target" >&2
      status=1
      index=$((index + 1))
      continue
    fi
    if ! cp "$backup" "$target.tmp" || ! mv "$target.tmp" "$target"; then
      echo "FAIL: ordinary runtime restoration failed for $target" >&2
      status=1
      index=$((index + 1))
      continue
    fi
    if [[ "$(sha256_of "$target")" != "$(cat "$expected")" ]]; then
      echo "FAIL: ordinary runtime was not restored byte-for-byte: $target" >&2
      status=1
    fi
    index=$((index + 1))
  done
  if [[ "$status" -eq 0 ]]; then
    rm -f "$guard_dir/active"
    if [[ "$cleanup" == "--cleanup" ]]; then
      rm -rf "$guard_dir"
    fi
  fi
  return "$status"
}

case "$operation" in
  snapshot)
    [[ $# -eq 4 ]] || { echo "FAIL: snapshot accepts no extra argument" >&2; exit 64; }
    snapshot_runtime
    ;;
  activate)
    [[ $# -eq 5 && "$extra" != "--cleanup" ]] || {
      echo "FAIL: activate requires the corrected runtime jar" >&2
      exit 64
    }
    activate_runtime "$extra"
    ;;
  restore)
    [[ $# -eq 4 || ( $# -eq 5 && "$extra" == "--cleanup" ) ]] || {
      echo "FAIL: restore accepts only the optional --cleanup argument" >&2
      exit 64
    }
    restore_runtime "$extra"
    ;;
  *)
    echo "FAIL: operation must be snapshot, activate, or restore" >&2
    exit 64
    ;;
esac
