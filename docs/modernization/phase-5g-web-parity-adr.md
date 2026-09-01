# ADR: Phase 5g web UI functional parity

Status: accepted; `5g-0` merged to `develop` as PR #13 at `91c4c2029`;
`5g-1a` in progress on `phase-5g-1a-bp-write-oracle`

Extends:

- `phase-5-zk-target-adr.md`, whose polling decision this ADR reaffirms without
  change;
- `phase-5c-ingress-session-adr.md` and `phase-5e-cohort-routing-adr.md`,
  whose routing and session boundaries this ADR does not move;
- `phase-5f-jakarta-web-routes-adr.md`, whose 82/30 route scope this ADR does
  not reopen;
- transitional states T5e-1 and T5f-1, which both remain open until Phase 5h.

## Context

Phase 5f merged to `develop` as PR #11 at `83aeb8536` with both gates green.
Every deployed non-SOAP route now has an owner, and the modern ZK CE
`10.3.0.1-jakarta` runtime serves selected `/webui` sessions behind the public
Tomcat 9 ingress.

What that does **not** establish is that the modern UI can do any work. The
modern runtime is proven for exactly one flow:

    login -> role selection -> desktop/menu -> read-only window -> logout

scored against eleven semantic facts and a zero-*business*-write database
effect. The modern runtime does write `AD_Session` on every login
(`docs/modernization/phase-5d-evidence.md:119`); the accurate claim is that no
modern **business write flow** has ever been observed. No record has been
created, no document completed, no process run, no report rendered, and no byte
uploaded or downloaded through the modern UI under any gate.

Phase 5g closes that gap. It is the largest remaining functional increment in
the roadmap, and the honest reading of its mandate - "read/write UI, process,
report, upload/download, POS, dashboard, server-push, and extension parity" - is
that it is a phase-sized programme, not a branch-sized task.

## Decision

### 1. Deliver Phase 5g through sequential sub-increments

Phase 5g is delivered through `5g-0` and `5g-1a` .. `5g-7`, each cut from
`develop` and merged before the next begins. This mirrors the `5a` .. `5h`
decomposition that carried Phase 5 itself. The table in
`MODERNIZATION_PLAN.md` under "Phase 5g decomposition and active scope" is
normative.

### 2. Oracle before modern

The frozen Phase 5b/5c browser oracle asserts zero business writes, so it
cannot score a write. Each new behaviour class is captured from the legacy
Tomcat 9 / ZK 3.6 runtime, frozen, and domain-reviewed **before** the modern
runtime is scored against it.

This is the ordering `MODERNIZATION_PLAN.md` §3.6 prescribes. Its alternative -
capturing modern behaviour first and blessing it - produces a self-frozen master
that protects later refactors but proves nothing about whether the first
modernized run is correct.

### 3. No branch may both invent the expected answer and implement the thing being scored

Oracle increments (`5g-1a`) ship no modern runtime code. Parity increments
(`5g-1b` onward) add no new oracle facts.

Without this rule a single green PR could author the expectation and the
implementation together, and the gate would be a tautology.

#### 3a. Every increment that ships modern code names the increment that captured its expected answer

*Amended during `5g-1a`.* Decisions 2 and 3 are only enforceable if each parity
increment can point at a specific, merged, frozen, domain-reviewed oracle. The
Phase 5g decomposition table in `MODERNIZATION_PLAN.md` therefore carries an
**"Expected answer captured by"** column, and an increment whose entry reads
`unassigned - blocking` **may not begin**. Naming and merging its oracle is the
first task of the increment that precedes it.

Applying this to the table exposed a gap the original decomposition did not
state: it names exactly one oracle increment, `5g-1a`, and every increment after
it ships modern code. Only `5g-1b` has an oracle. This is registered as residual
risk **R11**.

The first concrete casualty was the `C_BPartner_Location` tab, which
`contracts/legacy-web-write-v1/exclusions.tsv` originally assigned to `5g-1b`.
5g-1a excludes that tab from its capture because
`CalloutBPartnerLocation.formatPhone` puts callout arithmetic between the window
and the effect, so 5g-1b would have had to invent the answer it scores.
Callout-bearing writes are a distinct behaviour class - the 5g-0 inventory
classified 174 callout columns - and are now increment `5g-1f`.

