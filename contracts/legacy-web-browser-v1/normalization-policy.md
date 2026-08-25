# Legacy web browser normalization and mutation policy

`contracts/legacy-web-v1/normalization-policy.md` governs the frozen low-level
ZK AU wire oracle. This file governs only the browser-visible semantic contract
in this directory. Neither file may be used to justify a normalization in the
other.

## Approved volatility

A value may be normalized only when it is newly minted on every capture and
carries no product meaning. The complete approved set is implemented once, in
`BrowserSemanticContract`, and is exercised by
`ErrorMessageWindowFactsTest.approvedTextVolatilityIsStillNormalized` and
`approvedUrlVolatilityIsStillNormalized`.

| Volatility | Normalization | Why it carries no meaning |
|---|---|---|
| Non-breaking spaces and layout line breaks in rendered text | Collapse to single ASCII spaces and trim | ZK emits `&nbsp;` and wraps identical labels differently across renders |
| Container session id | `;jsessionid=<SESSION>` | Minted per HTTP session |
| ZK desktop id | `dtid=<DTID>`, `/webui/zkau/view/<DTID>/` | Minted per ZK desktop |
| ZK component counter | `zk_comp_<COMPONENT>` in URLs; trailing digits of an element id are matched but never recorded | `SahiIdGenerator_v1` assigns a per-desktop sequence, so `unqField_1_0_AD_Error_AD_Client_ID2977` and `...2981` are the same field |
| Repetition counts of an already-classified network request or browser error | Collapse to the distinct class | Browser scheduling of images and page-error callbacks is not deterministic |

Nothing else is normalized. In particular the semantic **name** of a field, a
column, a toolbar control, a tab, a route, or an error class is never rewritten,
and a request to a different host is never folded into an existing class.

## Stable selector policy for the window step

Every selector used to observe the "Error Message" window is anchored on an
ADempiere-owned name, so the same observation can be taken from the future ZK 10
slice, which keeps the ADempiere source and changes only the ZK runtime:

| Anchor | Owner | Source |
|---|---|---|
| `div.desktop-tabpanel` | ADempiere | `WindowContainer.java:214` |
| `unqField_<window>_<tab>_AD_Error_<Column>` id prefix | ADempiere | `WEditor.java:121-125` |
| `readonly-field`, `mandatory-field`, `normal-field`, `field-*` markers | ADempiere | `WEditor.java:369-408` |
| Toolbar `title` texts (`New Record`, `Save changes`, `Delete record`, `Delete Selected Items`) | ADempiere `AD_Message` | `CWindowToolbar.java:247-252` |
| Menu lookup label tooltip `Enter text to search for in tree` | ADempiere `AD_Message` `TreeSearchText` | `TreeSearchPanel.java:108-110` |

The only ZK-owned names the step depends on are the generic widget classes
`span.z-tab-text`, `input.z-combobox-inp`, and the disabled marker
`z-toolbar-button-disd`. They are recorded here so a ZK 10 divergence is a
reviewed contract change rather than a silent selector rewrite.

## Mutation policy

A normalization is only allowed to exist if a mutation proof shows it does not
also erase a real change. The Phase 5c proofs cover a changed semantic name, a
changed navigation class, a new error class, and approved whitespace. Phase 5d
adds proofs for the window step, all of which run database-neutrally in
`:zkwebui:check`:

| Mutation | Must fail because |
|---|---|
| Window never opened (`panels = 0`) | The flow no longer reaches the read-only window |
| Desktop tab absent | The window opened without becoming reachable to a user |
| Two matching window panels | The observation is ambiguous and cannot be compared |
| Window panel renders no box | The window is not visible |
| A record-identity column loses `readonly-field` | The window is no longer read-only where the contract says it is |
| A destructive control becomes enabled | The window is no longer read-only where the contract says it is |
| A destructive control disappears | The read-only assertion silently stops being made |
| The window step writes a row | The step is a write, not a read |
| The browser payload is truncated or is not an observation | A shape change would otherwise pass as an empty observation |

## What is deliberately not claimed

The window is not dictionary-read-only. `AD_Tab` 314 carries `IsReadOnly='N'`
and `IsInsertRecord='Y'`, `AD_Error` is empty on a restored seed, and the
rendered toolbar enables New Record and Save changes. Those enabled states are
recorded verbatim in `window-error-message-record-controls` rather than hidden,
and the read-only property that is asserted is a property of the flow: the
window renders, its record-identity columns are non-editable, both destructive
controls are disabled, and `window-readonly-effects.tsv` measures zero writes.
