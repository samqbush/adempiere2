# Phase 5b legacy web oracle and artifact pin evidence

## Scope

Phase 5b freezes the behaviour of the installed Tomcat 9 web product and pins the
legacy web artifacts, so that Phase 5c onward can move ZK source to Jakarta with
a real rollback baseline instead of a hope. It changes no `zkwebui` source, no
descriptor, no dependency coordinate and no runtime pin. Every change is
additive and rollback is a revert of the phase commit.

The branch is `phase-5b-legacy-web-oracle`, cut from `develop` at `dc7e84f68`
(Phase 5a, PR #6). `git log origin/develop..HEAD` was empty at branch creation,
clearing H7.

**Safety rung: L3.** Legacy ZK/Tomcat 9 is lit, so this phase is gated by
executable replay rather than by review alone.

## Scoping decisions

| Decision | Choice | Why |
|---|---|---|
| Capture level | HTTP / ZK-AU protocol only | Browser tooling is Phase 5c's deliverable; 5b must not depend on work that does not exist yet |
| Breadth | Deep flow for `/webui`, request vectors for the other contexts | Only `/webui` has a stateful protocol worth driving end to end |
| Publication | Checksum manifest only, no binaries | Retained binaries hide non-reproducibility instead of exposing it |
| Gates | Split DB-backed smoke and DB-neutral verification | Contract verification must not require a database to be honest |

## The frozen tree

`contracts/legacy-web-v1/` holds 105 files. `manifest.sha256` covers every one of
them except itself, and `verifyPhase5OracleManifest` fails on missing files,
altered files **and** unexpected files.

| Artifact | Contents |
|---|---|
| `zk-au-flows.tsv`, `zk-bootstrap/` | The eight-step deep `webui` flow and its normalized bodies |
| `context-request-vectors.tsv`, `context-responses/` | 82 reviewed request vectors across `/`, `/adempiere`, `/admin`, `/mobile`, `/wstore` |
| `session-http-observed.tsv` | Cookie and redirect semantics actually observed over HTTP |
| `session-static-config.tsv` | Descriptor-sourced facts, labelled source-inspected |
| `installed-web-assets.tsv`, `static-asset-contract.tsv` | Four-way WAR entry classification and the served-asset contract |
| `legacy-web-artifacts.tsv`, `legacy-zk-runtime.tsv` | Recursive logical digests and packaged runtime JAR pins |
| `database-effects.tsv` | The only database delta a capture is allowed to leave |
| `capture-environment.tsv` | 24 runtime coordinates |
| `oracle-exclusions.tsv` | 20 owned gaps, each with an owner and a closing gate |
| `normalization-policy.md`, `domain-review.md` | Per-field dispositions, and the review that had to precede the gate |

## Driving ZK 3.6 without a browser

The plan flagged this as the first escalation trigger. It cleared, but only
after the wire format was **derived from the shipped `zk.jar!/web/js/zk/html/au.org.js`**
rather than guessed:

- The AU separator is a dot (`cmd.0`), not an underscore. Underscores are
  rejected with `Illegal request: cmd required`.
- Responses are XML (`<rs><r><c>cmd</c><d>payload</d></r></rs>`), not JSON.
- `onClientInfo` takes an empty uuid and exactly eight ordered values, and must
  precede content construction.
- **Faults arrive as an `<c>alert</c>` command under HTTP 200.** A status-code
  assertion would have declared a failed login a success. The command sequence
  is therefore part of the contract, not decoration.
- Menu open is `ON_CLICK` on the Treerow, not the Tree or Treeitem
  (`MenuPanel.java:161,180`).

The resulting flow runs `GET /webui/` → `onClientInfo` → username → password →
OK → role OK (a 643 KB desktop response) → menu open → logout.

## Determinism, in both directions

A self-diff only ever proves that the normalizer is not *under*-normalizing.
`verifyPhase5NormalizerMutationProof` proves it is not *over*-normalizing, and
does so against a committed raw capture so the gate stays database-neutral. It
asserts that changed command order, changed command count, changed target
identity, changed selected ids, changed labels and changed payload values all
**fail**, while approved desktop ids, session ids, sequence counters, timestamps
and cookie values **pass** — including the uuid identity case, where one uuid
reused twice must not compare equal to two distinct uuids.

Because the flow writes to the database, capture A can change what capture B
sees. The reviewed delta set in `database-effects.tsv` was **not** arrived at by
inspection — every entry after the first was forced by the gate failing during
the rollback rehearsal, which is the strongest evidence that the assertion is
real rather than decorative:

| Effect | How it surfaced |
|---|---|
| `AD_Session` +1 per capture | Measured up front |
| `AD_Preference` created on first login, then rewritten in place | The "unchanged" assertion passed only on an already-warm database. It is now pinned to an **absolute** post-capture digest, which is strictly stronger: it also fails a capture that never reached role completion |
| `AD_ChangeLog` rows referencing the capture's session | The reset failed on the `adsession_adchangelog` foreign key |
| `AD_Tree_Favorite` node created on first menu open | The undeclared-table assertion named it |
| `AD_RecentItem` per opened window | The frozen-tree diff showed the desktop rendering two recent items where the oracle recorded one |

The last two mattered most: first login is **not** idempotent, so running the
determinism experiment directly onto a freshly restored seed compares a first
login against a repeat login and blames the normalizer. The replay therefore
**primes** a cold database and resets the fixture before capture A as well as
between A and B, so both captures start from the same declared precondition
instead of inheriting whatever the previous run left behind.

Server-rendered dates come from Tomcat's JVM, so exporting `TZ` in the capture
shell cannot make them reproducible. `run-legacy-web-oracle-lane.sh` pins
`-Duser.timezone=UTC` on the server. This was proven, not assumed: an oracle
captured under MDT replays byte-identically against a UTC-pinned JVM.

## Two normalizer defects the rehearsal exposed

Both would have produced an oracle that looked green and was not:

- **The Ant build stamp leaks into the page.** The login page renders
  `Release 3.9.4 20260824-1143` from `Adempiere.DATE_VERSION`. Two builds of
  identical source differ there, so the oracle could only ever have replayed
  against the single build that produced it — rollback verification would have
  been impossible. The rule is anchored to the release line and the
  `YYYYMMDD-HHMM` shape, and a **paired mutation case** proves the product
  version beside it is still detected. A rule wide enough to swallow the stamp
  would swallow the version too, and only the pair catches that.
- **The desktop-id replacement was unanchored, and therefore flaky.** ZK desktop
  ids are short and lowercase; a real capture drew `gth` and rewrote
  `maxlength="40"` into `maxlen<DTID>="40"`. Whether the corruption happens
  depends on a random id, so the oracle was intermittently wrong rather than
  reliably wrong. Replacement is now bounded to whole tokens and a deterministic
  regression test pins the behaviour.

The mutation proof now runs 11 cases, not 8.

## Artifact pins and reproducibility

The second escalation trigger was whether artifacts are reproducible enough to
back a rollback with no retained binaries. Running two clean
`phase3NoDatabaseDistribution` builds from deleted outputs found three distinct
defects, each of which would have produced a false pass or a false failure:

1. **Manifest build stamp.** Ant writes a `YYYYMMDD-HHMM` stamp into every
   generated `META-INF/MANIFEST.MF`. The first two builds matched only because
   they landed in the same minute. Manifests are now unfolded and the stamp
   normalized.
2. **Raw-length leakage.** The digest line carried the raw entry length, so a
   normalized manifest of different length still changed its parent archive's
   digest. Normalized length is now used consistently.
3. **Contagious non-reproducibility.** `admin.war/adempiereHome/AdempiereClient.zip`
   is not itself signed but bundles signed jars, so a top-level signature check
   missed it. The reason function now recurses.

Result: **2287 entries byte-identical across two independent clean builds.** The
13 that are not are pinned individually — 4 code-signed, 2 installation-configured
`.jnlp`, 7 envelope rows. Pin verification is therefore a reproducible-subset
comparison **plus** the requirement that the non-reproducible entry set is
unchanged, so a newly unreproducible entry cannot slip through unnoticed.

## Coverage

84 deployed routes, 82 request vectors, 20 owned exclusions. Four vectors are
`context-reachability-only`: the `/*` filters, which have no independently
addressable URL and are covered indirectly through representative servlet
vectors in the same context. The gate fails on an unknown proof strength, a
blank owner, a blank closing gate, or a stale exclusion.

## Findings frozen, not blessed

`domain-review.md` records the full review. The findings that matter:

- `/adempiere` and `/mobile` answer **every** path from one catch-all handler, so
  every static asset packaged in those WARs is unreachable. Proven with a
  two-probe test, not hardcoded.
- Four routes answer HTTP 500: `/AdRedirector`, `/communityServlet`, `/xml/*`,
  `/wstore/paymentServlet`.
- Logout does not invalidate the HTTP session (`AdempiereWebUI.java:349-367`).
- The session cookie carries neither `Secure` nor `SameSite`.
- `/admin/adempiereMonitor/` correctly returns 401.

Each is recorded with an owner and a closing gate. None is treated as a
requirement that Phase 5c must preserve.

Separately, and outside the oracle's scope: the installed
`Adempiere.properties` declares `type=Oracle` while `AdempiereEnv.properties`
declares `PostgreSQL`. Boot succeeds only because `-DPropertyFile` points at the
latter. This is an installer defect and is reported rather than frozen.

## Gates

| Gate | Database | Asserts |
|---|---|---|
| `phase5bFinalVerification` | no | Artifact pins, route coverage, mutation proof, 24 environment coordinates, file manifest; chains `phase5aFinalVerification` and therefore `phase4FinalVerification` |
| `phase5bLegacyWebOracleSmoke` | yes | Capture A, fixture reset, capture B, self-diff, replay of both against the frozen tree |

Both run in CI as `phase-5b-oracle-contracts` and `phase-5b-web-oracle`.
**Required-check enforcement remains a manual repository-administrator action**
and is still residual risk R8; these jobs run but do not yet block merges.

## Rollback rehearsal

Recorded in `docs/modernization/evidence/phase-5b-rollback-rehearsal.txt`. Build
outputs were deleted, the distribution rebuilt from the pinned commit, the
artifact pins re-verified against the rebuild, and the oracle replayed against a
restored runtime and a **freshly restored database seed**.

The rehearsal is the reason this phase is trustworthy. Every finding in the two
sections above was produced by it. An oracle proven only against the warm
machine that created it would have passed on the first attempt and failed the
first time anyone actually needed it.

## Security posture

H6 was held as a hard constraint. The capture authenticates with ordinary
credentials through the ordinary login flow. No bypass, debug endpoint,
permit-all, test-only servlet or disabled check was added, and nothing was added
to the transitional-state register.
