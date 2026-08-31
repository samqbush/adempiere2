#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
	echo "Usage: package-installed-api.sh <repo-root> <distribution-home> <version>" >&2
	exit 64
fi

repo_root=$(cd "$1" && pwd -P)
distribution_home=$(cd "$2" && pwd -P)
version=$3
expected_home="$repo_root/build/phase3/release/Adempiere"
output_dir="$repo_root/build/phase3/release"

if [[ "$distribution_home" != "$expected_home" || "$version" != "394LTS" ]]; then
	echo "Refusing to package outside the Phase 4 release staging tree." >&2
	exit 65
fi
if [[ -f "$distribution_home/AdempiereEnv.properties" ]]; then
	echo "Refusing to package a configured AdempiereEnv.properties file." >&2
	exit 65
fi
if [[ ! -f "$distribution_home/AdempiereEnvTemplate.properties" ]]; then
	echo "The release configuration template is missing." >&2
	exit 1
fi

zip_file="$output_dir/Adempiere_$version.zip"
tar_file="$output_dir/Adempiere_$version.tar.gz"
rm -f "$zip_file" "$tar_file" "$zip_file.MD5" "$tar_file.MD5"

(
	cd "$output_dir"
	zip -q -r "$(basename "$zip_file")" Adempiere
	tar -czf "$(basename "$tar_file")" Adempiere
)

md5_value() {
	if command -v md5sum >/dev/null 2>&1; then
		md5sum "$1" | awk '{print $1}'
	else
		md5 -q "$1"
	fi
}

printf '%s %s' "$(md5_value "$zip_file")" "$(basename "$zip_file")" \
	>"$zip_file.MD5"
printf '%s %s' "$(md5_value "$tar_file")" "$(basename "$tar_file")" \
	>"$tar_file.MD5"

echo "Packaged Phase 4 API runtime in the 394LTS ZIP and TAR distributions"
