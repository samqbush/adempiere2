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

Phases 1-4, Phase 5a, and Phase 5b are merged to `develop`; Phase 4 completed as
`8c0ca4c1d6b35a5f366d6dd2150ed3bb27bc2a89`. All 33 XFire
operation baselines and 11 additional scenarios now pass on the isolated CXF
4.1.8/Jakarta EE 10 runtime on Tomcat 10.1.59/JDK 21. A Tomcat 9 compatibility
router preserves both historical endpoint paths and the installed-product gate
passes, including embedding and verifying the unconfigured modern runtime in
both existing 394LTS release archives. Rollback was rehearsed for each
request-scoped service and the atomic 21-operation `ADService` unit before the
router became CXF-only. Active XFire source, publication, and runtime binaries
are removed. Phase 5 is an umbrella milestone delivered through sequential
`phase-5a-*` through `phase-5h-*` PRs. Phase 5a owns the ZK/Jakarta inventory,
the supported-target ADR, and the Phase 4-to-5 non-SOAP route hand-off. Phase
5b froze the Tomcat 9 oracle. Phase 5c adds a dark/L1 Jakarta packaging
beachhead and verified semantic browser oracle; Phase 5d remains the first
modern UI Testability Milestone.

The accepted target is ZK CE `10.3.0.1-jakarta` from the public ZK repository.
Do not introduce evaluation artifacts or commercial repository credentials.
Freeze the installed ZK 3.6/Tomcat 9 web product before migrating source; later
rollback uses checksum-pinned artifacts, not a dual framework source tree.

## Commands

These are the canonical commands visible in the current checkout. Commands added
by a phase become canonical only after that phase's exit criteria prove them.

| Action | Command | Current qualification |
|---|---|---|
| Phase 3 full product, no DB restore | `./gradlew phase3NoDatabaseDistribution --dependency-verification=strict` | Guarded JDK 21 Ant build, install, silent setup, topology check, and normalized artifact manifest |
| Phase 3 installed product | `xvfb-run -a ./gradlew phase3InstalledProduct -Pphase2DbSystemPassword='<password>' -Pphase3DbSystemPassword='<password>' --dependency-verification=strict` | Requires disposable local PostgreSQL 14.6; runs DB-backed smoke, full install, metadata validation, evidence capture, installed Tomcat 9 smoke, and marker-guarded database/role cleanup |
| Phase 4 modern SOAP boot | `./gradlew phase4ModernSoapRuntimeSmoke --dependency-verification=strict` | Builds the XFire-free CXF/Jakarta WAR, verifies its archive, and boots checksum-pinned loopback-only Tomcat 10.1.59 |
| Phase 4 modern SOAP database smoke | `./gradlew phase4ModernSoapDatabaseSmoke -Pphase3DbSystemPassword='<password>' -Pphase3DbPort=5433 --dependency-verification=strict` | Marker-owned PostgreSQL 14.6 gate for all 33 frozen operation baselines, 11 valid-credential/security scenarios, and four mutation-state deltas; cleanup runs after the smoke |
| Phase 4 final contracts | `./gradlew phase4FinalVerification --dependency-verification=strict` | Database-neutral final gate for frozen contracts, route classification, active XFire absence, retained evidence, business/router tests, and modern-WAR linkage |
| Phase 4 compatibility router smoke | `./gradlew phase4CompatibilityRouterSmoke -Pphase3DbSystemPassword='<password>' -Pphase3DbPort=5433 --dependency-verification=strict` | Starts Tomcat 9 and 10.1 together against marker-owned PostgreSQL 14.6 and proves both historical paths route the complete corpus only to CXF |
| Phase 4 installed API | `./gradlew phase4InstalledApi -Pphase3DbSystemPassword='<password>' -Pphase3DbPort=5433 --dependency-verification=strict` | Canonical XFire-free installed-product SOAP gate: verifies source/package absence, stages the isolated runtime beside Tomcat 9 and in both unconfigured 394LTS release archives, replays all 33 baselines through both historical paths and all 11 additional scenarios through the primary path, then performs marker-guarded cleanup |
| Phase 5a inventories and target | `./gradlew phase5aFinalVerification --dependency-verification=strict` | Database-neutral gate for reviewed ZK source/runtime, web-asset, namespace, descriptor deployment, and route inventories; verifies the pinned public ZK CE Jakarta target and preserves Phase 4 SOAP assertions |
| Phase 5b frozen web oracle | `./gradlew phase5bFinalVerification --dependency-verification=strict` | Database-neutral gate for the frozen `contracts/legacy-web-v1/` tree: recursive WAR/nested-JAR artifact pins, route coverage with per-vector proof strength, owned exclusions, normalizer mutation proof, 24 runtime coordinates, and the file manifest; chains `phase5aFinalVerification` |
| Phase 5b legacy web oracle replay | `./gradlew phase5bLegacyWebOracleSmoke -Pphase3DbSystemPassword='<password>' --dependency-verification=strict` | Marker-owned PostgreSQL gate that boots the installed Tomcat 9 product, drives the ZK 3.6 AU flow twice with a fixture reset between captures, proves the self-diff, and replays both captures against the frozen oracle |
| Phase 5c packaging beachhead | `./gradlew phase5cFinalVerification --dependency-verification=strict` | Database-neutral gate for the packaging-only ZK Jakarta WAR, transformer fixtures and corpus report, loopback 503 marker, installed/release overlay, Phase 4 preservation, binding ADR, and artifact rollback |
| Phase 5c browser and rollback | `./gradlew phase5cRollbackRehearsal -Pphase3DbSystemPassword='<password>' --dependency-verification=strict` | Marker-owned PostgreSQL gate for the Phase 5b replay plus two fixture-isolated semantic Playwright captures using only checksum-verified browser artifacts |
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

Existing CI lives under `.github/workflows/`. Phase 4 currently includes 29
Gradle projects after promoting the XFire-free web-service seam while Ant
continues to own the legacy WAR. Phase 3 preserved all 32
Ant reactor entries and added explicit no-database and
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
