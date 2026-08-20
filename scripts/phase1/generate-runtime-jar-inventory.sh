#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
output="$root/gradle/phase1/runtime-jars.tsv"
temporary="${output}.tmp"

{
  printf '# path\tclassification\treason\n'
  {
    git -C "$root" ls-files '*.jar'
    printf 'gradle/wrapper/gradle-wrapper.jar\n'
  } | LC_ALL=C sort -u | while IFS= read -r jar; do
    case "$jar" in
      gradle/wrapper/gradle-wrapper.jar)
        printf '%s\tsource-built\tPinned Gradle 8.10.2 wrapper bootstrap\n' "$jar"
        ;;
      tools/lib/*|lib/*)
        printf '%s\tquarantined\tShipped Ant/runtime binary retained pending coordinate reconciliation\n' "$jar"
        ;;
      *)
        printf '%s\tquarantined\tTracked legacy binary retained pending Phase 3 packaging parity\n' "$jar"
        ;;
    esac
  done
} > "$temporary"

mv "$temporary" "$output"
