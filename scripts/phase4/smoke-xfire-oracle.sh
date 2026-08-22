#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: smoke-xfire-oracle.sh <catalina-home> <catalina-base> <adempiere-home> <port> <evidence-dir>" >&2
  exit 64
fi

catalina_home=$1
catalina_base=$2
adempiere_home=$3
port=$4
evidence_dir=$5
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
if [[ ! "$port" =~ ^[0-9]+$ ]]; then
  echo "Tomcat port must be numeric." >&2
  exit 65
fi

mkdir -p "$evidence_dir/wsdl" "$evidence_dir/responses"
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
done

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
