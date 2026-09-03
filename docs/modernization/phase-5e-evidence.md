# Phase 5e evidence: fail-closed cohort routing and session isolation

Phase 5e is a post-testability ("lit"), **L3** security and session increment.
It selects new sessions for the modern ZK CE 10 runtime from strict, fail-closed
allowlists after ordinary authentication and role selection, hands them over
with a single-use signed ticket, and keeps Tomcat 9 as the only public ingress.

It adds **no** business screen, migrates **no** Phase 5f or 5g route, and
retires **nothing**.

## Canonical commands

```bash
./gradlew phase5eFinalVerification --dependency-verification=strict
./gradlew phase5eCohortRoutingSmoke \
  -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

## Execution status

| Gate | Status |
|---|---|
| `phase5eFinalVerification` (database-neutral) | **Executed and green.** |
| `phase5eCohortRoutingSmoke` (database-backed) | **Executed and green.** The marker-owned disposable PostgreSQL run recorded all 23 public-origin matrix rows as passing. |
| R15 routing-hardening re-verification | **Pending in GitHub Actions.** The branch adds deterministic neutral and bridge coverage plus a 24th runtime matrix row; no canonical full or database-backed gate is claimed green before its PR-head runs complete. |

The database-backed table below is recorded evidence from the canonical smoke.
Its generated row-level results are in
`build/phase5e/evidence/cohort-matrix.tsv`.

`phase5eFinalVerification` chains `phase5dFinalVerification`, so the Phase 5d
direct `/webui-modern` lane remains an independent regression gate: the Phase 5e
handoff filter, cookie-only tracking and protocol jar all have to leave it green.

## What is proven, and by which gate

### Database-neutral (`phase5eFinalVerification`)

| Claim | Gate |
|---|---|
| The cohort grammar accepts exactly the documented forms and nothing else | `:org.adempiere.cohort:test` — `CohortConfigurationParserTest` |
| Master-off, user hit, role hit, neither, missing, malformed, duplicate, inactive-duplicate, client/org-only and unreadable have exact, fail-closed outcomes | `CohortConfigurationParserTest`, `CohortSelectorTest` |
| A failed configuration read is never cached and reports once per burst | `CohortSelectorTest` |
| Ticket signature, version, timestamps, session binding, identity completeness, single use, capacity and constant-time MAC comparison | `HandoffTicketCodecTest` |
| Key hygiene: ≥32 bytes, 0600, regular file, no placeholder, never rendered | `HandoffTicketCodecTest` |
| The reserved internal header namespace is refused, not stripped | `CohortRoutingFilterTest` |
| Route, method, header, cookie, timeout and failure rules are exact and fail closed | `PublicRouteClassifierTest`, `RoutedWebContractTest` |
| The reserved internal session-end signal is inside the reserved namespace, is refused inbound and is never forwarded to a browser | `RoutedWebContractTest` |
| Exactly one concurrent request may rotate a session, and exactly one may hold its ticket; the losers are refused explicitly and do not fail the session the winner is establishing | `PublicRouteClassifierTest`, `CohortRoutingFilterTest`, `RoutedWebContractTest` |
| A persisted affinity is either restored intact or restored as `FAILED`/`affinity-not-restorable`; the bearer ticket is never written to the persisted session; a restored affinity that cannot resume is still modern | `PublicRouteClassifierTest`, `RoutedWebContractTest` |
| A session whose recorded decision is `MODERN` but whose affinity is absent is refused with 503 and never handed to the legacy application; a `LEGACY` one is served normally | `CohortRoutingFilterTest` |
| While `REDIRECT_PENDING` is set, only `GET`/`HEAD` `.gif`/`.png` files under `/theme/default/images/` may pass through the legacy chain; the barrier and affinity stay unchanged, and `/zkau/view/`, ZK resources, pages, AU requests, unknown routes, path parameters, and write methods remain refused | `PublicRouteClassifierTest`, `RoutingCoreTest`, `CohortRoutingFilterTest`, `verifyPhase5eCohortContracts` |
| A routed session end has exactly one cleanup owner and one route-aware navigation owner. Page END uses an HTTP redirect, AU END uses the ZK `redirect` response, duplicates cannot issue conflicting navigation, committed responses do not consume ownership, and the next request is undecided | `RoutingCoreTest`, `CohortRoutingFilterTest` |
| The 8 MiB request and 64 MiB response caps are **enforced**, one byte on either side of the limit, nothing past the limit is written, and an oversized declared `Content-Length` is refused before the body is opened | `BoundedTransferTest`, `RoutedWebContractTest`, `verifyPhase5eCohortContracts` |
| An established modern session never returns to the legacy runtime | `CohortRoutingFilterTest`, `PublicRouteClassifierTest` |
| A deployment missing the router or the interceptor fails loudly | `CohortRoutingFilterTest` |
| The derived legacy WAR differs from the frozen one in exactly three reviewed entries, and both descriptors are the frozen text plus the reviewed inserts | `verifyPhase5eDerivedLegacyWar` |
| The derivation is byte-deterministic | `verifyPhase5eDerivedDeterminism` |
| The ZK 3.6 bridge and the Jakarta modern WAR share only the neutral protocol | `verifyPhase5eBridgeIsolation` |
| The reviewed contract files match the implementation | `verifyPhase5eCohortContracts`, `:zkwebui:routedContractTest` |
| Twenty-four reviewed mutations each **compile**, run their named test, and are detected by a failure in that test's own JUnit report | `verifyPhase5eMutationProof` |
| The installed product and both 394LTS archives carry the routed artifact, the pristine rollback material, the Context descriptor, and the modern archive its `docBase` **resolves to**; the superseded auto-deployed `tomcat10-api/webapps/webui-modern.war` and its manifest are gone; the deployed Tomcat 9 `webapps/webui.war` agrees with `lib/webui.war` and no stale exploded context survives; no key material anywhere | `verifyPhase5eRoutedOverlay` |
| Rollback restores the pristine logical digest, restores the Phase 5c/5d modern location and the deployed Tomcat 9 archive, removes the exploded context, **and survives the real Ant `setupWLib`** run with real merge inputs whose markers must appear in the rebuilt archive | `verifyPhase5eRollback` |
| Phase 4 SOAP contracts, route classification and XFire absence still hold | `phase4FinalVerification` (via `phase5dFinalVerification`) |
| The Phase 5d direct modern lane is unchanged | `phase5dFinalVerification` |

### Database-backed (`phase5eCohortRoutingSmoke`) — **executed and green**

The browser uses **only** the Tomcat 9 public origin and `/webui`; any direct
request to the loopback Tomcat 10 origin is aborted by the harness, so "the
modern runtime stays loopback-only" is enforced rather than hoped for.

The table below is the matrix the successful canonical gate recorded.

Two things this matrix deliberately does not claim:

* **It does not test ticket expiry, tampering, wrong-session binding or partial
  identity.** A browser cannot present a ticket at all — the router refuses the
  whole reserved header namespace before it routes — so such a row could only
  re-observe the reserved-header refusal under four other names. Those rules are
  proved directly against the codec by `HandoffTicketCodecTest` in the
  database-neutral gate above. `verifyPhase5eRoutedEvidence` fails if one of
  those rows reappears here.
* **It does not test replay.** A replay is a consumed ticket presented again,
  which again requires holding a ticket. The nonce rules are
  `HandoffTicketCodecTest`; the router's refusal to re-present a consumed ticket
  is `CohortRoutingFilterTest`.

| Case | Expected |
|---|---|
| Master switch off / absent | Legacy, no router or bootstrap traffic |
| User ID allowlisted | Modern through public `/webui` |
| Role ID allowlisted, user absent | Modern through public `/webui`. The allowlisted role is `AD_Role_ID` 102 — the role the `GardenAdmin` login actually selects — and the browser asserts the role it selected, so the row cannot pass or fail for the wrong reason |
| Role ID allowlisted that the user holds but does not select (`AD_Role_ID` 103) | Legacy. This is the negative twin of the row above: it is what proves the decision reads the **selected** role rather than the user's role list |
| Neither allowlisted | Legacy |
| Duplicate / malformed / unreadable system config | Legacy plus a bounded operator error |
| Inactive duplicate | Modern — an inactive row is not a second row |
| Client/org-only config row | Ignored; legacy |
| Client-supplied internal header | Refused with 400 |
| Bootstrapped session navigated again | Reused, not re-bootstrapped: same cookie value, one cookie, still modern |
| Theme image overlaps the role-selection redirect barrier | At least one cache-bypassed `GET /theme/default/images/zk/progress2.gif?r15=...` is recorded with `transition-safe-asset` while the marked barrier is live, at least one burst response is 200, the barrier remains owned by the deciding response, and the session reaches the modern desktop. Post-barrier requests may correctly receive the existing fail-closed `awaiting-context-root` response and are recorded rather than misclassified as barrier failures |
| Log out, then log in again in the same browser after the configuration stops selecting the user | Legacy, on a **new** container session. The decision is sticky per session and must not outlive it |
| Missing or inconsistent affinity | Explicit failure; never a runtime switch |
| Tomcat 10 outage during a modern session | Explicit failure and cleanup; never a legacy fallback |
| Interceptor omitted by mutation of the deployed archive | Fails visibly; no silent all-legacy pass |
| Concurrent identities A/B with different user, role and explicitly selected language, interleaved across a barrier | Each interleaved capture reproduces that identity's own solo capture exactly |
| Logout, session-inactivity timeout and container-side destruction | A destruction is recorded on every runtime that must have one, and every `SessionManager` cache a runtime reports for that session returns to its marked baseline |
| Phase 4 SOAP corpus while routed modern sessions are authenticated | Passes, and the routed session survives it |
| Secret hygiene over the real container logs and every evidence file | No ticket, no internal session identifier, no `;jsessionid=` |

### How the concurrency row observes a language

Each identity is driven to an explicit `AD_Language` by selecting it from the
login combobox ADempiere itself populates — a real `onSelect`, because
`LoginPanel` listens for nothing else, so typing a name and pressing Enter
changes nothing on the server.

What is then compared is a **server-produced ADempiere fact**: the header
control ADempiere labels from the `AD_Message` dictionary entry `Logout`, in the
session's own language. The earlier version read
`document.documentElement.lang`, which neither ZK 3.6 nor ZK CE 10 renders, so
it compared two empty strings and could never have failed.

Nothing hard-codes a translated string. Each identity is captured **alone**
first, and the interleaved capture must reproduce its own solo capture exactly;
the two solo captures must also differ on the language axis, so a selection that
silently did nothing fails the row instead of making it vacuous. `es_MX` is used
because it is the only non-base `AD_Language` the seed marks
`IsSystemLanguage=Y` — the only second language the login form actually offers —
and it carries `AD_Message` translations.

### How the lifecycle rows observe a destruction

Each row records a **mark** first: the current log offset per runtime, plus the
current post-mutation cache census. The action then runs, the capture waits for
a recorded session destruction on every runtime that must have one, and only
records written **after** the mark are read.

* **Logout** is the product's own Log Out control. The modern runtime marks the
  session ended, destroys it on the next routed request, and signals the router
  on the reserved response header; the router invalidates its own Tomcat 9
  session. Both runtimes must record a destruction.
* **Timeout** is the two-minute standard servlet session timeout declared in
  both routed WAR descriptors. ADempiere's `SessionManagerListener` preserves
  that container value when no optional ephemeral override is configured. The
  browser context is closed so nothing polls, and each container's reaper ends
  its session. Phase 5e adds no endpoint that can end a session on demand.
* **Container-side destruction** stops the modern container, whose session
  manager expires every session it holds. The public session must then be
  refused with an explicit status and never served the legacy application, and
  is left to the container — recycling it would be the fallback this phase
  forbids.

Readings are taken only from **post-mutation** records. ADempiere's
`sessionCreated` writes its per-cache lines *before* the session is inserted and
`sessionDestroyed` writes them *after* the cleanup, so the earlier version
compared two different points in the lifecycle. The modern listener now also
writes one machine-readable census line after the mutation on both ends, and the
frozen Tomcat 9 listener — which must not change, because it is the Phase 5b
oracle — is parsed at its own post-cleanup block, anchored on
`Destroyed Session Id`.

The mark and the observation read the **same class of evidence**: both take the
newest *destruction* record, so a mark can never land on an after-create census
and be compared against a post-removal reading.

#### Why a routed Tomcat 9 destruction owns no census

The frozen listener writes its seven cache lines only inside
`if (SessionManager.existsSession(id))`, and then always writes its own
`Invalidate Session : <id>` terminator. A routed session is deliberately no
longer registered by the time the container destroys it:
`CohortRoutingFilter.rotateAndTicket` calls `request.changeSessionId()` and
`discardLegacySessionState` drops every `SessionManager` entry the pre-rotation
identifier owned. `existsSession(rotatedId)` is therefore false and the block
carries **zero** cache lines. That is the correct shape of a routed destruction,
not a truncated read.

`session-cache-census.py` bounds every destruction block at evidence the block
owns — its own `Invalidate Session : <same id>`, the modern listener's census
line for the same session, or failing both the next `Create Session Id` or
`Destroyed Session Id` — and reports a cache-line-less block as a **recorded
destruction with no census**. An earlier version scanned to the next
`Destroyed Session Id`, which gave that block two ways to lie: with a
`sessionCreated` block following it adopted that block's seven **pre-insertion**
lines as its own post-cleanup reading, and with nothing following it never
reached seven values, was discarded, and the destruction was reported *absent* —
so a correctly cleaned-up routed logout looked either balanced against numbers it
never produced, or like a missing cleanup. Both faults are pinned by fixtures in
`scripts/phase5/fixtures/session-cache-census/`, exercised by
`scripts/phase5/session_cache_census_test.py`, and gated in the database-neutral
lane by `verifyPhase5eSessionCensusParser`.

Each reading therefore carries its **provenance** beside it — `census-line`,
`destruction-block`, `none` (a record this action owns, carrying no cache lines)
or `absent` (no post-mutation record at all) — and each runtime is judged on
evidence the routed session itself owns:

* the **modern** runtime writes its census unconditionally, so it owns a
  post-removal reading at both ends; its census must be present at the mark and
  at the observation and its seven values must be identical. That is the
  cache-balance assertion;
* the **public** runtime owns the destruction record and no census, so that
  record is what its row asserts. Reaching past it for an older session's numbers
  would be borrowing another session's reading. The state the routed session did
  perturb on Tomcat 9 is released at rotation, by `discardLegacySessionState`,
  and is covered by `CohortRoutingFilterTest` rather than by this log reading.

`absent` is never healthy, and `verifyPhase5eRoutedEvidence` fails the lane if a
lifecycle file records it or records no provenance at all.

Lane evidence (`build/phase5e/evidence/routed-lane.tsv`) records **observed**
values, not asserted ones. The bound listeners are read from the running
processes with `lsof`/`ss`/`netstat` rather than printed as a literal, the
modern context's HTTP status is recorded exactly as returned (403 is the healthy
armed state and is no longer rewritten to 200), and the handoff-key search
inspects archive entries — `war`, `zip`, `ear`, `jar` and `tar.gz` — as well as
the filesystem, because `find -name` cannot see inside an archive. The search
scope is recorded beside the answer so the claim cannot be read as wider than
the search.

## The focused session and context defects Phase 5e fixed

These were found by the concurrency and lifecycle work and are fixed in the
modern (`zkwebui/WEB-INF/src`) tree only. The frozen legacy archive keeps its own
copies unchanged, so the legacy cohort stays comparable with the Phase 5b oracle.

| Defect | File | Consequence before the fix |
|---|---|---|
| ZK's `Locales` thread local was set on every request and never cleared | `SessionContextListener` | A pooled request thread carried the previous session's locale into the next request until the next successful context install. Invisible in any single-language test; a cross-identity leak under concurrent sessions in different languages. |
| `setContextForSession` installed a `null` context when the session context had been removed | `SessionContextListener` | `Env.getLanguage(null)` and every later `Env` read on that thread. |
| `isValidContext` used `Optional.of` on a removable value and answered `true` when it was absent | `SessionContextListener` | `NullPointerException` for a concurrently destroyed session, and "valid" for a session with no context. |
| `getApplication(String)` dereferenced an absent cache entry | `SessionManager` | `NullPointerException` inside the destruction path, aborting it before the remaining caches were cleaned. |
| `sessionDestroyed` called `httpSession.invalidate()` on a session the container was already destroying | `SessionManagerListener` | Re-entrant destruction or `IllegalStateException`, either of which aborts the listener chain. |
| An unregistered session produced an empty HTTP 200 | `SessionTimeoutFilter` | A silent success for a request that was actually refused. Now an explicit 503. |
| A poll-timeout invalidation continued into `chain.doFilter` and `setAttribute` on the destroyed session | `SessionTimeoutFilter` | `IllegalStateException` hiding the timeout behind a stack trace. Now an explicit 408. |
| `loadUserPreference(Integer)` derived its cache key from the thread's `ServerContext`, which does not exist before ZK creates an execution | `SessionManager`, `CohortHandoff` | The handoff seeded preferences under the empty-string key, so `getUserPreference(sessionId)` answered `null` and `AdempiereWebUI.loginCompleted()` threw `NullPointerException` on the first routed desktop. There is now an explicit `loadUserPreference(String sessionId, Integer)` overload, the single-argument form refuses an empty key instead of caching under it, and `CohortHandoff.seed` installs the session's own context for the duration of the seeding and removes it again in a `finally`. |
| The cohort bootstrap marker survived logout and short-circuited straight to the desktop | `AdempiereWebUI`, `CohortHandoff` | A logged-out routed session re-entered `loginCompleted()` with an empty context instead of the login form. The short-circuit is now gated on the marker **and** a logged-in context, and logout forgets the seeded `Check_AD_User_ID` identity. The marker itself is kept on purpose: it is what lets the routed lane serve the post-logout redirect at all. |
| A routed logout ended the session on one runtime only, leaving the affinity and the sticky decision on the other | `AdempiereWebUI`, `CohortHandoff`, `CohortHandoffFilter`, `CohortRoutingFilter`, `HandoffProtocol` | The cohort decision is sticky per session by design, so a Tomcat 9 session that survived a logout carried the previous user's decision into the next login on the same browser: a user the configuration no longer selected stayed modern indefinitely, and no configuration change could get them back. Logout now marks the modern session ended, the modern filter destroys it on the next routed request and signals the router on `X-ADempiere-Handoff-End`, and the router invalidates its own session — destroying the affinity and the decision together — and redirects to the public context root. |
| `ModernSessionAffinity` and everything it referenced were not serializable, while Tomcat persists sessions across a context stop | `ModernSessionAffinity`, `CohortDecision`, `CohortIdentity`, `CohortDecisionInterceptor`, `CohortRoutingFilter` | A container that persists sessions drops an attribute it cannot write, silently. The session came back decided-modern with no affinity, and the router — which treated "no affinity" as "undecided" — handed the legacy application to a user who was logged in to the modern one. The state is now serializable; the bearer ticket is `transient` and any phase that depends on holding one is restored as `FAILED`/`affinity-not-restorable`; and the decision is additionally recorded as the runtime's **name**, so a lost affinity still fails closed with 503 instead of falling back. |
| The rotation and bootstrap phase was read and then acted on in two steps | `ModernSessionAffinity`, `CohortRoutingFilter` | A browser opens several connections at once. Two requests could both see `PENDING_ROTATION`, so the container's session identifier was changed twice, two tickets were minted, the second `ticketed()` threw `IllegalStateException` out of the filter as a 500, and the affinity was left bound to an identifier nothing used — a session that could reach neither runtime. `admit()` is now a single synchronized check-and-transition with explicit winner/loser semantics: exactly one request rotates, exactly one holds the ticket, and a loser is refused with 503 **without** failing the session the winner is still establishing. |
| The lifecycle baseline read creation-time cache lines and "destroyed" a session by clearing browser cookies | `SessionManagerListener`, `capture-routed-lane.sh`, `RoutedCohortMatrixTest` | `sessionCreated` logs the cache sizes *before* the session is inserted and `sessionDestroyed` logs them *after* the cleanup, so the comparison compared two different points in the lifecycle; and `BrowserContext.clearCookies()` does not touch a container session, so the destruction row asserted nothing at all. The modern listener now writes one census line after the mutation on both ends, the capture anchors both readings to a recorded log offset, each lifecycle row drives a mechanism the product itself owns, and a recorded destruction on every runtime that must have one is required before the caches are compared. |
| A cache-line-less routed Tomcat 9 destruction block was scanned to the next `Destroyed Session Id` | `session-cache-census.py`, `capture-routed-lane.sh`, `RoutedCohortMatrixTest` | A routed session is unregistered by the rotation, so the frozen listener records its destruction with **no** cache lines. Scanning past that block meant that with a `sessionCreated` following it, the block adopted that creation's seven **pre-insertion** cache lines as its own post-cleanup reading — the exact borrowed, wrong-point comparison the census line exists to prevent — and with nothing following it, the block never reached seven values, was discarded, and a real destruction was reported `absent`, so a correct routed logout timed out the `await`. Each block is now bounded at its own `Invalidate Session : <id>`, the modern census line for the same session, or the next `Create Session Id`/`Destroyed Session Id`; a cache-line-less block is a recorded destruction with **no census**; every reading carries its provenance; the mark reads the same class of evidence as the observation; and each runtime is judged on evidence the routed session owns — the modern census on the modern runtime, the destruction record on the public one. Pinned by `scripts/phase5/session_cache_census_test.py` over `scripts/phase5/fixtures/session-cache-census/` and gated by `verifyPhase5eSessionCensusParser`. |
| The `role-allowlisted` fixture allowlisted a role the acting login never selects | `reset-cohort-config.sh`, `RoutedCohortMatrixTest` | `GardenAdmin` holds `AD_Role_ID` 102 and 103 in the seed and logs in as 102, but the fixture allowlisted 103, so the row could only have passed by accident. The fixture now allowlists 102, the browser reads back and asserts the role the session actually runs as, and a new `role-unselected` row proves a held-but-unselected role does **not** select the modern cohort. |
| The concurrency row read `document.documentElement.lang` and never selected a language | `RoutedCohortMatrixTest` | Neither ZK version renders that attribute and `LoginPanel` listens only for `onSelect`, so the row compared two empty strings from two `en_US` sessions and could never have failed. Each identity is now driven to an explicit language through the combobox ADempiere populates, and the compared fact is the `AD_Message` `Logout` label ADempiere renders in the session's own language, calibrated against a solo capture of the same identity rather than a hard-coded translation. |
| `discardLegacySessionState` called context-dependent `SessionManager` cleanup from a request thread with no `ServerContext` | `CohortRoutingFilter` | `getApplication()` resolved nothing, so `clearSession` and `cleanSessionBackground` silently did none of their work: the abandoned `AD_Session` row stayed open and the ZK background thread kept running. The abandoned session's context is now installed for the cleanup and restored in a `finally`, and the `AD_Session` row is closed from that context directly rather than through a weak reference that may already have been collected. |
| `REDIRECT_PENDING` refused every request class, including immutable theme images | `PublicRouteClassifier`, `RoutingCore`, `CohortRoutingFilter` | PR 18 run 33696036502 measured a 503 for `/theme/default/images/zk/progress2.gif` while a sibling transition resource moved the browser to the context root. Transition safety is now a separate closed predicate, not an alias for `STATIC_ASSET`: only `GET`/`HEAD` `.gif`/`.png` files under `/theme/default/images/` pass through, without releasing the barrier or touching affinity, cookies, rotation, bootstrap, or tickets. |
| Every END response received an ordinary HTTP redirect and every concurrent request repeated cleanup/navigation | `ModernSessionAffinity`, `RoutingLifecycle`, `CohortRoutingFilter` | PR 18 run 33704883870 measured concurrent AU/page END responses followed by no rendered login form. A 302 returned to ZK AU/XHR does not own top-level navigation. Cleanup and navigation now have separate atomic owners; AU uses the ZK redirect command, page requests use HTTP redirect, duplicates return 204, committed responses leave navigation available, and a fresh `/webui/` request is undecided. |

## H1–H8 red-team outcomes

| Hazard | Fired? | What was done |
|---|---|---|
| **H1** incomplete quarantine/removal | **Fired** | The complete set is enumerated and verified: bridge compile inputs (SHA-512 pinned against the frozen WAR), the pristine and derived WARs, `lib/webuiOriginal.war`, installed and release trees, both 394LTS archives, the Tomcat Context descriptor, the handoff key, scripts and CI. `CohortBridgeStartupListener` fails deployment when the installer's `zkcustomization.jar` / 2Pack / `zkpatches.jar` merge precedence leaves a partial overlay. |
| **H2** framework-major mechanics | **Fired** | Three isolated compile closures; no blanket namespace rewrite. `javax.crypto` is Java SE and is deliberately *not* renamed. `verifyPhase5eBridgeIsolation` re-derives the separation from the packaged bytes. |
| **H3** runtime/deployment lockstep | Runtime **cleared**, topology **fired** | No JDK or Tomcat version change. The internal context mount, same-account key ownership, startup scripts, installer staging, both release archives and CI move together. |
| **H4** route-class gap | **Fired** | The public affinity unit is a closed contract with named refusals, including the two Phase 5f routes. Phase 4 SOAP is preserved and re-run under load. |
| **H5** stateful-store major | **Cleared** | PostgreSQL stays 14.6. The three `AD_SysConfig` rows are marker-owned and fixture-reset; the nonce cache is bounded and cleared. |
| **H6** transitional insecurity | **Fired** | Registered as **T5e-1** in `phase-5e-transitional-state.md`, closing in Phase 5h. |
| **H7** stacked PR / trunk drift | **Cleared** | `phase-5e-cohort-routing` was cut from `develop` at `b47464d2763694c093ed22470000e00f2b6aee73` with an empty `origin/develop..HEAD`. |
| **H8** living-doc drift | **Fired** | `MODERNIZATION_PLAN.md`, `ARCHITECTURE.md`, `README.md`, `.github/copilot-instructions.md`, the route and namespace ledgers, the Phase 3 topology, the residual-risk register and this file all move in the same change. |

## Residual risks after Phase 5e

- The compatibility layer, the router and the handoff remain transitional until
  Phase 5h.
- R15's deterministic code and browser evidence are implemented, but its
  canonical `phase5eFinalVerification`, `phase5eCohortRoutingSmoke`, Phase 5f
  smoke, and complete regression-matrix executions remain pending until the
  dedicated routing-hardening PR runs in GitHub Actions.
- A production customisation can still replace an installed descriptor. Phase 5e
  makes that a **startup failure** and an operator-visible constraint, but it
  cannot prove the content of an unknown customer overlay.
- Visual, write, report, upload/download, POS, dashboard and extension parity
  remain Phase 5g.
- Non-ZK route and context parity remains Phase 5f.
- The in-memory replay cache is per-JVM. A future multi-instance modern
  deployment would need a shared one; Phase 5e ships a single loopback instance
  and the capacity is documented against a single instance's login rate.
- CI may run without blocking merge until a repository administrator enables
  required status checks on `develop` (**R8**, unchanged).
