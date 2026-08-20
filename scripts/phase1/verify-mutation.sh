#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
target="base/src/org/compiere/process/DocumentEngine.java"
patch="docs/modernization/evidence/mutation-DocumentEngine.patch"

cd "$root"
normalize_target() {
  perl -pi -e 's/\r?$/\r/' "$target"
  perl -pi -e 'if (eof) { s/\r$// }' "$target"
}
restore() {
  git apply --ignore-space-change --reverse "$patch" 2>/dev/null || true
  normalize_target
}
trap restore EXIT

git diff --exit-code -- "$target"
git apply --ignore-space-change "$patch"

if ./gradlew :base:test \
  --tests 'org.compiere.process.TestDocumentEngine.whenCalledWithStatusGetValidActionsReturnCorrectListOfActions'; then
  echo "Mutation was not detected" >&2
  exit 1
fi

git apply --ignore-space-change --reverse "$patch"
trap - EXIT
normalize_target
git diff --exit-code -- "$target"

./gradlew :base:test \
  --tests 'org.compiere.process.TestDocumentEngine.whenCalledWithStatusGetValidActionsReturnCorrectListOfActions'
