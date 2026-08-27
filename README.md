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

This covers the root and 29 included Gradle projects. It is not a replacement
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
