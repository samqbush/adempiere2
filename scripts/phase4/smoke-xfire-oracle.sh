#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "Usage: smoke-xfire-oracle.sh <catalina-home> <catalina-base> <adempiere-home> <port> <evidence-dir> <operations-file> <baseline-exceptions-file>" >&2
  exit 64
fi

catalina_home=$1
catalina_base=$2
adempiere_home=$3
port=$4
evidence_dir=$5
operations_file=$6
baseline_exceptions_file=$7
repo_root=$(git rev-parse --show-toplevel)
phase3_root="$repo_root/build/phase3"
phase4_root="$repo_root/build/phase4"

canonical_target() {
  local target=$1
  mkdir -p "$(dirname "$target")"
  printf '%s/%s\n' "$(cd "$(dirname "$target")" && pwd -P)" "$(basename "$target")"
}

catalina_home=$(canonical_target "$catalina_home")
catalina_base=$(canonical_target "$catalina_base")
adempiere_home=$(canonical_target "$adempiere_home")
evidence_dir=$(canonical_target "$evidence_dir")

for target in "$catalina_home" "$catalina_base" "$adempiere_home"; do
  if [[ "$target" != "$phase3_root/"* ]]; then
    echo "Phase 4 oracle may use only the guarded Phase 3 runtime: $target" >&2
    exit 65
  fi
done
if [[ "$evidence_dir" != "$phase4_root/"* ]]; then
  echo "Phase 4 oracle evidence must stay below $phase4_root: $evidence_dir" >&2
  exit 65
fi
if [[ "$operations_file" != "$repo_root/org.adempiere.webservice/contracts/xfire-v1/operations.tsv" ||
      ! -f "$operations_file" ]]; then
  echo "Phase 4 operation replay requires the frozen operation inventory: $operations_file" >&2
  exit 65
fi
if [[ "$baseline_exceptions_file" != "$repo_root/gradle/phase4/operation-baseline-exceptions.tsv" ||
      ! -f "$baseline_exceptions_file" ]]; then
  echo "Phase 4 operation replay requires the reviewed baseline exceptions: $baseline_exceptions_file" >&2
  exit 65
fi
if [[ ! "$port" =~ ^[0-9]+$ ]]; then
  echo "Tomcat port must be numeric." >&2
  exit 65
fi

parity_dir="$evidence_dir/historical-path-parity"
mkdir -p "$evidence_dir/wsdl" "$evidence_dir/responses" \
  "$parity_dir/wsdl" "$parity_dir/operations"
set +u
source "$adempiere_home/utils/myEnvironment.sh" >/dev/null
set -u
export CATALINA_HOME="$catalina_home"
export CATALINA_BASE="$catalina_base"
export CATALINA_PID="$catalina_base/temp/phase4-xfire-oracle.pid"
export CATALINA_OPTS="-DADEMPIERE_HOME=$adempiere_home -DPropertyFile=$adempiere_home/AdempiereEnv.properties --add-opens java.base/java.lang=ALL-UNNAMED"

cleanup() {
  if [[ -f "$CATALINA_PID" ]]; then
    "$CATALINA_HOME/bin/catalina.sh" stop 30 -force >/dev/null 2>&1 || true
  fi
  if [[ -f "$CATALINA_BASE/logs/catalina.out" ]]; then
    cp "$CATALINA_BASE/logs/catalina.out" "$evidence_dir/catalina.out"
  fi
}
trap cleanup EXIT

: >"$CATALINA_BASE/logs/catalina.out"
"$CATALINA_HOME/bin/catalina.sh" start

status_url="http://127.0.0.1:$port/statusInfo"
for _ in $(seq 1 90); do
  if curl --fail --silent "$status_url" >"$evidence_dir/statusInfo.html" 2>/dev/null; then
    break
  fi
  if [[ -f "$CATALINA_PID" ]] && ! kill -0 "$(cat "$CATALINA_PID")" 2>/dev/null; then
    cat "$CATALINA_BASE/logs/catalina.out" >&2
    exit 1
  fi
  sleep 2
done
curl --fail --silent "$status_url" >/dev/null

services=(ADService ModelADService ExternalSales WebService)
for service in "${services[@]}"; do
  wsdl_url="http://127.0.0.1:$port/ADInterface/services/$service?wsdl"
  wsdl_file="$evidence_dir/wsdl/$service.wsdl"
  curl --fail --silent --show-error "$wsdl_url" --output "$wsdl_file"
  grep -Eq '<(wsdl:)?definitions[ >]' "$wsdl_file" || {
    echo "$service did not return a WSDL document." >&2
    exit 1
  }
  grep -Fq "name=\"$service\"" "$wsdl_file" || {
    echo "$service WSDL does not identify the expected service." >&2
    exit 1
  }
  historical_wsdl="$parity_dir/wsdl/$service.wsdl"
  curl --fail --silent --show-error \
    "http://127.0.0.1:$port/ADInterface/servlet/XFireServlet/$service?wsdl" \
    --output "$historical_wsdl"
  cmp "$wsdl_file" "$historical_wsdl" || {
    echo "$service WSDL differs between the two historical URL forms." >&2
    exit 1
  }
