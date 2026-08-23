#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
properties_file="$repo_root/gradle/phase4/runtime.properties"
target_dir=${1:-"$repo_root/build/phase4/tomcat10"}
war_file=${2:-"$repo_root/org.adempiere.webservice/build/libs/ADInterface-Modern-1.0.war"}
adempiere_home=${3:-"$repo_root/build/phase3/runtime/Adempiere"}
download_dir="$repo_root/build/phase4/downloads"

property() {
	awk -F= -v key="$1" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' \
		"$properties_file"
}

tomcat_version=$(property tomcat.version)
tomcat_url=$(property tomcat.url)
tomcat_sha512=$(property tomcat.sha512)
api_port=$(property api.port)
archive="$download_dir/apache-tomcat-$tomcat_version.tar.gz"

if [[ -z "$tomcat_version" || -z "$tomcat_url" || -z "$tomcat_sha512" \
		|| -z "$api_port" ]]; then
	echo "Phase 4 runtime properties are incomplete" >&2
	exit 1
fi
if [[ ! -f "$war_file" ]]; then
	echo "Modern SOAP WAR is missing: $war_file" >&2
	exit 1
fi
if [[ ! -f "$adempiere_home/AdempiereEnv.properties" \
		&& ! -f "$adempiere_home/AdempiereEnvTemplate.properties" ]]; then
	echo "Installed configuration or template is missing below $adempiere_home" >&2
	exit 1
fi

mkdir -p "$download_dir"
if [[ ! -f "$archive" ]]; then
	curl --fail --location --silent --show-error \
		--output "$archive" "$tomcat_url"
fi
actual_sha512=$(shasum -a 512 "$archive" | awk '{print $1}')
if [[ "$actual_sha512" != "$tomcat_sha512" ]]; then
	echo "Tomcat SHA-512 mismatch: expected $tomcat_sha512, got $actual_sha512" >&2
	exit 1
fi

parent_dir=$(dirname "$target_dir")
staging_dir="$parent_dir/.tomcat10-staging-$$"
mkdir -p "$parent_dir" "$staging_dir"
cleanup() {
	if [[ -d "$staging_dir" ]]; then
		rm -rf "$staging_dir"
	fi
}
trap cleanup EXIT

tar -xzf "$archive" -C "$staging_dir"
extracted="$staging_dir/apache-tomcat-$tomcat_version"
if [[ ! -d "$extracted" ]]; then
	echo "Tomcat archive did not contain apache-tomcat-$tomcat_version" >&2
	exit 1
fi

rm -rf "$target_dir"
mv "$extracted" "$target_dir"
sed -i.bak \
	-e 's/<Server port="8005"/<Server port="-1"/' \
	-e "s/port=\"8080\" protocol=\"HTTP\\/1.1\"/address=\"127.0.0.1\" port=\"$api_port\" protocol=\"HTTP\\/1.1\"/" \
	"$target_dir/conf/server.xml"
rm "$target_dir/conf/server.xml.bak"
rm -rf \
	"$target_dir/webapps/docs" \
	"$target_dir/webapps/examples" \
	"$target_dir/webapps/host-manager" \
	"$target_dir/webapps/manager" \
	"$target_dir/webapps/ROOT"
cp "$war_file" "$target_dir/webapps/ADInterface.war"
if [[ "$(cd "$(dirname "$target_dir")" && pwd -P)" == \
		"$(cd "$adempiere_home" && pwd -P)" ]]; then
	cat >"$target_dir/bin/setenv.sh" <<'EOF'
#!/usr/bin/env bash
export ADEMPIERE_APPS_TYPE=tomcat
API_ADEMPIERE_HOME=$(cd "$CATALINA_HOME/.." && pwd -P)
export JAVA_OPTS="${JAVA_OPTS:-} -Dfile.encoding=UTF-8 -Djava.awt.headless=true -DADEMPIERE_HOME=$API_ADEMPIERE_HOME -DPropertyFile=$API_ADEMPIERE_HOME/AdempiereEnv.properties"
EOF
else
	printf '%s\n' \
		'#!/usr/bin/env bash' \
		'export ADEMPIERE_APPS_TYPE=tomcat' \
		"export JAVA_OPTS=\"\${JAVA_OPTS:-} -Dfile.encoding=UTF-8 -Djava.awt.headless=true -DADEMPIERE_HOME=$adempiere_home -DPropertyFile=$adempiere_home/AdempiereEnv.properties\"" \
		> "$target_dir/bin/setenv.sh"
fi
chmod +x "$target_dir/bin/setenv.sh"

printf 'Prepared Tomcat %s on 127.0.0.1:%s with %s\n' \
	"$tomcat_version" "$api_port" "$war_file"
