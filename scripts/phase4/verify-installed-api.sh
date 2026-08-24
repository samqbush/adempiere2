#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
	echo "Usage: verify-installed-api.sh <repo-root> <installed-home> <modern-war> <artifact-list>" >&2
	exit 64
fi

repo_root=$(cd "$1" && pwd -P)
installed_home=$(cd "$2" && pwd -P)
modern_war=$(cd "$(dirname "$3")" && pwd -P)/$(basename "$3")
artifact_list=$4
expected_root="$repo_root/build/phase3/runtime/Adempiere"
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/adempiere-phase4-installed.XXXXXX")
cleanup() {
	rm -rf "$work_dir"
}
trap cleanup EXIT

if [[ "$installed_home" != "$expected_root" ]]; then
	echo "Refusing to verify an installed API outside $expected_root" >&2
	exit 65
fi

while IFS= read -r artifact; do
	[[ -z "$artifact" || "$artifact" == \#* ]] && continue
	if [[ ! -f "$installed_home/$artifact" ]]; then
		echo "Missing installed Phase 4 API artifact: $artifact" >&2
		exit 1
	fi
done <"$artifact_list"

cmp "$modern_war" "$installed_home/lib/ADInterface-Modern-1.0.war"
cmp "$modern_war" "$installed_home/tomcat10-api/webapps/ADInterface.war"
unzip -Z1 "$installed_home/lib/ADInterface-1.0.war" \
	>"$work_dir/legacy-war-entries.txt"
if grep -Eiq '(^|/)xfire-all|org/codehaus/xfire|META-INF/xfire' \
	"$work_dir/legacy-war-entries.txt"; then
	echo "Retired XFire artifacts remain in the installed compatibility WAR." >&2
	exit 1
fi
unzip -p "$installed_home/lib/ADInterface-1.0.war" WEB-INF/web.xml \
	>"$work_dir/legacy-web.xml"
if grep -Eiq 'org\.codehaus\.xfire|internal/XFireServlet' \
	"$work_dir/legacy-web.xml"; then
	echo "Retired XFire publication remains in the installed compatibility WAR." >&2
	exit 1
fi
grep -Fq 'address="127.0.0.1" port="8890"' \
	"$installed_home/tomcat10-api/conf/server.xml"
grep -Fq 'API_ADEMPIERE_HOME=$(cd "$CATALINA_HOME/.." && pwd -P)' \
	"$installed_home/tomcat10-api/bin/setenv.sh"
grep -Fq 'PropertyFile=$API_ADEMPIERE_HOME/AdempiereEnv.properties' \
	"$installed_home/tomcat10-api/bin/setenv.sh"

for script in RUN_API.sh RUN_API_Stop.sh; do
	if [[ ! -x "$installed_home/utils/$script" ]]; then
		echo "Installed API script is not executable: utils/$script" >&2
		exit 1
	fi
done

echo "Installed Phase 4 API runtime is XFire-free and loopback-only"
