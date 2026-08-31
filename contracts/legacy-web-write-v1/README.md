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
| `raw/` | present | A committed raw capture sample; the input to the over-normalization mutation proof, **not** an oracle fact |
| `manifest.sha256` | present | SHA-256 over every file except itself |

### Not yet present: the captured facts

`effect-model.tsv`, `business-values.tsv`, `foreign-key-graph.tsv`,
`semantic-facts.tsv`, `network-classes.tsv`, `concurrency-facts.tsv`,
`allowed-browser-errors.tsv` and `write-flow.tsv` are **absent by design at this
commit**. They are captured from the booted legacy runtime by
`phase5g1aLegacyWriteOracleSmoke`, reviewed, and frozen here.

They are listed rather than silently omitted because an oracle tree that does not
say what it is missing is indistinguishable from one that has decided it needs
nothing. This section is the increment's own incompleteness, recorded.

## Two-layer effect model

Scoring against table digests is what Phase 5f did, and it can only ever prove
*that* a route wrote. Scoring against declared tables alone has the opposite
failure: a measurement that queries only the tables the contract names cannot see
a write to one it does not, which is a falsely-green gate.

So the capture is two layers:

1. a **whole-database changed-table sentinel**, which needs no declaration;
2. keyed relational extraction for each changed table.

The gate fails when a changed table is neither declared in the effect model nor
classified as reviewed ambient state. Layer 1 is what makes the exclusions in
`exclusions.tsv` provable rather than merely asserted.

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

**Not yet recorded.** The captured facts do not exist at this commit, and signing
off on facts that have not been captured would make the sign-off meaningless. The
review is recorded here, naming the reviewer and the date, before the facts are
frozen and before 5g-1b begins.
