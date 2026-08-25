# Phase 5c transitional-state register

Phase 5c introduces no active authorization, authentication, CSRF, secret,
scanner, or public-network weakening.

| Surface | Phase 5c state | Closing phase |
|---|---|---|
| Public ingress | Tomcat 9 remains the sole public listener. | Phase 5h |
| Modern web runtime | Tomcat 10.1.59 remains loopback-only. Its packaging marker returns HTTP 503 and is not routed publicly. | Phase 5d replaces the marker with the first functional slice. |
| Cohort routing | ADR only; no `AD_SysConfig` flag or routing implementation exists. | Phase 5e |
| Session affinity | ADR only; no second browser cookie or cross-JVM desktop-state copy is introduced. | Phase 5e implements the tested mapping. |
| Namespace transformer | Build tooling only. No transformed production class is installed or shipped. | Phase 5h |
| Browser runtime | External browser archives are verified against committed platform-specific checksums before installation. Runtime auto-download is disabled. | Retained while the browser gate remains canonical. |

Any implementation change that weakens one of these constraints must update this
register with its reason, owner, residual risk, and mandatory closing gate before
merge.
