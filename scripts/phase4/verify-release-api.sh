#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
	echo "Usage: verify-release-api.sh <zip> <tar.gz> <artifact-list>" >&2
	exit 64
fi

zip_file=$1
tar_file=$2
artifact_list=$3
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/adempiere-phase4-release.XXXXXX")
cleanup() {
	rm -rf "$work_dir"
}
trap cleanup EXIT

for archive in "$zip_file" "$tar_file"; do
	if [[ ! -s "$archive" || ! -s "$archive.MD5" ]]; then
		echo "Missing Phase 4 release archive or checksum: $archive" >&2
		exit 1
	fi
done

md5_value() {
	if command -v md5sum >/dev/null 2>&1; then
		md5sum "$1" | awk '{print $1}'
	else
		md5 -q "$1"
	fi
}

for archive in "$zip_file" "$tar_file"; do
	checksum_line=$(<"$archive.MD5")
	expected_checksum=${checksum_line%% *}
	expected_name=${checksum_line#* }
	if [[ "$expected_name" != "$(basename "$archive")" ||
		"$expected_checksum" != "$(md5_value "$archive")" ]]; then
		echo "Invalid Phase 4 release checksum: $archive.MD5" >&2
		exit 1
	fi
done

unzip -Z1 "$zip_file" >"$work_dir/zip-entries.txt"
tar -tzf "$tar_file" >"$work_dir/tar-entries.txt"

while IFS= read -r artifact; do
	[[ -z "$artifact" || "$artifact" == \#* ]] && continue
	grep -Fxq "Adempiere/$artifact" "$work_dir/zip-entries.txt"
	grep -Fxq "Adempiere/$artifact" "$work_dir/tar-entries.txt"
done <"$artifact_list"

for listing in "$work_dir/zip-entries.txt" "$work_dir/tar-entries.txt"; do
	grep -Fxq 'Adempiere/AdempiereEnvTemplate.properties' "$listing"
done

if grep -Fxq 'Adempiere/AdempiereEnv.properties' "$work_dir/zip-entries.txt"; then
	echo "Configured environment leaked into the Phase 4 ZIP." >&2
	exit 1
fi
if grep -Fxq 'Adempiere/AdempiereEnv.properties' "$work_dir/tar-entries.txt"; then
	echo "Configured environment leaked into the Phase 4 TAR." >&2
	exit 1
fi

for launcher in utils/RUN_API.sh utils/RUN_API_Stop.sh; do
	zipinfo -l "$zip_file" "Adempiere/$launcher" \
		>"$work_dir/zip-mode.txt"
	tar -tvzf "$tar_file" "Adempiere/$launcher" \
		>"$work_dir/tar-mode.txt"
	grep -Eq '^-rwx' "$work_dir/zip-mode.txt"
	grep -Eq '^-rwx' "$work_dir/tar-mode.txt"
done

for archive_type in zip tar; do
	if [[ "$archive_type" == "zip" ]]; then
		unzip -p "$zip_file" Adempiere/lib/ADInterface-Modern-1.0.war \
			>"$work_dir/$archive_type-modern.war"
		unzip -p "$zip_file" Adempiere/tomcat10-api/webapps/ADInterface.war \
			>"$work_dir/$archive_type-deployed.war"
		unzip -p "$zip_file" Adempiere/tomcat10-api/bin/setenv.sh \
			>"$work_dir/$archive_type-setenv.sh"
		unzip -p "$zip_file" Adempiere/lib/ADInterface-1.0.war \
			>"$work_dir/$archive_type-compatibility.war"
	else
		tar -xOzf "$tar_file" Adempiere/lib/ADInterface-Modern-1.0.war \
			>"$work_dir/$archive_type-modern.war"
		tar -xOzf "$tar_file" Adempiere/tomcat10-api/webapps/ADInterface.war \
			>"$work_dir/$archive_type-deployed.war"
		tar -xOzf "$tar_file" Adempiere/tomcat10-api/bin/setenv.sh \
			>"$work_dir/$archive_type-setenv.sh"
		tar -xOzf "$tar_file" Adempiere/lib/ADInterface-1.0.war \
			>"$work_dir/$archive_type-compatibility.war"
	fi
	cmp "$work_dir/$archive_type-modern.war" \
		"$work_dir/$archive_type-deployed.war"
	if [[ $(grep -Fxc \
		'API_ADEMPIERE_HOME=$(cd "$CATALINA_HOME/.." && pwd -P)' \
		"$work_dir/$archive_type-setenv.sh") -ne 1 ]]; then
		echo "Non-portable Phase 4 API home in the $archive_type archive." >&2
		exit 1
	fi
	unzip -Z1 "$work_dir/$archive_type-compatibility.war" \
		>"$work_dir/$archive_type-compatibility-entries.txt"
	if grep -Eiq '(^|/)xfire-all|org/codehaus/xfire|META-INF/xfire' \
		"$work_dir/$archive_type-compatibility-entries.txt"; then
		echo "Retired XFire artifacts remain in the $archive_type compatibility WAR." >&2
		exit 1
	fi
	unzip -p "$work_dir/$archive_type-compatibility.war" WEB-INF/web.xml \
		>"$work_dir/$archive_type-compatibility-web.xml"
	if grep -Eiq 'org\.codehaus\.xfire|internal/XFireServlet' \
		"$work_dir/$archive_type-compatibility-web.xml"; then
		echo "Retired XFire publication remains in the $archive_type compatibility WAR." >&2
		exit 1
	fi
done

echo "Phase 4 release archives contain the complete XFire-free API runtime"
