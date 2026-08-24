# Phase 4 transitional state T4-1

**Status:** Closed after rollback rehearsal and XFire retirement.

During cutover, Phase 4 temporarily ran the legacy Tomcat 9/XFire SOAP implementation beside
the CXF 4.1.8/Jakarta EE 10 implementation on Tomcat 10.1.59. Tomcat 9 remains
the only public listener and owns both historical URL forms:

- `/ADInterface/services/*`
- `/ADInterface/servlet/XFireServlet/*`

The modern connector remains bound to `127.0.0.1:8890` and is not a second
public API. The Tomcat 9 compatibility router is its only caller. After the
recorded rollback rehearsal, the internal XFire mapping and runtime were
removed; both historical public forms now route only to CXF.

## Security and operational controls

| Control | Transitional rule |
|---|---|
| Authorization | Existing SOAP request-body and session authentication is unchanged; no permit-all, form login, HTTP Basic, or CSRF exception is introduced. |
| Request handling | The router accepts only the 33 inventoried operations, rejects malformed, ambiguous, DTD/external-entity, unknown, and oversized requests, and caps request bodies at 1 MiB. |
| Network | CXF binds to loopback only. Tomcat 9 remains the sole public ingress and the modern endpoint is not advertised in WSDLs. |
| Timeouts | Router connections and reads are bounded by explicit servlet initialization parameters. |
| Routing | During transition, missing, invalid, duplicate, or unreadable flags selected XFire. Request-scoped flags identified one service and operation; `ADService` had one atomic service flag and server-side session affinity. Final routing is CXF-only and does not read these flags. |
| Session data | The Tomcat 10 session identifier is stored only in the Tomcat 9 server-side session. It is not exposed as a second client cookie and mutable `ADService` state is never copied between JVMs. |
| Audit | Every proxied request records service, operation, and selected runtime in the Tomcat 9 server log. Invalid or duplicate configuration values and lookup failures emit warnings. No diagnostic routing header is added to the public response. |
| Contract | Both historical paths replay the frozen WSDL, HTTP status, stable headers, and SOAP body. |

## Ownership, rollback, and closure

- **Owner:** Phase 4 API/runtime workstream.
- **Rehearsed rollback switch:** set the affected
  `MODERN_SOAP_ADAPTER_ENABLED.<service>.<operation>` flag to `N`, or set
  `MODERN_SOAP_ADAPTER_ENABLED.ADService` to `N` for new `ADService` sessions.
  Existing sessions remained pinned to their selected runtime. This procedure
  was exercised for each request-scoped service and the atomic `ADService` unit
  through both public paths without restarting either runtime.
- **Residual risk:** the extra loopback runtime increases local process and
  dependency surface, and unknown consumers can still have timing assumptions
  beyond the frozen contract.
- **Closure:** Phase 4 task 4.8 routes all historical SOAP traffic to CXF,
  removes the router's XFire branch and XFire runtime artifacts, and retains
  only the public-path compatibility hop required until Phase 5.

The state is not approval for any authorization weakening or public exposure of
Tomcat 10.1.
