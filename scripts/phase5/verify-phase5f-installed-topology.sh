#!/usr/bin/env bash
set -euo pipefail

installed_home=${1:?installed home is required}
release_home=${2:?release home is required}
zip_archive=${3:?release ZIP is required}
tar_archive=${4:?release TAR is required}
routing_dir=${5:?routing evidence directory is required}
modern_war_dir=${6:?modern WAR directory is required}

contexts=(
  "admin|adempiereRoot.war|admin.war|admin-modern.war|admin.xml|/admin"
  "ROOT|adempiereWebCM.war|ROOT.war|ROOT-modern.war|ROOT.xml|/"
  "mobile|mobile.war|mobile.war|mobile-modern.war|mobile.xml|/mobile"
  "adempiere|adempiereApps.war|adempiere.war|adempiere-modern.war|adempiere.xml|/adempiere"
  "wstore|adempiereWebStore.war|wstore.war|wstore-modern.war|wstore.xml|/wstore"
)

digest() {
  shasum -a 256 "$1" | awk '{print $1}'
}

verify_server_xml() {
  python3 - "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
connectors = root.findall(".//Connector")
if not connectors:
    raise SystemExit("Tomcat 10 declares no connector")
for connector in connectors:
    if connector.get("address") not in {"127.0.0.1", "::1"}:
        raise SystemExit(
            "Tomcat 10 connector is not loopback-only: %r" %
            connector.attrib)
PY
}

verify_home() {
  local home=$1 label=$2
  ( cd "$home" && shasum -a 256 --check \
      config/phase5e-routing.sha256 config/phase5f-context-routing.sha256 \
      >/dev/null )
  verify_server_xml "$home/tomcat10-api/conf/server.xml"

  [[ -f "$home/tomcat10-api/webapps/ADInterface.war" ]] || {
    echo "$label lost the Phase 4 CXF WAR" >&2
    exit 1
  }
  cmp -s "$home/lib/ADInterface-Modern-1.0.war" \
    "$home/tomcat10-api/webapps/ADInterface.war" || {
    echo "$label deployed CXF WAR differs from the canonical Phase 4 WAR" >&2
    exit 1
  }
  local soap_xml
  soap_xml=$(unzip -p "$home/lib/ADInterface-1.0.war" WEB-INF/web.xml)
  grep -Fq '<url-pattern>/services/*</url-pattern>' <<<"$soap_xml"
  historical_soap_pattern='<url-pattern>/servlet/'X'FireServlet/*</url-pattern>'
  grep -Fq "$historical_soap_pattern" <<<"$soap_xml"

  [[ -f "$home/tomcat10-api/phase5e/webui-modern.war" ]]
  [[ -f "$home/tomcat10-api/conf/Catalina/localhost/webui.xml" ]]
  [[ ! -e "$home/tomcat10-api/webapps/webui-modern.war" ]]
  [[ ! -e "$home/tomcat10-api/webapps/webui-modern" ]]

  local row name legacy deployed modern descriptor context original \
    descriptor_file doc_base resolved count cookie_name
  for row in "${contexts[@]}"; do
    IFS='|' read -r name legacy deployed modern descriptor context <<<"$row"
    original=${legacy%.war}Original.war
    cmp -s "$routing_dir/pristine/$legacy" "$home/lib/$original" || {
      echo "$label does not preserve canonical $original" >&2
      exit 1
    }
    cmp -s "$routing_dir/legacy/$deployed" "$home/lib/$legacy" || {
      echo "$label does not stage the deterministic routed $legacy" >&2
      exit 1
    }
    cmp -s "$modern_war_dir/$modern" \
      "$home/tomcat10-api/phase5f/$modern" || {
      echo "$label does not stage the built $modern" >&2
      exit 1
    }
    descriptor_file="$home/tomcat10-api/conf/Catalina/localhost/$descriptor"
    [[ -f "$descriptor_file" ]] || {
      echo "$label is missing $descriptor" >&2
      exit 1
    }
    grep -Fq 'disableURLRewriting="true"' "$descriptor_file"
    cookie_name=$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]')
    grep -Fq "sessionCookieName=\"JSESSIONID_$cookie_name\"" "$descriptor_file"
    grep -Fq "sessionCookiePath=\"$context\"" "$descriptor_file"
    grep -Fq 'internalProxies="127\.0\.0\.1|::1"' "$descriptor_file"
    if grep -Eq 'https?://|localhost:[0-9]|127\.0\.0\.1:[0-9]' \
        "$descriptor_file"; then
      echo "$label $descriptor exposes an internal origin" >&2
      exit 1
    fi
    doc_base=$(sed -n 's/.*docBase="\([^"]*\)".*/\1/p' \
      "$descriptor_file" | head -1)
    resolved=${doc_base//\$\{catalina.base\}/$home/tomcat10-api}
    [[ "$resolved" == "$home/tomcat10-api/phase5f/$modern" &&
      -f "$resolved" ]] || {
      echo "$label $descriptor has an unresolved docBase: $doc_base" >&2
      exit 1
    }
    count=$(find "$home/tomcat10-api" -type f -name "$modern" | wc -l |
      tr -d ' ')
    [[ "$count" == 1 ]] || {
      echo "$label stages $modern $count times" >&2
      exit 1
    }
    [[ ! -e "$home/tomcat10-api/webapps/${modern%.war}" ]]
    [[ ! -e "$home/tomcat10-api/webapps/$deployed" ]]
    [[ ! -e "$home/tomcat10-api/webapps/${deployed%.war}" ]]
    count=$(grep -RFl "docBase=\"\${catalina.base}/phase5f/$modern\"" \
      "$home/tomcat10-api/conf/Catalina/localhost" | wc -l | tr -d ' ')
    [[ "$count" == 1 ]] || {
      echo "$label mounts $modern from $count Context descriptors" >&2
      exit 1
    }
    [[ ! -e "$home/tomcat/webapps/${deployed%.war}" ]]
    if [[ -f "$home/tomcat/webapps/$deployed" ]]; then
      cmp -s "$routing_dir/legacy/$deployed" \
        "$home/tomcat/webapps/$deployed" || {
        echo "$label deploys a stale Tomcat 9 $deployed" >&2
        exit 1
      }
    fi
  done

  if find "$home/tomcat10-api/phase5f" "$home/config" -type f \
      \( -name '*.key' -o -name '*.secret' -o -name '*.token' \
      -o -iname '*handoff*' \) | grep -q .; then
    echo "$label carries secret or handoff material in Phase 5f topology" >&2
    exit 1
  fi
}

