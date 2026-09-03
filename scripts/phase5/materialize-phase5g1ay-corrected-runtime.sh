#!/usr/bin/env bash
# Materialize the Phase 5g-1a-y corrected legacy runtime from an exact source
# commit. The reviewed patch is applied only in a detached disposable worktree.
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  cat >&2 <<'USAGE'
Usage: materialize-phase5g1ay-corrected-runtime.sh \
  <repo-root> <installed-home> <output-dir> <freeze|acceptance> [java-home]
USAGE
  exit 64
fi

repo_root=$(cd "$1" && pwd -P)
installed_home=$(cd "$2" && pwd -P)
output_dir=$3
oracle_operation=$4
java_home=${5:-${JAVA_HOME:-}}
contract_dir="$repo_root/contracts/phase5g1ay-workflow-attribution-v1"
contract="$contract_dir/capture-contract.tsv"
patch_file=$(awk -F'\t' '$1 == "patch_file" { print $2 }' "$contract")
patch="$contract_dir/$patch_file"
source_commit=$(awk -F'\t' '$1 == "source_commit" { print $2 }' "$contract")
mode=$(awk -F'\t' '$1 == "mode" { print $2 }' "$contract")
worktree_root="$repo_root/build/phase5g1ay/corrected-source"
owner_marker="$repo_root/build/phase5g1ay/corrected-source.owner"
worktree_owned=0

case "$output_dir" in
  "$repo_root"/build/phase5g1ay/corrected-runtime) ;;
  *)
    echo "FAIL: corrected runtime output must be build/phase5g1ay/corrected-runtime" >&2
    exit 65
    ;;
esac
case "$worktree_root" in
  "$repo_root"/build/phase5g1ay/corrected-source) ;;
  *) echo "FAIL: refusing to manage an unexpected worktree path" >&2; exit 65 ;;
esac
case "$oracle_operation" in
  freeze|acceptance) ;;
  *) echo "FAIL: oracle operation must be freeze or acceptance" >&2; exit 64 ;;
esac
if [[ "$mode" != "corrected-legacy-workflow-attribution" ]]; then
  echo "FAIL: capture contract does not name the corrected-legacy mode" >&2
  exit 65
fi
if [[ -z "$java_home" || ! -x "$java_home/bin/javac" || ! -x "$java_home/bin/jar" ]]; then
  echo "FAIL: a JDK with javac and jar is required" >&2
  exit 66
fi
if ! command -v zip >/dev/null 2>&1; then
  echo "FAIL: zip is required to remove stale signatures from the corrected jar" >&2
  exit 66
fi

if ! git -C "$repo_root" cat-file -e "${source_commit}^{commit}" 2>/dev/null; then
  git -C "$repo_root" fetch --no-tags --depth=1 origin "$source_commit"
fi
git -C "$repo_root" cat-file -e "${source_commit}^{commit}"

python3 "$repo_root/scripts/phase5/validate-phase5g1ay-oracle.py" \
  --repo-root "$repo_root" --contract-dir "$contract_dir"

before_state=$(git -C "$repo_root" status --porcelain --untracked-files=no | LC_ALL=C sort)

