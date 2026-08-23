# Phase 4 evidence

Phase 4 implementation and local exit gates are complete; PR CI and merge to
`develop` remain required before Phase 5 begins. This record covers the complete
legacy operation baseline, fail-closed inventories, transport-neutral
33-operation business dispatcher, isolated CXF/Jakarta runtime, rollback
rehearsal, CXF-only compatibility router, and XFire removal. The database-backed
corpus includes all required valid-credential `ModelADService` scenarios and
explicit mutation-state deltas.

The foundation merged to `develop` as
`f91b0ef2ccfc03d94f3688d6e271b0480bcc9cdf`. Completion continues on
`phase-4-api-edge-modernization-completion`; Phase 5 remains blocked.

## Oracle and inventory

- The Phase 3 installed XFire WAR served all four WSDLs on JDK 21 and Tomcat 9.
- `ADService.getVersion` returned HTTP 200 and version `0.7.0`.
- `ModelADService.queryData` reached business authentication and returned the
  expected invalid-user result without a SOAP fault.
- `ExternalSales.getProductsCatalog` and `WebService.getCustomers` reached
  business authentication and returned HTTP 500 SOAP faults with
  `Invalid user/password`.
- The final inventory contains 11 descriptors, 114 servlet/filter mappings,
  four SOAP services, and 33 unique WSDL operations: 21
  `ADService`, eight `ModelADService`, three `ExternalSales`, and one
  `WebService`.
- `gradle/phase4/route-classes.tsv` freezes all 114 mappings with an owner and
  behavioral gate. Legacy JBoss HTTP-invoker routes are non-SOAP Phase 5 work,
  not part of the Phase 4 SOAP cutover. The reviewed artifact records that no
  callback/webhook mapping exists in the 11 descriptors.
- `gradle/phase4/xfire-removal.tsv` now contains only migrated public aliases
  and retained historical evidence. A separate absence gate rejects active
  XFire source, descriptors, IDE classpaths, and checked-in binaries; the
  inventory verifier still fails on untracked and stale evidence paths.

The final database-neutral contract/removal gate is:

```bash
./gradlew phase4FinalVerification --dependency-verification=strict
```

It completed successfully on the Phase 4 branch against an isolated
`postgres:14.6` container. The container's `postgres` role satisfies the legacy
installer's fixed system-user expectation; the marker-owned application
database and compatibility roles are removed after every replay.

## Frozen contract

`org.adempiere.webservice/contracts/xfire-v1/` contains the four WSDLs, the
original four representative requests, and a baseline request, response, header
record, and case record for every operation. The result matrix records 32
dispatch/application outcomes and the separately classified
`ExternalSales.uploadOrders` binding residual. `manifest.sha256` fails on
additions, removals, or byte changes across the complete corpus. Only response
`Date` and `JSESSIONID` values use the documented placeholders in
`normalization-policy.md`.

The mutation proof independently changes the frozen `ADService` service QName,
operation manifest, successful response, and application fault, and requires
every SHA-256 to differ:

```bash
./gradlew verifyPhase4FrozenContracts phase4ContractMutationProof \
  --dependency-verification=strict
```

## Gradle boundary

`org.adempiere.webservice` is the 29th included Gradle project and remains in
the Ant reactor as a shared surface. Its Gradle JAR currently contains only the
transport-neutral fault, request-context, and XMLBeans dispatch contracts.
The purity gate rejects XFire, CXF, and servlet framework imports from that
source set. Ant continues to own the legacy XFire WAR.

```bash
./gradlew :org.adempiere.webservice:check \
  verifyProjectTopology verifyPhase3Topology verifyPublicationContracts \
  --dependency-verification=strict
```

The full Gradle module gate also completed successfully after the promotion.

The continuation branch adds an immutable registry for the exact 33-operation
manifest, explicit request/session scope metadata, complete-handler-set
validation, fail-closed unknown-operation and argument dispatch, thread-safe
session values, and framework-neutral fault causes/details. Fifteen
`UnitTest`-tagged tests bind the registry keys and scopes directly to the frozen
`operations.tsv`, verify fault metadata, and prove concurrent session-value
creation; test results are included in the root fail-closed result gate. The
module dependency lock is source-controlled. Ant compiles the same neutral sources into
`WEB-INF/classes` at Java 21 class-file major 65 while Gradle continues to
publish only the neutral seam.

