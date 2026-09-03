# Phase 5g-1a-y workflow-attribution oracle amendment evidence

**Status: in progress and unverified.** Candidate run
https://github.com/samqbush/adempiere2/actions/runs/33779282955 completed two
self-consistent captures but was rejected: the corrected jar retained stale
code-signature entries, Java refused the replaced workflow classes with a
`SHA-384 digest error`, and the generated candidate dropped all workflow rows.
No candidate facts from that run are accepted or committed, and no separate
freeze-off acceptance run exists.

## Claim

For a document-triggered workflow, `AD_WF_Process`, `AD_WF_Activity`, and the
`AD_WF_EventAudit` row created from the activity are attributed to the
saving/invocation client and user rather than the cached `MWorkflow` startup
context.

The currently frozen `contracts/legacy-web-write-v1/business-values.tsv` rows
still carry `AD_Client_ID=0` and `CreatedBy` / `UpdatedBy=0`. They are the
candidate to be replaced after capture and review, not a corrected fact already
accepted by this branch. The frozen file still has no keyed event-audit row or
event-audit process edge.

## Oracle-only implementation

| Control | Implemented surface |
|---|---|
| Exact source | `capture-contract.tsv` pins `60af453aab0e6537a6189241366c857c239c959f`; both Lane 1 jobs fetch that exact object from the depth-one checkout, and the materializer ensures it exists before validation |
| Exact patch | `corrected-legacy-workflow-attribution.patch`, SHA-256 pinned in the contract, with exact hunk headers and executable added/removed line sequences |
| Exact touched paths | `allowed-patched-paths.txt` lists only `DocWorkflowManager.java`, `MWorkflow.java`, and `MWFProcess.java` |
| Disposable source | `materialize-phase5g1ay-corrected-runtime.sh` uses a detached worktree under `build/phase5g1ay/corrected-source`; an exact adjacent owner marker bounds cleanup, Git must unregister that exact path successfully, and cleanup never calls repository-wide prune or recursively deletes residue |
| Capture-only runtime | The corrected jar and classes exist only under `build/phase5g1ay/corrected-runtime` |
| Signed-jar correction | `removed-signature-entries.txt` pins the exact two stale signature entries; the materializer requires and removes exactly them, verifies none remain, and records the list in provenance |
| Ordinary runtime preservation | Gradle snapshots both installed `Adempiere.jar` copies before the smoke, the external guard owns activation/restoration, the smoke requires restoration before scoring, and a Gradle finalizer restores and digest-verifies both copies even on failure |
| Explicit opt-in | `-Pphase5g1ayMode=corrected-legacy-workflow-attribution` or `ADEMPIERE_PHASE5G1AY_MODE`; default remains `legacy` |
| Provenance | Repository head must equal `git rev-parse HEAD`; the artifact inventory is exactly corrected `Adempiere.jar` plus top-level `DocWorkflowManager.class`, `MWorkflow.class`, and `MWFProcess.class`, with every digest verified |
| No production merge | The neutral validator compares committed changes from the pinned source to `HEAD`, inspects working-tree mutations separately, excludes only the two documented Ant-regenerated tracked outputs from the latter, and keeps their committed versions protected |
| Keyed event audit | `AD_WF_EventAudit` is absent from ambient classification and keyed by its generated id through the exact fixture process/activity predicate |
| Attribution comparison | The generic generator is exercised with a synthetic event-audit row and must retain client/org, created/updated users, workflow user/responsible, process, node, table/record, event type, and state; it must symbolize the process edge and match an activity on process/node |
| Runtime row guard | Before freeze or acceptance scoring, both captures must contain exactly one keyed process, activity, and event-audit row with client 11 and saving-user 101 creation/update attribution |

The reviewed patch preserves existing callers through overload delegation and
does not change named transaction, savepoint, commit, rollback, document lock,
or unlock propagation. The patch is capture input only. PR 18,
https://github.com/samqbush/adempiere2/pull/18, is the later shared production
implementation consumer.

## Database-neutral gates

`phase5g1ayFinalVerification` chains `phase5g1aFinalVerification` and adds:

- exact contract-manifest, patch-digest, and touched-path validation;
- committed production-source and ordinary-artifact immutability from the
  pinned source commit through `HEAD`, plus separate fail-closed working-tree
  inspection with only the two documented Ant-regenerated tracked outputs
  excluded;
- exact patch-hunk and executable changed-line validation, including preserved
  `getCtx()` compatibility delegation and no added side effects;
- bounded materializer/output validation, including exact-object fetch before
  validation, exact stale-signature removal, and exact owned-worktree cleanup
  that fails on unregister errors;
- exact repository-head, removed-signature, and four-artifact provenance validation;
- independent activation/restoration guard validation, including the Gradle
  failure finalizer, mandatory pre-score restoration, and corrected workflow
  row/attribution presence in both captures;
- 56 mutation cases covering cached-context regressions, altered overload
  delegation, side effects, fetch bypass, working-tree/committed-scope
  weakening, unsafe worktree cleanup, activation/restoration bypass,
  allowlist/manifest weakening, wrong repository head, omitted, wrong, extra,
  or malformed artifacts, and dropped, re-ambiented, under-keyed, or
  under-attributed event-audit facts.

This gate makes no runtime claim. It cannot prove the corrected legacy runtime
booted or that the database rows carry the decided attribution.

The workflow's current-phase smoke selects corrected mode unconditionally for
this increment. Until the isolated candidate-capture run is reviewed and its
candidate facts are committed, the ordinary freeze-off job is expected to fail
against the old frozen zeros. That is the intended fail-closed state; selecting
legacy mode would make the prerequisite falsely green.

## Required CI evidence

| Evidence | Status |
|---|---|
| Corrected-legacy freeze run triggered by the prerequisite branch push | **Run 33779282955 rejected; rerun required after stale-signature fix** |
| Domain review of the candidate attribution and new fact digest | **Not recorded** |
| Committed frozen fact update | **Not present** |
| Separate corrected-legacy freeze-off acceptance run | **Not run** |
| Merge to `develop` | **Not complete** |

PR 18 remains blocked until every row above is complete.

## Hazard review

- H1, H2, H3, H4, H5, and H6 are cleared: no dependency removal, framework or
  runtime bump, route rewrite, data-store major, or insecure transition occurs.
- H7 is cleared: the branch was cut exactly from `origin/develop` at
  `60af453aab0e6537a6189241366c857c239c959f`.
- H8 fires: the phase-gate head and current database smoke mode move, so
  `MODERNIZATION_PLAN.md`, `ARCHITECTURE.md`, README, the workflow, and CI
  topology move in this increment.
