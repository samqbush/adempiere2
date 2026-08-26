# Phase 5c evidence

> **Superseded in part by Phase 5d.** The HTTP 503 packaging marker and the
> `phase5cModernWebPackagingSmoke` task described below no longer exist:
> Phase 5d replaced the marker archive with the functional ZK CE 10 slice while
> keeping the `webui-modern.war` artifact name and the `/webui-modern` context
> path. This document is retained unchanged as the historical record of what the
> marker proved, because a phase's evidence is not improved by editing it after
> the fact. Everything else here - the transformer results, the overlay
> manifests, the verified browser tooling, and the rollback rehearsal - is still
> live and is depended on individually by `phase5dFinalVerification`.

Phase 5c establishes a dark/L1 Jakarta web packaging beachhead. It does not
claim that the modern ZK application compiles, boots, or serves users.

## Verified surfaces

- `:zkwebui` packages ZK CE `10.3.0.1-jakarta`, Jakarta Servlet 6 metadata, and
  only the loopback HTTP 503 marker.
- Eclipse Transformer 1.0.0 fixture output is deterministic. The report-only
  legacy-WAR scan accepted 19,328 resources, changed 206, and failed none after
  the reviewed malformed JRuby Rake fixture exclusion. No transformed
  production output ships.
- The existing Tomcat 10.1.59 process serves the unchanged Phase 4 CXF WAR and
  the additive marker WAR on loopback.
- The installed product and both 394LTS archives carry a separate SHA-256
  overlay manifest. Artifact rollback removes only that overlay and preserves
  the Phase 4 WAR and listener binding.
- Playwright Java 1.62.0 uses platform-specific, checksum-verified Chromium
  Headless Shell and FFmpeg archives. Playwright auto-download is disabled; the
  explicit installer fetches only manifest-pinned archives and verifies size
  and SHA-256 before extraction.
- The Tomcat 9 semantic oracle captures login, role, desktop/menu, logout,
  ZK-AU traffic, and all four filtered contexts twice with a marker-guarded
  fixture reset. Non-loopback requests are recorded and blocked.

## Gates

```bash
./gradlew phase5cFinalVerification --dependency-verification=strict
./gradlew phase5cRollbackRehearsal -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

Phase 5c CI uses fixed `ubuntu-24.04` jobs for the database-neutral packaging
gate and PostgreSQL 14.6 browser/rollback gate. Required-check configuration is
still a manual repository-administrator action.

## Residuals

- The four `/*` filter mappings remain `context-reachability-only`.
- Inherited 404 and page-error classes are reviewed, not fixed.
- Eclipse Transformer does not migrate the Servlet 2.4 descriptor schema.
- The modern WAR remains pre-testability; Phase 5d owns source migration and
  the first modern login/role/menu/read-only-window proof.
