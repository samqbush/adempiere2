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
| Per-context smoke shards | **Six implemented. All six executed in order for the first time in run 33342082144, producing 122 observations. `/webui` (6), `/admin` (4), `/mobile` (14) and `/adempiere` (21) passed. `/` recorded 3 vector failures and `/wstore` 4.** |
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
has been executed repeatedly and has never passed. Three distinct failures have
been observed, and they are not a progression of one another.

### Current status: all six shards execute; eight vector failures remain

Latest completed run: 33342082144. The record-and-continue change did what it
was meant to do - one run now reports the whole matrix instead of one shard:

```
/:          16 observations   3 failed
/wstore:    61 observations   4 failed
/webui:      6 observations   PASS
/admin:      4 observations   PASS
/mobile:    14 observations   PASS
/adempiere: 21 observations   PASS
capturePhase5fSoapCoexistence               FAIL
```

`/webui` and `/wstore` were observed for the first time in this run. The gate
still fails, so no runtime row may be claimed green.

The eight failures were diagnosed from that run's own evidence and fixed. Each
fix is recorded here with the evidence it was derived from, not with the
reasoning that seemed plausible beforehand.

| Route | legacy | modern | contract | Cause |
|---|---|---|---|---|
| `/::MediaBroadcast::/media/*` | 302 | 404 | 302 | Dropped `<error-page>` |
| `/::Community::/communityServlet` | 500 | 500 | 400 | `DEV-P5F-ERR-02` unimplemented |
| `/::XMLBroadcast::/xml/*` | 500 | 500 | 404 | `DEV-P5F-ERR-03` unimplemented |
| `/wstore::PaymentServlet::/paymentServlet` | 500 | 500 | 405 | `DEV-P5F-ERR-04` unimplemented |
| `/wstore` `CheckOutServlet`, `LoginServlet`, `OrderServlet` | - | - | public HTTPS origin | Container `redirectPort` |
| Phase 4 SOAP corpus | - | - | frozen fault | POS fixture never applied |

**Descriptor fidelity.** `phase5fDescriptor` synthesizes the modern descriptor
from scratch and silently dropped the legacy `<error-page>` entries. The ROOT
context declares `404 -> /`, and `/` is mapped to `Broadcast`, so a legacy
`sendError(404)` is dispatched to `Broadcast` and observed as a 302 to
`/admin/`. The frozen oracle records exactly that:
`contracts/legacy-web-v1/context-observed.tsv` gives `/media/` status 302 with
`location_header=http://127.0.0.1:8888/admin/`. The modern runtime returned a
container 404 instead. Carrying the entries over fixes `/media/*` with no
source change, which matters because that route is contracted as
`preserve-legacy-status` with no registered deviation. Only the ROOT descriptor
declares an `<error-page>`, so the other four contexts are unchanged, and the
legacy descriptors are now declared task inputs.

**Recorded fidelity gap.** `phase5fDescriptor` still does not carry over
`<welcome-file-list>`. The `<error-page>` omission was proven to change an
observed status; the welcome-file omission is not, because `/` and `/wstore/`
already match legacy without it - a filter or the default servlet answers those
paths before welcome-file resolution. Restoring it would perturb two currently
passing vectors on no evidence, so it is left open and recorded here rather
than fixed speculatively.

**Three registered deviations were never implemented.**
`known-deviations.tsv` registers `DEV-P5F-ERR-01` through `DEV-P5F-ERR-04`, but
only `ERR-01` had a transform. `ERR-02` rejects the request that reaches
`getAllIDs` with an empty transaction name; the escaping type is `DBException`
and the `IllegalArgumentException` is only its cause, so a guard catching the
latter would never have fired. `ERR-03` guards the null web project behind the
observed NPE and uses `setStatus` rather than `sendError`, because the restored
404 error-page would otherwise turn the contracted 404 into a redirect.
`ERR-04` rejects a plain GET before any response is committed instead of
falling through to `doPost`.

