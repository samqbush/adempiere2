# Phase 5 ZK/Jakarta Target ADR

## Status

Accepted for Phase 5 implementation.

## Context

The installed browser UI uses ZK 3.6.3, including checked-in CE, PE, EE, and
legacy add-on JARs. The source has 298 `org.zkoss`-referencing Java files. Its
direct commercial-package usage is bounded to:

- `org.zkoss.zkex.zul` layout classes;
- `org.zkoss.zkmax.zul.Filedownload`;
- `org.zkoss.zkmax.zul.Portallayout` and `Portalchildren`; and
- `org.zkoss.zkmax.ui.comet.CometServerPush` in `zk.xml`.

The final runtime is JDK 21 and Tomcat 10.1.59. Phase 5 must not require
unconfirmed commercial credentials or commit repository secrets.

## Decision

Use **ZK CE 10.3.0.1-jakarta** from the public ZK repository:

```text
https://mavensync.zkoss.org/maven2
```

Pin `10.3.0.1-jakarta` in dependency locks and verification metadata during
Phase 5c. Do not use the evaluation repository or `-Eval` artifacts in
production, and do not add `mavenLocal()`.

Use these mechanical replacements:

| Legacy API | Phase 5 replacement |
|---|---|
| `org.zkoss.zkex.zul.Borderlayout`, `Center`, `East`, `North`, `South`, `West` | Corresponding `org.zkoss.zul` CE classes |
| `org.zkoss.zkmax.zul.Filedownload` | `org.zkoss.zul.Filedownload` |
| `org.zkoss.zkmax.ui.comet.CometServerPush` | `org.zkoss.zk.ui.impl.PollingServerPush` during coexistence |
| `org.zkoss.zkmax.zul.Portallayout` / `Portalchildren` | An ADempiere-owned adapter implemented with CE layout components; semantic dashboard parity closes in Phase 5g |

The 3.6-to-10 migration uses the vendor's per-major upgrade notes as a recipe
ledger. Intermediate versions are compile/reference checkpoints, not shipped
runtimes. In particular, implementation must account for the ZK 5 rendering and
event-thread change, ZK 7 theme/DOM rewrite, Servlet 3.1+ web-fragment behavior,
and the Jakarta dependency suffix.

## Evidence

- The official release note identifies 10.3.0.1 as the current CE hotfix
  released on 2026-07-23:
  https://www.zkoss.org/product/zk/releasenote/10.3.0.1
- Official Jakarta guidance states that Jakarta artifacts use the
  `-jakarta` version suffix and require matching Jakarta Servlet dependencies:
  https://docs.zkoss.org/zk_installation_guide/getting_started_with_zk_jakarta
- Official Maven guidance identifies the public CE repository and LGPL license,
  while PE/EE production repositories require customer credentials:
  https://docs.zkoss.org/zk_installation_guide/maven_setup
- The official upgrade guide identifies the 3.x-to-5 architectural jump and
  later theme, API, and descriptor changes:
  https://docs.zkoss.org/zk_dev_ref/upgrade_tips/version_upgrade
- A repository-resolution probe confirmed public
  `org.zkoss.zk:zk:10.3.0.1-jakarta`,
  `org.zkoss.zk:zul:10.3.0.1-jakarta`,
  `org.zkoss.zk:zkplus:10.3.0.1-jakarta`, and
  `org.zkoss.zk:zhtml:10.3.0.1-jakarta`. The CE artifacts contain the layout,
  file-download, and polling-server-push replacement classes listed above.

## Alternatives considered

### ZK 10.3.1 evaluation artifacts

Rejected. The public metadata exposes `10.3.1-jakarta-Eval` only through the
evaluation repository. Evaluation artifacts are time-limited and are not a
reproducible production dependency.

### ZK PE/EE 10.x

Deferred, not selected. It would reduce work around portal layout and server
push, but requires stakeholder purchase/approval and authenticated repository
credentials. The current source can be migrated to CE with bounded adapters, so
commercial licensing is not a prerequisite.

### SPA rewrite

Dropped from Phase 5. It would replace metadata-driven UI behavior rather than
modernize the supported framework boundary and would greatly expand parity and
transaction-context risk.

## Consequences

- The migration has no ZK commercial repository secret or license dependency.
- Dashboard portal layout and server-push behavior require explicit parity
  work; they cannot be treated as import-only codemods.
- ZK 3.6 themes and DOM assumptions require reconstruction against ZK 10.
- All transitive ZK artifacts must be locked to matching Jakarta variants.
- LGPL obligations and notices must be preserved in distribution evidence.
- If the CE adapter fails approved functional or performance criteria, a later
  ADR may select PE/EE, but that is a stakeholder-approved change rather than a
  silent dependency substitution.
