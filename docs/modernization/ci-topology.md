# CI topology

How the modernization gates are scheduled, why they are grouped this way, and
what that grouping deliberately gives up. This implements
`MODERNIZATION_PLAN.md` item 3.7.

## Lanes

| Lane | Trigger | Blocking | Jobs |
|---|---|---|---|
| 1 | `pull_request` → `develop`/`master` | Yes | `Contracts`, `Current-phase database smoke` |
| 2 | `push` → `develop`/`master`, nightly `schedule`, `workflow_dispatch` | No | `Regression matrix` (7 database-backed gates) |
| Candidate capture | Push adding/changing `phase5g1ay-oracle-candidate.yml` on `phase-5g-1a-y-workflow-attribution-oracle` | No; creates no required-check names | `Oracle candidate capture (non-acceptance)` |

Lane 1 is what a pull request must pass. Lane 2 records that the phases already
merged still hold.

## Why the contract gates are one job

The database-neutral phase gates are already chained inside Gradle:

```
phase3NoDatabaseDistribution
  └── phase4FinalVerification            (gradle/phase4/contracts.gradle:998)
        ├── phase5aFinalVerification     (gradle/phase5/contracts.gradle:158)
        │     └── phase5bFinalVerification   (gradle/phase5/oracle.gradle:931)
        │           └── phase5cFinalVerification (gradle/phase5/beachhead.gradle:200)
        └── phase5dFinalVerification     (gradle/phase5/zk-functional-slice.gradle:388-395)
              └── phase5eFinalVerification   (gradle/phase5/cohort-routing.gradle:1048)
                    └── phase5fFinalVerification (gradle/phase5/phase5f-context-routing.gradle:749-751)
                          └── phase5g0FinalVerification (gradle/phase5/phase5g-inventories.gradle:89-91)
                                └── phase5g1aFinalVerification (gradle/phase5/write-oracle.gradle)
                                      └── phase5g1ayFinalVerification (gradle/phase5/write-oracle.gradle)
```

Running each gate as its own CI job therefore re-executes work another job is
already doing. `phase5cFinalVerification` is the only gate not on the chain
below its head, and it contributes exactly one thing the chain does not:
`:zkwebui:check` (`gradle/phase5/beachhead.gradle:209`).

So the `Contracts` job runs both, in **one** invocation. Two tasks in one
Gradle invocation share a single task graph, so common work executes once.

## Measured baseline

Task counts from `./gradlew <task> --dry-run --dependency-verification=strict`,
re-measured on `phase-5g-1a-y-workflow-attribution-oracle`:

| Former job | Gradle task | Tasks scheduled |
|---|---|---|
| Full product without database | `phase3NoDatabaseDistribution` | 9 |
| Phase 4 final contract and removal gates | `phase4FinalVerification` | 45 |
| Phase 5b frozen web oracle and artifact pins | `phase5bFinalVerification` | 57 |
| Phase 5c Jakarta web packaging beachhead | `phase5cFinalVerification` | 130 |
| Phase 5d functional ZK CE 10 slice | `phase5dFinalVerification` | 164 |
| Phase 5e cohort routing contracts | `phase5eFinalVerification` | 196 |
| Phase 5f Jakarta web contracts and topology | `phase5fFinalVerification` | 282 |
| Phase 5g-0 discovery inventories | `phase5g0FinalVerification` | 285 |
| Phase 5g-1a legacy write oracle contracts | `phase5g1aFinalVerification` | 292 |
| Phase 5g-1a-y workflow-attribution amendment | `phase5g1ayFinalVerification` | 296 |
| **Sum across the 10 separate jobs** | | **1756** |
| **`Contracts`, one invocation** | `phase5g1ayFinalVerification phase5cFinalVerification` | **302** |

**1454 of 1756 task executions per pull request would be redundant (82.8%).**

