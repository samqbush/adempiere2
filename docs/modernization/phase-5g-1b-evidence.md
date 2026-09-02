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

`ZkDialect.identifyServingRuntime` closes that hole. Each dialect declares its
`expectedRuntime()`, the shared default method identifies the runtime that
actually answered, and it asserts them equal. It is written to its own file
rather than into `facts`, so the frozen answer is unchanged and the legacy
regression still scores clean - and because it is a lane precondition rather
than a product behaviour, asserting it cannot mask a divergence. It can only
refuse a capture that was never about the program it claims.

It runs for **every one of the four sessions**. That is not defensive
duplication: cohort routing decides per *identity*, and the flow drives four
sessions under two. Run 33626582558 is what one sample costs - see the narrative
for that run below.

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
of the archive it restored and **brackets** its own restore with the instants it
started and finished. The digest is byte-identical for A and B by construction,
so on its own it could never distinguish two independent restores from two
captures sharing one; and a single timestamp stamped beside the restore call
would be produced whether or not the restore actually ran.

Bracketing is what binds the record to the restore having happened. A full seed
restore takes minutes, so the validator requires the digests to agree, each
interval to exceed a one-minute floor, and the two intervals to be **disjoint**
- a capture that skipped its restore records a near-zero interval, and two
captures sharing one restore record overlapping ones. Two captures from a single
restore are not an A/B: they share every accident of the state they started in.

## The H6 write-traffic matrix

Six rows, each recorded to `h6/h6-matrix.tsv` and required by the validator.
Destructive rows restore the golden archive first, so no row is scored on state
another row left.

| Row | How it is decided |
|---|---|
| `h6-loopback-origin-unreached` | The driver's context blocks the loopback modern port; a request that reached it is recorded |
| `h6-cohort-decision-modern` | The router's own decision line, with a browser-observed fallback (see below) |
| `h6-no-legacy-fallback-mid-write` | `ModernNoLegacyFallbackTest` (see below) |
| `h6-ticket-replay-controls` | The T5e-1 handoff controls, re-run unchanged |
| `h6-session-cleanup-after-inflight-write` | Session evidence before and after logout and timeout |
| `h6-duplicate-submit` | Scored against the legacy answer `5g-1a-x` froze, not against a Phase 5e ticket-replay invariant, which is a different property |

### `h6-no-legacy-fallback-mid-write` needs a browser, not a `curl`

The contract is that when the modern backend dies mid-write, an **established**
modern session gets an explicit failure and is never quietly served the legacy
application instead.

That assertion is only meaningful from a vantage point that actually holds a
modern session. The router pins the cohort at login, so a shell request carries
no modern session and is served the legacy **login page** - which itself links
the `.dsp` theme. A browser-less check would therefore report "legacy marker
present" and read a by-design login page as a fallback that never happened.

So the row invokes `ModernNoLegacyFallbackTest`, which ports the mechanics of
Phase 5e's proven `backendOutageNeverFallsBack()`: log in modern through the
public origin, confirm `phase5d-modern.css` and no `.dsp`, stop the backend,
re-navigate **on the same authenticated context**, and require `status >= 500`
**and** no `.dsp`. A null response scores `-1`, so an absent observation fails
the row rather than falling through it. The stop is inside the guarded region
whose `finally` restarts the backend: were it outside, a stop that killed Tomcat
but reported a non-zero exit would leave the backend dead for every row after
it.

Phase 5e's existing control could not be reused directly - it is a private
method inside one large `@Test`, so it cannot be `--tests`-selected, and running
the whole Phase 5e matrix to obtain one row is not acceptable.

### One thing this increment does not prove

`h6-cohort-decision-modern` prefers the router's own
`phase5e-cohort runtime=MODERN reason=USER_ALLOWLISTED` decision line, which is
the only record that carries the **reason**. Whether that `log.info` reaches the
public ingress's `catalina.out` in this deployment is **not** established: every
existing Phase 5e evidence asserts routing by browser observation, never by this
line.

The row therefore falls back to the driver's browser-observed served runtime,
and says so in its evidence string. That fallback is a **served-runtime**
observation, not a decision reason - a browser can see `MODERN`, never
`USER_ALLOWLISTED` - and the two are never conflated in the record. It demands
**all four** of the write flow's sessions, not the bare `served` row: cohort
routing decides per identity and the flow uses two, so one row is a quarter of
the answer. A row with neither record fails. The first CI run of the smoke will settle which branch fires;
until then this is reported as a known limit of the row, not as coverage.

## Capture run log

Each modern defect is fixed in its own commit citing the run it closes.

