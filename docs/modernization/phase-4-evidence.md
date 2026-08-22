# Phase 4 evidence

Phase 4 remains in progress. This record covers the completed oracle,
inventory, frozen-contract, and initial Gradle-boundary slices.

## Oracle and inventory

- The Phase 3 installed XFire WAR served all four WSDLs on JDK 21 and Tomcat 9.
- `ADService.getVersion` returned HTTP 200 and version `0.7.0`.
- `ModelADService.queryData` reached business authentication and returned the
  expected invalid-user result without a SOAP fault.
- `ExternalSales.getProductsCatalog` and `WebService.getCustomers` reached
  business authentication and returned HTTP 500 SOAP faults with
  `Invalid user/password`.
- The generated inventory contains 11 descriptors, 114 servlet/filter
  mappings, four SOAP services, and 33 unique WSDL operations: 21
  `ADService`, eight `ModelADService`, three `ExternalSales`, and one
  `WebService`.

The live replay gate is:

```bash
./gradlew verifyPhase4OracleMatchesFrozen \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

It completed successfully on the Phase 4 branch. Local Homebrew PostgreSQL
required a temporary marker-guarded `postgres` compatibility role because the
legacy installer hardcodes that system username. The role and the disposable
Phase 3 database objects were removed after the replay.

## Frozen contract

`org.adempiere.webservice/contracts/xfire-v1/` contains the four WSDLs,
four representative requests, responses and header records, the round-trip
record, and the 33-operation inventory. `manifest.sha256` fails on additions,
removals, or byte changes. Only response `Date` and `JSESSIONID` values use the
documented placeholders in `normalization-policy.md`.

The mutation proof changes the frozen `ADService` service QName in memory and
requires its SHA-256 to differ:

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

## Remaining Phase 4 work

The frozen corpus currently proves one representative operation per service,
not success and applicable fault coverage for every operation. P4.2-P4.3 must
finish that replay matrix before the legacy wrappers are moved behind the
neutral dispatcher. The CXF/Tomcat 10.1 deployable, compatibility router,
operation cutover, XFire retirement, and final installed-product gate remain
pending.
