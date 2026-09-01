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
of the archive it restored, so an independent restore can be told from a shared
one after the fact - two captures from one restore are not an A/B, because they
share every accident of the state they started in.

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
runtime; a capture that did not record its restore; a missing or failing ambient
census; a step ledger short of the frozen one; a scorer left in freeze mode; a
frozen contract edited out from under the scorer; a missing or failing H6 row; an
unobserved session lifecycle; a second modern deployment; an absent or empty
JUnit report.

A fail-closed check that silently never fires is worse than no check: it accepts
what it should refuse, quietly, forever. So
`verify-phase5g1b-runtime-evidence-validator.py` builds a synthetic evidence
tree the validator must accept, then mutates it once per defect class and
requires a rejection each time.

**17 injected defect classes, all rejected.** The proof was itself verified by
weakening one validator check and confirming the proof went red.

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
