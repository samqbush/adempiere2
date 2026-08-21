#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

expected_java=$(sed -n 's/^java.version=//p' gradle/phase2/runtime.properties)
expected_tomcat=$(sed -n 's/^tomcat.version=//p' gradle/phase2/runtime.properties)
test "$expected_java" = "21"
test -n "$expected_tomcat"

owned_files=(
  .github/workflows/build_with_gradle.yml
  .github/workflows/main.yml
  .github/workflows/publish_with_gradle.yml
  .github/workflows/release.yml
  install/Adempiere/AdempiereEnvTemplate.properties
)

if grep -nE "(java-version:[[:space:]]*['\"]?(11|17)([^0-9]|$)|jdk-(11|17)([^0-9]|$))" \
  "${owned_files[@]}"; then
  echo "Phase 2-owned runtime pin still references JDK 11/17" >&2
  exit 1
fi

grep -q "java-version: '$expected_java'" .github/workflows/build_with_gradle.yml
grep -q "java-version: '$expected_java'" .github/workflows/publish_with_gradle.yml
grep -q "java-version: '$expected_java'" .github/workflows/main.yml
grep -q "java-version: '$expected_java'" .github/workflows/release.yml
grep -q "JAVA_HOME=/usr/lib/jvm/jdk-$expected_java" \
  install/Adempiere/AdempiereEnvTemplate.properties
grep -q "tomcat.version=$expected_tomcat" gradle/phase2/runtime.properties