Earlier measurements on the same topology: 9 jobs at 1458 against 296 merged
when the Phase 5g-1a database-neutral half landed, 8 jobs at 1168 against 291
merged on `develop` at `91c4c2029`, 7 jobs at 882 against 287 merged at
`6eda2bc8c`, and 6 jobs at 600 against 201 merged before that - redundancy
rising from 66% to 80% as the chain lengthens. Adding a gate to the chain adds
one task to the merged invocation and a whole gate to the notional per-job sum,
which is exactly why the jobs are not split per phase.

### Coverage proof

Set equality was verified, not assumed:

```bash
for t in phase3NoDatabaseDistribution phase4FinalVerification \
         phase5bFinalVerification phase5cFinalVerification \
         phase5dFinalVerification phase5eFinalVerification \
         phase5fFinalVerification phase5g0FinalVerification \
         phase5g1aFinalVerification phase5g1ayFinalVerification; do
  ./gradlew $t --dry-run --dependency-verification=strict \
    | grep SKIPPED | sed 's/ SKIPPED//'
done | sort -u > union.txt

./gradlew phase5g1ayFinalVerification phase5cFinalVerification \
  --dry-run --dependency-verification=strict \
  | grep SKIPPED | sed 's/ SKIPPED//' | sort -u > merged.txt

comm -23 union.txt merged.txt   # must print nothing
```

Result: union 302 tasks, merged 302 tasks, `comm -23` and `comm -13` empty. The
merged invocation schedules **exactly** the same task set as the ten gates
combined.

For `5g-1a-y`, `Contracts` advances to
`phase5g1ayFinalVerification phase5cFinalVerification`. The first task chains
the accepted `phase5g1aFinalVerification` graph and adds only the new
database-neutral patch, source/artifact immutability, materializer, provenance,
manifest, and mutation validators. `phase5cFinalVerification` remains the
off-chain argument because it still contributes `:zkwebui:check`. Advancing the
chain must never drop coverage, so the union proof compares both additions and
removals. The chain head grows from 292 to 296 scheduled tasks; the merged
invocation grows from 297 to 302 because it also includes the off-chain Phase 5c
work. No task is missing from or added beyond the ten-gate union.

The capture lane itself, `phase5g1aLegacyWriteOracleSmoke`, is deliberately
**not** on this chain. It needs a disposable PostgreSQL, the installed Tomcat 9
product and a browser, so it belongs to the `Current-phase database smoke` lane
described below. The task's default runtime remains the existing legacy mode;
this increment selects `corrected-legacy-workflow-attribution` explicitly.

Both Lane 1 jobs retain the default depth-one checkout, then read
`source_commit` from the reviewed contract and fetch only that exact object when
it is absent. This avoids an unbounded history fetch while making the pinned
tree available to the neutral source comparison and the corrected-runtime
materializer. The materializer repeats the same object check/fetch before
invoking its validator, so direct task execution cannot depend on workflow
ordering.

Re-run this whenever the `Contracts` task arguments change.

### Artifact coverage is a separate property

The proof above covers *tasks*, not *uploaded evidence*. Consolidating jobs also
merges their `upload-artifact` path blocks, and a path block is not implied by
the task graph — it is easy to fold six jobs into one and silently stop
uploading an output the graph still produces. Check both:

```bash
extract() { awk '/path: \|/{f=1;next} f&&/^ {12}[^ ]/{print $1;next} f{f=0}'; }
git show origin/develop:.github/workflows/main.yml | extract | sort -u > old.txt
extract < .github/workflows/main.yml | sort -u > new.txt
comm -23 old.txt new.txt   # every line must be subsumed by a broader new path
```

A remaining line is acceptable only when a broader new path is a prefix of it
(for example `zkwebui/build/test-results/` subsuming
`zkwebui/build/test-results/modernUiTest/`), or when a glob was replaced by the
literal filename it matched.

