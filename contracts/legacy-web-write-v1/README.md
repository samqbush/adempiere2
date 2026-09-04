# `contracts/legacy-web-write-v1` — the legacy Business Partner write oracle

This tree is the **expected answer** for the first business write in the
ADempiere modernization programme. It is captured from the **legacy** Tomcat 9 /
ZK 3.6 runtime and frozen, so that Phase 5g-1b can score the modern runtime
against an answer it did not invent.

No modern business write has ever been observed anywhere in this programme. The
modern runtime is proven for login, role selection, menu, and a read-only window;
it writes `AD_Session` on login and nothing else. Phase 5f route observation is
route observation, not write parity. This tree does not change that: **5g-1a
proves no parity and ships no modern runtime code.**

## Why a new tree rather than an extension of the frozen browser contract

`contracts/legacy-web-browser-v1/` cannot be extended additively.
`gradle/phase5/browser-contract.gradle` regenerates `manifest.sha256` over every
file in that tree and hard-fails on any unmanifested file, so adding one file
necessarily rewrites a frozen one. Its `modern-comparable-facts.tsv` is also
specifically the Phase 5d **read-only** comparison policy, not an extensible
write registry. That contract asserts *zero business writes*, so by construction
it cannot score one.

This tree therefore *references* the frozen login and role-selection contract
without mutating it, and carries its own manifest with the same "an unexpected
file is a failure" property.

## Why Business Partner is the first write

From the Phase 5g-0 discovery inventories, and independently re-derived by
`scripts/phase5/generate-phase5g-write-attribution.py`:

| Table | Callout columns | Registered model validators |
|---|---|---|
| `C_BPartner` (291) | 0 | 0 |
| `C_BPartner_Location` (293) | 1 | 0 |
| `C_Order` (259) | 5 | 2 |
| `C_OrderLine` (260) | 12 | 0 |

`C_BPartner` is the only table in the reviewed scope with **neither** a callout
**nor** a model validator on its path. A difference observed on this flow is a
difference in the window/save path itself, not callout arithmetic or an
extension hook firing on one runtime and not the other. That attribution is
mechanically re-proved by `verifyPhase5gWriteAttribution` on every run — an
inventory cross-reference would not catch a validator being edited to subscribe
to `C_BPartner`, because the 5g-0 inventories record declarations, not table
subscriptions.

## Scope

| | |
|---|---|
| Operations | create, update, deactivate (`IsActive='N'`) |
| Not covered | hard delete, `C_BPartner_Location` — see `exclusions.tsv` |
| Identity | `GardenAdmin` (`AD_User_ID` 101, `AD_Role_ID` 102) in `AD_Client_ID` 11 |
| Concurrency | `GardenUser` (`AD_User_ID` 102, `AD_Role_ID` 103) participates in the concurrency step only |
| Fixture key | `Value LIKE 'P5G1A-%'` |

## Files

| File | Status | Contents |
|---|---|---|
| `README.md` | present | This commentary preamble |
| `attribution-scope.tsv` | present | The reviewed tables the attribution analyzer must examine |
| `attribution.tsv` | present | Frozen callout and registered-validator attribution, regenerated and matched on every run |
| `measurement-scope.tsv` | present | Where the keyed effect layer looks |
| `fixture.sql` | present | Preconditions asserted before any capture |
| `exclusions.tsv` | present | Owned exclusions, each with a reason and a closing increment |
| `normalization-policy.md` | present | What is removed, what is deliberately kept, and why both directions are proved |
| `ambient-tables.tsv` | present | The reviewed session/dictionary-audit/sequence/UI tables exempt from the undeclared-table backstop |
| `raw/` | present | A committed raw capture sample; the input to the over-normalization mutation proof, **not** an oracle fact |
| `manifest.sha256` | present | SHA-256 over every file except itself |
| `write-flow.tsv` | present | The captured step ledger: twelve steps, in the order the driver executed them |
| `effect-model.tsv` | present | Index of the frozen per-step effect documents under `effect-model/` |
| `effect-model/` | present | One frozen sectioned effect document per measured step |
| `business-values.tsv` | present | The business column values the flow left behind |
| `foreign-key-graph.tsv` | present | The edges this flow actually wired between rows it created |
| `semantic-facts.tsv` | present | The UI-observable facts the driver asserted |
| `network-classes.tsv` | present | Request classes, including the four third-party origins the legacy login page reaches |
| `concurrency-facts.tsv` | present | The second editor's outcome and the conflicting save's verdict |
| `allowed-browser-errors.tsv` | present | The browser errors the legacy runtime is permitted to produce |
| `transport-class-policy.tsv` | present | The reviewed comparison semantics for each fact class, decided on legacy evidence before any modern capture existed |

