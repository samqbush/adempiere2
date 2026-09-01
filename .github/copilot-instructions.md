# Copilot Instructions - ADempiere Modernization

> This file is the **durable execution guide** for `MODERNIZATION_PLAN.md`. It
> records conventions, rules, and structural facts that outlive any one phase.
> It deliberately does **not** track phase status, gate identity, evidence, run
> ids, or the per-phase gate command list: those change every increment and
> belong in `MODERNIZATION_PLAN.md`, `ARCHITECTURE.md`, the README, and
> `docs/modernization/`. Update this file only when a *durable* convention,
> command, or structural fact changes.

ADempiere is being modernized as a modular monolith. Safety is adaptive: each
component has a named Testability Milestone and safety rung (L0-L4) in
`MODERNIZATION_PLAN.md`. Never require an automated test gate before that
component is testable. The plan-wide reproducible-CI Milestone is Phase 1, when
the existing Ant unit-test baseline is recorded and Gradle must become
reproducible and execute meaningful tests instead of `NO-SOURCE`.

## Current programme state: read the plan, not this file

`MODERNIZATION_PLAN.md` is authoritative for which phase is active, what has
merged, which gate is the head of the phase-gate chain, which residual risks and
transitional states are open, and what each increment claims.
`ARCHITECTURE.md` is authoritative for module topology and the per-gate
command/ownership table. `docs/modernization/` holds the binding ADRs, the CI
topology, and the captured evidence. Never restate that state here.

Two durable rules survive from the Phase 5g governance because they are
governance, not status, and they generalize to every oracle-and-parity pair:

1. **Oracle before modern.** The expected answer is captured from the legacy
   runtime and domain-reviewed **before** a modern runtime is scored against it.
2. **No single PR may both invent the expected answer and implement the thing
   being scored.** Oracle increments ship no modern runtime code; parity
   increments add no new oracle facts.

`contract-only-runtime-observation-pending` is an honest disposition only for
something outside an increment's own claim. It is never a valid disposition for
an acceptance criterion that defines the increment's claim.

Two durable ZK-target rules: the accepted target is ZK CE `10.3.0.1-jakarta`
from the public ZK repository, and no evaluation artifacts or commercial
repository credentials may be introduced. Freeze an installed legacy web product
before migrating its source; rollback uses checksum-pinned artifacts, not a dual
framework source tree.

## Commands

These commands are stable across phases. Per-phase gates are **not** listed
here, because they change every increment: discover them with
`./gradlew tasks --group verification`, and read `MODERNIZATION_PLAN.md`,
`ARCHITECTURE.md`, and `docs/modernization/ci-topology.md` for which gate is
current, what it proves, and how to invoke it.

| Action | Command | Qualification |
|---|---|---|
| Full product build, no DB restore | `ant build -Dnodbrestore=true` | Authoritative underlying Ant reactor; prefer the guarded Phase 3 lifecycle for CI |
| Full product build with DB restore/migrations | `ant build -Dnodbrestore=false` | Database-affecting underlying reactor; run only against an approved disposable environment |
| Gradle module build | `./gradlew build --dependency-verification=strict` | Reproducible gate on JDK 21 with Java 21 bytecode; omits quarantined Ant-only deployables |
| Base unit tests | `ant -f tools/build.xml && ant -f base/build.xml unit-tests` | `tools` must run first to create `lib/CCTools.jar` and related outputs; the complete Ant reactor already preserves this order |
| Base integration tests | `ant -f base/build.xml integration-tests -Dtest.performIntegrationTests=true` | Requires configured application/database environment |
| Base Gradle test task | `./gradlew :base:test --dependency-verification=strict` | Executes the fail-closed `UnitTest` classification and emits JUnit XML |
| Single Gradle test | `./gradlew :base:test --tests '<fully.qualified.Test>'` | `org.adempiere.test` remains a published test-support artifact |
| Desktop client | `./utils/RUN_Adempiere.sh` | Requires installed product and database configuration |
| Application server | `./utils/RUN_Server2.sh` | Requires installed product and selected Tomcat/WildFly/Jetty |
| Restore seed | `./utils/RUN_ImportAdempiere.sh` | Destructive/database-affecting; inspect environment first |
| Apply XML migrations | `./utils/RUN_MigrateXML.sh` | Database-affecting |
| Lint | None | Do not invent a lint gate; add and verify one in a planned phase |
| Format | Eclipse formatter config exists, but no canonical CLI gate | Do not claim formatting is enforced |
| Typecheck | Java compilation through the applicable Ant/Gradle build | No separate typecheck command |
| End-to-end/contract | Per-phase only | No phase-neutral end-to-end gate is canonical |

A per-phase gate command becomes canonical only after that phase's exit criteria
prove it. Record it in `ARCHITECTURE.md` and `MODERNIZATION_PLAN.md`, not here.

