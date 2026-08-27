# ADR: Phase 5e fail-closed cohort routing and authenticated handoff

Status: accepted (Phase 5e)
Supersedes: nothing. Extends `phase-5c-ingress-session-adr.md`, which chose the
single-public-ingress topology this ADR now populates.
Closing phase for the transitional state it registers: Phase 5h.

## Context

Phase 5d crossed the modern web UI Testability Milestone: the ZK CE
`10.3.0.1-jakarta` slice passes login, role selection, desktop/menu and the
read-only "Error Message" window on loopback Tomcat 10.1.59, reproducing eleven
frozen legacy semantic facts and the zero-write database effect.

It did so on a **direct** `/webui-modern` context. Three things were therefore
still unproven:

1. no public `/webui` request ever reached the modern runtime;
2. no user could be selected for it;
3. nothing about concurrent client/org/role/user/language isolation.

Phase 5e closes all three without retiring Tomcat 9, without a second public
origin, and without migrating a single Phase 5f or 5g route.

## Decision

### 1. Cohort configuration

Three system-level `AD_SysConfig` names, and nothing else:

| Name | Meaning |
|---|---|
| `MODERN_WEB_UI_ENABLED` | Exact value `Y` enables modern routing. Absent, or any other value, selects legacy. |
| `MODERN_WEB_UI_USER_IDS` | Strict comma-separated positive decimal `AD_User_ID` values. |
| `MODERN_WEB_UI_ROLE_IDS` | Strict comma-separated positive decimal `AD_Role_ID` values. |

Grammar: `|[1-9][0-9]{0,8}(,[1-9][0-9]{0,8})*` — empty, or positive decimals with
no sign, no whitespace, no leading zero and no repetition.

Rules, all fail-closed:

- Only rows at `AD_Client_ID=0, AD_Org_ID=0` are read. Client- and
  organisation-scoped rows are **ignored and reported**.
- Inactive rows are ignored entirely; they are not "a second row".
- Each key permits at most one active system-level row. A duplicate invalidates
  the **complete** configuration, not just that key.
- Malformed, null-valued or unreadable rows invalidate the complete
  configuration.
- An invalid configuration exposes **no** allowlist at all. A partially usable
  allowlist is the shape that silently routes the wrong people.

Precedence is strictly ordered: invalid → master → user allowlist → role
allowlist. Nothing else can select the modern runtime.

Because the grammar requires a *positive* identifier, the System user
(`AD_User_ID` 0) and the System Administrator role (`AD_Role_ID` 0) can never be
allowlisted. This is deliberate.

The three rows are loaded atomically through one statement in
`JdbcSysConfigRowSource`, not through `MSysConfig.getValue`. That helper returns
the first row a client-scoped query happens to produce and serves it from a
shared cache, so it cannot answer either question the decision depends on:
whether a second active system row exists, and whether the value was readable at
all.

The parsed configuration is cached for 15 seconds. A **failed** read is never
cached, and produces one operator error per 60-second burst rather than one per
login attempt. The database-backed matrix starts its private Tomcat 9 lane with
`adempiere.phase5e.configurationTtlMillis=0`, so each fixture login observes the
row set the test just installed; packaged and ordinary runtimes retain the
15-second production default.

### 2. Decision point and stickiness

The decision is taken once, by a ZK 3.6 `EventInterceptor`, on the first event
after which the session context carries a **completed role selection**.

"Completed" is a state, not an event name: `LegacyIdentity.read` requires
`#AD_User_ID`, `#AD_Role_ID`, `#AD_Client_ID`, `#AD_Org_ID`, `#AD_Language` and
`#M_Warehouse_ID` to all be present. An event-name check would be wrong in both
directions — the role panel's OK button fires long before `loginCompleted()`
finishes, and a deployment reaching the desktop by external authentication or a
role change would never be seen at all.

The decision is stored on the container session and never revisited. A
configuration change never moves an active session.

It is stored as the **runtime's name**, not as the affinity object. That matters
for the fail-closed rule below: a `String` is something every container can
persist, so a session whose affinity was dropped or could not be restored still
says "this session was decided modern".

