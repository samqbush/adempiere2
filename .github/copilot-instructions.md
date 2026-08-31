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

Phases 1-4 and Phases 5a through 5f are merged to `develop`; Phase 4 completed as
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
5b froze the Tomcat 9 oracle. Phase 5c added a dark/L1 Jakarta packaging
beachhead and a verified semantic browser oracle. **Phase 5d crossed the modern
UI Testability Milestone**: `webui-modern.war` keeps its artifact name and
`/webui-modern` context path but now carries the functional ZK CE 10 slice, and
it passes ordinary login, role selection, desktop/menu and the read-only "Error
Message" window while reproducing all eleven comparable frozen legacy semantic
facts and the zero-write database effect. The modern web component is therefore
lit at L3, not dark at L1. The Phase 5c 503 marker no longer exists; its
historical evidence lives in `docs/modernization/phase-5c-evidence.md`. Phase 5d
merged to `develop` as PR #9 at
`b47464d2763694c093ed22470000e00f2b6aee73`.

**Phase 5e routes selected sessions to that slice through the public Tomcat 9
`/webui` ingress.** It is a lit, L3 security and session increment. New sessions
are selected after ordinary authentication and role selection from three strict,
fail-closed, system-level `AD_SysConfig` rows (`MODERN_WEB_UI_ENABLED`,
`MODERN_WEB_UI_USER_IDS`, `MODERN_WEB_UI_ROLE_IDS`); a duplicate, malformed or
unreadable row invalidates the complete configuration and keeps every new
session legacy. The decision is taken once, is sticky, and never moves an active
session. A selected session rotates its Tomcat 9 session identifier exactly once
and is handed to Tomcat 10 with a versioned, HMAC-SHA-256, single-use,
30-second, loopback-only ticket that never reaches the browser. The modern
application is mounted internally at the same `/webui` path it is served on
publicly, so no response body is rewritten. The browser holds exactly one public
cookie, both contexts are cookie-only, and an established modern session never
falls back to the legacy runtime. The routed `webui.war` is the frozen Phase 5b
artifact plus exactly three reviewed entries, derived deterministically. The
installed product and both release archives stage the modern archive at
`tomcat10-api/phase5e/webui-modern.war` - the path the `Context` descriptor's
`docBase` resolves to - and remove the superseded auto-deployed
`tomcat10-api/webapps/webui-modern.war`, so exactly one modern UI context is
shipped. The internal handoff is registered as transitional state **T5e-1**,
closing in Phase 5h (`docs/modernization/phase-5e-cohort-routing-adr.md`,
`docs/modernization/phase-5e-transitional-state.md`).

Both Phase 5e gates are executed and green. The database-backed smoke records
all 23 public-origin cohort, isolation, lifecycle, SOAP-coexistence, and
secret-hygiene rows as passing.

**Phase 5f merged to `develop` as PR #11 at `83aeb8536`, with both gates
executed and green.** Its database-neutral `phase5fFinalVerification` gate
has executed green twice. It governs exactly 82 deployed and 30 non-deployed
mappings, builds isolated generated Jakarta source/web trees and five modern
context WARs (`/admin`, `/`, `/mobile`, `/adempiere`, `/wstore`), precompiles
all 25 retained `/wstore` JSPs, adds source-native `/webui/timeline`, and
replaces only the exact historical DSP theme URL with static Phase 5d CSS.
Installed and release topology preserves Phase 4 CXF and Phase 5e `/webui`,
stages one Phase 5f WAR per context under `tomcat10-api/phase5f/`, and retains
pristine per-context rollback.

