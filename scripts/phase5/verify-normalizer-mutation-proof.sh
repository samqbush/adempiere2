#!/usr/bin/env bash
# Phase 5b normalizer mutation proof (RD-2).
#
# The double-capture self-diff in replay-legacy-web-oracle.sh proves the
# normalizer is not UNDER-normalizing: two fresh sessions must reduce to the
# same bytes. It cannot prove the converse. A normalizer that deleted every
# identifier-shaped token would also pass a self-diff while being blind to the
# regressions this oracle exists to catch.
#
# This script proves the normalizer is not OVER-normalizing, by mutating a real
# capture and asserting which mutations survive normalization.
#
#   `stable` fields    -> mutation MUST be detected (normalized output differs)
#   `normalized` fields -> mutation MUST be absorbed (normalized output matches)
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: verify-normalizer-mutation-proof.sh <reference-capture>" >&2
  exit 64
fi

reference=$1
if [[ ! -f "$reference" ]]; then
  echo "Missing reference capture: $reference" >&2
  exit 66
fi

repo_root=$(git rev-parse --show-toplevel)
normalize="$repo_root/scripts/phase5/normalize-web-capture.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

baseline="$work/baseline.txt"
bash "$normalize" "$reference" >"$baseline"

failures=0

assert_detected() {
  local label=$1 mutated=$2
  if bash "$normalize" "$mutated" | diff --brief - "$baseline" >/dev/null 2>&1; then
    echo "FAIL [over-normalized] mutation was absorbed but must be detected: $label" >&2
    failures=$((failures + 1))
  else
    echo "ok   [detected]  $label"
  fi
}

assert_absorbed() {
  local label=$1 mutated=$2
  if bash "$normalize" "$mutated" | diff --brief - "$baseline" >/dev/null 2>&1; then
    echo "ok   [absorbed]  $label"
  else
    echo "FAIL [under-normalized] mutation must be absorbed but was detected: $label" >&2
    failures=$((failures + 1))
  fi
}

# ---------------------------------------------------------------------------
# `stable` fields: every mutation below is a real behavioural change.
# ---------------------------------------------------------------------------

# A changed component identity.
sed 's/zk_comp_5"/zk_comp_9995"/' "$reference" >"$work/m-uuid-changed.txt"
assert_detected 'component uuid changed' "$work/m-uuid-changed.txt"

# Identity equivalence: two distinct components collapsed onto one uuid. A
# positional-ordinal normalizer would wrongly absorb this.
sed 's/zk_comp_7"/zk_comp_6"/' "$reference" >"$work/m-uuid-collapsed.txt"
assert_detected 'two distinct component uuids collapsed into one' \
  "$work/m-uuid-collapsed.txt"

# A removed component changes the component count.
grep -v 'zk_comp_11"' "$reference" >"$work/m-uuid-removed.txt" || true
assert_detected 'component removed (count change)' "$work/m-uuid-removed.txt"

# Reordered output.
awk 'NR<=60' "$reference" >"$work/m-reordered.txt"
awk 'NR>60' "$reference" | sort >>"$work/m-reordered.txt"
assert_detected 'response body reordered' "$work/m-reordered.txt"

# A changed user-visible label.
sed 's/stylesheet/stylesheeet/' "$reference" >"$work/m-label.txt"
assert_detected 'literal text changed' "$work/m-label.txt"

# ---------------------------------------------------------------------------
# `normalized` fields: every mutation below is environmental noise.
# ---------------------------------------------------------------------------

sed 's/jsessionid=[0-9A-Fa-f]*/jsessionid=DEADBEEFDEADBEEFDEADBEEFDEADBEEF/g' \
  "$reference" >"$work/m-session.txt"
assert_absorbed 'session id changed' "$work/m-session.txt"

sed -E 's#/zkau/web/[0-9]+/#/zkau/web/9999999/#g' "$reference" >"$work/m-zkver.txt"
assert_absorbed 'ZK version cache-buster changed' "$work/m-zkver.txt"

# The desktop id is value-driven, so rewriting every occurrence of the real
# desktop id must be absorbed.
desktop_id=$(grep -oE 'z\.dtid="[^"]*"' "$reference" | head -1 \
  | sed -E 's/z\.dtid="([^"]*)"/\1/' || true)
if [[ -n "$desktop_id" ]]; then
  sed "s/${desktop_id}/qzqz/g" "$reference" >"$work/m-dtid.txt"
  assert_absorbed 'desktop id changed' "$work/m-dtid.txt"
else
  echo "skip [absorbed]  desktop id changed (no desktop id in reference)"
fi

echo
if [[ "$failures" -ne 0 ]]; then
  echo "Normalizer mutation proof FAILED with $failures violation(s)." >&2
  exit 1
fi
echo "Normalizer mutation proof passed."