| Run | Reached | Finding |
|---|---|---|
| [33570169991](https://github.com/samqbush/adempiere2/actions/runs/33570169991) | 25m24s, before any modern write | Lane defect: the parity lane seeded itself from the state the legacy regression left behind |
| [33572353340](https://github.com/samqbush/adempiere2/actions/runs/33572353340) | 30m45s, capture A fixture applied, browser not yet driven | Script defect: `psql` does not interpolate `:'var'` inside a `--command` string, so session evidence failed with `syntax error at or near ":"` |
| [33580195848](https://github.com/samqbush/adempiere2/actions/runs/33580195848) | 22m07s, failed in the **legacy** regression before the parity lane started | Latent oracle-lane flake: `PA_Goal` is a wall-clock-triggered lazy writer, so two captures that straddle an hour boundary diverge |
| [33584462937](https://github.com/samqbush/adempiere2/actions/runs/33584462937) | 32m10s; legacy regression **green**, routed lane prepared, ambient census **passed**, modern capture A driving | Driver defect: `ZkCe10Dialect.signIn` navigated to the bare origin instead of `/webui/`, so the browser landed on ADempiere's `/admin/` page |
| [33586831680](https://github.com/samqbush/adempiere2/actions/runs/33586831680) | 31m31s; modern login, role, menu and the Business Partner window all rendered, `served=modern`, steps 0-1 measured; failed clicking Save | **First modern runtime defect**: `NumberBox` attached its calculator handlers with ZK 3.6's `setAction`, which ZK 10 rejects at render time, and the resulting error overlay intercepted the click |
| [33679068657](https://github.com/samqbush/adempiere2/actions/runs/33679068657) | 35m18s; **root cause located** | `atClickWidgetDisabled=true` on the failing page against `false` on the working one, with `postHandlerQueued` 1 against 2. ZK's `Toolbarbutton.doClick_` discards a click on a `_disabled` widget silently. The button reads enabled thirty seconds later, which is why every earlier probe found it healthy. Fixed by awaiting ZK's own enabled state before clicking |
| [33673776776](https://github.com/samqbush/adempiere2/actions/runs/33673776776) | 38m22s; same failure, same step; **the click reaches ZK and ZK sends nothing** | `zk.dragging` was never set and the click propagated all the way to `document`, where ZK's single delegated click listener lives, with its `ignoreClick()` guard false - on both pages. Everything outside ZK is excluded. What remains unobserved is the inside of ZK's handler at click time: `Toolbarbutton.doClick_`'s silent `_disabled` return and `fireX`'s silent `evt.stop()` return, neither of which the 30-seconds-later dump can speak to |
| [33669560347](https://github.com/samqbush/adempiere2/actions/runs/33669560347) | 30m27s; same failure, same step; **the witnessed axes are identical, the toolbars are not** | A trusted DOM click reached the Save anchor on both pages, with no popup, no mask, and `disabledRequest`, the AU queue, `ajaxReq` and `pendingReqInf` healthy on both - yet only the primary sent its `onClick`. The Save controls differ in `sclass`: three disable-enable cycles on the primary against one on the deactivate page. The stale-node reading was retracted - ZK delegates clicks from `document` - leaving `zk.dragging` and an in-flight `stopPropagation` |
| [33664809767](https://github.com/samqbush/adempiere2/actions/runs/33664809767) | 34m34s; same failure, same step; **the wire question is settled** | The `deactivate` save produced **no** AU request naming the Save control across 22 posts, while the primary's conflicting save produced one, bundled behind an `onBlur`. The click is therefore lost before ZK sends, and the server is exonerated. Every widget property remains healthy, so the search narrows to whether a DOM click reaches the anchor at all |
| [33658582428](https://github.com/samqbush/adempiere2/actions/runs/33658582428) | 33m40s; same failure, same step, and **every client-side explanation refuted** | The Save control's widget is bound exactly to its own anchor, `class=zul.wgt.Toolbarbutton`, `disabled=false` on both the widget and the DOM, `desktop=yes`, `inServer=true`, `asapsClick=true` and listening for `onClick` in both `isListen` forms; `zk` and `zAu` are live with `processing=false`, `mounting=false`, no error boxes and **no console error**. The preserved console log carries only the six legacy-theme 404s |
| [33653427475](https://github.com/samqbush/adempiere2/actions/runs/33653427475) | 32m49s; **the layout fix holds where it is measured** - the Active checkbox now has a real box, hit-tests to itself and clears; failed in the `deactivate` **save** | The click reached the server (`dataStatusChanged: *1/1`), and then the Save click produced **no `/zkau` request at all** - the modern access log shows nothing but empty 18-byte polls until Tomcat stopped, and `GridTable.dataSave` was never invoked. The old `saveButton` probe read `!!e.disabled` on an anchor, which is always `false`, so it had never actually reported the control state |
| [33647155695](https://github.com/samqbush/adempiere2/actions/runs/33647155695) | 33m20s; **the event-thread fix worked** - the modern capture cleared the Find dialog, the second editor, the conflicting save and the duplicate submit, and failed at step 10 `deactivate` | The refusal is now observed on the modern runtime: the primary editor shows `*2/18` and *Current record was changed by another user, please ReQuery*. The new failure is layout, not logic - clicking the Active checkbox is intercepted by the border layout's own centre body and by the field grid, and the failure screenshot shows the field area painting blank although the DOM carries every editor |
| [33640642306](https://github.com/samqbush/adempiere2/actions/runs/33640642306) | 33m; same failure, same session | **Root cause.** The criterion diagnostic showed `wed.getValue()` returning `P5G1A-0001` correctly - and showed `getQuery` logging `Restrictions=0` *before* `cmd_ok_Simple` ran at all. ZK 3.6 enables the event processing thread by default and ZK 5 and later do not, so `Window.doModal()` no longer blocks - it branches on `isEventThreadEnabled()` and quietly degrades the window to a non-modal mode - and `AbstractADWindowPanel.initialQuery` read `FindWindow.getQuery()` from a dialog the operator had not yet touched |
| [33636622993](https://github.com/samqbush/adempiere2/actions/runs/33636622993) | 32m; same failure, same session | **The driver is exonerated.** The uuid assertion passed: the `onChange` carrying the key was reported for exactly the widget the driver filled. Everything observable from the browser now agrees, so the divergence is server-side and the next observation has to come from there |
| [33631958003](https://github.com/samqbush/adempiere2/actions/runs/33631958003) | 31m50s; **the first structurally valid modern capture** - all sessions reaching the flow were served ZK 10, and the legacy Tomcat logged nothing during it; failed positioning the *second editor*, one session earlier than before | The `onChange` guard did **not** fire, so the server was told the key. Both Tomcat logs now read cleanly against each other: legacy logs `UPPER(C_BPartner.Value) LIKE 'P5G1A-0001%'`, modern logs `MQuery[C_BPartner,Restrictions=0]` for the same driver actions. A **real modern divergence**, isolated at last on a capture that was entirely modern |
| [33626582558](https://github.com/samqbush/adempiere2/actions/runs/33626582558) | 33m; legacy lane green end to end; modern capture A completed steps 0-9 for the fourth run running, and failed at the same step | **Lane defect, and the worst kind**: the two Tomcat logs showed the *legacy* application serving two of the four sessions of the "modern" capture. The `user-allowlisted` preset allowlists only `GardenAdmin`; the second acting identity `GardenUser` fell back to legacy. The capture reported `served=modern` truthfully, because it sampled one session out of four |
| [33598342557](https://github.com/samqbush/adempiere2/actions/runs/33598342557) | 33m; legacy lane green end to end; modern capture A completed steps 0-9 for the third run running, and failed at the same step | The search key **was** committed - the new guard did not fire - so the wrong *field* was being filled: the dialog locator took `.first()` of a union that also matches the window behind it, and the Business Partner window has its own field captioned "Search Key" |
| [33591572610](https://github.com/samqbush/adempiere2/actions/runs/33591572610) | 33m; legacy lane green end to end; modern capture A again completed steps 0-9 and again failed positioning the deactivating session | The awaited `/zkau` response did not identify the request being waited for, so ZK 10's own in-flight traffic could satisfy it; an uncommitted search key then failed **silently** as an unfiltered query |
| [33589524866](https://github.com/samqbush/adempiere2/actions/runs/33589524866) | 33m; **the modern runtime completed steps 0-9**, including `create` (7 rows), `update`, the concurrency pair and `duplicate-submit`; failed positioning the deactivating session | Driver settlement: the Find dialog's Ok was clicked before the search key's own blur round trip had landed, so the query ran unfiltered |

### Run 33679068657 - the Save control was disabled at the instant it was clicked

The three click-time readings separated the two pages on the first attempt:

| | primary (saved) | deactivate (silent) |
|---|---|---|
| `resolvesTo` / `isSaveWidget` | `zk_comp_3222` / `true` | `zk_comp_3225` / `true` |
| `atClickWidgetDisabled` | **`false`** | **`true`** |
| `atClickClickLsns` | `0` | `0` |
| `postHandlerQueued` | **`2`** | **`1`** |

`Toolbarbutton.doClick_` is wrapped in `if (!this._disabled)`. The Save control
was disabled, to ZK, at the moment the click arrived, so ZK received the click
and discarded it without a listener running, without an error and without an AU
request. Widget resolution succeeded on both, and no client-side `onClick`
listener was involved on either. `postHandlerQueued` is consistent with the
reading - 2 on the page that saved against 1 on the page that did not - but it
is an absolute queue depth read once, post-click, on two different desktops,
with no pre-click baseline on either, so it is corroboration and not a measured
delta. `atClickWidgetDisabled` carries the finding on its own.

This is why nine runs of probes came back healthy. Every one of them sampled the
control from the failure dump, roughly thirty seconds after the click and after
the settle poll had expired - by which time the toolbar had caught up and the
button read `disabled=false`. The defect only exists in the instant the earlier
instrumentation could not see, and the `sclass` asymmetry noted in the previous
run - three disable-enable cycles against one - was the first visible trace of
it.

**This is a driver race, not a modern product defect, and the distinction is
evidenced rather than assumed.** The deactivating desktop's own server log ends
with `AbstractADWindowPanel.dataStatusChanged: *1/1` at `20:58:54.004`, and
nothing after it until the 20:59:28 shutdown. The `*` is
`DataStatusEvent.getMessage`'s changed marker, so the server had recognised the
record as dirty - the condition that enables Save - within half a second of the
checkbox click, and it emitted that status. The product's state machine reached
the correct state, and sent it; what did not happen in time was the client
applying it. What raced it is on the driver's side: `clearActive` and
`clickAwaitingServer` await *any* `/zkau` response rather than their own, and ZK
CE 10 polls roughly once per 1.5s, so an ambient 18-byte poll can satisfy that
wait before the checkbox's own response has been applied to the toolbar. That is
the same shape of defect this document already records for run 33591572610, on a
runtime whose ambient traffic is far heavier than ZK 3.6's - which is why the
legacy lane, whose `clearActive` is byte-identical and whose `awaitSaveOutcome`
has no enablement wait at all, simply wins the race and never observed it.

**Why the driver could not have caught this with an actionability check.**
Playwright's checks are thorough but they are checks on the DOM: visible,
stable, receives events, enabled. ZK CE 10 does render `disabled="disabled"` on
the anchor, and ADempiere additionally adds a `disableFilter` style class - but
Playwright treats the `disabled` attribute as meaningful only on
BUTTON/INPUT/SELECT/TEXTAREA/OPTION/OPTGROUP, not on an `<a>`, and ZK sets no
`aria-disabled`. The control is therefore visible, stable, hit-testable and
`isEnabled()`-true while being, to ZK, inert.

So `awaitSaveOutcome` now waits for ZK's own view of the control before
clicking: it polls `zk.Widget.$(el, {exact: true})._disabled` - the state
`doClick_` actually tests, and the source the attribute and the style class are
both derived from - until the widget is enabled, within the same settle budget.
`replaySave` takes the same wait, hoisted above its `waitForRequest` so that
toolbar settlement is not charged against the transport budget.

This is settlement, and settlement is the dialect's business - locate, operate,
await. Waiting on the state actually needed, rather than on a transport event
that may belong to someone else, is strictly more correct than what it replaces.
It normalizes no behavioural difference: if the runtime never enables Save, the
wait expires and the step reports `save-never-enabled`, which is deliberately
**not** in the scored outcome vocabulary, so it fails loudly as itself instead
of being retried into silence or dressed up as a driver timeout.

**It also removes a false-green exposure on the headline fact.** The frozen
answer for the conflicting save is `rejected-save-still-enabled`, and that is
also what `pollSaveOutcome` returns when it exhausts its budget having seen no
settlement. A Save that was ZK-disabled at click time in `attemptSave` therefore
produced the *expected* value for the single most important fact in the oracle,
for the wrong reason. It now produces `save-never-enabled`, which is red. The
plan anticipated exactly this class of problem - "settlement is the hardest
driver problem", and "a wrong wait produces a flaky capture rather than a red
one" - and this is the first confirmed instance.

### Run 33673776776 - ZK receives the click and still sends nothing

Both hypotheses were refuted, and the refutation is the most useful result the
lane has produced. Both pages, again identical apart from ids:

```
... atClickDragging=false atClickIgnoreClick=false
    disabledRequest=false queuedAuRequests=0 ajaxReq=false pendingReqInf=false
    draggingNow=false docWitness=reachedDocument fromSave=true
```

`zk.dragging` was never set, so no abandoned drag is discarding clicks. And
`docWitness=reachedDocument fromSave=true` on the failing page is the decisive
line: nothing stops the click in flight. It propagates from the Save anchor all
the way to `document`, which is where ZK's one delegated click listener lives,
and that listener's guard is false.

So ZK **receives** the click on the failing page and sends nothing. Everything
outside ZK is now excluded - the driver, the DOM, the transport, the request
queue and the server all behave identically on the page that works and the page
that does not.

What that listener does next is resolve a widget from the event target and hand
it to `_doEvt`:

```js
if (evt.which == 1)
  _doEvt(new zk.Event(Widget.$(evt, {child: true}), 'onClick', ...));
```

and `_doEvt` begins `var wgt = wevt.target; if (wgt && !wgt.$weave) { ... }`. If
that resolution yielded `undefined`, `_doEvt` would return having done nothing,
with no console error and no AU request.

**That reading was drafted and withdrawn before it ran.** It does not survive
contact with `Widget.$`, and it contradicted the retraction recorded for the
previous run, forty lines below, which had already given the reason: the lookup
walks up from the event target and returns on the **first** `_binds` hit, with no
node test. Walking from the `<img>`, the `-cnt` span's sub-id branch does apply
an `$n()`/`isAncestor` test - but failing it does not end the walk, it falls
through to the next ancestor, which is `<a id="zk_comp_3225">`, where
`_binds[id]` hits and returns unconditionally. Resolution cannot fail for this
control, and the same run's `saveControl` field proves `_binds` holds the entry.
The resolution fields are still recorded, but as confirmation, not as the
hypothesis.

**What is genuinely unobserved is the inside of ZK's handler, at click time.**
Two of its early returns are silent, and both fit the signature exactly:

- `Toolbarbutton.doClick_` begins `if (!this._disabled)`. A disabled widget
  swallows the click and returns.
- `fireX` runs client-side `onClick` listeners first and returns if one calls
  `evt.stop()`.

Neither has been read *when it matters*. The failure dump samples the widget
about thirty seconds after the click, once the settle poll has expired, and
`disabled=false` at that moment says nothing about `_disabled` during dispatch.
That gap is not hypothetical here: the two Save controls are already known to
differ in precisely this respect, three disable-enable cycles on the primary
against one on the deactivate page. A toolbar that was still disabled when the
driver clicked, and re-enabled before the dump, would produce every reading
observed so far.

So the witness now reads `_disabled` and the client `onClick` listener count from
the resolved widget at click time, and the `document` listener - which is
registered non-capture and later than ZK's, so it runs after ZK's whole handler
for the same click - additionally reports the desktop's AU queue depth. A
non-zero depth there means `fireX` did send and the loss is downstream; zero
means it never sent. Between them, the three readings cover every remaining link
in the chain.

### Run 33669560347 - the click arrives, and a retraction

The three-valued witness returned the one answer that was not on the list. Both
pages, verbatim apart from their component ids:

```
witness=domClick target=IMG phase=1 trusted=true atClickOpenComboPopups=0
        atClickMasks=0 atClickActiveElement=zk_comp_322X
        disabledRequest=false queuedAuRequests=0 ajaxReq=false pendingReqInf=false
```

A real DOM click reached the Save anchor on **both** pages. `trusted=true` is
what establishes that; `target=IMG phase=1` does not corroborate it, and is
recorded only as context - the phase is a consequence of registering the witness
capture-phase on the anchor while the click lands on the inner image, and a
synthetic event would report the same. No popup was open, no mask was up, and
focus was on the button itself. ZK's send state was healthy on both:
`disabledRequest=false` is meaningful even read late, because that flag is
cleared in exactly one place, the `beforeunload` path, so had it ever been set
it would still have been set.

The primary page then sent its `onClick`. The `deactivate` page, identical on
every one of these axes, sent nothing.

That refutes the third path. It does **not** mean the two pages are
indistinguishable, and the same dump shows they are not. The Save control's
rendered class differs beyond its id: `toolbar-button` followed by four spaces
on the primary, two on the deactivate page. That is a state counter, not
cosmetics. `ToolBarButton.setDisabled`
(`zkwebui/WEB-INF/src/org/adempiere/webui/component/ToolBarButton.java:38-45`)
appends `disableFilter` on disable and, on enable, removes the word while
leaving its separator space behind, so each disable-enable cycle adds one space.
The primary's Save button has been through three cycles, the deactivate page's
through one. The two toolbars did not follow the same state sequence, and no run
has probed the toolbar enable/disable path.

**A retraction.** The first reading of this run was that ZK's DOM handler must
not be attached to the node the click arrived on. That is a category error and
is withdrawn. ZK CE 10 does not attach per-node click handlers: `bind_`
registers drag, flex and touch molds only, and clicks are dispatched by
delegation from a single `document` listener installed at `zk/mount.ts:842`.
That listener is always attached, and its widget lookup walks up from
`evt.target` and returns on the first `_binds` hit without comparing `$n()`, so
even a widget caching a stale node would still receive the click. A
`widgetNodeIsClicked` probe could not have separated the two pages, and was
dropped before it ran rather than after.

**What replaces it.** Two things can silently swallow a delegated click, and
neither has been observed:

The first is `zk.dragging`. The `document` click listener's first statement is
`if (zk.Draggable.ignoreClick()) return;`, and `ignoreClick()` is `!!zk.dragging`.
`zk.dragging` is set when a drag begins and cleared in exactly one place - a
timeout inside `Draggable._endDrag`. A drag that starts and never completes
therefore leaves it set permanently, and from that moment every click on that
page is dropped with no console error, no AU request, and every widget and `zAu`
property still reading healthy. That is precisely the shape of this defect:
page-scoped, total, and invisible to every axis probed so far. It is also a
plausible thing for the `deactivate` step to have caused, since it is the step
that clicks a checkbox rather than typing into a field.

The second is a `stopPropagation` between the anchor and `document`. The witness
is capture-phase on the target; ZK's listener is bubble-phase on `document`; the
whole span between them is unobserved, and a stop there would fit every reading
taken so far just as well.

So the read-back now reports `zk.dragging` both at click time and afterwards -
late is meaningful for the same reason it is for `disabledRequest` - and a
second passive listener on `document` records whether the click ever arrived
there. If the document witness fires on the primary and not on the deactivate
page, the click is being stopped in flight; if `dragging` is true, the click is
being discarded on arrival.

### Run 33664809767 - the click never reaches the wire

The per-page record answered the question it was built for, and the two pages
answered it differently:

```
saveRequests  zk_comp_3222 auPostsFromThisPage=22 requestsNamingSaveControl=1
              || dtid=...&cmd_0=onBlur&uuid_0=Field_Name2_220_1_3359
                 &cmd_1=onClick&uuid_1=zk_comp_3222&data_1={"pageX":568,...}
saveRequests  zk_comp_3225 auPostsFromThisPage=22 requestsNamingSaveControl=0
```

The primary page's conflicting save **did** put its `onClick` on the wire,
bundled behind the `onBlur` of the field the driver had just left - which is
what a real ZK save submission looks like, and which independently confirms the
resolution above that this save reached the product and was refused on its
merits.

The `deactivate` page sent **nothing**. Twenty-two AU posts left that page
during the same window and not one carried the Save control's component id.
What those twenty-two were is not recorded and cannot be recovered - the
listener retains only bodies naming the Save control, and an 18-byte answer
looks the same for a poll and for a command that changed no UI - so they are
left unclassified. The claim that matters does not need them: no request named
the Save control, so the click is lost *before* ZK sends. No ZK log level is
needed, and the server is exonerated on this step - there was never a request
for it to answer.

That sits oddly beside the previous run's refutations, because every property
*so far probed* is healthy on this exact control: bound to its own anchor,
enabled on both widget and DOM, `desktop=yes`, `inServer=true`, ASAP-listening
for `onClick`, `zk` and `zAu` live, no console error. But "so far probed" is the
operative qualifier, and treating the search space as two-valued - click lost,
or handler declined - would have been wrong. There is a third path, and it is
silent by construction:

`zAu.sendNow` returns immediately when `zAu.disabledRequest` is set, writing
nothing anywhere. An obsolete desktop, an alert, or a redirect with no target
all set that flag, and only `beforeunload` clears it, so once set every later
request queues forever. Independently, `sendNow` parks the event and returns
when `zAu.ajaxReq` or `zAu.pendingReqInf` is truthy, draining only when the
in-flight request completes. In both cases the DOM click arrived, `doClick_`
fired and `fireX` ran correctly - and nothing left the browser. The existing
`zkClientState` probe could not see any of this: it reads `zk.processing`, which
is not `zAu.processing()`, and never reads the queue at all.

So the search space is three-valued: the click never landed, it landed and ZK's
handler declined to act, or it landed and fired and `zAu` never put it on the
wire.

`awaitSaveOutcome` now witnesses all three. Before the click it attaches a
capture-phase listener to the Save anchor recording whether a click event
arrives, with its target, phase and `isTrusted`; and afterwards it reads
`zAu.disabledRequest`, the desktop's queued AU request count, `zAu.ajaxReq` and
`zAu.pendingReqInf`. A queued request is the direct signature of the third path,
so one run now separates all three outcomes instead of two.

`ev.defaultPrevented` is recorded nowhere, deliberately: in a capture-phase
listener on the target it cannot yet reflect ZK's own bubble-phase handling, so
it would read `false` whether or not anything was wrong.

The popup and mask counters are sampled **inside** the listener rather than
afterwards, because they are only meaningful as click-time facts - read at the
end of a 30-second poll they describe a different moment. They are recorded as
context, not as a hypothesis: the `z-combobox-popup` elements in this run's dump
are equally present on the page whose click *did* reach the wire, and Playwright
hit-tests before clicking, so an overlay covering the anchor would have thrown
rather than silently swallowed. Anchor-level interception is already excluded.

The witness is passive. It adds one listener that appends to an array, removing
its predecessor first so that repeated saves against the same page cannot report
one click as several; it does not stop propagation, prevent the default,
register anything with ZK or replace any product function. Wrapping `zAu.send`
would have answered the same question by altering the thing being measured,
which in a parity lane is not a trade worth making.

### Run 33658582428 - every client-side explanation is refuted

The four mechanisms the previous run left open were named precisely so that one
run could close them. It closed all four, and none of them is the cause.

```
saveControl    uuid=zk_comp_3225 matchesId=true class=zul.wgt.Toolbarbutton
               disabled=false desktop=yes inServer=true asapsClick=true
               listensClickAsap=true listensClickAny=true autodisable=undefined
saveButton     a#zk_comp_3225[toolbar-button  z-toolbarbutton]
               attrDisabled=false aria=null
zkClientState  zk=function zAu=object processing=false mounting=false
               errorBoxes=0
```

Mechanism 1 is out: `zk` and `zAu` are live, neither flag is stuck, and
`browser-errors-at-failure.tsv` - preserved for the first time by the previous
commit, and the reason this is decidable at all - contains **no** JavaScript
error. Its only six entries are the legacy-theme
`/webui/theme/default/css/themesaf.css.dsp` 404s. Mechanism 2 is out:
`asapsClick=true` and both `isListen` forms agree the server is listening for
`onClick` as ASAP. Mechanism 3 is out: the widget and the DOM agree the control
is enabled. Mechanism 4 is out: `inServer=true`.

`matchesId=true` rules out the one way this reading could have been an artefact
of the probe - the widget reported is the anchor's own, not an ancestor's.

The failure is otherwise identical to the previous run: nine effect documents,
ten rendezvous acknowledgements, and `GridTable.dataSave` logged for four saves
and never for this one.

**The conflicting save is a genuine product refusal, and its missing `dataSave`
line is expected.** Four `dataSave` lines is fewer than the number of save
submissions that precede `deactivate`, and the gap is worth pinning down
precisely, because `rejected-save-still-enabled` is also the frozen legacy
answer for the conflicting save - so a modern save that silently did nothing
would score as parity on the headline concurrency fact for the wrong reason.

It did not do nothing. The refusal is `CurrentRecordModified`
(`AD_MESSAGE_ID=53059`, `db/ddlutils/data/AD_MESSAGE.xml:1347`), whose text is
exactly the observed `Current record was changed by another user, please
ReQuery`. It has one producer in the tree:
`GridTab.hasChangedCurrentTabAndParents()` at
`base/src/org/compiere/model/GridTab.java:1034-1035`. `GridTab.dataSave` calls
it at line 992, **before** `m_mTable.dataSave` at line 999, and on a manual
toolbar save returns `false` immediately (lines 995-996). `GridTable.dataSave`
is therefore never entered, and `log.info("Row=")` at `GridTable.java:1468`
never runs. The absence of a fifth line is the *signature* of this refusal, not
an anomaly in it.

The log line's odd shape corroborates the path rather than undermining it.
`log.saveError("CurrentRecordModified", msg, false)` stores a `ValueNamePair`
whose name is the already-resolved text (`CLogger.java:162`);
`AbstractADWindowPanel.onSave` then reports it through
`Msg.getMsg(ctx, CLogger.retrieveErrorString(null))`
(`AbstractADWindowPanel.java:1702-1707`), feeding resolved text back in as if it
were a key - which is why the log reads `Msg.getMsg: NOT found: Current record
was changed by another user, please ReQuery`. Had the refusal come from
`GridTable`'s `SaveErrorDataChanged` instead, the text would have been `Could
not save changes - data was changed after query.` (`AD_MESSAGE.xml:723`), which
appears nowhere in this run.

So the modern runtime refuses the conflicting save for the correct reason, and
`deactivate` remains the single open defect. Noted because the two are easy to
conflate: `GridTab.dataSave` and `GridTable.dataSave` are different methods, and
only the latter logs.

**What the refutation actually leaves.** A live, enabled, bound, ASAP-listening
Toolbarbutton that is clicked must put an `onClick` on the wire. Either it did
not - and no property of the widget explains why - or it did, and the product
answered by doing nothing. The access log cannot separate those, because ZK
answers a command that changes no UI with the same 18 bytes as an idle poll,
and response size is all the access log records.

That is one observation away from being decided, and the observation is of the
request rather than the page. `awaitSaveOutcome` now registers a request
listener around the click and records, for the failure dump alone, how many
`/zkau` POSTs this page sent and how many carried the Save control's own
component id, with a bounded prefix of each such body. `requestsNamingSaveControl=0`
means the click never reached the wire, which is decisive on its own. A
non-zero count means it reached the server, but it does **not** by itself
convict the product: `RequestQueueImpl` can discard an AU request before any
ADempiere code runs. Note that raising `org.zkoss.zk.ui.impl` to `FINE` would
**not** cover that branch. Its `"Ignore request: {}"` line sits in a
`ComponentNotFoundException` handler; `isObsolete` - which drops a request whose
component belongs to another desktop - contains no logging at any level, as its
bytecode in `/tmp/zkall` shows. This run's `desktop=yes` already makes
`isObsolete` implausible, so the non-zero branch would need a different
observation to be designed rather than a log level to be raised.

The record is keyed by `Page`, not held in a single field, because the failing
path dumps two pages: the deactivating session first, then the primary one
after the rethrow. A single field would have written the deactivate session's
record into `failure-primary.tsv` - the two dumps have different Save
components, `zk_comp_3225` and `zk_comp_3222` - and would have destroyed the
one measurement that bears on the unreconciled observation above. Keyed, the
primary reports its own last save, which is the conflicting save. A page that
attempted no save reports `no-save-attempted-on-this-page` rather than
borrowing another page's answer.

It is a listener rather than a `waitForRequest` on purpose: a wait that expires
throws a Playwright timeout and would replace the product's own outcome with a
driver error, which is precisely the substitution this lane exists to prevent.
The record is written in a `finally`, so a click or poll that throws still
reports what reached the wire. Nothing here decides an outcome; `save` still
hard-fails on any non-accepted result.

### Run 33653427475 - the layout fix holds, and the save goes silent

The geometry probes answered the previous run's open question for the control
that mattered. The Active checkbox now has a real box, and
`document.elementFromPoint` at its own centre returns the checkbox itself:

```
unqField_1_0_C_BPartner_IsActive3374-real[619,613,20x20]@629,623
    ->unqField_1_0_C_BPartner_IsActive3374-real.
unqField_1_0_C_BPartner_AD_Client_ID3313[611,249,230x34]@725,266
    ->unqField_1_0_C_BPartner_AD_Client_ID3313-real.z-combobox-input
```

The click landed and the dump records the checkbox as `z-checkbox-off`, where
the previous run could not reach it at all. That is what the dropped vflex is
evidenced to have fixed, and the claim is deliberately no wider: the probe
samples the first 40 matches of a tab reporting 169 editors, 22 of the 38
sampled entries hit-test to `null` because they sit outside the viewport, and
`IsEmployee3336` still reports a `0x0` box. Those are unresolved observations
about editors this flow does not touch, not evidence that the tab is now
uniformly well laid out.

The capture then failed one action later, saving that deactivation:

```
the record was not saved: rejected-save-still-enabled
```

**What the runtime's own logs establish.** The deactivating session queried its
record at 16:45:13.964, and at 16:45:14.464 the modern runtime logged
`AbstractADWindowPanel.dataStatusChanged: *1/1`. The leading `*` is the dirty
marker, so the checkbox change reached the server and the record was pending.
After that the modern Tomcat logged **nothing** until it was stopped at
16:45:49, and `GridTable.dataSave` - which the same log records four times for
the earlier saves at 16:43:40, 16:43:47, 16:44:02 and 16:44:55 - was never
invoked. The access log agrees: the last substantive `POST /webui/zkau` is the
2237-byte checkbox response at 16:45:14, and every request after it is an empty
18-byte poll.

So Playwright delivered a click to a control it found actionable, and the ZK
client sent no event-carrying request. It did keep polling: the empty 18-byte
POSTs continue every second or two until shutdown, which is weak evidence
against a wholly dead client - though the access log cannot attribute a poll to
a particular desktop, and three browser contexts were open. Playwright throws on
an intercepted click, so this is not the previous defect returning; the event
was lost after the click, on the client.

**What is *not* established, and why the run could not decide it.** The failure
dump's `saveButton` probe reported `disabled=false`, and that reading was
worthless: it evaluated `!!e.disabled` against an `<a>` element, where
`disabled` is not a property and the expression is unconditionally `false`. The
probe had never reported the control's state. (The scored path was never
affected - `saveSettled` reads `hasAttribute('disabled')`, matching how ZK CE 10
actually renders a disabled `Toolbarbutton`, which `Toolbarbutton.domAttrs_`
and `setDisabled` both confirm.)

Reading the ZK CE 10 client sources leaves at least these four mechanisms open.
They are not claimed to be exhaustive:

1. **The ZK client is dead.** A page whose JavaScript has failed still renders,
   still hit-tests, and still reports controls as enabled, while silently
   sending nothing.
2. **No server-side `onClick` listener is bound.** `Toolbarbutton.doClick_`
   calls `fireX(evt)`, and ZK transmits an event only when the server is
   listening for it. A widget that lost its listener fires locally and sends
   nothing.
3. **The widget and the DOM disagree about `disabled`.** `doClick_` returns
   early when `_disabled` is set, and `setDisabled` only touches the DOM
   attribute when the value actually changes (or `force` is passed) *and*
   `this.desktop` is truthy. A state change made while the widget is detached
   therefore leaves `_disabled` set on a control the DOM still presents as
   enabled - which is precisely the contradiction observed.
4. **The widget is no longer `inServer`.** `fireX` sends only when
   `toServer || (this.inServer && this.desktop)`, so a widget detached from the
   server-side component tree is independently sufficient for total silence.

All four produce the identical symptom, and no artifact from this run
distinguishes them, so no fix is attempted here. Three changes make the next
run decide between them instead:

- `browser-errors.tsv` and `network-requests.tsv` were written **only on the
  success path**, so the one run in which a page-level JavaScript error is a
  leading hypothesis discarded the console log that would have proved or
  refuted it. They are now also written as `*-at-failure.tsv` copies from the
  same lists. The scored files are unchanged.
- The `saveButton` probe now reports `hasAttribute('disabled')` and
  `aria-disabled` instead of a property that cannot exist.
- A `saveControl` probe records the control's markup and, if ZK is alive, its
  bound widget's `uuid`, `className`, `_disabled`, `desktop`, `inServer`,
  `_asaps['onClick']`, both forms of `isListen('onClick')` and `_autodisable`.
  It looks the widget up with `{exact: true}`, because `zk.Widget.$` otherwise
  walks up the DOM and would report an *ancestor* widget's state for a control
  whose own widget is gone; `matchesId` records the check. A `zkClientState`
  probe records `zk`, `zAu`, `zk.processing` and `zk.mounting`.

  Both forms of `isListen` are recorded because neither alone decides
  mechanism 2: with no options it also returns true for a purely client-side
  listener, and false for a deferrable server listener that `fireX` still
  sends. Mechanism 3 shows as `_disabled=true` against `attrDisabled=false`,
  and mechanism 4 as `inServer=false`. Mechanism 1 is evidenced by the console
  log and by a stuck `processing`/`mounting` flag - *not* by a missing `zk`,
  which a client that died mid-session would still define.

All of it is observation. The dialect gains no new way to operate a control,
and `save` still hard-fails on any non-accepted outcome, so a lost click stays
a red lane.

### Run 33647155695 - the fix lands, and the next defect is layout

Restoring the event thread carried the capture four steps past every
root-caused run before it - 33631958003, 33636622993 and 33640642306 all died
positioning the second editor. It did **not** reach further than
33589524866 or 33591572610, whose artifacts carry the same ten rendezvous
acknowledgements and the same nine effect documents; those runs died of a
driver settlement defect rather than a runtime one. What is new here is that
the steps in between are now trustworthy: the modern runtime positions the
second editor on the right record through a modal that actually blocked,
performs the conflicting save, and **refuses it**. The primary editor's status
bar reads `*2/18` with *Current record was changed by another user, please
ReQuery*. That is the headline concurrency fact of the frozen oracle, observed
on ZK CE 10 for the first time.

The run then failed at step 10, `deactivate`, clicking the Active checkbox:

```
locator resolved to <input type="checkbox" checked="checked"
    id="unqField_1_0_C_BPartner_IsActive3374-real"/>
  <div role="none" class="z-center-body" id="zk_comp_3298-cave">…</div>
    intercepts pointer events
  <div role="grid" id="zk_comp_3301" class="z-grid z-flex-item"
    data-adempiere-legacy-height="100%">…</div> intercepts pointer events
```

The element is present, checked, visible, enabled and stable, and the DOM dump
carries every editor of the tab. But the failure screenshot shows the field area
painting **blank**: scanning it across the field region, the only non-chrome
pixels are a 3px blue left border about two pixels tall. That border is set
nowhere but `ADTabPanel.activate(true)`, so the collapsed box is this tab's own
field grid, and the vflex plainly did not size it.

**What is established, and what is not.** `ADTabPanel.initComponents` built that
grid with `setHeight("100%")` followed by `ZkCompat.setVflex(grid, true)`, which
clears the height, because ZK CE 10 refuses the pair that ZK 3.6 accepted. The
grid is therefore vflex-sized and two pixels tall - that much the artifact
proves. The mechanism does **not** reduce to the `position: absolute` in the
same method: `activate(true)` runs for tab 0 at window open and replaces the
style wholesale, so that declaration never reaches the rendered element, and the
rendered class list `z-grid z-flex-item` shows ZK CE 10 did process the box
through its CSS-flex path rather than skipping it as positioned. An earlier
draft of this note asserted the absolute-positioning mechanism; it is retracted.

The change made in response is narrower than a root-cause fix and is warranted
on its own terms. Keeping the vflex is not merely ineffective here, it is
unreachable: `activate(false)` calls `setHeight("100%")` on this same grid on
every tab switch, and ZK CE 10's `HtmlBasedComponent.setHeight` throws
`UiException("Not allowed to set vflex and height at the same time except
vflex=\"min\"")` whenever a vflex is set - verified in the 10.3.0.1-jakarta
bytecode. So the vflex form aborts the event that switches away from a tab. The
percentage height is what ZK CE 10 leaves available once the vflex is refused.
Whether it is *sufficient* for this grid is an open question that the next run
decides.

The `data-adempiere-legacy-height="100%"` attribute in the interception message
is the Phase 5d shim recording the height it removed.

To make that next run decisive rather than another inference, the dialect gained
geometry to its failure dump. Playwright reports only the class of whatever it
hit, which names a container without saying whether the target was collapsed,
positioned outside its scroll parent, or genuinely covered. The dump now records
each editor's client rect and what `document.elementFromPoint` returns at that
editor's own centre; the full ancestor chain from an editor to `<body>`,
including the grid's internal mesh, with each box's computed position, display,
height, flex-grow, flex-basis and overflow; and the same computed state for the
candidate containers. The flex state is recorded because whether ZK CE 10
treated a given box as a flex item is exactly what is in dispute. It is pure
observation: the dialect gains no new way to *operate* a control, only to
describe one, and every probe is individually guarded so it cannot mask the
failure it is describing.

### Run 33640642306 - the modal that never blocked

The diagnostic answered the question it was added to answer, and answered it in
the operator's favour twice over. `cmd_ok_Simple` logged

```
14:46:13.852 cmd_ok_Simple: selection editor Value uuid=Field_Value_220_1_2323 value=P5G1A-0001
```

so the criterion reached the server, survived the round trip, was applied to the
right widget, and was still readable at the moment the dialog builds its query.
Nothing was lost.

The finding is in the timestamps. The unfiltered query is logged **before** the
criterion is read:

```
14:46:12.793 getQuery: Query=MQuery[C_BPartner,Restrictions=0]
14:46:13.852 cmd_ok_Simple: selection editor Value ... value=P5G1A-0001
```

The window does not read a criterion that was discarded. It reads the query
*before the operator has entered one*, and then never reads it again.

`AbstractADWindowPanel.initialQuery` constructs the dialog and synchronously
reads its answer:

```java
FindWindow find = new FindWindow(...);
if (!find.isCancel())
    query = find.getQuery();
```

That is only correct if the constructor blocks. It blocks because
`AbstractDesktop.showModal` calls `Window.doModal()`, which blocks its caller
**only** by suspending the ZK event processing thread until the dialog is
disposed. ZK 3.6 enables that thread by default. ZK 5 and every later release,
including CE 10, disable it by default.

ZK 10 does not refuse the call. `Window.doModal()` opens by branching on
`isEventThreadEnabled()`, and on the disabled branch it calls
`setNonModalMode(MODAL_EVENT_THREAD_DISABLED)` and returns - no exception is
constructed and none is thrown:

```
0: isEventThreadEnabled(true)
5: ifne 21          // enabled -> the suspending path
8: checkOverlappable(-100)
14: setNonModalMode(-100)
20: return
```

So the dialog degrades to non-modal in silence. Construction returned
immediately, `initialQuery` read an untouched `FindWindow`, and the window
queried the whole table. The operator's later Ok set `m_query` on a dialog no
longer connected to anything.

This mechanism is a correction. The first reading of this run attributed the
failure to `showModal` swallowing a `SuspendNotAllowedException` into an empty
`catch`. Disassembling `org.zkoss.zul.Window` refutes that: on the disabled
branch nothing is thrown, so that `catch` was never entered and never hid
anything. The symptom, the culprit and the fix are unchanged; only the
explanation was wrong, and a wrong explanation would have justified a detector
that could not fire.

This is a configuration divergence that a file-by-file descriptor migration
could not have caught, because the setting was never in the file: it was a ZK
*default* that changed between 3.6 and 5.

A second, narrower fact confirms the application was written for the event
thread. `SessionContextListener` implements `EventThreadResume`, whose
`beforeResume`/`afterResume`/`abortResume` hooks restore the ADempiere thread
context across a suspend; only `EventProcessingThreadImpl` suspends and resumes,
so under the ZK 10 default those three methods are dead code. Its
`EventThreadInit` and `EventThreadCleanup` implementations are **not** evidence
here: `UiEngineImpl.processEvent` invokes both on the servlet thread even when
the event thread is disabled, so they were already live and the claim that all
of these hooks were dead would have been false.

Four changes close it:

- `zkwebui/src/phase5d/webapp/WEB-INF/zk.xml` sets
  `<disable-event-thread>false</disable-event-thread>`, restoring the ZK 3.6
  default. ZK CE 10 still parses the element and still ships
  `EventProcessingThreadImpl`, so this is supported configuration, not a
  workaround. It is recorded as the fourth deliberate difference in that
  descriptor's own header.
- `AbstractDesktop.showModal` now checks `isEventThreadEnabled()` before calling
  `doModal()` and warns when it is off, because the degradation is otherwise
  silent and affects *every* caller that constructs a modal and reads its result
  synchronously. The pre-existing empty `catch` also gained a log, but that is
  housekeeping, not the detector: it covers only the paths that genuinely throw
  - no desktop, or a modal that cannot be entered - on which ZK restores the
  window's prior mode and visibility.
- `zkwebui/build.gradle`'s `webui-modern.war` content assertions now require
  `<disable-event-thread>false</disable-event-thread>` in the packaged
  descriptor. A runtime warning cannot protect a setting whose loss is silent
  and whose only symptom is a wrong answer, so the setting is asserted where it
  is packaged, alongside the existing polling-server-push and no-commercial-ZK
  assertions.
- The `cmd_ok_Simple` criterion diagnostic drops to `FINE`. `getQuery` already
  logs the resulting query at `INFO`, so the default level still distinguishes
  `Restrictions=0` from `Restrictions=1` without writing operators' search terms
  to `catalina.out`.

The fix is deliberately **not** in the dialect. A dialect may locate, operate
and await; it may not compensate for a runtime behaviour the application
depends on. Nor is it a change to the frozen legacy artifact. The legacy lane
*does* compile `zkwebui/WEB-INF/src` - but from the Phase 5b commit in an
isolated detached worktree, not from the working tree - which is why the
diagnostic produced no legacy lines and why no product-source change in this
increment can move the oracle.

**Residual risk.** ZK discourages event threads on scalability grounds and has
deprecated them. Restoring the default is the correct move for a parity
increment - it makes the modern runtime behave as the frozen answer expects -
but converting ADempiere's synchronous modals to an event-driven form remains
open for a later phase, and is recorded as such rather than presented as done.

### Run 33636622993 - the driver is exonerated

The new assertion did not fire. The `onChange` carrying `P5G1A-0001` was
reported for the same widget uuid as the input the driver filled, so the browser
did everything correctly: it located the dialog's own Search Key criterion,
typed the value, committed it, and the server accepted the commit for that
widget.

That closes the driver-defect branch. Four browser-side properties are now
proven together - the right dialog, the right field, an `onChange` rather than
any other command, and the right target - and the modern query is still
unfiltered. Whatever discards the criterion is on the server, between ZK
applying the value to the input and `cmd_ok_Simple` reading it back with
`wed.getValue()`.

Nothing further is observable from the browser, so the next run stops trying.
`cmd_ok_Simple` now logs each selection editor by column, by the same widget
uuid the browser addresses, and by value, immediately before it reads them.
**That expectation was wrong, and run 33640642306 disproved it**: the legacy war
is built from the Phase 5b source commit in an isolated worktree
(`gradle/phase5/legacy-web-artifact.gradle`), so a working-tree edit to
`zkwebui/WEB-INF/src` never reaches it and the diagnostic produced modern lines
only. That the legacy oracle cannot be moved by a product-source edit is the
governance property working as intended; it is recorded here because the
expectation, not the property, is what this run corrected.

### Run 33631958003 - the first valid modern capture, and a real divergence

The cohort fix worked and is visible in the evidence rather than argued:
`runtime-identification.tsv` records `served.second-editor modern`, the identity
that had been silently falling back to legacy. The legacy Tomcat's own log ends
at 13:11:20 - the legacy regression - and carries nothing during the modern
capture at 13:20. This is the first capture in the increment whose observations
are about the program it claims.

The failure moved one session *earlier*, to the second editor, which is exactly
what the diagnosis predicted: that session had been passing because it was
running ZK 3.6.

What it exposes is a genuine modern divergence, and the two logs now state it
without inference. For the same driver actions on the same seeded database:

| | Find dialog query |
|---|---|
| legacy ZK 3.6 | `(UPPER(C_BPartner.Value) LIKE 'P5G1A-0001%')` |
| modern ZK CE 10 | `MQuery[C_BPartner,Restrictions=0]` |

The unkeyed window open matches on both runtimes, so the divergence is specific
to a criterion being applied, not to the dialog as such.

The sharpened commit guard did not fire, so an accepted `/zkau` carried
`cmd=onChange` with the key: the server was told. That matters because of how
the dialog actually reads its criterion. `FindWindow` does not listen for the
change - its `hasValue` family sits inside a block comment
(`FindWindow.java:631-641`), so the visible Search Key is a selection-column
`WEditor` registering only `ON_OK` - and `cmd_ok_Simple` reads the value back at
Ok time with `wed.getValue()`. The `onChange` is what puts the text into the
server-side input that read returns, so an uncommitted one leaves the query
unfiltered and nothing else to show for it. One link is still unproven,
and it is the link that decides whether this is a product defect or a driver
one - **which widget** the change was reported for. The guard ignored `uuid_N`,
and an `onChange` carrying the right text for the wrong component is stored
somewhere `FindWindow` never reads, leaving the lookup unfiltered with every
earlier check satisfied. The guard now returns that uuid and asserts it is the
field the driver filled, so the next run names one cause or the other instead of
leaving them indistinguishable.

### Run 33626582558 - half the capture was the legacy application

The fourth identical failure refuted its own repair. The new diagnostic reported
`modal candidates=1, captioned=1, committed criterion='P5G1A-0001'`: one modal,
correctly captioned, carrying the right key. The locator theory of run 33598342557
was wrong, and so were the two before it.

What found the answer was reading the run's **other** Tomcat log. The legacy
Tomcat's `catalina.out` contained `FindWindow.getQuery` entries timestamped
*inside the modern capture*, at 12:21:37 and 12:22:29. Two of the four browser
sessions in the modern parity capture had been served by the legacy application.

The cause is a lane defect. The write flow drives four sessions under **two**
identities - `GardenAdmin` (`AD_User_ID` 101) for the primary and deactivating
sessions, `GardenUser` (102) for the second editor and the duplicate submitter -
and the lane applied the `user-allowlisted` cohort preset, which allowlists 101
alone. Cohort routing decides per identity, so 102 fell back to legacy exactly as
designed. Sessions 2 and 4 had been passing because they never touched ZK 10.

The evidence was not lying. `identifyServingRuntime` was called once, for the
primary session, and the validator checked that one row; the file said
`served=modern` and the primary session genuinely was. The comment above the
method called it "the only check in the capture that can see a lane serving the
wrong application", and it covered a quarter of the capture. A single sample of a
per-identity decision proves nothing about the other identities, and that is the
durable lesson of this run - more durable than the preset fix.

Three repairs follow. The lane applies a new `write-parity-users` preset
allowlisting both identities; `user-allowlisted` is left alone because the H6
matrix depends on it allowlisting exactly one. `identifyServingRuntime` becomes a
shared default method that runs for every session, writes a `served.<session>`
row, and now **asserts** the served runtime rather than only recording it. The
evidence validator requires all four rows and requires each to read `modern`,
with two new injected defect classes proving both halves of that check fire -
one for a capture that identified only its first session, one for a capture in
which a single session was served legacy.

Three earlier theories are recorded here as refuted, so they are not retried: a
settlement race on the blur round trip (refuted by two identical failures at the
same step), an ambiguous `/zkau` wait predicate satisfied by ZK 10 polling
(refuted when the hardened predicate passed), and a dialog locator resolving to
the window behind the modal (refuted by this run's own `candidates=1`
diagnostic).

**What this run leaves open.** Session 3 - the genuinely modern one - exposed a
real modern divergence that is still unfixed: the modern Tomcat logged
`MQuery[C_BPartner,Restrictions=0]` for every lookup while legacy logged
`UPPER(C_BPartner.Value) LIKE 'P5G1A-0001%'`. The next run should be read as
diagnosis of that product defect, not as a pass: once sessions 2 and 4 are
genuinely modern, they are expected to fail the same lookup.

### Run 33598342557 - the dialog was never the dialog

The third identical failure was the informative one, because of what did *not*
happen: the commit guard added for run 33591572610 stayed silent. An accepted
`/zkau` request had carried `P5G1A-0001`. The key was typed, sent and applied -
and the lookup still queried unfiltered.

If the key is committed and the query is unfiltered, the key was committed to
the wrong place. It was: `modalDialog(page)` is a union locator, callers took
`.first()` of it, and the Business Partner window has its own field captioned
**"Search Key"** - that is the caption of `C_BPartner.Value`. A dialog locator
that resolves to the window instead of to the modal finds a "Search Key" input,
fills it, and blurs it with a perfectly real AU request, while the dialog's own
criterion stays empty.

That also explains the shape of the failure, which no earlier theory did. It was
never intermittent and never a race: it failed on exactly one of the three keyed
window opens, always the same one. `enterWindowThroughFindDialog` answers
ADempiere's mandatory lookup dialog *before* any window has rendered behind it,
so its union has one member and it is always right. `reloadRecord` opens the
same dialog *over* a rendered window, so its union has two - and the deactivating
session is the only session that reaches `reloadRecord` at all.

The repair identifies the dialog by its own caption and takes the last match,
because Playwright resolves in document order and an ancestor that contains the
caption text always precedes the dialog that owns it. The Search Key criterion is
then resolved **strictly**: exactly one input, or the driver fails naming the
ambiguity. Filling the wrong "Search Key" is silent, and silence is what cost
three capture runs.

Because the dialog is destroyed before the wrong-record assertion runs, that
assertion now carries the dialog's own state - candidate counts and the committed
criterion, read while the dialog still existed - so a fourth recurrence would
report whether the criterion was ever there rather than only where the window
ended up.

### Run 33591572610 - a silent failure made loud

The repair from run 33589524866 did not hold: the modern capture reached the
same step and failed the same way, and the uploaded probe for the deactivating
session showed the window reporting `Data requeried 1/18` on
`Chemical Product, inc`. Eighteen is the whole GardenWorld business partner
list, so the lookup ran with **no criteria at all**.

That the same step failed twice ruled out the settlement race as the whole
story. The remaining defect is in the predicate rather than in the number of
waits: `url.contains("/zkau")` does not identify the request being waited for.
ZK 10 keeps its own `/zkau` traffic in flight - polling, echoes, timers - so the
wait can be satisfied by a request that carries nothing of ours.

The deeper problem is that an uncommitted key fails **silently**. FindWindow
simply queries unfiltered, the window opens on someone else's record, and the
driver only notices later and somewhere else, as a wrong-record assertion that
names neither the dialog nor the key. Two capture runs were spent on a symptom
whose cause was never reported.

So the wait now requires an **accepted** (`response.ok()`) `/zkau` response
whose own request body carries the search key, matched against the body both raw
and URL-decoded because ZK `encodeURIComponent`s each AU datum. That is direct
evidence the server received the key; it cannot be satisfied by unrelated
traffic; and when the key genuinely never leaves the browser, the driver now
fails there, with that named cause, instead of opening the wrong record.

A code review of the repair raised three defects, all fixed before commit: the
raw-`contains` match was blind to ZK's percent-encoding and would have called a
committed key uncommitted for any fixture value outside the unreserved character
set; the predicate ignored `response.status()`, so a server-side **rejection** of
the AU request would have been certified as a successful commit - the one way
this change could have masked a genuine modern divergence; and the keystroke ran
inside the guarded block, so a press that failed its own actionability checks
would have been reported as a network commit failure. A `TimeoutError` raised
before the press completes is now rethrown unchanged.

### Run 33589524866 - the first modern business write

**The modern runtime wrote to the database for the first time in the programme.**
Capture A measured steps 0 through 9 - `authenticated-baseline`,
`window-opened`, `create` (7 rows created across 11 changed tables), `update`,
`concurrency-second-editor-authenticated`, `concurrency-second-editor-update`,
`concurrency-conflicting-save`, `duplicate-submit-editor-authenticated`,
`duplicate-submit` and `deactivate-editor-authenticated` - through the public
routed origin, with the routed ambient census clean on both quiet intervals.

It then failed opening the window for the deactivating session:

    the window is not positioned on the captured record
    ==> expected: <P5G1A-0001> but was: <Chemical Product, inc>

`Chemical Product, inc` is a stock GardenWorld business partner, and landing on
it is the signature of an **unfiltered** query rather than of a missing record.

Both dialects fill the Find dialog's Search Key, press `Tab`, and then click Ok
inside a single `/zkau` wait. `Tab` blurs the field, which fires the editor's
`onChange` as its own AU request, and FindWindow does not hold the value until
that request is applied server-side. Under ZK 3.6 the single wait is enough.
Under ZK 10 it is a race the caller can lose: the wait is satisfied by the
blur's own round trip while the Ok click has already been dispatched against a
dialog whose query field is still empty. The same code path had been used
successfully twice earlier in the same capture, by the second editor and the
duplicate-submit session, which is what a race looks like.

The repair awaits the blur explicitly before dispatching the Ok click. It is a
dialect change, and a legitimate one: a dialect may express how a control is
located, operated and **awaited**, and this changes no step, no emitted fact and
no outcome vocabulary. It only stops the driver from reading the dialog before
the product has finished updating it.

### Run 33586831680 - the first modern runtime defect

The first run to drive the modern runtime through a business flow. Modern login,
role selection, the menu tree and the Business Partner window all rendered:
`runtime-identification.tsv` recorded `expected=modern served=modern`, the window
carried 169 editors, the organisation combobox held the expected `Fertilizer`,
and steps 0 and 1 (`authenticated-baseline`, `window-opened`) were measured.

Capture A then failed clicking the toolbar Save button. The button itself was
fine - Playwright resolved it and reported it "visible, enabled and stable" -
but something was on top of it:

    <div class="messages">…</div> from <div id="zk_err" class="z-error">…</div>
    subtree intercepts pointer events

The lane's failure screenshot named the cause: a ZK client error box reading
**147 Errors**, repeating `Illegal action: onKeyPress : return calc.validate(...)`
and `Unknown action: calc.append` / `calc.clearAll` / `calc.evaluate` /
`calc.percentage`.

`NumberBox.getCalculatorPopup()` attached its calculator handlers with
`HtmlBasedComponent.setAction("onClick : <javascript>")`. That was ZK 3.6's way
to register an inline client-side event handler. In ZK 5 and later `setAction`
means something else entirely - a client-side *effect* vocabulary - so ZK 10's
widget parser splits the string on `;`, fails to find each verb in
`zk.eff.Actions`, and calls `zk.error` for every one. `zk.error` is what renders
`div#zk_err.z-error`. Every numeric field on the Business Partner window
contributed, which is how a single API change produced 147 errors and an overlay
across the whole desktop.

The repair is the ZK 5+/10 API for the same intent, `setWidgetListener(event,
script)`, applied to all 20 call sites. The script body is compiled by
`new Function('var event=arguments[0];' + fn)`, so the one handler that takes
`event` and uses a top-level `return` keeps working unchanged.

This is a modern **runtime** defect, the first the increment has found, and it
is fixed in `WEB-INF/src` rather than worked around in the driver. Dismissing the
overlay in the dialect would have been a reclassification: the errors are real,
they are emitted by the product, and they are exactly the kind of difference the
increment exists to surface.

The legacy oracle cannot be perturbed by it. `zkwebui/build.gradle` sets
`sourceSets.main.java.srcDirs = []` and assigns `WEB-INF/src` exclusively to the
`modernUi` source set; `zkwebui/build.xml`'s `war` target only copies
`${phase5d.frozen.war}`, which `scripts/phase5/materialize-legacy-webui-war.sh`
builds in an isolated `git worktree` at the 40-hex `source_commit` recorded in
`contracts/legacy-web-v1/capture-environment.tsv`. A dirty working tree cannot
reach it, and the legacy freeze-off regression re-proves the frozen answer at PR
HEAD regardless.

Two residual observations were made while fixing this, neither in the write path
and neither introduced by the repair:

- **The modern calculator is functionally dead, though now silent.** ZK 5 dropped
  ZK 3.x auto-generated component ids, so `txtCalc.getId()` returns `""` before
  any `setId` - visible in the screenshot itself as `calc.validate('','',...)`.
  The popup now renders without errors, but its buttons resolve
  `document.getElementById('')` to `null`. That is an event-time `TypeError` in
  the console, not a render-time overlay, so it cannot affect a capture. It is
  recorded rather than fixed because repairing it means reworking `calc.js` to
  address components by uuid, which is not this increment's claim.
- **`WAttachment.java:147` still emits the ZK 3.x `$e(...)` helper**, which ZK 10
  replaced with `$eval`. It runs from a `setTimeout` inside an `AuScript`, so it
  cannot raise the overlay, and the attachment dialog is outside the Business
  Partner write flow.

### Run 33584462937 - the context path

The first run to reach a modern browser. Three things were proven on the way:
the PA_Goal quiescence closed the hour-boundary flake and the legacy freeze-off
regression re-proved the frozen oracle at PR HEAD; the routed lane brought up
both runtimes, recorded its topology and captured a golden archive from the
verified quiesced, cohort-routed state; and the routed ambient census passed,
which is the first evidence that the dual-runtime lane introduces no writer of
its own.

Capture A then failed after 30 seconds waiting for the login field. The lane's
own failure record named the cause without any local reproduction:

    url    http://127.0.0.1:8888/admin/
    titles a[]=Download ADempiere Client

`phase5g1a.browser.baseUrl` is the **origin** only, `http://127.0.0.1:8888`, and
`LegacyBrowserFlow.login` appends the `/webui/` context path itself.
`ZkCe10Dialect.signIn` navigated to `baseUrl + "/"`, which ADempiere redirects to
`/admin/`, so the driver waited for a ZK login field on the client download page.

This is a defect in the modern **driver**, not in the modern runtime: the
`[id^='rowUser'] input` selector it was waiting for is the one Phase 5d and
Phase 5e already drive successfully against ZK CE 10. Appending the context path
in the dialect also makes it structurally impossible for the scored origin to
drift onto the loopback `/webui-modern` context that ADR decision 6 forbids
scoring on.

The fix adds an explicit post-navigation assertion that the browser is still on
the `/webui` origin. The 403 in the modern Tomcat's access log for `GET /webui/`
is unrelated and correct - it is the loopback guard refusing a direct request
that carries no handoff ticket.

### Run 33580195848 - the hour boundary

This run failed in `phase5g1aLegacyWriteOracleSmoke` - the **legacy** freeze-off
regression that the parity smoke depends on so the oracle is re-proven at PR
HEAD. No modern code participates in that lane, and the same lane was green on
this branch in run
[33548556277](https://github.com/samqbush/adempiere2/actions/runs/33548556277),
so the finding is a pre-existing latent defect in the oracle lane that a longer
job merely made likely to be observed.

> `FAIL: step duplicate-submit-editor-authenticated: the effect diverged between
> captures A and B` - `observed: pa_goal +0 content`
>
> `FAIL: table pa_goal changed but is neither declared in the effect model nor
> classified as reviewed ambient state.`

`+0 content` is the R12 sentinel that increment 5g-1a-x added, working exactly
as designed: the row count did not move, the row *content* did, and without the
content component this would have been silently invisible.

The mechanism is `MGoal`
(`base/src/org/compiere/model/MGoal.java`). `getUserGoals` runs at login for the
performance indicator panel and calls `updateGoal(false)` per goal, which
recalculates and **saves** whenever

    force || getDateLastRun() == null || !TimeUtil.isSameHour(getDateLastRun(), null)

`PA_Goal` is therefore not a timer source - which is why
`quiesce-phase5f-background-processors.sh`, whose scope is the eight sources
`AdempiereServerMgr.startServers()` schedules, does not and should not cover it.
It is a **lazy, wall-clock-triggered** writer whose firing depends on nothing
but which clock hour a login happens in. Captures A and B of the *same* runtime
diverge when the lane crosses an hour boundary, and the 5g-1a freeze run simply
did not cross one, which is why the frozen models do not declare `pa_goal`.

**The fix removes the nondeterminism rather than forgiving it.** Two forgiving
repairs were available and both were rejected: widening `ambient-tables.tsv`
would teach the frozen contract to accept a real write set it cannot otherwise
explain, and `contracts/legacy-web-write-v1/` is read-only in 5g-1b in any case.
Instead `scripts/phase5/quiesce-performance-goals.sh` deactivates `PA_Goal`
during quiescence, so `getUserGoals`' own `WHERE IsActive='Y'` returns no rows
and `updateGoal` is never reached, in any hour.

This is lane infrastructure, not an oracle amendment:

- `contracts/` is untouched; nothing is re-captured, re-frozen or reclassified.
- Quiescence runs **before** the golden archive is captured, so every capture
  restores an already-quiesced database and the deactivation is a uniform
  baseline shift. `measure-write-effect.py` scores *diffs* between two snapshots
  within a capture, so a uniform baseline shift cannot perturb a frozen model.
- The same quiesce is applied identically to the legacy lane, the parity lane
  and every H6 case's own seed restore, so the two runtimes are never compared
  across different database states.
- The frozen contract itself, however, **was** captured unquiesced, and the
  deactivation is not invisible to the product: `WPAPanel.get()` returns `null`
  when `getUserGoals` yields no rows, so `DefaultDesktop`'s
  `if (!dashboardPanel.getChildren().isEmpty())` guard drops the Performance
  Indicators panel from the post-login desktop. That difference is asserted to
  be inert rather than assumed to be: the legacy freeze-off regression re-scores
  every frozen fact class - semantic facts, business values, network classes and
  concurrency facts - against the unquiesced-capture answer at PR HEAD, so if
  the missing panel perturbed any frozen fact, that gate fails. No other
  behaviour changes: all six `MMeasure.update*Goals()` variants resolve their
  write set through `MGoal.getMeasureGoals`, which filters `IsActive='Y'` too,
  and the dashboard's chart panels load goals by ID without calling
  `updateGoal`.
- `validate-phase5g1b-runtime-evidence.py` requires `goal-quiesce-state.tsv`, so
  a lane that silently stopped quiescing goals fails closed. It also requires
  `seed-goal-quiesce-state.tsv`, which the parity lane copies forward from the
  legacy lane's evidence directory before restoring the seed. That second file
  exists because the defect being repaired is in the *legacy* lane while the
  only PR-blocking gate reads 5g-1b evidence: without it, a legacy lane that
  stopped quiescing goals would leave every gate green - the parity lane
  re-quiesces after restoring the seed, so it would self-heal - while the oracle
  regressed to failing on an hour boundary. Removing either file is an injected
  defect class the validator's own validator rejects.

The acceptance test is the legacy freeze-off regression itself: it re-scores
both captures against the answer 5g-1a froze, so a green run is direct evidence
that the frozen answer still reproduces under the added quiescence.

### Run 33570169991 - the seed

The lane's own fixture guard caught a defect in the **lane**, not the modern
runtime:

> `Phase 5g-1a fixture precondition failed: 1 business partner row(s) with a
> P5G1A- key survived the reseed. The database was not restored from the golden
> archive.`

`phase5g1bModernWriteParitySmoke` depends on the legacy freeze-off regression so
the oracle is re-proven at PR HEAD, and that regression finishes with capture
B's post-write state - `P5G1A-0001` included - still in the database. The parity
lane then took its "golden" baseline from exactly that state.

The failure was the smaller half of the problem. A lane that seeded itself this
way would have scored the modern runtime **from a starting state the legacy
oracle never ran from**, and parity between two runtimes is only meaningful from
a bit-identical seed. The lane now restores the archive the legacy lane captured
from the quiesced installed product - the same verified state that produced the
frozen answer - and takes its own golden archive from there, failing closed if
that archive is absent rather than seeding itself from whatever ran before it.

### Run 33572353340 - session evidence

The seed fix held: `Phase 5g-1a fixture preconditions satisfied`, and the
routed ambient census passed with a clean scope. The session-evidence capture
then failed on `psql` variable interpolation, which does not happen inside a
`--command` string. The label is now embedded by the shell as a SQL literal,
after being constrained to `^[A-Za-z0-9_-]+$` so a value reaching a statement
can never carry quoting with it.

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
runtime; a capture that did not record its restore; two captures sharing one
restore; a missing or failing ambient census; a step ledger short of the frozen
one; a scorer left in freeze mode; a frozen contract edited out from under the
scorer; a re-frozen contract that agrees with its own regenerated manifest; a
missing or failing H6 row; an unobserved session lifecycle; an unquiesced
performance-goal recalculation in this lane or in the legacy lane the seed came
from; a second modern deployment; a JUnit report that is absent, empty, ran zero
tests, or failed.

A fail-closed check that silently never fires is worse than no check: it accepts
what it should refuse, quietly, forever. So
`verify-phase5g1b-runtime-evidence-validator.py` builds a synthetic evidence
tree the validator must accept, then mutates it once per defect class and
requires a rejection each time.

**29 injected defect classes, all rejected.**

The proof was itself verified, not asserted. The increment's central governance
guard - that a parity branch may not re-freeze the answer it is scored against -
is two independent checks, and each was proven to be load-bearing by disabling
it alone and confirming the proof went red with exactly one undetected class:

| Check disabled | Class the proof then missed |
|---|---|
| pinned manifest digest | a contract re-frozen so that it agrees with its own regenerated manifest |
| manifest walk | a contract file the manifest still lists |

That isolation matters because the obvious mutation - editing a frozen contract
file - also trips the step-ledger comparison, so it would have stayed green even
if both manifest guards were dead code.

The synthetic contract is built in the **real** generator's format, comment
headers and tab separators included. An earlier fixture used a format
`generatePhase5gWriteOracleManifest` never emits, which let a defect survive
review: the validator parsed the manifest's `#` comment headers as digest rows
and would have rejected **every** real run - after two captures, the scorer and
the whole H6 matrix had already run - with a message falsely accusing the branch
of editing the frozen oracle.

The lane invariants are proven the same way, and match only non-comment lines:
a whole-file substring search is satisfied by the very comment explaining the
rule, so a change that deleted the behaviour and left its explanation behind
would still have passed.

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
