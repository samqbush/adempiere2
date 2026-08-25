# Phase 5c web ingress and session-affinity ADR

## Status

Accepted for the Phase 5 coexistence implementation. Phase 5c records and gates
this decision but does not enable cohort routing.

## Context

The installed product already has two runtimes:

- Tomcat 9 is the sole public ingress and serves the legacy web applications.
- Tomcat 10.1.59 is bound to loopback and serves the Jakarta CXF API.

Phase 5d adds the first functional ZK 10 slice. Phase 5e later introduces
role/user cohort routing. The legacy and modern ZK runtimes cannot share an HTTP
session or copy mutable desktop state between JVMs.

## Decision

### Public topology

Tomcat 9 remains the only browser-facing listener through Phase 5h. Tomcat
10.1.59 remains bound to `127.0.0.1`; it is never exposed as a second public
origin during coexistence. The modern web application shares the existing
Tomcat 10 process with the CXF API rather than introducing a third JVM.

Phase 5c stages only a packaging marker at
`/webui-modern/__phase5c/packaging`. It returns `503 Service Unavailable`, is
reachable only through the loopback connector, and is not a business,
readiness, or cohort-routing endpoint.

### Contexts and routes

The public browser path remains `/webui`. A later Tomcat 9 compatibility filter
will treat the following as one affinity unit:

- ZUL and ZHTML page loads;
- `/webui/zkau/*` asynchronous update traffic;
- ZK-generated JavaScript, CSS, images, and other static resources;
- polling-based server-push requests;
- redirects and URL-rewritten session forms.

The ROOT, `/adempiere`, `/mobile`, `/wstore`, `/admin`, and `/ADInterface`
contexts retain their existing ownership. Phase 5f owns non-ZK servlet/JSP/tag
migration. Phase 4 retains both historical SOAP paths and operation contracts.

### Cohort selection

Phase 5e will select a runtime from reviewed role/user `AD_SysConfig` flags.
Missing, invalid, duplicate, or unreadable values select the legacy runtime.
The decision is made only after ordinary authentication and role selection and
is sticky for the complete HTTP session. No request may switch an established
session between runtimes.

Phase 5c creates no flag, route switch, authorization exception, or diagnostic
response header.

### Session affinity

Tomcat 9 terminates the browser-facing session cookie. For a modern cohort,
Tomcat 9 stores the loopback Tomcat 10 session identifier only in the Tomcat 9
server-side session. A Tomcat 10 `Set-Cookie` header is consumed by the
compatibility layer and is not forwarded as a second browser cookie.

The compatibility layer must:

1. send the mapped modern session identifier only to the loopback runtime;
2. preserve cookie path, redirect, and URL-rewriting semantics at the public
   `/webui` path;
3. invalidate both runtime sessions on logout or Tomcat 9 session destruction;
4. never serialize, copy, or reconstruct mutable ZK desktop state between JVMs;
5. reject a missing or inconsistent affinity mapping rather than creating a
   request-scoped runtime split.

### Proxy and security constraints

The compatibility layer uses explicit loopback URLs, bounded connect/read
timeouts, and bounded request/response handling. It forwards only reviewed
headers and strips hop-by-hop headers. It must not introduce a permit-all rule,
authentication bypass, HTTP Basic/form-login fallback, CSRF exception,
placeholder credential, or public diagnostic route.

Every routed request records the selected runtime and stable route class in the
server log without logging credentials, cookies, request bodies, tenant data, or
the internal session identifier.

## Alternatives considered

### External reverse proxy

Rejected for this phase. It adds a new packaged runtime and cannot make the
role-aware cohort decision without another application-controlled handoff.

### Tomcat 10 as public ingress during coexistence

Rejected. It moves the public boundary before the modern UI crosses its
Testability Milestone and requires proxying the still-authoritative legacy
application in the riskier direction.

### Independent browser cookies for both runtimes

Rejected. Two browser-visible session cookies with overlapping paths make
affinity ambiguous and expose the loopback implementation as a public contract.

## Consequences

- Tomcat 9 remains a deliberate transitional dependency until Phase 5h.
- The compatibility layer must support ZK AU, resource, redirect, and polling
  behavior rather than only simple request/response proxying.
- Logout and destruction tests in Phase 5e become a two-runtime security gate.
- The Phase 5c packaging marker proves deployment only; it does not cross the
  modern UI Testability Milestone.

## Closing gates

- Phase 5d boots login, role selection, menu, and a read-only window on ZK 10.
- Phase 5e implements and tests fail-closed cohort routing and concurrent
  session cleanup.
- Phase 5h removes Tomcat 9, the compatibility layer, and the namespace
  transformer after the final source-native Jakarta cutover.
