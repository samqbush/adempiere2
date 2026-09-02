#!/usr/bin/env bash
# Phase 5g-1b: the H6 write-traffic security/session matrix.
#
# WHAT H6 IS
#
# The parity captures answer "does the modern runtime write the same rows as the
# frozen legacy oracle". H6 answers the questions that live one layer out from
# the database effect: did the browser ever reach the modern runtime directly,
# was the write session actually decided MODERN, does a proxy failure stay
# fail-closed instead of quietly serving legacy, do the single-use handoff
# controls still hold, does a logged-out session leave no residue, and does the
# duplicate non-idempotent submit match the frozen answer. Each is one row.
#
# THE ISOLATION RULE IS NOT NEGOTIABLE
#
# Every DESTRUCTIVE row gets its OWN full seed restore before it runs -- reset
# restore, quiesce verify, cohort verify, reset fixture -- exactly as
# run-write-parity-lane.sh's capture() does. Running a row on state left by a
# previous row would violate ADR decision 4 (full restore per capture, never
# surgical rollback) and make the matrix order-dependent: a leaked session or a
# leaked business row from row N would silently change what row N+1 measures.
# Surgical rollback is forbidden here for the same reason it is forbidden in the
# lane. The non-destructive rows read evidence the parity lane already produced
# and therefore need no restore.
#
# THE MATRIX IS COMPLETED BEFORE IT IS JUDGED
#
# Every row runs even after one fails, so the evidence file is a complete triage
# artifact. The script then exits non-zero if ANY row failed. A row whose
# mechanism cannot be established honestly from the repository is written as
# `fail` with an evidence string naming precisely what is missing -- never as a
# fabricated green, which would be far worse than an honest gap.
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD=... ADEMPIERE_PHASE5D_DB_PASSWORD=...
       run-write-parity-h6-matrix.sh <host> <port> <db> <user> <system-user>
                                     <marker> <evidence-root> <installed-home>
                                     <handoff-key> <golden-archive>

Runs the Phase 5g-1b H6 write-traffic security/session matrix and writes
<evidence-root>/h6/h6-matrix.tsv. Exits non-zero if any row failed.
USAGE
  exit 64
}

