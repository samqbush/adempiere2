#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

printf '# kind\tlocation\tmatch\towner\n'
git grep -nI -E \
  '(import (sun|com\.sun|jdk\.internal)\.|Class\.forName\(|getDeclared(Method|Field|Constructor)\(|setAccessible\(|--add-(opens|exports)|java\.(version|specification\.version))' \
  -- '*.java' '*.gradle' '*.xml' '*.properties' '*.sh' '*.bat' '*.yml' '*.yaml' \
  ':(exclude)scripts/phase2/scan-jdk-internals.sh' |
  while IFS=: read -r file line text; do
    kind=reflection
    owner=UNCLASSIFIED
    case "$text" in
      *"import sun."*|*"import com.sun."*|*"import jdk.internal."*) kind=internal-import ;;
      *"--add-opens"*|*"--add-exports"*) kind=module-flag ;;
      *"java.version"*|*"java.specification.version"*) kind=version-gate ;;
    esac
    if [[ "$kind" == reflection ]]; then
      owner='Phase 2: reviewed application extension/reflection seam'
    fi
    case "$file" in
      glassfishfacet/*) owner='Phase 3: quarantined GlassFish adapter' ;;
      serverRoot/src/main/server/org/compiere/ldap/*) owner='Phase 2: LDAP BER replacement' ;;
      install/src/org/compiere/install/*|base/src/org/compiere/util/Login.java)
        owner='Phase 2: JDK validation/keytool replacement' ;;
      install/src/test/java/org/compiere/install/*)
        owner='Phase 2: JDK validation/keytool test coverage' ;;
      install/Adempiere/*|utils/*|build.xml|serverRoot/build.gradle|base/build.xml|.idea/*)
        owner='Phase 2: runtime/module flag review' ;;
      base/phase2-smoke/*)
        owner='Phase 2: runtime smoke test isolation' ;;
      base/phase3-metadata-validation/*)
        owner='Phase 3: metadata extension validation' ;;
      gradle/phase2/*|scripts/phase2/*)
        owner='Phase 2: JDK/runtime verification tooling' ;;
      scripts/phase3/*)
        owner='Phase 3: installed runtime verification tooling' ;;
      base/src/org/compiere/util/CLogMgt.java)
        owner='Phase 2: stack-trace text only' ;;
      base/src/org/compiere/util/EMail.java)
        owner='Phase 2: JavaMail implementation API, not a JDK internal' ;;
      base/src/org/adempiere/ad/services/impl/DeveloperModeBL.java)
        owner='Phase 2: user-code namespace filter' ;;
      base/src/org/compiere/util/CCachedRowSet.java|base/src/org/compiere/db/LDAP.java|base/src/org/compiere/model/MUser.java)
        owner='Phase 2: standard provider lookup review' ;;
      client/src/org/compiere/grid/ed/AutoCompletion.java|client/src/org/adempiere/plaf/AdempierePLAF.java|base/src/org/adempiere/process/rpl/exp/TopicExportProcessor.java)
        owner='Phase 2: legacy version branch review' ;;
      serverRoot/build.xml)
        owner='Phase 2: LDAP BER replacement' ;;
      tools/lib/ant/apache-ant-1.10.10/lib/libraries.properties)
        owner='Phase 2: Ant dependency property, not a JVM version gate' ;;
    esac
    match=$(printf '%s' "$text" | tr '\t' ' ' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+/ /g')
    printf '%s\t%s:%s\t%s\t%s\n' "$kind" "$file" "$line" "$match" "$owner"
  done |
  LC_ALL=C sort -t $'\t' -k2,2 -k1,1
