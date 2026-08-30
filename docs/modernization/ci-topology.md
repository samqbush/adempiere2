# CI topology

How the modernization gates are scheduled, why they are grouped this way, and
what that grouping deliberately gives up. This implements
`MODERNIZATION_PLAN.md` item 3.7.

## Lanes

| Lane | Trigger | Blocking | Jobs |
|---|---|---|---|
| 1 | `pull_request` → `develop`/`master` | Yes | `Contracts`, `Current-phase database smoke` |
| 2 | `push` → `develop`/`master`, nightly `schedule`, `workflow_dispatch` | No | `Regression matrix` (5 database-backed gates) |

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
```

Running each gate as its own CI job therefore re-executes work another job is
already doing. `phase5cFinalVerification` is the only gate not on the chain
below its head, and it contributes exactly one thing the chain does not:
`:zkwebui:check` (`gradle/phase5/beachhead.gradle:209`).

So the `Contracts` job runs both, in **one** invocation. Two tasks in one
Gradle invocation share a single task graph, so common work executes once.

## Measured baseline

Task counts from `./gradlew <task> --dry-run --dependency-verification=strict`
on `develop` at `6eda2bc8c`:

| Former job | Gradle task | Tasks scheduled |
|---|---|---|
| Full product without database | `phase3NoDatabaseDistribution` | 9 |
| Phase 4 final contract and removal gates | `phase4FinalVerification` | 45 |
| Phase 5b frozen web oracle and artifact pins | `phase5bFinalVerification` | 57 |
| Phase 5c Jakarta web packaging beachhead | `phase5cFinalVerification` | 130 |
| Phase 5d functional ZK CE 10 slice | `phase5dFinalVerification` | 164 |
| Phase 5e cohort routing contracts | `phase5eFinalVerification` | 195 |
| **Sum across the 6 separate jobs** | | **600** |
| **`Contracts`, one invocation** | `phase5eFinalVerification phase5cFinalVerification` | **201** |

**399 of 600 task executions per pull request were redundant (66.5%).**

### Coverage proof

Set equality was verified, not assumed:

```bash
for t in phase3NoDatabaseDistribution phase4FinalVerification \
         phase5bFinalVerification phase5cFinalVerification \
         phase5dFinalVerification phase5eFinalVerification; do
  ./gradlew $t --dry-run --dependency-verification=strict \
    | grep SKIPPED | sed 's/ SKIPPED//'
done | sort -u > union.txt

./gradlew phase5eFinalVerification phase5cFinalVerification \
  --dry-run --dependency-verification=strict \
  | grep SKIPPED | sed 's/ SKIPPED//' | sort -u > merged.txt

comm -23 union.txt merged.txt   # must print nothing
```

Result: union 201 tasks, merged 201 tasks, `comm -23` empty. The merged
invocation schedules **exactly** the same task set as the six jobs combined.

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

Then re-run the coverage proof above. Note for Phase 5f specifically: it
relocates the distribution archives out of `install/build/` into
`build/phase3/release/` via `scripts/phase3/run-isolated-ant-build.sh`, so the
archive assertion paths in the `Contracts` job must move with it.
