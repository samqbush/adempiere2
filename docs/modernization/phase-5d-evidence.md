# Phase 5d evidence

Phase 5d crosses the web UI Testability Milestone. The claim is specific, and
the parts of it that are *not* claimed are stated as plainly as the parts that
are.

## What is claimed

The ZK CE `10.3.0.1-jakarta` `/webui-modern` application, deployed beside the
unchanged Phase 4 CXF WAR in one loopback Tomcat 10.1.59 JVM, passes:

    ordinary GardenAdmin login -> role selection -> desktop/menu ->
    read-only "Error Message" window -> logout

and reproduces, at matching capture ordinals, **all eleven comparable frozen
legacy semantic facts** together with the zero-write database effect:

| Fact | Value |
|---|---|
| `role-labels-visible` | `true` |
| `desktop-user` | `GardenAdmin@GardenWorld.*` |
| `menu-user-browser` | `true` |
| `window-error-message-visible` | `true` |
| `window-error-message-tab-label` | `Error Message` |
| `window-error-message-readonly-columns` | `AD_Client_ID,AD_Language,AD_Org_ID` |
| `window-error-message-marked-columns` | `AD_Client_ID=readonly-field,AD_Language=readonly-field,AD_Org_ID=readonly-field,IsActive=mandatory-field` |
| `window-error-message-record-controls` | `Delete Selected Items=disabled,Delete record=disabled,New Record=enabled,Save changes=enabled` |
| `window-error-message-delete-controls-disabled` | `true` |
| `window-error-message-database-writes` | `0` |
| `logout-login-visible` | `true` |

The comparison set is reviewed in
`contracts/legacy-web-browser-v1/modern-comparable-facts.tsv`, which also records
why the four `filter-*` facts are **not** comparable: they describe Tomcat 9
contexts (`/adempiere`, `/mobile`, `/webui`, `/wstore`) that the modern runtime
does not deploy.

## What is NOT claimed

- **Not screen-level parity.** Eleven semantic facts matched. Nothing here says
  the modern screens look like the ZK 3.6 screens. The ZK 3.6 `.dsp` theme is
  deliberately not carried over, and Phase 5g owns visual parity.
- **Not route parity.** The modern descriptor maps the ZUL/ZHTML loader, the ZK
  AU engine and the ADempiere session filter, and nothing else. `*.dsp`,
  `/timeline`, every JSP/TLD asset and every non-ZK context remain Phase 5f.
- **Not report parity.** JasperReports' interactive web viewer
  (`net/sf/jasperreports/web/**`) is excluded from the modern runtime because
  every class in it links against `javax.servlet`. Residual **R4-5d-1**, closed
  by Phase 5g.
- **Not concurrency-proven.** One session at a time. Concurrent
  client/org/role/user/language isolation is Phase 5e.

## Artifact

`webui-modern.war` keeps the Phase 5c artifact name and the `/webui-modern`
context path, so the installed and release overlays, their SHA-256 manifests, and
the rollback rehearsal continue to work unchanged. Its contents are entirely
different:

| Layer | Source | Why from there |
|---|---|---|
| ZK CE closure | `modernWebZk` configuration | Every ZK artifact and ZK-owned transitive dependency is pinned by `gradle/verification-metadata.xml`. `zkwebfragment` is excluded: it contains no class, only a fragment that would register ZK's own `DHtmlLayoutServlet` instead of ADempiere's `WebUIServlet`. |
| ADempiere core | Ant-built `lib/Adempiere.jar` | The Phase 4 repackaging pattern: strip the code signature, drop the seven classes that link against `javax.servlet`. |
| ADempiere packages | installed `lib/packages.jar` | Same, with the two servlet-linked asset classes dropped. The one class both jars define (`org/compiere/model/MFreight`) is taken from `packages.jar`, reproducing the installed merge order in `install/Adempiere/build.xml:213-227`. |
| Shared runtime | installed, code-signed `lib/AdempiereSLib.jar` | The shipped server library, so `/webui-modern` runs the library versions the product ships rather than a second, silently different set. The reviewed allow-list is `gradle/phase5/modern-web-shared-runtime.tsv`. |

