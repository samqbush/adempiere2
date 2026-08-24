#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
contracts="$repo_root/org.adempiere.webservice/contracts/xfire-v1"
base_url=${1:-http://127.0.0.1:8890/ADInterface/services}
mode=${2:-full}
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/adempiere-cxf-smoke.XXXXXX")
cleanup() {
	rm -rf "$work_dir"
}
trap cleanup EXIT

for service in ADService ModelADService ExternalSales WebService; do
	curl --fail --silent --show-error \
		--output "$work_dir/$service.wsdl" \
		"$base_url/$service?wsdl"
	cmp "$contracts/wsdl/$service.wsdl" "$work_dir/$service.wsdl"
done

replay() {
	local service=$1
	local operation=$2
	local expected_status=$3
	local fixture="$contracts/operations/$service/$operation/baseline"
	local actual="$work_dir/$service-$operation.xml"
	local status
	status=$(curl --silent --show-error \
		--output "$actual" \
		--write-out '%{http_code}' \
		--header 'Content-Type: text/xml; charset=UTF-8' \
		--header 'SOAPAction: ""' \
		--data-binary "@$fixture/request.xml" \
		"$base_url/$service")
	if [[ "$status" != "$expected_status" ]]; then
		echo "$service.$operation returned HTTP $status, expected $expected_status" >&2
		cat "$actual" >&2
		echo >&2
		exit 1
	fi
	local expected="$work_dir/$service-$operation-expected.xml"
	printf '%s' "$(cat "$fixture/response.xml")" >"$expected"
	if ! cmp "$expected" "$actual"; then
		cat "$actual" >&2
		echo >&2
		exit 1
	fi
}

if [[ "$mode" == "boot-only" ]]; then
	replay ADService getVersion 200
	unknown_status=$(curl --silent --show-error \
		--output /dev/null \
		--write-out '%{http_code}' \
		"$base_url/UnknownService?wsdl")
	if [[ "$unknown_status" != "404" ]]; then
		echo "Unknown WSDL returned HTTP $unknown_status, expected 404" >&2
		exit 1
	fi
	malformed_status=$(printf '<not-soap' | curl --silent --show-error \
		--output /dev/null \
		--write-out '%{http_code}' \
		--header 'Content-Type: text/xml; charset=UTF-8' \
		--data-binary @- \
		"$base_url/ADService")
	if [[ "$malformed_status" != "400" && "$malformed_status" != "500" ]]; then
		echo "Malformed XML returned HTTP $malformed_status, expected 400 or 500" >&2
		exit 1
	fi
	oversized_status=$(head -c 1048577 /dev/zero | curl --silent --show-error \
		--output /dev/null \
		--write-out '%{http_code}' \
		--header 'Content-Type: text/xml; charset=UTF-8' \
		--data-binary @- \
		"$base_url/ADService")
	if [[ "$oversized_status" != "413" ]]; then
		echo "Oversized SOAP body returned HTTP $oversized_status, expected 413" >&2
		exit 1
	fi
	echo "CXF API boots, serves four frozen WSDLs, and fails closed for unknown services"
	exit 0
fi

operation_count=0
while IFS=$'\t' read -r service operation expected_status _; do
	if [[ "$service" == \#* || -z "$service" ]]; then
		continue
	fi
	if [[ "$expected_status" != "200" && "$expected_status" != "500" ]]; then
		echo "$service.$operation has unsupported expected HTTP status $expected_status" >&2
		exit 1
	fi
	replay "$service" "$operation" "$expected_status"
	operation_count=$((operation_count + 1))
done <"$contracts/operation-round-trips.tsv"

if [[ "$operation_count" -ne 33 ]]; then
	echo "Replayed $operation_count operations, expected 33" >&2
	exit 1
fi
if [[ "$mode" == "baseline-only" ]]; then
	echo "CXF API preserves all 33 operation baselines"
	exit 0
fi

if [[ "$mode" == "full" ]]; then
	set +u
	source "$repo_root/build/phase3/runtime/Adempiere/utils/myEnvironment.sh" \
		>/dev/null
	set -u
fi

assert_scenario_state() {
	local operation=$1
	local fixture=$2
	local query
	case "$operation" in
		createData)
			query="SELECT 'C_BPartner', C_BPartner_ID, Value, Name, IsVendor, IsCustomer FROM C_BPartner WHERE Value='PHASE4-CXF-PARITY'"
			;;
		updateData)
			query="SELECT 'C_BPartner', C_BPartner_ID, Value, Name, COALESCE(URL, '') FROM C_BPartner WHERE C_BPartner_ID=1000003"
			;;
		deleteData)
			query="SELECT 'C_BPartner', C_BPartner_ID, Value, Name FROM C_BPartner WHERE C_BPartner_ID=1000004"
			;;
		runProcess)
			query="SELECT 'C_Invoice', C_Invoice_ID, DocumentNo, DocStatus, DocAction, Processed FROM C_Invoice WHERE C_Invoice_ID=103"
			;;
		*)
			return
			;;
	esac
	local actual_state
	actual_state=$(PGPASSWORD="$ADEMPIERE_DB_PASSWORD" psql \
		--host="$ADEMPIERE_DB_SERVER" \
		--port="$ADEMPIERE_DB_PORT" \
		--username="$ADEMPIERE_DB_USER" \
		--dbname="$ADEMPIERE_DB_NAME" \
		--tuples-only --no-align --field-separator=$'\t' \
		--command="$query")
	local expected_state
	expected_state=$(tail -n +2 "$fixture/state-after.tsv")
	if [[ "$actual_state" != "$expected_state" ]]; then
		echo "ModelADService.$operation database delta drifted." >&2
		printf 'Expected: %s\nActual: %s\n' "$expected_state" "$actual_state" >&2
		exit 1
	fi
}

