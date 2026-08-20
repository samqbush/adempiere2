# ADempiere Modernization Plan

> **Evidence base:** [`ARCHITECTURE.md`](ARCHITECTURE.md), describing local
> branch `develop` at commit
> `59557cc2ee85ac938cd4f31a246d891bc2b15b8f` (2023-12-11).
>
> **Planning date:** 2026-08-20.
>
> **Status:** Proposed. No implementation phase is complete until its
> Verification & Exit Criteria are executed and recorded here.

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

CI enforcement remains **[UNVERIFIED]** because required checks and branch
protection are remote repository settings, not files in this checkout.

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
| Swing client and POS core | Gradle client assembles; no tests execute; application not booted | **A - Freeze-then-lift** behind core seam tests and a scripted login/menu smoke path | **L1** | **Phase 2**: client boots against a disposable database on JDK 21 and one smoke/contract test passes in CI | **L3** |
| Background server and scheduler | Gradle server assembles; tests are `NO-SOURCE`; no database-backed boot | **A - Freeze-then-lift** with processor discovery/scheduler characterization | **L1** | **Phase 2**: server starts on JDK 21 against disposable PostgreSQL and one processor/scheduler test passes | **L3** |
| Full Ant distribution and installer | The complete reactor is configured to build `tools` before `base`, but no local product archive or setup run was completed | **B - Beachhead-then-expand.** Make a minimal installable distribution first, then expand reactor coverage. | **L1** | **Phase 3**: installer builds, silent setup completes, product starts, and at least one DB-backed test passes in CI | **L3** |
| Database seed and XML migrations | Existing CI path and artifacts are documented, but no local restore/release-scoped migration run was completed | **A - Freeze-then-lift** using seed checksum, schema inventory, and release-scoped migration contracts | **L2**: source/seed artifacts can be frozen, execution not observed | **Phase 3**: disposable PostgreSQL restore plus release-scoped migration replay succeeds in CI | **L3** |
| ZK web UI/session boundary | Ant-only; 298 Java files reference `org.zkoss` (279 through imports); no boot or tests | **B - Beachhead-then-expand** via a login/menu/read-only window walking skeleton, then incremental screen migration | **L1** | **Phase 5**: modern ZK/Jakarta slice boots on the target runtime and passes session/tenant isolation tests | **L3**, then L4 as route/e2e coverage expands |
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

Phase 3 expands that gate to the full product distribution and database restore/
migration path. Making either workflow an **enforced required status check** is
a separate manual GitHub administrator action. Until a human configures branch
protection, CI runs but does not necessarily block merges.

### 3.5 Residual-risk register

| ID | Component | Residual risk below L4 | Accepted until | Closing action |
|---|---|---|---|---|
| R1 | Core | Unit/characterization coverage initially protects only selected seams; metadata-only failures remain | Phase 3 | Add disposable-DB metadata/process integration tests and migration replay. |
| R2 | Swing/POS | Compilation does not prove startup, rendering, or operator workflows | Phase 2 | Script login, menu load, and one document/process smoke path. |
| R3 | Background jobs | Thread context, locking, and duplicate-worker behavior remain unproven | Phase 2/6 | Add processor discovery, scheduler, transaction, and observability checks. |
| R4 | Web UI | First modern slice may be self-frozen without a trustworthy external oracle | Phase 5 | Human/domain review blesses the first snapshot; later changes diff against it. |
| R5 | SOAP/servlets | Unknown consumers and undocumented route classes may break | Phase 4 | Inventory consumers, freeze WSDL/HTTP fixtures, and run parallel replay. |
| R6 | Database | Production size, custom schema, supported engines, and rollback windows are unknown | Phase 6 | Approve customer-specific migration runbook and rehearse on a sanitized copy. |
| R7 | Extension metadata | Reflected class names and dictionary bindings can remain compile-clean but runtime-broken | Phase 3 | Add metadata graph validation for processes, validators, models, and workflows. |
| R8 | CI governance | Checks may run without blocking merges | Human action after Phase 1 | Enable branch protection and required status checks on `develop`. |

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
| Web UI | Upgrade ZK in place through supported intermediate releases to a Jakarta-compatible ZK 10.x line **[UNVERIFIED upstream/licensing]** | Reuses hundreds of existing controllers and ZUL assets. A full SPA rewrite would duplicate the metadata-driven UI and is not justified before usage evidence. |
| SOAP | Apache CXF 3.6.x `javax` bridge in Phase 4, then CXF 4.x on Jakarta in Phase 5, preserving current WSDL and wire behavior | Replaces retired XFire without forcing consumers to migrate protocols or creating a Tomcat 9/Jakarta mismatch. REST/OpenAPI is additive only after SOAP parity. |
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
  | `javax.mail` | 25 | Phase 5 after usage/API compatibility review |
  | `javax.jms` | 23 | Phase 5 after broker/client ownership review |
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
- **Decision:** Introduce a short-lived CXF 3.6.x `javax` adapter on Tomcat 9
  that serves the frozen WSDL and delegates to existing services, then upgrade
  that adapter to CXF 4.x during Phase 5's Jakarta runtime transition (level
  2/3: dependency swap plus adapter).