The first request-scoped extraction pilot moves `WebService.getCustomers` and
all three `ExternalSales` operations behind transport-neutral business
interfaces. Their XMLBeans response construction and shared database
authentication now raise `ServiceFault`; the unchanged XFire implementation
classes are temporary adapters that translate faults only at the transport
boundary. A separate non-published legacy-adapter test source set proves
response-object identity, all four business-operation bindings, and preservation
of fault message, QName, and cause. The purity gate covers both the published
dispatch contracts and the non-published business source tree.

The second request-scoped extraction moves all eight `ModelADService`
operations — `setDocAction`, `runProcess`, `getList`, `createData`,
`updateData`, `deleteData`, `readData`, and `queryData` — behind
`org.adempiere.webservice.business.ModelADServiceBusiness` and
`DefaultModelADService`. The move is mechanical: login, service-type
authentication, parameter validation, named transaction creation, rollback and
commit sequencing, the shared `"PO"` `CCache`, the reused XFire-free
`com._3e.ADInterface.Process` helper, and every XMLBeans document instance are
unchanged, and each `XFireFault` construction became the identically worded
`ServiceFault` with the same local fault-code name, detail, and cause
expression. `com._3e.ADInterface.ModelADServiceImpl` is now only a temporary
XFire adapter: it forwards the exact request document, returns the exact
response document without reserialization, and calls `XFireFaultMapper` at the
transport boundary. `XFireFault(String, QName)` delegates to
`XFireFault(String, null, QName)` in XFire 1.2.6, so the mapper reproduces both
legacy construction forms, and `XFireFault.getMessage()` returns the constructor
message verbatim, so the `createData`/`updateData` `getLocalizedMessage()`
rollback text is unchanged.

Two behavior-preserving details are recorded explicitly. `DefaultModelADService`
keeps the historical logger category by name
(`CLogger.getCLogger("com._3e.ADInterface.ModelADServiceImpl")`) so extracted
business logging is not recategorized. The value conversion inside
`setValueAccordingToClass` is extracted as `toColumnValue(Class, DataField,
String)`; it now evaluates the pure `WS_WebServiceType.Value` accessor eagerly
instead of only on the invalid-boolean branch, which performs no database access
and has no observable seam effect.

Before removal, `ModelADServiceImplTest` bound all eight adapter operations, response-object
identity, request-instance forwarding, the published `0.7.0` version, and fault
translation for both the caused and uncaused legacy forms.
`DefaultModelADServiceTest` exercises the extracted conversion without a
database and pins the exact legacy boolean, numeric, temporal, binary, and
failure-message text. No `XFireFault` signature or construction entered
`src/business/java` or `src/neutral/java`. XFire was confined to the temporary
legacy API/adapters, descriptor, and isolated tests until rollback passed; those
surfaces are now removed. `gradle/phase4/xfire-removal.tsv` retains the reviewed
historical evidence paths, while `verifyPhase4XFireAbsent` rejects active
transport remnants. The frozen static WSDL remains byte unchanged.

The final extraction moves the complete 21-operation session-scoped
`ADService` implementation behind `ADServiceBusiness` and
`DefaultADService`. The business body is mechanically identical apart from the
fault type and accessors added to `WWindowStatus`; it retains the historical
logger category, one `CompiereService`, login context, window caches, current
rows, transaction behavior, and XMLBeans object identities per service
instance. The legacy unauthenticated fault remains exactly `You need to login`
with the same cause and SOAP 1.2 `Receiver` QName.

`BusinessSoapDispatcher` binds all 33 registry keys explicitly to the four
business seams, validates exact argument counts/types, creates a fresh service
for request-scoped calls, and obtains `ADService` through an atomic
session-context value. All four XFire implementation classes now invoke that
registry rather than their business services directly. Twenty isolated
legacy-adapter tests prove all operation bindings, fault translation, response
identity, same-session continuity, cross-session isolation, and concurrent
session isolation. A fresh installed XFire replay after this cutover again
reached all 33 entries with 32 dispatch/application outcomes and the one frozen
`ExternalSales.uploadOrders` binding residual.

The completion design still treats `ADService` as one atomic cutover unit with
server-side per-session runtime affinity. Only the three request-scoped
services may cut over per operation. The CXF source set can now reuse the
neutral registry and business path without compiling or loading an XFire class.

## Isolated CXF/Jakarta runtime

`gradle/phase4/runtime.properties` pins CXF 4.1.8 and Tomcat 10.1.59. The
Tomcat archive is verified against Apache's published SHA-512 before extraction,
its HTTP connector binds only to `127.0.0.1:8890`, and it deploys the distinct
`ADInterface-Modern-1.0.war` without replacing the legacy XFire WAR.