### 4. Isolation is full seed restore, not surgical rollback

`scripts/phase5/reset-oracle-fixture.sh` cannot reset a write workload. It is
hard-wired to `AD_User_ID=101` (`:47`) and removes only capture-created
`AD_ChangeLog` (`:262`), `AD_Session` (`:271`) and that user's `AD_RecentItem`
(`:284`). It restores no business partner, order, tax, reservation,
`AD_PInstance` or accounting state.

Every capture - legacy A, legacy B, and each runtime - therefore restores the
marker-owned database from the seed, applies the reviewed fixture, and restarts
the relevant container to clear application caches. Surgical rollback is
forbidden until the complete transitive write set of a flow is proved, because a
rollback that misses one table silently makes the *next* capture start from a
different state than the previous one.

Ambient writers are quiesced with
`scripts/phase5/quiesce-phase5f-background-processors.sh`. Phase 5f found three
(timer-driven processors, automatic error reporting, and first-touch
`WebEnv.initWeb` initialisation), and an unquiesced writer is indistinguishable
from a route effect.

### 5. Effects are keyed relational facts, not table digests

`scripts/phase5/measure-window-readonly-effect.sh:114-151` accepts row-count
deltas but also requires table digests to be unchanged - a zero-write model by
construction, which cannot express a write at all.
`scripts/phase5/phase5f-route-smoke.py:65-112` works at table-digest
granularity, which proves *that* a route wrote but not *that a business
transition is correct*.

Write scoring therefore compares relational facts keyed by fixture identity:

- created, updated and deleted row counts by table, keyed by fixture key;
- the required foreign-key graph between created rows;
- before and after business column values;
- document status, document action, `Processed` and `Posted` values;
- accounting dimensions with balanced debit and credit totals;
- process status, summary, and parameter values.

Generated identities are normalized **through a captured mapping** and are never
dropped. Dropping them erases exactly the two defects the comparison exists to
catch: a broken foreign-key relationship, and a duplicated effect.

Normalization stays field-parsed and is protected in both directions - the A/B
self-diff detects under-normalization, and a committed raw fixture with a
mutation proof detects over-normalization - as
`scripts/phase5/verify-normalizer-mutation-proof.sh` already does for the read
oracle.

### 6. Modern writes are scored only through the public routed `/webui` origin

`zkwebui/build.gradle:1238-1267` registers `modernBrowserTest`, which drives the direct `/webui-modern` context. The
Phase 5e `routedBrowserTest` task at `zkwebui/build.gradle:1134-1159`
deliberately requires the public Tomcat 9 origin and supplies the loopback
modern port only so `RoutedCohortMatrixTest` can abort any direct request.

Every modern write is scored through the **public routed `/webui` origin** with
an allowlisted cohort, asserting that:

- the browser never reaches the loopback origin;
- the write belongs to the routed modern session;
- proxy failure never falls back to legacy;
- a failed or repeated AU request cannot execute a non-idempotent save or
  process twice;
- logout and timeout clean both runtimes after an in-flight write or process.

Direct Tomcat 10 capture is retained as a diagnostic subtest only. A green
direct test would leave ticket bootstrap, session affinity, request-body
proxying, response handling and proxy failure behaviour entirely outside the
write proof - which is precisely the surface that a write makes dangerous.

### 7. `contract-only-runtime-observation-pending` is banned for acceptance criteria

That disposition was honest in Phase 5f for the 25 `/wstore` JSP precompile rows
that no route vector reaches. It is never valid for a tier that defines an
increment's own claim. If a tier cannot execute on both runtimes, the gate fails
and the increment stays incomplete.

### 8. Accounting is a separate action from completion

`MOrder.completeIt()` (`base/src/org/compiere/model/MOrder.java:1760-1870`)
completes the document and returns `STATUS_Completed`. It does **not** call
`Doc.postImmediate`.

Accounting facts follow either the accounting processor - which
`scripts/phase5/quiesce-phase5f-background-processors.sh` deliberately disables
- or the explicit UI "Posted" document action
(`zkwebui/WEB-INF/src/org/adempiere/webui/panel/AbstractADWindowPanel.java:2128,2162,2175`
-> `apps/AEnv.java:319-329`). `Fact_Acct` rows are therefore never attributed to
the Complete action, and completion and posting are split across `5g-1c` and
`5g-1d`.