### Workflow-attribution amendment (`5g-1a-y`)

Corrected-legacy candidate run
https://github.com/samqbush/adempiere2/actions/runs/33785079015 replaced the
cached-startup attribution in `AD_WF_Process` and `AD_WF_Activity` with
`AD_Client_ID=11` and `CreatedBy` / `UpdatedBy=101`, and added the keyed
`AD_WF_EventAudit` row with the same saving-context attribution. The exact
CI-generated business values, create effect, propagated identity comments, and
foreign-key edges were accepted by separate freeze-off run
https://github.com/samqbush/adempiere2/actions/runs/33788686426.

Residual R14 records the domain decision that a document-triggered workflow must
use the saving/invocation client and user rather than `MWorkflow`'s cached
startup context. Increment `5g-1a-y` adds the capture-only corrected-legacy
mechanism under `contracts/phase5g1ay-workflow-attribution-v1/`. Its exact patch
is data, is applied only in a disposable detached worktree, and does not change
the checked-out files under `base/src`.

That amendment also makes `AD_WF_EventAudit` a keyed, non-ambient workflow fact.
Its primary key is normalized like every other capture-created identity; its
`AD_WF_Process_ID` resolves to the created process; and its
`AD_WF_Process_ID`/`AD_WF_Node_ID` pair must match an in-scope
`AD_WF_Activity`. The complete normalized audit row is captured in
`business-values.tsv`, with the client/org, created/updated users, workflow user
and responsible, process, node, table/record, event type, and workflow state all
retained for comparison. The exact machine-readable requirement is
`contracts/phase5g1ay-workflow-attribution-v1/workflow-attribution-policy.tsv`.

The candidate was reviewed as one domain change: workflow process/activity
attribution moves from startup `0/0` to saving client/user `11/101`, and event
audit becomes a keyed consequence of that same workflow. No unrelated business
value, semantic fact, concurrency result, or transport fact changed. The
acceptance run reported A/B self-diff `pass` and zero scoring problems. PR 18,
https://github.com/samqbush/adempiere2/pull/18, remains blocked until this
oracle amendment merges.

### The captured facts

Captured from the booted legacy Tomcat 9 / ZK 3.6 product through the public
`/webui` origin, self-diffed across two fixture-isolated captures, and frozen
here. See "Domain review" below for the run they came from.

### The shape `effect-model.tsv` takes, decided before the driver was written

`measure-write-effect.py` emits **one sectioned document per step**, not one row
per step, so a single flat TSV cannot hold the frozen effects. Rather than
flatten the documents — which would discard the section structure the scorer
compares — `effect-model.tsv` is an **index**: one row per step, naming the step,
the operation, and the per-step document under `effect-model/` that is its frozen
answer. The documents keep the emitted format exactly, so what is reviewed is
what is compared.

The step identity is carried in the compared payload as a `[step]` section, not
in a comment header. `score` strips comments before comparing, so a step id
carried only in a header would never be compared, and the create effect of step 1
would score cleanly against the frozen model of step 3 — which would make
per-step measurement decorative. `score` fails a frozen model that carries no
`[step]` section at all.

Writing this down before the driver exists is deliberate: driver and contract
cannot drift if the shape was agreed first.

## Two-layer effect model

