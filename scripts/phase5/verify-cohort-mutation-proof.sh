#!/usr/bin/env bash
# Phase 5e: mutation proof.
#
# A contract that no mutation can break is a description, not a gate. This
# script applies each reviewed mutation to the tree, rebuilds it, runs the named
# test that must notice it, and fails if that test still passes.
#
# Scoring is deliberately narrow, because the usual way a mutation proof rots
# into a no-op is by scoring the wrong signal:
#
#   * A mutation that does not COMPILE is a broken mutation, not a detection.
#     The reviewed statement is "assertion X fails when behaviour Y changes",
#     and a compiler error proves nothing about assertion X. Every mutant is
#     therefore compiled first, and a compile failure fails the gate with a
#     distinct message rather than being counted.
#   * A build that fails for an infrastructure reason - a Gradle lock timeout, a
#     missing dependency, a filter that matched no test - is not a detection
#     either. Detection is read from the named test class's own JUnit XML
#     report, which has to exist, has to have run at least one test, and has to
#     record a failure or an error. A non-zero exit status on its own is never
#     enough.
#
# Each row is `id~file~from~to~test-class`, tilde-separated because several of
# the mutated expressions contain a pipe. The "from" text must exist exactly
# once, so a mutation that silently applies to nothing fails before the build
# runs.
#
# Every mutated file is restored by an EXIT trap, and the pristine artifacts are
# rebuilt afterwards, so neither an interrupted run nor a completed one can
# leave a mutated source or a mutated jar behind for a later task to package.
set -euo pipefail

repo_root=${1:?repository root is required}
evidence_file=${2:?evidence file is required}

work="$repo_root/build/phase5e/mutation"
rm -rf "$work"
mkdir -p "$work"
mkdir -p "$(dirname "$evidence_file")"

cohort_src="org.adempiere.cohort/src/main/java/org/adempiere/web"
bridge_src="org.adempiere.cohort/src/bridge/java/org/adempiere/web/bridge"
results_root="$repo_root/org.adempiere.cohort/build/test-results"

# id ~ file ~ from ~ to ~ test class
#
# Declared as arrays rather than heredocs inside $( ): bash 3.2, which is still
# /bin/bash on macOS, mis-parses an unbalanced parenthesis inside a heredoc in a
# command substitution, and two of these expressions contain one.
mutations=(
'routing-precedence~SRC/cohort/CohortSelector.java~if (!configuration.enabled()) {~if (false) {~org.adempiere.web.cohort.CohortSelectorTest'
'ticket-single-use~SRC/handoff/ReplayCache.java~if (existing != null) {~if (false) {~org.adempiere.web.handoff.HandoffTicketCodecTest'
'reserved-namespace~SRC/handoff/HandoffProtocol.java~.startsWith(RESERVED_HEADER_PREFIX.toLowerCase(Locale.ROOT));~.startsWith("never-a-real-prefix");~org.adempiere.web.handoff.HandoffTicketCodecTest'
'cookie-never-forwarded~SRC/route/ProxyHeaderPolicy.java~"x-requested-with");~"x-requested-with", "cookie");~org.adempiere.web.route.PublicRouteClassifierTest'
'cohort-grammar~SRC/cohort/CohortConfigurationParser.java~Pattern.compile("|[1-9][0-9]{0,8}(,[1-9][0-9]{0,8})*");~Pattern.compile(".*");~org.adempiere.web.cohort.CohortConfigurationParserTest'
'duplicate-detection~SRC/cohort/CohortConfigurationParser.java~if (matches.size() > 1) {~if (false) {~org.adempiere.web.cohort.CohortConfigurationParserTest'
'audit-leak~SRC/route/RoutingAudit.java~if (lower.contains(forbidden)) {~if (false) {~org.adempiere.web.route.PublicRouteClassifierTest'
'affinity-one-way~SRC/route/ModernSessionAffinity.java~this.phase = Phase.FAILED;~this.phase = Phase.BOOTSTRAPPED;~org.adempiere.web.route.PublicRouteClassifierTest'
'handoff-admission-atomic~SRC/route/ModernSessionAffinity.java~phase = Phase.ROTATING;~phase = Phase.PENDING_ROTATION;~org.adempiere.web.route.PublicRouteClassifierTest'
'ticket-not-persisted~SRC/route/ModernSessionAffinity.java~private transient String ticket;~private String ticket;~org.adempiere.web.route.PublicRouteClassifierTest'
'transition-safe-prefix~SRC/route/PublicRouteClassifier.java~!path.startsWith(TRANSITION_SAFE_IMAGE_PREFIX)~!path.startsWith("/")~org.adempiere.web.route.PublicRouteClassifierTest'
'transition-safe-write-method~SRC/route/PublicRouteClassifier.java~&& !"HEAD".equalsIgnoreCase(method))) {~&& !"HEAD".equalsIgnoreCase(method)\n\t\t\t\t\t\t&& !"POST".equalsIgnoreCase(method))) {~org.adempiere.web.route.PublicRouteClassifierTest'
'session-end-cleanup-owner~SRC/route/ModernSessionAffinity.java~boolean cleanupOwner = !endCleanupClaimed;~boolean cleanupOwner = true;~org.adempiere.web.route.RoutingCoreTest'
'session-end-navigation-owner~SRC/route/ModernSessionAffinity.java~navigationEligible && !endNavigationClaimed;~navigationEligible;~org.adempiere.web.route.RoutingCoreTest'
'session-end-au-protocol~SRC/route/RoutingLifecycle.java~candidate = EndResponse.ZK_AU_REDIRECT;~candidate = EndResponse.HTTP_REDIRECT;~org.adempiere.web.route.RoutingCoreTest'
'session-end-committed-response~SRC/route/RoutingLifecycle.java~} else if (!responseCommitted\n\t\t\t\t&& "GET".equalsIgnoreCase(method)~} else if ("GET".equalsIgnoreCase(method)~org.adempiere.web.route.RoutingCoreTest'
)

