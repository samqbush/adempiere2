# Legacy web browser semantic contract

This Phase 5c contract complements, but does not modify,
`contracts/legacy-web-v1/`. It records browser-visible semantic facts from the
installed Tomcat 9 product and keeps screenshots, videos, and traces as
diagnostic CI artifacts rather than pixel-diff gates.

The four filter facts remain `context-reachability-only`: a browser proves that
a real request reached each mapped context, but the filters expose no unique
addressable URL. The classification is intentionally not overstated.

`allowed-browser-errors.tsv` freezes the distinct inherited HTTP and page-error
classes those routes may emit. Repetition counts, session identifiers, and
browser scheduling of page-error callbacks are volatile; a new error class
requires review, while self-diff treats the HTTP failures as the stable subset.
Phase 5c records rather than fixes these legacy defects, which remain owned by
Phase 5f.

The harness records but blocks every request outside the loopback product
origin, including the inherited Firefox image, Google Calendar, ZK branding,
and web-font requests. Canonical replay never depends on or sends traffic to
those external services. `network-classes.tsv` freezes the stable context,
ZK-AU, and external-origin classes without pretending that asynchronous image
counts or optional legacy page requests are deterministic.

Database effects remain owned by
`contracts/legacy-web-v1/database-effects.tsv`; this contract does not maintain
a second database-delta list.

## Phase 5d: the read-only window step

Phase 5d extends the same verified flow with the deep step the frozen wire
oracle already records as `onClick(menu-row:Error_Message)`: after login, role
selection, and the desktop menu, the browser looks the exact `Error Message`
menu item up through ADempiere's own menu lookup, opens it, observes it, and
then logs out. The low-level command/body contract in
`contracts/legacy-web-v1/` is untouched.

`window-observation-fixture.tsv` freezes the reviewed browser observation the
window facts are derived from, and `window-readonly-effects.tsv` freezes the
tables the step must not write. `normalization-policy.md` records the complete
approved volatility set, the ADempiere-owned selector anchors, and the mutation
policy.

The window is **not** dictionary-read-only, and the contract does not pretend it
is. `AD_Tab` 314 carries `IsReadOnly='N'` and `IsInsertRecord='Y'`, `AD_Error` is
empty on a restored seed, and the rendered toolbar really does enable New Record
and Save changes. Those enabled states are recorded verbatim in
`window-error-message-record-controls`. The read-only property that is asserted
is a property of the flow, and it is asserted four ways at once: the window
renders and is visible, its record-identity columns carry ADempiere's own
`readonly-field` marker, both destructive controls are disabled, and the
marker-owned database observes zero writes across the capture. The classification
is intentionally not overstated, for the same reason the four filter facts remain
`context-reachability-only`.

`manifest.sha256` covers every other file in this directory, so an unreviewed
fixture cannot silently join the contract.
