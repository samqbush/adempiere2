# First modern Business Partner demo

This bundle is the first portable demonstration of a real business write on
the modern ZK CE 10 runtime. It is a disposable, localhost-only evaluation
environment, not a production deployment.

The application preserves the accepted transition topology:

- public Tomcat 9 ingress at `http://127.0.0.1:8888/webui/`;
- loopback-only Tomcat 10 modern runtime at `127.0.0.1:8890`;
- PostgreSQL 14.6 on an internal Compose network;
- one generated handoff key shared by the two web runtimes;
- an instance-bound, marker-owned named database volume that only the matching
  bundle's `demo reset` may delete.

The bundle was introduced after Phase 5g-1b and its R18 correction were
accepted on `develop` by
[PR 21](https://github.com/samqbush/adempiere2/pull/21), merge commit
`0e5b42c18522eb6e3925d9a367ef8c348223e994`, and green post-merge run
[33932245213](https://github.com/samqbush/adempiere2/actions/runs/33932245213).
`contract.properties` and `provenance.json` identify the exact accepted
behavioral baseline and the source commit used to build a particular bundle.

## Host requirements

- Docker Engine with the Compose v2 plugin.
- A `linux/amd64` host is recommended. ARM hosts may use their normal amd64
  emulation, but startup will be slower and may be less stable.
- At least 8 GiB of available memory and 15 GiB of free disk for the two runtime
  images, database, and extracted product.
- Ports `8888` on loopback must be free.

Git, Java, Ant, Gradle, and repository sources are not required.

## Download and start

Download the `first-modern-business-demo-bundle` artifact from a successful
manual **First modern business demo bundle** workflow run on `develop`, then:

```bash
tar -xzf adempiere-modern-demo.tar.gz
./demo init
./demo up
./demo status
```

`demo init` creates `.demo-state/` with mode-restricted generated database,
keystore, instance, and handoff secrets. Those values are never part of the
uploaded artifact. `demo up` verifies `SHA256SUMS`, loads the two OCI images,
initializes the marked database volume, runs setup and migrations, configures
the accepted modern cohort, starts both Tomcats, and waits for bounded health.

Open `http://127.0.0.1:8888/webui/` and sign in with the disposable seed
credentials:

| Field | Value |
|---|---|
| User | `GardenAdmin` |
| Password | `GardenAdmin` |
| Client | `GardenWorld` |
| Role | `GardenWorld Admin` |
| Organization | `HQ` |

The browser must use the public URL above. Port 8890 is intentionally not
published and is not a valid demo entry point.

## Short presenter path

1. Open **Business Partner** from the menu.
2. Create a record with a unique Search Key such as
   `DEMO-LIVE-YYYYMMDD-HHMM` and a matching recognizable Name.
3. Set Business Partner Group to **Standard Customers**, Sales Representative
   to **GardenAdmin**, Price List to **Standard**, Payment Term to
   **Immediate**, and Dunning to **Dunning 30 days**.
4. Save. The record must remain visible with the assigned Search Key and Name.
5. Change the Name and save again. Refresh or search for the Search Key and
   confirm the new value is stored.
6. Run `./demo verify` in a terminal. It creates and reads back a separate
   uniquely named Business Partner through production model APIs and requires a
   linked workflow process, activity, event audit, and saving-user attribution.
7. Return to the browser, clear **Active**, save, and confirm the record becomes
   inactive/read-only.

The automated verifier is intentionally smaller than the accepted Phase 5g-1b
browser oracle. It proves the downloaded artifact's create/read-back/workflow
path; the accepted Phase 5g-1b gate remains the evidence for second-editor
concurrency, stale-save refusal, duplicate-submit behavior, and the complete
deactivation sequence.

## Full guided concurrency demonstration

Use a fresh unique Search Key and four private browser contexts:

1. In the primary `GardenAdmin` context, create and save the Business Partner.
2. Keep that record open. In a second context, sign in as `GardenUser` with
   password `GardenUser`, search for the same record, change its Name, and save.
3. In the still-open primary context, change the stale Name and save. The stale
   update must be refused rather than silently overwriting the second editor.
4. In a third `GardenUser` context, load the current row, change the Name, save,
   and immediately repeat the same Save submission. The duplicate submission
   must not create a second Business Partner.
5. In a fourth `GardenAdmin` context, load the row, clear **Active**, and save.
   The final row must be inactive.
6. Run `./demo verify` to add an independent artifact-level verification record
   and print its Business Partner and workflow process identifiers.

The full browser matrix is timing-sensitive and remains a CI acceptance test,
not a condition that every live presentation must repeat.

## Lifecycle

| Command | Behavior |
|---|---|
| `./demo init` | Generates private local state. Refuses to overwrite an existing state directory. |
| `./demo up` | Verifies bundle checksums, loads images, starts the topology, and waits for health. |
| `./demo status` | Shows Compose state, public URL, accepted commit, and accepted run without printing secrets. |
| `./demo verify` | Creates and reads back a unique Business Partner and verifies workflow attribution. |
| `./demo logs` | Prints both containers' logs without exposing the generated environment file. |
| `./demo down` | Stops containers without deleting the database volume. |
| `./demo reset` | Proves the exact instance-specific database marker and volume labels, removes only that bundle's named demo volume, then starts and verifies a pristine seed. |

To discard local generated secrets after the demo, run `./demo down` and remove
the local `.demo-state/` directory manually. This does not remove the named
database volume; use `./demo reset` first when a pristine database is required.

## Troubleshooting

- **`Demo state is absent`**: run `./demo init`.
- **Port 8888 already allocated**: stop the conflicting service. The reviewed
  artifact contract intentionally fixes the only published binding at
  `127.0.0.1:8888`.
- **Application health timeout**: run `./demo status`, then `./demo logs`.
- **Reset refusal**: do not rename or manually relabel the volume. The refusal
  is intentional; it prevents broad or ambiguous volume deletion.
- **Slow ARM startup**: allow additional time for amd64 emulation or use a
  native amd64 host.

Do not expose the public port beyond loopback, reuse the generated secrets, or
attach production data. This bundle has no production support or hardening
claim.
