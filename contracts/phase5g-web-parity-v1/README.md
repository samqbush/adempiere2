# Phase 5g web parity inventories (v1)

Reviewed Phase 5g-0 discovery output. These files answer two questions that had
to be settled **before** any Phase 5g write fixture could be designed:

1. Which extension-owned code actually runs during a web write?
2. What is a dictionary process, concretely enough to be a fixture?

The governing decisions are in `docs/modernization/phase-5g-web-parity-adr.md`.
The scope and constraints are in `MODERNIZATION_PLAN.md` under "Phase 5g
decomposition and active scope".

## Files

| File | Contents |
|---|---|
| `phase5g-process-classification.tsv` | All 351 `AD_Process` rows in the seed dictionary, classified by execution class |
| `phase5g-callout-columns.tsv` | All 174 `AD_Column` rows that declare a callout |
| `phase5g-extension-surfaces.tsv` | 197 Java classes that extend or render the web UI at runtime: 168 owned by extension reactor projects, 27 by `zkwebui` itself, and 2 by the per-client `extend` overlay |

Regenerate and validate:

```bash
python3 scripts/phase5/generate-phase5g-inventories.py \
  --repo-root . --output-dir /tmp/p5g
python3 scripts/phase5/validate-phase5g-inventories.py --repo-root .
```

Both run in about two seconds, read only committed files, start no container and
need no database. The validator compares the committed rows *and* the commentary
preamble exactly, and closes the directory listing, so neither an unreviewed row
nor an unreviewed file can join the contract unnoticed.

## What the inventories establish

### Process classification

| Execution class | Count | Phase 5g owner |
|---|---:|---|
| `java-process` | 215 | eligible for `5g-1e` |
| `report` | 110 | `5g-2` |
| `workflow` | 20 | the document increments (`5g-1c`, `5g-1d`) |
| `sql-procedure` | 4 | not scheduled; no Java body to migrate |
| `declaration-only` | 2 | executes nothing |

"A dictionary `AD_Process`" was never a sufficient fixture definition.
`ProcessPanel.runProcess()` constructs `ProcessCtl` directly and calls `run()`
(`zkwebui/WEB-INF/src/org/adempiere/webui/apps/ProcessPanel.java:688-689`), so
on the ZK path the process executes **synchronously on the request thread**.
`ProcessCtl.run()` (`client/src/org/compiere/apps/ProcessCtl.java:282`) contains
no threading; only `start()` (`:263`) spawns a thread, and the
`parent != null -> worker.start()` branches at `:134-138,205-210` live in the
two static `ProcessCtl.process(...)` overloads (`:92`, `:162`), which
`ProcessPanel` never calls. The completion signal on the ZK path is therefore the return of the call,
not a callback - and 110 of the
351 rows are reports whose parity belongs to a different increment entirely.

### The named `5g-1e` process fixture

**`AD_Process_ID` 295, `AD_Role_AccessUpdate` ("Role Access Update"),
`org.compiere.process.RoleAccessUpdate`.**

Driven with `AD_Client_ID=11` (GardenWorld) and `AD_Role_ID=102` (GardenWorld
Admin).

Chosen because it satisfies every property the fixture needs and few other
processes satisfy all of them:

| Requirement | How this process meets it |
|---|---|
| Non-report | `IsReport='N'`, no `JasperReport`; classified `java-process` |
| Core, not extension | `EntityType='D'` |
| Has parameters | `AD_Client_ID` (mandatory) and `AD_Role_ID` (optional), so `AD_PInstance_Para` marshalling is actually exercised |
| Writes rows | `updateRole()` calls `role.updateAccessRecords()`, which writes the `AD_*_Access` tables (`base/src/org/compiere/process/RoleAccessUpdate.java:101-104`) |
| Observable completion | `addLog(0, null, null, role.getName() + ": " + ...)` emits an `AD_PInstance_Log` line per role |
| Deterministic | Given a fixed client and role against a freshly restored seed, the access set it derives is a function of the dictionary alone |

Rejected alternatives worth recording:

- `AD_Sequence_Check` (258, `SequenceCheck`) — declares **no** parameters, so it
  would not exercise parameter marshalling at all, and against a freshly
  restored seed it may legitimately write nothing. A process fixture that can
  correctly produce a zero-row effect cannot distinguish "ran correctly" from
  "did not run".
- `C_BPartner Validate` (314, `BPartnerValidate`) — pairs thematically with the
  `5g-1a` fixture, but a validate-style process on clean seed data has the same
  zero-effect problem.

### Callouts on the fixture tables

The single most useful result for fixture design:

| Table | Callout columns | Owner |
|---|---:|---|
| `C_BPartner` (291) | **0** | — |
| `C_BPartner_Location` (293) | 1 | core (`org.adempiere.model.CalloutBPartnerLocation`) |
| `C_Order` (259) | 5 | core (`org.compiere.model.CalloutOrder`, `CalloutEngine`) |
| `C_OrderLine` (260) | 12 | core (`org.compiere.model.CalloutOrder`, `CalloutAssignment`) |

**The Business Partner window fires no callout at all.** That is why `5g-1a`
uses Business Partner as the first write oracle: its database effect is
attributable to the window and the model layer, with no callout arithmetic in
between. It is the simplest honest write in the product.

**No extension owns a callout on any fixture table.** All 17 callouts on the
Sales Order path are core. The Sales Order fixture's callout surface therefore
implicates no extension project.

### Model validators DO fire on the Sales Order path

This is the finding that changes `5g-1c` and `5g-1d` fixture design, and it is
not visible from source alone:

1. **`AD_MODELVALIDATOR` row 50000 registers
   `org.eevolution.model.LiberoValidator`** with `AD_Client_ID=0`, so it applies
   to **every** client, `IsActive='Y'`, and `EntityType='EE01'`, which is itself
   active in the seed. `LiberoValidator extends Libero`, and
   `org.eevolution.manufacturing/src/main/java/base/org/eevolution/manufacturing/model/validator/Libero.java:88`
   calls `engine.addDocValidate(MOrder.Table_Name, this)`.

   **An extension-owned validator from `org.eevolution.manufacturing` fires on
   Sales Order document validation.** Part of the `5g-1c` database effect is
   therefore owned by that extension, not by the order window, and the fixture
   must attribute it accordingly instead of treating the whole delta as core
   behaviour.

2. **GardenWorld (`AD_Client_ID=11`) registers
   `MODELVALIDATIONCLASSES='compiere.model.MyValidator'`.** The class exists at
   `extend/src/compiere/model/MyValidator.java` and registers both
   `addModelChange(MOrder.Table_Name, this)` and
   `addDocValidate(MOrder.Table_Name, this)` (`:75,77`).

   Its `modelChange` and `docValidate` bodies only log and return `null`
   (`:89-97`, `:108-130`), so it writes nothing — but it *is* on the path, it
   *is* invoked, and a future edit to it would silently become part of a Phase
   5g effect. It is recorded rather than assumed away.

Neither validator is on the Business Partner path, which reinforces the
`5g-1a` choice.

### Extension surface totals

| Surface | Count |
|---|---:|
| `process` (`extends SvrProcess`) | 129 |
| `zk-form` (`implements IFormController`) | 36 |
| `callout` | 21 |
| `model-validator` | 11 |

Concentrated in `org.eevolution.manufacturing` (49), `zkwebui` (27),
`org.eevolution.hr_and_payroll` (24) and `org.adempiere.asset` (19).

Two of the owners are not extension projects and are inventoried for specific
reasons. `zkwebui` contributes 25 of the 36 ZK form controllers and 2 of the 129
`process` rows - it is the web UI itself, scanned only because that is where a
ZK-facing extension surfaces. `extend` is the per-client
`MODELVALIDATIONCLASSES` overlay project, and contributes `CalloutUser` and
`MyValidator`; it is in scope precisely because the GardenWorld seed registers
`MyValidator`, so it sits on a Phase 5g fixture path.

Eleven model validators exist in the scanned source; only the two described
above are *registered* in the seed. The distinction matters: an unregistered
validator is inert, and Phase 5g scores what runs, not what could run.

## Consequences for the Phase 5g increments

- `5g-1a` (Business Partner CRUD oracle) has **no callout and no validator** on
  its path. Its effect is attributable without extension arithmetic.
- `5g-1c` and `5g-1d` (Sales Order) fire 17 core callouts and **two registered
  model validators, one of them extension-owned**. Their fixtures must declare
  this, and their effect models must attribute the `org.eevolution` portion
  rather than folding it into the window's.
- `5g-1e` uses `AD_Process_ID` 295 with a mandatory and an optional parameter,
  so parameter marshalling, `AD_PInstance_Log` output and a real row-writing
  effect are all exercised by one process.
- `5g-2` inherits 110 report-class processes.
- `5g-5` inherits 129 `process` rows - 127 extension-owned, 2 in `zkwebui` -
  and 36 ZK form controllers, of which 25 are `zkwebui`'s own and 11 are
  extension-owned.

## Disabled and unowned contexts

`/mobile`, `/adempiere` and `/admin` are **not** enabled by Phase 5g. Their
named dispositions, owner and closing increment are recorded in
`docs/modernization/phase-5g-disabled-context-disposition.md`.
