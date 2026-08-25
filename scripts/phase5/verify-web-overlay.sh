#!/usr/bin/env bash
set -euo pipefail

installed_home=${1:?installed home is required}
release_home=${2:?release home is required}
zip_archive=${3:?release ZIP is required}
tar_archive=${4:?release TAR is required}
api_war=${5:?modern API WAR is required}
overlay_path=tomcat10-api/webapps/webui-modern.war
manifest_path=config/phase5c-web-overlay.sha256

verify_home() {
	local home=$1
	(
		cd "$home"
		sha256sum --check "$manifest_path"
	)
	cmp "$api_war" "$home/tomcat10-api/webapps/ADInterface.war"
	grep -Fq 'address="127.0.0.1"' "$home/tomcat10-api/conf/server.xml"
}

verify_home "$installed_home"
verify_home "$release_home"

zip_entry="Adempiere/$overlay_path"
zip_manifest="Adempiere/$manifest_path"
unzip -l "$zip_archive" "$zip_entry" "$zip_manifest" >/dev/null

tar_entry="Adempiere/$overlay_path"
tar_manifest="Adempiere/$manifest_path"
tar -tzf "$tar_archive" "$tar_entry" "$tar_manifest" >/dev/null

expected=$(awk '{print $1}' "$release_home/$manifest_path")
zip_actual=$(unzip -p "$zip_archive" "$zip_entry" | shasum -a 256 | awk '{print $1}')
tar_actual=$(tar -xOzf "$tar_archive" "$tar_entry" | shasum -a 256 | awk '{print $1}')
if [[ "$zip_actual" != "$expected" || "$tar_actual" != "$expected" ]]; then
	echo "Phase 5c release overlay digest mismatch" >&2
	exit 1
fi