# The bridge mutation `modern-never-falls-back` is the reason this file records
# the rule above. Its first form replaced the unowned-route refusal with
# `chain.doFilter(...)` inside a method that has no `chain` in scope, so it could
# only ever produce a compiler error - and that compiler error was being scored
# as a detection. Its replacement is a genuine, compilable fallback: it widens
# the "no affinity, serve the legacy application" branch so a FAILED modern
# session is handed to the legacy application too, which is exactly the defect
# the reviewed rule forbids.
bridge_mutations=(
'modern-never-falls-back~BRIDGE/CohortRoutingFilter.java~if (affinity == null) {~if (affinity == null || !affinity.usable()) {~org.adempiere.web.bridge.CohortRoutingFilterTest'
'reserved-header-rejection~BRIDGE/CohortRoutingFilter.java~if (carriesReservedHeader(request)) {~if (false) {~org.adempiere.web.bridge.CohortRoutingFilterTest'
'deployment-completeness~BRIDGE/CohortBridgeStartupListener.java~if (problems.length() > 0) {~if (false) {~org.adempiere.web.bridge.CohortRoutingFilterTest'
'identity-completeness~BRIDGE/LegacyIdentity.java~if (warehouseId == null) {~if (false) {~org.adempiere.web.bridge.CohortRoutingFilterTest'
'decided-modern-fail-closed~BRIDGE/CohortRoutingFilter.java~if (CohortDecisionInterceptor.decidedModern(session)) {~if (false) {~org.adempiere.web.bridge.CohortRoutingFilterTest'
'routed-session-end~BRIDGE/CohortRoutingFilter.java~if (result.sessionEnded()) {~if (false) {~org.adempiere.web.bridge.CohortRoutingFilterTest'
'redirect-barrier-preserved~BRIDGE/CohortRoutingFilter.java~pending.reason()));\n\t\t\t\tchain.doFilter(servletRequest, servletResponse);\n\t\t\t\treturn;~pending.reason()));\n\t\t\t\tchain.doFilter(servletRequest, servletResponse);\n\t\t\t\treleaseRedirectBarrier(request);\n\t\t\t\treturn;~org.adempiere.web.bridge.CohortRoutingFilterTest'
'session-end-au-navigation-wire~BRIDGE/CohortRoutingFilter.java~sendAuRedirect(response, contextRoot(request));~response.sendRedirect(contextRoot(request));~org.adempiere.web.bridge.CohortRoutingFilterTest'
)

