# Copilot Instructions - ADempiere Modernization

> Generated as an editable execution guide for `MODERNIZATION_PLAN.md`. Keep
> this file synchronized with the plan, `ARCHITECTURE.md`, README, CI workflows,
> and module topology.

ADempiere is being modernized as a modular monolith. Safety is adaptive: each
component has a named Testability Milestone and safety rung (L0-L4) in
`MODERNIZATION_PLAN.md`. Never require an automated test gate before that
component is testable. The plan-wide reproducible-CI Milestone is Phase 1, when
the existing Ant unit-test baseline is recorded and Gradle must become
reproducible and execute meaningful tests instead of `NO-SOURCE`.

## Commands

These are the canonical commands visible in the current checkout. Commands added
by a phase become canonical only after that phase's exit criteria prove them.

| Action | Command | Current qualification |
|---|---|---|
| Phase 3 full product, no DB restore | `./gradlew phase3NoDatabaseDistribution --dependency-verification=strict` | Guarded JDK 21 Ant build, install, silent setup, topology check, and normalized artifact manifest |
| Phase 3 installed product | `xvfb-run -a ./gradlew phase3InstalledProduct -Pphase2DbSystemPassword='<password>' -Pphase3DbSystemPassword='<password>' --dependency-verification=strict` | Requires disposable local PostgreSQL 14.6; runs DB-backed smoke, full install, metadata validation, evidence capture, installed Tomcat 9 smoke, and marker-guarded database/role cleanup |
| Full product build, no DB restore | `ant build -Dnodbrestore=true` | Authoritative underlying Ant reactor; prefer the guarded Phase 3 lifecycle for CI |
| Full product build with DB restore/migrations | `ant build -Dnodbrestore=false` | Database-affecting underlying reactor; run only against an approved disposable environment |
| Gradle module build | `./gradlew build --dependency-verification=strict` | Reproducible Phase 2 gate on JDK 21 with Java 21 bytecode; omits quarantined Ant-only deployables |
| Base unit tests | `ant -f tools/build.xml && ant -f base/build.xml unit-tests` | `tools` must run first to create `lib/CCTools.jar` and related outputs; the complete Ant reactor already preserves this order |
| Base integration tests | `ant -f base/build.xml integration-tests -Dtest.performIntegrationTests=true` | Requires configured application/database environment |
| Base Gradle test task | `./gradlew :base:test --dependency-verification=strict` | Executes the fail-closed `UnitTest` classification and emits JUnit XML |
| Single Gradle test | `./gradlew :base:test --tests '<fully.qualified.Test>'` | `org.adempiere.test` remains a published test-support artifact |
| Phase 2 runtime smoke | `xvfb-run -a ./gradlew :base:phase2RuntimeSmoke -Pphase2DbSystemPassword='<password>' --dependency-verification=strict` | Requires disposable local PostgreSQL 14.6; restores the seed, applies `394lts`, and cleans up on success or failure |
| Desktop client | `./utils/RUN_Adempiere.sh` | Requires installed product and database configuration |
| Application server | `./utils/RUN_Server2.sh` | Requires installed product and selected Tomcat/WildFly/Jetty |
| Restore seed | `./utils/RUN_ImportAdempiere.sh` | Destructive/database-affecting; inspect environment first |
| Apply XML migrations | `./utils/RUN_MigrateXML.sh` | Database-affecting |
| Lint | None | Do not invent a lint gate; add and verify one in a planned phase |
| Format | Eclipse formatter config exists, but no canonical CLI gate | Do not claim formatting is enforced |
| Typecheck | Java compilation through the applicable Ant/Gradle build | No separate typecheck command |
| End-to-end/contract | None currently canonical | Introduced incrementally in Phases 2-5 |

Existing CI lives under `.github/workflows/`. Phase 3 preserves all 28 included
Gradle projects and all 32 Ant reactor entries, adds explicit no-database and
installed-product lanes, and retains the three-test disposable runtime smoke.
The `jbossfacet` surface is explicitly quarantined because its checked-in JBoss
API depends on `java.security.acl.Group`, which JDK 21 removed. Required-check
enforcement remains a manual repository-administrator action.

Phase 3 metadata validation is fail-closed but carries 16 named pre-existing
active process-binding residuals in `gradle/phase3/metadata-quarantine.tsv`.
Do not describe them as clean metadata; additions or stale quarantine entries
must fail, and Phase 7 owns the usage/retirement decision.

**CI enforcement is manual.** An agent can author workflows, but a GitHub
administrator must enable branch protection and required status checks for
`develop`. Until that happens, CI can run without blocking merges.

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
deployables, routes, endpoints, or supported databases updates this file,
`MODERNIZATION_PLAN.md`, `ARCHITECTURE.md`, README, and relevant topology lists
in the same PR.

No insecure transitional state is approved by default. A temporary permit-all,
CSRF disable, open endpoint, placeholder secret, scanner bypass, or disabled
check must be narrowly scoped and registered in the plan with its reason, owner,
residual risk, and mandatory closing phase.