`phase5fJakartaWebRoutesSmoke` has six public-origin shards and
**has been executed in CI, which supplies `phase3DbSystemPassword`, and it is
green.** All six shards execute in a single run. Run 33379849664, on commit
`9ba62875d`, recorded 129 observations - `/` (16), `/wstore` (68), `/webui` (6), `/admin` (4), `/mobile`
(14), `/adempiere` (21) - and passed `verifyPhase5fSwitchBaseline`,
`capturePhase5fSoapCoexistence` and
`verifyPhase5fBackgroundProcessorsQuiesced`. The strict aggregate
`verifyPhase5fRuntimeEvidence` passed. Every failure mode was diagnosed from a run's own evidence and fixed: a dropped `<error-page>`,
three registered deviations (`DEV-P5F-ERR-02..04`), the container
`redirectPort`, the Phase 4 POS credential fixture, a latent argument-list
defect, three ambient database writers (timer-driven processors, automatic error
reporting, and first-touch `WebEnv.initWeb` initialisation), and an aggregate
digest that measured more content than the table digests. The shards run in an
explicit order (`/`, `/wstore`, `/webui`, `/admin`, `/mobile`, `/adempiere`),
record vector failures rather than aborting, and `Current-phase database smoke`
passes `--continue`, so one run reports the whole matrix.
All 82 route observations and their route-specific database effects are
therefore observed, and both contract ledgers carry the executed marker. The 25
`/wstore` JSP precompile rows remain
`contract-only-runtime-observation-pending`, because only three of those pages
are reached by a route vector. T5e-1 remains open and T5f-1 closes in Phase 5h.

**Phase 5g is active and is decomposed into sequential sub-increments `5g-0`
through `5g-7`**, each cut from `develop` and merged before the next begins.
`MODERNIZATION_PLAN.md` carries the decomposition and
`docs/modernization/phase-5g-web-parity-adr.md` is binding. Two ordering rules
govern every increment: the expected answer is captured from the **legacy**
Tomcat 9 runtime and domain-reviewed **before** the modern runtime is scored
against it, and **no single PR may both invent the expected answer and implement
the thing being scored**. Every modern write is scored only through the public
routed `/webui` origin. `contract-only-runtime-observation-pending` is **not** a
permitted disposition for a Phase 5g acceptance criterion.

**No modern business write, document transition, process execution, report or
upload/download has ever been observed.** The modern runtime is proven for
login, role selection, menu and a read-only window; it does write `AD_Session`
on login. Do not describe Phase 5f route observation as write parity.

`5g-0` is reconciliation, discovery and governance and ships no runtime code. It
adds the reviewed `contracts/phase5g-web-parity-v1/` inventories - 351 classified
dictionary processes, 174 callout columns, 197 extension surfaces - gated by
`phase5g0FinalVerification`. It
also opens, but does not close, a named disposition for `/mobile`, `/adempiere`
and `/admin` in
`docs/modernization/phase-5g-disabled-context-disposition.md`. Phase 5g does
**not** enable those contexts; `phase5g-web-parity-gate` is defined there as the
`5g-7` gate that requires a recorded disposition per context, and Phase 5h is
blocked behind it. `5g-0` merged to `develop` as PR #13 at `91c4c2029`.

**`5g-1a` is active and is the legacy Business Partner CRUD write oracle.** It
captures the expected answer for the first business write from the **legacy**
Tomcat 9 / ZK 3.6 runtime, so that `5g-1b` can score the modern runtime against
an answer it did not invent. By ADR rule it ships **no modern runtime code** and
cannot report parity. Its contract tree is `contracts/legacy-web-write-v1/`,
which is new rather than an extension of `contracts/legacy-web-browser-v1/`,
because that tree's manifest hard-fails on any unmanifested file and its
`modern-comparable-facts.tsv` is specifically the Phase 5d read-only comparison
policy. Scope is create, update and deactivate on `C_BPartner` as `GardenAdmin`;
`C_BPartner_Location` and hard delete are recorded exclusions with named closing
increments. `C_BPartner` is chosen because it is the only reviewed table with
neither a callout nor a registered model validator on its path, and that
attribution is re-derived mechanically on every run rather than cross-referenced
against the 5g-0 inventories, which record declarations and not table
subscriptions.