**Sticky per session is not sticky per browser.** The decision is destroyed with
the session it was taken for. A routed logout therefore ends the session on both
runtimes (see §8), so the next login on the same browser starts undecided and is
decided again from the current configuration. Without that, a user the
configuration had stopped selecting stayed modern indefinitely and no
configuration change could move them back — a sticky decision outliving its
session is a stuck decision.

The allowlists are matched against the identity the session is actually running
as. In particular the role allowlist is matched against the selected
`AD_Role_ID`, never against the set of roles the user is entitled to; the routed
matrix carries a negative row for a role the acting user holds but does not
select, so this cannot regress into "any role the user has".

A **backstop** in the public router reports a fully authenticated session that
reached it with no recorded decision. That is exactly the shape a mutation
removing the interceptor produces, and it must be visible rather than presenting
as "everybody happens to be legacy today".

A session that was decided modern and arrives with **no affinity at all** is
refused with 503 rather than treated as undecided. Treating it as undecided was
the silent legacy fallback this phase exists to prevent: a container that
persists sessions drops an attribute it cannot serialize without saying so, and
the user would have been shown a different application while still logged in to
this one.

### 2b. Concurrency and persistence of the affinity

A browser opens several connections at once, so two requests routinely arrive on
one session before either finishes. `ModernSessionAffinity.admit()` is therefore
a single synchronized check-and-transition, and it is the only way a caller may
learn the phase in order to act on it:

| Phase on entry | Admission | Effect |
|---|---|---|
| `PENDING_ROTATION` | `ROTATE` | claims the rotation; phase becomes `ROTATING` |
| `ROTATING` / `BOOTSTRAPPING` | `IN_PROGRESS` | refused 503; the session is **not** failed |
| `AWAITING_BOOTSTRAP` | `BOOTSTRAP` | hands over the only ticket; phase becomes `BOOTSTRAPPING` |
| `BOOTSTRAPPED` | `PROXY` | ordinary proxied request |
| `FAILED` | `REFUSED` | terminal; never legacy |

The loser of a race is **refused**, not failed. Failing would let a losing
request destroy the session the winner is still establishing, which is a worse
outcome than the race. It is still an explicit status, never the legacy
application.

The affinity, the decision and the identity are `Serializable`, because Tomcat
persists sessions across a context stop and drops an attribute it cannot write.
The **ticket is `transient`**: it is a bearer credential and must not be written
to `SESSIONS.ser`. Consequently any phase that depends on holding one cannot
honestly resume, so `readObject` moves `ROTATING`, `AWAITING_BOOTSTRAP` and
`BOOTSTRAPPING` to `FAILED` with `affinity-not-restorable`.
`PENDING_ROTATION` carries nothing transient and `BOOTSTRAPPED` names a modern
session the other runtime may still hold, so both are restored as they were.

### 3. Context topology

| | Public | Internal |
|---|---|---|
| Ingress | Tomcat 9, the only public ingress | none |
| Legacy | `/webui` — derived `webui.war` | — |
| Modern | `/webui` — proxied | Tomcat 10.1.59 loopback, `/webui` via `conf/Catalina/localhost/webui.xml` |
| SOAP | — | Tomcat 10.1.59 loopback, `/ADInterface` (Phase 4, unchanged) |

The artifact name stays `webui-modern.war`. The **public and internal
application paths are identical**, which is the reason the proxy never rewrites
HTML, JavaScript, CSS or ZK asynchronous-update bodies: every URL the modern
application emits is already correct for the public origin.

In the routed lane the auto-deployed `webapps/webui-modern.war` is removed, so
exactly one modern UI context exists. The installed product and both 394LTS
archives are staged the same way rather than differently: the modern archive is
placed at `tomcat10-api/phase5e/webui-modern.war`, which is the path the
`Context` descriptor's `docBase` resolves to, and the Phase 5c/5d additive copy
at `tomcat10-api/webapps/webui-modern.war` — together with its
`config/phase5c-web-overlay.sha256` manifest — is removed. Staging the
descriptor beside a `docBase` nothing had staged, while also leaving an
auto-deployed copy of the same archive, would ship a product with one context
that cannot start and one that was never asked for.

