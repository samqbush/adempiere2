# Phase 5e transitional state register

One transitional state is approved by this phase. Nothing else in Phase 5e
relaxes a security control, and no permit-all, CSRF disable, open endpoint,
placeholder secret, scanner bypass or disabled check is introduced.

## T5e-1 — internal authenticated handoff between Tomcat 9 and Tomcat 10

| Field | Value |
|---|---|
| **What** | A signed, single-use, 30-second ticket carries an already-authenticated ADempiere identity from the public Tomcat 9 ingress to the loopback Tomcat 10 modern runtime, so the user does not authenticate twice. |
| **Why it is needed** | Tomcat 9 remains the only public ingress until Phase 5h, and the modern runtime must not re-prompt a user who has already completed login and role selection. The alternative — copying the encrypted password out of `SessionManager.getUserAuthentication` — would put a recoverable credential on the wire. |
| **Reason it is not a general auth bypass** | The ticket authenticates a *server*, not a user. It can only be minted by a Tomcat 9 session that has already completed ordinary authentication and role selection, and it can only be consumed once, within 30 seconds, from loopback, by a session that does not yet exist. |
| **Owner** | Phase 5e implementation owner |
| **Residual risk** | Anything that can read the key file *and* reach the loopback connector *and* forge a matching session binding could create a modern session for an arbitrary identity. That set is "a process running as the ADempiere account on the application host", which can already read `AdempiereEnv.properties` and connect to the database directly. The handoff therefore does not widen the trust boundary; it depends on it. |
| **Closing phase** | **Phase 5h**, when Tomcat 9 is retired, the compatibility layer is removed and the modern runtime becomes the public ingress. The ticket, the key, the router and the bridge are all deleted together. |

### Controls that bound it

| Control | Where |
|---|---|
| Loopback-only modern connector | `scripts/phase4/prepare-tomcat10.sh`, asserted by `startPhase5eRoutedLane` |
| Loopback-only ticket acceptance, independently of the connector | `CohortHandoffFilter.loopback` |
| HMAC-SHA-256, constant-time comparison | `HandoffTicketCodec` |
| 30 s TTL, 1 s skew | `HandoffProtocol` |
| Single use, bounded, fail-closed replay cache | `ReplayCache` |
| Bound to the rotated Tomcat 9 session, asserted at both ends | `CohortRoutingFilter`, `CohortHandoffFilter` |
| Session-end signal is inside the reserved namespace, refused inbound and never forwarded to a browser | `HandoffProtocol.END_HEADER`, `ProxyHeaderPolicy`, asserted by `RoutedWebContractTest` |
| Exactly one concurrent request may rotate or hold the ticket; losers are refused, not admitted | `ModernSessionAffinity.admit` |
| The ticket is never written to a persisted session; a persisted in-flight handoff is restored as failed | `ModernSessionAffinity` (`transient` ticket, `readObject`) |
| Complete identity required; no defaulting | `CohortIdentity` |
| Reserved header namespace refused on every browser request | `CohortRoutingFilter.carriesReservedHeader` |
| First request of an unbootstrapped session only | `CohortHandoffFilter` |
| 0600 key, ≥32 bytes, outside every archive, never logged, no placeholder | `HandoffKey`, `scripts/phase5/provision-handoff-key.sh` |
| Invalid key on Tomcat 10 fails deployment | `CohortHandoffFilter.init` |
| Invalid key on Tomcat 9 keeps every new session legacy | `CohortBridge.initialise` |
| No public diagnostics or status endpoint exists | reviewed route contract; every non-affinity route is `UNKNOWN` |
| No secret in any log or evidence file | `scripts/phase5/capture-routed-lane.sh secrets` |

### What would make this state unacceptable

Any of the following must fail the phase rather than be accepted:

- a ticket accepted from a non-loopback peer;
- a ticket accepted twice;
- a ticket accepted on a session that is already bootstrapped;
- a persisted session resuming a handoff whose ticket it no longer holds;
- a decided-modern session with no affinity being served the legacy application;
- a key readable by group or other, or shipped in an artifact;
- a ticket, an internal session identifier or a `;jsessionid=` visible in a
  browser URL, a response header, a response body, a container log or an
  evidence file;
- a public route outside the reviewed affinity unit reaching the modern runtime.

Each of these has a named assertion in either `phase5eFinalVerification` or
`phase5eCohortRoutingSmoke`.

`phase5eCohortRoutingSmoke` has **not yet been executed** (it requires a
disposable PostgreSQL 14.6). The assertions it owns — loopback-only acceptance
observed on a running connector, secret hygiene over real container logs, and
the public-origin route refusals — are therefore **specified but unverified**.
This transitional state is not fully bounded until that run is recorded in
`docs/modernization/phase-5e-evidence.md`.

One control above is also narrower than its name suggests, and is recorded here
rather than left to be discovered: the "outside every archive" key check is a
search, and a search has a scope. `start-routed-lane.sh` searches the
filesystem under `ADEMPIERE_HOME` and the entries of every `war`, `zip`, `ear`,
`jar` and `tar.gz` it finds there, and records that scope beside its answer. It
does not compare key *content* against archive members, so a key packaged under
an unrelated name inside an archive would not be found by it. What prevents that
is the provisioning script, which never writes the key inside `ADEMPIERE_HOME`,
plus the overlay gate's refusal of any `*.key` or `*handoff*` file in a staged
tree or release archive.