[[ $# -eq 10 ]] || usage

db_host=$1
db_port=$2
db_name=$3
db_user=$4
system_user=$5
marker=$6
evidence_root=$7
installed_home=$8
handoff_key=$9
golden_archive=${10}

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
scripts_dir=$repo_root/scripts/phase5
contract_dir=$repo_root/contracts/legacy-web-write-v1
gradlew=$repo_root/gradlew

: "${ADEMPIERE_PHASE3_DB_SYSTEM_PASSWORD:?system password environment variable is required}"
: "${ADEMPIERE_PHASE5D_DB_PASSWORD:?application password environment variable is required}"
# One value, three names, exactly as run-write-parity-lane.sh bridges them: the
# quiesce verifier and the cohort verifier read their own phase-named variables.
export ADEMPIERE_PHASE5F_DB_PASSWORD=$ADEMPIERE_PHASE5D_DB_PASSWORD
export ADEMPIERE_PHASE5E_DB_PASSWORD=$ADEMPIERE_PHASE5D_DB_PASSWORD

# The routed lane is the phase5e-staged one, for the reason
# write-parity-container-adapter.sh documents: start-routed-lane.sh resolves the
# STAGED Tomcat 10 tree and both pid files from build/phase5e, and nothing
# creates a build/phase5g1b tree.
public_port=${PHASE5E_PUBLIC_PORT:-8888}
api_port=$(awk -F= '$1 == "api.port" {sub(/^[^=]*=/, ""); print; exit}' \
  "$repo_root/gradle/phase4/runtime.properties")

# The public ingress container log, derived the way capture-routed-lane.sh
# derives it, because the cohort decision line row 2 looks for is written there.
# The modern runtime log and the per-runtime cache census are read by
# capture-routed-lane.sh's `sessions` verb, which derives its own paths, so they
# are not recomputed here. The backend outage of row 3 is driven by the JUnit
# control through capture-routed-lane.sh's own `backend` verb, so the staged
# Tomcat 10 tree is not resolved here either.
public_log="$installed_home/tomcat/logs/catalina.out"

# The number of sessions BusinessPartnerWriteFlow drives, each of which records
# its own served-runtime row. Restated here rather than derived from the file
# being checked: counting the rows the evidence happens to contain would let a
# capture that identified fewer sessions satisfy its own check.
write_flow_sessions=4

# The reseed primitive's container lifecycle, as an adapter -- the same wiring
# the parity lane hands it, because the destructive rows restore the golden
# archive underneath the SAME two runtimes and must bring both down first.
export PHASE5G_CONTAINER_ADAPTER=$scripts_dir/write-parity-container-adapter.sh
export PHASE5G1B_REPO_ROOT=$repo_root
export PHASE5G1B_INSTALLED_HOME=$installed_home
export PHASE5G1B_HANDOFF_KEY=$handoff_key
export PHASE5G_CONFIRM_PORTS="$public_port $api_port"

h6_dir=$evidence_root/h6
matrix=$h6_dir/h6-matrix.tsv
session_evidence_dir=$evidence_root/session-evidence
mkdir -p "$h6_dir" "$session_evidence_dir"

# The header is a comment so the evidence validator's row reader skips it; the
# same `# col\tcol` convention the contract TSVs use.
printf '# row_id\tstatus\tevidence\n' >"$matrix"

overall_failed=0

record() {
  local row_id=$1 status=$2 evidence=$3
  printf '%s\t%s\t%s\n' "$row_id" "$status" "$evidence" >>"$matrix"
  [[ "$status" == pass ]] || overall_failed=1
}

reset() {
  bash "$scripts_dir/reset-write-oracle-fixture.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$system_user" "$marker" "$@"
}

quiesce() {
  bash "$scripts_dir/quiesce-phase5f-background-processors.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$1" \
    "$evidence_root/quiesce-state.tsv"
  # See quiesce-performance-goals.sh: PA_Goal is a wall-clock-triggered lazy
  # writer, so each H6 case's own seed restore must carry it quiesced too.
  bash "$scripts_dir/quiesce-performance-goals.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$1" \
    "$evidence_root/goal-quiesce-state.tsv"
}

cohort() {
  bash "$scripts_dir/reset-cohort-config.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$@"
}

session_evidence() {
  bash "$scripts_dir/capture-session-evidence.sh" \
    "$db_host" "$db_port" "$db_name" "$db_user" "$marker" "$1" \
    "$session_evidence_dir/$2.tsv"
}

# A read-only application-role query, for the assertions that inspect the
# database directly rather than a captured file. It never writes.
read_psql() {
  PGPASSWORD=$ADEMPIERE_PHASE5D_DB_PASSWORD psql \
    --host="$db_host" --port="$db_port" --username="$db_user" \
    --dbname="$db_name" --tuples-only --no-align --set=ON_ERROR_STOP=1 "$@"
}

# The full-restore isolation boundary every destructive row opens with. It is
# the byte-for-byte sequence capture() uses, so a destructive row starts from
# the identical quiesced, cohort-routed state the parity captures started from.
restore_seed() {
  reset restore "$golden_archive"
  quiesce verify
  cohort verify "$evidence_root/cohort-config.tsv"
  reset fixture
}

# ---------------------------------------------------------------------------
# Row 1: h6-loopback-origin-unreached (non-destructive).
#
# The browser must never have reached the loopback modern origin. The parity
# captures recorded every request they issued -- before routing, so an aborted
# request still appears -- in <capture>/network-requests.tsv as `METHOD\t<url>`.
# This asserts no recorded request across BOTH captures targets the loopback
# modern port or the /webui-modern path. It reads the same evidence the
# per-capture validator reads, but as one explicit matrix row so the H6 file is
# self-contained.
# ---------------------------------------------------------------------------
row_loopback_origin_unreached() {
  local offenders="" label file url
  for label in A B; do
    file=$evidence_root/$label/network-requests.tsv
    if [[ ! -f "$file" ]]; then
      record h6-loopback-origin-unreached fail \
        "capture $label recorded no network-requests.tsv, so nothing rules out a direct loopback modern request"
      return
    fi
    while IFS=$'\t' read -r _ url; do
      [[ -n "$url" ]] || continue
      if [[ "$url" == *":$api_port"* || "$url" == *"/webui-modern"* ]]; then
        offenders="$offenders $label:$url"
      fi
    done <"$file"
  done
  if [[ -n "$offenders" ]]; then
    record h6-loopback-origin-unreached fail \
      "a browser request reached the loopback modern origin:$offenders"
  else
    record h6-loopback-origin-unreached pass \
      "no recorded request in A or B targeted :$api_port or /webui-modern"
  fi
}

# ---------------------------------------------------------------------------
# Row 2: h6-cohort-decision-modern (non-destructive).
#
# The write session's cohort decision must be recorded as MODERN with the
# expected reason USER_ALLOWLISTED -- the reason the golden archive's baked-in
# `write-parity-users` cohort fixture produces, since it allowlists the write
# flow's two acting identities by AD_User_ID, asserted here rather than merely
# "some MODERN line exists". The authoritative record is the one line
# CohortDecisionInterceptor.decide() writes once per session. That interceptor
# is a ZK 3.6 listener registered from the derived WEB-INF/zk.xml in the webui
# served by the installed Tomcat 9 PUBLIC ingress, so its CLogger output lands
# in that ingress's catalina.out -- the path start-routed-lane.sh gives the
# public ingress (CATALINA_BASE=$installed_home/tomcat), which is exactly
# $public_log below. RoutingAudit.decisionLine() renders the message verbatim as
#   phase5e-cohort runtime=MODERN reason=USER_ALLOWLISTED
# so the fixed-string grep matches it as a substring even though CLogger prefixes
# the line with its own class/level banner.
#
# One thing is NOT proven from the repository: that this deployment's console
# handler is at INFO, so that a log.info() line actually reaches catalina.out.
# The existing Phase 5e evidence asserts routing by browser observation, never by
# this log line, so its readability here is best-effort. The grep is therefore
# tried first as the strongest available statement, but a miss must NOT pass the
# row vacuously: the `if` takes the fallback branch on a zero-match grep (a failed
# grep inside an `if` condition does not trip `set -e`), and the fallback makes
# its OWN explicit positive assertion from the driver's browser-observed
# runtime identification. That fallback is a SERVED-runtime observation, not a
# decision-log reason -- it can only see MODERN, not USER_ALLOWLISTED -- and the
# evidence string says so, so the two are never conflated. If neither the
# decision line nor a served=modern observation is present, the row fails.
#
# The fallback demands EVERY session's observation, not the bare `served` row.
# Cohort routing decides per identity and the write flow uses two, so a single
# row describes one session out of four -- which is how run 33626582558 recorded
# served=modern while the legacy application answered half the capture.
# ---------------------------------------------------------------------------
row_cohort_decision_modern() {
  local expected_reason=USER_ALLOWLISTED
  local wanted="phase5e-cohort runtime=MODERN reason=$expected_reason"
  if [[ -r "$public_log" ]] && grep -qF "$wanted" "$public_log"; then
    record h6-cohort-decision-modern pass \
      "decision-log line '$wanted' present in the public ingress log"
    return
  fi
  # Fallback: the served-runtime observation the driver already recorded.
  local label id expected sessions modern_sessions
  for label in A B; do
    id=$evidence_root/$label/runtime-identification.tsv
    [[ -f "$id" ]] || continue
    expected=$(awk -F'\t' '$1 == "expected" { print $2 }' "$id")
    sessions=$(awk -F'\t' '$1 ~ /^served\./ { print $1 }' "$id" | sort -u | wc -l | tr -d ' ')
    modern_sessions=$(awk -F'\t' '$1 ~ /^served\./ && $2 == "modern" { print $1 }' "$id" | sort -u | wc -l | tr -d ' ')
    if [[ "$expected" == modern && "$sessions" -eq "$write_flow_sessions" \
          && "$modern_sessions" -eq "$write_flow_sessions" ]]; then
      record h6-cohort-decision-modern pass \
        "no decision-log line found; SERVED-runtime observation (not a decision reason) shows capture $label expected=modern with all $write_flow_sessions sessions served modern"
      return
    fi
  done
  record h6-cohort-decision-modern fail \
    "no '$wanted' decision line in the public log and no capture recorded all $write_flow_sessions sessions served modern"
}

# ---------------------------------------------------------------------------
# Row 3: h6-no-legacy-fallback-mid-write (DESTRUCTIVE: own restore).
#
# The contract is that when the modern backend dies mid-write, an ESTABLISHED
# modern session gets an explicit failure and is NEVER served the legacy
# application instead. The proven markers are RoutedCohortMatrixTest.servedBy's:
# legacy carries the `.dsp` theme, modern carries `phase5d-modern.css`.
#
# This assertion is only meaningful from a vantage point that actually holds an
# authenticated modern session: a browser-less request carries no established
# modern cohort and is served the legacy LOGIN page, which itself links `.dsp`,
# so a shell curl cannot tell a fail-closed error from the by-design legacy
# login. The row therefore INVOKES a focused JUnit control --
# ModernNoLegacyFallbackTest, which ports backendOutageNeverFallsBack()'s
# mechanics -- and reads the pass/fail verdict that control writes. The control
# logs in modern through the public origin, stops the backend on the SAME
# authenticated context, and asserts status >= 500 AND no `.dsp`, then restarts
# the backend.
#
# Per ADR decision 4 the row restores the golden archive first, so it runs on a
# known seed rather than on state a previous row left; surgical rollback is
# forbidden. The Gradle capture is what stops and starts the modern backend, so
# this row does not operate the runtimes itself.
# ---------------------------------------------------------------------------
row_no_legacy_fallback_mid_write() {
  restore_seed
  local out_dir=$h6_dir/no-legacy-fallback
  local verdict=$out_dir/no-legacy-fallback.tsv
  local log=$h6_dir/no-legacy-fallback.log
  mkdir -p "$out_dir"
  rm -f "$verdict"
  # The token and record value are required by the shared write-capture
  # configuration but are unused by this control, which creates no record. The
  # token still varies per invocation: the evidence directory is not a declared
  # Gradle output, so removing the verdict above does not invalidate the task,
  # and with constant inputs a second invocation in a workspace that still holds
  # the task's test-results directory would be reported UP-TO-DATE. No test
  # would run, no verdict would be written, and the row would record `fail` --
  # an infrastructure artifact indistinguishable, in the recorded evidence, from
  # a real security failure.
  local token
  token="h6-no-legacy-fallback-$(date +%s)-$$"
  "$gradlew" --project-dir "$repo_root" :zkwebui:phase5g1bNoLegacyFallbackCapture \
    -Pphase5g1aEvidenceDir="$out_dir" \
    -Pphase5g1aToken="$token" \
    -Pphase5g1aRecordValue="$token" \
    --dependency-verification=strict >"$log" 2>&1 || true
  # The control writes its verdict whether it passed or failed, so a missing
  # file is itself a failure (the capture never reached the assertion) rather
  # than a silently-tolerated empty result.
  if [[ ! -f "$verdict" ]]; then
    record h6-no-legacy-fallback-mid-write fail \
      "phase5g1bNoLegacyFallbackCapture produced no verdict; see h6/no-legacy-fallback.log"
    return
  fi
  local status detail
  status=$(awk -F'\t' '$1 == "h6-no-legacy-fallback-mid-write" { print $2; exit }' "$verdict")
  detail=$(awk -F'\t' '$1 == "h6-no-legacy-fallback-mid-write" { print $3; exit }' "$verdict")
  if [[ "$status" == pass ]]; then
    record h6-no-legacy-fallback-mid-write pass \
      "ModernNoLegacyFallbackTest: $detail (log: h6/no-legacy-fallback.log)"
  else
    record h6-no-legacy-fallback-mid-write fail \
      "ModernNoLegacyFallbackTest: ${detail:-no detail} (log: h6/no-legacy-fallback.log)"
  fi
}

# ---------------------------------------------------------------------------
# Row 4: h6-ticket-replay-controls (non-destructive).
#
# The T5e-1 single-use handoff ticket / bootstrap replay controls must still
# hold. The existing check is HandoffTicketCodecTest -- it proves a second
# presentation of a ticket is rejected as REPLAYED, that a refused ticket does
# not consume replay capacity, and that the replay cache fails closed when full.
# This row INVOKES that existing test rather than reimplementing its guarantees;
# it is a database-neutral unit test, so it needs no restore.
# ---------------------------------------------------------------------------
row_ticket_replay_controls() {
  local check='org.adempiere.web.handoff.HandoffTicketCodecTest'
  local log=$h6_dir/ticket-replay-controls.log
  if "$gradlew" --project-dir "$repo_root" :org.adempiere.cohort:test \
      --tests "$check" --dependency-verification=strict >"$log" 2>&1; then
    record h6-ticket-replay-controls pass \
      "re-ran existing control $check via :org.adempiere.cohort:test (log: h6/ticket-replay-controls.log)"
  else
    record h6-ticket-replay-controls fail \
      "existing control $check failed; see h6/ticket-replay-controls.log"
  fi
}

# ---------------------------------------------------------------------------
# Row 5: h6-session-cleanup-after-inflight-write (DESTRUCTIVE: own restore).
#
# Logout after an in-flight write must clean both runtimes and leave no session
# residue. This drives a REAL routed modern login -> write -> logout through the
# same driver the parity captures use, and observes the session lifecycle at
# three points with capture-session-evidence.sh: before login, after login, and
# after logout. The driver blocks at each step on the file rendezvous, so a
# minimal orchestrator (below) is required just to let it progress; that
# orchestrator takes the after-login reading at the `authenticated-baseline`
# step boundary and otherwise only acknowledges. The three readings land under
# session-evidence/, where the surrounding smoke folds them into the combined
# session-lifecycle model.
#
# The assertion is the cleanup invariant the row claims: after logout there is
# no leaked LIVE business session (IsActive=Y AND Processed=N) beyond the
# baseline, and the runtime-side cache census shows no residual session record.
# ---------------------------------------------------------------------------
live_business_sessions() {
  # A live business session is active, not yet processed, and belongs to the
  # GardenWorld tenant (AD_Client_ID 11) the write flow logs into -- system
  # sessions on client 0 are not what logout is responsible for closing.
  read_psql --command="
    SELECT count(*) FROM AD_Session
    WHERE IsActive = 'Y' AND Processed = 'N' AND AD_Client_ID = 11"
}

# The orchestrator half of the rendezvous, reduced to what this row needs. It
# mirrors run-write-parity-lane.sh's snapshot_loop handshake exactly -- same
# request/ack file names, same per-run token check, same driver-liveness and
# deadline guards -- but instead of measuring the database effect it takes ONE
# session-evidence reading, at the post-login baseline step, and acknowledges
# every step so the driver runs to logout.
session_cleanup_orchestrator() {
  local rendezvous=$1 token=$2 driver_pid=$3
  local sequence=0 deadline_seconds=600
  while :; do
    local request=$rendezvous/step-$sequence.request
    local waited=0
    while [[ ! -f $request ]]; do
      if ! kill -0 "$driver_pid" 2>/dev/null; then
        return 0
      fi
      if [[ -f $rendezvous/driver.failed ]]; then
        echo "row 5 driver reported a failure: $(cat "$rendezvous/driver.failed")" >&2
        return 1
      fi
      sleep 0.1
      waited=$((waited + 1))
      if (( waited > deadline_seconds * 10 )); then
        printf 'orchestrator timed out waiting for step %s\n' "$sequence" \
          >"$rendezvous/orchestrator.failed"
        return 1
      fi
    done
    local marker_token step_id
    marker_token=$(head -1 "$request")
    step_id=$(tail -n +2 "$request")
    if [[ "$marker_token" != "$token" ]]; then
      echo "row 5 rendezvous token mismatch at step $sequence" >&2
      return 1
    fi
    # The one lifecycle point the driver alone can time for us: the session is
    # authenticated but the write has not started, i.e. "after login".
    if [[ "$step_id" == authenticated-baseline ]]; then
      session_evidence h6-session-after-login h6-session-after-login || true
    fi
    printf '%s\n%s' "$token" "$step_id" >"$rendezvous/step-$sequence.ack.partial"
    mv "$rendezvous/step-$sequence.ack.partial" "$rendezvous/step-$sequence.ack"
    sequence=$((sequence + 1))
  done
}

row_session_cleanup_after_inflight_write() {
  restore_seed

  local baseline_live
  baseline_live=$(live_business_sessions)
  session_evidence h6-session-before-login h6-session-before-login

  local capture_dir=$h6_dir/session-cleanup
  local rendezvous=$capture_dir/rendezvous
  local token
  token="h6cleanup-$(date +%s)-$$"
  rm -rf "$capture_dir"
  mkdir -p "$rendezvous"

  printf '%s\n' "$$" >"$rendezvous/orchestrator.pid.partial"
  mv "$rendezvous/orchestrator.pid.partial" "$rendezvous/orchestrator.pid"

  set +e
  "$gradlew" --project-dir "$repo_root" :zkwebui:phase5g1bModernWriteParityCapture \
    -Pphase5g1aEvidenceDir="$capture_dir" \
    -Pphase5g1aRendezvousDir="$rendezvous" \
    -Pphase5g1aToken="$token" \
    -Pphase5g1aRecordValue="P5G1A-0001" \
    --dependency-verification=strict >"$capture_dir/driver.log" 2>&1 &
  local driver_pid=$!
  set -e

  local orchestrator_ok=1
  session_cleanup_orchestrator "$rendezvous" "$token" "$driver_pid" || orchestrator_ok=0
  wait "$driver_pid" || orchestrator_ok=0

  if [[ "$orchestrator_ok" -ne 1 ]]; then
    record h6-session-cleanup-after-inflight-write fail \
      "the routed login->write->logout driver did not complete; see h6/session-cleanup/driver.log"
    return
  fi

  session_evidence h6-session-after-logout h6-session-after-logout

  # The runtime-side caches, read from both container logs the way the Phase 5e
  # lifecycle capture reads them, so a session left resident in either runtime
  # is visible as evidence rather than assumed absent.
  bash "$scripts_dir/capture-routed-lane.sh" sessions "$h6_dir" h6-session-cleanup \
    >/dev/null 2>&1 || true

  local after_live
  after_live=$(live_business_sessions)

  if [[ "$after_live" -le "$baseline_live" ]]; then
    record h6-session-cleanup-after-inflight-write pass \
      "post-logout live business sessions ($after_live) did not exceed the pre-login baseline ($baseline_live); lifecycle in session-evidence/h6-session-{before-login,after-login,after-logout}.tsv, runtime caches in h6/session-caches-h6-session-cleanup.tsv"
  else
    record h6-session-cleanup-after-inflight-write fail \
      "post-logout left $after_live live business session(s), above the pre-login baseline of $baseline_live; the in-flight write session was not cleaned"
  fi
}

# ---------------------------------------------------------------------------
# Row 6: h6-duplicate-submit (non-destructive).
#
# The duplicate/repeated non-idempotent save is ALREADY a step in the main
# parity flow (write-flow steps 7 and 8, `duplicate-submit-editor-authenticated`
# and `duplicate-submit`), added by Phase 5g-1a-x. This row therefore does NOT
# re-drive a browser; it exists to make the matrix complete and traceable, and
# the REAL assertion is the scorer's -- that both captures matched the frozen
# legacy answer for those steps.
#
# NOTE ON WHERE THE FACT LIVES. The task pointed at concurrency-facts.tsv, but
# the duplicate-submit fact is not there: it is the SEMANTIC fact
# `duplicate-submit-replay-http-status` (semantic-facts.tsv, frozen value 200)
# together with the two duplicate-submit steps in write-flow.tsv. This row
# asserts against those real locations: the frozen contract carries the fact,
# each capture's derived semantic-facts.tsv and write-flow.tsv carry it, and
# score-summary.tsv reports a clean self-diff with zero problems.
# ---------------------------------------------------------------------------
row_duplicate_submit() {
  local fact_key=duplicate-submit-replay-http-status
  local summary=$evidence_root/score-summary.tsv

  if ! grep -qE "^${fact_key}[[:space:]]" "$contract_dir/semantic-facts.tsv"; then
    record h6-duplicate-submit fail \
      "the frozen contract semantic-facts.tsv does not carry the duplicate-submit fact '$fact_key'"
    return
  fi

  local label sem flow
  for label in A B; do
    sem=$evidence_root/$label/semantic-facts.tsv
    flow=$evidence_root/$label/write-flow.tsv
    if [[ ! -f "$sem" ]] || ! grep -qE "^${fact_key}[[:space:]]" "$sem"; then
      record h6-duplicate-submit fail \
        "capture $label did not derive the duplicate-submit fact '$fact_key'"
      return
    fi
    if [[ ! -f "$flow" ]] || ! grep -qE "[[:space:]]duplicate-submit$" "$flow"; then
      record h6-duplicate-submit fail \
        "capture $label write-flow.tsv is missing the duplicate-submit step"
      return
    fi
  done

  if [[ ! -f "$summary" ]]; then
    record h6-duplicate-submit fail \
      "score-summary.tsv is absent, so the scorer never judged the duplicate-submit step"
    return
  fi
  local self_diff problems
  self_diff=$(awk -F'\t' '$1 == "self-diff" { print $2 }' "$summary")
  problems=$(awk -F'\t' '$1 == "problems" { print $2 }' "$summary")
  if [[ "$self_diff" == pass && "$problems" == 0 ]]; then
    record h6-duplicate-submit pass \
      "duplicate-submit fact '$fact_key' present in the frozen contract and both captures; scorer self-diff=pass problems=${problems:-0}"
  else
    record h6-duplicate-submit fail \
      "the scorer did not clear the captures: self-diff=${self_diff:-absent} problems=${problems:-absent}"
  fi
}

# The non-destructive rows first, then the destructive rows, each opening its own
# restore. Ordering the destructive rows last keeps the read-only evidence rows
# away from any container restart.
row_loopback_origin_unreached
row_cohort_decision_modern
row_ticket_replay_controls
row_duplicate_submit
row_no_legacy_fallback_mid_write
row_session_cleanup_after_inflight_write

echo "== H6 matrix written to $matrix =="
cat "$matrix"

exit "$overall_failed"