Result on the Phase 5g-1a advance: two lines remained,
`zkwebui/build/test-results/routedBrowserTest/` and its reports directory. Both
are subsumed - `phase5fJakartaWebRoutesSmoke` moved into the regression matrix,
whose block already uploads the broader `zkwebui/build/test-results/` and
`zkwebui/build/reports/tests/`, and which gained `build/phase5f/runtime-evidence/`
and `build/phase5f/tomcat10/logs/` in the same change so the outgoing gate's
evidence is still published. Five paths were added for the incoming gate. This
is the check that catches the common failure of retiring a smoke into the
regression lane while leaving its evidence behind in the job it left.

For `5g-1a-y`, both Lane 1 jobs upload `build/phase5g1ay/`: `Contracts` produces
the neutral summaries and mutation report, while `Current-phase database smoke`
adds corrected-runtime provenance, class digests, and materialization logs. The
temporary `ordinary-runtime-guard/` directory is explicitly excluded, so the
digest-pinned ordinary installed jars used by the failure finalizer cannot be
published as evidence.

### What the count does *not* mean

Wall-clock time barely improves. The six jobs ran in parallel, and the head of
the chain already performed all their work, so it already set the critical
path. The saving is compute and cost — roughly six runner-hours per pull
request — not latency.

## Job naming

`Contracts` and `Current-phase database smoke` are deliberately phase-neutral.
Branch protection references a check by name, so a name that embeds the current
phase would silently stop being enforced the moment the phase advances. Only
the Gradle task arguments change from phase to phase.

Diagnosing a failure does not depend on the job name: Gradle reports the exact
failing task, for example `Execution failed for task ':verifyPhase5eRollback'`,
which is more precise than a per-phase job name was.

## Guards in the `Contracts` job

Two assertions run after the Gradle invocation.

**Distribution archives.** `actions/upload-artifact` treats
`if-no-files-found: error` as satisfied when *any* path in the block matches.
An upload block that lists both an evidence directory and an archive glob will
therefore succeed while silently uploading no archive. The job asserts each
archive with `test -s` instead of relying on the upload action.

This is why the consolidated upload blocks use `if-no-files-found: warn`: each
is the union of the paths produced by several former jobs, and no single gate
produces all of them, so `error` would fail on every run. The outputs worth
failing over are asserted explicitly instead.

**Tracked-file immutability.** `git diff --exit-code` runs after the whole
invocation. Phase 5f's `verifyPhase5fTrackedInputImmutability` is `finalizedBy`
its own aggregate only, so any task scheduled after that aggregate — including
`:zkwebui:check` — is outside its window. The CI-level postcondition covers the
invocation regardless of ordering.

Two tracked binaries are excluded from that check: the Ant reactor regenerates
`lib/ADInterface-1.0.war` and `lib/mysql-connector-java-5.1.13-bin.jar` in
place on every run. This postcondition is what first made that side effect
visible — it is a pre-existing repository condition, not something the
consolidation introduced. Both files are build outputs that happen to be
tracked, and it is the same pair Phase 5f pins with
`verifyPhase5fTrackedInputImmutability`, which corroborates that the phase
authors already knew about it.

They are excluded rather than tolerated silently: the step still prints
`git diff --stat` for the pair, so a change in *which* files the build rewrites
stays observable, while any mutation of any other tracked file fails the job.
The Phase 5g-1a-y oracle validator applies the same exception only to its
separate working-tree inspection. Its pinned-source-to-`HEAD` committed diff
still protects both files, so the exception cannot hide a committed binary
change.
Cleaning this up — either by untracking both artifacts or by making the reactor
write them to a build directory — is repository hygiene owned by Phase 7, not
by this CI change.

## Known flake in the current-phase smoke

`Current-phase database smoke` is a blocking gate that drives a real browser, so
its reliability matters as much as its coverage.