scenario_count=0
while IFS= read -r fixture; do
	relative=${fixture#"$contracts/operations/"}
	service=${relative%%/*}
	remainder=${relative#*/}
	operation=${remainder%%/*}
	scenario=${remainder#*/}
	scenario=${scenario%%/*}
	replay_fixture="$contracts/operations/$service/$operation/$scenario"
	actual="$work_dir/$service-$operation-$scenario.xml"
	status=$(curl --silent --show-error \
		--output "$actual" \
		--write-out '%{http_code}' \
		--header 'Content-Type: text/xml; charset=UTF-8' \
		--header 'SOAPAction: ""' \
		--data-binary "@$replay_fixture/request.xml" \
		"$base_url/$service")
	expected_status=$(awk 'NR == 1 { print $2 }' "$replay_fixture/headers")
	if [[ "$status" != "$expected_status" ]]; then
		echo "$service.$operation/$scenario returned HTTP $status, expected $expected_status" >&2
		exit 1
	fi
	expected="$work_dir/$service-$operation-$scenario-expected.xml"
	printf '%s' "$(cat "$replay_fixture/response.xml")" >"$expected"
	if ! cmp "$expected" "$actual"; then
		echo "$service.$operation/$scenario response drifted." >&2
		diff -u "$expected" "$actual" >&2 || true
		exit 1
	fi
	if [[ "$mode" == "full" ]]; then
		assert_scenario_state "$operation" "$replay_fixture"
	fi
	scenario_count=$((scenario_count + 1))
done < <(find "$contracts/operations" \
	-path '*/request.xml' ! -path '*/baseline/request.xml' -print | sort)

expected_scenario_count=$(find "$contracts/operations" \
	-path '*/request.xml' ! -path '*/baseline/request.xml' | wc -l | tr -d ' ')
if [[ "$scenario_count" -ne "$expected_scenario_count" ]]; then
	echo "Replayed $scenario_count additional scenarios, expected $expected_scenario_count" >&2
	exit 1
fi

echo "CXF API preserves all 33 baselines and $scenario_count additional scenarios"