done

stable_headers() {
  tr -d '\r' <"$1" |
    grep -Ei '^(HTTP/|Content-Type:|Transfer-Encoding:|Content-Length:|Connection:)'
}

verify_historical_path_parity() {
  local service=$1
  local operation=$2
  local request=$3
  local content_type=$4
  local charset=$5
  local soap_action=$6
  local expected_status=$7
  local expected_response=$8
  local expected_headers=$9
  local parity_case="$parity_dir/operations/$service/$operation"
  mkdir -p "$parity_case"
  local status
  status=$(curl --silent --show-error \
    --header "Content-Type: $content_type; charset=$charset" \
    --header "SOAPAction: $soap_action" \
    --data-binary "@$request" \
    --dump-header "$parity_case/headers" \
    --output "$parity_case/response.xml" \
    --write-out '%{http_code}' \
    "http://127.0.0.1:$port/ADInterface/servlet/XFireServlet/$service")
  if [[ "$status" != "$expected_status" ]]; then
    echo "$service.$operation returned HTTP $status through the servlet URL; expected $expected_status." >&2
    exit 1
  fi
  cmp "$expected_response" "$parity_case/response.xml" || {
    echo "$service.$operation body differs between the two historical URL forms." >&2
    exit 1
  }
  diff -u \
    <(stable_headers "$expected_headers") \
    <(stable_headers "$parity_case/headers") || {
    echo "$service.$operation headers differ between the two historical URL forms." >&2
    exit 1
  }
}

cat >"$evidence_dir/request-ADService.xml" <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ad="http://3e.pl/ADInterface">
  <soapenv:Body><ad:getVersion/></soapenv:Body>
</soapenv:Envelope>
EOF
cat >"$evidence_dir/request-ModelADService.xml" <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ad="http://3e.pl/ADInterface">
  <soapenv:Body>
    <ad:queryData>
      <ad:ModelCRUDRequest>
        <ad:ModelCRUD>
          <ad:serviceType>phase4-oracle</ad:serviceType>
          <ad:TableName>AD_User</ad:TableName>
          <ad:RecordID>0</ad:RecordID>
          <ad:Filter>1=0</ad:Filter>
          <ad:RetriveResultAs>Element</ad:RetriveResultAs>
          <ad:Action>Read</ad:Action>
          <ad:PageNo>0</ad:PageNo>
        </ad:ModelCRUD>
        <ad:ADLoginRequest>
          <ad:user>phase4-invalid</ad:user>
          <ad:pass>phase4-invalid</ad:pass>
          <ad:lang>en_US</ad:lang>
          <ad:ClientID>0</ad:ClientID>
          <ad:RoleID>0</ad:RoleID>
          <ad:OrgID>0</ad:OrgID>
          <ad:WarehouseID>0</ad:WarehouseID>
          <ad:stage>0</ad:stage>
        </ad:ADLoginRequest>
      </ad:ModelCRUDRequest>
    </ad:queryData>
  </soapenv:Body>
</soapenv:Envelope>
EOF
cat >"$evidence_dir/request-ExternalSales.xml" <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ext="http://externalSales.ws.erpCommon.openbravo.org">
  <soapenv:Body><ext:getProductsCatalog><ext:in0>0</ext:in0><ext:in1>0</ext:in1><ext:in2>0</ext:in2><ext:in3>phase4-invalid</ext:in3><ext:in4>phase4-invalid</ext:in4></ext:getProductsCatalog></soapenv:Body>
</soapenv:Envelope>
EOF
cat >"$evidence_dir/request-WebService.xml" <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ext="http://externalSales.ws.erpCommon.openbravo.org">
  <soapenv:Body><ext:getCustomers><ext:in0>0</ext:in0><ext:in1>phase4-invalid</ext:in1><ext:in2>phase4-invalid</ext:in2></ext:getCustomers></soapenv:Body>
</soapenv:Envelope>
EOF

printf '# service\thttp_status\tsoap_fault\n' >"$evidence_dir/round-trips.tsv"
for service in "${services[@]}"; do
  response="$evidence_dir/responses/$service.xml"
  headers="$evidence_dir/responses/$service.headers"
  status=$(curl --silent --show-error \
    --header 'Content-Type: text/xml; charset=UTF-8' \
    --data-binary "@$evidence_dir/request-$service.xml" \
    --dump-header "$headers" \
    --output "$response" \
    --write-out '%{http_code}' \
    "http://127.0.0.1:$port/ADInterface/services/$service")
  if [[ ! "$status" =~ ^(200|500)$ ]]; then
    echo "$service SOAP dispatch returned unexpected HTTP $status." >&2
    exit 1
  fi
  grep -Eq '<(soapenv:|soap:|SOAP-ENV:)?Envelope[ >]' "$response" || {
    echo "$service SOAP dispatch did not return a SOAP envelope." >&2
    exit 1
  }
  fault=false
  grep -Eq '<(soapenv:|soap:|SOAP-ENV:)?Fault[ >]' "$response" && fault=true
  if grep -Eq 'Not enough message parts|Parameter [^<]+ does not exist' "$response"; then
    echo "$service reached XFire but the oracle request did not match its RPC contract." >&2
    exit 1
  fi
  printf '%s\t%s\t%s\n' "$service" "$status" "$fault" \
    >>"$evidence_dir/round-trips.tsv"
