#!/usr/bin/env bash
# Phase 5d: materialize the frozen legacy webui.war from the Phase 5b source commit.
#
# Phase 5d migrates zkwebui/WEB-INF/src to ZK CE 10.3.0.1-jakarta. The migrated
# source can no longer be compiled against ZK 3.6, so the Ant reactor can no
# longer produce lib/webui.war from the working tree. The legacy web product is
# still shipped, still installed, and is still the Phase 5b oracle, so the WAR
# has to keep existing. This script produces it from the exact commit the Phase
# 5b oracle was frozen at, in an isolated Git worktree, so the shipped legacy
# artifact is a frozen build rather than a rebuild of migrated sources.
#
# The commit is read from the committed Phase 5b contract
# (contracts/legacy-web-v1/capture-environment.tsv). It is never guessed and
# never defaulted.
#
# Usage:
#   materialize-legacy-webui-war.sh <repo-root> <output-dir> [java-home]
#
# Environment:
#   ADEMPIERE_PHASE5D_FORCE=1        rebuild even when a matching artifact exists
#   ADEMPIERE_PHASE5D_MATERIALIZING  recursion guard, set by this script

set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
	echo "usage: $0 <repo-root> <output-dir> [java-home]" >&2
	exit 2
fi

REPO_ROOT=$(cd "$1" && pwd -P)
OUTPUT_DIR=$2
JAVA_HOME_ARG=${3:-${JAVA_HOME:-}}

if [[ -n "${ADEMPIERE_PHASE5D_MATERIALIZING:-}" ]]; then
	echo "FAIL: refusing to materialize the frozen legacy WAR from inside a frozen worktree" >&2
	exit 1
fi

CONTRACT="${REPO_ROOT}/contracts/legacy-web-v1/capture-environment.tsv"
if [[ ! -f "${CONTRACT}" ]]; then
	echo "FAIL: missing Phase 5b capture environment contract ${CONTRACT}" >&2
	exit 1
fi

# The frozen commit is contract metadata, not an argument. A caller cannot
# silently materialize a different commit than the oracle was frozen at.
FROZEN_COMMIT=$(awk -F'\t' '$1 == "source_commit" { print $2; found = 1 }
	END { if (!found) exit 1 }' "${CONTRACT}")
if [[ ! "${FROZEN_COMMIT}" =~ ^[0-9a-f]{40}$ ]]; then
	echo "FAIL: contracts/legacy-web-v1/capture-environment.tsv has no usable source_commit" >&2
	exit 1
fi

if ! git -C "${REPO_ROOT}" cat-file -e "${FROZEN_COMMIT}^{commit}" 2>/dev/null; then
	echo "FAIL: frozen source commit ${FROZEN_COMMIT} is not present in this repository" >&2
	exit 1
fi

mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR=$(cd "${OUTPUT_DIR}" && pwd -P)
WAR_OUT="${OUTPUT_DIR}/webui.war"
ZKPACKAGES_OUT="${OUTPUT_DIR}/zkpackages"
PROVENANCE="${OUTPUT_DIR}/provenance.tsv"

WORKTREE_ROOT="${REPO_ROOT}/build/phase5d/frozen-src"
# Every destructive path in this script is derived from this constant prefix and
# re-checked before use, so a bad argument can never widen the deletion.
case "${WORKTREE_ROOT}" in
	"${REPO_ROOT}/build/phase5d/frozen-src") ;;
	*)
		echo "FAIL: refusing to manage a worktree outside build/phase5d" >&2
		exit 1
		;;
esac

sha512_of() {
	if command -v shasum >/dev/null 2>&1; then
		shasum -a 512 "$1" | awk '{print $1}'
	else
		sha512sum "$1" | awk '{print $1}'
	fi
}

# Tracked-file state of the *current* worktree. The frozen build must never
# reach back into it; this is compared again after the build.
current_tracked_state() {
	git -C "${REPO_ROOT}" status --porcelain --untracked-files=no | LC_ALL=C sort
}

BEFORE_STATE=$(current_tracked_state)

remove_frozen_worktree() {
	if [[ -d "${WORKTREE_ROOT}" ]]; then
		git -C "${REPO_ROOT}" worktree remove --force "${WORKTREE_ROOT}" >/dev/null 2>&1 || true
	fi
	if [[ -d "${WORKTREE_ROOT}" ]]; then
		rm -rf "${WORKTREE_ROOT}"
	fi
	git -C "${REPO_ROOT}" worktree prune >/dev/null 2>&1 || true
}

trap remove_frozen_worktree EXIT

