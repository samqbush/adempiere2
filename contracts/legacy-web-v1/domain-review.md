# Phase 5b — recorded domain review of the frozen legacy web oracle

A self-frozen baseline freezes current behaviour, defects included. Before this
tree became an enforced gate it was reviewed row by row against the ADempiere
web sources and descriptors, and every observation that is *not* known-good was
either corrected in the capture or registered in `oracle-exclusions.tsv` with an
owner and a closing gate. Nothing in this tree is blessed by default.

Reviewer: `@samqbush`. Scope: every file in `contracts/legacy-web-v1/`.
Runtime under review: the Phase 3 installed product on Tomcat 9.0.121 / JDK 21 /
PostgreSQL 14, as pinned in `capture-environment.tsv`.

## What the review had to decide

For each frozen observation: is this **correct product behaviour** that Phase 5c+
must preserve, **known-wrong behaviour** that must be frozen for change detection
but never treated as a requirement, or **a capture artefact** that should not
have been frozen at all?

## Verdicts

### Accepted as correct behaviour to preserve

- The eight-step `webui` ZK-AU flow in `zk-au-flows.tsv`, from bootstrap through
  `onClientInfo`, credential entry, role/client/org selection, desktop
  construction, menu open and logout. Each step's command sequence was read
  against `AdempiereWebUI.java`, `LoginPanel.java`, `RolePanel.java` and
  `MenuPanel.java` and matches the documented control flow.
- `/admin/adempiereMonitor/` returning **401** without credentials. This is the
  one context that enforces container auth, and it does so correctly.
- The 78 `exact-servlet-dispatch` vectors in `context-request-vectors.tsv`, each
  of which reaches its declared servlet with its declared status.
- The cookie name, path scoping and `HttpOnly` attribute recorded in
  `session-http-observed.tsv`.

### Frozen as known-wrong — recorded, never treated as a requirement

- **Four routes answer HTTP 500** on a plain request: `/AdRedirector`,
  `/communityServlet`, `/xml/*` and `/wstore/paymentServlet`. These are frozen so
  that a Phase 5c+ change is detected, but they are registered as
  `known-defect-frozen` and owned by `phase5e-route-review`. Migrating them to a
  *different* failure is a change; migrating them to success is a fix, not a
  regression, and the exclusion says so.
- **`/adempiere` and `/mobile` shadow their own static assets.** Both contexts
  answer every path from a single catch-all handler, proven by a two-probe test
  against two distinct non-existent paths returning byte-identical bodies. Every
  static asset packaged in those two WARs is therefore unreachable over HTTP.
  This is a genuine packaging finding, not a capture limitation, and it is owned
  by `phase5e-packaging-review`.
- **Logout does not invalidate the HTTP session.**
  `AdempiereWebUI.java:349-367` clears application caches and redirects; the
  container session survives. `session-http-observed.tsv` records the observed
  negative behaviour rather than claiming invalidation, so Phase 5e inherits an
  accurate baseline instead of an aspirational one.
- **The session cookie carries neither `Secure` nor `SameSite`.** Recorded as an
  observed fact. `Secure` cannot be meaningfully characterised on a loopback HTTP
  endpoint, so the assertion itself is excluded and owned by
  `phase5e-session-gate`; the *absence* of the attributes is still frozen.
- **Shipped build and source artefacts.** The installed WARs contain build files
  and source-only JARs that have no business in a deployed artefact. Registered
  as a packaging finding rather than normalised away.

### Rejected from the oracle — capture artefacts, not behaviour

- **Comet server push** (`zkwebui/WEB-INF/zk.xml:47-54`) injects unsolicited
  traffic that is not a response to any captured request. Freezing it would
  freeze timing, so it is excluded and owned by `phase5c-ui-gate`.
- **`.jnlp` templates embed the request host and port**, so their bytes depend on
  which port the capture ran on. The host:port is normalised before digesting;
  the rule is documented in `normalization-policy.md` and the exclusion records
  why the raw bytes are not the contract.
- **`admin.war`'s `.jnlp` files embed the installer's encrypted database
  connection string**, which is environment-derived and not reproducible from a
  source commit. Pinned as `not-reproducible-installation-configured` and kept
  out of the enforced entry-set digest.

### Reproducibility residuals accepted with owners

Three distinct problems were found and fixed during `canonical-digest`; what
remains is genuinely irreducible and is pinned per entry rather than waved away:

- `not-reproducible-code-signed` (4 entries) — JCE signatures are nondeterministic.
- `not-reproducible-installation-configured` (2 entries) — see above.
- `not-reproducible-informational` (7 rows) — ZIP envelope metadata.

Against these, **2287 entries were proven byte-identical across two independent
clean builds from deleted outputs**. The enforced comparison is the reproducible
subset *plus* the requirement that the non-reproducible entry set itself is
unchanged, so a newly unreproducible entry can never appear unnoticed.

## Coverage honesty

84 deployed routes, 82 request vectors, 20 owned exclusions. Four vectors are
`context-reachability-only`: the `/*` servlet filters, which have no
independently addressable URL. They are covered indirectly through the
representative servlet vectors in the same context and are explicitly *not*
claimed as exact dispatch. Every one of the 20 exclusions carries an owner and a
named closing gate; the gate fails if either is blank or if an exclusion goes
stale.

## Pre-existing defect noted outside the oracle's scope

`build/phase3/runtime/Adempiere/Adempiere.properties` declares `type=Oracle`
while `AdempiereEnv.properties` declares `PostgreSQL`. Boot succeeds only because
`-DPropertyFile` points at the latter. This is an installer defect, not a web
behaviour, so it is reported here rather than frozen as a contract row.

## Conclusion

The tree is fit to become a gate. No observation was frozen as a requirement
without being understood, and every known-wrong behaviour is visible, owned and
scheduled rather than silently blessed.