The modern WAR publishes four Jakarta message-mode providers backed by one
bounded DOM/XMLBeans codec and `BusinessSoapDispatcher`. The invalid
`ExternalSales.uploadOrders` WSDL cannot pass CXF's WSDL model validation
because its global `ArrayOf_tns1_Order` element does not exist. The servlet
therefore serves all four approved WSDL files directly, byte-for-byte, while
the generic providers handle SOAP messages and deliberately reproduce the
frozen upload binding fault.

The WAR packages a filtered `Adempiere-Modern-Core.jar`; `jdeps` identified and
removed the seven aggregate-core classes linked to `javax.servlet`
(`MAd`, `FileUpload`, `WebEnv`, `WebLogin`, `WebSessionCtx`, `WebUser`, and
`WebUtil`). A minimal generated bridge carries only the legacy logging,
functional, database-driver, and connection-pool packages required by the
transport-neutral business path. `verifyModernSoapWar` scans all packaged
classes/JARs and fails on XFire linkage, shared-core `javax.servlet` linkage,
or a legacy transport adapter.

Authenticated model execution exposed active model validators outside the base
aggregate. The modern WAR therefore builds `Adempiere-Modern-Packages.jar` from
the installed `packages.jar`, excluding only `MAssetDelivery` and
`MRegistration`, whose shared package classes link to `javax.servlet`. The
archive gate scans that filtered aggregate as well. The minimal legacy bridge
also carries the installed `javax.mail`, `javax.activation`, `com.sun.mail`,
and `com.sun.activation` packages required by the active validator path.

The strict dependency lock and verification metadata now cover the CXF/Jakarta
runtime graph. The direct boot smoke passed on JDK 21: Tomcat 10.1.59 started,
all four frozen WSDL byte comparisons passed, `ADService.getVersion` matched
its frozen HTTP 200 body exactly, and an unknown service WSDL returned 404.
`phase4ModernSoapDatabaseSmoke` now creates the marker-owned PostgreSQL 14.6
database through the guarded Phase 3 Ant lifecycle, rebuilds the modern WAR
after Ant, replays all 33 frozen operation baselines and 11 additional
valid-credential/security scenarios against CXF, and runs marker-guarded cleanup
after success or failure. The additional corpus includes deterministic
`ModelADService` create, read, query, update, delete, and process execution;
valid-credential configuration responses for `setDocAction` and `getList`; and
the three POS security faults. Create, update, delete, and process execution
compare explicit before/after database state.

The replay exposed and closed serialization differences in successful XMLBeans
responses. Result documents retain the service namespace as their default
namespace, the bounded response stream preserves XFire's space before
empty-element closure, removes redundant child `xsi` declarations, and restores
the schema-declaration order of `WindowTabData` attributes. No business payload
normalization was added. The same replay also proved the filtered package
aggregate and legacy mail bridge described above.

## Compatibility router, cutover, and rollback

Tomcat 9 maps both public historical SOAP URL forms to a bounded, SOAP-aware
compatibility router. The parser recognizes exactly the 33 frozen operation keys, rejects
DTD/external entities, multiple body operations, namespace mismatches, unknown
operations, malformed XML, and bodies over 1 MiB. Proxy connection and read
timeouts are explicit.

The pre-removal live oracle replayed all 33 operation bodies and all four static
WSDLs through both historical URL forms. The router preserved status, stable
headers, content type, chunked transfer behavior, fault `Connection: close`,
and body bytes; routing decisions remain in server logs rather than a new
public response header.

Before removal, `phase4CompatibilityRouterSmoke` started Tomcat 9 and Tomcat
10.1 together against marker-owned PostgreSQL 14.6 and proved absent/invalid
flags selected XFire, one request-scoped flag moved only its operation, and the
21-operation `ADService` unit retained per-session affinity.

The rollback rehearsal then selected CXF for all 33 operations and required a
fresh CXF audit for each operation. It returned `ModelADService`,
`ExternalSales`, and `WebService` independently to XFire, replaying every
operation in each service through both paths. It returned the complete
`ADService` unit to XFire with fresh sessions through both paths. No Tomcat
restart was required. The rehearsal passed in 7 minutes 20 seconds, and cleanup
removed both disposable database objects.

During the migration window, the approved operation-key format reached 60 characters while the physical
`AD_SysConfig.Name` column was only 50 characters even though its application
dictionary `FieldLength` is already 100. Migration 10030 synchronizes the
PostgreSQL and Oracle columns to 100. T4-1 and its now-closed controls are recorded in
`docs/modernization/phase-4-transitional-state.md`.

