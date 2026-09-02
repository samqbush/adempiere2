# Phase 5g-1b evidence: modern Business Partner CRUD parity

**Status: in progress. No gate in this document may be reported as green until
the run id that produced it is recorded here.**

## The claim

The modern ZK CE `10.3.0.1-jakarta` / Tomcat 10.1 runtime, driven **only**
through the public routed `/webui` origin, produces the same keyed relational
effects, business values, foreign-key graph, semantic facts and concurrency
verdict that Phase 5g-1a froze from legacy ZK 3.6 / Tomcat 9.

Before this increment, **no modern business write had ever been observed**.
Phase 5d proved login, role selection, menu and a read-only window; Phase 5e
proved cohort routing and session isolation; Phase 5f proved route and context
topology. None of them wrote a business record.

## What is scored, and against what

`contracts/legacy-web-write-v1/` is **read-only** here. The modern captures are
scored against the frozen legacy answer by the same scorer, in the same
freeze-off mode, that the legacy acceptance run used.

There is deliberately **no** modern contract tree and **no**
`runtime-divergence.tsv`. A list of "acceptable differences" written after the
modern runtime has been observed is an oracle fact wearing a parity increment's
clothes: it lets the increment decide, with the answers in front of it, which of
its own failures do not count. Removing that file from the design is what makes
reclassification structurally impossible rather than merely discouraged. If the
modern conflicting save is not refused, that is a parity failure and a modern
defect to fix.

## Architecture

### The selector-dialect seam

`LegacyBusinessPartnerWriteOracleTest` was 1036 lines of interleaved flow
orchestration and ZK 3.6 markup resolution. It is now 44 lines that bind a
dialect to a shared flow:

| Type | Source set | Role |
|---|---|---|
| `BusinessPartnerWriteFlow` | `writeParitySupport` | The 12-step ledger, rendezvous ordering, traffic recording and semantic-fact emission. Shared and invariant. |
| `ZkDialect` | `writeParitySupport` | The seam. Locating, operating and awaiting a control - nothing else. |
| `Zk36Dialect` | `writeParitySupport` | Legacy mechanics, moved verbatim. **Declared closed.** |
| `ZkCe10Dialect` | `writeParitySupport` | The modern implementation. |

**A dialect may express only how a control is located, operated and awaited. It
may never normalize a behavioural difference away.** Step order, emitted facts,
rendezvous and outcome vocabulary are shared, so a modern runtime that behaves
differently produces a different *answer*, not a different *flow*.

The extraction landed as its own commit with no modern code, and the legacy
freeze-off regression was proven green on it. The legacy dialect was then
closed; modern work adds a sibling rather than editing shared semantics.

### Runtime identification: why a green can lie

Every other observation in a capture is runtime-blind. The browser only ever
sees the public origin. Recorded URLs are normalized against it. The database
effects are the product's, and the product is the same source tree either way.

So a routed lane whose cohort decision, handoff or proxy failed **closed** would
serve the *legacy* application, score a **perfect green** against the legacy
oracle, and report modern parity. Nothing else in the capture could see it.

`ZkDialect.identifyServingRuntime` closes that hole. Both dialects implement it,
it is asserted per capture by the lane and again by the validator, and it is
written to its own file rather than into `facts` so the frozen answer is
unchanged and the legacy regression still scores clean.

The markers are the ones Phase 5e already proved: `phase5d-modern.css` for
modern, `.dsp` for legacy.

Related: `reset-cohort-config.sh verify` compares only the total `AD_SysConfig`
row count. Since every capture restores the archive the snapshot was taken from,
a match is close to a tautology. It is recorded as a **tamper check**, not as
proof of routing.

### The routed lane

`run-write-parity-lane.sh` is `run-write-oracle-lane.sh`'s sibling, not its
replacement. Same golden-dump/quiesce/restore/fixture cycle, same file
rendezvous, same per-step snapshot boundaries. It differs in exactly two places:
the deployment behind the public origin, and the dialect the driver binds.

Three additions over the legacy lane:

- **A container lifecycle adapter, not a lane name.** The routed lane needs a
  repo root, an installed home and a handoff key for two runtimes, which does
  not fit `stop-/start-legacy-browser-lane.sh <port>`. Parameterizing the reseed
  primitive by lane string would have meant per-lane isolation guards, which is
  the one thing they must not be. The stop is now **confirmed** at every declared
  port before database sessions are terminated: terminating sessions belonging to
  a container that is still running does not isolate anything.