Scoring against table digests is what Phase 5f did, and it can only ever prove
*that* a route wrote. Scoring against declared tables alone has the opposite
failure: a measurement that queries only the tables the contract names cannot see
a write to one it does not, which is a falsely-green gate.

So the capture is two layers:

1. a **whole-database changed-table sentinel**, which needs no declaration;
2. keyed relational extraction for each changed table.

The gate fails when a changed table is neither declared in the effect model nor
classified as reviewed ambient state. "Declared" means declared in the **keyed**
sections — `[created]`, `[updated]`, `[deleted]` — not merely present in the
sentinel. Reading it from the sentinel too would let a table declare itself
simply by having changed, and the backstop could then never be the sole cause of
a failure. Requiring a keyed declaration is also the right rule on its own terms:
a table that changed but sits outside the measurement scope cannot be examined at
all, which is the precise condition this check exists to refuse. Layer 1 is what
makes the exclusions in `exclusions.tsv` provable rather than merely asserted.

That ambient classification lives in `ambient-tables.tsv`, in this tree, under
the manifest and under the domain review — not as a constant in a script. It is
the only list in the increment that can make an unexpected write acceptable, so
widening it must change the manifest digest and require a reviewer. An ambient
table is still measured and still appears in every captured effect document; it
is excluded only from the compared payload, because a table that churns
non-deterministically is exactly what "ambient" describes and requiring its delta
to match byte for byte would make the classification unreachable.
`verifyPhase5gAmbientClassificationMutationProof` scores four mutations across
three directions: an undeclared business table must fail — proved twice, the
second time with capture and contract byte-identical so that the backstop is
demonstrably what failed the run rather than a payload diff — a classified table
must be forgiven, and reclassifying a business table as ambient must actually
turn the failure green, the last one proving in the repository that this file is
load-bearing rather than cosmetic.

Effects are measured **per step**, not once around the whole flow. A single
before/after pair around create → update → deactivate shows only the final
deactivated row, so a create that wrote the wrong value and an update that
corrected it would be indistinguishable from a correct run.

## Isolation

Full seed restore, reviewed fixture, and container restart before **every**
capture — capture A, capture B, and later every runtime. Surgical rollback is
forbidden until the complete transitive write set is proved; a rollback that
misses a row the flow wrote produces an oracle that is green because it is
comparing contaminated state with contaminated state.

Ambient writers are quiesced first. Phase 5f found three, and an unquiesced
background writer is indistinguishable from a route effect.

## Domain review

**Recorded.**

| Field | Value |
|---|---|
| Reviewer | @samqbush |
| Date | 2026-09-01 |
| Capturing CI run | https://github.com/samqbush/adempiere2/actions/runs/33513391616 |
| Captured commit | `a250709cbbea4d063e3e8eb6b26420e800c9e664` |
| Effect documents | Re-derived locally from that run's snapshots — see "How these bytes were produced" below |
| Evidence digest | `c3ffc902ccca93e149e6984e95240ec0bfc3c7b409cb3cc5ad323ce673ce0ce3` |
| Disposition | **Approved as the expected answer for Phase 5g-1b.** |

### Workflow-attribution amendment review

| Field | Value |
|---|---|
| Reviewer | @samqbush |
| Date | 2026-09-03 |
| Corrected-legacy candidate run | https://github.com/samqbush/adempiere2/actions/runs/33785079015 |
| Captured commit | `b9bab1e44722237b021cee31d7b03badd54f3ecb` |
| Freeze-off acceptance run | https://github.com/samqbush/adempiere2/actions/runs/33788686426 |
| Accepted candidate commit | `cebd25609c8d3c30b34972599af95cd0caac778a` |
| Evidence digest | `a0fc668b69b19ce61901c9a27185aa935c74db33458652080b2abf977fbd2112` |
| Reviewed change | Process/activity client and audit attribution `0/0` → saving context `11/101`; one keyed event-audit row and its process/record edges added; unrelated facts unchanged |
| Disposition | **Accepted as the expected workflow-attribution answer for Phase 5g-1b.** |