```bash
./gradlew phase4CompatibilityRouterSmoke \
  -Pphase3DbPort=5433 \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

## Installed CXF API

`phase4InstalledApi` augments the guarded Phase 3 installed home with the
distinct modern WAR, pinned runtime metadata, checksum-verified Tomcat 10.1.59,
and dedicated `RUN_API.sh` / `RUN_API_Stop.sh` launchers. The installed artifact
inventory verifies both modern WAR copies byte-for-byte, requires the launch
surface, confirms the API connector remains bound to `127.0.0.1:8890`, rejects
an unexpected installed-home path, and rejects XFire runtime/publication in the
Tomcat 9 compatibility WAR.

The canonical installed lifecycle completed successfully against PostgreSQL
14.6. Through the installed Tomcat 9 router it replayed all 33 operation
baselines through the servlet alias, then all 33 baselines and 11 additional
valid-credential/security scenarios through `/ADInterface/services/*`. The
create, update, delete, and process state comparisons passed, and final cleanup
removed both the marker-owned database and role.

```bash
./gradlew phase4InstalledApi \
  -Pphase3DbPort=5433 \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

The gate is now present in `.github/workflows/main.yml`. CI enforcement remains
the existing manual repository-administrator action.

## Release-archive integration

The canonical lifecycle also stages the modern WAR, pinned runtime metadata,
checksum-verified Tomcat 10.1.59, and dedicated launchers in the unconfigured
`install/build/Adempiere` release tree. It rebuilds the existing
`Adempiere_394LTS.zip` and `Adempiere_394LTS.tar.gz` names and their MD5 files
rather than introducing a new release-artifact name.

The archive verifier proves both archives contain every required dual-runtime
artifact, retain `AdempiereEnvTemplate.properties`, omit
`AdempiereEnv.properties`, preserve executable API launchers, and carry
byte-identical staged and deployed modern WARs. It validates both archive
checksums and requires `tomcat10-api/bin/setenv.sh` to derive the ADempiere home
from `CATALINA_HOME`, so no build-machine path is embedded.

The pre-removal archive lifecycle passed in 7 minutes 15 seconds. It regenerated and
verified both archives before replaying the public corpus, then removed the
marker-owned database and role. This closes the installer and release-packaging
scope of task 4.4.

## Final XFire removal and exit gates

The complete frozen baseline proves unauthenticated dispatch or application
behavior for 32 operations and the exact pre-existing binding failure for one;
`gradle/phase4/operation-scenarios.tsv` now classifies all 33 baseline and
additional scenario requirements fail-closed. It records the unreachable
`ADService` authenticated path, the POS valid-password security fault, reachable
Model sample scenarios, and the upload binding residual. All required
valid-credential and state-isolated `ModelADService` scenarios are captured and
pass direct CXF replay. The first continuation replay stopped before Tomcat
startup because the available PostgreSQL server was 14.18 rather than the
required 14.6; the version gate was not weakened. A pinned PostgreSQL 14.6
container then completed the canonical replay and marker-guarded cleanup.

That pinned replay confirmed one pre-existing contract defect:
`ExternalSales.uploadOrders` references a nonexistent global
`ArrayOf_tns1_Order` element from its RPC message. XFire rejects both a generated
XFire client and the deterministic request at message binding, before business
dispatch. `gradle/phase4/operation-baseline-exceptions.tsv` records the exact
legacy fault, owner, and closing action; the harness reports it as a known
residual rather than green dispatch and fails on any fault-text or inventory
drift.

After rollback passed, the compatibility router was reduced to one CXF target.
The internal XFire servlet mapping, service descriptor, eight adapter
interfaces/implementations, fault mapper, isolated adapter tests, IDE classpath
entries, and both checked-in XFire JARs were removed. The compatibility WAR
packages only the four approved static WSDLs from the versioned oracle corpus;
it excludes source contracts, Gradle output, and test reports.

The final `phase4InstalledApi` lifecycle passed in 7 minutes 17 seconds. Both
historical paths replayed all 33 baselines through CXF, the primary path replayed
all 11 additional scenarios and four state deltas, every operation emitted a
fresh `target=MODERN` audit, and no request selected a legacy target. Installed
and release-archive checks rejected XFire classes, descriptors, and binaries.
Cleanup again proved the database and role absent.

The final targeted Phase 4 gate passed in 7 seconds, the guarded
`phase3NoDatabaseDistribution` passed in 3 minutes 13 seconds, and the full
29-project Gradle build passed 167 tasks in 2 minutes 21 seconds. PR CI remains
the authoritative branch gate, and required-check enforcement remains the
manual repository-administrator action. Phase 5 must not begin until this branch
passes PR CI and merges to `develop`.
