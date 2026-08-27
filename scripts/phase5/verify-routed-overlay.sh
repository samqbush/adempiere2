#!/usr/bin/env bash
# Phase 5e: verify the routed overlay in the installed product and in both
# 394LTS release archives.
#
# Seven claims, each of which has failed in a real modernization at least once:
#
#   1. The installed lib/webui.war IS the derived artifact, byte for byte. A
#      staging step that copied the pristine WAR would leave a product that
#      starts cleanly and routes nobody.
#   2. lib/webuiOriginal.war IS the pristine artifact. That file is both the
#      rollback material and the input `setupWLib` merges FROM, so a derived
#      copy there would make the next install derive from an already-derived
#      archive and make rollback impossible.
#   3. The Tomcat Context descriptor that mounts the modern application at the
#      internal /webui path is present and disables URL rewriting.
#   4. The descriptor's docBase RESOLVES to a file that actually exists. A
#      descriptor whose docBase names a path nothing ever staged is a context
#      Tomcat refuses to start, and it is invisible to a check that only greps
#      the descriptor's text.
#   5. Exactly one modern UI context is staged. The Phase 5c/5d
#      tomcat10-api/webapps/webui-modern.war copy - and any expansion of it -
#      is gone, because an archive under webapps is auto-deployed IN ADDITION to
#      the descriptor mount.
#   6. The Tomcat 9 DEPLOYED copy agrees with lib/webui.war and no stale
#      exploded context survives beside it. Verifying only lib/ leaves the
#      question "what does this product actually serve" unanswered.
#   7. No handoff key is inside any archive or any staged tree.
set -euo pipefail

installed_home=${1:?installed home is required}
release_home=${2:?release home is required}
zip_archive=${3:?release ZIP is required}
tar_archive=${4:?release TAR is required}
derived_war=${5:?derived legacy WAR is required}
pristine_war=${6:?pristine legacy WAR is required}
modern_war=${7:?modern webui-modern.war is required}

manifest_path=config/phase5e-routing.sha256
context_path=tomcat10-api/conf/Catalina/localhost/webui.xml
routed_modern_path=tomcat10-api/phase5e/webui-modern.war
superseded_modern_path=tomcat10-api/webapps/webui-modern.war

digest() {
  shasum -a 256 "$1" | awk '{print $1}'
}

