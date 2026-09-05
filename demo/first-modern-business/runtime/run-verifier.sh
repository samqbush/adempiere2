#!/usr/bin/env bash
set -euo pipefail

exec "$JAVA_HOME/bin/java" \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC \
  -DADEMPIERE_HOME=/opt/Adempiere \
  -DPropertyFile=/opt/Adempiere/AdempiereEnv.properties \
  -cp '/opt/demo/classes:/opt/Adempiere/lib/*' \
  org.adempiere.demo.FirstModernDemoVerifier
