# Phase 5g: disposition of the disabled and unowned web contexts

Status: governance amendment accepted in `5g-0`; each disposition is **open**
and must close before Phase 5h begins.

Owner: @samqbush.

## Why this document exists

Three of the six non-SOAP web contexts Phase 5f migrated are packaged but not
served to anyone:

| Context | Phase 5f enable state | Routes | Residual owner (5f contract) | Closing gate (5f contract) |
|---|---|---:|---|---|
| `/admin` | `legacy-unless-approved` | 4 | `phase5f` | `phase5fJakartaWebRoutesSmoke` |
| `/mobile` | `disabled` | 14 | `phase5g` | `phase5g-web-parity-gate` |
| `/adempiere` | `disabled` | 21 | `phase5g` | `phase5g-web-parity-gate` |

Source: `contracts/phase5f-jakarta-web-v1/enable-state-residuals.tsv`, which is
frozen under that contract's `manifest.sha256` and is **not** modified by this
amendment.

Phase 5h removes Tomcat 9. If these contexts are still legacy-only when 5h
begins, 5h has three choices and no prepared answer: migrate them unexpectedly,
retire them without evidence, or ship a break. This document exists so that does
not happen.

## What each context actually is

This matters, because two of the three are **superseded alternate UIs**, not
supporting infrastructure.

### `/adempiere` — the pre-ZK servlet web client (21 routes)

`WLogin`, `WMenu`, `WWindow`, `WProcess`, `WReport`, `WFindAdv`, `WLookup`,
`WLocation`, `WAccount`, `WAttachment`, `WChat`, `WHelp`, `WRequest`, `WStart`,
`WStatus`, `WTask`, `WValuePreference`, `WWorkflow`, `WZoom`, `WFieldUpdate`,
and the `WFilter` `/*` filter.

This is the servlet-and-JSP web client that the ZK `/webui` application
replaced. It is a *second complete web UI* for the same application dictionary.

### `/mobile` — the mobile variant of the same servlet client (14 routes)

`WLogin`, `WMenu`, `WWindow`, `WProcess`, `WReport`, `WFindAdv`, `WLookup`,
`WLocation`, `WHelp`, `WZoom`, `WFieldUpdate`, `LoginDynUpdate`,
`DisplayChart`, and the `WFilter` `/*` filter.

The same servlet family with a mobile rendering. It shares `/adempiere`'s fate
by construction: the two are not independently useful.

### `/admin` — server monitoring and Java Web Start (4 routes)

`AdempiereMonitor` and `AdempiereMonitorFilter` on `/adempiereMonitor/*`,
`StatusInfo` on `/statusInfo`, and `JnlpDownloadServlet` on `*.jnlp`.

This is **not** an alternate UI. It is operational surface: the server monitor
an administrator uses, and the JNLP descriptor that launches the Swing client
via Java Web Start. It has a different disposition question from the other two -
not "does anyone still use this UI?" but "which operator and deployment
workflows depend on this endpoint?"

## The amendment

### 1. Phase 5g does not enable these contexts by default

`MODERNIZATION_PLAN.md` R4 previously implied Phase 5g would close the disabled
contexts. It does not. They follow the `/admin` precedent - a context is not
enabled merely because it was migrated - and remain legacy until a named
consumer owns them.

The reasoning is the one already accepted for `/admin` and stated in the
project's architecture rules: *do not remove an old UI/API/application only
because it is old, and require usage and consumer evidence*. The same rule cuts
both ways. Enabling an unowned context is not the safe default either: it adds a
production surface, a maintenance obligation, and a parity claim nobody asked
for. For `/adempiere` and `/mobile` specifically, enabling them would commit
Phase 5g to proving screen-level parity for a **second and third complete web
UI** on top of the ZK one - roughly tripling the phase - for applications the ZK
client was built to replace.

### 2. `phase5g-web-parity-gate` is defined as the disposition gate

The Phase 5f contract already names `phase5g-web-parity-gate` as the closing
gate for `ENABLE-P5F-MOBILE` and `ENABLE-P5F-ADEMPIERE`. That gate did not
previously exist. This amendment defines it rather than contradicting the frozen
contract:

> `phase5g-web-parity-gate` is satisfied for a context when that context carries
> a **recorded disposition** - `migrate`, `retire`, or `narrow-5h-scope` - with
> the evidence that disposition requires, a named owner, and a closing
> increment. It is **not** satisfied by enabling the context, and it is **not**
> satisfied by leaving the decision open.

The gate is implemented in `5g-7`, the Phase 5g exit roll-up, and it fails while
any of the three contexts has an open disposition. Phase 5h is blocked behind
it.

### 3. The three permitted dispositions and the evidence each requires

| Disposition | Meaning | Evidence required before it may be recorded |
|---|---|---|
| `migrate` | The context is enabled on the modern runtime and carries full Phase 5g parity | Named consumer; runtime route and database-effect parity for every route in the context; screen-level parity where the context renders a UI; the context's own rollback rehearsal |
| `retire` | The context is removed from the product | Usage evidence showing no consumer, **or** a named owner accepting removal; a migration path for each named capability; a release note; removal of routes, WAR, descriptors and installer/topology entries in one change |
| `narrow-5h-scope` | Tomcat 9 removal in Phase 5h is narrowed so the context keeps running unmigrated | An explicit statement of what remains on the legacy runtime after 5h, the resulting residual risk, its owner, and the phase that closes it |

`narrow-5h-scope` is deliberately listed and deliberately unattractive. It is
the honest option if evidence cannot be obtained in time, and it is far better
than an undocumented survival. But it means Phase 5h does not fully retire
Tomcat 9, which is a material change to the roadmap and must be visible as one.

### 4. What evidence is still missing

Nothing in this repository can settle these dispositions. All three require
information from production or from the deployment owner:

- **`/adempiere` and `/mobile`:** is any deployment still serving the pre-ZK
  servlet client or its mobile variant? The seed and the source prove the
  contexts exist; they cannot prove anyone reaches them.
- **`/admin`:** which operator runbooks or monitoring integrations call
  `/adempiereMonitor/*` or `/statusInfo`, and does any distribution channel
  still serve the Swing client through `*.jnlp`? Java Web Start was removed from
  Oracle JDK 11, so the JNLP route's real-world reachability is itself in
  question and must be established rather than assumed in either direction.

`5g-7` may not record a disposition without this evidence. Recording
`retire` on the strength of "we did not find a caller in the source tree" would
be exactly the reasoning the architecture rules forbid.

### 5. Interaction with the Phase 5f runtime evidence

All three contexts were **observed** by the Phase 5f smoke - `/admin` 4
observations, `/mobile` 14, `/adempiere` 21, all passing in run 33379849664.
That is route-level behaviour on the modern runtime, and it is real.

It is not a parity claim, and it is not a reason to enable anything. A route
that returns the frozen legacy status and headers has been shown to *route*
correctly. Whether the resulting screens are usable, whether writes through them
are correct, and whether anyone wants them are separate questions this document
holds open.

## Residual risk

Registered against `MODERNIZATION_PLAN.md` R4. Until `phase5g-web-parity-gate`
passes, Phase 5g carries three contexts whose future is undecided, and Phase 5h
cannot plan its Tomcat 9 removal scope precisely. The containment is that 5h is
blocked behind the gate, so the ambiguity cannot silently survive into the phase
that would be broken by it.
