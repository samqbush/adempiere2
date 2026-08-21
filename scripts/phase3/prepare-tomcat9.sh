#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: prepare-tomcat9.sh <runtime-properties> <tomcat-home>" >&2
  exit 64
fi

runtime_properties=$1
tomcat_home=$2
repo_root=$(git rev-parse --show-toplevel)
phase3_root="$repo_root/build/phase3"
tomcat_version=$(sed -n 's/^tomcat.version=//p' "$runtime_properties")

if [[ -z "$tomcat_version" ]]; then
  echo "Missing tomcat.version in $runtime_properties" >&2
  exit 65
fi

mkdir -p "$(dirname "$tomcat_home")"
tomcat_parent=$(cd "$(dirname "$tomcat_home")" && pwd -P)
tomcat_home="$tomcat_parent/$(basename "$tomcat_home")"
if [[ "$tomcat_home" != "$phase3_root/"* ]]; then
  echo "Phase 3 Tomcat must stay below $phase3_root. Refusing: $tomcat_home" >&2
  exit 65
fi

if [[ -x "$tomcat_home/bin/catalina.sh" &&
      "$("$tomcat_home/bin/version.sh" 2>/dev/null | sed -n 's/^Server number: *//p')" == "$tomcat_version.0" ]]; then
  exit 0
fi

archive="$phase3_root/apache-tomcat-$tomcat_version.tar.gz"
checksum="$archive.sha512"
extract_root="$phase3_root/tomcat-extract"
base_url="https://downloads.apache.org/tomcat/tomcat-9/v$tomcat_version/bin"

rm -rf "$tomcat_home" "$extract_root"
mkdir -p "$extract_root"
curl --fail --location --silent --show-error \
  "$base_url/apache-tomcat-$tomcat_version.tar.gz" --output "$archive"
curl --fail --location --silent --show-error \
  "$base_url/apache-tomcat-$tomcat_version.tar.gz.sha512" --output "$checksum"
(cd "$phase3_root" && shasum -a 512 -c "$(basename "$checksum")")
tar -xzf "$archive" -C "$extract_root"
mv "$extract_root/apache-tomcat-$tomcat_version" "$tomcat_home"
rm -rf "$extract_root"
