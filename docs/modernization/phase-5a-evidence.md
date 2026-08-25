# Phase 5a inventory and target evidence

## Scope

Phase 5a is the database-neutral hand-off from the merged Phase 4 edge
modernization to the ZK/Jakarta web transition. It changes no shipped runtime,
dependency, route, or database behavior.

The branch is `phase-5a-web-inventory-and-target`, cut from `develop` at
`8c0ca4c1d6b35a5f366d6dd2150ed3bb27bc2a89`.

## Reviewed inventories

`scripts/phase5/generate-inventories.sh` generates the reviewed files below;
`verifyPhase5Inventories` fails on byte drift:

| Inventory | Reviewed result |
|---|---|
| `gradle/phase5/zk-sources.tsv` | 298 ZK-referencing Java files with module owner, API family, compile gate, behavior gate, and disposition |
| `gradle/phase5/zk-runtime-jars.tsv` | 39 checked-in `zkwebui` runtime JARs with manifest version, SHA-512, disposition, and closing gate |
| `gradle/phase5/web-assets.tsv` | 50 tracked JSP/tag/TLD/ZUL/ZHTML/DSP/XML assets with ZK or migration-owned `javax` references |
| `gradle/phase5/namespace-ownership.tsv` | 692 file/namespace ownership rows: 486 Java SE rows retained, 172 Jakarta web rows assigned to Phase 5f, and 34 non-web Jakarta rows explicitly deferred |
| `gradle/phase5/route-contracts.tsv` | 114 routes: 2 frozen Phase 4 SOAP routes and 112 Phase 5 non-SOAP routes |

The route contract adds deployment evidence to the Phase 4 classification.
Eighty-two non-SOAP routes are in the seven installed Tomcat contexts and 30
descriptor routes are not deployed by the Phase 3 Tomcat product. Presence in a
source descriptor is no longer treated as evidence that a route is active.

Phase 4 now derives descriptor count from
`gradle/phase5/web-descriptors.txt` and projects the first nine route-contract
columns when checking source-descriptor drift. It still requires exactly the
two historical SOAP mappings to remain service-to-service routes owned by Phase
4. No SOAP operation, fixture, or security assertion moved to Phase 5.

## ZK target decision

`docs/modernization/phase-5-zk-target-adr.md` selects public
**ZK CE 10.3.0.1-jakarta**. The decision avoids evaluation artifacts and
unconfirmed commercial repository credentials.

`verifyPhase5ZkTarget` downloads the four direct CE artifacts from the approved
public ZK repository, verifies committed SHA-512 values, proves the CE layout,
file-download, and polling-server-push replacement classes exist, and rejects
the old commercial package names.

The decision explicitly assigns the remaining behavior work:

- old `zkex` layouts -> `org.zkoss.zul`;
- old `zkmax` file download -> `org.zkoss.zul.Filedownload`;
- Comet server push -> CE polling during coexistence; and
- portal layout -> an ADempiere-owned CE adapter with Phase 5g parity.

## Hazard review

- **H1 fired:** all source files, web assets, checked-in web JARs, extension
  outputs, descriptors, and route dependents are reviewed inputs.
- **H2 fired:** ZK per-major API/theme/event-thread changes, Jakarta suffix,
  descriptors, JSP/Jakarta Tags, tests, and commercial API replacements are
  separate work.
- **H3 fired:** later runtime work must update Tomcat, installer, launch, CI,
  context, and release pins together; Phase 5a changes none.
- **H4 fired:** all 114 routes retain explicit traffic class, deployment
  evidence, auth enforcement, disposition, and closing gate; callback/webhook
  absence remains explicit.
- **H5 cleared:** PostgreSQL remains 14.6 and no persisted store changes.
- **H6 cleared:** no security weakening is introduced.
- **H7 fired:** Phase 5 uses sequential 5a-5h branches from `develop`.
- **H8 fired:** plan, architecture, README, Copilot instructions, Phase 4
  evidence, route ownership, and target ADR update together.

## Verification

Canonical database-neutral command:

```bash
./gradlew phase5aFinalVerification --dependency-verification=strict
```

Local result on the Phase 5a branch: **passed** on 2026-08-24. The gate ran
the Phase 3 no-database distribution, Phase 4 frozen contracts, route and
XFire-removal checks, SOAP business/router tests and modern WAR verification,
the reviewed Phase 5 inventory byte comparison, and the checksum-pinned ZK CE
target verification. No database-backed gate applies to this dark,
inventory/ADR-only increment.

New Phase 5 tasks become canonical only after this branch passes PR CI and
merges to `develop`. Required-check enforcement remains a manual repository
administrator action.