- **Alternatives considered:** REST-only rewrite and direct XFire removal. Both
  require coordinated consumer migration that cannot be assumed.
- **Consequences:** Temporary dual endpoint implementation and replay/diff
  infrastructure; consumers see no intentional contract change.

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
- Published Gradle artifacts move to Java 21 bytecode only under a new release
  version whose POM/release notes declare JDK 21; no existing artifact version
  is overwritten with a higher class-file level.
- **Hazard red-team:** H1 cleared (no dependency family removed); H2 fired for
  the Java major and requires explicit internal/removal recipes; H3 fired and is
  task 2.2; H4/H5/H6 cleared; H7 applies; H8 fired because commands/runtime pins
  change and all executable docs update in this PR.

#### Verification & Exit Criteria (Definition of Done)

- [ ] `./gradlew build` is green across all 28 unique Gradle projects on JDK 21
      in CI.
- [ ] The Gradle smoke-runtime task creates a temporary runnable layout and
      restores the committed seed without invoking the Ant installer.
- [ ] Swing login/menu/process smoke passes against that disposable database.
- [ ] Background processor/scheduler smoke passes exactly once and verifies
      transaction/context cleanup.
- [ ] Empty Tomcat 9 starts on JDK 21 with the documented JVM module flags.
      Application artifact deployment is explicitly deferred to Phase 3.
- [ ] All Gradle, CI, installer-template, launch-script, version-allowlist, and
      runtime-documentation pins owned by Phase 2 accept/use JDK 21. Remaining
      Ant/Javadoc/XMLBeans source-target pins are enumerated and owned by Phase 3.
- [ ] Published artifacts use class-file version 65 only under a new release
      version, and POM/release documentation declares JDK 21 as the minimum.
- [ ] JDK-internal API inventory is empty or each remaining item has an owner,
      adapter, and closing phase.
- [ ] Core seam snapshots match Phase 1.

### Phase 3: Full distribution and database testability (T-shirt size: XL)

**Goal:** Cross the full-product, installer, migration, and extension
Testability Milestones on the JDK 21/Tomcat 9 bridge.

**Regime:** Full product transitions from dark to lit.

**Safety rung:** L3. Residual risk: external SOAP/web consumers and Jakarta/ZK
final stack are not yet migrated.

**Prerequisites:** Phase 2 merged to `develop`.

**Duration estimate:** 4-6 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 3.1 | Make all 32 Ant `jar` reactor entries plus the installer compile/package on JDK 21; reconcile them bidirectionally with 28 unique Gradle projects | Distribution | Phase 2 |
| 3.2 | Add Gradle lifecycle tasks that invoke and inventory the Ant distribution without pretending parity | Build bridge | 3.1 |
| 3.3 | Restore the `394lts` PostgreSQL seed and apply the release-scoped migration set selected by `migration/build.properties` (currently 249 files from `393lts-394lts` and `394lts-3.9.4.001`) | Database | 3.1 |
| 3.4 | Add metadata graph validation for model classes, processes, validators, workflows, entity packages, and generated models | Metadata | 3.3 |
| 3.5 | Run silent setup, produce ZIP/TAR, start the installed product, and exercise health/login/process/database seams | Installer/runtime | 3.2-3.4 |
| 3.6 | Generate artifact manifests and compare Ant output against an approved baseline | Packaging | 3.5 |
| 3.7 | Expand CI to run the full no-DB path on every PR and the restore/release-scoped-migration path when migrations/build/runtime inputs change; include `.github/actions/**` as database-gate inputs, not only workflow YAML | CI | 3.3-3.6 |

#### Risks & mitigations

- **Risk:** Manual reactor omissions create false package success.
  **Mitigation:** Compare Ant modules, Gradle modules, archives, and installer
  contents as machine-readable manifests.
- **Risk:** Path filtering skips a migration-sensitive change.
  **Mitigation:** Replace hand-maintained filters with generated dependency/input
  sets or run the DB gate on every relevant PR until evidence supports narrowing.
