#!/usr/bin/env bash
set -euo pipefail

war_file=${1:?Usage: verify-modern-soap-war.sh <modern-war>}
if [[ ! -f "$war_file" ]]; then
	echo "Modern SOAP WAR is missing: $war_file" >&2
	exit 1
fi

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/adempiere-modern-soap.XXXXXX")
cleanup() {
	rm -rf "$work_dir"
}
trap cleanup EXIT
unzip -q "$war_file" -d "$work_dir"

if find "$work_dir" -iname '*xfire*' -print -quit | grep -q .; then
	echo "Modern SOAP WAR contains an XFire-named entry" >&2
	find "$work_dir" -iname '*xfire*' -print >&2
	exit 1
fi

scan_archive_for_linkage() {
	local archive=$1
	local needle=$2
	if unzip -p "$archive" \
			| LC_ALL=C grep -aF "$needle" >/dev/null; then
		echo "$archive references $needle" >&2
		return 1
	fi
}

while IFS= read -r archive; do
	scan_archive_for_linkage "$archive" 'org/codehaus/xfire'
done < <(find "$work_dir/WEB-INF/lib" -type f -name '*.jar' | sort)

while IFS= read -r class_file; do
	if LC_ALL=C grep -aF 'org/codehaus/xfire' "$class_file" >/dev/null; then
		echo "$class_file references org/codehaus/xfire" >&2
		exit 1
	fi
done < <(find "$work_dir/WEB-INF/classes" -type f -name '*.class' | sort)

for archive in \
	"$work_dir/WEB-INF/lib/Adempiere-Modern-Core.jar" \
	"$work_dir/WEB-INF/lib/Adempiere-Modern-Packages.jar" \
	"$work_dir/WEB-INF/lib/adempiere-soap-legacy-runtime.jar"; do
	scan_archive_for_linkage "$archive" 'javax/servlet'
done

if find "$work_dir/WEB-INF/classes" -type f \
		\( -name 'ADServiceImpl*.class' \
		-o -name 'ModelADServiceImpl*.class' \
		-o -name 'ExternalSalesImpl*.class' \
		-o -name 'WebServiceImpl*.class' \) \
		-print -quit | grep -q .; then
	echo "Modern SOAP WAR contains a legacy transport adapter" >&2
	exit 1
fi

echo "Modern SOAP WAR contains no XFire or shared-core javax.servlet linkage"
