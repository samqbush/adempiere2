# Phase 5f evidence: Jakarta non-SOAP web routes

Status: **implemented on the active Phase 5f branch; database-backed evidence
pending; phase not complete or merged**.

## Baseline

- Phase 5e merged to `develop` at `6eda2bc8`.
- Phase 5e database-neutral and database-backed gates were executed and green
  before this phase began.
- Phase 5f is active on `phase-5f-jakarta-web-routes`.
- Phase 5f remains post-testability ("lit"), L3.

## Contract inventory

| Contract | Implemented count/status |
|---|---|
| Deployed non-SOAP mappings | 82 fixed rows; runtime observations pending |
| Non-deployed descriptor mappings | 30 fixed dispositions, database-neutrally verified |
| Context policies | 6 independent schemas, implementations and smoke shards |
| Reviewed deviations | 5 rows: four error corrections and one DSP decision |
| Enable states | 6 explicit rows |
| Generated Jakarta applications | 5 deterministic context WARs plus the retained Phase 5e `webui-modern.war` |
| Hazard checks | H1-H8 recorded and database-neutrally enforced where applicable |

The normative files are under `contracts/phase5f-jakarta-web-v1/`.

## Execution status

| Gate | Status |
|---|---|
| Phase 5e regression after routing-core extraction | **Executed and green through both Phase 5f final-gate runs.** |
| `phase5fFinalVerification` | **Implemented; executed and green twice** on 2026-08-27. Local logs: `build/phase5f-final-rebuild.log` and `build/phase5f-final-validation.log`. |
| `phase5fJakartaWebRoutesSmoke` | **Implemented; executed in CI; never passed.** See "Runtime gate failures" below. |
| Per-context smoke shards | **Six implemented. `/adempiere`, `/admin` and `/mobile` have passed once. `/` has failed. `/webui` and `/wstore` have never been observed.** |
| Installed product/release overlay proof | **Executed and green** through `phase5fFinalVerification`. |
| Per-context rollback rehearsal | **Executed and green** through `phase5fFinalVerification`. |

Canonical commands:

```bash
./gradlew phase5fFinalVerification --dependency-verification=strict
./gradlew phase5fJakartaWebRoutesSmoke \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

## Runtime gate failures

`phase5fJakartaWebRoutesSmoke` runs in CI, which supplies `phase3DbSystemPassword`
as the `postgres` service password. The earlier claim in this document that the
gate was "not executed" because that password was unavailable was wrong: the gate
has been executed repeatedly and has never passed. The two failures observed so
far are distinct, and the second is not a regression of the first - it occurs
strictly earlier.

### Current failure: the routed lane never starts

Latest run: `:startPhase5fRoutedLane` exits 70 after roughly 65 minutes with:

```
The Phase 5e modern runtime did not become ready
```

No shard executes, so **zero of the 82 route observations are collected**. The
long wall-clock is the readiness loop retrying until it gives up; it is the
main reason Phase 5f CI runs are slow.

### Earlier failure: the ROOT context does not initialize

A previous run reached the shards and recorded:

```
/adempiere: 21 observations  PASS
/admin:      4 observations  PASS
/mobile:    14 observations  PASS
/::AdRedirector::/AdRedirector modern-public: status 500 != 400
  jakarta.servlet.ServletException: Broadcast.init
    at org.compiere.cm.HttpServletCM.init(HttpServletCM.java:165)
```

`webCM/src/main/servlet/org/compiere/cm/HttpServletCM.java:163-165`:

```java
super.init (config);
if (!WebEnv.initWeb (config))
    throw new ServletException ("Broadcast.init");
```

`WebEnv.initWeb(config)` returns `false` inside the generated Jakarta ROOT (`/`)
WAR, so `AdRedirector` never initializes and Tomcat answers 500 where the
contract requires 400. This is an environment-bootstrap failure in the migrated
`/` context, not a route-contract mismatch.

### Unobserved routes

The shards are fail-fast and `/` runs fourth of six. **`/webui` and `/wstore`
have never been observed in any run.** Any statement about their conformance is
unproven, and Phase 5f cannot be called complete on the strength of the
database-neutral gate alone.

The two successful database-neutral executions completed in 25 seconds and 22
seconds respectively. They validate:
- the exact 82 deployed mappings and 30 non-deployed dispositions;
- the isolated generated Jakarta source/web trees without modifying legacy
  sources or assets;
- five deterministic source-native WARs: `admin-modern.war`,
  `ROOT-modern.war`, `mobile-modern.war`, `adempiere-modern.war`, and
  `wstore-modern.war`;
- metadata-complete Servlet 6 descriptors, explicit discovery, namespace
  closure, Jakarta Tags, and precompilation of all 25 retained `/wstore` JSPs;
- source-native read-only `/webui/timeline`, the exact static
  `/webui/theme/default/css/theme.css.dsp` compatibility resource for GET/HEAD,
  all other DSP paths absent, and no DSP interpreter/vendor TLDs;
- independent fail-closed context routing policies, derived Tomcat 9 bridge
  WARs, routing-core isolation, and Phase 5e regression;
- installed-product and both-release topology, including exactly one staged
  Phase 5f modern WAR per context under `tomcat10-api/phase5f/`, preserved Phase
  4 CXF and Phase 5e `/webui`, loopback-only Tomcat 10 connectors, no internal
  origin leakage, and no secret material;
- rollback to every pristine Tomcat 9 context while removing all five Phase 5f
  modern contexts.

## Retained residuals

- `/mobile` and `/adempiere` are packaged but remain disabled until Phase 5g.
- `/admin` remains legacy until named infrastructure consumers and owner
  approval are recorded.
- `/` and `/wstore` are only eligible after the unexecuted database-backed gate.
- All 82 `runtime_observation` fields and their route-specific database effects
  remain pending. No contract-only row is presented as runtime evidence.
- T5e-1 remains open. T5f-1 is implemented database-neutrally but its
  public-origin runtime controls remain unobserved; both close in Phase 5h.
- Required checks/branch protection remain a manual repository-administrator
  action.

Phase 5f cannot complete or merge on this evidence alone. The six-shard
`phase5fJakartaWebRoutesSmoke` must execute against the marker-owned disposable
PostgreSQL 14.6 database, record all 82 rows and database effects, replay the
complete Phase 4 SOAP corpus, and pass the runtime-evidence validator.