One timeout has been observed: `RoutedCohortMatrixTest.publicOriginCohortMatrix`
failed at `RoutedCohortMatrixTest:320`, the `waitFor` on the `grdChooseRole`
role-selection grid, with a Playwright `TimeoutError`. The same commit passed on
re-run with no change to the job, and the job definition is byte-equivalent to
the `phase-5e-public-origin` job it replaced — same runner image, service
container, environment and Gradle task — so the consolidation did not introduce
it. It is a ZK login round-trip that occasionally exceeds the default Playwright
timeout under runner load.

It is recorded rather than fixed here because raising a timeout inside a phase
contract is a phase change, not a CI change. If it recurs, the fix belongs in
the Phase 5e test's own wait configuration.

`phase5eCohortRoutingSmoke` has since moved to the regression matrix, and
`phase5fJakartaWebRoutesSmoke` has now followed it. The flake therefore no
longer blocks a pull request, but it still gates the post-merge lane and is
still worth fixing.

## The current-phase smoke awaits freeze-off acceptance

`Current-phase database smoke` still runs
`phase5g1aLegacyWriteOracleSmoke`, but now passes
`-Pphase5g1ayMode=corrected-legacy-workflow-attribution`. Candidate run
33785079015 executed that corrected mode with freezing enabled and produced the
domain-reviewed committed facts. It is not acceptance evidence. The
current-phase job must now score those committed bytes with freezing off before
R14 can close.

The corrected mode is unconditional in the current-phase slot deliberately.
Before the reviewed candidate facts were committed, an ordinary PR run was
expected to fail when it scored the corrected attribution against the old
frozen zeros. With the candidate now committed, the same ordinary freeze-off
job is the required separate acceptance run.

Before that task starts, Gradle snapshots both ordinary installed
`Adempiere.jar` copies. The smoke uses an external guard for activation and
requires restoration before provenance validation and scoring. The same guard
is also a Gradle finalizer, so it restores and digest-verifies the ordinary jars
even when materialization, capture, provenance, or scoring fails.

The job must continue to point at the phase under development rather than at a
known-green earlier task. See `docs/modernization/phase-5f-evidence.md` for the
outgoing gate's observed failure modes and the evidence each fix was derived
from.

The job's two Phase 5f deviations have been reconsidered rather than inherited:

- **`--continue` is dropped.** It earned its place against six independent
  shards, where a failed shard still left five real data points. The write
  oracle is one sequential lane: capture B is meaningless if capture A failed,
  and scoring is meaningless if either did. Continuing would produce cascades
  of derived failures that explain nothing.
- **`timeout-minutes: 180` is kept**, as a backstop rather than a budget. The
  lane performs two full captures, each preceded by a golden-archive restore, a
  quiescence re-verification and a container restart, with a whole-schema
  snapshot between every one of nine steps. The real duration is unknown until
  the gate runs, and the value should be revisited from observed data then.

### Freeze mode is isolated from required checks

The separate `.github/workflows/phase5g1ay-oracle-candidate.yml` workflow runs
the uniquely named `Oracle candidate capture (non-acceptance)` job when that
file is pushed on the exact
`phase-5g-1a-y-workflow-attribution-oracle` branch. Scoping the push trigger to
the workflow, corrected-runtime materializer/guard/validators, Gradle wiring,
and dedicated 5g-1a-y contract makes the initial prerequisite push and any
capture-mechanism correction request a candidate run without running the
expensive lane on later frozen-fact or documentation commits. The job passes
`-Pphase5g1aFreeze=true` in addition to the mandatory
`-Pphase5g1ayMode=corrected-legacy-workflow-attribution`. That run emits
candidate contract files instead of scoring against them, and it is **not an
acceptance run**: the run that invents the expected answer must not also be the
run that verifies it. The workflow defines neither branch-protected job name,
so its success or a skipped job cannot satisfy either required check for the
same commit. Only the exact prerequisite-branch push described above can reach
freeze mode. Its artifact includes the exact generated
`contracts/legacy-web-write-v1/` candidate directory as well as the raw runtime
evidence, so domain review and the later contract commit use the files produced
by CI rather than a local reconstruction. The acceptance run is a later
ordinary current-phase smoke against committed, domain-reviewed files, with the
same corrected mode and freezing off.

