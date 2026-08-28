#!/usr/bin/env bash
# Runs the legacy product reactor against a disposable source copy. The Ant
# reactor rewrites files under its source tree, including tracked lib binaries,
# so canonical verification must never execute it in the candidate worktree.
set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "Usage: run-isolated-ant-build.sh <repo-root> <source-copy> <release-output> <ant-args...>" >&2
  exit 64
fi

repo_root=$1
source_copy=$2
release_output=$3
shift 3
install_output="$(dirname "$release_output")/install"
lock_dir="$(dirname "$source_copy")/isolated-ant-build.lock"

case "$source_copy" in
  "$repo_root"/build/phase3/*) ;;
  *) echo "Refusing isolated source outside build/phase3: $source_copy" >&2; exit 65 ;;
esac
case "$release_output" in
  "$repo_root"/build/phase3/*) ;;
  *) echo "Refusing release output outside build/phase3: $release_output" >&2; exit 65 ;;
esac
case "$install_output" in
  "$repo_root"/build/phase3/*) ;;
  *) echo "Refusing install output outside build/phase3: $install_output" >&2; exit 65 ;;
esac

if ! mkdir "$lock_dir" 2>/dev/null; then
  lock_pid=
  [[ -f "$lock_dir/pid" ]] && read -r lock_pid < "$lock_dir/pid"
  if [[ "$lock_pid" =~ ^[0-9]+$ ]] && kill -0 "$lock_pid" 2>/dev/null; then
    echo "Another isolated Phase 3 Ant build is already running (PID $lock_pid)" >&2
    exit 75
  fi
  rm -rf "$lock_dir"
  if ! mkdir "$lock_dir" 2>/dev/null; then
    echo "Unable to acquire isolated Phase 3 Ant build lock: $lock_dir" >&2
    exit 75
  fi
fi
printf '%s\n' "$$" > "$lock_dir/pid"
trap 'rm -rf "$lock_dir"' EXIT

rm -rf "$source_copy"
rm -rf "$install_output" "$release_output"
mkdir -p "$source_copy" "$install_output" "$release_output"

rsync -a --delete \
  --exclude '/.git/' \
  --exclude '/build/' \
  --exclude '/install/build/' \
  --exclude '/.gradle/' \
  "$repo_root/" "$source_copy/"

before=$(git -C "$repo_root" status --porcelain --untracked-files=no | LC_ALL=C sort)

(
  cd "$source_copy"
  ant build "$@"
)

after=$(git -C "$repo_root" status --porcelain --untracked-files=no | LC_ALL=C sort)
if [[ "$before" != "$after" ]]; then
  echo "The isolated Ant build changed tracked files in the candidate worktree" >&2
  diff <(printf '%s\n' "$before") <(printf '%s\n' "$after") >&2 || true
  exit 1
fi

zip_file="$install_output/Adempiere_394LTS.zip"
tar_file="$install_output/Adempiere_394LTS.tar.gz"
for artifact in \
  "$zip_file" "$zip_file.MD5" "$tar_file" "$tar_file.MD5"; do
  if [[ ! -f "$artifact" ]]; then
    echo "The isolated Ant build did not stage $(basename "$artifact") in $install_output" >&2
    exit 1
  fi
done

# utils_dev:install copies the release archives to env.ADEMPIERE_INSTALL and
# then expands the ZIP into env.ADEMPIERE_ROOT. install/build is an internal
# reactor scratch directory and is legitimately removed by later clean steps,
# so it is not a stable handoff. Rebuild the unconfigured release tree from the
# exact archive that the isolated reactor staged for installation.
cp "$install_output"/Adempiere_394LTS.* "$release_output/"
unzip -q "$zip_file" -d "$release_output"
if [[ ! -d "$release_output/Adempiere" ]]; then
  echo "The isolated release archive contains no Adempiere product root" >&2
  exit 1
fi

# The Ant ZIP records these tracked launchers without their executable mode.
# Restore the source contract before later phases rebuild the release archives.
for launcher in RUN_API.sh RUN_API_Stop.sh; do
  release_launcher="$release_output/Adempiere/utils/$launcher"
  if [[ ! -f "$release_launcher" ]]; then
    echo "The isolated release archive is missing utils/$launcher" >&2
    exit 1
  fi
  chmod +x "$release_launcher"
done
