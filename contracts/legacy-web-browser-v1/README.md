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
