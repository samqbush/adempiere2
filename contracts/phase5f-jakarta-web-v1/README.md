# Phase 5f Jakarta non-SOAP web contract

Status: **reviewed database-neutral contract; runtime observations pending**.

This contract fixes the Phase 5f governance boundary before Java, Gradle,
descriptor, packaging, or runtime work begins. It is additive and does not
change the frozen Phase 5b oracle or the accepted Phase 5e routing contract.

## Normative files

| File | Purpose |
|---|---|
| `deployed-routes.tsv` | Closed list of all 82 deployed non-SOAP route mappings and their Phase 5f disposition. |
| `non-deployed-dispositions.tsv` | Closed list of all 30 descriptor mappings absent from the Phase 3 installed product: eight JBoss drops, twenty duplicate-descriptor drops, and two Jasper deferrals. |
| `context-policy-schema.tsv` | Per-context routing, header, cookie, TLS, byte-limit, timeout, lifecycle, and rollback policy schema. |
| `header-policy.tsv` | Closed common and per-context request/response header decisions. |
| `cookie-policy.tsv` | Context-isolated public session cookies and the only three application-cookie crossings. |
| `tls-routes.tsv` | The four `/wstore` `CONFIDENTIAL` routes and public-origin/loopback metadata rules. |
| `session-routing-cases.tsv` | Sessionless, pre-switch, no-fallback, lifecycle, rollback, and cross-context cases. |
| `route-validation.tsv` | Deterministic 82-route join to the Phase 5b request vectors and Phase 5 route classifications. |
| `database-effect-ownership.tsv` | Per-route effect owner, reset boundary, and fail-closed unowned-write rule. |
| `jsp-precompile-contract.tsv` | Closed list of the 25 retained JSPs; execution remains pending. |
| `known-deviations.tsv` | Four reviewed legacy-error corrections plus the DSP compatibility decision. |
| `enable-state-residuals.tsv` | Which contexts may be enabled in Phase 5f and which must remain legacy until later evidence exists. |
| `mutation-cases.tsv` | Closed ten-case proof for omissions, unsafe policy changes, fallback, stale routes, and dispositions. |
| `hazard-register.tsv` | H1-H8 red-team result and required closing evidence. |
| `manifest.sha256` | SHA-256 freeze over every normative file above. |

`deployed-routes.tsv` joins normatively to
`../legacy-web-v1/context-request-vectors.tsv` by `route_id`; that file remains
the source of the legacy probe method, status, proof strength, and response
capture. It also joins to `gradle/phase5/route-contracts.tsv` by `route_id` for
descriptor, implementation, traffic class, and authentication enforcement.
Phase 5f may strengthen those observations but may not silently remove or
reclassify a row.

## Freeze rules

1. The deployed inventory must contain exactly 82 unique `route_id` values:
   `/webui` 6, `/admin` 4, `/` 8, `/mobile` 14, `/adempiere` 21, `/wstore` 29.
2. The non-deployed inventory must contain exactly 30 unique source mappings:
   8 `drop-jboss-http-invoker`, 20 `drop-superseded-descriptor-duplicate`, and
   2 `defer-phase5g-jasper-web`.
3. Every limit and timeout is a reviewed positive literal. The gate rejects an
   unresolved marker, a non-numeric boundary, or a fallback policy.
4. No context may inherit `/webui` headers, cookies, byte limits, or timeouts by
   default. Every context policy is independent.
5. No new deviation is allowed without a new reviewed row.
6. `/mobile` and `/adempiere` remain disabled through Phase 5f. `/admin` remains
   legacy unless its infrastructure consumers are owned and approved.
7. Runtime fields remain explicitly `pending`; database-neutral validation must
   never rewrite them into observations. The database-backed smoke owns that
   later evidence.
8. `./gradlew phase5fOracleContractValidation
   --dependency-verification=strict` regenerates the two derived ledgers,
   validates all joins and counts, verifies the manifest, and proves all ten
   named mutations are rejected. Its outputs are deterministic TSV files under
   `build/phase5f/oracle-contracts/`.
