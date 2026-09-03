# Phase 5g-1a-y corrected-legacy workflow-attribution capture contract

This contract authorizes one narrow exception to the ordinary legacy-oracle
rule: the write oracle may be captured from a **corrected legacy runtime** when
the correction is fully represented by the reviewed patch in this directory and
is applied only inside a disposable detached worktree at the exact
`source_commit` in `capture-contract.tsv`.

The exception is capture-only. No patched production source, corrected ordinary
installer, corrected release archive, or shared runtime artifact may merge in
this oracle amendment. The resulting runtime exists only under
`build/phase5g1ay/`, is activated only while the explicitly selected
`corrected-legacy-workflow-attribution` capture mode runs, and the ordinary
installed runtime is restored byte-for-byte before scoring continues. Gradle
also snapshots both ordinary jars before the smoke task and invokes the
independent restoration guard as a finalizer, so a shell failure cannot leave
the corrected jar installed.

## Intended answer

For a document-triggered workflow, `AD_WF_Process`, `AD_WF_Activity`, and the
`AD_WF_EventAudit` rows created from those activities must be attributed to the
client and user that saved/invoked the document workflow. They must not inherit
the cached `MWorkflow` startup context.

The reviewed patch expresses the future production design without shipping it:

1. `DocWorkflowManager` passes `document.getCtx()` to a new `MWorkflow.start`
   overload.
2. The existing `MWorkflow.start(ProcessInfo)` remains and delegates with
   `getCtx()`, preserving all existing callers.
3. The new overload passes the explicit invocation `Properties` to a new
   `MWFProcess` constructor.
4. The existing `MWFProcess` constructor delegates with `wf.getCtx()`, preserving
   its callers, while the new overload supplies the invocation context to the PO
   superclass.
5. Existing named transaction, savepoint, commit, rollback, lock, and unlock
   propagation is unchanged.

`workflow-attribution-policy.tsv` makes the downstream measurement part of the
same amendment. `AD_WF_EventAudit` is removed from ambient classification and
keyed by `AD_WF_EventAudit_ID` through an exact fixture-workflow predicate. Its
direct `AD_WF_Process_ID` edge is symbolized by the existing generic normalizer;
because the table has no `AD_WF_Activity_ID`, its activity relationship is
represented without inventing a foreign key, by requiring its
`AD_WF_Process_ID`/`AD_WF_Node_ID` pair to match an in-scope activity. The
complete normalized audit row is compared, including at least client/org,
created/updated users, workflow user/responsible, process, node, table/record,
event type, and workflow state.

The currently frozen `business-values.tsv` answer still records client and
audit attribution as `0` for the workflow process and activity, and contains no
keyed event-audit row. Those rows are the **candidate to be replaced**, not a
fact this branch has already changed. They remain untouched until a
corrected-legacy freeze run is captured, reviewed, committed, and accepted by a
separate freeze-off run.

## Governance

The procedure is approved only when all of these controls remain true:

- patch SHA-256, hunk headers, touched paths, and the complete executable
  added/removed line sequence match this contract exactly;
- committed changes from the pinned `source_commit` through `HEAD`, combined
  with the separately inspected working tree, change exactly the files in
  `oracle-branch-allowed-paths.txt`, so neither scope can hide production-source
  or ordinary-artifact changes;
- working-tree inspection excludes only the two documented Ant-regenerated
  tracked outputs, `lib/ADInterface-1.0.war` and
  `lib/mysql-connector-java-5.1.13-bin.jar`; their committed versions remain
  protected like every other path under `lib`;
- each depth-one CI checkout narrowly fetches the exact pinned source object,
  and the materializer independently ensures that object exists before it
  invokes validation;
- the patch applies cleanly to the exact source commit and carries the explicit
  invocation context through the reviewed overload chain without adding side
  effects or changing the compatibility overloads;
- the corrected runtime is built from that disposable patched worktree and is
  not packaged into ordinary installed or release outputs;
- `AD_WF_EventAudit` remains absent from `ambient-tables.tsv`, carries the exact
  keyed predicate in `measurement-scope.tsv`, and the generator proves it can
  retain every required attribution column, symbolize the direct process edge,
  and match the process/node activity join;
- cleanup owns the exact disposable worktree through its adjacent ownership
  marker, unregisters only that path through Git, and fails rather than pruning
  repository-wide worktree state, suppressing unregister errors, or recursively
  deleting unregistered residue; ownership is asserted only after `git
  worktree add` succeeds, so a failed add cannot authorize trap cleanup of a
  foreign path;
- capture provenance records the current repository `HEAD` and the exact four
  runtime artifacts: corrected `Adempiere.jar` plus the top-level
  `DocWorkflowManager`, `MWorkflow`, and `MWFProcess` class outputs;
- restoration is required before scoring, and the independent Gradle finalizer
  restores and digest-verifies both ordinary jars even when the smoke fails;
- freeze and acceptance remain separate CI runs.

If this corrected-legacy procedure is not approved, capture cannot proceed. The
alternative is not to bless the cached-context result; the increment remains
blocked until another independently reviewed legacy oracle mechanism exists.

PR 18, https://github.com/samqbush/adempiere2/pull/18, is the later production
consumer. It remains blocked until the corrected legacy answer is captured,
domain-reviewed, accepted in a separate freeze-off run, and this oracle
amendment is merged to `develop`.
