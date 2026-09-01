# Phase 5g-1a write-capture normalization policy

Implemented by `scripts/phase5/normalize_write_capture.py`. Scored in both
directions by `scripts/phase5/verify-write-normalizer-mutation-proof.py`.

## The rule that matters most

**Generated identities are normalized through a captured mapping and are never
dropped.**

Dropping them is tempting. They are the most volatile values in a capture: every
reseed restarts the sequences, so `C_BPartner_ID` differs between any two runs
for no business reason. But dropping them erases exactly the two defects the
comparison exists to catch:

- a **broken foreign-key relationship** — a created location that stops pointing
  at the created business partner renders identically to one that does;
- a **duplicated effect** — the same logical row created twice collapses into
  one.

Both are scored as `foreign-key-broken` and `effect-duplicated` in the mutation
proof. Mapping identities to stable symbols keeps both observable while removing
the volatility.

The mapping is built from the rows the capture **created**, not from an ID-range
heuristic. "Ids above one million are generated" is a guess about the seed that
would silently misclassify rows the moment a sequence moved. Membership in the
created set is a fact about this capture.

Resolution is by `(column, value)`, where the column is the target table's own
primary key. That covers ordinary foreign keys, because an ADempiere key column
is named after the table it points into.

Two further rules exist, and each is qualified by a **declared fact** rather than
by a bare value:

- A **generic row pointer** resolves only when something says which table it
  points into. `AD_ChangeLog.Record_ID` and `AD_WF_Process.Record_ID` are
  qualified by the companion `AD_Table_ID` in the same row; `AD_TreeNodeBP.Node_ID`
  is qualified by its containing table, which by definition holds business-partner
  nodes and nothing else. Without a qualifier the value stays literal.
- A **composite key** is declared and looked up under its primary component only.
  Looking it up under the full composite string made every such row miss its own
  symbol and freeze a raw sequence-allocated integer into the comparison;
  declaring it under the shared first component instead would collapse it onto
  the parent. `generated-identities-moved` moves a composite key precisely so
  that this cannot regress unnoticed.

Neither rule ever resolves by bare value, and both refuse to resolve when a value
maps to more than one symbol: inventing a foreign-key edge that does not exist is
worse than leaving a value literal.

Identity is scoped to the **capture**, not to the step. The reference is the
capture's post-login baseline snapshot, so a row created in the create step is
still symbolic in the update and deactivate steps, while a row present in the
baseline is seeded and never receives a capture-local symbol.

## What is removed

| Class | Treatment | Why |
|---|---|---|
| `Created`, `Updated` | replaced with `<volatile>` | Wall-clock timestamps. They differ between any two captures and mean nothing to a transition. |
| Numeric scale | canonicalized by value | `10` and `10.00` are the same quantity. `10.5` and `10.6` still differ, and `numeric-value-changed` proves it. |
| Whitespace, non-breaking spaces | collapsed | Rendering volatility. Interior structure is preserved. |
| `UUID` | replaced with `<volatile>` | ADempiere allocates a fresh random identifier to every row it inserts, so two captures of the same flow always disagree. It carries no business meaning and no in-scope row references it. Scored as `record-uuid-moved`. |
| Generated identities | mapped to symbols | See above. Never dropped. |

## What is deliberately kept

| Class | Why |
|---|---|
| `CreatedBy`, `UpdatedBy` | The acting user. This is the whole point of the concurrency capture: without `UpdatedBy` the oracle cannot say which of two editors won. Scored as `updatedby-changed`. |
| `NULL` versus `''` | Different facts. Collapsing them would hide a column that stopped being populated. Scored as `null-became-empty`. |
| `IsActive` | The deactivate step's entire observable effect. Scored as `isactive-flipped`. |
| Unlisted timestamp columns | Truncating every timestamp that is not explicitly classified would be over-normalization by default. |

## Why both directions are proved

The A/B self-diff proves the normalizer removes **enough**: two isolated captures
of the same flow that disagree mean something volatile survived.

It cannot prove the normalizer removes **only** enough. A normalizer that erased
business values would make every capture agree with every other, and the
self-diff would go green precisely because the comparison had stopped comparing
anything. That needs an input whose correct answer is known independently of any
capture, which is what `raw/` is.
