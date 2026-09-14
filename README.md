# ADempiere

[![ADempiere release downloads](https://img.shields.io/github/downloads/adempiere/adempiere/3.9.4/total)](https://github.com/adempiere/adempiere/releases)
[![Modernization CI](https://github.com/samqbush/adempiere2/actions/workflows/main.yml/badge.svg?branch=develop)](https://github.com/samqbush/adempiere2/actions/workflows/main.yml)

ADempiere is an open-source business suite covering ERP, CRM, manufacturing,
supply-chain management, and point of sale.

This repository is being modernized as a modular monolith. The
[modernization plan](MODERNIZATION_PLAN.md) is the authoritative source for the
current phase, completed work, open risks, and required verification. The
[architecture guide](ARCHITECTURE.md) describes the module topology and build
ownership.

## Run the modern Business Partner demo

The first portable modern-business demo exercises a real Business Partner
create, update, read-back, workflow-attribution, and deactivation path through
the modern ZK CE 10 web runtime.

The demo is a disposable, localhost-only evaluation environment. It is not a
production deployment.

### Requirements

- Docker Engine with the Compose v2 plugin.
- At least 8 GiB of available memory and 15 GiB of free disk.
- Port `8888` available on loopback.
- A `linux/amd64` host, or an ARM host using amd64 emulation.

Git, Java, Ant, Gradle, and repository sources are not required to run the
downloaded bundle.

### Download and start

1. Run the
   [First modern business demo bundle workflow](https://github.com/samqbush/adempiere2/actions/workflows/first-modern-business-demo.yml)
   manually on the `develop` branch.
2. Download its `first-modern-business-demo-bundle` artifact. Artifacts are
   retained for 14 days.
3. Extract and start the bundle:

   ```bash
   tar -xzf adempiere-modern-demo.tar.gz
   ./demo init
   ./demo up
   ./demo status
   ```

4. Open `http://127.0.0.1:8888/webui/` and sign in:

   | Field | Value |
   |---|---|
   | User | `GardenAdmin` |
   | Password | `GardenAdmin` |
   | Client | `GardenWorld` |
   | Role | `GardenWorld Admin` |
   | Organization | `HQ` |

5. Open **Business Partner**, create a record with a unique Search Key, save it,
   edit it, and save again. Run `./demo verify` to independently create and read
   back another Business Partner and verify its workflow attribution.

See the
[full demo guide](docs/modernization/first-modern-business-demo.md) for the
presenter path, concurrency demonstration, lifecycle commands, reset behavior,
and troubleshooting.

To download an artifact with the GitHub CLI after the workflow succeeds:

```bash
gh run download <run-id> \
  --name first-modern-business-demo-bundle \
  --dir ./build/demo-download
```

## Build from source

The supported Gradle build uses the committed Gradle 8.10.2 wrapper on JDK 21:

```bash
./gradlew build --dependency-verification=strict
```

This builds the Gradle modules and verifies their tests and publication
contracts. It is not yet a replacement for the complete Ant product build.

Build the full product without restoring a database:

```bash
ant build -Dnodbrestore=true
```

Build the full product with database restore and migrations only against an
approved disposable environment:

```bash
ant build -Dnodbrestore=false
```

Run the base unit tests:

```bash
ant -f tools/build.xml
ant -f base/build.xml unit-tests
```

Database-backed, installed-product, browser, container, and full phase gates
run in GitHub Actions. Discover available verification tasks with:

```bash
./gradlew tasks --group verification
```

The current required gates and their coverage are recorded in the
[modernization plan](MODERNIZATION_PLAN.md) and
[CI topology](docs/modernization/ci-topology.md).

## Run an installed product

These commands require an installed product and configured database:

| Action | Command |
|---|---|
| Desktop client | `./utils/RUN_Adempiere.sh` |
| Application server | `./utils/RUN_Server2.sh` |
| Restore seed database | `./utils/RUN_ImportAdempiere.sh` |
| Apply XML migrations | `./utils/RUN_MigrateXML.sh` |

Database restore and migration commands modify data. Inspect and approve the
target environment before running them.

## Project documentation

- [Modernization plan and current status](MODERNIZATION_PLAN.md)
- [Architecture and command ownership](ARCHITECTURE.md)
- [CI topology and gate coverage](docs/modernization/ci-topology.md)
- [Modern Business Partner demo](docs/modernization/first-modern-business-demo.md)
- [Modernization completion forecast](docs/modernization/modernization-completion-forecast.md)
- [Modernization evidence and design decisions](docs/modernization/)

## Community

- Official site: https://www.adempiere.io
- Documentation: https://www.adempiere.io/docs
- Business processes: https://www.adempiere.io/product/business-process.html
- Community chat: https://discord.gg/T6eH6A7PJZ
- Upstream issue tracker: https://github.com/adempiere/adempiere/issues