## Build topology invariants

Existing CI lives under `.github/workflows/`. `settings.gradle`,
`gradle/phase3/topology.tsv`, and `ARCHITECTURE.md` are authoritative for
reactor and Gradle project membership; do not restate the counts here.

- `gradle build` is not the full product build until Phase 7 proves parity.
- The `jbossfacet` surface is explicitly quarantined because its checked-in
  JBoss API depends on `java.security.acl.Group`, which JDK 21 removed.
- Metadata validation is fail-closed but carries named pre-existing active
  process-binding residuals in `gradle/phase3/metadata-quarantine.tsv`. Do not
  describe them as clean metadata; additions and stale quarantine entries must
  fail, and Phase 7 owns the usage/retirement decision.
- A Gradle-only project with no Ant reactor entry is legitimate when its source
  set compiles against materialized frozen artifacts rather than reactor output.
  Record the rationale in `ARCHITECTURE.md` when you add one.

## CI enforcement and topology

**CI enforcement is manual.** An agent can author workflows, but a GitHub
administrator must enable branch protection and required status checks for
`develop`. Until that happens, CI can run without blocking merges. The two
checks to mark required are `Contracts` and `Current-phase database smoke`.

`.github/workflows/main.yml` runs two lanes, documented in
`docs/modernization/ci-topology.md`, which is authoritative for the current task
arguments and the coverage proof:

- **`Contracts`** - every pull request. One Gradle invocation naming the head of
  the phase-gate chain **plus any database-neutral gate that is deliberately off
  that chain**. Some gates are intentionally unchained - a later gate may
  supersede an earlier gate's assertions while the earlier gate still
  contributes unique coverage - so the argument list is not just the chain head,
  and it is the coverage proof in `ci-topology.md` that establishes what the
  list must be. Dropping an off-chain gate from the arguments silently drops its
  coverage.
- **`Current-phase database smoke`** - the runtime gate for the phase under
  active development. The remaining database-backed smokes run post-merge on
  `push` to `develop`, nightly, and on demand as `Regression matrix`.

Do not re-add a per-phase CI job for a database-neutral gate. Those gates are
already chained inside Gradle, so a separate job only re-executes work the
`Contracts` job performs. When a phase lands: change the **task arguments** of
the two phase-neutral jobs - re-deriving the full `Contracts` argument list, not
only its head - retire the outgoing current-phase smoke into the regression
matrix, and re-run the coverage proof in the topology document. Never rename the
jobs: branch protection references them by name.

Lane 2 is non-blocking in GitHub but is governed by a stop-the-line containment
rule; see the residual-risk register in `MODERNIZATION_PLAN.md`.

## Where to run gates: prefer GitHub Actions over local execution

**Do not run heavy builds, Ant reactor builds, full Gradle gates, container
lanes, browser lanes, or any database-backed smoke on the developer machine.**
Push the branch and let GitHub Actions execute them. The Ant reactor build,
`phase3InstalledProduct`, every `*FinalVerification` gate, and every `*Smoke`
task are CI activities, not local ones. Phase 5f initial setup alone is roughly
22 minutes and its full gate took 1h13m54s; a local run is slower still and can
exhaust the machine.

Local execution is for **fast, targeted, single-purpose checks** that finish in
seconds. The table below is a set of *worked examples* of that shape, not a
canonical gate list - the point is the pattern, and the examples may age out
with their phase:

| Check | Local command | Cost |
|---|---|---|
| Phase 5f database-neutral contract | `python3 scripts/phase5/generate-phase5f-oracle-contracts.py --repo-root . --output-dir /tmp/p5fgen` then `python3 scripts/phase5/validate-phase5f-oracle-contracts.py --repo-root . --contract-dir contracts/phase5f-jakarta-web-v1 --generated-dir /tmp/p5fgen --summary /tmp/p5fsum.tsv` | ~1s |
| Phase 5g-0 inventories | `python3 scripts/phase5/generate-phase5g-inventories.py --repo-root . --output-dir /tmp/p5g` then `python3 scripts/phase5/validate-phase5g-inventories.py --repo-root .` | ~2s |
| Phase 5f runtime evidence | Download a CI evidence artifact, rewrite each `provenance.json` `git_head` to local `HEAD`, then run `scripts/phase5/validate-phase5f-runtime-evidence.py` | seconds |
| `org.adempiere.cohort` unit tests | `javac` the main and test source sets, then run `junit-platform-console-standalone` from the Gradle cache | ~1s |

The pattern generalizes: a gate implemented as a Python generator plus a
validator, or as plain JUnit over a small source set, can and should be
reproduced locally by invoking that script or compiler directly rather than
through its Gradle task. Prefer writing new gates that way, so they stay cheap
to reproduce.