`verifyPhase5eRoutedOverlay` resolves the `docBase` and requires the file to
exist in the installed tree and in both archives, so "the descriptor points
somewhere real" is checked rather than assumed. Phase 5e rollback restores the
Phase 5c/5d location, and the Phase 5c gates that own that location run before
Phase 5e stages over it. The Phase 5d direct `/webui-modern` lane boots its own
Tomcat from `zkwebui/build/libs/webui-modern.war` and remains an independent
regression gate.

### 4. Session tracking and cookies

- The modern `web.xml` declares `<tracking-mode>COOKIE</tracking-mode>` alone
  and `<http-only>true</http-only>`.
- The modern `Context` sets `disableURLRewriting="true"` and
  `sessionCookiePath="/webui"`.
- The derived legacy `web.xml` replaces its `<session-config>` with one that
  also declares `<tracking-mode>COOKIE</tracking-mode>`. The descriptor version
  stays at 2.4: raising it to 3.0 would additionally enable web-fragment and
  annotation scanning for an archive frozen without them.
- The router strips inbound `;jsessionid=` path parameters, and **refuses** a
  modern request that carries one.
- The browser holds exactly one cookie: `JSESSIONID` on the public `/webui`
  path, `HttpOnly`, `SameSite=Lax`, and `Secure` when the public request is
  HTTPS.
- The modern runtime's own `Set-Cookie` is consumed by the router. Only its
  identifier is retained, server-side, in the Tomcat 9 session.
- The Tomcat 9 session identifier is rotated **exactly once**, when a session is
  assigned to the modern cohort. The legacy cohort is unchanged and stays
  comparable with the frozen Phase 5b oracle.

### 5. Ticket protocol

Wire format: `v1.<payload-base64url>.<mac-base64url>`, where the payload is
`nonce|issuedAt|expiresAt|legacySessionId|user|role|client|org|warehouse|language`
and the MAC is HMAC-SHA-256 over `version.payload`.

| Parameter | Value | Why |
|---|---|---|
| TTL | 30 s | Far longer than a loopback request, far shorter than any human interaction. |
| Clock skew | 1 s | Both runtimes share one host and one clock. |
| Nonce | 256 bits | `SecureRandom`, base64url, unpadded. |
| MAC comparison | `MessageDigest.isEqual` | Constant time. |
| Replay capacity | 4096 | 31 s window × 20 logins/s documented ceiling = 620 live nonces; 4096 is 6.6×. |
| Exhaustion | refuse | Evicting a live nonce would make replay possible exactly under the load an attacker would create. |

`javax.crypto` is Java SE. It is identical on both runtimes and is deliberately
**not** subject to the `javax`→`jakarta` rename.

Validation order is fixed: MAC first, then version, timestamps, session binding
and identity completeness, and the nonce is consumed **last** — so a malformed
or expired ticket can never burn a nonce a valid ticket would have needed.

The ticket lives in the Tomcat 9 server-side session and is sent once, in
`X-ADempiere-Handoff-Ticket`, on the first proxied bootstrap request, alongside
`X-ADempiere-Handoff-Session`. Neither ever reaches the browser. The router
**refuses** — rather than strips — any browser request in the reserved
`X-ADempiere-Handoff-` namespace: stripping would make the attempt invisible.

A consequence worth stating because it decides where the ticket rules are
proved: **a browser can never present a ticket.** The reserved namespace is
refused before routing, so a browser-driven "expired ticket" or "tampered
ticket" case can only ever re-observe the reserved-header refusal under another
name. Expiry, tampering, wrong-session binding, partial identity and single-use
replay are therefore asserted directly against the codec by
`HandoffTicketCodecTest`, and the router's refusal to re-present a consumed
ticket by `CohortRoutingFilterTest` — both in the database-neutral gate. The
routed browser matrix asserts what a browser can actually observe: the
reserved-header refusal, and that a bootstrapped session is reused rather than
re-bootstrapped. `verifyPhase5eRoutedEvidence` fails if a duplicate ticket row
reappears in the runtime matrix.

### 6. Key