- **An ambient census after every restore.** Quiescence is a statement about
  configuration; the modern runtime is new to this database; and Phase 5f already
  found a first-touch writer (`WebEnv.initWeb`) that no configuration disables.
  Two bounded quiet intervals with no browser attached: the first must show no
  *non-ambient* change, the second must show **no** change at all - because an
  ambient writer that never settles would satisfy the first forever.
  **`ambient-tables.tsv` is not widened in 5g-1b** to forgive a newly observed
  table.
- **Topology recorded from the running lane** - bound sockets and artifact
  digests - rather than from the tasks that were supposed to produce them.

### Session evidence, separate from the business oracle

`AD_Session` is ambient and unkeyed, so a modern change to an *existing* session
row is invisible to the sentinel by design. A separate non-business model
records marker-owned session rows around each authenticated write and across the
logout/timeout cases, without adding volatile rows to the Business Partner
oracle. `AD_ChangeLog` is asserted to stay as 5g-1a froze it.

### Isolation

Full seed restore, including for **every** H6 row. Running a destructive case on
state left by the parity capture would violate ADR decision 4 and make results
order-dependent. Surgical rollback is forbidden. Each capture records the digest
of the archive it restored and **brackets** its own restore with the instants it
started and finished. The digest is byte-identical for A and B by construction,
so on its own it could never distinguish two independent restores from two
captures sharing one; and a single timestamp stamped beside the restore call
would be produced whether or not the restore actually ran.

Bracketing is what binds the record to the restore having happened. A full seed
restore takes minutes, so the validator requires the digests to agree, each
interval to exceed a one-minute floor, and the two intervals to be **disjoint**
- a capture that skipped its restore records a near-zero interval, and two
captures sharing one restore record overlapping ones. Two captures from a single
restore are not an A/B: they share every accident of the state they started in.

## The H6 write-traffic matrix

Six rows, each recorded to `h6/h6-matrix.tsv` and required by the validator.
Destructive rows restore the golden archive first, so no row is scored on state
another row left.

| Row | How it is decided |
|---|---|
| `h6-loopback-origin-unreached` | The driver's context blocks the loopback modern port; a request that reached it is recorded |
| `h6-cohort-decision-modern` | The router's own decision line, with a browser-observed fallback (see below) |
| `h6-no-legacy-fallback-mid-write` | `ModernNoLegacyFallbackTest` (see below) |
| `h6-ticket-replay-controls` | The T5e-1 handoff controls, re-run unchanged |
| `h6-session-cleanup-after-inflight-write` | Session evidence before and after logout and timeout |
| `h6-duplicate-submit` | Scored against the legacy answer `5g-1a-x` froze, not against a Phase 5e ticket-replay invariant, which is a different property |

### `h6-no-legacy-fallback-mid-write` needs a browser, not a `curl`

The contract is that when the modern backend dies mid-write, an **established**
modern session gets an explicit failure and is never quietly served the legacy
application instead.

That assertion is only meaningful from a vantage point that actually holds a
modern session. The router pins the cohort at login, so a shell request carries
no modern session and is served the legacy **login page** - which itself links
the `.dsp` theme. A browser-less check would therefore report "legacy marker
present" and read a by-design login page as a fallback that never happened.

So the row invokes `ModernNoLegacyFallbackTest`, which ports the mechanics of
Phase 5e's proven `backendOutageNeverFallsBack()`: log in modern through the
public origin, confirm `phase5d-modern.css` and no `.dsp`, stop the backend,
re-navigate **on the same authenticated context**, and require `status >= 500`
**and** no `.dsp`. A null response scores `-1`, so an absent observation fails
the row rather than falling through it. The stop is inside the guarded region
whose `finally` restarts the backend: were it outside, a stop that killed Tomcat
but reported a non-zero exit would leave the backend dead for every row after
it.

Phase 5e's existing control could not be reused directly - it is a private
method inside one large `@Test`, so it cannot be `--tests`-selected, and running
the whole Phase 5e matrix to obtain one row is not acceptable.

### One thing this increment does not prove

`h6-cohort-decision-modern` prefers the router's own
`phase5e-cohort runtime=MODERN reason=USER_ALLOWLISTED` decision line, which is
the only record that carries the **reason**. Whether that `log.info` reaches the
public ingress's `catalina.out` in this deployment is **not** established: every
existing Phase 5e evidence asserts routing by browser observation, never by this
line.

The row therefore falls back to the driver's browser-observed served runtime,
and says so in its evidence string. That fallback is a **served-runtime**
observation, not a decision reason - a browser can see `MODERN`, never
`USER_ALLOWLISTED` - and the two are never conflated in the record. A row with
neither fails. The first CI run of the smoke will settle which branch fires;
until then this is reported as a known limit of the row, not as coverage.

## Capture run log

Each modern defect is fixed in its own commit citing the run it closes.