### How these bytes were produced

The browser-and-database observation came from the CI run above. The frozen
effect documents did **not** come directly from that run's `--freeze` output, and
saying so plainly is the point of this block.

That run captured the flow successfully — twelve steps, both the A and B
captures, self-diff clean — but its `Contracts` job concluded `failure` at
`verifyPhase5gWriteOracleManifest`, which is expected for a freeze run, since a
freeze run rewrites the very files the manifest pins. More importantly,
`ad_sequence` was not yet classified ambient at `a250709`, so on the four steps
whose every changed table is ambient the run emitted effect documents with no
`[no-effect]` marker, and `score` would have rejected them for an undeclared
table. Those bytes were wrong.

Rather than re-run the capture, the run's snapshot artifacts were downloaded and
every effect document was re-derived by re-running
`scripts/phase5/measure-write-effect.py diff` over **those same snapshots** with
the corrected `ambient-tables.tsv`, for both the A and B captures, and the result
re-frozen with `score-write-oracle-capture.py --freeze`.

This is legitimate because `diff` is a pure function of the before snapshot, the
after snapshot, the step baseline, the attribution scope and the ambient list: it
opens no database and no network, and the scripts are byte-identical to those at
`a250709`. The classification is an *input* to the derivation, not an
observation, so correcting it does not require re-observing the product. `freeze`
additionally refuses to write unless the A and B captures self-diff clean, which
they did — two independently restored captures agreeing is what makes the
re-derivation checkable rather than asserted.

The control that closes this is the **separate acceptance run**,
https://github.com/samqbush/adempiere2/actions/runs/33528308317: a CI run with
freezing off that re-captured the flow from scratch and scored it against the
bytes frozen here, reporting "A/B self-diff clean and both captures match the
frozen contract". If the re-derivation had produced anything the product does
not actually do, that run would have failed. The two-run protocol is also
recorded in `MODERNIZATION_PLAN.md`.

The digest is SHA-256 over the nine frozen fact files, in the order the gate
declares them, followed by every per-step effect document in sorted order, each
preceded by its name. The ninth file is `transport-class-policy.tsv`, which is
in the digest because it decides *how* every other fact class is compared, so
relaxing a class must move the reviewed digest. It is what ties this
sign-off to specific bytes: re-freezing from a different capture changes it, and
the change is visible in this file and in `manifest.sha256`.

The captured snapshots are the raw evidence, and the frozen facts are derived
from them. A review-driven change to the derivation therefore changes the frozen
bytes without changing the run they came from, and this digest moved once for
exactly that reason: code review found that composite-keyed rows, and every row
in a step after the one that created it, were freezing raw sequence-allocated
integers into the comparison, that a deleted capture-created row would still
have done so, and that the edge scan skipped every composite key component --
which is where the fan-out edges live, so `foreign-key-graph.tsv` recorded the
three workflow edges and none of the four the create step actually wires. It now
records all seven, including `ad_treenodebp node_id c_bpartner` and the three
accounting rows. The facts above were re-derived from the same run's snapshots,
both captures still agree, and both still score against the contract.

### Amendment in increment `5g-1a-x`

This answer was re-captured and re-frozen once more, and the digest moved a
second time. The amendment shipped no modern runtime code, and every decision
below was taken on legacy evidence alone — which is the point of doing it in its
own increment rather than during parity.

* **The `[no-effect]` marker became content-aware (residual R12, now closed).**
  The whole-database sentinel was a row **count** per table, so it could not see
  an `UPDATE` outside the then-nine-table measurement scope, and an insert paired with
  a delete inside one step netted to zero. It now also carries a per-table
  content fingerprint taken inside the same consistent snapshot, and the marker
  reads `no-keyed-change-in-scope  no-content-change-outside-ambient`. This
  changed the emitted document format, which is why the answer had to be
  re-captured rather than edited.

  The repair immediately produced signal that had been invisible: `ad_recentitem`,
  `ad_session`, `c_bpartner` and `ad_sequence` all now show `+0 content` rows on
  steps where the row count did not move.

