#!/usr/bin/env bash
set -euo pipefail

installed_home=${1:?installed home is required}
zip_archive=${2:?release ZIP is required}
tar_archive=${3:?release TAR is required}
routing_dir=${4:?routing directory is required}
evidence_dir=${5:?evidence directory is required}

contexts=(
  "adempiereRoot.war|admin.war|admin-modern.war|admin.xml"
  "adempiereWebCM.war|ROOT.war|ROOT-modern.war|ROOT.xml"
  "mobile.war|mobile.war|mobile-modern.war|mobile.xml"
  "adempiereApps.war|adempiere.war|adempiere-modern.war|adempiere.xml"
  "adempiereWebStore.war|wstore.war|wstore-modern.war|wstore.xml"
)

rm -rf "$evidence_dir"
mkdir -p "$evidence_dir/installed" "$evidence_dir/zip" "$evidence_dir/tar"
cp -R "$installed_home/." "$evidence_dir/installed/"
unzip -qq "$zip_archive" -d "$evidence_dir/zip"
tar -xzf "$tar_archive" -C "$evidence_dir/tar"

rollback_home() {
  local home=$1 row legacy deployed modern descriptor original
  for row in "${contexts[@]}"; do
    IFS='|' read -r legacy deployed modern descriptor <<<"$row"
    original=${legacy%.war}Original.war
    cmp -s "$routing_dir/pristine/$legacy" "$home/lib/$original"
    cp "$home/lib/$original" "$home/lib/$legacy"
    rm -f "$home/lib/$original"
    rm -f "$home/tomcat10-api/phase5f/$modern"
    rm -f "$home/tomcat10-api/conf/Catalina/localhost/$descriptor"
    rm -rf "$home/tomcat10-api/webapps/${modern%.war}"
    rm -rf "$home/tomcat/webapps/${deployed%.war}"
    if [[ -f "$home/tomcat/webapps/$deployed" ]]; then
      cp "$home/lib/$legacy" "$home/tomcat/webapps/$deployed"
    fi
  done
  rmdir "$home/tomcat10-api/phase5f" 2>/dev/null || true
  rm -f "$home/config/phase5f-context-routing.sha256"
}

for home in \
  "$evidence_dir/installed" \
  "$evidence_dir/zip/Adempiere" \
  "$evidence_dir/tar/Adempiere"; do
  rollback_home "$home"
  for row in "${contexts[@]}"; do
    IFS='|' read -r legacy deployed modern descriptor <<<"$row"
    cmp -s "$routing_dir/pristine/$legacy" "$home/lib/$legacy" || {
      echo "$home did not restore canonical $legacy" >&2
      exit 1
    }
    [[ ! -e "$home/lib/${legacy%.war}Original.war" ]]
    [[ ! -e "$home/tomcat10-api/phase5f/$modern" ]]
    [[ ! -e "$home/tomcat10-api/conf/Catalina/localhost/$descriptor" ]]
    [[ ! -e "$home/tomcat/webapps/${deployed%.war}" ]]
  done
  [[ ! -e "$home/config/phase5f-context-routing.sha256" ]]
  [[ -f "$home/tomcat10-api/webapps/ADInterface.war" ]]
  [[ -f "$home/tomcat10-api/phase5e/webui-modern.war" ]]
  [[ -f "$home/tomcat10-api/conf/Catalina/localhost/webui.xml" ]]
  ( cd "$home" && shasum -a 256 --check config/phase5e-routing.sha256 \
      >/dev/null )
done

test_report="$installed_home/../../../../org.adempiere.cohort/build/test-results/phase5fRollbackCoreTest/TEST-org.adempiere.web.route.ContextRoutingDecisionTest.xml"
bridge_report="$installed_home/../../../../org.adempiere.cohort/build/test-results/phase5fRollbackBridgeTest/TEST-org.adempiere.web.context.ContextRoutingFilterTest.xml"
for report in "$test_report" "$bridge_report"; do
  [[ -f "$report" ]] || {
    echo "Missing rollback contract test report $report" >&2
    exit 1
  }
done
grep -Fq 'tests="4"' "$test_report"
grep -Fq 'failures="0"' "$test_report"
grep -Fq 'liveModernSessionIsInvalidatedAcrossDeployment' "$bridge_report"
grep -Fq 'failures="0"' "$bridge_report"

{
  echo $'surface\tresult'
  echo $'installed-contexts\trestored-canonical'
  echo $'release-zip-contexts\trestored-canonical'
  echo $'release-tar-contexts\trestored-canonical'
  echo $'phase5f-modern-contexts\tremoved'
  echo $'phase4-cxf\tpreserved'
  echo $'phase5e-webui\tpreserved'
  echo $'live-modern-session\tinvalidated-on-deployment-change'
  echo $'legacy-fallback\tforbidden'
} >"$evidence_dir/rollback.tsv"
cat "$evidence_dir/rollback.tsv"
