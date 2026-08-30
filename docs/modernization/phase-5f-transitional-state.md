# Phase 5f transitional-state register

T5e-1 remains unchanged and open. Phase 5f adds one bounded transitional state;
it approves no permit-all rule, CSRF disable, placeholder secret, scanner
bypass, public Tomcat 10 connector, or silent fallback.

## T5f-1 — multi-context proxying and forwarded secure state

| Field | Value |
|---|---|
| **What** | Tomcat 9 remains the only public ingress and proxies selected whole contexts to same-path applications on loopback Tomcat 10. For the four `/wstore` confidential paths it also conveys reviewed public scheme/secure metadata to a Tomcat 10 `RemoteIpValve`. |
| **Why needed** | Phase 5f must migrate non-SOAP contexts independently without exposing a second browser origin or retiring the Phase 5e session boundary. Tomcat 10 must know the original request was HTTPS so redirects and secure behavior use the public origin. |
| **Owner** | Phase 5f implementation owner |
| **Residual risk** | A broadened proxy classifier or trusted-forwarded-header boundary could expose an undeclared route, allow client spoofing of secure state, leak loopback coordinates, or route a modern failure to legacy. |
| **Closing phase** | **Phase 5h**, when Tomcat 9, the compatibility proxy/handoff, the Javax bridge, Eclipse Transformer, and ZK 3.6 are removed together and the Jakarta runtime becomes public ingress. |

### Mandatory controls

- Tomcat 10 connectors remain loopback-only and reject direct public use.
- Browser-supplied forwarding and reserved internal headers are refused, not
  trusted or silently stripped.
- Each context has an independent closed route classifier and request/response
  header allowlist.
- Public HTTP requests for `/wstore/login.jsp`, `/wstore/loginServlet`,
  `/wstore/checkOutServlet`, and `/wstore/orderServlet` redirect to the public
  HTTPS origin and are never proxied.
- Only Tomcat 9 may supply reviewed forwarded scheme/secure metadata; the
  Tomcat 10 trust boundary is loopback.
- `Location`, cookies, bodies, logs, and evidence never expose loopback
  host/port, an internal session identifier, or a handoff ticket.
- Existing sessions remain pinned. A decided-modern session with failed or
  missing affinity is refused; it is never served by legacy.
- Rollback invalidates modern sessions for that context and restores the
  checksum-pinned pristine WAR.
- T5e-1 ticket/key controls remain unchanged for `/webui`.

The database-neutral controls, topology and rollback are implemented and were
executed green twice through `phase5fFinalVerification`. The six-shard
public-origin and database-effect observations remain **unverified** because
`phase5fJakartaWebRoutesSmoke`, although executed in CI, has never passed. It
currently fails in `:startPhase5fRoutedLane` before any shard runs, and
`/webui` and `/wstore` have never been observed in any run. T5f-1 remains open and closes only in
Phase 5h; Phase 5f is not complete or merged.
