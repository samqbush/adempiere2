#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
manifest="$repo_root/gradle/phase5/browser-artifacts.tsv"
target_dir=${1:-"$repo_root/build/phase5c/playwright-browsers"}
download_dir="$repo_root/build/phase5c/browser-downloads"

case "$(uname -s):$(uname -m)" in
	Darwin:arm64) platform=mac-arm64 ;;
	Linux:x86_64) platform=linux-x64 ;;
	*)
		echo "Unsupported Phase 5c browser platform: $(uname -s):$(uname -m)" >&2
		exit 1
		;;
esac

mkdir -p "$download_dir" "$target_dir"

while IFS=$'\t' read -r row_platform component revision version url expected_sha expected_bytes install_directory; do
	[[ -z "$row_platform" || "$row_platform" == \#* ]] && continue
	[[ "$row_platform" != "$platform" ]] && continue

	archive="$download_dir/$(basename "$url")"
	if [[ ! -f "$archive" ]]; then
		curl --fail --location --silent --show-error \
			--output "$archive" "$url"
	fi
	actual_sha=$(shasum -a 256 "$archive" | awk '{print $1}')
	actual_bytes=$(wc -c <"$archive" | tr -d ' ')
	if [[ "$actual_sha" != "$expected_sha" || "$actual_bytes" != "$expected_bytes" ]]; then
		echo "Browser artifact verification failed for $component $revision" >&2
		echo "expected sha256=$expected_sha bytes=$expected_bytes" >&2
		echo "actual   sha256=$actual_sha bytes=$actual_bytes" >&2
		exit 1
	fi

	staging="$target_dir/.${install_directory}.staging.$$"
	rm -rf "$staging"
	mkdir -p "$staging"
	unzip -q "$archive" -d "$staging"
	rm -rf "$target_dir/$install_directory"
	mv "$staging" "$target_dir/$install_directory"
	printf '%s\t%s\t%s\t%s\t%s\n' \
		"$platform" "$component" "$revision" "$version" "$actual_sha"
done <"$manifest" >"$target_dir/verified-artifacts.tsv"

if [[ $(wc -l <"$target_dir/verified-artifacts.tsv" | tr -d ' ') != 2 ]]; then
	echo "Expected exactly two verified browser artifacts for $platform" >&2
	exit 1
fi
