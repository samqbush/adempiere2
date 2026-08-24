#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
output_dir=${1:-"$repo_root/build/phase5/generated"}

mkdir -p "$output_dir"
cd "$repo_root"
export LC_ALL=C

owner_for() {
	case "$1" in
		zkwebui/*) echo "zkwebui" ;;
		org.adempiere.pos/*) echo "org.adempiere.pos" ;;
		org.eevolution.manufacturing/*) echo "org.eevolution.manufacturing" ;;
		org.eevolution.hr_and_payroll/*) echo "org.eevolution.hr_and_payroll" ;;
		org.eevolution.warehouse/*) echo "org.eevolution.warehouse" ;;
		org.spin.loan_management/*) echo "org.spin.loan_management" ;;
		base/*) echo "base" ;;
		serverApps/*) echo "serverApps" ;;
		webStore/*) echo "webStore" ;;
		webCM/*) echo "webCM" ;;
		org.compiere.mobile/*) echo "org.compiere.mobile" ;;
		serverRoot/*) echo "serverRoot" ;;
		JasperReportsWebApp/*) echo "JasperReportsWebApp" ;;
		org.adempiere.webservice/*) echo "org.adempiere.webservice" ;;
		*) printf '%s\n' "${1%%/*}" ;;
	esac
}

{
	printf '# path\towner\treference_count\tapi_families\tcompile_gate\tbehavior_gate\tdisposition\n'
	git grep -l 'org\.zkoss' -- '*.java' |
		sort |
		while IFS= read -r path; do
			count=$(git grep -o 'org\.zkoss' -- "$path" | wc -l | tr -d ' ')
			families=
			if git grep -q 'org\.zkoss\.zkmax' -- "$path"; then
				families=zkmax
			fi
			if git grep -q 'org\.zkoss\.zkex' -- "$path"; then
				families=${families:+$families,}zkex
			fi
			if git grep -q 'org\.zkoss\.zk\|org\.zkoss\.zul\|org\.zkoss\.zhtml\|org\.zkoss\.util' -- "$path"; then
				families=${families:+$families,}ce-core
			fi
			printf '%s\t%s\t%s\t%s\t5d\t5g\tmigrate-source\n' \
				"$path" "$(owner_for "$path")" "$count" "${families:-other}"
		done
} >"$output_dir/zk-sources.tsv"

{
	printf '# path\ttype\towner\treferences\tdisposition\tclosing_gate\n'
	git ls-files |
		grep -E '\.(jsp|tag|tld|zul|zhtml|dsp|xml)$' |
		while IFS= read -r path; do
			if git grep -q -E 'org\.zkoss|javax\.(servlet|mail|jms|ejb|annotation|activation)' -- "$path"; then
				refs=
				if git grep -q 'org\.zkoss' -- "$path"; then
					refs=zk
				fi
				for namespace in servlet mail jms ejb annotation activation; do
					if git grep -q "javax\\.$namespace" -- "$path"; then
						refs=${refs:+$refs,}javax.$namespace
					fi
				done
				case "$path" in
					*.jsp|*.tag|*.tld) gate=5f ;;
					*) gate=5d ;;
				esac
				printf '%s\t%s\t%s\t%s\tmigrate-or-review\t%s\n' \
					"$path" "${path##*.}" "$(owner_for "$path")" "$refs" "$gate"
			fi
		done
} >"$output_dir/web-assets.tsv"

{
	printf '# path\tnamespace\tclassification\towner\tdisposition\tclosing_gate\n'
	for namespace in servlet mail jms ejb annotation activation swing print sql naming crypto imageio; do
		git grep -l "javax\\.$namespace" -- \
			'*.java' '*.jsp' '*.tag' '*.tld' '*.zul' '*.zhtml' '*.dsp' '*.xml' |
			sort -u |
			while IFS= read -r path; do
				case "$namespace" in
					swing|print|sql|naming|crypto|imageio)
						classification=java-se
						disposition=retain
						gate=never
						;;
					servlet)
						classification=jakarta-web
						disposition=migrate
						gate=5f
						;;
					*)
						classification=non-web-jakarta
						disposition=defer
						gate=post-phase5
						;;
				esac
				printf '%s\tjavax.%s\t%s\t%s\t%s\t%s\n' \
					"$path" "$namespace" "$classification" \
					"$(owner_for "$path")" "$disposition" "$gate"
			done
	done
} >"$output_dir/namespace-ownership.tsv"

{
	printf '# path\tspecification_version\tsha512\tdisposition\tclosing_gate\n'
	find zkwebui/WEB-INF/lib -maxdepth 1 -type f -name '*.jar' -print |
		sort |
		while IFS= read -r path; do
			version=$(unzip -p "$path" META-INF/MANIFEST.MF 2>/dev/null |
				tr -d '\r' |
				awk -F': ' '/^(Specification-Version|Implementation-Version|Bundle-Version):/ {print $2; exit}' ||
				true)
			sha512=$(shasum -a 512 "$path" | awk '{print $1}')
			printf '%s\t%s\t%s\treplace-or-provenance-review\t5d\n' \
				"$path" "${version:-unknown}" "$sha512"
		done
} >"$output_dir/zk-runtime-jars.tsv"

awk -F '\t' '
BEGIN {
	OFS = "\t"
	print "# descriptor", "kind", "name", "implementation", "url_pattern", \
		"configured_security", "traffic_class", "behavioral_gate", "owner", \
		"deployment_status", "artifact", "context", "auth_enforcement", \
		"disposition", "closing_gate"
}
/^# callback\/webhook/ {
	print $0
	next
}
/^#/ { next }
{
	descriptor = $1
	status = "not-phase3-deployed"
	artifact = "none"
	context = "none"
	disposition = "review-retain-defer-or-drop"
	closing = "5f"

	if (descriptor == "org.adempiere.webservice/WEB-INF/web.xml") {
		status = "deployed"
		artifact = "ADInterface-1.0.war"
		context = "/ADInterface"
		disposition = "retain-phase4-soap"
		closing = "phase4"
	} else if (descriptor == "org.compiere.mobile/WEB-INF/web.xml") {
		status = "deployed"
		artifact = "mobile.war"
		context = "/mobile"
		disposition = "migrate"
	} else if (descriptor == "serverApps/src/web/WEB-INF/web.xml") {
		status = "deployed"
		artifact = "adempiereApps.war"
		context = "/adempiere"
		disposition = "migrate"
	} else if (descriptor == "serverRoot/src/web/WEB-INF/web.xml") {
		status = "deployed"
		artifact = "adempiereRoot.war"
		context = "/admin"
		disposition = "migrate"
	} else if (descriptor == "webCM/src/web/WEB-INF/web.xml") {
		status = "deployed"
		artifact = "adempiereWebCM.war"
		context = "/"
		disposition = "migrate"
	} else if (descriptor == "webStore/src/web/WEB-INF/web.xml") {
		status = "deployed"
		artifact = "adempiereWebStore.war"
		context = "/wstore"
		disposition = "migrate"
	} else if (descriptor == "zkwebui/WEB-INF/web.xml") {
		status = "deployed"
		artifact = "webui.war"
		context = "/webui"
		disposition = "migrate"
	}

	auth = $6 == "true" ? "container-constraint" : \
		($7 == "anonymous/public" || $7 == "infrastructure" ? \
			"explicit-public-or-application-check" : "application-session-check")

	print $0, status, artifact, context, auth, disposition, closing
}
' gradle/phase4/route-classes.tsv >"$output_dir/route-contracts.tsv"

printf 'Generated Phase 5 inventories in %s\n' "$output_dir"