`verifyPhase5dModernWebWar` rejects: the Phase 5c marker in any form; ZK 3.6
runtime jars; `zkex`, `zkmax` or `org.zkforge` in any name or asset; vendor TLDs
and schemas; `jboss-web.xml`; packaged source or test trees; `.dsp` resources;
the `images_old` backup tree; a packaged `javax.servlet` or `jakarta.servlet`
API; stale JAR signatures; undeclared multi-release entries; and any class that
appears twice across `WEB-INF/classes` and `WEB-INF/lib`. It also requires 25
named login/session/menu/window classes and resources to be present, so an
archive that packaged only the framework cannot pass.

## Descriptors

Hand-written and reviewed route by route. Eclipse Transformer does not migrate
the Servlet 2.4 schema, which `gradle/phase5/transformer-rules.tsv` already
records as `manual-migration`.

`WEB-INF/web.xml` declares an empty `<absolute-ordering/>` so no library fragment
can inject an undeclared servlet, listener or mapping. `WEB-INF/zk.xml` differs
from the ZK 3.6 file in exactly three reviewed places: Comet server push
(Enterprise-only) becomes `org.zkoss.zk.ui.impl.PollingServerPush`, the four
`.dsp`-theme font properties are dropped, and the ZK 3.6 `i3-log.conf` monitor is
dropped.

## Defects the capture found

None of these were found by reading code. Each is fixed in ADempiere-owned code,
with a regression test or a reviewed contract row.

| Symptom | Cause | Fix |
|---|---|---|
| Every ADempiere keyboard shortcut silently dead; `setCtrlKeys: Unknown #enter` in the browser | ZK CE rejects the **entire** control-key specification on the first unknown extended key. `#enter` is a ZK 3.x `org.zkforge.keylistener` key that ZK CE's parser does not implement. | `Keylistener` translates the specification and re-implements Enter on ZK CE's `onOK`, still delivering key code 13. `Keylistener_Test` pins nine cases. |
| The same error again after the fix | ZK CE renders a component's own control keys by calling `getCtrlKeys()`, so the legacy value reached the client through the getter without any caller passing it to `setCtrlKeys`. | `getCtrlKeys()` returns the translated value; the caller's value moves to `getLegacyCtrlKeys()`. Pinned by `zkNeverSeesTheLegacySpecificationThroughTheGetter`. |
| Desktop never rendered: `Not allowed to set hflex and width at the same time` | ZK CE enforces "flex or size, not both" in **both** directions; ZK 3.6 let the flex win. | `ZkCompat.setFlex` leaves a child that declares an explicit size alone; `ZkCompat.setVflex`/`setHeight` clear the conflicting dimension at the nine call sites that set both. |
| Menu lookup visible but unclickable | `TreeSearchPanel` hard-coded a 20px row height that the ZK 3.6 11px `.dsp` font produced. ZK CE's taller combobox overflowed it and the tree below overlapped it. | The row sizes to its content with a 20px minimum and carries the ADempiere-owned `adempiere-tree-search` class. |
| Change Role and Log Out visible but unclickable | ZK CE pads a region body by 16px and sizes a North region to its content, so the desktop header had zero content height and the region body painted over it. `HeaderPanel`'s `position: absolute` user panel made it worse by leaving the flow. | The header region is sized explicitly to the 50px `theme.css.dsp` height, the Phase 5d stylesheet removes the inherited padding, and the user panel becomes `position: relative`. |
| `ReferenceError: $e is not defined` on every grid row change | `js/layout.js` still called the ZK 3.6 `$e()` and `zkau` globals. `GridPanel` sends `scrollToRow` on every row change. | `layout.js` migrated to `zk.Widget.$`/`$n()` with a `document.getElementById` fallback. |
| Page-level `SyntaxError: missing ) after argument list` | A pre-existing brace error in a script `GridTabRowRenderer` injects. ZK CE surfaced it as a page error. | The `each()` and `keyup()` calls are closed properly. |
| `NoClassDefFoundError: oracle/jdbc/rowset/OracleCachedRowSet` after role selection | `org.compiere.util.CCachedRowSet` **extends** `oracle.jdbc.rowset.OracleCachedRowSet` (`base/src/org/compiere/util/CCachedRowSet.java:42`), so every ADempiere runtime loads it regardless of the configured database. | `oracle/jdbc/**` and its five supporting namespaces are included, with the reason recorded in the shared-runtime contract. |