## Debug dispatch

The main workflow's `workflow_dispatch` takes a `debug_gate` choice. `full`
runs the ordinary manual matrix. Any other value names a single task, runs only
`Current-phase database smoke`, and suppresses `Contracts` and the regression
matrix - without that suppression, asking for one task would also launch a
half-hour contract graph and seven historical database-backed smokes. The
separate push-triggered candidate-capture workflow has no `debug_gate` and
always runs the complete corrected-legacy freeze lane. The main workflow's debug allowlist
currently holds only `phase5g1aLegacyWriteOracleSmoke`, the whole capture lane.
The former direct
`phase5g1aWriteOracleCapture` choice is removed because it bypasses
materialization, activation, provenance, restoration, and scoring; accepting
the corrected-mode property while silently running the ordinary legacy runtime
would be a false diagnostic.

The input is a typed `choice` over a fixed allowlist rather than free text
because `.github/actions/adempiere-build` expands its `task` and `args` inputs
unquoted, so that a multi-task gate shares one Gradle task graph.

## Concurrency

Concurrency groups are repository-wide, not scoped to a workflow. Both
workflows therefore namespace their group with `${{ github.workflow }}`;
without it, one workflow cancels the other on the same ref.

`cancel-in-progress` is enabled for `pull_request` only. A push, nightly or
manual run is a regression record; cancelling it midway would leave the phases
it covers unverified with nothing to indicate that anything was skipped.

## Residual risk: reduced pre-merge coverage

Moving five database-backed smokes to Lane 2 is a real reduction in pre-merge
coverage, not merely a scheduling change. A pull request can now regress any of
the following and still show a green Lane 1:

| Surface | Gate that used to run pre-merge |
|---|---|
| Phase 3 installed product, metadata, Tomcat 9 smoke | `phase3InstalledProduct` |
| Phase 4 installed SOAP API, both historical paths | `phase4InstalledApi` |
| Phase 5b frozen legacy web oracle replay | `phase5bLegacyWebOracleSmoke` |
| Phase 5c browser oracle and rollback rehearsal | `phase5cRollbackRehearsal` |
| Phase 5d modern web slice and SOAP coexistence | `phase5dModernWebSmoke` |

Detection for these moves to *after* the merge.

**Containment rule.** Lane 2 is non-blocking in GitHub, but it is not advisory.
A red `Regression matrix` is a stop-the-line event:

1. No further phase work merges to `develop` until it is green or the failure is
   explicitly triaged as unrelated and recorded.
2. The owner is the phase lead for the failing phase; overall owner is
   @samqbush.
3. If Lane 2 has not run green within a week, the affected phases must be
   treated as unverified in the plan's rung table, not assumed still lit.

Without that rule this lane is detection without containment.

## Enforcement status

**None of these checks is enforced.** Branch protection and required status
checks have never been enabled on `develop` (`MODERNIZATION_PLAN.md`, risk R8),
so every job can be red and a merge still succeeds. Enabling them is a manual
GitHub administrator action.

The two checks to mark required are:

- `Contracts`
- `Current-phase database smoke`

## Advancing the lane when a phase lands

When a phase merges, change only the task arguments:

- `Contracts` → `<newPhase>FinalVerification phase5cFinalVerification`
- `Current-phase database smoke` → the new phase's database-backed smoke
- `Regression matrix` → add the previous phase's smoke to the matrix

Then re-run the coverage proof above, and check whether the phase moves any
asserted output path.

This has already been done once, for Phase 5f: it relocates the distribution
archives out of `install/build/` into `build/phase3/release/` via
`scripts/phase3/run-isolated-ant-build.sh`, and the archive assertion paths in
the `Contracts` job moved with it. `install/build/` is an internal reactor
scratch directory, not a stable handoff.