* **`AD_Sequence` is classified ambient.** `MSequence.getNextID` issues
  `UPDATE AD_Sequence SET CurrentNext = ...`
  (`base/src/org/compiere/model/MSequence.java:205`), so every id allocation
  mutates the row without changing the row count. It was always written; it was
  never before *visible*. It is classified for the same reason `AD_Sequence_No`
  already was, and the classification is bounded: ambient status affects only the
  undeclared-table backstop and the `[no-effect]` marker, and `AD_Sequence` is not
  one of the nine measurement-scope tables, so it cannot hide a keyed effect.

* **A duplicate-submit step was added, so the answer exists before parity asks
  for it.** The flow is now twelve steps. A repeated, non-idempotent AU save
  request is replayed against the running legacy runtime. The legacy answer is
  that the replay returns **HTTP 200**, the step's total effect is exactly one
  `c_bpartner.name` transition, and no table's row count moved — that is, the
  replay created no duplicate record and left no other trace.

  What that does **and does not** assert is worth stating, because the step will
  be scored for parity. The replayed request sets `Name` to a fixed value, so
  re-applying it is idempotent by value; `updated` is normalized volatile,
  `updatedby` is unchanged either way, and `C_BPartner` has change logging off,
  so a second application of the same `UPDATE` would produce a byte-identical
  effect document. The oracle therefore asserts *no duplicate row and no
  additional effect*, not *the server declined to execute the statement twice*.
  Phase 5g-1b scores the modern runtime against *this*, rather
  than against the Phase 5e single-use ticket invariant, which is a different
  property. The step runs as the second editor, which preserves the `deactivate`
  step's frozen `updatedby 102 -> 101` transition.

* **Comparison semantics are declared per fact class, in
  `transport-class-policy.tsv`.** Two classes are legacy-theme transport
  artifacts rather than product facts, and were being compared by exact list
  equality, which a modern capture would fail by construction and for no product
  reason. `themesaf.css.dsp` does not exist anywhere in this repository or in the
  shipped ZK jars — `theme.css.dsp`, `themeie.css.dsp` and `thememoz.css.dsp` do
  — so Chromium requests a Safari stylesheet the deployment does not contain and
  is answered 404. The row count of that error tracks how many browser sessions
  the flow opened, and re-capturing with two more sessions moved it from six rows
  to eight, which is the empirical demonstration that it was never a product
  fact. The four external origins are the same theme's font and branding
  references. Both classes move to `declared-subset`; every other class, including
  both `network-classes.tsv` classes that carry product and security meaning,
  stays `exact`. The A/B self-diff stays exact for every class.

  The relaxation is bounded by its own gate,
  `verifyPhase5gTransportClassPolicyProof`, which proves among other things that
  an undeclared row still fails, that a class absent from the policy is still
  compared exactly, and that a byte-identical capture scores clean.

What was reviewed, and what it says:

* **The write is real and it fans out.** Creating a business partner writes
  `C_BPartner` and starts a document workflow — `AD_WF_Process`, `AD_WF_Activity`
  and `AD_WF_EventAudit` each gain a row — and creates the three default
  accounting rows and the tree node. A modern runtime that writes only
  `C_BPartner` has not reproduced this.
* **The conflicting save is refused.** With a second editor holding the row, the
  first editor's save is rejected, the status bar reads "Current record was
  changed by another user, please ReQuery", and the step's measured effect is
  empty: no created, updated or deleted row, and no changed table. The refusal is
  a real refusal, not a silent last-write-wins.
* **`UpdatedBy` moves to the second editor.** The concurrency capture can say
  which editor won because `CreatedBy`/`UpdatedBy` are deliberately not
  normalized.
* **`AD_ChangeLog` is empty throughout.** GardenWorld does not log column changes
  for `C_BPartner`. That is the legacy answer, recorded rather than assumed; the
  table stays in the measurement scope so that a runtime which starts logging is
  caught.