# Reuse guard. The Ant reactor calls this from every crossed entry, so an
# unchanged frozen commit must not re-run the frozen product reactor six times.
if [[ -z "${ADEMPIERE_PHASE5D_FORCE:-}" && -f "${WAR_OUT}" && -f "${PROVENANCE}" &&
	-d "${ZKPACKAGES_OUT}" ]]; then
	CACHED_COMMIT=$(awk -F'\t' '$1 == "source_commit" { print $2 }' "${PROVENANCE}" || true)
	CACHED_DIGEST=$(awk -F'\t' '$1 == "war_envelope_sha512" { print $2 }' "${PROVENANCE}" || true)
	if [[ "${CACHED_COMMIT}" == "${FROZEN_COMMIT}" && -n "${CACHED_DIGEST}" &&
		"${CACHED_DIGEST}" == "$(sha512_of "${WAR_OUT}")" ]]; then
		echo "Reusing materialized frozen legacy webui.war for ${FROZEN_COMMIT}"
		exit 0
	fi
fi

remove_frozen_worktree
mkdir -p "$(dirname "${WORKTREE_ROOT}")"

echo "Materializing legacy webui.war from frozen commit ${FROZEN_COMMIT}"
git -C "${REPO_ROOT}" worktree add --detach --force "${WORKTREE_ROOT}" "${FROZEN_COMMIT}" >/dev/null

CHECKED_OUT=$(git -C "${WORKTREE_ROOT}" rev-parse HEAD)
if [[ "${CHECKED_OUT}" != "${FROZEN_COMMIT}" ]]; then
	echo "FAIL: frozen worktree is at ${CHECKED_OUT}, expected ${FROZEN_COMMIT}" >&2
	exit 1
fi
if [[ -n "$(git -C "${WORKTREE_ROOT}" status --porcelain --untracked-files=no)" ]]; then
	echo "FAIL: frozen worktree is dirty immediately after checkout" >&2
	exit 1
fi
# The tracked ZK source trees are the thing this crossing is about; the reactor
# is allowed to rewrite its own tracked build products (lib/*.war and friends)
# inside the throwaway worktree, but it must never rewrite a ZK source.
FROZEN_ZK_SOURCE_PATHS=(
	'zkwebui/WEB-INF/src'
	'org.adempiere.pos/src/main/java/ui/zk'
	'org.eevolution.manufacturing/src/main/java/ui/zk'
	'org.eevolution.hr_and_payroll/src/main/java/ui/zk'
	'org.eevolution.warehouse/src/main/java/ui/zk'
	'org.spin.loan_management/src/main/java/ui/zk'
)
if [[ -e "${WORKTREE_ROOT}/zkwebui/build.gradle" ]]; then
	echo "FAIL: frozen worktree unexpectedly carries post-freeze zkwebui files" >&2
	exit 1
fi

if [[ -n "${JAVA_HOME_ARG}" && ! -x "${JAVA_HOME_ARG}/bin/javac" ]]; then
	echo "FAIL: ${JAVA_HOME_ARG} is not a usable JDK" >&2
	exit 1
fi

# The frozen build is contained: every ADempiere path it is told about points
# inside the throwaway worktree, so it can neither read nor write the installed
# product, the current lib/ tree, or the developer environment.
FROZEN_ROOT="${WORKTREE_ROOT}/build/phase5d-frozen-runtime"
mkdir -p "${FROZEN_ROOT}"

ANT_ARGS=(
	"-Denv.ADEMPIERE_SOURCE=${WORKTREE_ROOT}"
	"-Denv.ADEMPIERE_ROOT=${FROZEN_ROOT}"
	"-Denv.ADEMPIERE_HOME=${FROZEN_ROOT}/Adempiere"
	"-Denv.ADEMPIERE_INSTALL=${FROZEN_ROOT}/install"
	'-Denv.ADEMPIERE_VERSION_FILE=394LTS'
	# The frozen worktree materializes a shipped artifact; the Ant test lanes
	# belong to the reactor that runs in the current worktree.
	'-Dtest.performTests=false'
	'-Dtest.performUnitTests=false'
	'-Dtest.performIntegrationTests=false'
)

LOG_DIR="${OUTPUT_DIR}/logs"
mkdir -p "${LOG_DIR}"

ENV_ARGS=(
	"ADEMPIERE_PHASE5D_MATERIALIZING=1"
	"ADEMPIERE_HOME=${FROZEN_ROOT}/Adempiere"
	"ADEMPIERE_ROOT=${FROZEN_ROOT}"
	"ADEMPIERE_INSTALL=${FROZEN_ROOT}/install"
	"ADEMPIERE_VERSION_FILE=394LTS"
)
if [[ -n "${JAVA_HOME_ARG}" ]]; then
	ENV_ARGS+=("JAVA_HOME=${JAVA_HOME_ARG}")
fi

