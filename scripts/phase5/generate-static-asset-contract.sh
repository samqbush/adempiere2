#!/usr/bin/env bash
#
# Phase 5b - derive the served static-asset contract from the installed-WAR
# inventory.
#
# Only entries classified `http-addressable-static` in installed-web-assets.tsv
# are candidates. Fetching all 1574 of them on every smoke would dominate the
# gate, so a deterministic sample is fetched over HTTP and its served bytes are
# compared against the packaged WAR entry digest. The sampling rule is recorded
# in the output header so the sample is reproducible and reviewable, and the
# rule itself is registered in oracle-exclusions.tsv.
#
# Sampling rule `min-sha512-per-war-extension-v1`:
#   Group candidates by (war, lowercased file extension). Within each group,
#   order by sha512 ascending and take the first SAMPLE_PER_GROUP entries.
#
# The rule is content-addressed, so it is stable across rebuilds, independent of
# archive entry order, and guarantees that every served extension in every
# deployed context is represented. It has no random seed.

set -euo pipefail

readonly INVENTORY="${1:?usage: generate-static-asset-contract.sh <installed-web-assets.tsv> <port> <output.tsv>}"
readonly PORT="${2:?missing port}"
readonly OUTPUT="${3:?missing output path}"
readonly SAMPLE_PER_GROUP="${SAMPLE_PER_GROUP:-2}"
readonly RULE="min-sha512-per-war-extension-v1"
readonly BASE="http://127.0.0.1:${PORT}"

[[ -f "${INVENTORY}" ]] || { echo "inventory not found: ${INVENTORY}" >&2; exit 1; }

