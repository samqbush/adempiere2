# Phase 5f evidence: Jakarta non-SOAP web routes

Status: **implemented on the active Phase 5f branch; database-neutral and
database-backed evidence both executed and green; branch not yet merged**.

## Baseline

- Phase 5e merged to `develop` at `6eda2bc8`.
- Phase 5e database-neutral and database-backed gates were executed and green
  before this phase began.
- Phase 5f merged to `develop` as PR #11 at `83aeb8536`.
- Phase 5f remains post-testability ("lit"), L3.

## Contract inventory

| Contract | Implemented count/status |
|---|---|
| Deployed non-SOAP mappings | 82 fixed rows; runtime observations executed and green in run 33379849664 |
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
| `phase5fJakartaWebRoutesSmoke` | **Implemented; executed and green** in run 33379849664 on commit `9ba62875d`: 129 observations, zero vector failures, strict aggregate validated. See "Runtime gate failures" for every failure mode diagnosed on the way. |
| Per-context smoke shards | **Six implemented and all six green, producing 129 observations: `/` (16), `/wstore` (68), `/webui` (6), `/admin` (4), `/mobile` (14), `/adempiere` (21).** |
| Installed product/release overlay proof | **Executed and green** through `phase5fFinalVerification`. |
| Per-context rollback rehearsal | **Executed and green** through `phase5fFinalVerification`. |

Canonical commands:

```bash
./gradlew phase5fFinalVerification --dependency-verification=strict
./gradlew phase5fJakartaWebRoutesSmoke \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

## Runtime gate history

`phase5fJakartaWebRoutesSmoke` runs in CI, which supplies `phase3DbSystemPassword`
as the `postgres` service password. The earlier claim in this document that the
gate was "not executed" because that password was unavailable was wrong: the gate
was executed repeatedly and failed for several distinct reasons that are not a
progression of one another. Each is kept below with the evidence it was
diagnosed from, newest first, ending with the run that passed.

### Current status: the gate is green

**Run 33379849664, on commit `9ba62875d`, is the first green
`phase5fJakartaWebRoutesSmoke`.** `Contracts` was green in the same run. All six
shards recorded zero vector failures, and every downstream task passed,
including the strict aggregate:

```
/:          16 observations   PASS
/wstore:    68 observations   PASS
/webui:      6 observations   PASS
/admin:      4 observations   PASS
/mobile:    14 observations   PASS
/adempiere: 21 observations   PASS
verifyPhase5fSwitchBaseline                 PASS
capturePhase5fSoapCoexistence               PASS
verifyPhase5fBackgroundProcessorsQuiesced   PASS
verifyPhase5fRuntimeEvidence                PASS
  validated 82 legacy routes, all 37 eligible modern routes,
  and 45 explicitly unexecuted modern routes