- Generated by `scripts/phase5/provision-handoff-key.sh` from the OS CSPRNG.
- At least 32 bytes; 48 by default.
- Mode `0600` from creation (created under `umask 077`, never chmod'ed after).
- Outside every archive under `ADEMPIERE_HOME`.
- No checked-in or generated placeholder exists, and `HandoffKey.load` rejects
  uniform or entirely-printable material so a placeholder cannot slip through.
- Never logged. `HandoffKey.toString()` renders `HandoffKey[N bytes]`.
- Absent or invalid on **Tomcat 9** → every new decision is legacy, with one
  rate-limited operator error. Tomcat 9 is the only public ingress; taking it
  down because the modern lane is misconfigured would turn a routing defect into
  an outage.
- Invalid on **Tomcat 10** → the modern context fails to deploy. A
  configured-but-unusable key means routed operation was intended, and serving
  an unverifiable modern UI would be worse than not deploying.

### 7. Proxy

Closed affinity unit (`contracts/phase5e-routed-web-v1/public-route-classes.tsv`):
context root, ZUL/ZHTML `GET`, `*.zul` `POST` (URL-rewritten public forms),
`/zkau` `GET`/`POST` including polling, `/zkau/web/**` resources, and reviewed
static prefixes. Everything else is `UNKNOWN` and is never proxied.

Request and response headers are **allowlists**
(`contracts/phase5e-routed-web-v1/proxy-header-policy.tsv`). `Host` is replaced,
hop-by-hop headers are dropped, `Cookie` is synthesised from the server-side
identifier, and `Set-Cookie` is consumed.

Bounds: 3 s connect, 30 s read, 90 s read for asynchronous updates (ZK CE's
polling server push holds a request open), 8 MiB request cap, 64 MiB response
cap, 8 KiB streaming buffer. Streaming rather than buffering is not an
optimisation: a buffering proxy on the public ingress turns every concurrent
modern session into retained heap on Tomcat 9.

The two byte caps are enforced by `org.adempiere.web.route.BoundedTransfer`,
which is in the transport-neutral closure rather than in the bridge. That is
deliberate: a cap whose only exercise would be pushing an oversized body through
a live container is a cap nobody runs, and an unrun cap is indistinguishable
from a missing one. `BoundedTransferTest` asserts both directions one byte on
either side of the limit, asserts that nothing past the limit is written to the
destination, and asserts that an oversized declared `Content-Length` is refused
before the body is opened. The documented values and the enforced constants are
compared by `verifyPhase5eCohortContracts` and `RoutedWebContractTest`.

**An established modern session never falls back to legacy.** An unknown route,
a ticket failure, a missing affinity or a dead backend produces an explicit
status. Showing a different application to a user who is already logged in to
this one is the specific defect that rule exists to prevent.

Audit lines carry the runtime, the stable route class and a closed outcome
token, and nothing else. `RoutingAudit.sanitised` runs on its own output, so a
future field that leaks fails where it is added rather than in a log nobody
reads.

### 8. Ending a routed session

A routed session exists on two runtimes, and only one of them ever observes the
user clicking Log Out. Ending it on that one alone leaves the Tomcat 9 session
holding the affinity **and** the sticky cohort decision, which is the stuck
decision described in §2.

The end is therefore signalled, on the internal hop only:

1. `AdempiereWebUI.logout()` marks the modern session ended. It does not
   invalidate it: ZK is mid-execution and still has to send its own redirect,
   and destroying the container session underneath it would abort that response.
2. On the next routed request, `CohortHandoffFilter` invalidates the modern
   session **before the chain runs** — so nothing is committed yet — and answers
   `205 Reset Content` carrying `X-ADempiere-Handoff-End`. That invalidation is
   what runs the modern `SessionManagerListener` cleanup.
3. `ModernBackendProxy` reads that header immediately after the response code
   and before a single byte of status, header or body reaches the public
   response, because the router still has to invalidate and redirect.
4. `CohortRoutingFilter` invalidates the Tomcat 9 session — destroying the
   affinity and the decision together, and running the legacy
   `SessionManagerListener` cleanup — and redirects the browser to the public
   context root, where a new session is created and the legacy login form is
   served.

The header is inside `X-ADempiere-Handoff-`, so a browser can never send one
(the router refuses the whole namespace inbound) and can never see one
(`ProxyHeaderPolicy` refuses to forward it outbound).

A **terminally failed** modern session is deliberately *not* ended this way. It
stays `FAILED` and keeps being refused until the container expires it; recycling
it would create a new undecided session and serve the legacy login form, which
is the fallback this phase forbids.

### 9. Compile isolation

Three closures, none of which can see the others' framework:

| Closure | Namespace | Compiled against |
|---|---|---|
| `:org.adempiere.cohort:main` | Java SE only | nothing |
| `:org.adempiere.cohort:bridge` | `javax.servlet` 4 + ZK 3.6 | classes extracted from the materialised frozen Phase 5b WAR, SHA-512 pinned in `gradle/phase5/bridge-classpath.tsv` |
| `:zkwebui:modernUi` | `jakarta.servlet` 6 + ZK CE 10 | the locked public ZK CE Jakarta target |

ZK 3.6 never enters reproducible repository resolution and never reaches the
modern runtime. The neutral protocol is compiled once and shipped to both, so
the two ends of the handoff cannot disagree about the wire format.

## Alternatives rejected

**A second public origin or a new reverse proxy.** Rejected: it doubles the
ingress attack surface, needs a second certificate and a second cookie domain,
and Phase 5h has to remove it again.

**Restoring a second ZK 3.6 source tree for the bridge.** Rejected: it
re-creates the dual-framework source tree Phase 5b exists to avoid, and the
frozen artifact already contains exactly the classes the bridge needs.

**Copying the encrypted password from `SessionManager.getUserAuthentication`.**
Rejected: it would put a recoverable credential on the wire, and it is not
needed — seeding the six identity values and running ADempiere's own
`Login.validateLogin` and `Login.loadPreferences` reaches the same server-side
state an ordinary role selection produces.

**Deciding on an event name.** Rejected: see §2.

**Evicting the oldest nonce when the replay cache is full.** Rejected: it makes
replay possible exactly under load.

## Consequences

- A transitional internal authenticated handoff exists until Phase 5h. It is
  registered as **T5e-1** in `docs/modernization/phase-5e-transitional-state.md`.
- Production customisations that replace `WEB-INF/web.xml` or `WEB-INF/zk.xml`
  now fail deployment loudly rather than silently un-registering the router or
  the interceptor.
- The legacy cohort's descriptor differs from the frozen one in exactly three
  reviewed entries, and the difference is recomputed rather than trusted.
- Rollback requires deleting `lib/webuiOriginal.war`, not merely restoring
  `lib/webui.war`; the rehearsal proves a later `setupWLib` cannot resurrect the
  overlay. The rehearsal runs the real `setupWLib` and `backupWebuiOriginal`
  bodies, read out of `install/Adempiere/build.xml` at run time, under Ant, with
  real merge inputs — a site customisation jar, a patches jar and a 2Pack
  package jar, each carrying a marker entry. The markers have to appear in the
  rebuilt archive, so a rehearsal that had degraded into an identity unzip/rezip
  fails. The observed precedence is recorded rather than assumed: Ant's
  `duplicate="preserve"` is first-seen-wins over the declared `zipfileset`
  order, and `zkcustomization.jar` is declared first, so a site
  `WEB-INF/web.xml` **wins** over the one inside `lib/webuiOriginal.war`. That
  is exactly why `CohortBridgeStartupListener` refuses to deploy a context whose
  descriptor lost the router. The rehearsal also records what the merge
  legitimately **drops**: Ant's `<zip>` writes no manifest and the installer's
  own `manifest.exclude` patternset excludes `META-INF/MANIFEST.MF` and the
  signature entries, so the rebuilt archive really is missing them. The
  allowed-drop set is read out of `install/Adempiere/build.xml` at run time, so
  any other dropped entry fails. A hand-written unzip/rezip rehearsal could not
  have shown this, which is the second reason it was replaced.
- Rollback also restores the deployed Tomcat 9 `webapps/webui.war` and removes
  its exploded expansion. A rollback that only restores `lib/` is undone by the
  next container start.