reviewed_count=$(( ${#mutations[@]} + ${#bridge_mutations[@]} ))

restore_all() {
  local saved
  for saved in "$work"/*/original; do
    [[ -f "$saved" ]] || continue
    local marker="${saved%/original}/target-path"
    [[ -f "$marker" ]] || continue
    cp "$saved" "$(cat "$marker")"
  done
}
trap restore_all EXIT

echo "# Phase 5e mutation proof" >"$evidence_file"
printf '# mutation\tfile\tgradle_task\tcompiled\toutcome\n' >>"$evidence_file"

# Reads the executed-test count and failures+errors out of a JUnit XML report.
# Printed as `tests failures errors`, or `absent` when the report is not there.
read_report() {
  local report=$1
  python3 - "$report" <<'PY'
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception:
    print('absent')
    sys.exit(0)
print('%s %s %s' % (root.get('tests', '0'), root.get('failures', '0'),
                    root.get('errors', '0')))
PY
}

run_mutation() {
  local id=$1 file=$2 from=$3 to=$4 test_class=$5 task=$6 compile_task=$7 results=$8
  local sandbox="$work/$id"
  rm -rf "$sandbox"
  mkdir -p "$sandbox"

  # The original is saved, the source is mutated in place, and the copy is
  # restored both on the normal path and by the EXIT trap. Building a separate
  # tree would need a second Gradle configuration whose drift from the real one
  # would be the first thing to make this proof meaningless.
  local target="$repo_root/$file"
  if [[ ! -f "$target" ]]; then
    echo "Mutation $id names a file that does not exist: $file" >&2
    return 1
  fi
  cp "$target" "$sandbox/original"
  printf '%s' "$target" >"$sandbox/target-path"

  local occurrences
  occurrences=$(python3 - "$target" "$from" <<'PY'
import sys
path, needle = sys.argv[1], sys.argv[2].replace('\\n', '\n').replace('\\t', '\t')
print(open(path, encoding='utf-8').read().count(needle))
PY
)
  if [[ "$occurrences" != "1" ]]; then
    cp "$sandbox/original" "$target"
    echo "Mutation $id matches $occurrences times in $file; exactly one is required" >&2
    return 1
  fi

  python3 - "$target" "$from" "$to" <<'PY'
import sys
path, needle, replacement = sys.argv[1], sys.argv[2], sys.argv[3]
needle = needle.replace('\\n', '\n').replace('\\t', '\t')
replacement = replacement.replace('\\n', '\n').replace('\\t', '\t')
text = open(path, encoding='utf-8').read()
open(path, 'w', encoding='utf-8').write(text.replace(needle, replacement, 1))
PY

  # A stale report from the unmutated run would otherwise be readable as this
  # mutation's evidence.
  local report="$results/TEST-$test_class.xml"
  rm -f "$report"

  # The compile task is named explicitly ahead of the test task so a mutant that
  # does not build produces an unmistakable `Execution failed for task
  # ':...:compileXJava'` line rather than an ambiguous test failure.
  local status=0
  (
    cd "$repo_root"
    ./gradlew --quiet --console=plain "$compile_task" "$task" \
      --tests "$test_class" >"$sandbox/gradle.log" 2>&1
  ) || status=$?
  cp "$target" "$sandbox/mutated"
  cp "$sandbox/original" "$target"

  if grep -Eq "Execution failed for task '[^']*:compile[A-Za-z]*Java'" \
      "$sandbox/gradle.log"; then
    echo "Mutation $id does not compile, so it proves nothing about $test_class." >&2
    echo "A mutation proof scores behaviour, never compiler errors." >&2
    grep -m 5 'error:' "$sandbox/gradle.log" >&2 || true
    printf '%s\t%s\t%s\tno\tbroken-mutation-did-not-compile\n' \
      "$id" "$file" "$task" >>"$evidence_file"
    return 1
  fi

  if grep -q 'No tests found for given includes' "$sandbox/gradle.log"; then
    echo "Mutation $id ran no test: $test_class matched nothing" >&2
    printf '%s\t%s\t%s\tyes\tnamed-test-did-not-run\n' \
      "$id" "$file" "$task" >>"$evidence_file"
    return 1
  fi

  local report_line
  report_line=$(read_report "$report")
  if [[ "$report_line" == "absent" ]]; then
    # Neither a compile error nor an unmatched filter, so the build fell over
    # for an infrastructure reason. That is not a detection.
    echo "Mutation $id produced no JUnit report for $test_class" >&2
    echo "  (gradle exit status $status; the run never reached the assertion)" >&2
    tail -40 "$sandbox/gradle.log" >&2 || true
    printf '%s\t%s\t%s\tyes\tno-report-run-never-reached-the-assertion\n' \
      "$id" "$file" "$task" >>"$evidence_file"
    return 1
  fi

  local executed failures errors
  read -r executed failures errors <<<"$report_line"
  if [[ "$executed" -lt 1 ]]; then
    echo "Mutation $id executed no test in $test_class" >&2
    printf '%s\t%s\t%s\tyes\tnamed-test-did-not-run\n' \
      "$id" "$file" "$task" >>"$evidence_file"
    return 1
  fi
  if [[ $((failures + errors)) -lt 1 ]]; then
    echo "Mutation $id did NOT fail $test_class; the assertion is vacuous" >&2
    tail -40 "$sandbox/gradle.log" >&2 || true
    printf '%s\t%s\t%s\tyes\tundetected\n' "$id" "$file" "$task" \
      >>"$evidence_file"
    return 1
  fi

  printf '%s\t%s\t%s\tyes\tdetected-by-%s(%s-of-%s-failed)\n' \
    "$id" "$file" "$task" "$test_class" "$((failures + errors))" "$executed" \
    >>"$evidence_file"
  return 0
}

failures=0

for row in "${mutations[@]}"; do
  IFS='~' read -r id file from to test_class <<<"$row"
  file=${file/SRC/$cohort_src}
  run_mutation "$id" "$file" "$from" "$to" "$test_class" \
    ':org.adempiere.cohort:test' ':org.adempiere.cohort:compileJava' \
    "$results_root/test" || failures=$((failures + 1))
done

for row in "${bridge_mutations[@]}"; do
  IFS='~' read -r id file from to test_class <<<"$row"
  file=${file/BRIDGE/$bridge_src}
  run_mutation "$id" "$file" "$from" "$to" "$test_class" \
    ':org.adempiere.cohort:bridgeTest' ':org.adempiere.cohort:compileBridgeJava' \
    "$results_root/bridgeTest" || failures=$((failures + 1))
done

# The sources are restored, but the last mutant's CLASSES and any jar built from
# them are still on disk and would otherwise be packaged by a later task in the
# same invocation. Rebuilding from the restored tree is part of the proof, not
# housekeeping.
(
  cd "$repo_root"
  ./gradlew --quiet --console=plain \
    :org.adempiere.cohort:jar \
    :org.adempiere.cohort:bridgeOverlayJar \
    :org.adempiere.cohort:protocolJar \
    >"$work/rebuild.log" 2>&1
) || {
  echo "The pristine Phase 5e artifacts could not be rebuilt after the proof" >&2
  tail -40 "$work/rebuild.log" >&2 || true
  exit 1
}

if [[ "$failures" != "0" ]]; then
  echo "$failures Phase 5e mutation(s) were not scored as detections" >&2
  exit 1
fi

detected=$(grep -c 'detected-by-' "$evidence_file")
if [[ "$detected" -ne "$reviewed_count" ]]; then
  echo "Only $detected mutations were detected; $reviewed_count are reviewed" >&2
  exit 1
fi
printf 'Phase 5e mutation proof: %s reviewed mutations compiled, ran their named test, and were detected by it\n' \
  "$detected"