**Container CONFIDENTIAL redirect.** Three `/wstore` routes emitted
`Location: https://127.0.0.1:4444/...`. Port 4444 is the installed product's
`ADEMPIERE_SSL_PORT` and is not listening in this lane. The access logs place
the redirect on Tomcat 9, not Tomcat 10 - the modern access log has no entry
for those paths - because a security constraint is enforced before any routing
filter runs and the redirect is built from the connector's `redirectPort`. The
routed lane now retargets it at the isolated public HTTPS ingress.

**SOAP coexistence.** The corpus returned `password / Invalid user/password`
where the frozen fault is `webServiceName / Security not implemented yet`. The
corpus authenticates as GardenAdmin with the password
`scripts/phase4/prepare-operation-scenarios.sh` sets, while the route shards
run against the seeded oracle credential. Phase 5d records the same hazard at
`gradle/phase5/zk-functional-slice.gradle:81-85`. The fixture is now applied
inside the coexistence capture, after every shard and after the switch baseline
is verified, so it cannot contaminate route observations.

### Earlier failure: the routing proxy manufactured a 502 on `/`

Run 33327217291 failed at `/::Broadcast::/` in `modern-public` mode with
status 502 where the contract requires 302. The two access logs disagreed, and
that disagreement was the whole diagnosis:

| Runtime | Access-log entry |
|---|---|
| Modern Tomcat 10.1 (backend) | `GET / HTTP/1.1" 302 -` |
| Public Tomcat 9 (ingress) | `GET / HTTP/1.1" 502 713` |

The modern application answered the contract status correctly. The 502 was
produced by our own routing proxy: `LoopbackProxy.proxy()` fails the exchange
closed with `internal-location-leak` when `publicLocation()` cannot map the
backend `Location` header onto the public origin.
`webCM/.../utils/RequestAnalyzer.java` computes its base URL with
`indexOf(getContextPath())`, which for the ROOT context searches for the empty
string and drops the port, so `Broadcast` emitted a port-omitted absolute
`Location` that the matcher rejected.

`internal-location-leak` is a Phase 5e isolation guarantee and was not weakened
for genuinely foreign loopback origins. `RedirectDescriptor` renders a log-safe
structured descriptor of any rejected value, `ProxyResult` carries it as a
non-wire `detail`, both routing filters log it at `SEVERE` under the
`PHASE5F-PROXY-FAIL` prefix, and each shard harvests those lines into its own
evidence directory. This route now observes 302 against 302.

### Earlier failure: the routed lane never started

An earlier run had `:startPhase5fRoutedLane` exit 70 after roughly 65 minutes
with `The Phase 5e modern runtime did not become ready`, and no shard executed.
That is no longer the observed behaviour.

### Earlier failure: the ROOT context did not initialize

An earlier run still recorded:

```
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

### Observation coverage

The shards were fail-fast until run 33342082144, and `/` did not run first, so
`/webui` and `/wstore` had never been observed. That is now closed: all six
shards executed in one run and every context has runtime observations.

Three changes make one run report the whole matrix instead of one shard:

- the six shards run in an explicit `mustRunAfter` order - `/`, `/wstore`,
  `/webui`, `/admin`, `/mobile`, `/adempiere` - so the two contexts that are
  `ELIGIBLE` for modern routing report first;
- a failing route vector is recorded to `route-failures.tsv` and the shard
  continues; only an infrastructure failure aborts. Both ledgers are
  republished atomically after every vector, so an aborted shard still uploads
  what it observed;
- `Current-phase database smoke` passes `--continue`. This weakens nothing:
  `verifyPhase5fRuntimeEvidence` depends on all six shards, so a failed shard
  skips the strict aggregate and the build still fails, the lane remains
  `finalizedBy stopPhase5fRoutedLane`, and the marker-guarded database cleanup
  finalizer still runs.

`verifyPhase5fRuntimeEvidence` fails if any shard reports a non-zero
`failure_count` or a non-empty failure ledger, and now compares each shard's
recorded `git_head` against the checked-out commit so that evidence left by an
earlier attempt cannot be mistaken for this run's. Both checks carry mutants in
`verify-phase5f-runtime-evidence-validator.py`.

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