Order preparation also rejects a closed accounting period
(`base/src/org/compiere/model/MOrder.java:1239`), and the GardenWorld seed's
periods end in 2011. Any order fixture must use a reviewed historical date or a
marker-owned open period; a fixture defaulting to the current date will not
complete.

### 9. Server push remains polling

`phase-5-zk-target-adr.md` already decided Comet server push moves to polling on
ZK CE 10. Phase 5g reaffirms that decision unchanged and does not evaluate a
websocket transport. Revisiting it would introduce a new long-lived connection
class across the T5e-1 handoff and the T5f-1 proxy while both are still open.

### 10. Extension discovery precedes fixture design

Extension parity is scheduled at `5g-5`, but it is **not** independent of the
increments before it. Forms execute processes; callouts and validators fire on
every write and document transition; extension processes emit reports and accept
files; POS is itself an extension-facing surface.

`5g-0` therefore inventories the ZK-facing callout, validator, form, process and
report surfaces across `org.eevolution.*`, `org.spin.*` and `org.adempiere.*`
before any write fixture is designed. Every later fixture states whether
extension hooks fire, and whether they are included, quarantined, or absent.

### 11. A dictionary process is not a fixture until it is named

`ProcessPanel.runProcess()` constructs `ProcessCtl` directly and calls `run()`
(`zkwebui/WEB-INF/src/org/adempiere/webui/apps/ProcessPanel.java:688-689`), so
on the ZK path a process executes **synchronously on the request thread**.
`ProcessCtl.run()` (`client/src/org/compiere/apps/ProcessCtl.java:282`) contains
no threading; only `start()` (`:263`) spawns one, and the asynchronous
`parent != null` branches at `:134-138,205-210` are in the two static
`ProcessCtl.process(...)` overloads (`:92`, `:162`), which `ProcessPanel` never
calls. Do not assume
the Swing dispatch behaviour on the web path. Separately, an `AD_Process` may be
a report, a workflow, a Java class, or an SQL procedure.

`5g-0` produces a classified inventory and names one **non-report** process with
a fixed ID, deterministic parameters, expected `AD_PInstance` / parameter / log
rows, and an observable completion signal, for `5g-1e`. Report-type processes are
explicitly excluded until `5g-2`.

### 12. Write concurrency is new coverage

Phase 5e proved client/org/role/user/language identity isolation. It did not
prove transactional correctness under concurrent edits or process execution.

From `5g-1b` onward the matrix adds: two users editing the same record,
concurrent writes in different client/org contexts, a process-completion test
that records the executing identity and verifies thread-context cleanup, and
duplicate-submit protection or an explicit legacy-parity result.

*Amended during `5g-1a`.* "From `5g-1b` onward" describes where the **modern**
comparison runs, not where the expected answer comes from. Decisions 2 and 3
apply to concurrency exactly as they apply to any other behaviour class, so the
**legacy** conflict behaviour for two users editing one `C_BPartner` record is
captured, domain-reviewed and frozen by `5g-1a` as `concurrency-facts.tsv`, and
`5g-1b` scores the modern runtime against it. The remaining matrix rows above
each require the legacy answer their own oracle increment freezes; see decision
3a and residual risk R11.

### 13. `5g-7` owns the Phase 5 exit criteria that no increment otherwise claims

Screen-level visual comparison, parallel-run performance and error thresholds,
full distribution/database CI, and the complete rollback rehearsal are Phase 5
exit criteria. Without a named owner, every Phase 5g sub-increment could merge
green while Phase 5g itself remained incomplete.

### 14. `/mobile`, `/adempiere` and `/admin` are not enabled by Phase 5g

They follow the `/admin` precedent and remain legacy until a named consumer owns
them.

This is a **change** to the earlier assumption, recorded in `MODERNIZATION_PLAN.md`
R4 and in the Phase 5f findings, that Phase 5g would close the disabled
contexts. It is adopted deliberately: enabling a context with no identified
consumer produces migration risk and maintenance surface with no evidence of
value, and the same reasoning already governs `/admin`.