A related finding: ZK CE 10 logs through SLF4J 2, which is a no-op without a
provider. Until `slf4j-jdk14` was added to the WAR, a ZK server-side failure
reached the browser and nowhere else, and the first three defects above were
diagnosable only from client-side error boxes.

## Coexistence with Phase 4

The complete Phase 4 SOAP contract gate - four WSDLs compared byte for byte, all
33 frozen operation baselines and the 11 additional valid-credential and
security scenarios - is replayed **while a modern ZK session is authenticated in
the same JVM**, and the modern ZK desktop is asserted to survive it.

That runs in a third session, not in one of the two measured captures. The
reason is arithmetic, not convenience: the SOAP corpus authenticates 44 times,
every ADempiere login writes an `AD_Session` row, and
`contracts/legacy-web-v1/database-effects.tsv` allows a capture exactly one.
Folding the corpus into a measured capture would have required loosening the one
assertion that proves the flow logged in exactly once.

Recorded per capture, before and after the corpus: the loopback-only listener
set, `GC.heap_info`, the webapp classloader count (two - one per context, so the
ZK slice and the CXF WAR cannot see each other's classes), and the single
`ADEMPIERE_HOME` system property pointing at the guarded disposable installed
tree.

Lane isolation is measured with **both** containers running: distinct ports
(8888 and 8890), distinct PIDs, distinct `CATALINA_BASE` trees, exactly two
deployed modern contexts (`ADInterface.war`, `webui-modern.war`), and one shared
resource - the disposable PostgreSQL database, verified by reading the Phase 3
marker comment out of the database itself.

## Gates

```bash
./gradlew phase5dFinalVerification --dependency-verification=strict
./gradlew phase5dModernWebSmoke -Pphase3DbSystemPassword='<password>' \
  --dependency-verification=strict
```

`phase5dFinalVerification` deliberately does **not** chain
`phase5cFinalVerification`: that gate still asserts the 503 marker this phase
removed, so chaining it would either re-introduce a success-shaped placeholder or
pass on an assertion that is no longer true. The Phase 5c assertions that remain
valid are depended on individually.

`.github/workflows/main.yml` runs both canonical gates as separate fixed
`ubuntu-24.04` jobs and uploads compile, WAR, browser, database-effect,
coexistence, and rollback evidence. Required-check enforcement remains a manual
repository-administrator action.

## Evidence produced

| Path | Contents |
|---|---|
| `build/phase5/evidence/phase5d-modern-web-war.tsv` | WAR digest, entry and class counts, zero duplicates, provenance |
| `build/phase5d/evidence/modern-lane.tsv` | Lane readiness, loopback listener, disabled shutdown port |
| `build/phase5d/browser/A`, `.../B` | The two measured captures: semantic facts, route classes, browser errors, window observation, and before/after row counts plus full-row SHA-256 digests for every reviewed read-only table |
| `build/phase5d/browser/modern-vs-legacy.tsv` | The eleven-fact comparison against the frozen legacy baseline |
| `build/phase5d/browser/coexistence` | The SOAP corpus log and result, runtime evidence before and after it, and lane isolation |