BUILD SUCCESSFUL in 1h 13m 54s
```

Both contract ledgers now carry the executed marker
(`observed-phase5f-database-smoke` and
`runtime-observed-phase5f-database-smoke`), and
`validate-phase5f-oracle-contracts.py` fails closed on any other value, so the
marker cannot silently regress. The 25 `/wstore` JSP precompile rows are
deliberately left at `contract-only-runtime-observation-pending`: only
`login.jsp`, `basket.jsp` and `info.jsp` are reached by a route vector, so the
other 22 are proven to precompile but are not proven to serve.

The rest of this section records every failure mode that had to be diagnosed to
get here, and the evidence each fix was derived from. The matrix shape below is
from run 33360842891, the first run in which all six shards passed and whose
evidence produced the ambient-writer diagnosis:

```
/:          16 observations   PASS
/wstore:    68 observations   PASS
/webui:      6 observations   PASS
/admin:      4 observations   PASS
/mobile:    14 observations   PASS
/adempiere: 21 observations   PASS
verifyPhase5fSwitchBaseline                 PASS
capturePhase5fSoapCoexistence               PASS
verifyPhase5fRuntimeEvidence                FAIL
```

The gate still fails, so **no runtime row may be claimed green.** The remaining
failure is in `verifyPhase5fRuntimeEvidence`, the strict aggregate validator,
which had never executed before this run because every earlier run died in a
shard. Two defects surfaced on its first execution, both diagnosed from the
run's uploaded evidence rather than from inspection.

#### 1. A latent argument-list defect in the task

`verifyPhase5fRuntimeEvidence` failed immediately with
`unrecognized arguments: .../database-effect-ownership.tsv`. The task's
`commandLine(...)` passed the effects ledger twice: once as a bare positional
immediately after the script path, and once correctly under `--effects`. The
stray entry used a `RegularFile` provider rather than `.asFile`, unlike every
other argument in the block, which marks it as an editing leftover. It was
latent from authoring and only observable once a run reached the task. Removed.

#### 2. ADempiere background processors contaminate per-vector attribution

With the argument list corrected, the validator was replayed locally against
the run's own uploaded evidence. It rejected the matrix for unowned database
writes. Of 129 observations, 13 carried any change at all, and the changed
tables were dominated by ADempiere's timer-driven background processors:

| Tables | Observations | Origin |
|---|---|---|
| `C_AcctProcessor(+Log)` | 5 | Accounting processor heartbeat |
| `R_RequestProcessor(+Log)` | 3 | Request processor heartbeat |
| `AD_WorkflowProcessor(+Log)`, `AD_AlertProcessor(+Log)` | 2 | Processor heartbeats |
| `Fact_Acct`, `C_Invoice`, `GL_Journal`, `M_InOut`, `M_Match*`, `C_Payment`, +11 more | 1 | One accounting posting burst |
| `AD_Sequence` | 13 | ID allocation by `MSequence` |
| `AD_Session` | 9 | Session creation, including the processors' own |
| `W_Click` | 2 | The click-tracking route itself |

Phase 5f attributes a database effect to the route vector in flight, by diffing
a full data dump taken immediately before and after each request. A processor
firing inside that window is recorded as an unowned write of whichever route
happened to be observed. The clearest instance: the single accounting posting
burst was attributed to `/::AdRedirector::/AdRedirector`, a banner redirect
under a `no-new-write` contract, which reported 19 unowned business tables.

Widening the ownership contract to accept those tables was rejected. It would
make a genuine unowned write invisible, which
`validate-phase5f-runtime-evidence.py` explicitly exists to detect. The ambient
writer is removed instead of the check:
`scripts/phase5/quiesce-phase5f-background-processors.sh` deactivates every
processor definition before the container boots, so `AdempiereServerMgr` never
schedules them.

The set it deactivates is exactly the eight timer sources
`AdempiereServerMgr.startServers()` schedules
(`serverRoot/src/main/server/org/compiere/server/AdempiereServerMgr.java:114,
124, 134, 144, 154, 164, 174, 184`): `C_AcctProcessor`, `R_RequestProcessor`,
`AD_WorkflowProcessor`, `AD_AlertProcessor`, `AD_Scheduler`, `AD_LdapProcessor`,
`IMP_Processor` and `C_ProjectProcessor`.

An earlier draft selected tables by name pattern instead. Review rejected it:
`%processor` also matches `C_PaymentProcessor`, which `MPaymentProcessor.find()`
selects with `IsActive='Y'`
(`base/src/org/compiere/model/MPaymentProcessor.java:71`). Deactivating it would
have silently changed the `/wstore` checkout and payment routes under test
rather than merely removing an ambient writer - the opposite of the "one
specific, recorded way" this narrowing is allowed to work.

Discovery is retained, but only as a **drift detector in both directions**. The
script still enumerates every base table with an `IsActive` column whose name
ends in `processor`, plus `AD_Scheduler`, and then fails closed if a scheduler
source has disappeared, or if a processor-shaped table appears that is neither a
known scheduler source nor one of the two reviewed non-scheduler configuration
tables (`C_PaymentProcessor`, `EXP_Processor`). A new processor therefore cannot
be introduced without an explicit classification decision.

The mutation is marker-guarded to the disposable Phase 3 database, applied in
one transaction, captured to a state file for restore, and re-verified after the
shard matrix by `verifyPhase5fBackgroundProcessorsQuiesced`, which
`verifyPhase5fRuntimeEvidence` depends on and which is ordered `mustRunAfter`
the shards, so a processor reactivated mid-run fails the gate rather than
silently contaminating it.

#### A response can commit before its write does

Review found a second misattribution path that quiescing does not close.
`serverApps/src/main/servlet/org/compiere/wstore/Click.java` calls
`response.sendRedirect(url)` and `response.flushBuffer()` at lines 114-115, and
only then persists the `MClick` row at line 119. The client is therefore served
before the `W_Click` insert commits, so the write can land after that vector's
`after` snapshot and inside the **next** vector's window, where it is
misattributed to a route that does not own it - now fatal, because `W_Click` is
owned by the click route alone and the `AD_Sequence` half is fatal under a
no-write contract.

`phase5f-route-smoke.py` now waits `WRITE_SETTLE_SECONDS` (0.5 s) between the
request and the post-request snapshot. A fixed bounded wait is used rather than
polling until two consecutive snapshots agree, because each snapshot is a full
data dump and a second one per observation would roughly double the matrix
runtime; 0.5 s over 129 observations costs about a minute.

This narrows the runtime under test in one specific, recorded way. It narrows
it identically for the legacy and the modern leg of every vector, so route
parity is unaffected.

#### Two ownership corrections derived from the same evidence

Two of the recorded effects are route-caused rather than ambient, and the
contract - authored before any runtime observation - was wrong about them.

**`W_Click` on `/wstore::Click::/click/*`.** The ownership row read
`read-only-plus-session-basket` with the rationale "Read route may initialize
the context-local session basket only." That is incorrect:
`serverApps/src/main/servlet/org/compiere/wstore/Click.java:119,176-201`
persists an `MClick` (`W_Click`) row on every request, which is the servlet's
entire purpose. Run 33360842891 observed the write on the legacy and the modern
leg alike, and the legacy observation carried no active processor row. The
route now owns `W_Click` explicitly. Ownership stays per-row: a new
`unowned-click-table-write` mutant proves a different
`read-only-plus-session-basket` route touching `W_Click` is still rejected.

**`AD_Sequence` as an ID-allocation consequence.** ADempiere allocates every new
record's ID from `AD_Sequence`, so an `AD_Sequence` update is an unavoidable
consequence of a permitted insert, not an independent effect. It is now
permitted **only where some write is already permitted**. A route under a
`no-write`, `no-new-write`, `read-only` or `download-read-only` contract that
touches `AD_Sequence` still fails, which a new `id-allocation-under-no-write`
mutant proves.

These two corrections took the runtime-evidence mutation harness from 24 to 26
mutations:
`id-allocation-under-no-write` and `unowned-click-table-write`. Both were
confirmed to fail for the intended reason rather than incidentally.

Review also noted that the two new mutants prove the allowance is *bounded* but
not that it *exists*: every row of the clean fixture recorded `none`, so making
`with_id_allocation` a no-op would still leave all 26 "detected". The fixture
now carries a positive control - one row per context that already owns
`AD_Session` records a permitted `AD_Session` insert with its consequent
`AD_Sequence` update. Neutering `with_id_allocation` was confirmed to make the
harness reject its own clean fixture.

#### Automatic error reporting is the second ambient writer

The run's evidence was replayed through the corrected validator with every
processor-caused table removed, to find what the quiesce would *not* fix. One
row survived: `AD_Issue` on `/::Community::/communityServlet` in
`modern-public`.

Both legs of that route are contractually correct - legacy returns 500 and
modern returns 400, each matching its contract - so this is not a status
regression. `AD_Issue` is ADempiere's automatic error report:
`CLogErrorBuffer` routes every SEVERE log record to `MIssue.create`
(`base/src/org/compiere/util/CLogErrorBuffer.java:222`), which inserts a row
unless `AD_System.IsAutoErrorReport` is off
(`base/src/org/compiere/model/MIssue.java:70-73`). Like the processors, it is
diagnostic infrastructure driven by log events rather than route business
state, and it is written after the response is decided, so it lands in whichever
observation window is open. `MIssue.report()` additionally attempts an outbound
call to ADempiere's issue service, which a CI lane should not be making.

The quiesce therefore also turns `IsAutoErrorReport` off. This cannot change a
route response: `MIssue.create` simply returns `null`. The state file carries a
column name per row, so one file restores both the processor `IsActive` flags
and this one, and `verify` fails if either is re-enabled during the matrix.

#### Deliberately not blessed - and what run 33369428234 settled

The same replay left two residuals that were **not** widened into the contract,
because the evidence did not establish they were route-caused:

- `AD_Session` on `/::CacheService::/cache/*`, `/::MediaBroadcast::/media/*`
  and `/wstore::AssetServlet::/assetServlet/*`. **Removed by the quiesce**, as
  predicted: run 33369428234 recorded no such change.
- `AD_NotificationQueue`, `AD_NotificationRecipient` and `AD_Queue` on
  `/::AdRedirector::/AdRedirector` in `modern-public`. **Not removed.** Run
  33369428234 still records them, so the prediction that they were processor
  session noise was wrong. Their real origin is diagnosed under "First-touch
  initialisation is the third ambient writer" below.

#### Checked and cleared

`/::AdRedirector::/AdRedirector` carries a `no-new-write` contract, and
`webCM/src/main/servlet/org/compiere/cm/AdRedirector.java:55-56` does write:
it constructs an `MAd` and calls `addClick()`, which increments `ActualClick`
and saves (`base/src/org/compiere/model/MAd.java`). The contract is
nevertheless correct **for this vector**, because that branch is only taken
when a `CM_Ad_ID` is supplied and the Phase 5b request vector is a bare
`/AdRedirector`. Neither observed leg recorded a `CM_Ad` change, which confirms
it. A future vector carrying `CM_Ad_ID` would need the ownership row corrected
the same way the click route's was.

#### The aggregate digest measured more than the table digests

Run 33369428234 failed on `/::AdRedirector::/AdRedirector` with `database
aggregate and table snapshots disagree`. Replaying the run's own uploaded
evidence pinned it to the `legacy-public` leg, which recorded
`database_before != database_after` while **not one** of its 480 per-table
digests differed.

The two digests measured different things.
`phase5f-route-smoke.py::database_snapshot()` collected one
`pg_dump --data-only --column-inserts` and derived the aggregate from *every*
non-comment line of it, while the per-table map bucketed only lines beginning
`INSERT INTO `. The aggregate therefore also covered
`SELECT pg_catalog.setval(...)` lines. A native PostgreSQL sequence advances
outside the transaction that drew from it, so an allocation elsewhere in the
JVM moves the aggregate while no observed row changes - and
`validate-phase5f-runtime-evidence.py:158` asserts
`(before == after) == (not changed)`, which then fires on a route that wrote
nothing.

The aggregate is now derived from the sorted table-grain digests, so both
measure exactly the same content and the cross-check becomes what it was
intended to be: a detector of evidence edited after collection. A new
`aggregate-table-snapshot-disagreement` mutant proves it still has teeth; the
harness now detects 27 mutations.

Sequence position is deliberately outside the database-effect contract. The
contract governs business-data writes, deletions and truncations - all of which
still change a table digest, and a table that empties or appears is still caught
because the changed set is computed over the union of both maps. ADempiere's own
allocator table `AD_Sequence` remains observed at table grain and remains
governed by the `with_id_allocation` rule.

#### First-touch initialisation is the third ambient writer

With the aggregate corrected, the same replay surfaced the residual the quiesce
had not removed: `AD_Queue`, `AD_NotificationQueue`,
`AD_NotificationRecipient` and the consequent `AD_Sequence`, on
`/::AdRedirector::/AdRedirector` in `modern-public` only.

The origin is not the redirector. A servlet without `load-on-startup` is
initialised lazily by its first request, and ADempiere's web initialisation is
not read-only: `base/src/org/compiere/util/WebEnv.java:144-195` runs once per
web-application class loader behind a static `s_initOK` guard and, inside
`Trx.run`, enqueues a `@ServerStarted@` notification through
`QueueLoader`/`DefaultNotifier`, inserting the queue and recipient rows. It is
one-time per context per JVM, it is attributed to whichever vector happens to
touch that context first, and a different vector ordering would move it to a
different route. That is why it appeared on the modern leg only: the legacy
contexts were already initialised, and `AdRedirector` was the first modern
vector to reach an uninitialised one.

Blessing it as an ownership row was rejected for the same reason as the
processors: it would grant a redirect route under a `no-new-write` contract a
standing permit for four unrelated tables, and the permit would be load-bearing
for a write it does not cause.

`phase5f-route-smoke.py` instead performs an unscored **warm-up pass** over
every route of the shard immediately after each `set_switch`, once for the
legacy runtime and once for the modern one, followed by a 3-second settle. Every
servlet under test is therefore already initialised before the first observation
window opens. The pass asserts nothing and swallows transport errors, because
each route is asserted immediately afterwards by its own observation, and it
cannot mask a per-request effect, which by definition also occurs on the
observed request. Review hardened it twice: warming uses a short 15-second
timeout rather than the 130-second observation timeout, so a lane that accepts
connections but never answers cannot burn the job's whole budget before a single
observation is recorded; and it catches `http.client.HTTPException` as well as
`OSError`, because `http.client` exceptions are not `OSError` subclasses and
would otherwise end the shard with an empty evidence directory - the exact
defect per-vector publishing exists to prevent. `observe()` was widened the same
way, so a truncated response is recorded as an attributed transport failure.

Review also found that the table-grain parser dropped physical lines that were
not the first line of an `INSERT`. `pg_dump --column-inserts` does not escape
newlines inside string literals, so a row containing one spans several lines.
Those lines used to reach the raw-dump aggregate; once the aggregate became
derived, they would have reached nothing, and an update confined to the tail of
a multi-line value would have changed no digest at all. Continuation lines are
now attributed to their statement's table, with a `SET` or `SELECT` line ending
the statement.

Both fixes were first validated by replaying run 33369428234's own evidence with
the aggregate recomputed and the four initialisation tables removed from that one
vector, which made the strict validator accept the whole matrix. CI then
reproduced it for real in run 33379849664.

### Earlier status: all six shards execute; eight vector failures remain

Run 33342082144. The record-and-continue change did what it
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

**The CONFIDENTIAL HTTPS leg had no legacy baseline.** With the port fixed, the
`modern-public-confidential` observation executed for the first time and
returned 200 with real page content where the contract required 302. The
contract was not wrong about 302 - that is the *transport redirect*, which the
separate `modern-public-tls-redirect` vector already asserts - it was being
reused to score the protected resource behind the redirect. The frozen Phase 5b
oracle captured `/wstore/loginServlet`, `/wstore/checkOutServlet` and
`/wstore/orderServlet` over public HTTP only, recording the 302 and its
`Location`, so no legacy HTTPS response for them has ever been observed.

The fourth CONFIDENTIAL route is not evidence for the other three.
`/wstore::Login::/login.jsp/*` has frozen legacy status 200, but its vector path
is `/wstore/login.jsp/` with a trailing slash, and the constraint's
`<url-pattern>/login.jsp</url-pattern>` is an exact match. That path escapes the
constraint on the legacy runtime, so its 200 was served over plain HTTP.

Blessing the observed 200 as a contract literal would have claimed an
unexecuted runtime observation, which this phase's own contract validator
exists to prevent. Instead the smoke now observes the legacy runtime over
public HTTPS in the same run, as `legacy-public-confidential` with a
record-only expectation, and scores the modern HTTPS response against it. The
aggregate validator enforces the same parity and requires the four baselines to
be present, unduplicated, public-origin and record-only.

Parity alone is not sufficient, because both legs cross the same public HTTPS
ingress: a broken ingress fails both identically and would satisfy parity while
serving nothing. A CONFIDENTIAL route is therefore also required to have
actually served or redirected - a status below 400 - before its observation may
be promoted to the baseline. Six mutants cover the new checks, including one
that moves both legs to 502 in lockstep and one that inserts a shadowing
duplicate baseline; the harness detects all 24.

**Recorded divergence.** `/wstore/login.jsp/` is redirected to HTTPS by the
modern runtime but served over HTTP by the legacy runtime, because the two
containers disagree about whether a trailing-slash path matches an exact
`url-pattern`. The `modern-public-tls-redirect` vector hardcodes 302, so this
divergence is currently unasserted rather than proven benign. It is recorded
here and is not closed by this phase.

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
- The 25 `/wstore` JSP precompile rows remain
  `contract-only-runtime-observation-pending`. Only `login.jsp`, `basket.jsp`
  and `info.jsp` are reached by a route vector, so the other 22 are proven to
  precompile through Tomcat 10.1 Jasper but are not proven to serve.
- Three narrowings of the runtime under test are recorded and enforced, not
  incidental: the eight timer-driven processor sources are deactivated,
  `AD_System.IsAutoErrorReport` is off, and each shard warms its routes before
  observing. All three are applied identically to the legacy and the modern leg
  of every vector, so route parity is unaffected, and
  `verifyPhase5fBackgroundProcessorsQuiesced` fails the gate if the first two
  are reverted mid-run.
- Sequence position is outside the database-effect contract. Table writes,
  deletions, truncations and tables appearing or disappearing are all still
  observed; native PostgreSQL sequence state is not.
- The `<welcome-file-list>` fidelity gap in `phase5fDescriptor` and the
  `/wstore/login.jsp/` trailing-slash divergence remain recorded and open.
- `/::AdRedirector::/AdRedirector` carries a `no-new-write` contract that is
  correct only because the Phase 5b vector omits `CM_Ad_ID`. A future vector
  carrying it would write `CM_Ad` and needs the ownership row corrected first.
- T5e-1 remains open; it closes in Phase 5h. T5f-1's public-origin runtime
  controls are now observed, but it also closes in Phase 5h.
- Required checks/branch protection remain a manual repository-administrator
  action.
