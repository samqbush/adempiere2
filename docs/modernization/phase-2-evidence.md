# Phase 2: JDK 21 runtime walking skeleton

## Scope

Phase 2 moves all 28 Gradle projects, their publications, the core Swing client,
and the background scheduler runtime to JDK 21. It retains Tomcat 9 and standard
`javax` APIs as a compatibility bridge. Full installed-distribution parity,
deployed web applications, Jakarta conversion, and a database-major upgrade
remain deferred to later phases.

The branch was cut from merged Phase 1 commit
`1fd8052140e1a0f33acb336d44247d7d0cdb8729` on `develop`.

## Recorded results

| Gate | Result |
|---|---|
| Gradle wrapper and execution JDK | Gradle 8.10.2 on OpenJDK 21.0.2 |
| Gradle topology | Root plus all 28 included projects |
| Published bytecode | Java 21 class-file major 65 |
| Gradle unit tests | 863 tests across the core, installer, and LDAP additions; zero failures |
| Ant no-restore reactor | `BUILD SUCCESSFUL` on JDK 21; 1,387 tests, zero failures/aborts, five skips |
| Installer candidate-JDK/keytool tests | 10 tests passed |
| LDAP BER characterization | 18 tests passed, including bind, unbind, equality, compound AND/presence, malformed input, and response bytes |
| Disposable runtime smoke | 3 tests passed; zero failures/skips |
| Database | PostgreSQL 14.6 seed restored; only `394lts` migrations applied; `AD_System ReleaseNo=3.9.4`, `Version=2023-01-24` |
| Swing | Garden World authentication, role/client/org/warehouse selection, preference loading, real menu tree discovery, and `FlushSystemQueue` execution passed under a virtual display |
| Groovy | A real database-backed `MRule` executed with exactly one Groovy script-engine provider |
| Scheduler | One isolated fixture executed exactly once; caller context and transaction state were restored |
| Tomcat bridge | Empty Tomcat 9.0.121 started and reported ready on JDK 21 |
| JDK internals | Source/reflection/module inventory classified; `jdeps --jdk-internals` found no unowned Gradle-output dependency |
| Publication | Candidate POMs and isolated repository publication declare JDK 21; reused/empty/local/snapshot release identifiers are rejected |
| Cleanup | Disposable runtime, database, tagged compatibility role, and isolated PostgreSQL process were removed |

The Ant reactor reports success even when its legacy silent-setup subprocess
cannot configure a local installation and prints `Java Result: -1`. Phase 2
therefore treats the Ant result as compilation, packaging, and existing-test
compatibility only. Silent setup and installed-product startup remain Phase 3
exit criteria.

## Reproduction

```bash
export JAVA_HOME=/path/to/jdk-21
export ADEMPIERE_LIBRARY_VERSION=phase2-candidate

./gradlew --no-daemon build verifyJava21Bytecode \
  verifyTestClassification verifyTestResults verifyPublicationContracts \
  verifyReleaseVersion verifyJdkInternalApiInventory verifyJdepsInternals \
  verifyPhase2RuntimePins --dependency-verification=strict

./gradlew generatePomFileForMavenJavaPublication \
  publishMavenJavaPublicationToPhase2Repository \
  verifyPublicationContracts --dependency-verification=strict

ant build -Dnodbrestore=true

scripts/phase2/probe-tomcat9.sh

xvfb-run -a ./gradlew :base:phase2RuntimeSmoke \
  -Pphase2DbSystemPassword=postgres \
  --dependency-verification=strict
```

The runtime smoke requires a disposable local PostgreSQL 14.6 server. The
restore helper rejects non-loopback hosts, unexpected server versions, and
untagged database/role cleanup. CI provides the same version through the
`postgres:14.6` service image.

## Compatibility decisions

- Gradle compilation and publication use JDK 21 and class-file major 65.
- Maven coordinates and release artifact names remain unchanged. A real release
  cannot be published until an unused version is supplied; the checked-in ledger
  and the release workflow prevent known-version reuse.
- Ant-only carry-over remains Java 11 bytecode for Phase 3. The base Ant compile
  uses `--release 11` so the quarantined JBoss facet can compile against the
  historical Java 11 API while Ant itself runs on JDK 21.
- Mockito remains on its existing major. Byte Buddy and its agent are aligned at
  1.15.4 in Gradle and checked-in Ant test/runtime locations for JDK 21 support.
- The private `com.sun.jndi.ldap` BER implementation and compiler exports were
  removed. The replacement codec intentionally supports only the wire forms
  frozen by the LDAP fixtures.
- `KeyStoreMgt` now runs the supported external JDK `keytool`; passwords are
  passed through keytool environment-variable sources rather than command-line
  arguments.
- PostgreSQL remains 14.6 and Tomcat remains 9.0.121. Jakarta and PostgreSQL
  major changes are deferred, not dropped.

## Defects found by the runtime oracle

- `GardenWorldCleanup.clearSessionLog()` deleted its active `AD_PInstance`,
  leaving final process unlock blocked by its own open transaction. Cleanup now
  excludes the active process instance.
- `Scheduler` retained a reference to the global `Env` properties. Switching
  context cleared that same object, so restoration lost caller values. The
  scheduler now snapshots values into a separate `Properties` instance.
- Installer source tests were under the legacy Ant source root. Ant now excludes
  `src/test/**`; Gradle remains responsible for compiling and executing them.

## Hazard review

| Hazard | Result |
|---|---|
| H1 removal/quarantine | Cleared; no dependency family or Gradle project was removed. Ant-only Java pins are enumerated in `gradle/phase2/ant-java-carryover.txt`. |
| H2 mechanical major migration | Fired; JDK scans, class-file verification, candidate-JDK execution, keytool replacement, LDAP codec replacement, and dynamic Groovy execution are separate gates. Jakarta is deferred. |
| H3 runtime/deployment lockstep | Fired; Gradle, Ant/release CI, installer templates, launch scripts, publication metadata, Tomcat, and runtime documentation move together. |
| H4 route/auth inventory | Cleared for HTTP; no route changed. LDAP authentication behavior is frozen by byte fixtures. |
| H5 stateful-store migration | Cleared; PostgreSQL remains 14.6 and every smoke database is guarded, disposable, and destroyed. |
| H6 transitional insecurity | Cleared; no permit-all mode, placeholder secret, disabled scanner, or open endpoint was introduced. |
| H7 stacked phases | Satisfied; the branch was cut from merged Phase 1 on `develop`. Phase 3 must wait for this branch to merge. |
| H8 living documentation | Fired; the plan, architecture, README, Copilot instructions, CI, runtime pins, and evidence update together. |

## Residual risk and manual handoff

- The full installer and deployed application artifacts are not proven runnable;
  Phase 3 owns silent setup, archive manifests, and deployed-product startup.
- Swing coverage is one semantic Garden World path, not a complete operator
  workflow suite.
- Scheduler exact-once/context behavior is covered for one isolated fixture;
  production processor breadth and observability remain Phase 6 work.
- The exact release identifier is still required. `phase2-candidate` is only a
  local/CI validation value and must not be released.
- The workflow files are authored, but repository administrators must make the
  Phase 2 checks required on `develop`. Until then, CI can run without blocking
  merges (R8).