The database-neutral half is delivered and gated by
`phase5g1aFinalVerification`, now the chain head. The captured facts
(`effect-model.tsv`, `business-values.tsv`, `foreign-key-graph.tsv`,
`semantic-facts.tsv`, `network-classes.tsv`, `concurrency-facts.tsv`,
`allowed-browser-errors.tsv`, `write-flow.tsv`) and the recorded domain review
are **not frozen yet**; the contract README names each absent file rather than
omitting it. Two rules bind the capture: isolation is **full seed restore plus
reviewed fixture plus container restart before every capture**, never surgical
rollback, and effects are measured **per step**, because one before/after pair
around create -> update -> deactivate shows only the final deactivated row. The
effect model is two layers - a whole-database changed-table sentinel plus keyed
relational extraction - because a measurement that queries only declared tables
cannot see a write to an undeclared one.

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
| Phase 5d functional slice | `./gradlew phase5dFinalVerification --dependency-verification=strict` | Database-neutral gate for the functional ZK CE 10 `/webui-modern` WAR, the ZK compile closure, the current ZK source and namespace inventories, the frozen legacy artifact and source crossing, the installed/release overlay, artifact rollback, Phase 4 preservation, and the still-valid Phase 5b/5c assertions. Does **not** chain `phase5cFinalVerification` |
| Phase 5d modern web smoke | `./gradlew phase5dModernWebSmoke -Pphase3DbSystemPassword='<password>' --dependency-verification=strict` | Marker-owned PostgreSQL gate that boots the modern ZK slice beside the unchanged Phase 4 CXF WAR in one loopback Tomcat 10.1.59 JVM, captures the login/role/menu/read-only-window flow twice with a fixture reset, compares eleven semantic facts and the zero-write effect with the frozen legacy baseline, and replays the complete Phase 4 SOAP corpus while a modern ZK session is authenticated |
| Phase 5e cohort routing gate | `./gradlew phase5eFinalVerification --dependency-verification=strict` | Database-neutral gate for the fail-closed cohort model and handoff protocol, the isolated Javax/ZK 3.6 bridge closure pinned against the frozen Phase 5b WAR, the deterministic derived routed `webui.war` and its three-entry diff contract, the reviewed public route/header/cookie/audit contracts, sixteen mutation proofs (each mutant is compiled before it is scored, and detection is read from the named test's own JUnit report so a compile or infrastructure failure can never count), the enforced 8 MiB/64 MiB proxy byte caps, the session-cache census parser tests over the reviewed `catalina.out` fixtures, the installed and release routing overlay including the resolved modern `docBase`, and rollback that survives the **real** Ant `setupWLib` run with real merge inputs. **Chains** `phase5dFinalVerification` |
| Phase 5e cohort routing smoke | `./gradlew phase5eCohortRoutingSmoke -Pphase3DbSystemPassword='<password>' --dependency-verification=strict` | Executed, green marker-owned PostgreSQL gate that boots the routed public Tomcat 9 ingress and the loopback modern runtime, drives the complete public-origin cohort matrix through a browser that can only reach the public origin, proves concurrent client/org/role/user/language isolation by comparing each interleaved capture with that identity's solo capture, proves that logout, the product's session-inactivity timeout and container-side destruction each record a real session destruction on every runtime that must have one and return every `SessionManager` cache a runtime reports for that session to its marked baseline, proves a logged-out browser is decided again rather than inheriting its previous cohort, and replays the complete Phase 4 SOAP corpus while routed modern sessions are authenticated. All 23 matrix rows pass |
| Phase 5e handoff key | `./gradlew provisionPhase5eHandoffKey` | Generates the shared >=32-byte 0600 key from the OS CSPRNG, outside every archive under `ADEMPIERE_HOME`. The repository ships no key and no placeholder |
| Phase 5f Jakarta route contracts and topology | `./gradlew phase5fFinalVerification --dependency-verification=strict` | Executed green twice. Verifies the 82 deployed/30 non-deployed contract and mutations, isolated generated Jakarta closures, five deterministic modern context WARs, 25 JSP precompiles, Servlet 6/discovery rules, `/timeline` and static DSP contracts, independent routing policies, installed/release topology, rollback, inventories, and Phase 4/5d/5e regressions. Does **not** prove runtime route/database-effect parity |
| Phase 5f Jakarta route smoke | `./gradlew phase5fJakartaWebRoutesSmoke -Pphase3DbSystemPassword='<password>' --dependency-verification=strict` | Six-shard, marker-owned PostgreSQL public-origin replay for `/webui`, `/admin`, `/`, `/mobile`, `/adempiere`, and `/wstore`, plus exact 82-row effect validation and Phase 4 SOAP coexistence. Shards run in the explicit order `/`, `/wstore`, `/webui`, `/admin`, `/mobile`, `/adempiere`, record vector failures instead of aborting, and are driven with `--continue`. **Executed and green** in run 33379849664 on commit `9ba62875d`: 129 observations, zero vector failures across all six shards, and the strict aggregate `verifyPhase5fRuntimeEvidence` validated 82 legacy routes, all 37 eligible modern routes and 45 explicitly unexecuted modern routes |
| Phase 5g-1a legacy write oracle contracts | `./gradlew phase5g1aFinalVerification --dependency-verification=strict` | Database-neutral **head of the phase-gate chain**. Verifies the `contracts/legacy-web-write-v1/` manifest and its required-file floor, re-derives the table-scoped callout and registered-validator attribution from `db/ddlutils/adempiere-data.xml` and the reactor sources to prove `C_BPartner` carries neither a callout nor a registered model validator, scores the write-capture normalizer in both directions (10 defect classes detected, 4 volatility classes normalized) against the committed raw fixture, and chains `phase5g0FinalVerification`. Ships no modern runtime code and proves **no** write parity |
| Phase 5g-0 discovery inventories | `./gradlew phase5g0FinalVerification --dependency-verification=strict` | Database-neutral gate, chained by `phase5g1aFinalVerification`. Regenerates the Phase 5g-0 process-classification, callout-column and extension-surface inventories from `db/ddlutils/adempiere-data.xml` and the reactor sources, requires an exact match against the reviewed `contracts/phase5g-web-parity-v1/` tree including its commentary preamble, closes that directory listing, and chains `phase5fFinalVerification`. Ships no runtime code and proves no parity |
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

Existing CI lives under `.github/workflows/`. The build has the root plus 31
included Gradle projects: Phase 4 promoted the XFire-free web-service seam, and Phase 5e added
the Gradle-only `:org.adempiere.cohort` routing project. That project has no Ant
reactor entry, because its bridge source set compiles against classes extracted
from the materialized frozen Phase 5b WAR rather than against anything the Ant
reactor builds. From Phase 5d
the Ant reactor no longer compiles the migrated ZK trees: the legacy `webui.war`
and the five ZK package jars are materialized from the frozen Phase 5b commit in
an isolated worktree, and Gradle owns the modern `/webui-modern` WAR. From Phase
5e the deployed Tomcat 9 `webui.war` is *derived* from that frozen artifact by
`derivePhase5eLegacyWebWar`; the pristine copy remains `lib/webuiOriginal.war`
and is the rollback material. Phase 5f adds isolated generated source sets and
five WAR tasks under existing projects, not another Gradle project. Phase 3 preserved all 32
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
`develop`. Until that happens, CI can run without blocking merges. The two
checks to mark required are `Contracts` and `Current-phase database smoke`.

**CI topology.** `.github/workflows/main.yml` runs two lanes, documented in
`docs/modernization/ci-topology.md`. Every pull request runs `Contracts` - a
single Gradle invocation of the head of the phase-gate chain - currently
`phase5g1aFinalVerification` - plus `phase5cFinalVerification`, which together
schedule exactly the same task set as the nine notional per-phase jobs would
(296 tasks, versus 1458 across those gates) - and
`Current-phase database smoke`, the runtime gate for the phase under active
development, currently `phase5fJakartaWebRoutesSmoke`, which is green. The remaining database-backed
smokes run post-merge on `push` to `develop`, nightly, and on demand as
`Regression matrix`.

Do not re-add a per-phase CI job for a database-neutral gate. Those gates are
already chained inside Gradle, so a separate job only re-executes work the
`Contracts` job performs. When a phase lands, change the task arguments of the
two phase-neutral jobs and re-run the coverage proof in the topology document;
do not rename the jobs, because branch protection references them by name.
Lane 2 is non-blocking in GitHub but is governed by a stop-the-line containment
rule (residual risk R10).

## Where to run gates: prefer GitHub Actions over local execution

**Do not run heavy builds, Ant reactor builds, full Gradle gates, container
lanes, browser lanes, or any database-backed smoke on the developer machine.**
Push the branch and let GitHub Actions execute them. The Ant reactor build,
`phase3InstalledProduct`, every `*FinalVerification` gate, and every `*Smoke`
task are CI activities, not local ones. Phase 5f initial setup alone is roughly
22 minutes and its full gate took 1h13m54s; a local run is slower still and can
exhaust the machine.

Local execution is for **fast, targeted, single-purpose checks** that finish in
seconds. The verified fast loops in this repository are:

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
with the run id and commit, exactly as `MODERNIZATION_PLAN.md` records run
33379849664 on `9ba62875d`. Never present an unrun gate as green.

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
