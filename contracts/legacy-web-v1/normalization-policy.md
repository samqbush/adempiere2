# Phase 5b normalization policy — legacy ZK 3.6 web oracle

This policy governs how a captured legacy web response is reduced to a
comparable form. It exists because a byte-diff of ZK Asynchronous Update (AU)
traffic is unstable, while an over-aggressive normalizer silently destroys the
oracle's ability to detect regressions.

Every field below carries exactly one disposition:

| Disposition | Meaning |
|---|---|
| `stable` | Compared byte-for-byte. A change fails the gate. |
| `normalized` | Replaced by a documented deterministic token before comparison. |
| `structural` | Only shape, arity, or ordering is compared, never the literal value. |

A field that is not listed here is `stable` by default. Adding a `normalized`
disposition requires a recorded reason in the table. A blanket regular
expression that deletes all identifier-shaped tokens is prohibited: it would
mask changed command targets, changed component counts, and changed payload
values, which are precisely the regressions this oracle exists to catch.

## Grounding: which ZK identifiers are actually nondeterministic

`zkwebui/WEB-INF/zk.xml:43-45` registers
`org.adempiere.webui.AdempiereIdGenerator`, which resolves its delegate from the
`AD_SysConfig` key `org.adempiere.webui.IdGenerator`. The code default is
`org.adempiere.webui.SahiIdGenerator`
(`AdempiereIdGenerator.SYSCONFIG_IdGenerator_Default`), but the shipped seed
**overrides it** to `org.adempiere.webui.SahiIdGenerator_v1`. The configured
delegate, not the code default, is what governs this policy. Reading
`SahiIdGenerator_v1`:

- `nextComponentUuid` returns either the component's explicit
  `zk_component_ID` attribute, or `<prefix><n>` where `prefix` defaults to
  `zk_comp_` and `<n>` is a per-desktop counter seeded at `0`. A collision on an
  explicit id appends `_<n>`. Every branch is a pure function of the component
  construction path, so **component uuids are deterministic**.
- `nextDesktopId` returns `null`, so ZK falls back to its own generator. **Desktop
  ids are nondeterministic.**
- `nextPageUuid` returns `null`, so ZK falls back to its own generator. **Page
  uuids are nondeterministic.**

`SahiIdGenerator` and `SahiIdGenerator_v1` agree on all three dispositions, so
the policy is valid under either delegate. It would *not* be valid under a
delegate that randomizes component uuids.

This is a load-bearing conclusion. Because component uuids are stable and
sequential, they are compared as `stable`, which means a changed component
count, a changed construction order, or a changed command target all fail the
gate natively — without relying on positional-ordinal reconstruction.

The conclusion holds only while the configured delegate keeps component uuids
deterministic. The `AD_SysConfig` key `org.adempiere.webui.IdGenerator` and its
resolved value are therefore recorded in `capture-environment.tsv` and asserted
by `verifyPhase5OracleEnvironment`. If it is ever pointed at a delegate that
randomizes component uuids, they move to `normalized` with positional ordinals
and this policy must be revised in the same change.

## Field dispositions

### HTTP envelope

| Field | Disposition | Rule and reason |
|---|---|---|
| Request method, path, query shape | `stable` | Part of the route contract. |
| Response status code | `stable` | Core contract. |
| `Content-Type` including charset | `stable` | ZK 10 must preserve MIME and charset behaviour. |
| `Location` header path | `stable` | Redirect target is contractual. |
| `Location` header `jsessionid` fragment | `normalized` | `;jsessionid=<hex>` → `;jsessionid=<SESSION>`. Container-generated. |
| `Set-Cookie` name, `Path`, `HttpOnly`, `Secure`, `SameSite` | `stable` | Session-security contract frozen for Phase 5e. |
| `Set-Cookie` value | `normalized` | → `<SESSION>`. Container-generated. |
| `Date`, `Expires`, `Last-Modified` | `normalized` | → `<HTTP-DATE>`. Wall-clock. |
| `ETag` | `normalized` | → `<ETAG>`. Derived from timestamped resources. |
| `Content-Length` | `structural` | Compared only for zero versus non-zero, because normalization changes body length. Body identity is carried by `body_digest`. |
| Header ordering | `structural` | Headers are compared as a sorted name→value map. |

### ZK AU protocol

