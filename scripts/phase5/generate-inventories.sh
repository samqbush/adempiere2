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

# Phase 5d: the inventories describe the WORKING TREE, not the index.
#
# The generator used `git grep`, which only sees tracked content. Phase 5d adds
# ADempiere-owned ZK CE compatibility sources, and until they are committed
# `git grep` reports an inventory that is missing exactly the files the phase
# introduced - a green inventory over an incomplete tree. These helpers list and
# search tracked plus untracked-but-not-ignored files instead, so the inventory
# is the same before and after the commit.
tree_files() {
	git ls-files --cached --others --exclude-standard -- "$@" |
		sort -u |
		while IFS= read -r path; do
			[ -f "$path" ] && printf '%s\n' "$path"
		done
}

tree_grep_l() {
	local pattern=$1
	shift
	# One grep over a batched file list rather than one grep per file: the
	# per-file loop turned a two-second inventory into a ten-minute one.
	tree_files "$@" | tr '\n' '\0' |
		LC_ALL=C xargs -0 grep -lE "$pattern" 2>/dev/null || true
}

tree_grep_q() {
	local pattern=$1 path=$2
	LC_ALL=C grep -qE "$pattern" "$path" 2>/dev/null
}

tree_grep_count() {
	local pattern=$1 path=$2
	LC_ALL=C grep -oE "$pattern" "$path" 2>/dev/null | wc -l | tr -d ' '
}