It interacts directly with Phase 5h, which removes Tomcat 9. `5g-0` therefore
**opens** a named disposition per context - **migrate**, **retire with usage
evidence**, or **narrow the Phase 5h removal scope** - with an owner and a
closing increment, in
`docs/modernization/phase-5g-disabled-context-disposition.md`. That document
also defines `phase5g-web-parity-gate`, which the frozen
`contracts/phase5f-jakarta-web-v1/enable-state-residuals.tsv` already names as
the closing gate for `ENABLE-P5F-MOBILE` and `ENABLE-P5F-ADEMPIERE` but never
defined: the gate is satisfied for a context when that context carries a
recorded disposition with its required evidence, and is satisfied neither by
enabling the context nor by leaving the decision open. It is implemented in
`5g-7` and Phase 5h is blocked behind it.

`5g-0` does not *settle* any of the three dispositions, and cannot: none can be
decided from this repository. The one outcome this ADR forbids is arriving at
Phase 5h with three legacy-only contexts and no decision.

## Consequences

- Phase 5g takes more branches than any prior phase. That is the cost of not
  letting one PR author both the expectation and the implementation.
- The legacy Tomcat 9 / ZK 3.6 runtime must stay bootable and capturable for the
  whole of Phase 5g. It cannot be retired before `5g-7`, which reinforces the
  existing Phase 5h ordering.
- Full seed restore per capture makes the database-backed gates slower than the
  Phase 5f shards. Sharding by tier is expected, following the Phase 5f pattern
  of recording vector failures rather than aborting and driving the job with
  `--continue` so one run reports the whole matrix.
- A reusable legacy write capture harness is new work.
  `zkwebui/src/browserTest/java/org/adempiere/webui/phase5c/LegacyWebSemanticOracleTest.java`
  implements one fixed sequence (`:123-204`), exposes exactly one window driver
  (`openErrorMessageWindow()` at `:262`), compares against a frozen
  `expectedFacts()` (`:230,339`), and invokes only the read-only effect
  measurement (`:133,210-211,413`). It is extended by adding flow-specific
  drivers over shared login/menu/window primitives, not by growing that test
  into one large conditional.
- H2, H4, H6, H7 and H8 fire for Phase 5g. H1 is not applicable, and H3 and H5
  are cleared. H6 fires **without any new component**: extending the existing
  transitional routing from authenticated read-only traffic to destructive and
  process traffic raises the consequence of every failure mode the routing can
  have.

## Alternatives rejected

| Alternative | Why rejected |
|---|---|
| One Phase 5g branch | Bundles a new capture harness, a new contract language, destructive fixture isolation, master-data CRUD, document transitions, accounting, process execution, modern defect fixes, routed security verification and CI topology movement into one review. Not reviewable, and not bisectable. |
| Score the modern runtime first and bless the result | A self-frozen master proves later refactors, not first-run correctness. `MODERNIZATION_PLAN.md` §3.6 requires the legacy comparison when a legacy runtime is available, and it is. |
| Extend `contracts/legacy-web-browser-v1/` additively | `gradle/phase5/browser-contract.gradle:25-49` regenerates `manifest.sha256` over every file in the tree and `:81-84` hard-fails on any unmanifested file, so adding a file necessarily rewrites a frozen one. `modern-comparable-facts.tsv:1-10` is also specifically the Phase 5d read-only comparison policy, not an extensible write registry. A new `contracts/legacy-web-write-v1/` tree references the login contract without mutating it. |
| Reuse `reset-oracle-fixture.sh` between write captures | It resets login and recent-item state for one hard-coded user and nothing else. Reusing it would let one capture's business rows leak into the next, producing a nondeterministic or falsely green oracle. |
| Assert `Fact_Acct` rows as an effect of Sales Order completion | Factually wrong. Completion does not post, and the quiescence step disables the processor that otherwise would, so the assertion could only ever fail or be satisfied by accident. |
| Score modern writes on the direct `/webui-modern` context | Leaves ticket bootstrap, session affinity, body proxying and proxy failure behaviour outside the proof, on exactly the traffic class where those failures become destructive. |
| Enable `/mobile` and `/adempiere` in Phase 5g | No named consumer. The `/admin` precedent already governs this case, and enabling an unowned context adds risk and maintenance surface with no evidence of value. |