* **The legacy login page reaches four third-party origins** —
  `sfx-images.mozilla.org`, `www.google.com`, `www.zkoss.org` and
  `fonts.googleapis.com`. These are product content, not browser noise, and they
  are frozen as facts a modern runtime will be scored against.
* **One error class is allowed**: repeated 404s for
  `/webui/theme/default/css/themesaf.css.dsp`, the same missing DSP theme URL
  Phase 5f registered.
* **Five steps declare `[no-effect]`** — opening the window, the second editor's
  login, the conflicting save, the deactivating session's login, and logout. The
  emptiness is declared rather than inferred, so a step that later starts
  writing fails.

### What the frozen keys do and do not normalize

No compared byte in this contract carries a raw sequence-allocated identity.
Every row created during a capture is rendered through a capture-local symbol
(`@c_bpartner#1`, `@c_bp_customer_acct#1`, `@ad_treenodebp#1`), and the
`[identities]` legend that maps each symbol back to the observed integer is a
comment, so it is stripped before comparison.

Three resolution rules earn that, and each is a declared fact rather than a
guess:

- **Composite-keyed children** are declared and looked up under their own
  table's primary key component, so `c_bp_customer_acct` gets its own symbol
  instead of collapsing onto the business partner whose id is its first key
  component.
- **Identity is capture-scoped, not step-scoped.** The reference is the
  capture's post-login baseline snapshot, so the row created in the create step
  is still symbolic in the update and deactivate steps. A row present in the
  baseline is seeded and is never handed a capture-local symbol.
- **Generic pointers resolve only when something qualifies them.**
  `Record_ID` resolves through its companion `AD_Table_ID`; `Node_ID` resolves
  through its containing table, because `AD_TreeNodeBP` holds business-partner
  nodes and nothing else. Neither ever resolves by bare value, which would
  collide across tables sharing a sequence range.

This matters because it is what makes the oracle scoreable by a runtime that
allocates different integers. Without it, 5g-1b would fail this comparison for
a reason that is about identity allocation rather than about the business
transition.

### What the `[no-effect]` marker now measures, and what it still does not

Six steps — `window-opened`, `concurrency-second-editor-authenticated`,
`duplicate-submit-editor-authenticated`, `deactivate-editor-authenticated`,
`concurrency-conflicting-save` and `logged-out` — carry `[no-effect]` with the
explicit value
`no-keyed-change-in-scope	no-content-change-outside-ambient`.

That value states precisely what was measured, and no more. Residual **R12**
closed the two blind spots the earlier row-count sentinel had: an `UPDATE`
outside the then-nine-table measurement scope, and an insert paired with a delete
netting to zero, are both now visible, because the sentinel carries a per-table
content fingerprint alongside the count and both are taken in the same
consistent snapshot. `concurrency-conflicting-save` demonstrates the layering: it
reports `ad_recentitem +0 content` and still emits `[no-effect]`, because
`ad_recentitem` is classified ambient.

Two limits remain, and are recorded rather than papered over:

* The fingerprint is a **fingerprint, not a cryptographic digest**. It sums a
  pair of 64-bit halves of each row's `md5(row::text)`, which is adequate against
  accidental and defective writes — the failure mode this oracle exists to catch
  — and is not a defence against a deliberately constructed collision. Summation
  is used rather than XOR on purpose: XOR cancels a duplicated identical row pair
  to zero, which would reintroduce a blind spot while appearing to close one.
* A change confined to an **ambient** table is still not asserted, by design.
  That is what the classification means, and `ambient-tables.tsv` is
  manifest-covered so widening it requires a reviewer. `AD_WF_EventAudit` is
  explicitly outside that exemption because its attribution can distinguish a
  correct workflow save from one using cached startup context.

This is load-bearing for the headline concurrency fact: the refused save's
entire content is that assertion, so the assertion has to be one the measurement
can actually support.

Human judgement is not automatable. This block is provenance; the governance
control is the named reviewer's approval on the pull request that froze it.