# The frozen reactor is invoked as one `jar` target rather than as a guessed
# module subset: the ZK artifacts depend on ../base/build, ../client/build,
# ../JasperReports/build, ../packages/*.jar and ../zkwebui/WEB-INF/classes, and
# only utils_dev/build.xml knows the order that produces them. `jar` is the
# product reactor without the install step, so nothing leaves the worktree.
echo "  frozen ant: utils_dev/build.xml jar"
if ! (
	cd "${WORKTREE_ROOT}/utils_dev" &&
		env "${ENV_ARGS[@]}" ant "${ANT_ARGS[@]}" jar
) >"${LOG_DIR}/frozen-reactor.log" 2>&1; then
	echo "FAIL: the frozen Ant reactor failed; see ${LOG_DIR}/frozen-reactor.log" >&2
	tail -60 "${LOG_DIR}/frozen-reactor.log" >&2 || true
	exit 1
fi

FROZEN_WAR="${WORKTREE_ROOT}/lib/webui.war"
if [[ ! -f "${FROZEN_WAR}" ]]; then
	echo "FAIL: the frozen Ant build produced no lib/webui.war" >&2
	exit 1
fi

# The installer merges zkpackages/**/lib/*.jar into the deployed webui.war, so
# the ZK UI jars of the packaged modules are part of the same frozen legacy
# artifact set and are materialized with it.
if [[ ! -d "${WORKTREE_ROOT}/zkpackages" ]]; then
	echo "FAIL: the frozen Ant build produced no zkpackages directory" >&2
	exit 1
fi
FROZEN_ZK_JARS=()
while IFS= read -r jar; do
	FROZEN_ZK_JARS+=("${jar}")
done < <(cd "${WORKTREE_ROOT}/zkpackages" && ls -1 *.jar | LC_ALL=C sort)
if [[ ${#FROZEN_ZK_JARS[@]} -eq 0 ]]; then
	echo "FAIL: the frozen Ant build produced no zkpackages jars" >&2
	exit 1
fi

if [[ -n "$(git -C "${WORKTREE_ROOT}" status --porcelain --untracked-files=no -- \
	"${FROZEN_ZK_SOURCE_PATHS[@]}")" ]]; then
	echo "FAIL: the frozen Ant build modified a frozen ZK source tree" >&2
	git -C "${WORKTREE_ROOT}" status --porcelain --untracked-files=no -- \
		"${FROZEN_ZK_SOURCE_PATHS[@]}" >&2
	exit 1
fi

AFTER_STATE=$(current_tracked_state)
if [[ "${BEFORE_STATE}" != "${AFTER_STATE}" ]]; then
	echo "FAIL: the frozen build changed tracked files in the current worktree" >&2
	diff <(printf '%s\n' "${BEFORE_STATE}") <(printf '%s\n' "${AFTER_STATE}") >&2 || true
	exit 1
fi

cp "${FROZEN_WAR}" "${WAR_OUT}.tmp"
mv "${WAR_OUT}.tmp" "${WAR_OUT}"

rm -rf "${ZKPACKAGES_OUT}"
mkdir -p "${ZKPACKAGES_OUT}"
for jar in "${FROZEN_ZK_JARS[@]}"; do
	cp "${WORKTREE_ROOT}/zkpackages/${jar}" "${ZKPACKAGES_OUT}/${jar}"
done

ENTRY_COUNT=$(unzip -Z1 "${WAR_OUT}" | grep -c -v '/$' || true)
if [[ "${ENTRY_COUNT}" -lt 1 ]]; then
	echo "FAIL: the materialized frozen WAR has no entries" >&2
	exit 1
fi

{
	printf '# Phase 5d materialized frozen legacy web artifact provenance\n'
	printf '# coordinate\tvalue\n'
	printf 'source_commit\t%s\n' "${FROZEN_COMMIT}"
	printf 'source_commit_provenance\tcontracts/legacy-web-v1/capture-environment.tsv\n'
	printf 'frozen_worktree_clean\tyes\n'
	printf 'frozen_zk_sources_clean\tyes\n'
	printf 'frozen_reactor_target\tutils_dev/build.xml:jar\n'
	printf 'war_envelope_sha512\t%s\n' "$(sha512_of "${WAR_OUT}")"
	printf 'war_entry_count\t%s\n' "${ENTRY_COUNT}"
	for jar in "${FROZEN_ZK_JARS[@]}"; do
		printf 'zkpackage_jar\t%s\t%s\n' "${jar}" "$(sha512_of "${ZKPACKAGES_OUT}/${jar}")"
	done
} >"${PROVENANCE}.tmp"
mv "${PROVENANCE}.tmp" "${PROVENANCE}"

echo "Materialized ${WAR_OUT} (${ENTRY_COUNT} entries) and ${#FROZEN_ZK_JARS[@]} ZK package jars from ${FROZEN_COMMIT}"