owner_marker_matches() {
  [[ -f "$owner_marker" ]] || return 1
  [[ "$(cat "$owner_marker")" == "$repo_root
$worktree_root
$source_commit" ]]
}

exact_worktree_is_registered() {
  local listing
  if ! listing=$(git -C "$repo_root" worktree list --porcelain); then
    echo "FAIL: cannot inspect registered worktrees" >&2
    return 2
  fi
  awk -v expected="$worktree_root" '
    $1 == "worktree" && substr($0, 10) == expected { found = 1 }
    END { exit(found ? 0 : 1) }
  ' <<<"$listing"
}

cleanup_owned_worktree() {
  local registration_status
  if [[ "$worktree_owned" -ne 1 ]] && ! owner_marker_matches; then
    if exact_worktree_is_registered; then
      echo "FAIL: refusing to remove unowned registered worktree $worktree_root" >&2
      return 1
    else
      registration_status=$?
      [[ "$registration_status" -ne 2 ]] || return 1
    fi
    if [[ -e "$worktree_root" || -L "$worktree_root" || -e "$owner_marker" ]]; then
      echo "FAIL: refusing to delete unowned corrected-source residue" >&2
      return 1
    fi
    return 0
  fi

  if exact_worktree_is_registered; then
    if ! git -C "$repo_root" worktree remove --force "$worktree_root"; then
      echo "FAIL: Git could not unregister owned worktree $worktree_root" >&2
      return 1
    fi
    if exact_worktree_is_registered; then
      echo "FAIL: owned worktree remains registered after removal" >&2
      return 1
    else
      registration_status=$?
      [[ "$registration_status" -ne 2 ]] || return 1
    fi
  else
    registration_status=$?
    [[ "$registration_status" -ne 2 ]] || return 1
    if [[ -e "$worktree_root" || -L "$worktree_root" ]]; then
      echo "FAIL: owned worktree exists but Git cannot unregister it" >&2
      return 1
    fi
  fi
  if [[ -e "$worktree_root" || -L "$worktree_root" ]]; then
    echo "FAIL: Git left the owned worktree path behind" >&2
    return 1
  fi
  if ! rm -f "$owner_marker"; then
    echo "FAIL: cannot remove corrected-source ownership marker" >&2
    return 1
  fi
  worktree_owned=0
}

cleanup_on_exit() {
  local status=$?
  trap - EXIT
  if ! cleanup_owned_worktree; then
    exit 1
  fi
  exit "$status"
}

cleanup_owned_worktree
mkdir -p "$(dirname "$worktree_root")"
trap cleanup_on_exit EXIT
git -C "$repo_root" worktree add --detach --force "$worktree_root" "$source_commit" >/dev/null
worktree_owned=1
printf '%s\n%s\n%s\n' "$repo_root" "$worktree_root" "$source_commit" >"$owner_marker"

if [[ "$(git -C "$worktree_root" rev-parse HEAD)" != "$source_commit" ]]; then
  echo "FAIL: corrected source worktree is not at source_commit" >&2
  exit 1
fi
if [[ -n "$(git -C "$worktree_root" status --porcelain --untracked-files=no)" ]]; then
  echo "FAIL: corrected source worktree is dirty before patching" >&2
  exit 1
fi

(cd "$worktree_root" && git apply --ignore-whitespace "$patch")

allowed_paths=()
while IFS= read -r relative; do
  [[ -n "$relative" && "$relative" != \#* ]] || continue
  allowed_paths+=("$relative")
done <"$contract_dir/allowed-patched-paths.txt"
patched_paths=()
while IFS= read -r relative; do
  [[ -n "$relative" ]] || continue
  patched_paths+=("$relative")
done < <(git -C "$worktree_root" diff --name-only | LC_ALL=C sort)
if [[ "$(printf '%s\n' "${allowed_paths[@]}" | LC_ALL=C sort)" != \
  "$(printf '%s\n' "${patched_paths[@]}")" ]]; then
  echo "FAIL: applied patch changed an unreviewed path" >&2
  printf 'allowed:\n%s\npatched:\n%s\n' \
    "$(printf '%s\n' "${allowed_paths[@]}" | LC_ALL=C sort)" \
    "$(printf '%s\n' "${patched_paths[@]}")" >&2
  exit 1
fi

baseline_jar="$installed_home/tomcat/lib/Adempiere.jar"
installed_jar="$installed_home/lib/Adempiere.jar"
for jar_path in "$baseline_jar" "$installed_jar"; do
  if [[ ! -f "$jar_path" ]]; then
    echo "FAIL: installed legacy runtime is missing $jar_path" >&2
    exit 66
  fi
done

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

if [[ "$(sha256_of "$baseline_jar")" != "$(sha256_of "$installed_jar")" ]]; then
  echo "FAIL: installed and Tomcat Adempiere.jar inputs differ" >&2
  exit 1
fi

rm -rf "$output_dir"
classes_dir="$output_dir/classes"
empty_sourcepath="$output_dir/empty-sourcepath"
logs_dir="$output_dir/logs"
mkdir -p "$classes_dir" "$empty_sourcepath" "$logs_dir"

classpath=$(
  find "$installed_home/lib" "$installed_home/tomcat/lib" \
    -maxdepth 1 -type f -name '*.jar' -print | LC_ALL=C sort | paste -sd: -
)
if [[ -z "$classpath" ]]; then
  echo "FAIL: installed legacy runtime classpath is empty" >&2
  exit 66
fi

sources=()
for relative in "${allowed_paths[@]}"; do
  sources+=("$worktree_root/$relative")
done

if ! "$java_home/bin/javac" \
  --release 21 \
  -encoding UTF-8 \
  -sourcepath "$empty_sourcepath" \
  -classpath "$classpath" \
  -d "$classes_dir" \
  "${sources[@]}" >"$logs_dir/javac.log" 2>&1; then
  echo "FAIL: corrected legacy workflow classes did not compile" >&2
  tail -80 "$logs_dir/javac.log" >&2 || true
  exit 1
fi

corrected_jar="$output_dir/Adempiere.jar"
cp "$baseline_jar" "$corrected_jar.tmp"
mv "$corrected_jar.tmp" "$corrected_jar"

expected_signatures=()
while IFS= read -r signature; do
  [[ -n "$signature" && "$signature" != \#* ]] || continue
  expected_signatures+=("$signature")
done <"$contract_dir/removed-signature-entries.txt"

actual_signatures=()
while IFS= read -r signature; do
  [[ -n "$signature" ]] && actual_signatures+=("$signature")
done < <(
  "$java_home/bin/jar" --list --file "$corrected_jar" \
    | LC_ALL=C grep -E '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' \
    | LC_ALL=C sort
)
if [[ "${actual_signatures[*]}" != "${expected_signatures[*]}" ]]; then
  echo "FAIL: baseline Adempiere.jar signature entries differ from the capture contract" >&2
  printf 'expected: %s\nactual:   %s\n' \
    "${expected_signatures[*]}" "${actual_signatures[*]}" >&2
  exit 1
fi
zip -q -d "$corrected_jar" "${expected_signatures[@]}"
if "$java_home/bin/jar" --list --file "$corrected_jar" \
  | grep -Eq '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$'
then
  echo "FAIL: stale signature entries remain in corrected Adempiere.jar" >&2
  exit 1
fi

class_list="$output_dir/runtime-classes.txt"
: >"$class_list"
for class_name in DocWorkflowManager MWorkflow MWFProcess; do
  found=0
  for class_file in "$classes_dir/org/compiere/wf/${class_name}"*.class; do
    [[ -f "$class_file" ]] || continue
    relative=${class_file#"$classes_dir/"}
    printf '%s\n' "$relative" >>"$class_list"
    found=1
  done
  if [[ "$found" -ne 1 ]]; then
    echo "FAIL: javac produced no class for $class_name" >&2
    exit 1
  fi
done
LC_ALL=C sort -u "$class_list" -o "$class_list"

while IFS= read -r relative; do
  "$java_home/bin/jar" --update --file "$corrected_jar" \
    -C "$classes_dir" "$relative"
done <"$class_list"

while IFS= read -r relative; do
  if ! "$java_home/bin/jar" --list --file "$corrected_jar" | grep -Fxq "$relative"; then
    echo "FAIL: corrected runtime jar is missing $relative" >&2
    exit 1
  fi
done <"$class_list"

after_state=$(git -C "$repo_root" status --porcelain --untracked-files=no | LC_ALL=C sort)
if [[ "$before_state" != "$after_state" ]]; then
  echo "FAIL: corrected runtime materialization changed tracked repository files" >&2
  diff <(printf '%s\n' "$before_state") <(printf '%s\n' "$after_state") >&2 || true
  exit 1
fi

repository_head=$(git -C "$repo_root" rev-parse HEAD)
python3 - "$repo_root" "$output_dir" "$source_commit" "$mode" \
  "$repository_head" "$oracle_operation" "$contract_dir" \
  "${expected_signatures[*]}" <<'PY'
import hashlib
import json
import pathlib
import sys

repo_root = pathlib.Path(sys.argv[1]).resolve()
output_dir = pathlib.Path(sys.argv[2]).resolve()
source_commit, mode, repository_head, oracle_operation = sys.argv[3:7]
contract_dir = pathlib.Path(sys.argv[7])
removed_jar_signatures = sys.argv[8].split()

contract = {}
for line in (contract_dir / "capture-contract.tsv").read_text(encoding="utf-8").splitlines():
    if line and not line.startswith("#"):
        key, value = line.split("\t")
        contract[key] = value
patched_paths = [
    line
    for line in (contract_dir / "allowed-patched-paths.txt").read_text(
        encoding="utf-8"
    ).splitlines()
    if line and not line.startswith("#")
]
artifact_paths = [output_dir / "Adempiere.jar"]
artifact_paths.extend(
    output_dir / "classes/org/compiere/wf" / class_name
    for class_name in (
        "DocWorkflowManager.class",
        "MWorkflow.class",
        "MWFProcess.class",
    )
)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

data = {
    "schema_version": int(contract["provenance_schema_version"]),
    "phase": "5g-1a-y",
    "mode": mode,
    "source_commit": source_commit,
    "patch_sha256": contract["patch_sha256"],
    "patched_paths": patched_paths,
    "runtime_artifacts": [
        {
            "path": path.relative_to(repo_root).as_posix(),
            "sha256": digest(path),
        }
        for path in artifact_paths
    ],
    "removed_jar_signatures": removed_jar_signatures,
    "repository_head": repository_head,
    "oracle_operation": oracle_operation,
    "isolated_source_worktree": True,
    "ordinary_repository_unchanged": True,
}
(output_dir / "provenance.json").write_text(
    json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
PY

python3 "$repo_root/scripts/phase5/validate-phase5g1ay-provenance.py" \
  --provenance "$output_dir/provenance.json" \
  --contract-dir "$contract_dir" \
  --repo-root "$repo_root" \
  --verify-artifacts

echo "Materialized corrected legacy runtime at $corrected_jar"
