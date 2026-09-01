# ADempiere Modernization Plan

> **Evidence base:** [`ARCHITECTURE.md`](ARCHITECTURE.md), describing local
> branch `develop` at commit
> `59557cc2ee85ac938cd4f31a246d891bc2b15b8f` (2023-12-11).
>
> **Planning date:** 2026-08-20.
>
> **Status:** Phases 1-4, Phase 5a, and Phase 5b are merged to `develop`. Phase 4 completed
> as `8c0ca4c1d6b35a5f366d6dd2150ed3bb27bc2a89`; Phase 5a completed as
> `dc7e84f68` (PR #6) and owns the ZK/Jakarta inventory, the Phase 4-to-5
> route-contract hand-off, and the target-stack ADR. Phase 5b completed as
> `cac0efdcaa13464e069291992214880cd0239ec5` (PR #7). Phase 5c is in progress
> on `phase-5c-jakarta-web-beachhead`; it adds the dark Jakarta packaging
> beachhead, verified semantic browser oracle, and binding ingress/session ADR.
> Required-check enforcement remains a repository-administrator action and
> residual risk R8.

## 1. Executive summary

Modernize ADempiere as a **modular monolith**, not as a service rewrite. Preserve
the metadata-driven persistence, process, workflow, document, accounting, and
extension model; first make the existing core reproducible and meaningfully
testable, then move the runtime to JDK 21, freeze external contracts, replace
retired edge technologies behind compatibility adapters, and finally consolidate
the build and dependency estate. The work is mixed-strategy: the Gradle-covered
core, Swing client, and background server are close enough to use
**freeze-then-lift**, while the Ant-only web/API/install surfaces use
**beachhead-then-expand**. The expected scope is seven independently gated
phases, with the ZK/Jakarta transition and external-contract migration carrying
the largest cost and risk.

## 2. Current state assessment

### 2.1 Stack and topology

ADempiere is a Java 11 metadata-driven modular monolith. Swing, ZK, SOAP,
servlet applications, POS, background processors, migrations, reports, and
domain extensions share the `base` model/process engine and relational
application dictionary
([architecture stack](ARCHITECTURE.md#technology-detection),
[container map](ARCHITECTURE.md#c4-level-2-containers-and-deployables)). Ant is the authoritative
distribution reactor, Gradle builds a narrower library/module graph, and sbt is
a non-portable experimental path
([commands](ARCHITECTURE.md#commands-and-verification-inventory),
[resolved build contradiction](ARCHITECTURE.md#directory-and-module-layout)).

The database is both the transactional store and the runtime control plane.
Schema, UI metadata, access rules, processes, workflows, validators, schedulers,
and system configuration are deployable behavior, not passive data
([data and storage](ARCHITECTURE.md#data-and-storage)). This means a safe migration
must preserve Java contracts, database schema, application-dictionary semantics,
generated models, and ordered XML migrations together.

### 2.2 Functional/domain map

| Domain | Current implementation | Architectural significance |
|---|---|---|
| Persistence and extension | `PO`, `MTable`, generated `I_*`/`X_*`, hand-written `M*`, validators, reflection | Core compatibility boundary; metadata and code can drift at runtime ([deep-dive](ARCHITECTURE.md#1-metadata-driven-persistence-and-extension)). |
| Process, workflow, documents, accounting | `SvrProcess`, `ProcessCtl`, `MWFActivity`, `DocumentEngine`, `Doc`/`Fact` | Highest business-regression cost; transaction and state-machine behavior must be frozen at seams ([deep-dive](ARCHITECTURE.md#2-document-workflow-process-and-accounting-execution)). |
| Desktop and POS | Swing client and Swing/ZK POS | Gradle-compilable, but currently has no executed Gradle tests ([entry points](ARCHITECTURE.md#entry-points-and-deployable-applications), [testing](ARCHITECTURE.md#testing-model)). |
| Web UI and session context | ZK 3.6.3, servlet descriptors, thread/session context propagation | Security and tenancy boundary; 298 Java files reference `org.zkoss` in this checkout (279 through imports). |
| Background processing | Database-configured processor manager, worker threads, scheduler, named transactions | Operationally coupled to metadata and tenant/user context ([background jobs](ARCHITECTURE.md#background-jobs)). |
| External APIs and legacy web apps | XFire SOAP plus store, CM, mobile, server, monitor, and servlet routes | Wire and route compatibility boundary; there are 11 `web.xml` descriptors locally ([APIs](ARCHITECTURE.md#apis-and-integration-surfaces)). |
| Database/install/release | PostgreSQL/Oracle seeds, 978 migration XML files in repository history, release-scoped migration selection, setup, installer ZIP/TAR, external app servers | Deployment and upgrade boundary; Ant is authoritative ([runtime surface](ARCHITECTURE.md#deployment-and-runtime-surface)). |
| Domain modules | `org.adempiere.*`, `org.eevolution.*`, and `org.spin.*` | Build topology is manually duplicated across Ant and Gradle. |

### 2.3 Principal modernization drivers

1. **False-green testing.** Gradle modules declare Jupiter APIs but do not wire
   repository test sources or `useJUnitPlatform()`. The observed Gradle tasks
   report `NO-SOURCE`; integration tests are disabled by default
   ([testing model](ARCHITECTURE.md#testing-model)).
2. **Non-reproducible dependencies.** There is no Gradle wrapper or dependency
   lockfile, and 406 tracked JARs coexist with Gradle coordinates. Build and
   shipped-runtime versions can differ
   ([runtime contradiction](ARCHITECTURE.md#deployment-and-runtime-surface)).
3. **Split build truth.** The Ant distribution has 32 `jar` reactor entries plus
   the installer; the Gradle graph has 29 include declarations and 28 unique
   projects. A green `gradle build` is not a product build
   ([directory layout](ARCHITECTURE.md#directory-and-module-layout)).
4. **Retired framework generations.** ZK 3.6.3, XFire 1.2.6, Java EE 5-era
   APIs/descriptors, ActiveMQ 5.7, Log4j 1.2, and legacy drivers create support,
   security, and upgrade constraints
   ([EOL scan](ARCHITECTURE.md#eol-and-dead-dependency-scan)).
5. **Runtime drift.** CI, installer, sbt, and externally selected app servers
   pin or imply different runtime versions
   ([deployment surface](ARCHITECTURE.md#deployment-and-runtime-surface)).
6. **Weak and uneven enforcement.** The Ant graph is configured to execute base
   unit tests, but does not publish an explicit test-count baseline; required
   checks are not visible locally, Gradle tests execute no sources, integration
   tests are normally disabled, and there is no lint/format/architecture gate
   ([governance](ARCHITECTURE.md#governance-and-enforcement)).

### 2.4 Commands and current verification baseline

The canonical current commands remain those in the architecture inventory
([commands](ARCHITECTURE.md#commands-and-verification-inventory)):

| Purpose | Current command | Observed status on 2026-08-20 |
|---|---|---|
| Full product, no DB restore | `ant build -Dnodbrestore=true` | Not executed locally; system Ant absent. The reactor builds `tools` before `base` and is configured to execute base unit tests. |
| Full product with DB restore/migration | `ant build -Dnodbrestore=false` | Not executed locally; database-affecting and requires the complete reactor/environment. |
| Gradle module graph | `gradle build` | Not run as a whole. Targeted `:base`, `:client`, and `:serverRoot` compilation succeeded online on Java 17. |
| Base unit tests | `ant -f tools/build.xml && ant -f base/build.xml unit-tests` | The isolated spike omitted the `tools` prerequisite, so generated `lib/CCTools.jar` and database/app-server JARs were absent. The failure does not prove a defective base classpath. |
| Base Gradle tests | `gradle :base:test` | Build succeeded, but `compileTestJava` and `test` reported `NO-SOURCE`; zero tests ran. |
| Integration tests | `ant -f base/build.xml integration-tests -Dtest.performIntegrationTests=true` | Not run; requires a configured database and the base compile first. |
| Run desktop | `./utils/RUN_Adempiere.sh` | Not run; requires installed product and database configuration. |
| Run server | `./utils/RUN_Server2.sh` | Not run; requires installed product, database, and external app server. |

CI enforcement remains a **manual repository-administrator action** because
required checks and branch protection are remote repository settings, not files
an implementation branch can enforce.

## 3. Feasibility spike result and strategy

### 3.1 Spike scope and evidence

The spike was intentionally bounded to configuration, dependency restore,
targeted compilation, and the smallest test runners. It did not attempt to
install the product, restore a database, or repair the legacy build.

Observed:

- No committed lockfile or Gradle wrapper exists. `gradle --offline help`
  succeeds with ambient Gradle 8.10.2, but `:base:test --offline` cannot resolve
  the dependency graph.
- Online `gradle :base:test` resolves dependencies and compiles `base` on Java
  17, but reports `compileTestJava NO-SOURCE` and `test NO-SOURCE`.
- Online `gradle :client:build` and `gradle :serverRoot:build` compile and
  assemble successfully on Java 17, but their tests are also `NO-SOURCE`.
- `serverRoot` Javadoc reports illegal access to `com.sun.jndi.ldap`; the build
  continues because Javadoc failures are disabled.
- The bundled Ant 1.10.10 can be launched with the installed Zulu Java 11. The
  isolated `ant -f base/build.xml unit-tests` probe failed because it skipped
  the `tools` build that creates `lib/CCTools.jar` and other required outputs.
  The complete Ant reactor builds `tools` before `base` and is configured to
  execute the base JUnit launcher; the local spike did not measure its test
  count.
- No desktop client, web application, background processor, SOAP endpoint, or
  database migration was booted. Production/runtime oracle availability is
  **[UNVERIFIED]**.

### 3.2 Safety ladder

| Rung | Evidence required in this repository |
|---|---|
| **L0 - No net** | The component cannot build or run and no reliable behavioral oracle exists. Treat source/metadata as the specification or archive the component. |
| **L1 - Reversibility** | Small reversible commits, isolated flags/adapters, a documented smoke checklist, and recorded human review; no runnable automated behavior test. |
| **L2 - Characterization only** | Versioned seam snapshots or golden masters can be captured and diffed, manually if necessary, but the component cannot yet run those checks in CI. |
| **L3 - Partial automated gate** | Pinned/locked dependencies, green CI for the named build/test/contract subset, and a complete owned quarantine list for everything outside the gate. |
| **L4 - Full automated gate** | Green lint/format where adopted, unit, characterization/contract, integration, and relevant e2e/smoke checks in enforced CI, with no unnamed quarantine. |

### 3.3 Component strategy and testability milestones

| Component | Spike result | Strategy | Current rung | Testability Milestone | Target rung after milestone |
|---|---|---|---|---|---|
| Core model/process/workflow/accounting | Gradle compiles on Java 17; Gradle tests are `NO-SOURCE`; Ant compile fails before tests | **A - Freeze-then-lift.** The code nearly builds under both paths and existing tests can be wired rather than rewritten. | **L1**: reversible changes and source review only | **Phase 1**: JDK 17, wrapper/locks, successful build, and at least one meaningful unit test in CI | **L3**: core build/unit/characterization gate; integration and deployables explicitly quarantined |
| Swing client and POS core | Phase 2 boots the real Garden World login/role/menu path on JDK 21 and executes a deterministic process under a virtual display | **A - Freeze-then-lift** behind core seam tests and a scripted login/menu smoke path | **L3** | **Phase 2 milestone crossed locally**; PR CI remains the authoritative merge gate | **L3** |
| Background server and scheduler | Phase 2 executes an isolated scheduler fixture exactly once and proves transaction/context cleanup on JDK 21 | **A - Freeze-then-lift** with processor discovery/scheduler characterization | **L3** | **Phase 2 milestone crossed locally**; PR CI remains the authoritative merge gate | **L3** |
| Full Ant distribution and installer | The complete reactor is configured to build `tools` before `base`, but no local product archive or setup run was completed | **B - Beachhead-then-expand.** Make a minimal installable distribution first, then expand reactor coverage. | **L1** | **Phase 3**: installer builds, silent setup completes, product starts, and at least one DB-backed test passes in CI | **L3** |
| Database seed and XML migrations | Phase 2 restores the committed seed to disposable PostgreSQL 14.6, applies only `394lts`, and verifies `AD_System` release/version | **A - Freeze-then-lift** using seed checksum, schema inventory, and release-scoped migration contracts | **L3** | **Phase 2 milestone crossed locally**; Phase 3 expands this into the full distribution/installer gate | **L3** |
| ZK web UI/session boundary | Phase 5d drives BOTH renderings through a checksum-verified Chromium semantic oracle: the frozen ZK 3.6 product on Tomcat 9 and the functional ZK CE 10.3.0.1-jakarta `/webui-modern` slice on loopback Tomcat 10.1.59. 310 Java files reference `org.zkoss` after the migration added 15 ADempiere-owned CE compatibility sources | **B - Beachhead-then-expand** via a login/menu/read-only window walking skeleton, then incremental screen migration | **L3** for both the legacy oracle and the modern slice | **Phase 5d milestone crossed**: the modern slice boots on the target runtime and passes login, role, menu, and read-only-window tests, reproducing all eleven comparable frozen semantic facts and the zero-write database effect | **L3**, then L4 as route/e2e coverage expands |
| SOAP and legacy servlet applications | XFire and servlet descriptors are present; no endpoint booted; 11 route descriptors | **B - Beachhead-then-expand** using a contract-preserving adapter and per-route cutover | **L1** | **Phase 4**: one real SOAP operation and one route class pass replay/contract tests on the target stack | **L3** |
| Domain extension modules | Most Gradle modules compile transitively, but tests and metadata bindings are not exercised | Mixed: **A** for compilable modules, **B** for Ant-only UI/deployment pieces | **L1** | **Phase 3** for Gradle/Ant buildability; feature-specific testability follows Phases 4-6 | **L3** |

### 3.4 CI Milestone

**Phase 1 is the plan-wide reproducible-CI Milestone.** CI already exists and
the Ant build graph is configured to execute base unit tests, but test counts
are not published, required-check enforcement is unverified, and Gradle test
tasks execute no sources. Phase 1 records the existing Ant test baseline and
makes Gradle reproducible and meaningful: a pinned toolchain restores from
committed dependency locks, preserves the full 28-project Gradle build breadth,
and runs tests that have been proven to fail when behavior is deliberately
mutated.

Phase 2 adds a disposable Gradle-owned PostgreSQL restore/migration and
Swing/server smoke. Phase 3 expands that proven path to the full installed
product distribution, metadata graph, and installer. Making either workflow an
**enforced required status check** is a separate manual GitHub administrator
action. Until a human configures branch protection, CI runs but does not
necessarily block merges.

### 3.5 Residual-risk register

| ID | Component | Residual risk below L4 | Accepted until | Closing action |
|---|---|---|---|---|
| R1 | Core | Phase 2 smoke and Phase 3 DB-backed metadata validation protect selected runtime and dictionary seams; broader business behavior remains | Phase 5 | Expand representative document, accounting, and UI behavior coverage. |
| R2 | Swing/POS | Closed for the Phase 2 Garden World login/role/menu/process slice; broader operator workflows remain outside this phase | Closed in Phase 2; broader coverage Phase 5 | The semantic Xvfb smoke is now gated; expand coverage during the ZK/client modernization. |
| R3 | Background jobs | Exact-once scheduler execution and context/transaction cleanup are proven; production discovery breadth and observability remain | Phase 6 | Expand processor discovery and add operational metrics/alerts. |
| R4 | Web UI | Phase 5d replaces the 503 marker with a functional modern slice and crosses the Testability Milestone. Phase 5e adds proven fail-closed `/webui` cohort routing, concurrent identity isolation, and logout/timeout/container cleanup. Phase 5f merged at `83aeb8536` with both gates green: isolated generated Jakarta source/web trees, five source-native context WARs, the source-native `/timeline` route and exact static DSP compatibility resource, and independently reversible context routing for the closed 82-deployed/30-non-deployed scope, observed in run 33379849664 with 129 observations and zero vector failures across all six contexts. **No modern *business* write, document transition, process execution, report, or upload/download has ever been observed** - the modern runtime is proven only for login, role selection, menu and a read-only window (it does write `AD_Session` on login). `5g-1a` closed the other half of that gap: a frozen, domain-reviewed **legacy** write oracle now exists, so `5g-1b` scores the modern runtime against an answer it did not invent rather than against nothing. Production customizations, 13 non-reproducible legacy entries, the four `/*` reachability-only vectors, disabled `/mobile` and `/adempiere`, unowned `/admin`, JasperReports interactive web, and screen-level visual parity remain residuals. | Phase 5g sub-increments `5g-1a`..`5g-7` for write/process/report/upload/dashboard/POS/extension parity and screen-level visual parity; `5g-0` for the governance amendment that opens a named disposition per disabled or unowned context and `5g-7` for the `phase5g-web-parity-gate` that closes it; Phase 7 for full artifact reproducibility | Do not treat implemented packaging or database-neutral contracts as runtime route parity. Production owners must validate custom overlays. Both Phase 5f gates are green; the 25 `/wstore` JSP precompile rows remain runtime-pending because only three of those pages are reached by a route vector. `contract-only-runtime-observation-pending` is **not** an acceptable disposition for a Phase 5g acceptance criterion. |
| R5 | SOAP/servlets | Unknown consumers and undocumented route classes may break | Phase 4 | Inventory consumers, freeze WSDL/HTTP fixtures, and run parallel replay. |
| R6 | Database | Production size, custom schema, supported engines, and rollback windows are unknown | Phase 6 | Approve customer-specific migration runbook and rehearse on a sanitized copy. |
| R7 | Extension metadata | The fail-closed validator names 16 pre-existing active `AD_Process` bindings with absent or incompatible classes | Phase 7 | Obtain usage evidence, then correct or retire every row in `gradle/phase3/metadata-quarantine.tsv`; additions and stale quarantine rows fail CI. |
| R8 | CI governance | Checks may run without blocking merges. The two checks to mark required are now stably named `Contracts` and `Current-phase database smoke` | Human action after Phase 1 | Enable branch protection and required status checks on `develop`. |
| R9 | JBoss facet | Checked-in `jboss.jar` implements removed JDK API `java.security.acl.Group`, so the unused-by-Phase-3 facet cannot emit Java 21 bytecode | Phase 7 | Establish deployable usage, then replace the dependency or retire the facet; `gradle/phase3/quarantine.txt` must remain explicit meanwhile. |
| R10 | CI coverage | Six database-backed smokes (`phase3InstalledProduct`, `phase4InstalledApi`, `phase5bLegacyWebOracleSmoke`, `phase5cRollbackRehearsal`, `phase5dModernWebSmoke`, `phase5eCohortRoutingSmoke`) moved off the pull-request gate to the post-merge regression matrix, so a PR can regress the installed product, installed SOAP, the frozen legacy oracle, the 5c browser/rollback lane, the 5d modern slice, or 5e `/webui` cohort routing, isolation and session lifecycle while Lane 1 stays green. `phase5eCohortRoutingSmoke` joined the matrix when the current-phase slot advanced to Phase 5f, and it carries a recorded Playwright flake, so its post-merge signal is the only remaining check on cohort routing | Ongoing, under the containment rule | A red `Regression matrix` is a stop-the-line event: no further phase work merges until it is green or the failure is triaged and recorded. Owner @samqbush. If the matrix has not run green within a week, treat the affected phases as unverified rather than lit. See `docs/modernization/ci-topology.md`. |
| R11 | Phase 5g governance | The Phase 5g decomposition names exactly one oracle increment (`5g-1a`). ADR decisions 2 and 3 require every new behaviour class to be captured from the legacy runtime and forbid a parity increment from adding oracle facts, so `5g-1c`, `5g-1d`, `5g-1e`, `5g-1f`, `5g-2`, `5g-3`, `5g-4`, `5g-5`, `5g-6` and `5g-7` each currently have **no** increment that captures the answer they would be scored against | Before each affected increment begins | The increment immediately preceding an `unassigned` row names and merges its oracle increment - frozen and domain-reviewed - before that row starts. An increment marked `unassigned - blocking` in the Phase 5g decomposition table may not begin. Owner @samqbush. |
| R12 | Write-effect sentinel blind spot | The Phase 5g-1a whole-database sentinel (`measure-write-effect.py`) is a row **count** per table, so it cannot see an `UPDATE` to a table outside the nine-table keyed measurement scope, nor an insert paired with a delete that nets to zero within one step. Four frozen steps - role selection, both concurrency read steps, and the refused conflicting save - assert `[no-effect]`, and the refused save is the increment's headline concurrency fact, so the blind spot sits underneath a compared positive assertion | `5g-1b`, before the modern runtime is scored on any step that asserts `[no-effect]` | Contained, not closed. The marker carries the explicit value `no-keyed-change-in-scope\tno-row-count-delta-outside-ambient`, so the frozen assertion states what was measured rather than claiming nothing happened, and the limitation is recorded in `contracts/legacy-web-write-v1/README.md`. `5g-1b` either adds a content component to the sentinel - a per-table checksum, not `pg_stat_user_tables`, whose collector is asynchronous and would flake - or widens the keyed scope to cover the tables its own flow touches. Owner @samqbush. |

### 3.6 Oracle decision

Use this order:

1. A permitted running production/test instance, if one exists, for read-only
   record/replay of SOAP, HTTP, document transitions, process outputs, and SQL/
   migration inventory.
2. Existing seed databases plus current source, XML migrations, descriptors, and
   WSDL as the specification.
3. If neither is trustworthy or permitted, create a **self-frozen golden master**
   when each component first crosses its Testability Milestone.

A self-frozen master protects later refactors but does **not** prove the first
modernized run is correct. Human domain review must explicitly bless that first
snapshot.

## 4. Target architecture

### 4.1 Recommended target stack

| Layer | Target | Rationale |
|---|---|---|
| Architecture | Modular monolith with explicit module APIs and stable external adapters | Preserves shared transaction, metadata, workflow, and accounting semantics; avoids distributed transaction and duplicated-dictionary risk. |
| Java | JDK 21 LTS; retain Java 17 only as the Phase 1 testability bridge | JDK 21 is the final supported runtime target. Moving through Java 17 uses the toolchain already proven to compile core/client/server. |
| Build | Gradle wrapper 8.10.2 initially, dependency locking/verification, version catalog, reproducible archives | 8.10.2 configured this checkout successfully. Ant remains a temporary distribution adapter until parity is proven. |
| Tests | JUnit 5/JUnit Platform, Testcontainers or equivalent disposable PostgreSQL, approval/golden tests at external seams | Converts false-green `NO-SOURCE` tasks into executable gates and keeps behavior contracts stack-independent. |
| Runtime bridge | Tomcat 9 on JDK 21 for the first fully running baseline | Separates the JVM move from the `javax.*` to `jakarta.*` migration. |
| Final servlet runtime | Tomcat 10.1 and Jakarta Servlet APIs | Removes the long-term Java EE/`javax` container constraint. The exact supported patch is pinned in the phase branch. |
| Web UI | Upgrade in place to ZK CE 10.3.0.1-jakarta from the public ZK repository | Reuses hundreds of existing controllers and ZUL assets without commercial repository credentials. CE namespace replacements cover direct layout/download usage; polling and an ADempiere-owned portal adapter replace the old EE-only runtime behavior. |
| SOAP | Apache CXF 4.1.x/Jakarta EE 10 on an isolated Tomcat 10.1 API runtime in Phase 4, preserving current WSDL and wire behavior through a Tomcat 9 compatibility router | CXF 3.6.x is an EOL `javax` line without the JDK 21 support baseline required by Phase 2. The isolated API boundary avoids moving ZK or other legacy servlet applications before Phase 5. REST/OpenAPI is additive only after SOAP parity. |
| Database | PostgreSQL 16 reference platform; retain Oracle compatibility where contractually required | PostgreSQL 14.6 is the current CI pin and is near the end of a prudent modernization horizon. PostgreSQL 16 gives a supported target without choosing the newest major. |
| Logging | SLF4J 2 facade with a single maintained backend; bridge JUL during transition | Allows incremental migration from mixed JUL/Log4j generations without changing every call site at once. |
| Observability | OpenTelemetry traces plus Prometheus-compatible metrics around HTTP, process, workflow, scheduler, DB pool, and migrations | Makes old/new parallel runs comparable and exposes background-worker health. |
| Supply chain | Repository-managed dependencies, Gradle verification metadata, SBOM, pinned GitHub Actions | Replaces opaque checked-in JAR drift with auditable inputs. Proprietary/unpublished JARs remain in a documented internal repository until replaceable. |

### 4.2 What stays, changes, and leaves

| Area | Decision | Notes |
|---|---|---|
| Metadata-driven application dictionary | **Keep as-is, then validate** | It is the product's executable architecture and customization model. Add graph validation rather than replacing it. |
| `PO`, `MTable`, `SvrProcess`, workflow/document/accounting engines | **Wrap/adapt and incrementally refactor** | Preserve public behavior and transaction/state-machine semantics. Introduce module APIs and seam tests before internal cleanup. |
| Modular monolith | **Keep** | Service extraction is deferred unless a measured scaling or ownership constraint appears. |
| Java 11 | **Upgrade in place** | Java 17 bridge in Phase 1; JDK 21 final in Phase 2. |
| Ant distribution build | **Wrap/adapt, then remove** | Keep as an oracle until Gradle produces byte-for-byte/semantic-equivalent artifacts. |
| Ambient Gradle and unlocked dependencies | **Replace** | Commit wrapper, locks, verification metadata, and version catalog. |
| Root/module sbt paths | **Remove from the supported build** | Hard-coded workstation paths and incompatible Scala lines make it non-portable. Archive only if a stakeholder identifies an active use. |
| ZK web UI | **Upgrade in place behind a strangler seam** | No big-bang frontend rewrite. Cut over login/menu/read-only window first. |
| XFire | **Swap dependency through an adapter** | Preserve WSDL and response/fault behavior with CXF. |
| Legacy servlet/store/mobile/CM apps | **Leave in place until usage is verified** | Removal is forbidden on age alone. Each unused surface can be dropped only with consumer/usage evidence. |
| Checked-in JAR estate | **Incrementally replace** | Move published artifacts to repositories; isolate unpublished/vendor JARs with checksums, provenance, and ownership. |
| PostgreSQL/Oracle metadata model | **Keep** | Upgrade engine/runtime separately from application-dictionary semantics. |
| MySQL/MariaDB adapters | **Deferred decision** | Keep until stakeholders confirm support obligations and active deployments. |

### 4.3 Inline architectural decisions

#### ADR: Preserve the modular monolith

- **Context:** Core domain behavior depends on shared JDBC transactions,
  application-dictionary metadata, document/workflow state machines, and
  accounting posting.
- **Decision:** Keep one modular monolith and strengthen module boundaries
  (decision framework level 1: upgrade in place).
- **Alternatives considered:** Microservices and event-driven decomposition.
  They lose because they introduce distributed consistency and duplicate
  metadata before the current seams are testable.
- **Consequences:** Lower migration risk and preserved semantics; deployment
  remains coordinated until measured boundaries justify extraction.

#### ADR: Make Gradle authoritative through an Ant compatibility bridge

- **Context:** Ant packages the complete product; Gradle compiles a subset; sbt
  is non-portable.
- **Decision:** Pin Gradle 8.10.2, lock dependencies, wire real tests, and invoke
  or compare Ant packaging until Gradle reaches full parity (level 3:
  wrap/adapt, followed by removal).
- **Alternatives considered:** Rewrite every Ant target immediately or retain
  three build systems. Immediate replacement creates an untestable big bang;
  permanent coexistence preserves drift.
- **Consequences:** Temporary dual-build cost, but every removed Ant target has
  an explicit parity oracle.

#### ADR: Separate the JDK upgrade from the Jakarta migration

- **Context:** The tree mixes Jakarta-migration candidates with Java SE packages
  that retain the `javax` namespace:

  | Package family | Import occurrences | Migration ownership |
  |---|---:|---|
  | `javax.servlet` | 599 | Phase 5 namespace transformation/source migration |
  | `javax.xml` | 166 | Phase 4-5, scoped to APIs actually moved by JAX-WS/Jakarta |
  | `javax.mail` | 25 | Deferred beyond Phase 5 unless an independently gated web-runtime need is proven |
  | `javax.jms` | 23 | Deferred beyond Phase 5 unless an independently gated web-runtime need is proven |
  | `javax.swing` | 1,719 | **Never Jakarta-codemod; Java SE API** |
  | `javax.print` | 58 | **Never Jakarta-codemod; Java SE API** |
  | `javax.naming` | 42 | **Never blanket-codemod; Java SE naming API** |
  | `javax.sql` | 29 | **Never Jakarta-codemod; Java SE JDBC API** |

  `base` itself has servlet-coupled classes, so one source tree cannot be
  blindly converted while legacy Tomcat 9 and modern Tomcat 10.1 coexist.
- **Decision:** First run the complete product on JDK 21 and Tomcat 9. During
  coexistence, use a packaging-time namespace transformer to produce the
  Tomcat 10.1/Jakarta WAR while retaining the legacy `javax` WAR. After legacy
  retirement, migrate owned source packages with scoped OpenRewrite recipes and
  remove the transformer (level 1/3, upgrade plus adapter).
- **Alternatives considered:** One JDK+Jakarta+UI+API big bang. It loses because
  failure attribution and rollback would be impractical.
- **Consequences:** Tomcat 9 is a deliberate compatibility bridge, not the final
  runtime. The bridge has a named retirement phase.

#### ADR: Preserve SOAP while replacing XFire

- **Context:** External consumers are unknown and the WSDL/wire contract is more
  durable than the implementation framework.
- **Decision:** Introduce a CXF 4.1.x/Jakarta EE 10 message-mode adapter on an
  isolated Tomcat 10.1 API runtime using JDK 21. A Tomcat 9 compatibility
  router preserves both historical endpoint paths and routes operations
  incrementally between XFire and CXF. Publish approved static WSDLs and parse
  the existing XMLBeans document contracts explicitly rather than regenerating
  a consumer contract from annotations (level 2/3: dependency swap plus
  adapter).
- **Alternatives considered:** REST-only rewrite and direct XFire removal. Both
  require coordinated consumer migration that cannot be assumed.
- **Consequences:** Temporary dual runtime/endpoint implementation,
  installer-owned lifecycle and replay/diff infrastructure; consumers see no
  intentional contract or URL change. Phase 5 can absorb the proven API runtime
  without owning another SOAP framework upgrade.

#### ADR: Upgrade ZK incrementally instead of rewriting the frontend

- **Context:** ZK is old, but 298 Java files directly use its API and the UI is
  generated heavily from application-dictionary metadata.
- **Decision:** Upgrade through supported intermediate releases, place new
  session/tenant tests at the boundary, and cut over vertical slices (level 1/3:
  upgrade in place with strangler isolation).
- **Alternatives considered:** React/Angular/Vue rewrite. It loses because it
  duplicates dynamic windows, access rules, process dispatch, and localization
  before those behaviors are characterized.
- **Consequences:** A long compatibility phase and possible temporary dual web
  deployments. ZK licensing/support must be confirmed by stakeholders.

#### ADR: Treat dependency provenance as a product feature

- **Context:** Gradle coordinates and 406 tracked JARs disagree about effective
  runtime versions.
- **Decision:** Move published dependencies to locked repository resolution,
  record checksums/provenance for all retained binaries, generate an SBOM, and
  block unexplained artifacts (level 1/2: upgrade and swap).
- **Alternatives considered:** Continue checking in all JARs. It loses because
  it cannot provide coherent upgrades, provenance, or reliable vulnerability
  response.
- **Consequences:** Some proprietary or abandoned artifacts may need internal
  hosting or source-compatible replacements.

#### ADR: Upgrade PostgreSQL through parallel data migration

- **Context:** The database contains business data and executable metadata.
  A major upgrade cannot be treated as a CI image-tag change.
- **Decision:** For durable environments, restore a verified logical backup into
  a parallel PostgreSQL 16 instance, run schema/metadata/behavior validation,
  then cut over with the old instance retained for rollback. CI/demo data is
  explicitly disposable and re-seeded (level 1: upgrade in place).
- **Alternatives considered:** Direct image/tag replacement over existing
  storage. It loses because rollback and on-disk compatibility are not assured.
- **Consequences:** Requires temporary duplicate storage and a measured cutover
  window; custom databases need rehearsals.

## 5. Per-feature migration analysis

### 5.1 Persistence, metadata, and extension loading

- **Current implementation:** `MTable` resolves metadata to generated or custom
  `PO` implementations; `PO.save()` controls hooks, validators, SQL,
  transactions, translations, and workflow callbacks
  ([architecture](ARCHITECTURE.md#1-metadata-driven-persistence-and-extension)).
- **Migration strategy:** Strategy A, incremental refactor. Freeze model
  resolution, save/rollback, tenant access, validator ordering, and generated
  model contracts before dependency cleanup.
- **Testability:** Phase 1, target L3; Phase 3 raises DB-backed metadata
  validation toward L4.
- **Dependencies/coupling:** Database dictionary, generated sources, `Trx`,
  validators, security context, migrations, and every presentation surface.
- **Effort:** **L.** The core can compile now, but broad reflective behavior
  requires representative metadata fixtures.
- **Risk:** Runtime-only class binding, tenant leakage, and generated/live schema
  drift.
- **Acceptance:** Fixed metadata fixtures resolve the same classes; save events
  fire in the same order; failed hooks roll back to the same state; generated
  model diff is empty after replaying migrations.

### 5.2 Process, workflow, documents, and accounting

- **Current implementation:** Metadata dispatch selects Java/process/report/
  workflow behavior; document transitions and accounting facts are polymorphic
  state machines
  ([architecture](ARCHITECTURE.md#2-document-workflow-process-and-accounting-execution)).
- **Migration strategy:** Strategy A, leave public behavior in place and refactor
  behind seam contracts.
- **Testability:** Phase 1 for pure state transitions; Phase 3 for database-backed
  workflows/posting. L3 until multi-schema/currency integration coverage is
  green.
- **Dependencies/coupling:** `AD_Process`, `MWFActivity`, `DocumentEngine`,
  transaction context, period controls, generated records, and posting tables.
- **Effort:** **XL.** This is the highest business-risk domain.
- **Risk:** Legal but inconsistent states, duplicate side effects, unbalanced
  facts, and rollback divergence.
- **Acceptance:** Golden transition matrices, process outputs, generated
  document IDs, and accounting facts match the chosen oracle for representative
  order/invoice/payment/shipment flows.

### 5.3 Swing desktop and POS

- **Current implementation:** Java Swing desktop startup and menus share the
  core engine; POS has Swing and ZK variants
  ([entry points](ARCHITECTURE.md#entry-points-and-deployable-applications)).
- **Migration strategy:** Strategy A, lift to JDK 21 with the core; no UI rewrite.
- **Testability:** Phase 2, L3. Compilation already succeeds, but startup and
  workflows are unverified.
- **Dependencies/coupling:** Core, JasperReports, look-and-feel libraries,
  database preferences, role/client/org context.
- **Effort:** **M.** Runtime/module access and old UI libraries are the main
  constraints.
- **Risk:** Rendering regressions, removed JDK internals, and environment-specific
  startup behavior.
- **Acceptance:** Scripted startup, login, role selection, menu load, and one
  read/write process pass on JDK 21 with no new module-access exceptions.

### 5.4 Background processors and scheduler

- **Current implementation:** Active database rows create worker threads for
  accounting, requests, workflows, alerts, schedulers, LDAP, import, and
  projects
  ([architecture](ARCHITECTURE.md#3-web-session-and-background-server-lifecycle)).
- **Migration strategy:** Strategy A, incremental refactor and observability
  wrapper.
- **Testability:** Phase 2, L3; Phase 6 adds production-parity metrics and
  duplicate-work alarms.
- **Dependencies/coupling:** Database locks, tenant/user/role context,
  `ProcessBuilder`, named transactions, mail/LDAP, and worker timing.
- **Effort:** **L.**
- **Risk:** Duplicate execution, context leakage, silent worker death, and
  schedule drift.
- **Acceptance:** Processor discovery is deterministic; a scheduled fixture runs
  once; transaction rollback leaves no partial records; metrics expose lag,
  success/failure, duration, and last heartbeat.

### 5.5 SOAP and servlet integration surfaces

- **Current implementation:** Four XFire services plus multiple legacy servlet
  applications and route descriptors
  ([architecture](ARCHITECTURE.md#apis-and-integration-surfaces)).
- **Migration strategy:** Strategy B, strangler adapter. Freeze WSDL/HTTP
  fixtures, deploy CXF/Jakarta edge, replay requests, and cut over per operation.
- **Testability:** Phase 4, L3; L4 when all active consumer operations and route
  classes are replayed in CI.
- **Dependencies/coupling:** Login/security context, model/process APIs, XML
  serialization, servlet session behavior, external consumers.
- **Effort:** **XL** until consumer inventory is known.
- **Risk:** Namespace, fault, date/number, authentication, or anonymous-route
  drift.
- **Acceptance:** WSDL is equivalent; canonical request/response/fault fixtures
  match after normalization; anonymous, authenticated, service, infra, and
  callback route classes each have an explicit passing rule.

### 5.6 ZK web UI and session/tenant context

- **Current implementation:** Stateful ZK events copy HTTP/ZK session properties
  into thread context around each execution
  ([architecture](ARCHITECTURE.md#3-web-session-and-background-server-lifecycle)).
- **Migration strategy:** Strategy B, walking skeleton and strangler. Upgrade
  login, role selection, menu, and one read-only dynamic window first, then
  process/write flows.
- **Testability:** Phase 5, L3, with a self-frozen master if no permitted running
  oracle exists. L4 is a later coverage target.
- **Dependencies/coupling:** ZUL assets, 298 `org.zkoss`-referencing Java files,
  descriptors, session listener, core metadata, security/role context.
- **Effort:** **XL.**
- **Risk:** Tenant leakage, event ordering changes, component API removals,
  rendering regressions, and licensing constraints.
- **Acceptance:** Login/role/menu/window fixtures match; parallel requests never
  leak context; every event cleans thread state; old/new screenshots and
  semantic DOM/state snapshots are reviewed for representative workflows.

### 5.7 Database, migrations, installer, and release

- **Current implementation:** PostgreSQL/Oracle seeds, ordered XML migrations,
  application-dictionary synchronization, installer assembly, and external app
  server setup
  ([architecture](ARCHITECTURE.md#data-and-storage),
  [runtime surface](ARCHITECTURE.md#deployment-and-runtime-surface)).
- **Migration strategy:** Strategy A for migration semantics; Strategy B for
  reproducible Gradle-first packaging.
- **Testability:** Phase 3, L3; Phase 6 reaches L4 for supported PostgreSQL
  upgrade and rollback scenarios.
- **Dependencies/coupling:** Seed archives, 978 historical/current XML files in
  the repository, the `migration/build.properties` release selector, generated
  models, version checks, and external Tomcat/Oracle environments. The current
  `394lts` distribution selects 249 files rather than replaying all history.
- **Effort:** **XL.**
- **Risk:** irreversible data changes, product/database version mismatch,
  database-specific SQL divergence, and package omissions.
- **Acceptance:** Clean seed restore and release-scoped migration replay are repeatable;
  schema/model/metadata checks pass; archives contain the approved manifest;
  parallel PostgreSQL upgrade and rollback rehearsals meet data and downtime
  objectives.

## 6. Phased implementation plan

**Regime-aware gate:** A phase is not complete until its exit criteria are
executed and recorded. Lit components advance on green CI. Dark components
advance on their achievable safety rung: captured seam contracts, reversible
changes, passed smoke checklists, and recorded review. No component is blocked
on an automated test before its own Testability Milestone.

### Phase 1: Reproducible core safety gate (T-shirt size: L)

**Goal:** Cross the core Gradle Testability Milestone while preserving the
existing Ant unit-test gate and turning CI into a reproducible, observable
partial gate.

**Status:** Implemented on `phase-1-reproducible-core-gate`; CI enforcement
remains residual risk R8 until enabled by a repository administrator.

**Regime:** Core has transitioned from pre-testability ("dark") to
post-testability ("lit"); web/API/install remain dark.

**Safety rung:** L1 -> L3. Residual risk: deployment, database integration, and
Ant-only modules remain quarantined until Phases 2-3.

**Prerequisites:** None.

**Duration estimate:** 2-4 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 1.1 | Commit Gradle wrapper 8.10.2, pin Java 17 toolchains, move Gradle build/publish workflows to Java 17 in the same PR, and compile published artifacts with `options.release = 11` | Build/publication | - |
| 1.2 | Add dependency locking, verification metadata, repository policy, remove `mavenLocal()` from normal resolution, and generate a dependency/SBOM inventory | Supply chain | 1.1 |
| 1.3 | Reconcile Gradle coordinates with checked-in runtime JARs; classify each JAR as repository-resolved, internally hosted, source-built, or quarantined | Core/build | 1.2 |
| 1.4 | Preserve `org.adempiere.test` as a published test-support main artifact; compile `base/test/src` and `org.adempiere.test/src/test/java` together in `:base`'s test source set to avoid a project dependency cycle; configure JUnit Platform to exclude `IntegrationTest`; classify/tag every test so none is unintentionally untagged; do not register the dormant `base/test` project | Core tests | 1.1 |
| 1.4a | Inventory published Maven coordinates/POMs and release artifact names from `publish_with_gradle.yml` and `release.yml`; freeze them as compatibility seams | Release contracts | 1.1 |
| 1.5 | Record the current Ant test count, document/script the required `tools` prerequisite, then run `ant -f tools/build.xml && ant -f base/build.xml unit-tests`; do not add direct `tools/lib/*.jar` entries to the base classpath | Ant baseline | 1.3 |
| 1.6 | Add seam tests for `DocumentEngine`, `TimeUtil`, model resolution, transaction rollback, and validator ordering | Core | 1.4 |
| 1.7 | Prove the new Gradle net has teeth: deliberately mutate one transition/return value, record the red Gradle test, revert, and record green | Core | 1.6 |
| 1.8 | Replace ambient Gradle CI with wrapper commands, publish test counts/results, and enforce non-zero tests only for modules listed in a committed test-enabled-module manifest | CI | 1.4-1.6 |
| 1.9 | Human action: enable branch protection and make the Phase 1 workflow a required status check on `develop` | Governance | 1.8 |

#### Risks & mitigations

- **Risk:** Locking resolves versions that differ from shipped Ant JARs.
  **Mitigation:** Classify and compare first; do not delete a binary until
  package parity is proven.
- **Risk:** Existing tests assume a live database. **Mitigation:** Separate
  tagged unit tests from integration tests and start with pure state/utility
  seams.
- **Risk:** A green Gradle gate still omits deployables. **Mitigation:** Name the
  quarantine and keep Ant as a required compatibility bridge.

#### Decisions made

- The post-phase Gradle target is `./gradlew build`, preserving all 28 unique
  Gradle projects currently covered by CI. It remains a **partial product gate**
  because Ant-only deployables are outside the Gradle graph.
- Quarantined from the Phase 1 gate: `zkwebui`,
  `org.adempiere.webservice`, `serverApps`, `webStore`, `webCM`,
  `org.compiere.mobile`, `JasperReportsWebApp`, `sqlj`,
  `com.kkalice.adempiere.migrate`, `looks`, installer/setup/database restore,
  and the Ant-packaged ZK/package outputs for manufacturing, warehouse, HR/
  payroll, POS, production, distribution, freight, cashflow, finance, loan,
  time/attendance, store, authentication, and notifier modules.
- The topology inventory is bidirectional: it also records Gradle projects not
  represented as standalone Ant reactor entries (`install`,
  `org.adempiere.test`, `org.adempiere.project`, `org.adempiere.request`, and
  `org.adempiere.crm`) and whether Ant compiles their sources indirectly.
- sbt is dropped from the supported gate; its files are not deleted until active
  use is checked.
- The Gradle execution JDK moves to 17, but published bytecode remains Java 11
  (`options.release = 11`) until the Phase 2 runtime cutover. No production
  behavior, schema, dependency major, or runtime logic changes in this phase.
- **Hazard red-team:** H1 fired (full Ant-only quarantine and all Gradle projects
  inventoried); H2 cleared; H3 fired because the Gradle build JDK changes and is
  closed by moving build/publish workflows in lockstep plus asserting Java 11
  class-file compatibility; H4/H5/H6 cleared; H7 applies (merge Phase 1 to
  `develop` before Phase 2); H8 applies to command/topology docs and must update
  this plan, `ARCHITECTURE.md`, README, and Copilot instructions.

#### Verification & Exit Criteria (Definition of Done)

- [x] `./gradlew build` succeeds across all 28 unique Gradle projects on the
      pinned Java 17 toolchain using strict dependency verification and only
      versions present in committed lockfiles.
- [x] Regenerating dependency locks produces no diff; unverified artifacts or
      `mavenLocal()` resolution fail the gate.
- [x] The existing Ant unit-test count is recorded; CI reports Gradle test counts,
      and a zero-test result fails for modules named in the committed
      test-enabled-module manifest.
- [x] `ant -f tools/build.xml && ant -f base/build.xml unit-tests` reaches and
      executes the unit test runner on the documented Java 11 bridge without
      adding a divergent base classpath.
- [x] JUnit Platform excludes `IntegrationTest`; every test class is explicitly
      tagged/classified; no `:base`/`:org.adempiere.test` project cycle exists.
- [x] A deliberate behavior mutation made the new Gradle seam test red; after
      revert the same test is green.
- [x] Published artifacts built on Java 17 retain Java 11 class-file compatibility,
      and build/publish workflows use the same pinned build JDK.
- [x] The complete Phase 1 quarantine and effective dependency inventory are
      committed.
- [x] No behavior, database schema, or runtime logic changed.
- [ ] CI workflow is authored and green. Branch-protection enforcement is either
      enabled by a human or recorded as outstanding residual risk R8.

### Phase 2: JDK 21 runtime walking skeleton (T-shirt size: L)

**Goal:** Run the core, Swing client, and background server on JDK 21 while
retaining Tomcat 9/`javax` as an explicit compatibility bridge.

**Status:** Implementation and local verification complete on
`phase-2-jdk21-runtime`. Remote PR CI, required-check enforcement, and the final
unused release identifier remain open; see
[`docs/modernization/phase-2-evidence.md`](docs/modernization/phase-2-evidence.md).

**Regime:** Core remains lit; Swing and background server cross their Testability
Milestones; Ant-only web/API/install remain dark.

**Safety rung:** L3. Residual risk: full distribution and web/API behavior are
not yet gated.

**Prerequisites:** Phase 1 merged to `develop`.

**Duration estimate:** 3-5 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 2.1 | Run OpenRewrite/static scans for JDK 21 removals and illegal JDK-internal API use; enumerate each fix | Core/server/client | Phase 1 |
| 2.1a | Grep the full tree for `java.version`, `java.specification.version`, and literal `JAVA_HOME` gates; update `Login.isJavaOK`, `ConfigVMOpenJDK`, `ConfigVMOracle`, `ConfigVMMacOS`, `KeyStoreMgt` keytool selection, and root template substitution to feature-based JDK 21-compatible checks | Core/installer | 2.1 |
| 2.2 | Move Gradle toolchain, all build/publish/release CI `setup-java` steps, installer Java home/options, launch scripts, and runtime documentation to JDK 21 in one change set; raise Gradle `options.release` to 21 only under a new release version with an announced minimum JDK; inventory 23 Ant `javac target=11` occurrences, the `tools`/`sqlj` source pins, Javadoc source pin, and XMLBeans `javasource=11` as Phase 3 carry-over | Runtime/publication | 2.1a |
| 2.3 | Keep Tomcat 9 as the pinned bridge and verify the empty container starts on JDK 21 with the documented ADempiere JVM module flags; application artifact deployment remains Phase 3 | Runtime | 2.2 |
| 2.4 | Replace `com.sun.jndi.ldap` usage with supported APIs or isolate it behind a tested adapter | LDAP/server | 2.1 |
| 2.5 | Add a Gradle smoke-runtime task that assembles a temporary `ADEMPIERE_HOME`, expands/restores the committed PostgreSQL seed, records its `AD_System.Version`, applies the release-scoped migration set through `MigrationLoader`, verifies the resulting application/database version match, and writes test-only environment properties without the Ant installer | Runtime beachhead | 2.2 |
| 2.6 | Boot Swing from the smoke runtime (under a virtual display in CI) and automate login, role selection, menu load, and one process | Client | 2.5 |
| 2.7 | Boot background services from the smoke runtime and test processor discovery, one scheduler execution, context cleanup, and rollback | Server | 2.5 |
| 2.8 | Add smoke artifacts, logs, and timing to CI; fail on module-access warnings newly introduced by the phase | CI | 2.4-2.7 |

#### Risks & mitigations

- **Risk:** Build bytecode moves while installer/runtime stays on Java 11.
  **Mitigation:** H3 lockstep task 2.2 and a real startup smoke, not compile only.
- **Risk:** New module boundaries reveal reflective access failures.
  **Mitigation:** Inventory every `--add-opens`/`--add-exports`; replace internals
  rather than silently growing flags.
- **Risk:** Background smoke causes duplicate work. **Mitigation:** Use isolated
  test metadata/database and one deterministic scheduler fixture.

#### Decisions made

- JDK 21 is the final runtime; Java 17 is only the Phase 1 bridge.
- Tomcat 9 remains deliberately in Phase 2 to avoid combining JDK and Jakarta
  migrations.
- `javax.*` codemods are deferred to Phases 4-5, not dropped.
- Phase 2 owns JDK string/version gates in both core and installer code even
  though full installer packaging remains dark until Phase 3.
- The seed smoke always applies the release-scoped migration set and verifies
  `AD_System.Version`; it never assumes the committed seed is already current.
- PostgreSQL 14.6 and the `394lts` migration path crossed a disposable-runtime
  testability milestone in Phase 2. Phase 3 retains ownership of installer,
  packaged-distribution, metadata-graph, and deployed-application parity.
- The Ant reactor keeps Java 11 bytecode for its Phase 3 carry-over surfaces by
  using the JDK 21 compiler's `--release 11` API view where removed JDK APIs
  would otherwise prevent compilation. Gradle publications are Java 21.
- Mockito remains 4.11 in Gradle and the checked-in Ant test runtime; Byte Buddy
  is aligned at 1.15.4 so both paths execute on class-file major 65.
- Published Gradle artifacts move to Java 21 bytecode only under a new release
  version whose POM/release notes declare JDK 21; no existing artifact version
  is overwritten with a higher class-file level.
- **Hazard red-team:** H1 cleared (no dependency family removed); H2 fired for
  the Java major and requires explicit internal/removal recipes; H3 fired and is
  task 2.2; H4/H5/H6 cleared; H7 applies; H8 fired because commands/runtime pins
  change and all executable docs update in this PR.

#### Verification & Exit Criteria (Definition of Done)

- [x] `./gradlew build` is green locally across all 28 unique Gradle projects
      on JDK 21. The PR workflow remains the authoritative merge gate.
- [x] The Gradle smoke-runtime task creates a temporary runnable layout and
      restores the committed seed without invoking the Ant installer.
- [x] Swing login/menu/process smoke passes against that disposable database.
- [x] Background processor/scheduler smoke passes exactly once and verifies
      transaction/context cleanup.
- [x] Empty Tomcat 9.0.121 starts on JDK 21 with the documented JVM module flags.
      Application artifact deployment is explicitly deferred to Phase 3.
- [x] All Gradle, CI, installer-template, launch-script, version-allowlist, and
      runtime-documentation pins owned by Phase 2 accept/use JDK 21. Remaining
      Ant/Javadoc/XMLBeans source-target pins are enumerated and owned by Phase 3.
- [ ] Candidate publications use class-file version 65 and POM metadata declares
      JDK 21; production publication remains blocked until a stakeholder supplies
      an unused release identifier.
- [x] JDK-internal API inventory is empty or each remaining item has an owner,
      adapter, and closing phase.
- [x] Core seam snapshots match Phase 1; the full Gradle gate and Ant
      no-database-restore reactor are green on JDK 21.
- [x] The Phase 2 PR workflow is green.
- [ ] A repository administrator has made the checks required on `develop`
      (residual risk R8 until completed).

### Phase 3: Installed distribution and metadata testability (T-shirt size: XL)

**Status:** Complete. Merged to `develop` as
`eb1953d091836db59fabb153ccde41d8e07b7cf1`; all five PR checks passed.
Required-check enforcement remains residual risk R8.

**Goal:** Cross the installed full-product and extension Testability Milestones
on the JDK 21/Tomcat 9 bridge, building on Phase 2's disposable migration proof.

**Regime:** Full product transitions from dark to lit.

**Safety rung:** L3. Residual risk: external SOAP/web consumers and Jakarta/ZK
final stack are not yet migrated.

**Prerequisites:** Phase 2 merged to `develop`.

**Duration estimate:** 4-6 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 3.1 | Preserve the Phase 2 JDK 21 Ant reactor baseline, verify all 32 `jar` entries and installer outputs, and reconcile them bidirectionally with 28 unique Gradle projects | Distribution | Phase 2 |
| 3.2 | Add Gradle lifecycle tasks that invoke and inventory the Ant distribution without pretending parity | Build bridge | 3.1 |
| 3.3 | Promote the Phase 2 PostgreSQL 14.6 seed/`394lts` replay into the full distribution and installer path, retaining its version and migration-manifest evidence | Database | 3.1 |
| 3.4 | Add metadata graph validation for model classes, processes, validators, workflows, entity packages, and generated models | Metadata | 3.3 |
| 3.5 | Run silent setup, produce ZIP/TAR, start the installed product, and exercise health/login/process/database seams | Installer/runtime | 3.2-3.4 |
| 3.6 | Generate artifact manifests and compare Ant output against an approved baseline | Packaging | 3.5 |
| 3.7 | Expand CI to run the full no-DB path on every PR and the restore/release-scoped-migration path when migrations/build/runtime inputs change; include `.github/actions/**` as database-gate inputs, not only workflow YAML | CI | 3.3-3.6 |

**3.7 is implemented.** CI runs two lanes. Every pull request runs the complete
database-neutral path as a single `Contracts` job plus the current phase's
database-backed smoke; the remaining database-backed smokes run post-merge on
`push` to `develop`, nightly, and on demand. Because the phase gates are already
chained inside Gradle, the previous per-phase jobs re-executed one another's
work: 600 scheduled task executions across six jobs became 201 in one, with
verified task-set equality. Topology, the coverage proof, the guards, and the
containment rule for the post-merge lane are in
`docs/modernization/ci-topology.md`. The reduced pre-merge coverage is
registered as residual risk R10.

#### Risks & mitigations

- **Risk:** Manual reactor omissions create false package success.
  **Mitigation:** Compare Ant modules, Gradle modules, archives, and installer
  contents as machine-readable manifests.
- **Risk:** Path filtering skips a migration-sensitive change.
  **Mitigation:** Replace hand-maintained filters with generated dependency/input
  sets or run the DB gate on every relevant PR until evidence supports narrowing.
- **Risk:** Seed/migration replay mutates durable data.
  **Mitigation:** The exact local `adempiere_phase3_ci` database and role must
  carry immutable Phase 3 ownership markers before any reset or cleanup.

#### Decisions made

- Ant remains authoritative through Phase 3; Gradle is the orchestrator and
  eventual replacement.
- The database gate uses PostgreSQL 14.6 initially to isolate product
  resurrection from the database-major move.
- Integration tests no longer default silently off in CI.
- The Phase 3 acceptance bridge is PostgreSQL 14.6 and checksum-verified Tomcat
  9.0.121. Oracle, MySQL/MariaDB, WildFly, and Jetty remain compatibility
  surfaces but are not Phase 3 acceptance gates.
- `jbossfacet` is explicitly quarantined because its checked-in JBoss API
  depends on the JDK-removed `java.security.acl.Group`; `glassfishfacet` remains
  compiled through `base`.
- The metadata gate checks active records and generated-model bindings. Sixteen
  pre-existing active process bindings are named in
  `gradle/phase3/metadata-quarantine.tsv`; new findings and stale quarantine
  entries fail closed. This is an explicit L3 residual, not clean L4 metadata.
- SOAP wire behavior and semantic ZK session/UI behavior are deferred to Phases
  4 and 5 respectively. Their current WARs must deploy and initialize in Phase
  3.
- Context reachability requires HTTP 2xx/3xx except for the deployment-only
  `ADInterface` base path, whose explicit 404 policy does not claim SOAP
  behavior. The aggregate captures evidence before marker-guarded cleanup of
  the Phase 3 database and role.
- **Hazard red-team:** H1 fired (all 32 Ant `jar` entries, installer, and 28
  unique Gradle projects inventoried bidirectionally; the JBoss facet and 16
  metadata bindings are explicit quarantines);
  H2/H3 cleared because no new major is
  introduced; H4 cleared because routes are not cut over; H5 applies only to
  ephemeral CI and the explicit destructive reset/re-seed is approved; H6
  cleared; H7 applies; H8 fired because build topology/commands change.

#### Verification & Exit Criteria (Definition of Done)

- [x] `ant build -Dnodbrestore=true` succeeds on JDK 21 through
      `phase3NoDatabaseDistribution`.
- [x] `ant build -Dnodbrestore=false` succeeds against a disposable PostgreSQL
      14.6 service and replays the release-scoped migration set.
- [x] The authored CI installed-product lane runs its three meaningful
      DB-backed smoke tests and metadata test without skips.
- [x] Silent setup produces runnable archives; installed product starts and
      passes the smoke checklist.
- [x] Destructive reset and final cleanup refuse unmarked database objects, and
      context HTTP status policy fails closed without pulling SOAP semantics
      into Phase 3.
- [x] Metadata graph validation is fail-closed; generated-model bindings are
      clean and the 16 pre-existing process-binding residuals are explicitly
      quarantined with a Phase 7 closing action.
- [x] The normalized 396-file installed manifest and topology inventory account
      for every approved deployable; the JBoss facet quarantine is named.
- [x] Phase 1-2 characterization fixtures remain equivalent locally: the
      canonical Gradle build is green and the Phase 2 smoke executes three
      tests with no skips.

### Phase 4: Contract-preserving API and edge modernization (T-shirt size: XL)

**Goal:** Replace XFire and establish explicit security/routing contracts without
breaking active integrations.

**Status:** Complete and merged to `develop` as
`8c0ca4c1d6b35a5f366d6dd2150ed3bb27bc2a89`. The
oracle/seam foundation merged to `develop` as
`f91b0ef2ccfc03d94f3688d6e271b0480bcc9cdf`. The live XFire pre-flight is green
for all four services; the 11 descriptors and original 114 mappings are
inventoried alongside 33 WSDL
operations. Representative four-service contracts are frozen, replayed in CI,
and mutation-sensitive. The XFire-free service boundary is the 29th Gradle
project while Ant continues to own the legacy WAR. Source-controlled route and
XFire-removal inventories now fail on drift. The frozen baseline covers all 33
operations, and `operation-scenarios.tsv` gives every operation an explicit
success, fault-only, or owned-residual decision. All 11 required
valid-credential Model/POS and state-isolated mutation scenarios are frozen and
pass direct CXF replay; create, update, delete, and process execution also
compare explicit database deltas. The
transport-neutral extraction is complete for all 33 operations: an explicit
business dispatcher binds the fail-closed registry to all four business seams,
and the 21-operation `ADService` retains one atomic state object per session.
Meaningful neutral tests plus the pre-removal isolated adapter suite cover exact
binding, fault translation, response identity, and concurrent session isolation.
The first isolated CXF runtime slice now also builds a distinct modern WAR with
strictly locked CXF 4.1.8 dependencies, boots checksum-verified Tomcat 10.1.59
on loopback, serves the four approved WSDL byte streams, dispatches the frozen
`getVersion` success, and fails closed for unknown WSDLs. Its archive gate
rejects XFire and removes the seven servlet-linked classes from the packaged
core. A marker-owned PostgreSQL 14.6 lifecycle now rebuilds the WAR after Ant,
replays all 33 frozen operation baselines and 11 additional scenarios directly
against CXF, and orders database cleanup after replay. The Tomcat 9
compatibility router now preserves both historical URLs, replays all 33
baselines through both forms, and first passed a live dual-runtime proof for
request-scoped flags, invalid-value fallback, and atomic `ADService` session
affinity. Migration 10030 expands the physical
`AD_SysConfig.Name` column to its existing 100-character dictionary length so
the approved 60-character operation keys are representable. Installer
post-install integration now passes the canonical `phase4InstalledApi` lifecycle
through both historical paths. The same lifecycle now embeds the distinct
modern WAR, portable Tomcat 10.1 runtime, configuration, and executable
launchers in the existing 394LTS ZIP/TAR artifacts; it verifies checksums, exact
WAR copies, the unconfigured environment template, and absence of
`AdempiereEnv.properties`. Task 4.4 is complete. Complete cutover evidence,
rollback, and XFire retirement then completed: each request-scoped service and
the atomic `ADService` unit returned to XFire through both historical paths
without a restart; after that evidence was recorded, the router became CXF-only
and active XFire source, descriptors, checked-in JARs, adapter tests, and
packaged artifacts were removed. The final installed lifecycle, no-database
distribution, targeted gates, and full Gradle build passed before merge.

**Regime:** The Phase 3 XFire deployment is lit but its operation behavior must
pass a Phase 4 oracle pre-flight. The new CXF/Jakarta adapter transitions from
dark to lit.

**Safety rung:** L3. The complete automated seam gate is green, but unknown
external consumer ownership and observation timing prevent an L4 claim.

**Prerequisites:** Phase 3 merged to `develop`. All four SOAP services and all
11 source descriptors are treated as active until evidence proves otherwise.
The approved oracle is repository and installed Phase 3 evidence only.

**Duration estimate:** 4-8 sprints.

#### Route-class artifact

| Class | Examples to inventory | Required target rule |
|---|---|---|
| Anonymous/public | Login, registration, static assets, public store/CM content | Explicit allowlist; no wildcard permit-all |
| End-user authenticated | ZK events, desktop-served routes, store checkout/account, mobile windows/processes | Existing role/client/org authorization preserved |
| Service-to-service | SOAP operations, import/replication, machine integrations | Narrow operation/scope credentials and audit identity |
| Infrastructure | Status, readiness, monitor, metrics | Explicit network/auth policy; scraping remains functional |
| Callback/webhook | Payment/mail/external callbacks, if active | Signature/credential verification and replay protection |

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 4.1 | Boot the unmodified Phase 3 XFire WAR, fetch all four live WSDLs, execute one round trip per service, and generate the complete SOAP/11-descriptor route inventory; stop for an explicit oracle decision if any service fails | Edge/API | Phase 3 |
| 4.2 | Capture normalized WSDL, XML request/response/fault, auth, HTTP status/header/charset, SOAPAction, scope, and state fixtures; prove a deliberate mutation fails the contract harness | Contracts | 4.1 |
| 4.3 | Promote `org.adempiere.webservice` to a shared Gradle/Ant surface and extract a transport-neutral service/fault seam behind byte-contract-compatible XFire wrappers | Build/service seam | 4.2 |
| 4.4 | Build a CXF 4.1.x/Jakarta message-mode adapter on checksum-pinned Tomcat 10.1/JDK 21 and a Tomcat 9 compatibility router at the historical WAR path; wire both runtimes into the installer | SOAP/runtime | 4.3 |
| 4.5 | Cross the SOAP Testability Milestone through the unchanged public URL and prove shared-core `javax.servlet` classes are not linked on SOAP paths | SOAP/runtime | 4.4 |
| 4.6 | Inventory all five route classes; behavior-test SOAP/shared security now and assign every non-SOAP route a named Phase 5 behavioral test | Security/edge | 4.1, 4.5 |
| 4.7 | Replay every operation, using parallel read diffs and independent disposable restores for mutating operations; cut over request-scoped operations incrementally behind fail-closed `MSysConfig` flags, then move the 21-operation session-scoped `ADService` atomically with per-session runtime affinity | API | 4.5-4.6 |
| 4.8 | Rehearse per-operation rollback, remove XFire from active packaging only after complete parity, and prove the installed product still serves both historical URL forms | API/distribution | 4.7 |

Tasks 4.1-4.8 are complete and merged to `develop`.

#### Risks & mitigations

- **Risk:** Unknown consumers rely on serialization quirks. **Mitigation:**
  Record/replay real traffic where permitted and normalize only unstable fields.
- **Risk:** Route rewrite drops anonymous or monitoring traffic. **Mitigation:**
  Required route-class table and negative/positive tests.
- **Risk:** Temporary dual endpoints broaden attack surface. **Mitigation:**
  Bind the new adapter to controlled ingress and register any temporary exception.

#### Decisions made

- SOAP compatibility is preserved; REST is additive.
- Cutover unit is one operation for request-scoped services. `ADService` is an
  atomic 21-operation unit because its mutable `CompiereService` login/window
  state cannot be split safely across JVMs; existing sessions remain pinned to
  their selected runtime during cutover.
- CXF 3.6.x is dropped from the roadmap because it is an EOL `javax` line
  without the JDK 21 support baseline established in Phase 2.
- CXF 4.1.8/Jakarta runs in an isolated Tomcat 10.1.59 API base. The version,
  download URL, and official SHA-512 are pinned in
  `gradle/phase4/runtime.properties`; the API connector is loopback-only. ZK and all
  other legacy servlet applications remain on Tomcat 9 until Phase 5. Exact
  artifact availability and the official checksum were rechecked when the pins
  were added.
- The invalid `ExternalSales.uploadOrders` WSDL cannot be loaded into CXF's
  validating WSDL model. CXF therefore publishes generic message-mode providers
  while the servlet serves the approved static WSDL bytes directly; the bounded
  codec deliberately preserves the exact frozen binding fault for that one
  operation rather than silently repairing a versioned consumer contract.
- The Tomcat 9 compatibility router preserves
  `/ADInterface/services/*` and `/ADInterface/servlet/XFireServlet/*`; the
  Tomcat 10.1 port is internal only. Routing audit stays in server logs so no
  diagnostic header changes the public contract. During migration the router
  read flags directly from marker-tested `AD_SysConfig` storage; after rollback
  rehearsal it routes only to CXF and no longer reads cutover flags.
- Per-operation keys require up to 60 characters. Migration 10030 expands the
  physical `AD_SysConfig.Name` column from 50 to the application dictionary's
  existing 100-character length for PostgreSQL and Oracle.
- `org.adempiere.webservice` becomes a shared Gradle/Ant surface in Phase 4.
  Ant remains the distribution adapter until Phase 7.
- Existing business implementations were not treated as neutral delegates:
  XFire fault signatures were extracted behind transport-neutral interfaces and
  `ServiceFault`; the temporary adapter classes were removed only after replay
  and rollback.
- XFire removal occurred after consumer-contract evidence and installed-product
  rollback rehearsal, not merely because it was old.
- All 11 descriptors receive complete L3 classification. Phase 4 behavior-tests
  SOAP and shared security; Phase 5 owns non-SOAP route behavior. The
  source-controlled artifact contains the original 114 routes, removes the
  temporary internal XFire mapping, and explicitly records that no
  callback/webhook mapping exists in the inventoried descriptors.
- **Hazard red-team:** H1 fired (grep all XFire coordinates/imports/descriptors
  before removal); H2 fired (XFire/CXF XML, JAX-WS, test engine/config recipes);
  H3 fired because the isolated Tomcat 10.1 runtime, installer, launch scripts,
  CI, and packaging must move together; H4 fired and the route table is
  mandatory; H5 cleared; H6 fired for bounded dual-endpoint exposure and
  requires T4-1; H7/H8 apply.

#### Verification & Exit Criteria (Definition of Done)

- [x] Every active WSDL operation has replay fixtures for success and fault
      behavior.
- [x] Old/new WSDL, status, headers, XML, auth, and error semantics match the
      approved normalization policy.
- [x] All five route classes are inventoried, including explicit `none found`
      evidence where applicable; SOAP/shared-security routes have positive and
      negative tests, and every non-SOAP route has a named Phase 5 closing test.
- [x] Parallel-run diffs are clean for the approved observation runs.
- [x] Rollback to XFire is rehearsed before final consumer cutover.
- [x] XFire artifacts/imports are absent only after all active dependents move;
      the full-product build target and installed-product SOAP smoke remain
      green.
- [x] T4-1 records and closes the internal-only dual endpoint, unchanged
      authorization, compensating controls, and owner; no actual auth weakening
      was approved.
- [x] Phase 4 PR CI passes and the branch merges to `develop`; making the checks
      required remains a manual repository-administrator action.

### Phase 5: ZK/Jakarta web transition (T-shirt size: XL)

**Goal:** Move the primary browser UI and servlet runtime to a supported
Jakarta-compatible stack through vertical slices.

**Status:** Phase 5 is an umbrella milestone delivered through sequential
`phase-5a-*` through `phase-5h-*` branches. Each increment merges to `develop`
before the next begins. Phase 5a merged as `dc7e84f68`; Phase 5b merged as
`cac0efdcaa13464e069291992214880cd0239ec5`; Phase 5c merged as `3154ced80`;
Phase 5d merged as PR #9 at `b47464d2763694c093ed22470000e00f2b6aee73`.
Phase 5e merged to `develop` at `6eda2bc8`; its database-neutral and
database-backed gates are executed and green. Phase 5f **merged to `develop` as
PR #11 at `83aeb8536`**; both of its gates are executed and green. Its
database-neutral `phase5fFinalVerification` gate has been executed green twice.
Its database-backed `phase5fJakartaWebRoutesSmoke` gate and six context shards
are implemented and **have been executed in CI, which supplies
`phase3DbSystemPassword`. They are green.** All six shards execute in a single run. Run 33379849664, on
commit `9ba62875d`, recorded 129 public-origin observations with zero vector
failures - `/` (16), `/wstore` (68), `/webui` (6), `/admin` (4), `/mobile` (14),
`/adempiere` (21) - and passed `verifyPhase5fSwitchBaseline`,
`capturePhase5fSoapCoexistence`, `verifyPhase5fBackgroundProcessorsQuiesced` and
the strict aggregate `verifyPhase5fRuntimeEvidence`, which validated 82 legacy
routes, all 37 eligible modern routes and 45 explicitly unexecuted modern
routes. Reaching it required diagnosing every failure from a run's own evidence:
a dropped `<error-page>`, three registered deviations
(`DEV-P5F-ERR-02..04`), the container `redirectPort` behind three
`CONFIDENTIAL` `/wstore` redirects, the Phase 4 POS credential fixture, a latent
argument-list defect in the aggregate task, three ambient database writers
(timer-driven processors, automatic error reporting, and first-touch
`WebEnv.initWeb` initialisation), and a snapshot aggregate that measured more
content than the table digests it was cross-checked against.

**Phase 5g is now active.** It is the web UI functional parity increment and is
delivered through sub-increments `5g-0` and `5g-1a` .. `5g-7`; see "Phase 5g
decomposition and active scope". `5g-0` merged to `develop` as PR #13 at
`91c4c2029`. `5g-1a`, the legacy Business Partner CRUD write oracle, is
**complete**: PR #16, reviewed head `3a0f5fd911d85a545661bdee067be710f47d2fda`,
captured in run 33491714444. It ships no modern runtime code and, by ADR rule,
reports no parity.

`5g-1a` produced the programme's first observed business writes through a
running ADempiere web UI. Creating a business partner writes `C_BPartner`,
starts a document workflow (`AD_WF_Process`, `AD_WF_Activity`,
`AD_WF_EventAudit`), creates the three default accounting rows and the menu tree
node; updating it moves `Name`; a second editor's update moves `UpdatedBy`; the
first editor's conflicting save is **refused** with "Current record was changed
by another user, please ReQuery" and writes nothing; deactivation, measured from
a third session that had only read the row, moves `IsActive`. Ten steps, eight
frozen fact classes, nine per-step effect documents, two fixture-isolated
captures that agree, and a recorded domain review. **No modern business write
has still ever been observed.**

`phase5g1aFinalVerification` is the head of the phase-gate chain:

```bash
./gradlew phase5g1aFinalVerification --dependency-verification=strict
```

It verifies the `contracts/legacy-web-write-v1/` manifest and its required-file
floor, re-derives the table-scoped callout and registered-validator attribution
to prove `C_BPartner` carries neither, and scores the write-capture normalizer in
**both** directions - ten defect classes must be detected and four volatility
classes must be normalized away - against a committed raw fixture. The captured
legacy write facts and the recorded domain review are **not yet frozen**;
`contracts/legacy-web-write-v1/README.md` names each absent file rather than
omitting it silently.

**Regime:** Legacy ZK/Tomcat 9 remains lit; the modern ZK/Tomcat 10.1 slice
crossed from dark to lit at Phase 5d and now expands.

**Safety rung:** L3, progressing toward L4.

**Prerequisites:** Phase 4 merged to `develop`.

**Duration estimate:** 8-16 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 5a | Reconcile Phase 4 status; inventory ZK source/runtime/assets, namespace ownership, descriptor deployment, and inherited routes; accept the ZK target ADR | Contracts/docs | Phase 4 |
| 5b | Freeze the installed Tomcat 9 route/UI oracle and publish checksum-pinned legacy web artifacts before the source crossing | Oracle | 5a |
| 5c | Add the reproducible Jakarta packaging beachhead, verified browser tooling, and binding ingress/session-affinity ADR | Build/runtime | 5b |
| 5d | Migrate the complete ZK compile closure and cross the Testability Milestone at login -> role -> menu -> read-only window | Web UI | 5c |
| 5e | Prove concurrent client/org/role/user/language/session cleanup and add fail-closed cohort routing | Security/session | 5d (**merged and verified** at `6eda2bc8`; see "Phase 5e decisions and findings") |
| 5f | Migrate all 82 deployed non-SOAP mappings by independently reversible context; disposition all 30 non-deployed mappings; build isolated generated Jakarta trees and five modern context WARs; preserve `/webui` while adding source-native `/timeline` and the exact static DSP compatibility resource | Web routes | 5e (**merged and verified** at `83aeb8536`; database-neutral gate green twice, six-shard database smoke green in run 33379849664) |
| 5g | Complete read/write UI, process, report, upload/download, POS, dashboard, server-push, and extension parity. Delivered through sub-increments `5g-0` and `5g-1a` .. `5g-7`; see "Phase 5g decomposition" | Web UI/extensions | 5f (**active**; `5g-0` and `5g-1a` merged, `5g-1b` next) |
| 5h | Finish source-native Jakarta, preserve both historical SOAP paths on final ingress, then remove the router, Tomcat 9, transformer, and ZK 3.6 | Runtime/source | 5g |

#### Risks & mitigations

- **Risk:** Mechanical namespace/API/test changes are underestimated.
  **Mitigation:** Separate codemod, compile-fix, descriptor, test-engine, and
  manual component tasks; never call them one "version bump."
- **Risk:** The first self-frozen snapshot encodes a bug. **Mitigation:** Require
  domain review and compare with any permitted running legacy instance.
- **Risk:** Session thread-local leakage becomes a security incident.
  **Mitigation:** Concurrent tenant isolation and cleanup tests gate every slice.

#### Decisions made

- No SPA rewrite in this roadmap.
- ZK CE `10.3.0.1-jakarta` is the target. Direct `zkex` layout and `zkmax`
  download APIs move to CE namespaces; Comet server push moves to polling;
  portal layout receives an ADempiere-owned CE adapter.
- Login/menu/read-only window is the walking skeleton.
- The installed Tomcat 9 web product is frozen before current source moves away
  from ZK 3.6. Later rollback uses checksum-pinned artifacts, not a second
  framework source tree.
- Old and new web deployments coexist through role/user flags; no blanket
  cutover.
- Phase 5c resolves public ingress, ROOT context, cookie scope, runtime affinity,
  and server-push transport before routing users.
- Tomcat 9 retirement occurs in this phase, not during the JDK 21 phase.
- Packaging-time transformation is the chosen coexistence mechanism. It is
  removed after the source-level Jakarta migration; dual namespace source trees
  are not maintained.
- Phase 5 owns non-SOAP descriptor/route classification after Phase 5a; Phase 4
  retains exact SOAP route/operation assertions.
- **Hazard red-team:** H1 fired (full ZK dependency/import/asset set); H2 fired
  (ZK APIs, `javax` -> `jakarta`, descriptors, tests/config); H3 fired (Tomcat
  runtime and all deployment pins); H4 fired for browser/public/infra routes; H5
  cleared; H6 applies to any compatibility auth shim; H7/H8 apply.

#### Phase 5b decisions and findings

Phase 5b froze the legacy web oracle in `contracts/legacy-web-v1/` and pinned the
legacy web artifacts. Scoping decisions:

- **HTTP/ZK-AU protocol-level capture only.** No browser tooling in 5b; real
  browser semantic snapshots remain Phase 5c's "verified browser tooling" task.
  The ZK 3.6 AU wire format was derived from the shipped `au.org.js` rather than
  guessed, and drives an eight-step flow through logout.
- **Deep oracle for `/webui`, shallow request vectors for the other contexts.**
  84 deployed routes are covered by 82 reviewed request vectors plus 20 owned
  exclusions. The two `/ADInterface` routes stay Phase 4-owned by assertion.
- **Checksum manifest only.** No WAR binaries are committed or released;
  rollback depends on reproducible regeneration from the pinned source commit.
- **Split gates.** DB-backed `phase5bLegacyWebOracleSmoke` and DB-neutral
  `phase5bFinalVerification`.

Normalization is **field-parsed, not token-regex**, and is protected in both
directions: the capture A/B self-diff detects under-normalization, and
`verifyPhase5NormalizerMutationProof` detects over-normalization against a
committed raw fixture, so the gate stays database-neutral. It runs 11 cases.

The rollback rehearsal, not inspection, produced the substantive findings.
Rebuilding from the pinned commit against a **freshly restored seed** proved
that first login is not idempotent: it creates `AD_Preference`, `AD_Tree_Favorite`
and `AD_ChangeLog` rows, and every opened window records an `AD_RecentItem` that
the desktop menu then renders. The replay therefore primes a cold database and
resets the fixture before capture A as well as between A and B. The same
rehearsal exposed two normalizer defects that would have produced a green but
worthless oracle: the Ant build stamp rendered into the login page (which would
have pinned the oracle to one build and made rollback verification impossible),
and an unanchored desktop-id replacement that intermittently corrupted unrelated
text such as `maxlength`. Both are fixed and pinned by regression cases.

Reproducibility residuals, each pinned per entry rather than waved away:
`not-reproducible-code-signed` (4 entries; JCE signatures),
`not-reproducible-installation-configured` (2 `.jnlp` entries embedding the
installer's encrypted connection string), and `not-reproducible-informational`
(7 ZIP-envelope rows). Against these, **2287 entries were proven byte-identical
across two independent clean builds from deleted outputs**. Pin verification is
therefore a reproducible-subset comparison *plus* an unchanged non-reproducible
entry set, so a newly unreproducible entry cannot appear unnoticed. Whole-tree
artifact reproducibility remains Phase 7's concern.

Product findings frozen with owners rather than blessed:
`/adempiere` and `/mobile` answer every path from one catch-all handler, so
their packaged static assets are unreachable; four routes answer HTTP 500;
logout does not invalidate the HTTP session; the session cookie carries neither
`Secure` nor `SameSite`. All are recorded in `oracle-exclusions.tsv` with an
owner and a closing gate, and reviewed in `contracts/legacy-web-v1/domain-review.md`.

**Hazard red-team (5b):** H1/H2 cleared (5b removes and rewrites nothing); H3
fired and is mitigated by `capture-environment.tsv` plus
`verifyPhase5OracleEnvironment` over 24 runtime coordinates; H4 fired and is
mitigated by per-route `proof_strength`; H5 fired and is mitigated by splitting
session facts into HTTP-observed and source-inspected files; H6 held as a hard
constraint — the capture uses ordinary credentials through the ordinary login
flow and adds nothing to the T-register; H7 cleared by an empty
`git log origin/develop..HEAD` at branch creation; H8 fired and is closed by
these in-PR documentation updates.

#### Phase 5c decisions and findings

Phase 5c keeps the modern web slice dark/L1. The new `:zkwebui` Gradle project
packages only ZK CE `10.3.0.1-jakarta`, reviewed dependencies/notices, Jakarta
Servlet 6 metadata, and a loopback marker that returns HTTP 503. It compiles no
legacy UI source and creates no public business route. Tomcat 9 remains the
only browser-facing ingress through Phase 5h; Tomcat 10.1.59 remains
loopback-only beside the Phase 4 CXF WAR. The binding ADR stores any future
modern session identifier server-side under the Tomcat 9 session, never exposes
a second browser cookie, and forbids copying mutable ZK desktop state.

The semantic browser contract lives separately in
`contracts/legacy-web-browser-v1/`. Playwright Java 1.62.0 launches only the
checksum-verified Chromium Headless Shell/FFmpeg pair selected from
`gradle/phase5/browser-artifacts.tsv`; Playwright auto-download is disabled.
The explicit installer may fetch only those manifest-pinned archives and
rejects them before extraction unless their size and SHA-256 match. The gate
blocks all non-loopback requests, captures twice with a marker-guarded database
reset between runs, compares semantic facts, stable request classes, and stable
HTTP errors, and runs mutation cases for changed names, routes, error classes,
and approved whitespace volatility. The four `/*` filter mappings remain
`context-reachability-only`: browser traffic proves real context requests, not
an independently observable filter effect.

Eclipse Transformer 1.0.0 is packaging-time tooling only. Fixture tests cover
bytecode, service resources, signature stripping, and deterministic output. A
report-only scan accepted all 19,328 resources in the real legacy WAR, changing
206 and failing none after excluding one malformed JRuby Rake test fixture.
The transformer does not migrate the Servlet 2.4 descriptor schema; Phase
5d/5f therefore own reviewed manual descriptor migration. No transformed
production output is installed or shipped.

The modern WAR is a separately checksummed additive overlay in the installed
tree and both 394LTS archives. Rollback rehearsal removes only that overlay and
manifest from isolated installed/release copies, preserves the Phase 4 API WAR
and loopback listener, and chains the frozen Phase 5b oracle. Phase 5c adds
fixed `ubuntu-24.04` packaging and DB-backed browser CI jobs. Required-check
enforcement remains manual residual R8.

Canonical Phase 5c gates:

```bash
./gradlew phase5cFinalVerification --dependency-verification=strict
./gradlew phase5cRollbackRehearsal -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

**Hazard red-team (5c):** H1 fired and is mitigated by target/legacy dependency,
overlay, and transformer ledgers; H2 fired and exposed the descriptor work that
remains manual; H3 fired and is mitigated by installed plus both-release overlay
verification without changing Phase 4 pins; H4 fired and retains four honest
context-only dispositions; H5 cleared because PostgreSQL remains 14.6 and the
fixture reset is marker-guarded; H6 fired as a constraint and no public route,
security bypass, second cookie, or unverified download exists; H7 cleared at
branch creation from merged Phase 5b; H8 is closed by these synchronized docs,
commands, topology, and CI changes.

#### Phase 5d decisions and findings

Phase 5d crosses the web UI Testability Milestone. `webui-modern.war` keeps the
Phase 5c artifact name and `/webui-modern` context path, and its contents are
replaced entirely: the Phase 5c marker servlet and its 503 route are gone, and
the archive now carries the migrated ZK compile closure, hand-written Servlet 6
and ZK 10 descriptors, and the shared ADempiere runtime.

- **Descriptors are hand-written, not transformed.** Eclipse Transformer does not
  migrate the Servlet 2.4 schema, which Phase 5c already recorded. The Phase 5d
  `web.xml` declares only the routes the walking skeleton needs - the ZK session
  listener, `WebUIServlet` on `*.zul`/`*.zhtml`, `DHtmlUpdateServlet` on
  `/zkau/*`, and `SessionTimeoutFilter` on `/*` - and carries an empty
  `<absolute-ordering/>` so no library fragment, including ZK's own
  `zkwebfragment`, can inject an undeclared route. `*.dsp`, `/timeline`, the two
  `resource-ref` datasource declarations, `WEB-INF/tld/**`, `WEB-INF/xsd/**` and
  `jboss-web.xml` are explicitly not migrated and stay with Phase 5f.
- **`zk.xml` differs from the ZK 3.6 file in exactly three reviewed places:**
  Comet server push (Enterprise-only) becomes the CE polling implementation, the
  four `.dsp`-theme font properties are dropped, and the ZK 3.6 `i3-log.conf`
  monitor is dropped.
- **The shared runtime is repackaged, not re-resolved.** The third-party layer
  comes from the shipped, code-signed `AdempiereSLib.jar` through a reviewed
  allow-list in `gradle/phase5/modern-web-shared-runtime.tsv`, so `/webui-modern`
  runs the library versions the product ships. ADempiere's own code comes from
  the Ant-built `lib/Adempiere.jar` and the installed `packages.jar`; the base
  copy of the one colliding class is dropped so the installed merge order
  (`install/Adempiere/build.xml:213-227`, packages wins) is reproduced exactly.
  Signatures, `META-INF/versions/**` and root `module-info` are stripped, and the
  gate rejects any duplicate class across `WEB-INF/classes` and `WEB-INF/lib`.
- **Six ZK CE runtime defects were found by the capture, not by inspection.**
  Each is fixed in ADempiere-owned code with a regression test or a reviewed
  contract row: the ZK 3.x `#enter` control key that made ZK CE reject the
  *entire* shortcut specification (and reached the client a second time through
  `getCtrlKeys()`); ZK CE's two-directional "flex or size, not both" rule at nine
  call sites; the fixed 20px menu-lookup row that ZK CE's taller combobox
  overflowed, leaving the lookup visible and unclickable; the desktop header
  collapsing to zero height under ZK CE's 16px region padding, which let the
  region body cover Change Role and Log Out; `layout.js` still calling the
  removed ZK 3.6 `$e()` and `zkau` globals on every grid row change; and a
  pre-existing brace error in a `GridTabRowRenderer` injected script that ZK CE
  surfaced as a page-level `SyntaxError`.
- **`oracle.jdbc` is packaged on the PostgreSQL lane.**
  `org.compiere.util.CCachedRowSet` *extends* `oracle.jdbc.rowset.OracleCachedRowSet`
  (`base/src/org/compiere/util/CCachedRowSet.java:42`), so every ADempiere runtime
  loads it regardless of the configured database. Omitting it failed the modern
  desktop after role selection.
- **ZK CE 10 logs through SLF4J 2, which is a no-op without a provider.** The WAR
  ships `slf4j-jdk14` so a ZK server-side failure reaches the container log
  instead of only the browser. Without it the first three defects above were
  diagnosable only from client-side error boxes.
- **Coexistence is measured in its own session.** The Phase 4 SOAP corpus
  authenticates 44 times and every ADempiere login writes an `AD_Session` row,
  which `contracts/legacy-web-v1/database-effects.tsv` allows a capture exactly
  one of. Folding the corpus into a measured capture would have forced that
  assertion to be loosened, so a third authenticated session hosts it. The Phase
  4 fixture that rewrites GardenAdmin's password is applied only after the two
  measured captures, so those captures use the frozen oracle's own credential.
- **Comparison is over a reviewed subset.** `modern-comparable-facts.tsv` records
  which frozen legacy facts the modern slice must reproduce and why the four
  `filter-*` facts cannot be compared: they describe Tomcat 9 contexts the modern
  runtime does not deploy. All eleven comparable facts, including the zero-write
  database effect, matched. Modern route classes are frozen separately in
  `modern-route-classes.tsv`, which records the three inherited outbound hosts
  that come from ADempiere's own markup and the two the slice removed.
- **`phase5dFinalVerification` does not chain `phase5cFinalVerification`.** That
  gate still asserts the 503 marker this phase removed. The Phase 5c assertions
  that remain true are depended on individually, and the historical marker
  evidence is preserved in `docs/modernization/phase-5c-evidence.md`.

**Hazard red-team (5d):** H1 fired and is mitigated by the reviewed shared-runtime
allow-list, the web-asset ledger and the duplicate-class rejection; H2 fired and
is why the descriptors are hand-written and the six ZK CE defects are fixed in
source rather than worked around in the test; H3 fired and is mitigated by
running both lanes concurrently and proving distinct ports, JVMs and
`CATALINA_BASE` trees with one shared marker-owned database; H4 fired and is
mitigated by the frozen modern route-class contract, which reports an
unclassified route verbatim; H5 fired for the `AD_Session` effect and is closed
by the separate coexistence session rather than by loosening the frozen contract;
H6 held as a hard constraint - ordinary credentials, no auth bypass, no second
cookie, no copied desktop state, no public route; H7 cleared by an empty
`git log origin/develop..HEAD` at branch creation; H8 is closed by these
synchronized plan, README, ARCHITECTURE and instruction updates.

Canonical Phase 5d gates:

```bash
./gradlew phase5dFinalVerification --dependency-verification=strict
./gradlew phase5dModernWebSmoke -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

#### Phase 5e decisions and findings

Phase 5e is a lit, **L3** security and session increment. It routes selected
sessions to the Phase 5d slice through the public Tomcat 9 `/webui` ingress and
proves concurrent isolation. It adds no business screen and migrates no Phase 5f
or 5g route.

**Cohort configuration.** Three system-level (`AD_Client_ID=0, AD_Org_ID=0`)
`AD_SysConfig` rows: `MODERN_WEB_UI_ENABLED` (exact `Y`),
`MODERN_WEB_UI_USER_IDS` and `MODERN_WEB_UI_ROLE_IDS` (strict
`|[1-9][0-9]{0,8}(,[1-9][0-9]{0,8})*`). Each key permits exactly one active
system row. Duplicate, malformed, null-valued or unreadable rows invalidate the
**complete** configuration and keep every new session legacy; an invalid
configuration exposes no allowlist at all. Client- and organisation-scoped rows
are ignored and reported. The three rows are loaded atomically through a
dedicated repository, not `MSysConfig.getValue`, whose first-row and cache
behaviour cannot prove duplicate absence. Because the grammar requires a
positive identifier, the System user and the System Administrator role can never
be allowlisted.

**Decision point.** A ZK 3.6 `EventInterceptor` takes the decision once, on the
first event after which the session context carries a *completed role
selection* - a state (`#AD_User_ID`, `#AD_Role_ID`, `#AD_Client_ID`,
`#AD_Org_ID`, `#AD_Language`, `#M_Warehouse_ID` all present), not an event name.
The role allowlist is matched against the **selected** `AD_Role_ID`, never
against the roles the user is merely entitled to; the routed matrix carries a
negative row for a role the acting user holds but does not select. The decision
is sticky per session; configuration changes never move an active session. It is
recorded as the runtime's *name*, so a container that persists sessions can
write it, and a session decided modern whose affinity is absent is refused with
503 rather than treated as undecided. Sticky per session is not sticky per
browser: a routed logout destroys the session on both runtimes, so the next
login is decided again from the current configuration. A request-filter backstop
reports a fully authenticated session that reached the router with no recorded
decision, which is exactly the shape an interceptor-removing mutation produces.

**Concurrency and persistence of the affinity.** `ModernSessionAffinity.admit()`
is one synchronized check-and-transition and the only way a caller may learn the
phase in order to act on it, so exactly one concurrent request rotates the
session identifier and exactly one holds the ticket. A loser is refused with 503
and, unlike a failure, does not poison the session the winner is still
establishing. The affinity, decision and identity are `Serializable` because a
session-persisting container silently drops what it cannot write; the bearer
ticket is `transient`, so a restored `ROTATING`, `AWAITING_BOOTSTRAP` or
`BOOTSTRAPPING` affinity becomes `FAILED`/`affinity-not-restorable` rather than
resuming a handoff whose secret is gone.

**Ending a routed session.** A routed session exists on two runtimes and only
one observes the Log Out click. `AdempiereWebUI.logout()` marks the modern
session ended; `CohortHandoffFilter` destroys it on the next routed request,
before the chain runs, and answers `205` carrying the reserved
`X-ADempiere-Handoff-End` header; the proxy reads that header before writing a
byte to the public response; and the router invalidates its own Tomcat 9 session
- destroying affinity and decision together - and redirects to the public
context root. A terminally `FAILED` session is deliberately not ended this way:
recycling it would create an undecided session and serve the legacy login form,
which is the fallback this phase forbids.

**Topology.** Tomcat 9 stays the only public ingress. `webui-modern.war` keeps
its artifact name and is mounted on loopback Tomcat 10.1.59 at the **internal**
context path `/webui` through `conf/Catalina/localhost/webui.xml`. Public and
internal application paths are therefore identical, which is why the router
never rewrites HTML, JavaScript, CSS or ZK asynchronous-update bodies. The
installed product and both 394LTS archives stage that archive at
`tomcat10-api/phase5e/webui-modern.war`, which is the path the `Context`
descriptor's `docBase` resolves to, and remove the superseded Phase 5c/5d
auto-deployed `tomcat10-api/webapps/webui-modern.war` and its manifest, so
exactly one modern UI context exists in the shipped product. The overlay gate
resolves the `docBase` and requires the file to be present in the installed tree
and in both archives. The Phase 5d direct lane boots its own Tomcat from the
build output and remains an independent regression gate.

**Handoff.** A selected session rotates its Tomcat 9 session identifier exactly
once - the session-fixation protection - and receives a versioned,
HMAC-SHA-256, single-use, 30-second, loopback-only ticket carrying a 256-bit
nonce, both timestamps, the rotated session binding and the complete identity.
It is sent once in a reserved internal header and never reaches the browser. The
router refuses, rather than strips, any browser request in that namespace.
Validation checks the MAC first and consumes the nonce last, so a malformed or
expired ticket cannot burn a nonce. The replay cache holds 4096 entries - 6.6x
the 620 live nonces the documented 20 logins/second ceiling implies - and
refuses rather than evicts when full. The bootstrap seeds the six identity
values and runs ADempiere's own `Login.validateLogin` and
`Login.loadPreferences`; it copies no password and no legacy desktop state.

**Cookies and tracking.** One public `JSESSIONID` on `/webui`, `HttpOnly`,
`SameSite=Lax`, `Secure` when the public request is HTTPS. Both contexts are
cookie-only and the modern `Context` sets `disableURLRewriting="true"`. The
modern runtime's `Set-Cookie` is consumed by the router and only its identifier
is retained server-side. The derived legacy descriptor keeps `version="2.4"`:
raising it to 3.0 would additionally enable web-fragment and annotation scanning
for an archive frozen without them.

**Failure policy.** An established modern session never falls back to legacy. An
unknown route, a ticket failure, a missing affinity or an unavailable backend
produces an explicit status.

**Derived artifact.** The deployed Tomcat 9 `webui.war` is the frozen Phase 5b
artifact plus exactly three reviewed entries - the two descriptors and the
bridge overlay jar - derived deterministically and recomputed rather than
trusted. `lib/webuiOriginal.war` stays pristine and is both the rollback
material and the installer's `setupWLib` merge input. Rollback therefore
**deletes** it rather than restoring a derived copy, restores the deployed
Tomcat 9 `webapps/webui.war` and removes its exploded expansion, and the
rehearsal re-runs the merge afterwards to prove the overlay cannot be
resurrected. The rehearsal runs the **real** `setupWLib` and
`backupWebuiOriginal` bodies, read out of `install/Adempiere/build.xml` at run
time, under Ant, with real merge inputs whose marker entries must appear in the
rebuilt archive - so a rehearsal that had degraded into an identity unzip/rezip
fails rather than passes. It records two observed installer properties a
hand-written re-zip could not have shown: a site `WEB-INF/web.xml` wins over the
one inside `lib/webuiOriginal.war` under Ant's `duplicate="preserve"`, and the
merge legitimately drops `META-INF/MANIFEST.MF` because the installer's own
`manifest.exclude` patternset names it. The allowed-drop set is read from
`install/Adempiere/build.xml` at run time, so any other dropped entry fails.

**Session and context defects fixed.** ZK's `Locales` thread local was never
cleared (a cross-identity locale leak on pooled request threads);
`setContextForSession` installed a `null` context; `isValidContext` threw on a
removed context and answered `true` when it was absent;
`SessionManager.getApplication(String)` dereferenced an absent entry inside the
destruction path; `sessionDestroyed` invalidated a session the container was
already destroying; `SessionTimeoutFilter` returned an empty HTTP 200 for an
unregistered session and continued into `chain.doFilter` after invalidating.
`CohortHandoff.seed` cached user preferences under the empty-string key because
`SessionManager.loadUserPreference(Integer)` reads its key from a thread context
that does not exist before ZK creates an execution, which made the first routed
desktop throw; the cohort bootstrap marker survived logout and short-circuited a
logged-out session straight to the desktop; and the router's abandoned-session
cleanup ran context-dependent `SessionManager` calls with no `ServerContext`, so
it silently did nothing. All are fixed in the modern tree only; the frozen
archive is unchanged.

**Capture defects fixed.** The `role-allowlisted` fixture allowlisted
`AD_Role_ID` 103 while the acting `GardenAdmin` login selects 102, so the row
could only have passed by accident; the concurrency row read
`document.documentElement.lang`, which neither ZK version renders, and never
selected a language, so it compared two empty strings from two `en_US` sessions;
and the lifecycle rows compared a pre-insertion creation reading with a
post-removal destruction reading and "destroyed" a session with
`BrowserContext.clearCookies()`, which does not touch a container session. The
fixture now allowlists 102 and carries a `role-unselected` negative row, the
browser asserts the role its session actually runs as, each identity is driven
to an explicit language and compared on the `AD_Message` `Logout` label
ADempiere renders in the session's own language against that identity's own solo
capture, and every lifecycle row anchors both readings to a recorded log offset,
drives a mechanism the product itself owns, and requires a recorded destruction
on each runtime that must have one.

The census parser behind those rows was a third capture defect. A routed Tomcat
9 session is unregistered by the rotation, so the frozen listener records its
destruction with **no** cache lines; a parser that scanned on to the next
`Destroyed Session Id` therefore absorbed the seven **pre-insertion** lines of
whichever `sessionCreated` followed, and reported the destruction *absent* when
none did. Each block is now bounded at its own `Invalidate Session : <id>`, the
modern census line for the same session, or the next `Create Session Id` /
`Destroyed Session Id`; a cache-line-less block is a recorded destruction with
no census; each reading carries its provenance; the mark reads the same class of
evidence as the observation; and each runtime is judged on evidence the routed
session owns - the unconditional census on the modern runtime, the destruction
record on the public one, whose rotation-time cleanup is covered by
`CohortRoutingFilterTest`. Reviewed `catalina.out` fixtures under
`scripts/phase5/fixtures/session-cache-census/` reproduce both faults and are
gated by `verifyPhase5eSessionCensusParser`.

**Hazards.** H1, H2, H3 (topology), H4, H6 and H8 fired and are closed as
recorded in `docs/modernization/phase-5e-evidence.md`. H3 (runtime major), H5 and
H7 were checked and cleared. The internal handoff is registered as transitional
state **T5e-1**, closing in Phase 5h.

Canonical Phase 5e gates:

```bash
./gradlew phase5eFinalVerification --dependency-verification=strict
./gradlew phase5eCohortRoutingSmoke -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

Both `phase5eFinalVerification` and `phase5eCohortRoutingSmoke` are executed and
green. The database-backed run used the marker-owned disposable PostgreSQL
database and recorded all 23 public-origin matrix rows, including concurrency,
logout, timeout, container destruction, Phase 4 SOAP coexistence, and secret
hygiene, as passing in `build/phase5e/evidence/cohort-matrix.tsv`.

#### Phase 5f delivered state and scope

Phase 5f is a lit, **L3** non-SOAP route increment. Its accepted governance is
recorded in
`docs/modernization/phase-5f-jakarta-web-routes-adr.md`,
`docs/modernization/phase-5f-transitional-state.md`, and
`contracts/phase5f-jakarta-web-v1/`.

- Phase 5f **merged to `develop` as PR #11 at `83aeb8536`**, with both gates
  executed and green.
- The deployed scope is closed at **82 mappings**: `/webui` 6, `/admin` 4,
  `/` 8, `/mobile` 14, `/adempiere` 21, and `/wstore` 29.
- The 30 non-deployed mappings have fixed dispositions: drop eight JBoss HTTP
  invoker mappings, drop twenty mappings from the superseded non-deployed
  `serverApps/src/etc/WEB-INF/web.xml`, and defer both JasperReports
  `GetMD5File` mappings to Phase 5g.
- Routing is context-wide, fail-closed, same-path, and independently reversible.
  Sessionless requests do not gain a session merely for routing; existing
  sessions stay pinned; a modern failure never falls back.
- Headers, cookies, TLS, byte limits, timeouts, database effects, rollback, and
  enable state are independent per-context contracts. `/webui` policy is not a
  default for another context; all literal policy values are frozen and
  database-neutrally gate-enforced.
- Gradle creates isolated generated Jakarta source and web-asset trees under
  `build/phase5f/jakarta-web/`; the legacy source/assets are not rewritten.
  Five deterministic source-native WARs are built for `/admin`, `/`, `/mobile`,
  `/adempiere`, and `/wstore`. The existing Phase 5e `webui-modern.war` remains
  the sixth modern application.
- Four frozen legacy 500s are corrected through reviewed deviation rows:
  AdRedirector 400, Community 400, missing XML resource 404, and plain GET
  payment 405.
- `/webui/timeline` is now a source-native Jakarta read-only servlet. The
  historical theme DSP URL is served as reviewed static Phase 5d CSS for
  GET/HEAD; all other DSP paths are 404. The DSP interpreter and five vendor
  TLDs are absent.
- `/mobile` and `/adempiere` are packaged and replayed but remain disabled until
  Phase 5g. `/admin` remains legacy without named consumer ownership.
- The installed product and both 394LTS release archives preserve the Phase 4
  CXF WAR and Phase 5e `/webui` topology, stage exactly one copy of each of the
  five Phase 5f WARs under `tomcat10-api/phase5f/`, install same-path Context
  descriptors, retain pristine `*Original.war` rollback material, and reject
  stale exploded or auto-deployed modern contexts. Rollback removes the five
  modern contexts and restores every pristine Tomcat 9 WAR.
- T5e-1 stays open. T5f-1 registers multi-context proxying and forwarded secure
  state, bounded to loopback and closing in Phase 5h.
- H1, H2, H3-topology, H4, H6, and H8 fire; H5 engine-major and H7 are cleared
  only under their recorded controls.

Canonical Phase 5f gates:

```bash
./gradlew phase5fFinalVerification --dependency-verification=strict
./gradlew phase5fJakartaWebRoutesSmoke \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

`phase5fFinalVerification` was executed green twice. It
validates the frozen 82/30 contracts and mutation proofs, routing-core/bridge
isolation, generated Jakarta source and asset closures, five deterministic WARs,
all 25 retained JSPs through Tomcat 10.1 Jasper, Servlet 6 descriptors and
discovery absence, `/timeline` and DSP behavior contracts, installed/release
topology, per-context rollback, current inventories, and the Phase 4/5d/5e
regressions.

`phase5fJakartaWebRoutesSmoke` runs six public-origin shards
(`/webui`, `/admin`, `/`, `/mobile`, `/adempiere`, `/wstore`) plus complete
Phase 4 SOAP coexistence and exact runtime-evidence validation. It **has been
executed in CI and is green.** All six shards execute in a single run. Run 33379849664, on
commit `9ba62875d`, recorded 129 public-origin observations with zero vector
failures - `/` (16), `/wstore` (68), `/webui` (6), `/admin` (4), `/mobile` (14),
`/adempiere` (21) - and passed `verifyPhase5fSwitchBaseline`,
`capturePhase5fSoapCoexistence`, `verifyPhase5fBackgroundProcessorsQuiesced` and
the strict aggregate `verifyPhase5fRuntimeEvidence`, which validated 82 legacy
routes, all 37 eligible modern routes and 45 explicitly unexecuted modern
routes. Reaching it required diagnosing every failure from a run's own evidence:
a dropped `<error-page>`, three registered deviations
(`DEV-P5F-ERR-02..04`), the container `redirectPort` behind three
`CONFIDENTIAL` `/wstore` redirects, the Phase 4 POS credential fixture, a latent
argument-list defect in the aggregate task, three ambient database writers
(timer-driven processors, automatic error reporting, and first-touch
`WebEnv.initWeb` initialisation), and a snapshot aggregate that measured more
content than the table digests it was cross-checked against. The shards run in an explicit order (`/`, `/wstore`,
`/webui`, `/admin`, `/mobile`, `/adempiere`), record vector failures instead of
aborting, and the job passes `--continue`, so one run reports the whole matrix.
All 82 route observations and their route-specific database effects are
therefore observed, and both contract ledgers carry the executed marker. The 25
`/wstore` JSP precompile rows remain
`contract-only-runtime-observation-pending`, because only three of those pages
are reached by a route vector. See
`docs/modernization/phase-5f-evidence.md`.

**Byte caps.** The proxy's 8 MiB request and 64 MiB response caps are enforced
by transport-neutral code (`org.adempiere.web.route.BoundedTransfer`) and are
asserted one byte on either side of the limit, including that nothing past the
limit reaches the destination and that an oversized declared `Content-Length` is
refused before the body is opened. A cap that could only be exercised by pushing
an oversized body through a live container would never be run, and an unrun cap
is indistinguishable from a missing one.

**Mutation proof.** Sixteen reviewed mutations are scored, and only behaviour is
scored: each mutant is compiled first, a mutant that does not compile fails the
gate with a distinct message rather than counting as a detection, and detection
is read from the named test class's own JUnit report - which must exist, must
have executed a test, and must record a failure. A non-zero build status is
never sufficient on its own.

**Where the ticket rules are proved.** A browser cannot present a ticket: the
router refuses the whole reserved header namespace before it routes. Expiry,
tampering, wrong-session binding, partial identity and single-use replay are
therefore asserted directly against the codec in the database-neutral gate, and
the routed browser matrix asserts only what a browser can observe. The routed
evidence gate fails if a duplicate ticket row reappears in the runtime matrix.

#### Phase 5g decomposition and active scope

Phase 5g is the web UI **functional parity** increment. Like Phase 5 itself it
is too large for one branch, so it is delivered through sequential
sub-increments, each cut from `develop` and merged before the next begins.
Its accepted governance is recorded in
`docs/modernization/phase-5g-web-parity-adr.md`.

Two ordering rules bind every Phase 5g increment:

1. **Oracle before modern.** The frozen Phase 5b/5c browser oracle asserts zero
   *business* writes, so it cannot score a write. Each new behaviour class is
   captured from the legacy Tomcat 9 / ZK 3.6 runtime, frozen, and
   domain-reviewed **before** the modern runtime is scored against it.
2. **No branch may both invent the expected answer and implement the thing being
   scored.** Oracle increments ship no modern runtime code; parity increments
   add no new oracle facts.

| Increment | Scope | Ships modern code? | Expected answer captured by |
|---|---|---|---|
| 5g-0 | Phase 5f reconciliation; the Phase 5g ADR; ZK-facing extension/callout/validator discovery; the dictionary-process classification and named 5g-1e fixture; the disabled-context governance amendment | No | n/a - ships no runtime code |
| 5g-1a (**complete**, PR #16 at `3a0f5fd91`) | The legacy Business Partner CRUD write oracle: a reusable legacy write capture harness, `contracts/legacy-web-write-v1/`, the keyed relational effect model, seed-restore isolation, normalizer and ambient-classification mutation proofs, the legacy two-user concurrency answer, and the recorded domain review. Captured in run 33491714444; frozen, self-diffed and scored | **No** | n/a - it *is* the oracle |
| 5g-1b | Modern Business Partner CRUD parity, scored **only** through the public routed `/webui` origin, including the legacy two-user concurrency answer 5g-1a froze | Yes | **5g-1a** |
| 5g-1c | Sales Order draft -> Complete: document status, document number, reservations and tax. **No accounting** | Yes | **unassigned - blocking** |
| 5g-1d | The explicit "Post Immediate" action: `Posted='Y'` and balanced `Fact_Acct` | Yes | **unassigned - blocking** |
| 5g-1e | One named non-report dictionary process: `AD_PInstance`, parameters, log rows, and an observable completion signal | Yes | **unassigned - blocking** |
| 5g-1f | Callout-bearing write parity, of which the `C_BPartner_Location` tab (`CalloutBPartnerLocation.formatPhone`) is the first instance, implemented against the 174 callout columns the 5g-0 inventory classified | Yes | **unassigned - blocking** |
| 5g-2 | Report parity: `ZkReportViewer`, `ZkJRViewer`, PDF/print output, residual **R4-5d-1** (the JasperReports interactive web viewer), and the two deferred JasperReports `GetMD5File` mappings | Yes | **unassigned - blocking** |
| 5g-3 | Upload/download and attachment parity: `WAttachment`, `WMediaDialog`, `WImageDialog`, `WFileImport`, `SimplePDFViewer`, `WArchiveViewer`; byte-cap and content-type contracts | Yes | **unassigned - blocking** |
| 5g-4 | Dashboard and server push: `DashboardPanel`, `DPActivities`, `DPRecentItems`, `DashboardRunnable`, `ServerPushTemplate`; Comet -> polling under the existing ZK target ADR | Yes | **unassigned - blocking** |
| 5g-5 | Extension and plugin parity, implemented against the 5g-0 inventory | Yes | **unassigned - blocking** |
| 5g-6 | POS parity | Yes | **unassigned - blocking** |
| 5g-7 | The Phase 5g exit roll-up: screen-level visual comparison, parallel-run performance and error thresholds, full distribution/database CI, the complete rollback rehearsal, the `phase5g-web-parity-gate` disposition gate for `/mobile`, `/adempiere` and `/admin`, and residual/transitional reconciliation | Yes | **unassigned - blocking** |

`5g-7` exists because the Phase 5 exit criteria below - screen-level parity,
performance and error thresholds, full distribution/database CI, and the
rollback rehearsal - were otherwise owned by no increment. Without it every
sub-increment could merge while Phase 5g remained incomplete.

**The "Expected answer captured by" column is new, and most of it is empty.**
Decision 3 of the ADR forbids a parity increment from adding oracle facts, and
decision 2 requires the expected answer to be captured from the legacy runtime
first. Reading those two rules against the table exposes a gap the original
decomposition did not state: it names exactly **one** oracle increment, `5g-1a`,
and every increment after it ships modern code. So `5g-1b` has an oracle and
`5g-1c` through `5g-7` do not.

Each of those introduces a genuinely new behaviour class - a document
transition, an accounting post, a dictionary process, a rendered report, a byte
stream, a pushed dashboard update, an extension hook, a POS flow - and none of
them can be scored against the Phase 5b read-only browser oracle or against
`contracts/legacy-web-write-v1/`, which is a Business Partner CRUD oracle.

`unassigned - blocking` is therefore a real prerequisite, not a formatting
placeholder: **an increment marked `unassigned` may not begin.** Naming its
oracle increment - and merging that oracle, frozen and domain-reviewed - is the
first task of the increment that precedes it. This is registered as residual
risk **R11**.

`5g-1f` is new. `contracts/legacy-web-write-v1/exclusions.tsv` originally named
`5g-1b` as the closing increment for the `C_BPartner_Location` tab, which is not
possible: 5g-1a deliberately excludes that tab from its capture because
`CalloutBPartnerLocation.formatPhone` puts callout arithmetic between the window
and the effect, so 5g-1b would have to invent the expected answer for the very
thing it scores. Callout-bearing writes are a distinct behaviour class - the
5g-0 inventory classified 174 callout columns - and now have a named increment.

**Constraints that bind every Phase 5g increment.**

- **Isolation is seed restore, not surgical rollback.**
  `scripts/phase5/reset-oracle-fixture.sh` is hard-wired to `AD_User_ID=101` and
  only removes capture-created `AD_ChangeLog`, `AD_Session` and `AD_RecentItem`
  rows. It restores no business partner, order, tax, reservation, `AD_PInstance`
  or accounting state, so it cannot reset a write workload. Every capture -
  legacy A, legacy B, and each runtime - restores the marker-owned database from
  the seed, applies the reviewed fixture, and restarts the container to clear
  application caches. Surgical rollback is forbidden until the complete
  transitive write set is proved.
- **Effects are keyed relational facts, not table digests.** The Phase 5f
  whole-database snapshot works at table-digest granularity, which proves *that*
  a route wrote but not *that a business transition is correct*. Write scoring
  compares created/updated/deleted rows keyed by fixture identity, the required
  foreign-key graph, before/after business values, document status/action
  values, accounting dimensions with balanced debit/credit totals, and process
  status/summary/parameter values. Generated identities are normalized through a
  captured mapping and are never dropped, because dropping them erases broken
  foreign keys and duplicate effects.
- **Modern writes are scored only through the public routed `/webui` origin.**
  A green direct-Tomcat-10 test would not exercise T5e-1 ticket bootstrap,
  session affinity, request-body proxying or proxy failure behaviour. Direct
  `/webui-modern` capture is retained as a diagnostic subtest only.
- **`contract-only-runtime-observation-pending` is banned for acceptance
  criteria.** It was an honest residual for the 25 Phase 5f `/wstore` JSP rows
  unreached by the route corpus. It is never valid for a tier that defines an
  increment's own claim: if a tier cannot execute on both runtimes, the gate
  fails and the increment stays incomplete.
- **Accounting is a separate action from completion.** `MOrder.completeIt()`
  does not post. `Fact_Acct` rows follow either the accounting processor - which
  the Phase 5f quiescence script deliberately disables - or the explicit UI
  "Post Immediate" action, so accounting facts are never attributed to the
  Complete action. Order preparation also rejects a closed accounting period,
  and the seed's periods end in 2011, so any order fixture must use a reviewed
  historical date or a marker-owned open period.
- **Write concurrency is new coverage.** Phase 5e proved identity isolation, not
  transactional correctness. The **legacy** answer for two users editing one
  `C_BPartner` record is captured, domain-reviewed and frozen by `5g-1a` as
  `concurrency-facts.tsv`; a parity increment may not invent it. From `5g-1b`
  onward the modern runtime is scored against that frozen answer, and the matrix
  additionally adds concurrent writes in different client/org contexts, a
  process-completion test that records the executing identity and proves
  thread-context cleanup, and duplicate-submit protection or an explicit
  legacy-parity result - each against the legacy answer its own oracle
  increment froze.

**Hazard red-team for Phase 5g.** H1 is not applicable - Phase 5g removes
nothing. **H2 fires:** ZK CE 10 write-path work is real API work, not
`javax` -> `jakarta` substitution. H3 is cleared - no runtime major moves.
**H4 fires:** write and process flows reach AU route classes the read-only
capture never touched, so every new class is enumerated or the gate fails. H5 is
cleared. **H6 fires** - and it fires without any new component. T5e-1 and T5f-1
were proved for authentication and read-only behaviour; extending them to
destructive and process operations raises the consequence of replay,
CSRF/same-site requests, session confusion, proxy truncation and error recovery,
so the public-origin security/session matrix is re-run for write traffic.
**H7 fires:** every sub-increment is cut from `develop`, never from a sibling.
**H8 fires:** living-doc updates ship in the same PR.

**Disabled-context disposition.** `/mobile`, `/adempiere` and `/admin` are
**not** enabled by Phase 5g. They follow the `/admin` precedent and remain
legacy until a named consumer owns them. This changes the earlier assumption
that Phase 5g would close them, and it interacts directly with Phase 5h, which
removes Tomcat 9. `docs/modernization/phase-5g-disabled-context-disposition.md`
carries the amendment. It permits exactly three dispositions per context -
`migrate`, `retire` with usage evidence, or `narrow-5h-scope` - and states the
evidence each one requires.

The amendment does **not** modify the frozen
`contracts/phase5f-jakarta-web-v1/enable-state-residuals.tsv`. That contract
already names `phase5g-web-parity-gate` as the closing gate for
`ENABLE-P5F-MOBILE` and `ENABLE-P5F-ADEMPIERE`, and the amendment defines that
previously undefined gate: it is satisfied for a context when the context
carries a recorded disposition with its required evidence, a named owner and a
closing increment. It is **not** satisfied by enabling the context, and **not**
by leaving the decision open. The gate is implemented in `5g-7` and Phase 5h is
blocked behind it, so the decision cannot arrive as a surprise during 5h.

All three dispositions are **open** at the end of `5g-0`. None can be settled
from this repository: `/adempiere` and `/mobile` need production evidence about
the pre-ZK servlet client, and `/admin` needs the operator and Java Web Start
consumers of `/adempiereMonitor/*`, `/statusInfo` and `*.jnlp`. Recording
`retire` on the strength of an absent in-tree caller is explicitly forbidden.

#### Verification & Exit Criteria (Definition of Done)

- [ ] Modern login, role selection, menu, and representative windows/processes
      pass semantic snapshot and database-effect parity checks.
- [ ] Concurrent session tests prove no client/org/role/user/language context
      leaks across requests.
- [ ] All active route classes pass on Tomcat 10.1/JDK 21.
- [ ] `./gradlew` and full distribution/database CI gates remain green.
- [ ] Parallel-run performance and error rates meet approved thresholds.
- [ ] Rollback to legacy ZK/Tomcat 9 is rehearsed.
- [ ] Tomcat 9/ZK legacy artifacts, the Phase 4 compatibility router, and the
      namespace transformer are removed only after the complete dependent set is
      source-migrated and CXF 4.1.x contracts are green.

### Phase 6: Database platform and observability upgrade (T-shirt size: XL)

**Goal:** Move the PostgreSQL reference platform to 16 with reversible data
migration and establish production-comparison telemetry.

**Regime:** Post-testability ("lit").

**Safety rung:** L4 for the supported PostgreSQL path; other database engines
remain at their explicitly supported rung.

**Prerequisites:** Phase 5 merged to `develop`; production data/engine support
matrix approved.

**Duration estimate:** 4-8 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 6.1 | Capture schema, extensions, encodings, collations, row counts, large objects, custom objects, and migration status from a sanitized production-like copy | Database | Phase 5 |
| 6.2 | Upgrade JDBC drivers and validate PostgreSQL 14 and 16 compatibility before the engine cutover | Core/database | 6.1 |
| 6.3 | Build parallel PostgreSQL 16 migration runbook: backup, restore, analyze, schema/metadata checks, write freeze, delta/cutover, rollback | Database | 6.1 |
| 6.4 | Change CI's ephemeral service from 14.6 to 16 and re-seed explicitly; do not reuse a prior-major volume | CI | 6.2 |
| 6.5 | Rehearse production-like migration and rollback; compare document/process/accounting outputs | Database | 6.3-6.4 |
| 6.6 | Add OpenTelemetry/metrics for HTTP, DB pool, process/workflow, scheduler, posting, migration, and errors | Observability | 6.2 |
| 6.7 | Define alert thresholds and an old/new comparison dashboard for cutover | Operations | 6.5-6.6 |

#### Risks & mitigations

- **Risk:** Database major is treated as an image bump. **Mitigation:** Parallel
  logical restore with old instance retained; ephemeral CI uses explicit re-seed.
- **Risk:** Custom extensions or collations invalidate restore. **Mitigation:**
  Preflight inventory and rehearsed sanitized copy.
- **Risk:** Telemetry exposes tenant or sensitive data. **Mitigation:** Attribute
  allowlist, redaction tests, and no business payloads in traces.

#### Decisions made

- CI/demo data is ephemeral: destructive reset/re-seed is approved.
- Durable environments use parallel backup/restore and cutover; no in-place
  direct major jump.
- PostgreSQL 16 is the reference target. Oracle remains supported only to the
  approved stakeholder matrix; MySQL/MariaDB are not silently removed.
- **Hazard red-team:** H1 applies to driver/artifact replacement; H2 applies to
  driver API/config changes; H3 applies to CI/runtime database pins; H4 applies
  to metrics scrape routes; H5 fired and is handled by tasks 6.3-6.5; H6 cleared
  unless temporary open metrics endpoints are proposed; H7/H8 apply.

#### Verification & Exit Criteria (Definition of Done)

- [ ] Clean PostgreSQL 16 restore of the `394lts` seed and the release-scoped
      migration set selected by `migration/build.properties` (currently 249
      files) passes in CI.
- [ ] Sanitized production-like backup restores into a parallel PostgreSQL 16
      instance with schema, metadata, row-count, and business-seam checks green.
- [ ] Rollback to the retained PostgreSQL 14 instance is rehearsed and timed.
- [ ] No persisted PostgreSQL 14 volume is mounted directly into PostgreSQL 16.
- [ ] Full build, integration, API, web, process, and accounting gates are green.
- [ ] Metrics/traces prove request, DB pool, worker, scheduler, posting, and
      migration health without sensitive payload leakage.

### Phase 7: Build consolidation and legacy retirement (T-shirt size: L)

**Goal:** Make Gradle the sole authoritative build and remove only those legacy
surfaces proven unused or fully replaced.

**Regime:** Post-testability ("lit").

**Safety rung:** L4.

**Prerequisites:** Phase 6 merged to `develop`; usage/consumer decisions approved.

**Duration estimate:** 4-8 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 7.1 | Model every Ant reactor output as a Gradle task/module and compare archive manifests/content | Build | Phase 6 |
| 7.2 | Move remaining published JARs to locked repository resolution; document/provenance-check retained internal binaries | Supply chain | 7.1 |
| 7.3 | Produce reproducible ZIP/TAR, installer, seeds, WARs/JARs, checksums, SBOM, and signatures through Gradle | Release | 7.1-7.2 |
| 7.4 | Run Ant and Gradle builds in parallel for an observation window and diff artifacts/startup/smoke behavior | Build | 7.3 |
| 7.5 | Remove Ant/sbt only after parity; archive scripts and update all topology/command docs in the same PR | Build/docs | 7.4 |
| 7.6 | Remove legacy servlet/mobile/store/CM surfaces only where usage and consumer evidence says zero; otherwise retain and assign ownership | Product | 7.4 |
| 7.7 | Pin maintained GitHub Action SHAs/majors and keep required checks current | CI | 7.3 |

#### Risks & mitigations

- **Risk:** A small packaging difference breaks an installer or extension.
  **Mitigation:** Machine-readable archive inventory plus installed-product smoke.
- **Risk:** "Old" is mistaken for "unused." **Mitigation:** Removal requires
  telemetry, consumer inventory, and stakeholder approval.
- **Risk:** Deleting Ant removes the only runnable rollback. **Mitigation:**
  Retain the last known-good Ant build tag/artifacts through one release cycle.

#### Decisions made

- Ant is removed only after demonstrated Gradle parity; sbt is removed from the
  supported path earlier and deleted here if no owner appears.
- Unused product surfaces are **dropped** only with evidence; unverified surfaces
  are **deferred**, not dropped.
- **Hazard red-team:** H1 fired for Ant/sbt/JAR/legacy-surface removal and requires
  full manifest/import/dependent grep; H2 applies to final build/test config;
  H3/H4/H5/H6 cleared unless runtime/routes/stores are removed; H7 applies; H8
  fired and all executable/onboarding docs update atomically.

#### Verification & Exit Criteria (Definition of Done)

- [ ] Gradle produces every approved release artifact and installed-product smoke
      remains green.
- [ ] Ant-vs-Gradle archive manifests, startup behavior, and seam contracts match
      for the approved observation window.
- [ ] No supported command, CI workflow, installer, release job, or module list
      references removed Ant/sbt paths.
- [ ] Every removed JAR/framework/application has a complete dependent grep and
      an explicit replacement or evidence-backed removal decision.
- [ ] Required checks are green and still enforced after workflow/check-name
      changes.
- [ ] README, architecture, Copilot instructions, release docs, and module lists
      reflect the final topology in the same PR.

## 7. Execution governance

1. **Branch per phase.** Create `phase-N-<short-name>` from `develop`; never
   commit phase implementation directly to trunk.
2. **Merge before continuing.** Phase N must merge to `develop` before Phase N+1
   starts. At branch creation,
   `git log origin/develop..HEAD` must be empty. Do not stack phase branches. If
   controlled stacking is unavoidable, require a reconciliation PR and record
   residual risk.
3. **Trunk is `develop`.** Local `origin/HEAD` resolves to `origin/develop`.
   `master` is history/release compatibility only unless repository
   administrators explicitly change that decision.
4. **Gate by regime.** Lit work advances only on green CI. Dark work advances on
   recorded oracle/seam artifacts, a passed smoke checklist, reversibility, and
   review at the assigned safety rung.
5. **CI Milestone is Phase 1.** Authoring the workflow is repository work;
   making it an enforced required check is a manual GitHub administrator action.
6. **One independently deployable outcome per phase.** Preserve interfaces or
   run old/new in parallel. Rollback is redeploying the prior phase plus its
   compatible database/runtime.
7. **Living plan.** Mark tasks/phases `COMPLETE`, `DESCOPED`, or `DROPPED` only
   after evidence. Update the testability table, rung, and residual-risk register
   as each component crosses its milestone.
8. **Living executable docs.** Any topology, command, branch, endpoint, or
   deployable change updates this plan, `ARCHITECTURE.md`, README, and
   `.github/copilot-instructions.md` in the same PR.
9. **Pre-implementation red-team.** Re-run H1-H8 from the migration hazard
   catalog against the actual phase diff before writing code. Record both fired
   and cleared hazards in the phase PR.
10. **No silent test downgrade.** A skipped, `NO-SOURCE`, quarantined, or
    non-enforced gate is reported as such; it is never presented as green test
    coverage.

## 8. Migration safety net

### 8.1 Feature flags and coexistence

Use existing database-backed `MSysConfig` as the initial flag mechanism, with
typed constants, owner, default, scope, expiry phase, and audit logging:

| Flag | Purpose | Default | Removal |
|---|---|---|---|
| `MODERN_SOAP_ADAPTER_ENABLED` | Historical Phase 4 cutover and rollback control; no longer read | Retired | Removed from active routing after Phase 4 cutover |
| `MODERN_WEB_UI_ENABLED` | Route selected users/roles to modern ZK/Jakarta UI | Off | Phase 5 after complete cutover |
| `PARALLEL_BEHAVIOR_DIFF_ENABLED` | Execute safe read-only old/new comparisons | Off | Phase closing each comparison |
| `POSTGRES16_CUTOVER_ENABLED` | Deployment-level cutover marker, not an application dual-write switch | Off | Phase 6 after cutover |

Flags must not bypass authorization. Write operations are never dual-executed
unless idempotency and reconciliation are explicitly designed.

### 8.2 Data migration strategy

- **CI/demo:** PostgreSQL storage is ephemeral. Stop the service, delete its
  disposable volume, start PostgreSQL 16, and restore/re-seed. This is an
  explicit destructive reset.
- **Durable environments:** Create and verify backup; restore into a parallel
  PostgreSQL 16 instance; run extensions/schema/metadata/row-count/LOB checks;
  replay read-only and approved write seams; freeze writes; take final delta or
  final backup/restore; cut connection configuration; retain the old instance
  read-only for rollback.
- **Application dictionary:** Migration status, `AD_System.Version`, generated
  models, terminology, sequences, role access, process/validator/workflow class
  bindings, and report definitions are first-class validation targets.
- **Oracle and other engines:** No engine is removed or upgraded without an
  approved support matrix and engine-specific rehearsal. A PostgreSQL success is
  not evidence of Oracle/MySQL/MariaDB parity.

### 8.3 Rollback by phase

| Phase | Rollback |
|---|---|
| 1 | Revert wrapper/lock/test wiring commits; retain existing build files and binaries. |
| 2 | Redeploy Phase 1 Java 17 artifacts and restore prior Java/app-server pins; no schema change permitted. |
| 3 | Redeploy Phase 2 artifacts and discard the ephemeral CI/install database; durable data is not modified by this phase. |
| 4 | Flip per-operation routing flags back to XFire and redeploy prior adapter; no SOAP schema change. |
| 5 | Route users/roles back to legacy ZK/Tomcat 9; preserve shared DB compatibility throughout coexistence. |
| 6 | Point application connections back to retained PostgreSQL 14 and replay/reconcile only explicitly logged post-cutover writes. |
| 7 | Rebuild/redeploy the last Ant-produced release artifact retained for one release cycle. |

### 8.4 Transitional-insecure-state register

No insecure transition is approved by default.

| ID | State | Why needed | Scope | Closes | Residual risk |
|---|---|---|---|---|---|
| T5e-1 | Internal authenticated handoff between public Tomcat 9 and loopback Tomcat 10 | Avoid a second login while `/webui` is split across runtimes | `/webui`; signed single-use 30-second ticket and shared 0600 key | Phase 5h | Remains open; a process with host-account/key access and loopback reach could forge a modern identity, without widening the existing host trust boundary. |
| T5f-1 | Multi-context proxying and forwarded secure state | Migrate whole non-SOAP contexts independently while Tomcat 9 remains the only public ingress | `/admin`, `/`, `/mobile`, `/adempiere`, `/wstore`; forwarded secure metadata only for four confidential `/wstore` paths | Phase 5h | A classifier/header-boundary defect could expose an undeclared route, trust spoofed secure state, leak loopback coordinates, or fall back incorrectly. Database-neutral controls are green, and the public-origin runtime observations are green in run 33379849664; the transitional proxy and forwarded secure state are not removed by observing them, so this closes in Phase 5h. |

If a phase adds a temporary security weakening, the row must state the exact
route/service, compensating control, scanner waiver, owner, and closing task.
Generic permit-all chains are prohibited.

### 8.5 Oracle and seam contracts

Freeze and version:

- WSDL, SOAP XML requests/responses/faults, HTTP status/headers/charset;
- servlet route inventory and auth class;
- login/role/client/org/warehouse/session outcomes;
- process parameters, summaries, output rows, and attachments;
- document transition matrix and generated document side effects;
- accounting facts by schema/currency/segment;
- scheduler/processor execution, retry, and transaction outcome;
- seed checksum, `migration/build.properties` release selector, exact
  release-scoped migration-file manifest, schema inventory, migration status,
  generated-model diff;
- published Maven coordinates/POMs, release artifact names, checksums, and
  signatures;
- installer archive manifest and startup smoke;
- semantic UI snapshots for login/menu/windows/process dialogs.

Normalize timestamps, generated IDs, and ordering only where the contract
explicitly permits it. Store the normalization policy with each fixture.

### 8.6 Testing strategy

- **Phase 1:** pure unit and characterization tests on the core.
- **Phase 2:** JDK/runtime startup smoke for Swing and server workers.
- **Phase 3:** promote disposable-DB integration and migration replay into the
  installer/full-distribution path, then add metadata-graph coverage.
- **Phase 4:** SOAP/HTTP contract and route/security tests.
- **Phase 5:** web session/tenant concurrency, semantic UI/e2e, and parallel
  behavior diff.
- **Phase 6:** data upgrade/rollback, performance, and observability assertions.
- **Phase 7:** artifact reproducibility and complete build parity.

Quarantined tests are listed by class/module with reason and closing phase. A
quarantine without an owner and closing phase fails the gate.

### 8.7 Observability

Add low-cardinality metrics and traces for:

- HTTP/SOAP route, status, latency, and auth class;
- JDBC pool utilization, transaction duration/rollback, slow query category;
- process/workflow/document action, result, duration, and retry;
- scheduler due time, start delay, duration, duplicate prevention, heartbeat;
- accounting posting count, duration, error, and unbalanced-fact rejection;
- migration file/step, duration, retry, and status;
- old/new comparison count, mismatch category, and cutover flag state.

Do not place credentials, SQL bind values, business document payloads, personal
data, or tenant secrets in telemetry.

## 9. Open questions and stakeholder decisions

These are genuine product/operations decisions that cannot be derived from the
checkout:

1. **Production and oracle:** Is ADempiere currently in production, with real
   users, and may the team capture sanitized read-only I/O from a running
   instance?
2. **Active surfaces:** Which of Swing, ZK, POS, SOAP, store, CM, mobile,
   serverApps, monitoring, WildFly, Jetty, and domain packages are actively used?
3. **Database support:** Are PostgreSQL and Oracle both contractual? Are MySQL or
   MariaDB deployments active and supported?
4. **ZK:** Is a maintained ZK 10.x edition technically and commercially
   acceptable, including its licensing/support terms?
5. **External consumers:** Who owns each SOAP/servlet integration, and what
   compatibility/downtime windows can they accept?
6. **Data migration:** What are database size, custom extensions/schema,
   downtime objective, retention requirement, and rollback point objective?
7. **Security/identity:** Which LDAP/OIDC/browser-token paths are in use, and
   what identity provider is authoritative?
8. **CI enforcement (manual action):** A GitHub administrator must enable branch
   protection on `develop` and make the Phase 1 checks required. This cannot be
   completed from repository files.
9. **Release branches:** Confirm that `develop` is modernization trunk and define
   whether/how fixes are backported to `master` or `hotfix/*`.
10. **Capacity:** Team size and acceptable parallel work determine whether UI,
    API, and data phases can overlap after their prerequisite gates.
