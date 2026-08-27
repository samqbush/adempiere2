# Phase 5e routed web contract

This directory is the reviewed Phase 5e contract. It is **separate from**
`contracts/legacy-web-v1/`, which stays pristine: the Phase 5b oracle describes
the frozen ZK 3.6 product, and editing it to describe a routed product would
replace a baseline with a moving target.

| File | What it fixes |
|---|---|
| `derived-artifact-diff.tsv` | The complete set of entries in which the routed `webui.war` may differ from the frozen one, and how each may differ. |
| `public-route-classes.tsv` | The closed public affinity unit: every method and path the router may proxy, and the refused rows that make it closed. |
| `proxy-header-policy.tsv` | The request and response header allowlists, in both directions. |
| `cohort-configuration.tsv` | The configuration grammar, precedence, decision lifetime, ticket parameters, key rules and cookie policy. |
| `manifest.sha256` | The digests of the files above. |

Every row is asserted against the implementation by
`verifyPhase5eCohortContracts`, and the artifact diff is recomputed rather than
trusted by `verifyPhase5eDerivedLegacyWar`. A change to any of these files
without a matching change to the code, or the reverse, fails the
database-neutral gate.

## What this contract deliberately does not pin

**Compiled digests.** The overlay jar is build output. Pinning its bytes would
turn every ordinary source change into a contract edit, and a contract that is
edited on every change stops being read. What is pinned instead is the *shape*
of the difference and the overlay's package closure, which cannot be satisfied
by accident.

**Legacy behaviour.** The routed archive's legacy cohort is compared against
`contracts/legacy-web-v1/` by the Phase 5e browser gate, not re-frozen here.