verify_home() {
  local home=$1 label=$2
  (
    cd "$home"
    shasum -a 256 --check "$manifest_path" >/dev/null
  )
  if ! cmp -s "$derived_war" "$home/lib/webui.war"; then
    echo "$label lib/webui.war is not the derived routed artifact" >&2
    exit 1
  fi
  if ! cmp -s "$pristine_war" "$home/lib/webuiOriginal.war"; then
    echo "$label lib/webuiOriginal.war is not the pristine rollback material" >&2
    exit 1
  fi
  if [[ ! -f "$home/$context_path" ]]; then
    echo "$label is missing the modern Tomcat Context descriptor" >&2
    exit 1
  fi
  grep -Fq 'disableURLRewriting="true"' "$home/$context_path"
  grep -Fq 'sessionCookiePath="/webui"' "$home/$context_path"

  # The docBase is RESOLVED, not merely inspected. ${catalina.base} for the
  # modern runtime in an installed tree is <ADEMPIERE_HOME>/tomcat10-api, which
  # is the same substitution start-routed-lane.sh performs against its own
  # CATALINA_BASE.
  local doc_base resolved
  doc_base=$(sed -n 's/.*docBase="\([^"]*\)".*/\1/p' "$home/$context_path" |
    head -1)
  if [[ -z "$doc_base" ]]; then
    echo "$label Context descriptor declares no docBase" >&2
    exit 1
  fi
  case "$doc_base" in
    */webapps/*)
      echo "$label Context descriptor docBase points inside webapps, which " \
        "auto-deploys a second context" >&2
      exit 1
      ;;
  esac
  resolved=${doc_base//\$\{catalina.base\}/$home/tomcat10-api}
  if [[ ! -f "$resolved" ]]; then
    echo "$label Context descriptor docBase resolves to a file that does not " \
      "exist: $resolved" >&2
    exit 1
  fi
  if ! cmp -s "$modern_war" "$resolved"; then
    echo "$label routed modern archive is not the built webui-modern.war" >&2
    exit 1
  fi

  # Exactly one modern UI context.
  if [[ -e "$home/$superseded_modern_path" ]]; then
    echo "$label still carries the superseded auto-deployed modern archive" >&2
    exit 1
  fi
  if [[ -e "$home/tomcat10-api/webapps/webui-modern" ]]; then
    echo "$label still carries an exploded /webui-modern context" >&2
    exit 1
  fi
  if [[ -e "$home/config/phase5c-web-overlay.sha256" ]]; then
    echo "$label still carries the Phase 5c manifest for a path it no longer has" >&2
    exit 1
  fi

  # The Tomcat 9 deployment, not just the library copy.
  if [[ -e "$home/tomcat/webapps/webui" ]]; then
    echo "$label carries a stale exploded /webui context beside the routed WAR" >&2
    exit 1
  fi
  if [[ -f "$home/tomcat/webapps/webui.war" ]] &&
      ! cmp -s "$derived_war" "$home/tomcat/webapps/webui.war"; then
    echo "$label deploys a webapps/webui.war that is not the routed artifact" >&2
    exit 1
  fi

  if find "$home" -type f \( -name '*.key' -o -name '*handoff*' \) \
      -not -name '*.sha256' | grep -q .; then
    echo "$label contains handoff key material" >&2
    exit 1
  fi
}

verify_home "$installed_home" "The installed product"
verify_home "$release_home" "The release tree"

expected_war=$(digest "$derived_war")
expected_original=$(digest "$pristine_war")
expected_modern=$(digest "$modern_war")

zip_war=$(unzip -p "$zip_archive" "Adempiere/lib/webui.war" | shasum -a 256 | awk '{print $1}')
zip_original=$(unzip -p "$zip_archive" "Adempiere/lib/webuiOriginal.war" | shasum -a 256 | awk '{print $1}')
tar_war=$(tar -xOzf "$tar_archive" "Adempiere/lib/webui.war" | shasum -a 256 | awk '{print $1}')
tar_original=$(tar -xOzf "$tar_archive" "Adempiere/lib/webuiOriginal.war" | shasum -a 256 | awk '{print $1}')

if [[ "$zip_war" != "$expected_war" || "$tar_war" != "$expected_war" ]]; then
  echo "A release archive does not carry the derived routed webui.war" >&2
  exit 1
fi
if [[ "$zip_original" != "$expected_original" || "$tar_original" != "$expected_original" ]]; then
  echo "A release archive does not carry the pristine rollback webui.war" >&2
  exit 1
fi

# The resolved docBase has to exist inside the shipped archives too, or the
# product installs a context that cannot start.
zip_modern=$(unzip -p "$zip_archive" "Adempiere/$routed_modern_path" |
  shasum -a 256 | awk '{print $1}')
tar_modern=$(tar -xOzf "$tar_archive" "Adempiere/$routed_modern_path" |
  shasum -a 256 | awk '{print $1}')
if [[ "$zip_modern" != "$expected_modern" || "$tar_modern" != "$expected_modern" ]]; then
  echo "A release archive does not carry the routed modern archive at $routed_modern_path" >&2
  exit 1
fi

unzip -l "$zip_archive" "Adempiere/$context_path" >/dev/null
tar -tzf "$tar_archive" "Adempiere/$context_path" >/dev/null

zip_listing=$(unzip -Z1 "$zip_archive")
tar_listing=$(tar -tzf "$tar_archive")

for archive_listing in "$zip_listing" "$tar_listing"; do
  if printf '%s\n' "$archive_listing" | grep -Eq 'handoff\.key|\.secret$'; then
    echo "A release archive carries handoff key material" >&2
    exit 1
  fi
  if printf '%s\n' "$archive_listing" |
      grep -Fq "Adempiere/$superseded_modern_path"; then
    echo "A release archive still carries the superseded auto-deployed modern archive" >&2
    exit 1
  fi
  if printf '%s\n' "$archive_listing" |
      grep -Eq 'Adempiere/tomcat10-api/webapps/webui-modern/'; then
    echo "A release archive carries an exploded /webui-modern context" >&2
    exit 1
  fi
  if printf '%s\n' "$archive_listing" |
      grep -Eq 'Adempiere/tomcat/webapps/webui/'; then
    echo "A release archive carries an exploded /webui context" >&2
    exit 1
  fi
done

printf 'phase5e_installed_webui\t%s\n' "$expected_war"
printf 'phase5e_installed_original\t%s\n' "$expected_original"
printf 'phase5e_routed_modern\t%s\n' "$expected_modern"
printf 'phase5e_release_zip\tverified\n'
printf 'phase5e_release_tar\tverified\n'
printf 'phase5e_context_descriptor\tpresent-and-resolvable\n'
printf 'phase5e_superseded_webapps_copy\tabsent\n'
printf 'phase5e_key_material_in_archives\tabsent\n'
