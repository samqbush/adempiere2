# ADempiere
  **Short status**:
![GitHub release (latest by date)](https://img.shields.io/github/downloads/adempiere/adempiere/3.9.4/total)
![GitHub code size in bytes](https://img.shields.io/github/languages/code-size/adempiere/adempiere)
![GitHub repo size](https://img.shields.io/github/repo-size/adempiere/adempiere)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/main.yml/badge.svg?branch=develop)](https://github.com/adempiere/adempiere/actions/workflows/main.yml)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/build_with_gradle.yml/badge.svg?branch=develop)](https://github.com/adempiere/adempiere/actions/workflows/build_with_gradle.yml)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/publish_with_gradle.yml/badge.svg?branch=3.9.4)](https://github.com/adempiere/adempiere/actions/workflows/publish_with_gradle.yml)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/release.yml/badge.svg?branch=3.9.4)](https://github.com/adempiere/adempiere/actions/workflows/release.yml)
 \
 \
**Issues and Pull Requests**:
![GitHub issues](https://img.shields.io/github/issues/adempiere/adempiere)
![GitHub closed issues](https://img.shields.io/github/issues-closed/adempiere/adempiere)
![GitHub pull requests](https://img.shields.io/github/issues-pr/adempiere/adempiere)
![GitHub closed pull requests](https://img.shields.io/github/issues-pr-closed/adempiere/adempiere)
 \
 \
**Social Media**:
[![Discord](https://badgen.net/badge/icon/discord?icon=discord&label)](https://discord.gg/T6eH6A7PJZ)
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/adempiere/adempiere?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

The _ADempiere Business Suite_ _ERP/CRM/MFG/SCM/POS_ is done the Bazaar way in an open and unabated fashion. \
Focus is on the Community that includes Technical Specialists, Functional Specialists, Implementors and End-Users. 

## Reproducible builds

The core/module gate uses the committed Gradle 8.10.2 wrapper on JDK 21 and
publishes Java 21 bytecode:

```bash
./gradlew build verifyJava21Bytecode verifyTestClassification \
  verifyTestResults verifyPublicationContracts verifyJdkInternalApiInventory \
  verifyJdepsInternals --dependency-verification=strict
```

This covers the root and 31 included Gradle projects. It is not a replacement
for the full Ant distribution build.

Phase 3 adds guarded Gradle orchestration around the authoritative Ant product
reactor:

```bash
./gradlew phase3NoDatabaseDistribution --dependency-verification=strict
```

The installed-product gate requires a disposable local PostgreSQL 14.6 service
and runs the full Ant install, release-scoped migration, database evidence,
metadata checks, Phase 2 DB-backed smoke, and installed Tomcat 9 smoke:

```bash
xvfb-run -a ./gradlew phase3InstalledProduct \
  -Pphase2DbSystemPassword='<password>' \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

Both Phase 3 tasks refuse any database identity other than the exact local
`adempiere_phase3_ci` database and role, refuse installation paths outside
`build/phase3`, and delete only objects carrying the Phase 3 ownership markers.
The installed manifest records per-file hashes rather than timestamp-sensitive
archive hashes. The JBoss facet is the only explicit
Phase 3 quarantine because its checked-in library implements the JDK-removed
`java.security.acl.Group`; Tomcat 9 is the acceptance bridge.

Phase 5a adds a database-neutral inventory and target-stack gate:

```bash
./gradlew phase5aFinalVerification --dependency-verification=strict
```

It byte-compares the reviewed ZK source/runtime, web-asset, namespace, and route
inventories; verifies the public ZK CE 10.3.0.1-jakarta target; and preserves
the frozen Phase 4 SOAP assertions while Phase 5 takes ownership of non-SOAP
routes.

Phase 5b freezes the legacy web behaviour before any ZK source crosses to
Jakarta:

```bash
./gradlew phase5bFinalVerification --dependency-verification=strict
./gradlew phase5bLegacyWebOracleSmoke -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

The frozen tree lives in `contracts/legacy-web-v1/`. The database-backed smoke
boots the installed Tomcat 9 product, drives the real ZK 3.6 AU protocol through
login, role selection, menu open and logout, captures twice with a fixture reset
between runs, and replays both captures against the frozen oracle. The
database-neutral gate verifies recursive WAR and nested-JAR logical digests,
asserts that all 84 deployed non-SOAP routes are covered by 82 request vectors
with a stated proof strength or excluded with an owner and a closing gate,
proves the normalizer is not over-normalizing, and pins 24 runtime coordinates.

Because no WAR binaries are committed, rollback depends on reproducible
regeneration from the pinned source commit: 2287 archive entries are proven
byte-identical across two independent clean builds, and the 13 entries that
cannot be (code signatures, installation-configured `.jnlp` files, ZIP envelope
metadata) are pinned individually and owned by later phases.
`contracts/legacy-web-v1/domain-review.md` records the review that had to happen
before a self-frozen baseline could become a gate.

Phase 5c adds the packaging-only Jakarta beachhead and semantic browser gate:

```bash
./gradlew phase5cFinalVerification --dependency-verification=strict
./gradlew phase5cRollbackRehearsal -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

The first command verifies the ZK CE `10.3.0.1-jakarta` WAR, Eclipse
Transformer fixtures and report-only legacy-WAR scan, additive installed/release
overlay, Phase 4 API preservation, and rollback artifacts. The second also
replays the Phase 5b wire oracle and two isolated Playwright Java semantic
captures against Tomcat 9 using only checksum-verified Chromium artifacts.

Phase 5d replaces the Phase 5c 503 marker with the functional modern slice and
crosses the web UI Testability Milestone:

```bash
./gradlew phase5dFinalVerification --dependency-verification=strict
./gradlew phase5dModernWebSmoke -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

`webui-modern.war` keeps its artifact name and `/webui-modern` context path but
now contains the migrated ZK compile closure, hand-written Servlet 6 and ZK 10
descriptors for the login/role/menu/window routes, and the shared ADempiere
runtime repackaged from the Ant-built `Adempiere.jar`, `packages.jar` and
`AdempiereSLib.jar` using the Phase 4 pattern. The database-backed gate drives
login, role selection, desktop/menu and the read-only "Error Message" window
twice with a marker-guarded fixture reset between captures, compares all eleven
comparable semantic facts and the zero-write database effect against the frozen
legacy baseline at matching capture ordinals, and replays the complete Phase 4
SOAP corpus in a third session while a modern ZK session is authenticated in the
same Tomcat 10 JVM.

`phase5dFinalVerification` deliberately does not chain
`phase5cFinalVerification`: that gate still asserts the 503 marker Phase 5d
removed. The Phase 5c assertions that remain true - the transformer determinism
and corpus report, the ingress/session ADR, the installed/release overlay, and
the artifact rollback - are depended on individually.

Phase 5e routes selected sessions to that slice through the public Tomcat 9
`/webui` ingress, and proves concurrent isolation:

```bash
./gradlew phase5eFinalVerification --dependency-verification=strict
./gradlew phase5eCohortRoutingSmoke -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

New sessions are selected after ordinary authentication and role selection from
three strict, fail-closed, system-level `AD_SysConfig` rows
(`MODERN_WEB_UI_ENABLED`, `MODERN_WEB_UI_USER_IDS`, `MODERN_WEB_UI_ROLE_IDS`).
A duplicate, malformed or unreadable row invalidates the complete configuration
and keeps every new session legacy. The decision is taken once, is sticky for the
life of the session, and never moves an active session.

A selected session rotates its Tomcat 9 session identifier exactly once and is
handed to Tomcat 10 with a versioned, HMAC-SHA-256, single-use, 30-second,
loopback-only ticket that never reaches the browser. The modern application is
mounted internally at the same `/webui` path it is served on publicly, so the
router forwards HTML, JavaScript, CSS and ZK asynchronous-update bodies
verbatim. The browser holds exactly one public cookie and never sees an internal
session identifier. An established modern session never falls back to the legacy
runtime.

Before running the database-backed gate, provision the shared handoff key:

```bash
./gradlew provisionPhase5eHandoffKey
```

It generates at least 32 bytes from the OS CSPRNG at mode `0600`, outside every
archive under `ADEMPIERE_HOME`. The repository ships no key and no placeholder.

`phase5eFinalVerification` chains `phase5dFinalVerification`, so the direct
`/webui-modern` lane remains an independent regression gate.

Both Phase 5e gates are executed and green. The database-backed smoke records
all 23 public-origin cohort, isolation, lifecycle, SOAP-coexistence, and
secret-hygiene rows as passing; see
`docs/modernization/phase-5e-evidence.md`.

The merged R15 hardening increment addresses transition defects measured
while validating https://github.com/samqbush/adempiere2/pull/18. Immutable
legacy theme images now have a separate, closed `GET`/`HEAD` pass-through policy
while the deciding response owns the redirect barrier and while the selected
modern affinity still awaits its context-root rotation; general `STATIC_ASSET` routes such as
`/zkau/view/` remain refused. Routed-session END now assigns one cleanup owner
and one navigation owner per transport: AU/XHR receives the ZK redirect command
and a racing top-level page receives HTTP redirect to the same context root,
while same-transport duplicates cannot redirect again and a fresh request is
undecided. The neutral and bridge regressions and the 24th
runtime matrix row are implemented. PR 20 merged at `ccffe15ff`, and its
post-merge `develop` run 33829108255 is green. PR 18 run 33893941634 additionally
proved the second transition interval: both 12-step modern captures completed
without the earlier `progress2.gif` refusal, and the corrected `/webui/`
no-legacy-fallback control passed.

Phase 5f merged to `develop` as PR #11 at `83aeb8536`; both of its gates are
executed and green:

```bash
./gradlew phase5fFinalVerification --dependency-verification=strict
./gradlew phase5fJakartaWebRoutesSmoke \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

The database-neutral gate has been executed green twice. It closes the scope at
82 deployed and 30 explicitly non-deployed mappings, builds isolated generated
Jakarta source/web trees without rewriting legacy sources, precompiles all 25
retained `/wstore` JSPs, and produces five deterministic modern context WARs for
`/admin`, `/`, `/mobile`, `/adempiere`, and `/wstore`. The retained Phase 5e
`webui-modern.war` now includes the source-native read-only `/timeline` route
and serves only the exact historical theme DSP path as static Phase 5d CSS;
every other DSP path, the interpreter, and its five vendor TLDs remain absent.

Routing is whole-context, same-path, sticky for existing sessions, independently
policy-bound, and fail-closed without legacy fallback. The installed product and
both 394LTS archives stage one Phase 5f WAR per context under
`tomcat10-api/phase5f/`, preserve Phase 4 CXF and Phase 5e `/webui`, and retain
pristine rollback WARs. `/mobile`, `/adempiere` and `/admin` remain legacy, and
Phase 5g does not enable them - each carries an open disposition closed by
`phase5g-web-parity-gate` in `5g-7`. `/` and `/wstore` are the two eligible
modern contexts and both are now observed.

`phase5fJakartaWebRoutesSmoke` is implemented as six public-origin shards plus
Phase 4 SOAP coexistence. It is **Executed and green** in run 33379849664 on commit `9ba62875d`, which recorded
129 public-origin observations with zero vector failures - `/` (16), `/wstore`
(68), `/webui` (6), `/admin` (4), `/mobile` (14), `/adempiere` (21) - and passed
`verifyPhase5fSwitchBaseline`, `capturePhase5fSoapCoexistence`,
`verifyPhase5fBackgroundProcessorsQuiesced` and the strict aggregate
`verifyPhase5fRuntimeEvidence`, which validated 82 legacy routes, all 37
eligible modern routes and 45 explicitly unexecuted modern routes. The 82 route observations and
their route-specific database effects are therefore observed, and the contract
ledgers carry the executed marker. The 25 `/wstore` JSP precompile rows stay
`contract-only-runtime-observation-pending`, because only three of those pages
are reached by a route vector. T5e-1 remains open;
T5f-1 closes in Phase 5h. Required checks and branch protection remain a manual
repository-administrator action. See
`docs/modernization/phase-5f-evidence.md`.

Phase 5g - web UI functional parity - is active. It is decomposed into
sequential sub-increments `5g-0` through `5g-7`, each cut from `develop` and
merged before the next begins; `MODERNIZATION_PLAN.md` carries the decomposition
and `docs/modernization/phase-5g-web-parity-adr.md` is binding.

**No modern business write, document transition, process execution, report or
upload/download has ever been observed.** The modern runtime is proven for
login, role selection, menu and a read-only window. Phase 5f proved that 82
routes behave like the frozen legacy oracle; that is route parity, not write
parity.

`5g-0` ships no runtime code. It reconciles the Phase 5f documentation, records
the Phase 5g ADR, and adds the reviewed discovery inventories - 351 classified
dictionary processes, 174 callout columns and 197 extension surfaces - under
`contracts/phase5g-web-parity-v1/`:

```bash
./gradlew phase5g0FinalVerification --dependency-verification=strict
```

It regenerates the inventories from the seed dictionary and the reactor sources
and requires an exact match, so a new extension callout, model validator or
changed process class fails the build rather than silently widening a later
fixture's blast radius.

`5g-0` also **opens** a named disposition for `/mobile`, `/adempiere` and
`/admin` in `docs/modernization/phase-5g-disabled-context-disposition.md`. Phase
5g does not enable those contexts. `phase5g-web-parity-gate` - already named as
their closing gate by the frozen Phase 5f contract - is defined there as the
`5g-7` gate that requires a recorded `migrate`, `retire` or `narrow-5h-scope`
disposition with its evidence, and Phase 5h is blocked behind it.

`5g-1a` and `5g-1a-x` froze and accepted the legacy Business Partner write
oracle. `5g-1a-y` is the merged R14 oracle-only prerequisite from
https://github.com/samqbush/adempiere2/pull/19: candidate run
33785079015 replaced cached-startup workflow process/activity attribution with
the saving client/user `11/101` and added the keyed `AD_WF_EventAudit` fact,
while separate freeze-off run 33788686426 accepted the committed answer with
A/B self-diff `pass` and zero scoring problems. The amendment moves event audit
out of ambient state and into the keyed workflow scope, with an exact
process/activity predicate, required attribution columns, and symbolic process
and record edges.

The amendment stores the future three-file production correction only as a
SHA-256-pinned patch under
`contracts/phase5g1ay-workflow-attribution-v1/`. The patch is applied only in a
disposable detached worktree to build a capture-only runtime under
`build/phase5g1ay/`; no `base/src` change or ordinary installed/release artifact
ships from the oracle branch. The two validator-running CI jobs use full
history so they can prove whether the accepted PR 19 merge is an ancestor of
the checkout, and still ensure the exact pinned source object is present.
Before acceptance, validation enforces the exact oracle-only branch scope and
protected roots. On descendants of the accepted merge, it allows unrelated
production changes but rejects committed or working-tree changes and renames
under `contracts/legacy-web-write-v1/`.
The corrected-source worktree is removed only through exact owned Git cleanup
that fails closed on unregister errors. The corrected jar removes exactly the
two contract-pinned stale code-signature entries before replacing classes.
Provenance requires the current repository `HEAD`, that removal list, and the
exact corrected jar/three-class inventory. Both captures must contain the keyed
workflow process, activity, and event-audit rows with saving-context
attribution before scoring, and an independent Gradle finalizer restores both
ordinary installed jars even when the smoke fails. The neutral validator also
functionally exercises the existing
generic fact generator against a synthetic event-audit row and fails if the
table is dropped, reclassified ambient, loses a required attribution column, or
cannot resolve its process/activity relationships. The 62-case mutation proof
covers those controls. The accepted oracle amendment remains a required link beneath the current
database-neutral chain head:

```bash
./gradlew phase5g1bFinalVerification --dependency-verification=strict
```

The corrected database-backed capture is selected explicitly with
`-Pphase5g1ayMode=corrected-legacy-workflow-attribution`; omitting it preserves
the existing legacy write-oracle behavior. PR 18,
https://github.com/samqbush/adempiere2/pull/18, merged the accepted candidate at
`e02c82ed5cae77ec9eb3067be291cb78332ffccc`:
exact-head run 33904468993 completed both 12-step modern captures through the
public `/webui` origin, self-diffed cleanly, matched the frozen contract, passed
all six H6 controls, and passed the evidence validator. A later final-head run
exposed a concurrent routed-logout race after the modern session was destroyed;
the candidate now repeats the internal END handshake for that exact
loopback-bound stale-session case while keeping unbound requests forbidden.
Exact-head run 33913861562 passed both captures and all six H6 controls with the
correction. Post-merge run 33922390350 kept the current Phase 5g-1b smoke and
seven historical lanes green, but stopped the line on Phase 5e's
`missing-affinity` control: the first correction mistook any surviving router
binding after a Tomcat 10 restart for a completed logout. R18 narrows END
recovery to the exact recently ended modern session identifier; the full
post-merge matrix remains required before demo work starts.

The Tomcat smoke requires HTTP 2xx/3xx from each deployed context except
`ADInterface`, whose unrouted base path is explicitly expected to return 404;
SOAP behavior remains a Phase 4 contract gate.

Phase 3 is merged to `develop` as
`eb1953d091836db59fabb153ccde41d8e07b7cf1` with all five PR checks green.
The Phase 4 continuation freezes all 33 SOAP-operation baselines, proved legacy
XFire through the transport-neutral registry, and now boots a distinct,
XFire-free CXF 4.1.8/Jakarta API WAR on checksum-pinned, loopback-only Tomcat
10.1.59. Direct replay passes all 33 operation baselines plus 11
valid-credential/security scenarios, including explicit create, update, delete,
and process state deltas. After a recorded per-service and atomic `ADService`
rollback rehearsal, the Tomcat 9 compatibility router now preserves both
historical URLs while routing SOAP only to CXF. XFire source, publication,
checked-in binaries, and packaged runtime artifacts are removed. The canonical
`phase4InstalledApi` gate now stages and verifies the isolated runtime in the
installed product and the existing `Adempiere_394LTS.zip` and
`Adempiere_394LTS.tar.gz` artifacts, then replays the complete corpus through
the historical paths. The archive gate preserves the unconfigured environment
template, validates checksums and executable launchers, and rejects a configured
environment file and any retired XFire runtime/publication. Phase 4 merged to
`develop` as `8c0ca4c1d6b35a5f366d6dd2150ed3bb27bc2a89`. Phase 5a now owns
the ZK/Jakarta inventory, supported-target ADR, and reviewed hand-off of all
non-SOAP routes; Phase 4 retains the frozen SOAP assertions.

The DB-backed metadata gate fails on new or stale process, validator, workflow,
entity-package, or generated-model findings. Sixteen pre-existing active process
bindings without compatible classes are explicitly tracked in
`gradle/phase3/metadata-quarantine.tsv` for a Phase 7 usage/retirement decision;
they are not reported as clean coverage.

The JDK 21 runtime walking skeleton can be exercised against disposable
PostgreSQL 14.6:

```bash
xvfb-run -a ./gradlew :base:phase2RuntimeSmoke \
  -Pphase2DbSystemPassword='<password>' \
  --dependency-verification=strict
```

This restores the committed seed, applies the `394lts` migrations, verifies the
database release, and runs the Swing, Groovy, and scheduler smokes. The target
must be local and disposable; tagged runtime objects are removed on success or
failure.

Release publication requires a new, previously unused version and declares JDK
21 as the minimum runtime. The installed compatibility bridge is pinned to
Tomcat 9.0.121 in `gradle/phase2/runtime.properties`.

- Official Page: http://www.adempiere.io
- Official Docs: http://adempiere.io/docs
- Download and debug source: https://www.adempiere.io/product/source-code.html#cloning-the-repository-with-a-slow-connection
- Business process: https://www.adempiere.io/product/business-process.html
- If you need to report a bug: https://github.com/adempiere/adempiere/issues
