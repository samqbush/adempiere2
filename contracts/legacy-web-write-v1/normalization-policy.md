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

Resolution is by `(column, value)` first and by value alone as a fallback. The
fallback is required for correctness, not convenience: `AD_ChangeLog.Record_ID`
points at a created row without being named after its table, and a
`(column, value)` lookup alone leaves it holding a raw generated id. The fallback
refuses to resolve when a value maps to more than one symbol, because inventing a
foreign-key edge that does not exist is worse than leaving a value literal.

## What is removed

| Class | Treatment | Why |
|---|---|---|
| `Created`, `Updated` | replaced with `<volatile>` | Wall-clock timestamps. They differ between any two captures and mean nothing to a transition. |
| Numeric scale | canonicalized by value | `10` and `10.00` are the same quantity. `10.5` and `10.6` still differ, and `numeric-value-changed` proves it. |
| Whitespace, non-breaking spaces | collapsed | Rendering volatility. Interior structure is preserved. |
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
