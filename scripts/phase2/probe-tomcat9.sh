#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
runtime_properties="$repo_root/gradle/phase2/runtime.properties"
tomcat_version=$(sed -n 's/^tomcat.version=//p' "$runtime_properties")
test -n "$tomcat_version"

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/adempiere-tomcat9.XXXXXX")
tomcat_home="$work_dir/apache-tomcat-$tomcat_version"
archive="$work_dir/apache-tomcat-$tomcat_version.tar.gz"
base_url="https://downloads.apache.org/tomcat/tomcat-9/v$tomcat_version/bin"

cleanup() {
  if [[ -x "$tomcat_home/bin/catalina.sh" ]]; then
    CATALINA_HOME="$tomcat_home" CATALINA_BASE="$tomcat_home" \
      "$tomcat_home/bin/catalina.sh" stop >/dev/null 2>&1 || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

curl --fail --location --silent --show-error \
  "$base_url/apache-tomcat-$tomcat_version.tar.gz" --output "$archive"
curl --fail --location --silent --show-error \
  "$base_url/apache-tomcat-$tomcat_version.tar.gz.sha512" --output "$archive.sha512"
(cd "$work_dir" && shasum -a 512 -c "$(basename "$archive").sha512")
tar -xzf "$archive" -C "$work_dir"

export CATALINA_HOME="$tomcat_home"
export CATALINA_BASE="$tomcat_home"
export CATALINA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED"
"$tomcat_home/bin/catalina.sh" start

for _ in $(seq 1 30); do
  if curl --fail --silent "http://127.0.0.1:8080/" >/dev/null; then
    java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
    printf 'Tomcat %s ready on %s\n' "$tomcat_version" "$java_version"
    exit 0
  fi
  sleep 1
done

cat "$tomcat_home/logs/catalina.out" >&2
exit 1
