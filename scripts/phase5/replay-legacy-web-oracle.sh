#!/usr/bin/env bash
# Phase 5b oracle replay gate.
#
# Runs the two proofs that make the frozen tree meaningful:
#
#   1. Self-diff (determinism).  Capture A, reset the database fixture, capture
#      B, and require them to be byte-identical after normalization. A failure
#      here is a NORMALIZER defect or an unstable capture, not a product
#      regression, and is reported as such. Conflating the two would train the
#      reader to dismiss real failures.
#
#   2. Frozen-tree diff (regression).  Compare capture A against
#      contracts/legacy-web-v1/. A failure here means the legacy web behaviour
#      changed relative to the frozen oracle.
#
# The database fixture reset between A and B is mandatory: login writes
# AD_Session rows and role completion writes user preferences, so without it the
# self-diff is not an isolated experiment.
set -euo pipefail

if [[ $# -ne 8 ]]; then
  echo "Usage: replay-legacy-web-oracle.sh <port> <oracle-user> <db-host> <db-port> <db-name> <db-user> <db-password> <db-marker>" >&2
  exit 64
fi

port=$1
oracle_user=$2
db_host=$3
db_port=$4
db_name=$5
db_user=$6
db_password=$7
db_marker=$8

repo_root=$(git rev-parse --show-toplevel)
work="$repo_root/build/phase5b/replay"
frozen="$repo_root/contracts/legacy-web-v1"
capture="$repo_root/scripts/phase5/capture-legacy-web-oracle.sh"
fixture="$repo_root/scripts/phase5/reset-oracle-fixture.sh"

[[ -d "$frozen" ]] || { echo "Missing frozen oracle tree: $frozen" >&2; exit 66; }

rm -rf "$work"
mkdir -p "$work"

run_fixture() {
  "$fixture" "$db_host" "$db_port" "$db_name" "$db_user" "$db_password" "$db_marker" "$@"
}

# The frozen oracle was captured against a database where the oracle user had
# already logged in once. First login is not idempotent: it creates the user's
# AD_Preference rows and AD_Tree_Favorite node, and logs both. Running the
# determinism experiment straight onto a freshly restored seed would therefore
# compare a first login against a repeat login and blame the difference on the
# normalizer. Prime the database instead of assuming its state.
if [[ "$(run_fixture state)" == cold ]]; then
  echo "== priming a cold database (first login is not idempotent) =="
  run_fixture snapshot "$work/prime.tsv"
  "$capture" "$port" "$work/prime" "$oracle_user" >"$work/capture-prime.log" 2>&1 \
    || { echo "Priming capture failed; see $work/capture-prime.log" >&2; tail -20 "$work/capture-prime.log" >&2; exit 1; }
  run_fixture reset "$work/prime.tsv"
  rm -rf "$work/prime"
fi

echo "== fixture snapshot =="
run_fixture snapshot "$work/fixture.tsv"

# Reset before capture A as well as between A and B. Capture A is not entitled
# to inherit whatever UI state the priming capture or a previous run left
# behind; both captures must start from the same fixture or the experiment is
# not controlled.
echo "== fixture reset (establish the capture precondition) =="
run_fixture reset "$work/fixture.tsv"

echo "== capture A =="
"$capture" "$port" "$work/A" "$oracle_user" >"$work/capture-a.log" 2>&1 \
  || { echo "Capture A failed; see $work/capture-a.log" >&2; tail -20 "$work/capture-a.log" >&2; exit 1; }

echo "== fixture verify + reset =="
run_fixture verify "$work/fixture.tsv"
run_fixture reset "$work/fixture.tsv"

echo "== capture B =="
"$capture" "$port" "$work/B" "$oracle_user" >"$work/capture-b.log" 2>&1 \
  || { echo "Capture B failed; see $work/capture-b.log" >&2; tail -20 "$work/capture-b.log" >&2; exit 1; }

run_fixture verify "$work/fixture.tsv"
run_fixture reset "$work/fixture.tsv"

# ---------------------------------------------------------------------------
# Proof 1: determinism.
# ---------------------------------------------------------------------------
echo
echo "== self-diff (determinism) =="
self_diff_failed=0
for artifact in zk-au-flows.tsv context-observed.tsv session-http-observed.tsv static-asset-contract.tsv; do
  if ! diff -u "$work/A/$artifact" "$work/B/$artifact" >"$work/selfdiff-$artifact.diff"; then
    self_diff_failed=1
    echo "  DIVERGED: $artifact" >&2
    head -20 "$work/selfdiff-$artifact.diff" >&2
  fi
done
for tree in zk-bootstrap context-responses; do
  if ! diff -r "$work/A/$tree" "$work/B/$tree" >"$work/selfdiff-$tree.diff" 2>&1; then
    self_diff_failed=1
    echo "  DIVERGED: $tree/" >&2
    head -20 "$work/selfdiff-$tree.diff" >&2
  fi
done

if (( self_diff_failed )); then
  cat >&2 <<EOF

NORMALIZER DEFECT (not a product regression).

Two captures of the same unchanged product produced different normalized
output. The oracle cannot distinguish a real regression until this is fixed.
Either a nondeterministic field is not covered by
contracts/legacy-web-v1/normalization-policy.md, or the capture is not
isolated from its own side effects.

Do NOT re-freeze the oracle to make this pass.
Diffs: $work/selfdiff-*.diff
EOF
  exit 2
fi
echo "  captures A and B are identical"

# ---------------------------------------------------------------------------
# Proof 2: regression against the frozen tree.
# ---------------------------------------------------------------------------
echo
echo "== frozen-tree diff (regression) =="
regression_failed=0

compare() {
  local captured=$1 expected=$2 label=$3
  if [[ ! -e "$expected" ]]; then
    echo "  MISSING from frozen tree: $label" >&2
    regression_failed=1
    return
  fi
  if ! diff -u "$expected" "$captured" >"$work/frozen-$(basename "$label").diff" 2>&1; then
    regression_failed=1
    echo "  CHANGED: $label" >&2
    # Report the first divergence rather than the whole diff, so the failure is
    # readable when a 600 KB desktop response shifts.
    head -15 "$work/frozen-$(basename "$label").diff" >&2
  fi
}

compare "$work/A/zk-au-flows.tsv" "$frozen/zk-au-flows.tsv" zk-au-flows.tsv
compare "$work/A/context-observed.tsv" "$frozen/context-observed.tsv" context-observed.tsv
compare "$work/A/session-http-observed.tsv" "$frozen/session-http-observed.tsv" session-http-observed.tsv
compare "$work/A/static-asset-contract.tsv" "$frozen/static-asset-contract.tsv" static-asset-contract.tsv

for tree in zk-bootstrap context-responses; do
  if ! diff -r "$frozen/$tree" "$work/A/$tree" >"$work/frozen-$tree.diff" 2>&1; then
    regression_failed=1
    echo "  CHANGED: $tree/" >&2
    head -15 "$work/frozen-$tree.diff" >&2
  fi
done

if (( regression_failed )); then
  cat >&2 <<EOF

PRODUCT REGRESSION against the frozen legacy web oracle.

The installed Tomcat 9 / ZK 3.6 product no longer behaves as
contracts/legacy-web-v1/ records. Either the change is intended, in which case
the contract must be updated deliberately with review, or it is a regression.
Diffs: $work/frozen-*.diff
EOF
  exit 1
fi

echo "  capture matches the frozen oracle"
echo
echo "Replay passed: determinism and frozen-tree comparison both green."