{
	printf '# path\towner\treference_count\tapi_families\tcompile_gate\tbehavior_gate\tdisposition\n'
	# A Java source that names org.zkoss only inside a comment is documenting
	# the migration, not using the API, exactly as namespace-ownership.tsv
	# already reasons. Scan the comment-stripped text for both membership and
	# the reference count, so this ledger cannot be moved by an author typing a
	# fully-qualified name into Javadoc. Perl whole-file mode is used because
	# GNU and BSD sed disagree on the non-greedy expression.
	# perl -p exits 0 and prints nothing when it cannot open its argument, so
	# without this guard an unreadable source would strip to the empty string
	# and be silently dropped from the ledger - fail-open in a fail-closed
	# inventory.
	strip_java_comments() {
		if [ ! -r "$1" ]; then
			printf 'cannot read %s while inventorying ZK sources\n' "$1" >&2
			return 1
		fi
		perl -0777 -pe 's{/\*.*?\*/}{}gs; s{//[^\n]*}{}g' -- "$1"
	}
	tree_grep_l 'org\.zkoss' '*.java' |
		sort |
		while IFS= read -r path; do
			stripped=$(strip_java_comments "$path")
			# grep exits 1 on a comment-only source, and the script runs under
			# `set -o pipefail`, so the no-match case has to be absorbed here
			# rather than aborting the whole inventory.
			count=$(printf '%s' "$stripped" |
				{ LC_ALL=C grep -oE 'org\.zkoss' || true; } |
				wc -l | tr -d ' ')
			if [ "$count" = 0 ]; then
				continue
			fi
			families=
			# Matched in-process with bash's own ERE rather than by piping to
			# `grep -q`. Under `set -o pipefail`, `grep -q` exits at its first
			# match while the writer is still blocked on a pipe larger than the
			# 64KiB pipe buffer, so the writer takes SIGPIPE and the pipeline
			# reports 141 - read here as "no match". That would silently drop an
			# api_families classification, including a zkmax one, on the largest
			# sources; FindWindow.java already strips to 65,647 bytes. A
			# false-negative commercial-dependency row is a far worse failure
			# than the comment-only false positive this rule exists to remove.
			if [[ $stripped =~ org\.zkoss\.zkmax ]]; then
				families=zkmax
			fi
			if [[ $stripped =~ org\.zkoss\.zkex ]]; then
				families=${families:+$families,}zkex
			fi
			if [[ $stripped =~ org\.zkoss\.(zk|zul|zhtml|util) ]]; then
				families=${families:+$families,}ce-core
			fi
			# Phase 5e: the isolated Javax/ZK 3.6 bridge is NOT a migration
			# candidate. It exists to run inside the frozen Tomcat 9 archive and
			# is compiled against classes extracted from that archive, so it
			# targets ZK 3.6 deliberately and is removed with Tomcat 9 in Phase
			# 5h. Recording it as "migrate-source ... 5g" would put it on the ZK
			# CE migration list and make the inventory describe the opposite of
			# the decision.
			case "$path" in
				org.adempiere.cohort/src/bridge/*|org.adempiere.cohort/src/bridgeTest/*)
					printf '%s\t%s\t%s\t%s\t5e\t5h\tfrozen-zk36-bridge\n' \
						"$path" "$(owner_for "$path")" "$count" "${families:-other}"
					continue
					;;
			esac
			printf '%s\t%s\t%s\t%s\t5d\t5g\tmigrate-source\n' \
				"$path" "$(owner_for "$path")" "$count" "${families:-other}"
		done
} >"$output_dir/zk-sources.tsv"

{
	printf '# path\ttype\towner\treferences\tdisposition\tclosing_gate\n'
	tree_files |
		grep -E '\.(jsp|tag|tld|zul|zhtml|dsp|xml)$' |
		while IFS= read -r path; do
			if tree_grep_q 'org\.zkoss|javax\.(servlet|mail|jms|ejb|annotation|activation)' "$path"; then
				refs=
				if tree_grep_q 'org\.zkoss' "$path"; then
					refs=zk
				fi
				for namespace in servlet mail jms ejb annotation activation; do
					if tree_grep_q "javax\\.$namespace" "$path"; then
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
		tree_grep_l "javax\\.$namespace" \
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
						case "$path" in
							org.adempiere.cohort/src/bridge/*|org.adempiere.cohort/src/bridgeTest/*|org.adempiere.cohort/src/contextBridge/*|org.adempiere.cohort/src/contextBridgeTest/*)
								# Phase 5e: the bridge targets the frozen Tomcat 9
								# archive on purpose. It is not migrated to
								# Jakarta; it is deleted with Tomcat 9 in 5h.
								disposition=frozen-zk36-bridge
								gate=5h
								;;
						esac
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

	# Phase 5d: the ZK commercial, ZK 3.x add-on and ZK 3.6-removed-API
	# namespaces. The Phase 5a ledger covered only javax.*, which described the
	# starting position and said nothing about the namespaces the ZK migration
	# actually had to resolve. Each one is resolved by an ADempiere-owned
	# replacement under zkwebui/WEB-INF/src/org/adempiere/webui/compat/ rather
	# than by a commercial artifact or a repository credential.
	#
	# The classification and disposition are derived from where the reference
	# lives, so a reference that reappears in a migrated production source is
	# reported as `unresolved` here and fails
	# gradle/phase5/zk-compile-closure.gradle at the same time.
	for namespace in \
			'org.zkoss.zkex' \
			'org.zkoss.zkmax' \
			'org.zkforge' \
			'org.zkoss.zul.api' \
			'org.zkoss.zul.SimpleTreeModel' \
			'org.zkoss.zul.SimpleTreeNode' \
			'org.zkoss.zul.ListModelExt' \
			'org.zkoss.zk.ui.render'; do
		escaped=$(printf '%s' "$namespace" | sed 's/\./\\./g')
		case "$namespace" in
			org.zkoss.zkex|org.zkoss.zkmax) classification=zk-commercial ;;
			org.zkforge) classification=zk3-addon ;;
			*) classification=zk3-removed-api ;;
		esac
		tree_grep_l "$escaped" \
			'*.java' '*.zul' '*.zhtml' '*.dsp' '*.xml' '*.tsv' '*.gradle' |
			sort -u |
			while IFS= read -r path; do
				# A Java source that names the namespace only inside a comment is
				# documenting the migration, not using the API. The compile
				# closure gate strips comments before scanning for exactly this
				# reason, so the ledger uses the same rule instead of a different
				# one.
				case "$path" in
					*.java)
						# GNU and BSD sed disagree on the non-greedy expression
						# previously used here, which made Linux CI drop a
						# comment-only namespace row that macOS retained. Perl's
						# whole-file mode gives both hosts the same Java comment
						# stripping semantics.
						if ! perl -0777 -pe \
								's{/\*.*?\*/}{}gs; s{//[^\n]*}{}g' "$path" |
								LC_ALL=C grep -qE "$escaped"; then
							continue
						fi
						;;
				esac
				case "$path" in
					*_Test.java|*/test/*|*/testScripts/*)
						disposition=test-assertion
						gate=5d
						;;
					zkwebui/WEB-INF/src/org/adempiere/webui/compat/timeline/*)
						disposition=replaced-adempiere-owned
						gate=5f
						;;
					zkwebui/WEB-INF/src/org/adempiere/webui/compat/*|\
					zkwebui/WEB-INF/src/org/adempiere/webui/component/Keylistener.java)
						disposition=replaced-adempiere-owned
						gate=5d
						;;
					zkwebui/WEB-INF/zk.xml|zkwebui/WEB-INF/web.xml|\
					zkwebui/WEB-INF/web-2.5.xml|zkwebui/WEB-INF/lib/*|\
					zkwebui/WEB-INF/tld/*|zkwebui/theme/*|zkwebui/index.zul|\
					zkwebui/timeout.zul|zkwebui/zul/*)
						# The ZK 3.6 descriptors and assets of the FROZEN legacy
						# artifact. They are not migrated: the legacy web product
						# is materialized from the Phase 5b commit and removed
						# with Tomcat 9 in Phase 5h.
						disposition=frozen-legacy-artifact
						gate=5h
						;;
					gradle/*|contracts/*|docs/*|scripts/*|*.md)
						disposition=recorded-in-contract
						gate=5d
						;;
					zkwebui/src/phase5d/*|*/build.gradle)
						disposition=replaced-ce-equivalent
						gate=5d
						;;
					*)
						disposition=unresolved
						gate=5d
						;;
				esac
				printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
					"$path" "$namespace" "$classification" \
					"$(owner_for "$path")" "$disposition" "$gate"
			done
	done

	# The reviewed replacement map. These rows cannot be discovered by scanning:
	# the replacement classes deliberately contain no reference to the namespace
	# they replace, because gradle/phase5/zk-compile-closure.gradle fails the
	# build if one does.
	awk -F'\t' 'NF >= 5 && $0 !~ /^#/ {
			printf "%s\t%s\treplacement\t%s\treplaced-adempiere-owned\t%s\n",
				$1, $2, owner($1), $4
		}
		function owner(path,   head) {
			head = path
			sub(/\/.*$/, "", head)
			return head
		}' gradle/phase5/zk-namespace-replacements.tsv
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