- **Risk:** Seed/migration replay mutates durable data.
  **Mitigation:** CI database is explicitly ephemeral and uniquely named.

#### Decisions made

- Ant remains authoritative through Phase 3; Gradle is the orchestrator and
  eventual replacement.
- The database gate uses PostgreSQL 14.6 initially to isolate product
  resurrection from the database-major move.
- Integration tests no longer default silently off in CI.
- **Hazard red-team:** H1 fired (all 32 Ant `jar` entries, installer, and 28
  unique Gradle projects inventoried bidirectionally; no silent quarantine);
  H2/H3 cleared because no new major is
  introduced; H4 cleared because routes are not cut over; H5 applies only to
  ephemeral CI and the explicit destructive reset/re-seed is approved; H6
  cleared; H7 applies; H8 fired because build topology/commands change.

#### Verification & Exit Criteria (Definition of Done)

- [ ] `ant build -Dnodbrestore=true` succeeds on JDK 21.
- [ ] `ant build -Dnodbrestore=false` succeeds against a disposable PostgreSQL
      14.6 service and replays the release-scoped migration set.
- [ ] At least one meaningful DB-backed integration test passes in CI.
- [ ] Silent setup produces runnable archives; installed product starts and
      passes the smoke checklist.
- [ ] Metadata graph validation and generated-model diff are clean.
- [ ] Artifact/module manifests account for every approved deployable; no
      Phase 1 quarantine remains unnamed.
- [ ] Phase 1-2 characterization fixtures remain equivalent.

### Phase 4: Contract-preserving API and edge modernization (T-shirt size: XL)

**Goal:** Replace XFire and establish explicit security/routing contracts without
breaking active integrations.

**Regime:** Legacy endpoint remains lit; new CXF/Jakarta adapter transitions from
dark to lit.

**Safety rung:** L3 -> L4 for active API operations.

**Prerequisites:** Phase 3 merged to `develop`; consumer inventory approved.

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
| 4.1 | Inventory active SOAP operations, WSDL consumers, all 11 `web.xml` descriptors, and every route class | Edge/API | Phase 3 |
| 4.2 | Capture normalized WSDL, XML request/response/fault, auth, HTTP status/header, and charset fixtures from the best oracle | Contracts | 4.1 |
| 4.3 | Introduce a short-lived CXF 3.6.x `javax` deployable on the existing Tomcat 9 bridge that delegates to existing service/domain interfaces | SOAP adapter | 4.2 |
| 4.4 | Implement explicit rules for anonymous, authenticated, service, infra, and callback traffic; add tenant/auth negative tests | Security | 4.3 |
| 4.5 | Parallel-run and diff old/new endpoints; cut operations over incrementally behind `MSysConfig` flags | API | 4.3-4.4 |
| 4.6 | Retire XFire only after all active consumers pass and rollback has been rehearsed | API | 4.5 |
| 4.7 | Add REST/OpenAPI only for new use cases; do not replace SOAP contracts implicitly | API | 4.5 |

#### Risks & mitigations

- **Risk:** Unknown consumers rely on serialization quirks. **Mitigation:**
  Record/replay real traffic where permitted and normalize only unstable fields.
- **Risk:** Route rewrite drops anonymous or monitoring traffic. **Mitigation:**
  Required route-class table and negative/positive tests.
- **Risk:** Temporary dual endpoints broaden attack surface. **Mitigation:**
  Bind the new adapter to controlled ingress and register any temporary exception.

#### Decisions made

- SOAP compatibility is preserved; REST is additive.
- Cutover unit is one operation/route class, not the whole API.
- XFire removal is deferred until consumer evidence exists; it is not dropped
  merely because the framework is old.
- **Hazard red-team:** H1 fired (grep all XFire coordinates/imports/descriptors
  before removal); H2 fired (XFire/CXF XML, JAX-WS, test engine/config recipes);
  H3 cleared because Phase 4 stays on the pinned Tomcat 9/JDK 21 bridge; H4 fired and the
  route table is mandatory; H5 cleared; H6 applies to any dual-endpoint/security
  exception and requires the register; H7/H8 apply.

#### Verification & Exit Criteria (Definition of Done)

- [ ] Every active WSDL operation has replay fixtures for success and fault
      behavior.
- [ ] Old/new WSDL, status, headers, XML, auth, and error semantics match the
      approved normalization policy.
- [ ] All five route classes have explicit positive and negative tests.
- [ ] Parallel-run diffs are clean for the approved observation window.
- [ ] Rollback to XFire is rehearsed before any consumer cutover.
- [ ] XFire artifacts/imports are absent only after all active dependents move;
      the full-product build target remains green.