verify_home "$installed_home" "The installed product"
verify_home "$release_home" "The release tree"

archive_listing() {
  if [[ $1 == zip ]]; then
    unzip -Z1 "$2"
  else
    tar -tzf "$2"
  fi
}

archive_extract_digest() {
  if [[ $1 == zip ]]; then
    unzip -p "$2" "$3" | shasum -a 256 | awk '{print $1}'
  else
    tar -xOzf "$2" "$3" | shasum -a 256 | awk '{print $1}'
  fi
}

for kind_archive in "zip|$zip_archive" "tar|$tar_archive"; do
  IFS='|' read -r kind archive <<<"$kind_archive"
  listing=$(archive_listing "$kind" "$archive")
  for row in "${contexts[@]}"; do
    IFS='|' read -r name legacy deployed modern descriptor context <<<"$row"
    original=${legacy%.war}Original.war
    for path in \
      "Adempiere/lib/$legacy" \
      "Adempiere/lib/$original" \
      "Adempiere/tomcat10-api/phase5f/$modern" \
      "Adempiere/tomcat10-api/conf/Catalina/localhost/$descriptor"; do
      count=$(grep -Fxc "$path" <<<"$listing")
      [[ "$count" == 1 ]] || {
        echo "$kind archive contains $path $count times" >&2
        exit 1
      }
    done
    [[ "$(archive_extract_digest "$kind" "$archive" \
      "Adempiere/lib/$original")" == \
      "$(digest "$routing_dir/pristine/$legacy")" ]]
    [[ "$(archive_extract_digest "$kind" "$archive" \
      "Adempiere/lib/$legacy")" == \
      "$(digest "$routing_dir/legacy/$deployed")" ]]
    [[ "$(archive_extract_digest "$kind" "$archive" \
      "Adempiere/tomcat10-api/phase5f/$modern")" == \
      "$(digest "$modern_war_dir/$modern")" ]]
    [[ "$(archive_extract_digest "$kind" "$archive" \
      "Adempiere/tomcat10-api/conf/Catalina/localhost/$descriptor")" == \
      "$(digest "$release_home/tomcat10-api/conf/Catalina/localhost/$descriptor")" ]]
    if grep -Eq "Adempiere/tomcat10-api/webapps/${modern}(/|$)|Adempiere/tomcat10-api/webapps/${modern%.war}/" \
        <<<"$listing"; then
      echo "$kind archive carries a duplicate $modern deployment" >&2
      exit 1
    fi
    if grep -Eq "Adempiere/tomcat10-api/webapps/${deployed}(/|$)|Adempiere/tomcat10-api/webapps/${deployed%.war}/" \
        <<<"$listing"; then
      echo "$kind archive carries a stale auto-deployed $deployed context" >&2
      exit 1
    fi
    if grep -Eq "Adempiere/tomcat/webapps/${deployed%.war}/" <<<"$listing"; then
      echo "$kind archive carries stale exploded ${deployed%.war}" >&2
      exit 1
    fi
  done
  grep -Fqx 'Adempiere/tomcat10-api/webapps/ADInterface.war' <<<"$listing"
  grep -Fqx 'Adempiere/tomcat10-api/phase5e/webui-modern.war' <<<"$listing"
  for inherited in \
    lib/ADInterface-Modern-1.0.war \
    tomcat10-api/webapps/ADInterface.war \
    tomcat10-api/phase5e/webui-modern.war \
    tomcat10-api/conf/Catalina/localhost/webui.xml; do
    [[ "$(archive_extract_digest "$kind" "$archive" "Adempiere/$inherited")" == \
      "$(digest "$release_home/$inherited")" ]] || {
      echo "$kind archive changed inherited artifact $inherited" >&2
      exit 1
    }
  done
  if grep -Eq '(^|/)(handoff\.key|[^/]+\.(secret|token))$' <<<"$listing"; then
    echo "$kind archive carries secret or handoff material" >&2
    exit 1
  fi
done

evidence="$routing_dir/installed-release-topology.tsv"
{
  echo $'surface\tresult'
  echo $'installed-manifests\tverified'
  echo $'release-tree-manifests\tverified'
  echo $'release-zip\tverified'
  echo $'release-tar\tverified'
  echo $'modern-war-count\tone-per-context'
  echo $'context-docbases\tresolved'
  echo $'tomcat10-connectors\tloopback-only'
  echo $'internal-origin-leakage\tabsent'
  echo $'phase4-cxf-and-soap-paths\tpreserved'
  echo $'phase5e-webui-topology\tpreserved'
  echo $'secret-key-material\tabsent'
} >"$evidence"
cat "$evidence"
