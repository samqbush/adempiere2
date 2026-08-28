# ADR: Phase 5f Jakarta non-SOAP web routes

Status: accepted and implemented on `phase-5f-jakarta-web-routes`;
database-backed verification pending; not complete or merged

Extends:

- `phase-5e-cohort-routing-adr.md` without changing its `/webui` behavior;
- the Phase 5b 82-vector installed-product oracle;
- transitional state T5e-1, which remains open until Phase 5h.

## Context

Phase 5e merged to `develop` at `6eda2bc8`. Tomcat 9 is still the only public
ingress; selected `/webui` sessions reach the loopback Tomcat 10.1 runtime with
strict affinity and no legacy fallback. Phase 5f closes the non-SOAP route
inventory without retiring that boundary.

The governed scope is exactly 82 deployed mappings across six contexts:

| Context | Mappings |
|---|---:|
| `/webui` | 6 |
| `/admin` | 4 |
| `/` | 8 |
| `/mobile` | 14 |
| `/adempiere` | 21 |
| `/wstore` | 29 |

Thirty additional descriptor mappings are not deployed by the Phase 3 installed
product and therefore require explicit disposition rather than migration by
accident.

## Decision

1. Cut over whole contexts, never individual routes. Existing sessions remain
   pinned; sessionless requests follow the context switch without creating a
   session; established modern sessions fail explicitly and never fall back.
2. Keep Tomcat 9 public and Tomcat 10 loopback-only. Public and internal context
   paths are identical; response bodies are not rewritten.
3. Use the extracted ZK-free routing core for expanded contexts, while retaining
   the frozen Javax/ZK 3.6 `/webui` adapter and both unchanged Phase 5e gates.
4. Give every context an independent allowlisted header policy, cookie policy,
   byte limits, timeouts, TLS rule, lifecycle rule, rollback artifact, and
   enable state. Unknown values are fail-closed. `/webui` policy is not a
   default for another context.
5. Enforce the four `/wstore` `CONFIDENTIAL` paths
   (`/login.jsp`, `/loginServlet`, `/checkOutServlet`, `/orderServlet`) at the
   public ingress. HTTP redirects to the public HTTPS origin; it is never
   proxied. Only reviewed secure/scheme metadata crosses loopback, and internal
   coordinates never appear in `Location`.
6. Use source-native Jakarta Web namespaces only for the manifest-owned Phase
   5f closure. Java SE `javax.*` and the Phase 5e bridge remain. Modern
   descriptors are hand-written Servlet 6, metadata-complete, explicitly map
   every servlet/filter/listener, and carry empty `absolute-ordering`.
7. Precompile all 25 retained `/wstore` JSPs. Use pinned Jakarta Tags libraries;
   ship no legacy servlet/JSP namespace or old taglib URI.
8. Do not restore DSP. Serve the one historical theme DSP URL as the exact
   reviewed Phase 5d CSS for GET/HEAD; return 404 for every other `*.dsp`; ship
   neither the interpreter nor its five vendor TLDs.
9. Correct four frozen legacy errors only through
   `known-deviations.tsv`: AdRedirector 400, Community 400, XML missing resource
   404, and plain GET payment 405. Any additional difference needs a reviewed
   row before implementation.
10. Drop all eight JBoss HTTP-invoker mappings and all twenty mappings in the
    non-deployed `serverApps/src/etc/WEB-INF/web.xml`. Defer both Jasper
    `GetMD5File` mappings to Phase 5g; none may appear in a Phase 5f shipped
    descriptor.
11. Package and replay `/mobile` and `/adempiere`, but keep their production
    enable state off until Phase 5g. `/admin` stays legacy without named consumer
    ownership. `/` and `/wstore` become eligible only after their complete
    Phase 5f gates pass.
12. Rollback restores the pristine context WAR and invalidates that context's
    modern sessions. It never moves a live modern session to legacy.
13. Build the migrated servlet/JSP closure from isolated generated source and
    web trees under `build/phase5f/jakarta-web/`; do not rewrite the legacy
    source or assets. Package five deterministic source-native WARs for
    `/admin`, `/`, `/mobile`, `/adempiere`, and `/wstore`; retain the Phase 5e
    `webui-modern.war` for `/webui`.

## Consequences

- Phase 5f remains lit at L3: contract, mutation, database-backed, rollback, and
  coexistence gates are mandatory, while full UI/report parity remains Phase 5g.
- T5e-1 stays open. New multi-context proxy and forwarded-secure-state risk is
  registered as T5f-1 and also closes in Phase 5h.
- Numeric policies and route-specific database-effect ownership are frozen and
  database-neutrally enforced. Runtime observations remain pending until the
  six-shard database-backed smoke executes.
- `phase5fFinalVerification` is implemented and green twice. The implemented
  `phase5fJakartaWebRoutesSmoke` has not run because
  `phase3DbSystemPassword` is unavailable, so Phase 5f is not complete.
- The installed product and both 394LTS archives stage exactly one copy of each
  of the five Phase 5f WARs under `tomcat10-api/phase5f/`, preserve Phase 4 CXF
  and Phase 5e `/webui`, retain pristine rollback WARs, and reject stale
  exploded/auto-deployed modern contexts.