Use `gh run watch`, `gh run view --log-failed`, and `gh run download` to drive
and diagnose CI. Diagnose a failure from the run's **own uploaded evidence**
before attempting any local reproduction; Phase 5f established that every
failure mode was diagnosable that way.

When a gate can only run in CI, say so plainly and report it as executed in CI
with its run id and commit, in the form `MODERNIZATION_PLAN.md` uses to record
executed gates. Never present an unrun gate as green.

## Phase gating

A phase is not complete until its Verification & Exit Criteria in
`MODERNIZATION_PLAN.md` are:

1. objectively verifiable;
2. executed and recorded;
3. gated before the next phase begins.

Use the component's regime:

- **Post-testability ("lit"):** green CI on the phase PR is authoritative. Run
  every build/test/contract/smoke gate relevant to the touched surface.
- **Pre-testability ("dark"):** do not demand a test suite that cannot run.
  Require the assigned safety rung instead: captured seam/oracle evidence,
  reversible changes, smoke checklist, and recorded review.

A skipped, `NO-SOURCE`, quarantined, or non-enforced gate must be reported
exactly as such. Never present it as green coverage. A downgrade to a lower rung
is allowed only when the residual risk, owner, and closing phase are recorded.

## Architecture and compatibility rules

- Preserve the modular monolith and application dictionary unless the plan has
  an approved ADR changing them.
- Treat schema, XML migrations, generated models, metadata, role access,
  processes, validators, workflows, and terminology as one deployable change.
- Preserve named transaction propagation and client/org/role/user context.
- Net external seams: SOAP/HTTP contracts, route/auth classes, DB schema and
  migration state, document transitions, accounting facts, process outputs,
  scheduler outcomes, installer manifests, and semantic UI flows.
- `gradle build` is not the full product build until Phase 7 proves parity.
- Do not update a Gradle coordinate without reconciling the shipped Ant/runtime
  JAR.
- Preserve published Maven coordinates/POMs and release artifact names unless a
  phase explicitly versions and communicates a compatibility break.
- Gradle runs on JDK 21 and publishes Java 21 bytecode; assert class-file major
  65 in CI. Never overwrite an existing Maven version with the higher bytecode
  level.
- Do not use `mavenLocal()` in the reproducible CI resolution path.
- Do not remove an old UI/API/application only because it is old. Require usage
  and consumer evidence.
- When a runtime major changes, move CI, installer, launch scripts, app-server
  config, and deployment pins in the same phase.
- Never blanket-rewrite `javax.*`: Java SE packages such as `javax.swing`,
  `javax.print`, `javax.sql`, and `javax.naming` are not Jakarta namespaces.
- A database major upgrade requires a data migration and rollback rehearsal,
  not an image/tag edit.

## Decisions and hazard review

- Resolve implementation sub-decisions in the phase's "Decisions made" block.
- State `dropped` versus `deferred` explicitly.
- Before implementation, red-team the phase against H1-H8:
  complete removal set, mechanical codemods, runtime/deployment lockstep,
  route-class enumeration, stateful-store migration, transitional insecure
  states, stacked-PR/trunk drift, and living-doc drift.
- Record hazards that were checked and cleared as well as those that fired.
- Update the plan's component rung, Testability Milestone, and residual-risk
  register when evidence changes.

## Branching and pull requests

The trunk is `develop`; local `origin/HEAD` points to `origin/develop`.
Treat `master` as history/release compatibility unless repository administrators
explicitly change this rule.

Each phase uses its own `phase-N-<short-name>` branch cut from `develop`. Merge
the phase PR to `develop` before starting the next phase. Never base a phase
branch on a sibling phase branch. At branch creation:

```bash
git fetch origin
git switch develop
git pull --ff-only
git switch -c phase-N-short-name
git log origin/develop..HEAD
```

The final command must be empty at branch creation. If controlled stacking is
unavoidable, require a reconciliation PR to trunk and record the residual risk.

Lit phase PRs merge only after green CI. Dark phase PRs carry the required
oracle/seam snapshots, smoke evidence, reversibility proof, and residual-risk
record. Do not implement multiple roadmap phases in one PR.

## Living documentation and security transitions

Any change to modules, reactor membership, commands, branches, runtimes,
deployables, routes, endpoints, or supported databases updates
`MODERNIZATION_PLAN.md`, `ARCHITECTURE.md`, README, and the relevant topology
lists in the same PR.

**This file is not part of that routine sweep.** Phase status, gate identity,
evidence, run ids, and per-phase commands live in those documents, not here.
Update this file only when a *durable* convention, rule, or structural
invariant changes - and when you do, delete the superseded rule rather than
appending beside it.

No insecure transitional state is approved by default. A temporary permit-all,
CSRF disable, open endpoint, placeholder secret, scanner bypass, or disabled
check must be narrowly scoped and registered in the plan with its reason, owner,
residual risk, and mandatory closing phase.
