# Phase 5g-1a raw normalizer fixture

`before.json` and `after.json` are **normalizer test data**, not oracle facts.

They exist so that over-normalization can be proved. The A/B self-diff already
catches under-normalization: two isolated captures of the same flow that
disagree mean something volatile survived. It cannot catch the opposite defect.
A normalizer that erased business values, foreign-key edges or duplicate rows
would make every capture agree with every other one, and the self-diff would go
green precisely because the comparison had stopped comparing anything.

Detecting that needs an input whose *correct* answer is known independently of
any capture, which is what these files are. They are shaped like a real create
step — one `C_BPartner`, one dependent `C_BPartner_Location` carrying the
foreign key, one `AD_ChangeLog` row, and a sentinel that moves — but their
values are chosen, not observed.

They are deliberately **not** the frozen oracle. The frozen oracle lives in
`effect-model.tsv` and is captured from the legacy runtime. Confusing the two
would reintroduce exactly the tautology the Phase 5g ADR forbids: a branch that
both invents the expected answer and implements the thing being scored.

Scored by `scripts/phase5/verify-write-normalizer-mutation-proof.py`.
