#!/usr/bin/env bash
set -euo pipefail

installed_home=${1:?installed home is required}
zip_archive=${2:?release ZIP is required}
tar_archive=${3:?release TAR is required}
api_war=${4:?modern API WAR is required}
evidence_dir=${5:?evidence directory is required}
overlay=tomcat10-api/webapps/webui-modern.war
manifest=config/phase5c-web-overlay.sha256

rm -rf "$evidence_dir"
mkdir -p "$evidence_dir/installed" "$evidence_dir/zip" "$evidence_dir/tar"

[[ -f "$installed_home/$overlay" && -f "$installed_home/$manifest" ]]
cp -R "$installed_home/tomcat10-api" "$evidence_dir/installed/"
cp -R "$installed_home/config" "$evidence_dir/installed/"
rm "$evidence_dir/installed/$overlay" "$evidence_dir/installed/$manifest"

unzip -q "$zip_archive" -d "$evidence_dir/zip"
tar -xzf "$tar_archive" -C "$evidence_dir/tar"
rm "$evidence_dir/zip/Adempiere/$overlay" \
	"$evidence_dir/zip/Adempiere/$manifest"
rm "$evidence_dir/tar/Adempiere/$overlay" \
	"$evidence_dir/tar/Adempiere/$manifest"

for home in \
	"$evidence_dir/installed" \
	"$evidence_dir/zip/Adempiere" \
	"$evidence_dir/tar/Adempiere"; do
	[[ ! -e "$home/$overlay" && ! -e "$home/$manifest" ]]
	cmp "$api_war" "$home/tomcat10-api/webapps/ADInterface.war"
	grep -Fq 'address="127.0.0.1"' "$home/tomcat10-api/conf/server.xml"
done

(
	cd "$evidence_dir/zip"
	zip -qr "$evidence_dir/Adempiere_394LTS-phase5c-rollback.zip" Adempiere
)
tar -czf "$evidence_dir/Adempiere_394LTS-phase5c-rollback.tar.gz" \
	-C "$evidence_dir/tar" Adempiere

if unzip -l "$evidence_dir/Adempiere_394LTS-phase5c-rollback.zip" \
		"Adempiere/$overlay" | grep -Fq "$overlay"; then
	echo "Rollback ZIP still contains the Phase 5c overlay" >&2
	exit 1
fi
if tar -tzf "$evidence_dir/Adempiere_394LTS-phase5c-rollback.tar.gz" \
		"Adempiere/$overlay" >/dev/null 2>&1; then
	echo "Rollback TAR still contains the Phase 5c overlay" >&2
	exit 1
fi

{
	echo "phase5c-overlay=removed"
	echo "phase4-api-war=$(shasum -a 256 "$api_war" | awk '{print $1}')"
	echo "phase4-listener=loopback-only"
	echo "release-zip=overlay-absent"
	echo "release-tar=overlay-absent"
	echo "legacy-oracle=owned-by-phase5cRollbackRehearsal"
} >"$evidence_dir/rollback-evidence.txt"