| Field | Disposition | Rule and reason |
|---|---|---|
| Desktop id (`dtid`) | `normalized` | → `<DTID>`. Nondeterministic per `SahiIdGenerator.nextDesktopId`. |
| Page uuid | `normalized` | → `<PAGEUUID>`. Nondeterministic per `SahiIdGenerator.nextPageUuid`. |
| Component uuid (`zk_comp_<n>`) | `stable` | Deterministic per `SahiIdGenerator.nextComponentUuid`. Compared literally so that component count and construction order regressions fail. |
| AU command name | `stable` | The command vocabulary is the behavioural contract. |
| AU command ordering | `stable` | Reordering is a real behavioural change. |
| AU command count | `stable` | Asserted explicitly by `au_command_sequence`. |
| AU command target uuid | `stable` | Follows the component-uuid disposition; identity relationships must be preserved. |
| AU sequence / request counter | `normalized` | → `<SEQ>`. Transport-level bookkeeping. |
| ZK version token and cache-buster in resource URLs | `normalized` | → `<ZKVER>`. Changes on any ZK build. |
| Ant build stamp in the rendered release line (`Release 3.9.4 20260824-1143`) | `normalized` | → `<BUILD-STAMP>`. `Adempiere.DATE_VERSION` records when the build ran, so two builds of identical source differ here. Anchored to the release line and the `YYYYMMDD-HHMM` shape: the **product version itself stays stable and is still part of the contract**, so a version change fails. Without this rule the oracle could only replay against the one build that produced it, which would make rollback verification impossible. |
| Server-push / poll traffic | excluded | The capture disables or drains it; see "Server push" below. |
| Localized labels and status text | `stable` | Captured under a pinned locale; a changed label is a real regression. |
| Serving JVM name/version rendered in the login version box | `normalized` | → `<JVM>`. The product prints the VM of whichever host runs Tomcat. It is a host coordinate, recorded in `capture-environment.tsv`, not product behaviour: without this rule the oracle can only replay on the machine that froze it. The surrounding row markup stays `stable`, so a removed or renamed row still fails. |
| Serving OS name/version rendered in the login version box | `normalized` | → `<OS>`. Same reason as the JVM row. |
| `ADEMPIERE_HOME` base64 argument of `adempiere.findUserToken(...)` | `normalized` | → `<ADEMPIERE-HOME>`. Records where the product was unpacked. The component uuid argument stays `stable`, so a retargeted call still fails. |
| Stylesheet include ordering within one contiguous `<link rel="stylesheet">` block | `structural` | Compared as a sorted set. ZK emits one link per language addon in classloader discovery order, which follows the host's filesystem enumeration and therefore differs between macOS and Linux. The **set** of stylesheets stays contractual: a dropped, added, or rewritten stylesheet fails. |
| Selected role, client, org, warehouse ids | `stable` | The seeded oracle fixture pins these; a change is a real regression. |

### Database-derived values appearing in responses

| Field | Disposition | Rule and reason |
|---|---|---|
| `AD_Session_ID` | `normalized` | → `<AD_SESSION>`. Sequence-generated per login. |
| Rendered dates and timestamps | `normalized` | → `<TIMESTAMP>`. Wall-clock. |
| Business record ids from the pinned seed | `stable` | The seed is checksum-pinned, so these are reproducible. |
| Result-set ordering | `stable` | Captured queries must be deterministically ordered; if one is not, that is a defect recorded in `oracle-exclusions.tsv`, not something to normalize away. |

## Live-id map

The normalizer never rewrites the request stream. It maintains a **separate live
id map** used to construct subsequent requests from the *unnormalized* capture.
Normalized tokens such as `<DTID>` and `<SESSION>` are a comparison artifact only
and must never be sent to the server. The capture driver and the normalizer
therefore operate on two distinct copies of each response.

## Server push

`zkwebui/WEB-INF/zk.xml:47-54` selects
`org.zkoss.zkmax.ui.comet.CometServerPush`. Comet holds a long-lived request open
and can inject unsolicited AU commands into a capture, which would be
nondeterministic in both content and timing. The capture driver does not open a
server-push channel, and any AU response command originating from server push is
excluded from `au_command_sequence`. Server-push behaviour is therefore **not**
covered by this oracle and is recorded in `oracle-exclusions.tsv` with an owner
and a closing gate.

## Determinism and mutation proofs

Normalization is verified from both directions, because either alone is
insufficient.

- **Under-normalization** is detected by `replay-legacy-web-oracle.sh`: it
  captures twice against two fresh sessions, resetting the oracle fixture in
  between, and asserts the two normalized outputs are byte-identical before
  either is compared to the frozen tree. A failure here is reported as a
  normalizer defect, with a distinct exit path from a product regression.
- **Over-normalization** is detected by `verifyPhase5NormalizerMutationProof`.
  Mutating any `stable` field must fail; mutating any `normalized` field must
  pass. In particular, replacing two distinct component uuids with one repeated
  uuid must fail, proving the normalizer preserves identity equivalence and not
  merely positional shape.

## Normalization of the capture environment

Normalization assumes a pinned *emulated client* and a pinned product build.
Locale, timezone, and the emulated client information sent in the
`onClientInfo` event are fixed constants recorded in `capture-environment.tsv`,
and comparing a capture taken under different **client** coordinates is not a
supported operation.

The *host* coordinates are a different matter. The oracle exists to survive a
rollback rehearsal and to run in CI, so it must replay on a machine other than
the one that froze it. Host coordinates that the product renders into a
response — the serving JVM, the serving OS, and the install path — are
therefore `normalized` rather than assumed identical, and their real values stay
recorded in `capture-environment.tsv`. Anything a capture inherits from the host
*shell* rather than the product is a defect in the capture driver, not something
to normalize: the driver joins its own multi-valued fields instead of relying on
`paste`, whose empty-input behaviour differs between BSD and GNU coreutils and
once made the same response record a different `au_command_sequence` on macOS
and on Linux.