case "${OUTPUT}" in
	*/build/phase5b/*|build/phase5b/*) : ;;
	*) echo "refusing to write outside build/phase5b: ${OUTPUT}" >&2; exit 1 ;;
esac

mkdir -p "$(dirname "${OUTPUT}")"
readonly WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

# Determine, per context, whether a catch-all servlet shadows packaged static
# content. Two distinct non-existent paths with different extensions are
# requested; if both return the same body, the context answers every path from
# one handler and its packaged static entries are never actually served.
# `/adempiere` and `/mobile` behave this way, so recording their entries as a
# plain digest "mismatch" would be misleading.
probe_shadowing() {
	local context="$1" a b
	a="$(curl -sS "${BASE}/${context}/zzz-phase5b-shadow-probe.png" 2>/dev/null | HOSTPORT="127.0.0.1:${PORT}" perl -pe 's/\Q$ENV{HOSTPORT}\E/<ORACLE-HOST-PORT>/g' | shasum -a 512 | cut -d' ' -f1)"
	b="$(curl -sS "${BASE}/${context}/zzz-phase5b-shadow-probe.css" 2>/dev/null | HOSTPORT="127.0.0.1:${PORT}" perl -pe 's/\Q$ENV{HOSTPORT}\E/<ORACLE-HOST-PORT>/g' | shasum -a 512 | cut -d' ' -f1)"
	if [[ -n "${a}" && "${a}" == "${b}" ]]; then
		printf '%s\t%s\n' "${context}" "${a}"
	else
		printf '%s\t%s\n' "${context}" "none"
	fi
}

shadow_digest_for() {
	awk -F'\t' -v c="$1" '$1 == c { print $2; exit }' "${WORK}/shadow.tsv"
}

# Select the sample: group by (war, extension), order by sha512, take the first
# SAMPLE_PER_GROUP of each group.
awk -F'\t' -v n="${SAMPLE_PER_GROUP}" '
	NR == 1 { next }
	$3 != "http-addressable-static" { next }
	{
		entry = $2
		ext = "none"
		if (entry ~ /\.[A-Za-z0-9]+$/) {
			ext = entry
			sub(/^.*\./, "", ext)
			ext = tolower(ext)
		}
		print $1 "\t" ext "\t" $6 "\t" $4 "\t" $5 "\t" $2
	}
' "${INVENTORY}" | LC_ALL=C sort -t $'\t' -k1,1 -k2,2 -k3,3 | awk -F'\t' -v n="${SAMPLE_PER_GROUP}" '
	{
		key = $1 "\t" $2
		if (key != prev) { prev = key; count = 0 }
		count++
		if (count <= n) { print }
	}
' > "${WORK}/sample.tsv"

readonly CANDIDATES="$(awk -F'\t' 'NR > 1 && $3 == "http-addressable-static"' "${INVENTORY}" | wc -l | tr -d ' ')"
readonly SAMPLED="$(wc -l < "${WORK}/sample.tsv" | tr -d ' ')"
readonly SAMPLE_GROUPS="$(cut -f1,2 "${WORK}/sample.tsv" | LC_ALL=C sort -u | wc -l | tr -d ' ')"

: > "${WORK}/shadow.tsv"
for context in adempiere admin mobile webui wstore; do
	probe_shadowing "${context}" >> "${WORK}/shadow.tsv"
done

{
	echo "# Phase 5b served static-asset contract"
	echo "# sampling_rule	${RULE}"
	echo "# sampling_seed	none (content-addressed selection is deterministic)"
	echo "# sample_per_group	${SAMPLE_PER_GROUP}"
	echo "# candidate_entries	${CANDIDATES}"
	echo "# sampled_entries	${SAMPLED}"
	echo "# sampled_groups	${SAMPLE_GROUPS}"
	echo "#"
	echo "# body normalization: the literal capture host:port is replaced by"
	echo "# <ORACLE-HOST-PORT> before content_length and served_sha512 are"
	echo "# computed, so the contract replays on any host and port. This is a"
	echo "# no-op for genuinely static entries."
	echo "# disposition values:"
	echo "#   served-exact                 response body digest equals the packaged WAR entry digest"
	echo "#   shadowed-by-catch-all-servlet the context answers every path from one handler, so the"
	echo "#                                packaged entry is never served; frozen as a negative fact"
	echo "#   served-differs               reachable but the body differs from the packaged entry"
	echo "#                                (server-side templating); frozen for change detection"
	printf '# war\tserved_path\thttp_status\tcontent_type\tcharset\tcontent_length\tpackaged_size\tpackaged_sha512\tserved_sha512\tdisposition\n'
} > "${OUTPUT}"

fetched=0
exact=0
shadowed=0
differs=0
while IFS=$'\t' read -r war ext sha url size entry; do
	[[ -n "${url}" ]] || continue
	context="${url#/}"
	context="${context%%/*}"
	headers="${WORK}/h"
	body="${WORK}/b"
	status="$(curl -sS -o "${body}" -D "${headers}" -w '%{http_code}' "${BASE}${url}" || echo "000")"

	ctype="$(awk 'BEGIN{IGNORECASE=1} /^Content-Type:/{sub(/^[^:]*: */,""); gsub(/\r/,""); print; exit}' "${headers}")"
	charset="none"
	case "${ctype}" in
		*charset=*) charset="${ctype##*charset=}"; charset="${charset%%;*}" ;;
	esac
	ctype_only="${ctype%%;*}"
	ctype_only="$(printf '%s' "${ctype_only}" | tr -d ' ')"
	[[ -n "${ctype_only}" ]] || ctype_only="none"

	# The served body may embed the capture host and port (the `.jnlp` templates
	# substitute the request URL into `codebase`/`href`). Digesting raw bytes
	# would make the contract unreplayable on a different host or port, so the
	# single documented normalization below is applied before digesting. It is a
	# no-op for genuinely static entries, which is why served-exact rows still
	# compare equal to the packaged digest.
	HOSTPORT="127.0.0.1:${PORT}" perl -pe 's/\Q$ENV{HOSTPORT}\E/<ORACLE-HOST-PORT>/g' < "${body}" > "${WORK}/bn"
	clen="$(wc -c < "${WORK}/bn" | tr -d ' ')"
	served_sha="$(shasum -a 512 "${WORK}/bn" | cut -d' ' -f1)"
	shadow_sha="$(shadow_digest_for "${context}")"

	if [[ "${served_sha}" == "${sha}" ]]; then
		disposition="served-exact"
		exact=$((exact + 1))
	elif [[ "${shadow_sha}" != "none" && "${served_sha}" == "${shadow_sha}" ]]; then
		disposition="shadowed-by-catch-all-servlet"
		shadowed=$((shadowed + 1))
	else
		disposition="served-differs"
		differs=$((differs + 1))
	fi

	printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
		"${war}" "${url}" "${status}" "${ctype_only}" "${charset}" \
		"${clen}" "${size}" "${sha}" "${served_sha}" "${disposition}" >> "${OUTPUT}"
	fetched=$((fetched + 1))
done < "${WORK}/sample.tsv"

echo "  static-asset contract: ${fetched} sampled entries across ${SAMPLE_GROUPS} (war, extension) groups of ${CANDIDATES} candidates"
echo "  static-asset contract: ${exact} served-exact, ${shadowed} shadowed-by-catch-all-servlet, ${differs} served-differs"