- [ ] Any transitional insecure state is registered with a closing task and
      scoped exception.

### Phase 5: ZK/Jakarta web transition (T-shirt size: XL)

**Goal:** Move the primary browser UI and servlet runtime to a supported
Jakarta-compatible stack through vertical slices.

**Regime:** Legacy ZK/Tomcat 9 remains lit; modern ZK/Tomcat 10.1 slice moves
from dark to lit, then expands.

**Safety rung:** L3, progressing toward L4.

**Prerequisites:** Phase 4 merged to `develop`; ZK support/licensing decision
approved.

**Duration estimate:** 8-16 sprints.

#### Tasks

| ID | Task | Component | Blocked by |
|---|---|---|---|
| 5.1 | Inventory all 298 `org.zkoss`-referencing Java files (279 through imports), ZUL assets, custom components, listeners, filters, servlet descriptors, and shared `base`/`serverRoot` web coupling | Web UI | Phase 4 |
| 5.2 | Select supported intermediate ZK upgrade steps and run vendor/automated migration recipes where available | Web UI | 5.1 |
| 5.3 | Create a per-module/package `javax` ownership table. Explicitly exclude Java SE `javax.swing`, `javax.print`, `javax.sql`, `javax.imageio`, `javax.crypto`, and `javax.naming` from blanket Jakarta recipes; assign servlet/XML/mail/JMS/EJB/annotation/activation ownership | Web/runtime | 5.1 |
| 5.4 | During coexistence, use a pinned packaging-time namespace transformer to produce the Jakarta WAR from the `javax` application while retaining the legacy WAR; add bytecode/archive verification | Web/runtime | 5.2-5.3 |
| 5.5 | Upgrade the SOAP bridge from CXF 3.6.x to CXF 4.x in the transformed/Jakarta deployment and rerun all Phase 4 contracts | SOAP/runtime | 5.4 |
| 5.6 | Build login -> role -> menu -> read-only dynamic window walking skeleton on Tomcat 10.1/JDK 21 | Web UI | 5.4-5.5 |
| 5.7 | Add concurrent session/tenant/language cleanup tests and semantic UI snapshots | Security/UI | 5.6 |
| 5.8 | Expand by vertical slice: read/write window, process dialog, report, upload/download, then POS/domain custom screens | Web UI | 5.7 |
| 5.9 | Parallel-run behind `MSysConfig`; route users/roles selectively; diff behavior and performance | Runtime | 5.8 |
| 5.10 | Retire Tomcat 9 web deployment after parity/rollback; then migrate owned source packages to Jakarta with scoped OpenRewrite recipes and remove the packaging transformer | Runtime/source | 5.9 |

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
- Login/menu/read-only window is the walking skeleton.
- Old and new web deployments coexist through role/user flags; no blanket
  cutover.
- Tomcat 9 retirement occurs in this phase, not during the JDK 21 phase.
- Packaging-time transformation is the chosen coexistence mechanism. It is
  removed after the source-level Jakarta migration; dual namespace source trees
  are not maintained.
- **Hazard red-team:** H1 fired (full ZK dependency/import/asset set); H2 fired
  (ZK APIs, `javax` -> `jakarta`, descriptors, tests/config); H3 fired (Tomcat
  runtime and all deployment pins); H4 fired for browser/public/infra routes; H5
  cleared; H6 applies to any compatibility auth shim; H7/H8 apply.

#### Verification & Exit Criteria (Definition of Done)

- [ ] Modern login, role selection, menu, and representative windows/processes
      pass semantic snapshot and database-effect parity checks.
- [ ] Concurrent session tests prove no client/org/role/user/language context
      leaks across requests.
- [ ] All active route classes pass on Tomcat 10.1/JDK 21.
- [ ] `./gradlew` and full distribution/database CI gates remain green.
- [ ] Parallel-run performance and error rates meet approved thresholds.
- [ ] Rollback to legacy ZK/Tomcat 9 is rehearsed.
- [ ] Tomcat 9/ZK legacy artifacts and the namespace transformer are removed only
      after the complete dependent set is source-migrated and CXF 4.x contracts
      are green.

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
| `MODERN_SOAP_ADAPTER_ENABLED` | Route selected operations to CXF adapter | Off | Phase 4 after complete cutover |
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
| T0 | None currently approved | - | - | - | Any proposed permit-all, CSRF disable, open metrics endpoint, placeholder secret, or disabled scanner must add a row before merge. |

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
- **Phase 3:** disposable-DB integration, metadata graph, release-scoped migration replay,
  installer, and full distribution.
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
