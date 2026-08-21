# ADempiere
  **Short status**:
![GitHub release (latest by date)](https://img.shields.io/github/downloads/adempiere/adempiere/3.9.4/total)
![GitHub code size in bytes](https://img.shields.io/github/languages/code-size/adempiere/adempiere)
![GitHub repo size](https://img.shields.io/github/repo-size/adempiere/adempiere)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/main.yml/badge.svg?branch=develop)](https://github.com/adempiere/adempiere/actions/workflows/main.yml)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/build_with_gradle.yml/badge.svg?branch=develop)](https://github.com/adempiere/adempiere/actions/workflows/build_with_gradle.yml)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/publish_with_gradle.yml/badge.svg?branch=3.9.4)](https://github.com/adempiere/adempiere/actions/workflows/publish_with_gradle.yml)
[![ADempiere Build](https://github.com/adempiere/adempiere/actions/workflows/release.yml/badge.svg?branch=3.9.4)](https://github.com/adempiere/adempiere/actions/workflows/release.yml)
 \
 \
**Issues and Pull Requests**:
![GitHub issues](https://img.shields.io/github/issues/adempiere/adempiere)
![GitHub closed issues](https://img.shields.io/github/issues-closed/adempiere/adempiere)
![GitHub pull requests](https://img.shields.io/github/issues-pr/adempiere/adempiere)
![GitHub closed pull requests](https://img.shields.io/github/issues-pr-closed/adempiere/adempiere)
 \
 \
**Social Media**:
[![Discord](https://badgen.net/badge/icon/discord?icon=discord&label)](https://discord.gg/T6eH6A7PJZ)
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/adempiere/adempiere?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

The _ADempiere Business Suite_ _ERP/CRM/MFG/SCM/POS_ is done the Bazaar way in an open and unabated fashion. \
Focus is on the Community that includes Technical Specialists, Functional Specialists, Implementors and End-Users. 

## Reproducible core build

The Phase 2 core/module gate uses the committed Gradle 8.10.2 wrapper on JDK 21
and publishes Java 21 bytecode:

```bash
./gradlew build verifyJava21Bytecode verifyTestClassification \
  verifyTestResults verifyPublicationContracts verifyJdkInternalApiInventory \
  verifyJdepsInternals --dependency-verification=strict
```

This covers the root and 28 included Gradle projects. It is not a replacement
for the full Ant distribution build. Ant-only web applications, installer and
database operations remain explicitly quarantined in
`gradle/phase1/quarantine.txt`.

The JDK 21 runtime walking skeleton can be exercised against disposable
PostgreSQL 14.6:

```bash
xvfb-run -a ./gradlew :base:phase2RuntimeSmoke \
  -Pphase2DbSystemPassword='<password>' \
  --dependency-verification=strict
```

This restores the committed seed, applies the `394lts` migrations, verifies the
database release, and runs the Swing, Groovy, and scheduler smokes. The target
must be local and disposable; tagged runtime objects are removed on success or
failure.

Release publication requires a new, previously unused version and declares JDK
21 as the minimum runtime. The empty-container compatibility bridge is pinned
to Tomcat 9.0.121 in `gradle/phase2/runtime.properties`.

- Official Page: http://www.adempiere.io
- Official Docs: http://adempiere.io/docs
- Download and debug source: https://www.adempiere.io/product/source-code.html#cloning-the-repository-with-a-slow-connection
- Business process: https://www.adempiere.io/product/business-process.html
- If you need to report a bug: https://github.com/adempiere/adempiere/issues