| Run | Reached | Finding |
|---|---|---|
| [33570169991](https://github.com/samqbush/adempiere2/actions/runs/33570169991) | 25m24s, before any modern write | Lane defect: the parity lane seeded itself from the state the legacy regression left behind |
| [33572353340](https://github.com/samqbush/adempiere2/actions/runs/33572353340) | 30m45s, capture A fixture applied, browser not yet driven | Script defect: `psql` does not interpolate `:'var'` inside a `--command` string, so session evidence failed with `syntax error at or near ":"` |

### Run 33570169991 - the seed

The lane's own fixture guard caught a defect in the **lane**, not the modern
runtime:

> `Phase 5g-1a fixture precondition failed: 1 business partner row(s) with a
> P5G1A- key survived the reseed. The database was not restored from the golden
> archive.`

`phase5g1bModernWriteParitySmoke` depends on the legacy freeze-off regression so
the oracle is re-proven at PR HEAD, and that regression finishes with capture
B's post-write state - `P5G1A-0001` included - still in the database. The parity
lane then took its "golden" baseline from exactly that state.

The failure was the smaller half of the problem. A lane that seeded itself this
way would have scored the modern runtime **from a starting state the legacy
oracle never ran from**, and parity between two runtimes is only meaningful from
a bit-identical seed. The lane now restores the archive the legacy lane captured
from the quiesced installed product - the same verified state that produced the
frozen answer - and takes its own golden archive from there, failing closed if
that archive is absent rather than seeding itself from whatever ran before it.

### Run 33572353340 - session evidence

The seed fix held: `Phase 5g-1a fixture preconditions satisfied`, and the
routed ambient census passed with a clean scope. The session-evidence capture
then failed on `psql` variable interpolation, which does not happen inside a
`--command` string. The label is now embedded by the shell as a SQL literal,
after being constrained to `^[A-Za-z0-9_-]+$` so a value reaching a statement
can never carry quoting with it.

## Gates

| Gate | Kind | Status |
|---|---|---|
| `phase5g1bFinalVerification` | database-neutral, new `Contracts` chain head | Verified locally; CI run to be recorded |
| `phase5g1bModernWriteParitySmoke` | database-backed, new current-phase smoke | **Not yet executed** |

### The evidence validator, and its own validator

A green Gradle status is not evidence. `validate-phase5g1b-runtime-evidence.py`
refuses - rather than repairs - evidence that would otherwise let a green be
cited: a wrong `git_head`; a base URL other than the public origin; a request
that reached the loopback modern origin; a capture not served by the modern
runtime; a capture that did not record its restore; two captures sharing one
restore; a missing or failing ambient census; a step ledger short of the frozen
one; a scorer left in freeze mode; a frozen contract edited out from under the
scorer; a re-frozen contract that agrees with its own regenerated manifest; a
missing or failing H6 row; an unobserved session lifecycle; a second modern
deployment; a JUnit report that is absent, empty, ran zero tests, or failed.

A fail-closed check that silently never fires is worse than no check: it accepts
what it should refuse, quietly, forever. So
`verify-phase5g1b-runtime-evidence-validator.py` builds a synthetic evidence
tree the validator must accept, then mutates it once per defect class and
requires a rejection each time.

**25 injected defect classes, all rejected.**

The proof was itself verified, not asserted. The increment's central governance
guard - that a parity branch may not re-freeze the answer it is scored against -
is two independent checks, and each was proven to be load-bearing by disabling
it alone and confirming the proof went red with exactly one undetected class:

| Check disabled | Class the proof then missed |
|---|---|
| pinned manifest digest | a contract re-frozen so that it agrees with its own regenerated manifest |
| manifest walk | a contract file the manifest still lists |

That isolation matters because the obvious mutation - editing a frozen contract
file - also trips the step-ledger comparison, so it would have stayed green even
if both manifest guards were dead code.

The synthetic contract is built in the **real** generator's format, comment
headers and tab separators included. An earlier fixture used a format
`generatePhase5gWriteOracleManifest` never emits, which let a defect survive
review: the validator parsed the manifest's `#` comment headers as digest rows
and would have rejected **every** real run - after two captures, the scorer and
the whole H6 matrix had already run - with a message falsely accusing the branch
of editing the frozen oracle.

The lane invariants are proven the same way, and match only non-comment lines:
a whole-file substring search is satisfied by the very comment explaining the
rule, so a change that deleted the behaviour and left its explanation behind
would still have passed.

It runs in about a second with no database, so the validator's correctness is
checked on every pull request rather than only when the hour-long lane runs.

### Why there is no freeze path

The legacy gate has one, because the legacy lane is what *produces* an oracle. A
parity increment that could re-freeze the contract it is scored against would be
inventing its own expected answer.

The guard is threefold rather than conventional:

1. `run-write-parity-smoke.sh` does not **accept** a freeze argument at all.
2. The workflow emits `-Pphase5g1aFreeze=true` only when the selected debug gate
   starts with `phase5g1a`.
3. `verifyPhase5g1bLaneInvariants` fails the database-neutral gate if that script
   ever passes `--freeze` to the scorer.

All four lane invariants were mutation-tested: freeze passed to the scorer,
scoring against a modern contract tree, capturing on the loopback modern origin,
and a lane that stopped asserting the serving runtime. Each was detected.

## CI topology movement

- `Contracts` → `phase5g1bFinalVerification phase5cFinalVerification`.
  Re-derived, not re-headed: addition set `{:phase5g1bFinalVerification,
  :verifyPhase5g1bEvidenceValidator, :verifyPhase5g1bLaneInvariants}`, **removal
  set empty**, union 301 = merged 301. The empty removal set is the evidence that
  dropping `phase5g1aFinalVerification` from the arguments is safe, not the
  assumption behind it.
- `Current-phase database smoke` → `phase5g1bModernWriteParitySmoke`.
- `Regression matrix` → `phase5g1aLegacyWriteOracleSmoke` added (eight gates).
- `timeout-minutes` raised 180 → 330, because the parity gate **dependsOn** the
  legacy one and this single job now runs both write lanes plus the H6 matrix.
  Splitting them would need a second required status check, and branch protection
  is administered manually and references jobs by name. The cost is accepted and
  recorded rather than designed around.
- Debug allowlist gains `phase5g1bModernWriteParitySmoke` and
  `phase5g1bModernWriteParityCapture`, so ZK CE 10 selector work - the expected
  failure mode - can be reproduced without the hour-long legacy regression.

Job **names** are unchanged; branch protection references them by name.

## Known risks

- **Modern write-path defects are expected, not exceptional.** Suspect surfaces:
  ZK CE 10 combobox/datebox editors, `AbstractADWindowPanel`'s save path, the
  status/error popup the concurrency step asserts, and the Comet → polling change
  altering save-feedback settlement.
- **Settlement is the hardest driver problem.** The legacy dialect waits on
  `/zkau` requests and payloads; ZK CE 10's payload shape differs. A wrong wait
  produces a *flaky* capture rather than a red one.
- **ZK CE 10 disabled state.** ADempiere's `ToolBarButton` extends
  `org.zkoss.zul.Toolbarbutton`, which ZK CE 10 renders as
  `<a role="button" disabled="disabled">`. Playwright treats an element as
  natively disabled only for `BUTTON`/`INPUT`/`SELECT`/`TEXTAREA`/`OPTION`/
  `OPTGROUP`, and ZK sets no `aria-disabled` - so `isEnabled()` on that anchor is
  **unconditionally true**. A poll built on it could never return `accepted`, and
  its fall-through value is byte-identical to the frozen headline conflict
  answer. The dialect therefore reads the DOM directly. The structural backstop:
  `save()` hard-fails on any non-accepted outcome and runs twice before the
  conflicting save, so a detection fault fails loudly at the first save rather
  than quietly at the last.
- `ZkCe10Dialect`'s selectors are **reasoned, not runtime-proven**. Each is
  either already proven against ZK CE 10 by the Phase 5d modern slice, or owned
  by ADempiere's own source and therefore identical across runtimes because both
  compile the same `WEB-INF/src`. Only a CI capture can validate them.
- `phase5eCohortRoutingSmoke` is a known intermittent Playwright flake under
  heavy parallel CI load.
- **T5e-1 and T5f-1 remain open** through Phase 5h. This increment re-exercises
  them; it does not close them.

## Residual risk closure

**R12 is closed**, by `5g-1a-x`, and is recorded as closed in this increment's
evidence. The sentinel now carries a content component, so the five frozen steps
that assert `[no-effect]` - including the headline refused conflicting save - are
compared on content as well as row count before any modern runtime is scored
against them.

**R11 is narrowed, not closed.** `5g-1b` names `5g-1c`'s oracle as
`5g-1c-oracle`. Naming is not capturing: the decomposition row still reads
`named, not yet captured/merged; blocking`, and only the increment that actually
freezes and domain-reviews the Sales Order answer may remove that qualifier.
`5g-1d` through `5g-7` remain `unassigned - blocking`.

## Runs

| Run | Commit | Gate | Result |
|---|---|---|---|
| [33548556277](https://github.com/samqbush/adempiere2/actions/runs/33548556277) | `8bbd8a829` | `phase5g1aLegacyWriteOracleSmoke`, freeze off | **success** - the dialect extraction did not move the legacy oracle |

Further runs are recorded here as they execute. An unrun gate is never presented
as green.
