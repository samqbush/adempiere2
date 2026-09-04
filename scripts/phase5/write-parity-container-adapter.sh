#!/usr/bin/env bash
# Phase 5g-1b: the routed lane's container lifecycle adapter.
#
# reset-write-oracle-fixture.sh restores the golden archive with the containers
# down and brings them back up afterwards. In 5g-1a that was one Tomcat 9 and a
# port. Here it is two runtimes -- the public Tomcat 9 ingress that carries the
# cohort router, and the loopback Tomcat 10 that serves the modern ZK
# application -- whose start needs the repository root, the installed
# ADEMPIERE_HOME and the same-user handoff key.
#
# That argument list does not fit `stop-/start-legacy-browser-lane.sh <port>`,
# and the ADR forbids the reseed primitive from growing lane-specific branches.
# So this file closes over the routed lane's arguments and exposes exactly the
# two verbs the reseed primitive calls.
#
# Both verbs are STRICT. `stop` must not swallow a failure: the reseed
# primitive terminates database sessions and drops the database immediately
# afterwards, and a runtime that survived the stop would reconnect into the
# database the next capture is about to measure. `stop-routed-lane.sh` is a
# deterministic shutdown rather than a kill, so the container listeners still
# run; that is what makes the routed lane's own lifecycle evidence meaningful.
set -euo pipefail

verb=${1:?stop or start is required}

repo_root=${PHASE5G1B_REPO_ROOT:?PHASE5G1B_REPO_ROOT is required}
installed_home=${PHASE5G1B_INSTALLED_HOME:?PHASE5G1B_INSTALLED_HOME is required}
handoff_key=${PHASE5G1B_HANDOFF_KEY:?PHASE5G1B_HANDOFF_KEY is required}

# The lane phase stays `phase5e`, deliberately and not by omission. It is not a
# label: start-routed-lane.sh resolves the STAGED Tomcat 10 tree, its pid file
# and the public pid file from it (`build/$lane_phase/tomcat10`), and that tree
# is materialized by the Phase 5e staging tasks this lane depends on. Renaming
# it to `phase5g1b` would point the adapter at a directory nothing creates, and
# the lane would fail to start for a reason unrelated to the parity capture.
#
# Consequently 5g-1b must not run concurrently with the Phase 5e routed lane on
# one machine; they share the staged tree and the pid files. CI runs one
# database-backed lane per job, so that holds by construction.
export ADEMPIERE_ROUTED_LANE_PHASE=phase5e

case "$verb" in
  stop)
    bash "$repo_root/scripts/phase5/stop-routed-lane.sh" \
      "$repo_root" "$installed_home"
    ;;
  start)
    bash "$repo_root/scripts/phase5/start-routed-lane.sh" \
      "$repo_root" "$installed_home" "$handoff_key"
    ;;
  *)
    echo "Unknown verb: $verb (expected stop or start)" >&2
    exit 64
    ;;
esac
