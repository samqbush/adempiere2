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
| `ambient-tables.tsv` | present | The reviewed session/audit/workflow tables exempt from the undeclared-table backstop |
| `raw/` | present | A committed raw capture sample; the input to the over-normalization mutation proof, **not** an oracle fact |
| `manifest.sha256` | present | SHA-256 over every file except itself |
| `write-flow.tsv` | present | The captured step ledger: ten steps, in the order the driver executed them |
| `effect-model.tsv` | present | Index of the frozen per-step effect documents under `effect-model/` |
| `effect-model/` | present | One frozen sectioned effect document per measured step |
| `business-values.tsv` | present | The business column values the flow left behind |
| `foreign-key-graph.tsv` | present | The edges this flow actually wired between rows it created |
| `semantic-facts.tsv` | present | The UI-observable facts the driver asserted |
| `network-classes.tsv` | present | Request classes, including the four third-party origins the legacy login page reaches |
| `concurrency-facts.tsv` | present | The second editor's outcome and the conflicting save's verdict |
| `allowed-browser-errors.tsv` | present | The browser errors the legacy runtime is permitted to produce |

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
| Capturing CI run | https://github.com/samqbush/adempiere2/actions/runs/33491714444 |
| Captured commit | `3a0f5fd911d85a545661bdee067be710f47d2fda` |
| Evidence digest | `edd369f6f6f5636d7459c251608d0cb39abb4d931aa673acd69b576d1cf4df20` |
| Disposition | **Approved as the expected answer for Phase 5g-1b.** |

The digest is SHA-256 over the eight frozen fact files and every per-step effect
document, each preceded by its name, in sorted order. It is what ties this
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

### A recorded limitation of the `[no-effect]` marker

Five steps — `window-opened`, `concurrency-second-editor-authenticated`, `deactivate-editor-authenticated`, `concurrency-conflicting-save` and `logged-out` — carry `[no-effect]` with the explicit value
`no-keyed-change-in-scope	no-row-count-delta-outside-ambient`.

That value states precisely what was measured, and no more. The whole-database
sentinel is a row **count** per table, so an `UPDATE` to a table outside the
nine-table measurement scope produces neither a keyed row nor a count delta, and
an insert paired with a delete inside one step nets to zero. The marker is
therefore not a claim that nothing happened; it is a claim that these two
measurements saw nothing. Narrowing the blind spot is residual **R12** in
`MODERNIZATION_PLAN.md`.

This is load-bearing for the headline concurrency fact: the refused save's
entire content is that assertion, so the assertion has to be one the measurement
can actually support.

Human judgement is not automatable. This block is provenance; the governance
control is the named reviewer's approval on the pull request that froze it.