done

grep -Fq 'ADService' "$evidence_dir/round-trips.tsv"
grep -Fq 'ModelADService' "$evidence_dir/round-trips.tsv"
grep -Fq 'ExternalSales' "$evidence_dir/round-trips.tsv"
grep -Fq 'WebService' "$evidence_dir/round-trips.tsv"

operation_root="$evidence_dir/operations"
operation_results="$evidence_dir/operation-round-trips.tsv"
operation_mismatches="$evidence_dir/operation-contract-mismatches.tsv"
operation_residuals="$evidence_dir/operation-known-residuals.tsv"
printf '# service\toperation\thttp_status\tsoap_fault\tscope\tmutability\tauthentication\n' \
  >"$operation_results"
printf '# service\toperation\treason\n' >"$operation_mismatches"
printf '# service\toperation\tfault\towner\tclosing_action\n' >"$operation_residuals"
operation_count=0
while IFS= read -r operation_row; do
  operation_row=${operation_row//$'\t'/$'\034'}
  IFS=$'\034' read -r service operation namespace request_message request_parts \
      response_message response_parts soap_action endpoint url_forms http_method \
      content_type charset scope mutability authentication traffic_class phase4_gate \
      <<<"$operation_row"
  [[ "$service" == \#* ]] && continue
  case_dir="$operation_root/$service/$operation/baseline"
  request="$case_dir/request.xml"
  response="$case_dir/response.xml"
  headers="$case_dir/headers"
  if [[ ! -f "$request" || ! -f "$case_dir/case.tsv" ]]; then
    echo "Missing generated baseline request for $service.$operation." >&2
    exit 1
  fi
  status=$(curl --silent --show-error \
    --header "Content-Type: $content_type; charset=$charset" \
    --header "SOAPAction: $soap_action" \
    --data-binary "@$request" \
    --dump-header "$headers" \
    --output "$response" \
    --write-out '%{http_code}' \
    "http://127.0.0.1:$port/ADInterface/services/$service")
  if [[ ! "$status" =~ ^(200|500)$ ]]; then
    echo "$service.$operation baseline returned unexpected HTTP $status." >&2
    exit 1
  fi
  grep -Eq '<(soapenv:|soap:|SOAP-ENV:)?Envelope[ >]' "$response" || {
    echo "$service.$operation baseline did not return a SOAP envelope." >&2
    exit 1
  }
  fault=false
  grep -Eq '<(soapenv:|soap:|SOAP-ENV:)?Fault[ >]' "$response" && fault=true
  if grep -Eq 'Not enough message parts|Parameter [^<]+ does not exist' "$response"; then
    fault_message=$(sed -n 's:.*<faultstring>\([^<]*\)</faultstring>.*:\1:p' "$response")
    exception_row=$(awk -F '\t' -v service="$service" -v operation="$operation" \
      '$1 == service && $2 == operation { print; exit }' \
      "$baseline_exceptions_file")
    if [[ -n "$exception_row" ]]; then
      exception_row=${exception_row//$'\t'/$'\034'}
      IFS=$'\034' read -r exception_service exception_operation expected_fault \
        exception_owner exception_closing_action <<<"$exception_row"
      if [[ "$fault_message" == "$expected_fault" ]]; then
        printf '%s\t%s\t%s\t%s\t%s\n' \
          "$service" "$operation" "$fault_message" "$exception_owner" \
          "$exception_closing_action" >>"$operation_residuals"
      else
        printf '%s\t%s\t%s\n' \
          "$service" "$operation" \
          "unexpected RPC binding fault: $fault_message" \
          >>"$operation_mismatches"
      fi
    else
      printf '%s\t%s\t%s\n' \
        "$service" "$operation" \
        "unexpected RPC binding fault: $fault_message" \
        >>"$operation_mismatches"
    fi
  fi
  verify_historical_path_parity \
    "$service" "$operation" "$request" "$content_type" "$charset" \
    "$soap_action" "$status" "$response" "$headers"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$service" "$operation" "$status" "$fault" "$scope" "$mutability" \
    "$authentication" >>"$operation_results"
  operation_count=$((operation_count + 1))
done <"$operations_file"

if [[ "$operation_count" -ne 33 ]]; then
  echo "Expected 33 operation baselines, replayed $operation_count." >&2
  exit 1
fi
if [[ $(wc -l <"$operation_mismatches") -ne 1 ]]; then
  cat "$operation_mismatches" >&2
  exit 1
fi
