#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
inventory="$repo_root/gradle/phase5/zk-target-artifacts.tsv"
download_dir=${1:-"$repo_root/build/phase5/zk-target"}

mkdir -p "$download_dir"

while IFS=$'\t' read -r artifact url expected_sha512; do
	[[ -z "$artifact" || "$artifact" == \#* ]] && continue
	target="$download_dir/$artifact.jar"
	curl --fail --location --silent --show-error --output "$target" "$url"
	actual_sha512=$(shasum -a 512 "$target" | awk '{print $1}')
	if [[ "$actual_sha512" != "$expected_sha512" ]]; then
		echo "ZK target checksum mismatch for $artifact" >&2
		exit 1
	fi
done <"$inventory"

require_class() {
	local archive=$1
	local class_name=$2
	if ! unzip -Z1 "$download_dir/$archive.jar" "$class_name" >/dev/null 2>&1; then
		echo "ZK target class missing from $archive: $class_name" >&2
		exit 1
	fi
}

require_class zul org/zkoss/zul/Borderlayout.class
require_class zul org/zkoss/zul/Filedownload.class
require_class zk org/zkoss/zk/ui/impl/PollingServerPush.class

for forbidden in \
	org/zkoss/zkex/zul/Borderlayout.class \
	org/zkoss/zkmax/zul/Filedownload.class \
	org/zkoss/zkmax/ui/comet/CometServerPush.class; do
	for archive in "$download_dir"/*.jar; do
		if unzip -Z1 "$archive" "$forbidden" >/dev/null 2>&1; then
			echo "Commercial/evaluation API unexpectedly present in CE target: $forbidden" >&2
			exit 1
		fi
	done
done

printf 'Verified ZK CE 10.3.0.1-jakarta target artifacts and replacement APIs\n'
