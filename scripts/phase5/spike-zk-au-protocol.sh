#!/usr/bin/env bash
# Phase 5b protocol spike (RD-1).
#
# Purpose: prove that the ZK 3.6 Asynchronous Update protocol is drivable from
# an ordinary HTTP client using ordinary credentials, and record the observed
# wire-format facts that the normalization policy and capture driver depend on.
#
# The spike is a proof, not a gate. It asserts each transition so that a silent
# protocol regression cannot be mistaken for a passing spike. It deliberately
# stops after the authenticated desktop is reached; flow enumeration belongs to
# the capture driver.
#
# H6: no bypass, debug endpoint, or test-only servlet is used. The spike logs in
# exactly as a user does.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: spike-zk-au-protocol.sh <port> <evidence-dir> <oracle-user>" >&2
  exit 64
fi

port=$1
evidence_dir=$2
oracle_user=$3
repo_root=$(git rev-parse --show-toplevel)
phase5b_root="$repo_root/build/phase5b"

mkdir -p "$(dirname "$evidence_dir")"
evidence_dir="$(cd "$(dirname "$evidence_dir")" && pwd -P)/$(basename "$evidence_dir")"
if [[ "$evidence_dir" != "$phase5b_root/"* ]]; then
  echo "Phase 5b spike evidence must stay below $phase5b_root: $evidence_dir" >&2
  exit 65
fi
if [[ ! "$port" =~ ^[0-9]+$ ]]; then
  echo "Tomcat port must be numeric." >&2
  exit 65
fi

# shellcheck source=scripts/phase5/zk-au-client.sh
source "$repo_root/scripts/phase5/zk-au-client.sh"

rm -rf "$evidence_dir"
mkdir -p "$evidence_dir"

failures=0
note() { printf '  %s\n' "$*"; }
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

echo "== step 1: bootstrap GET /webui/ =="
zkau_bootstrap "http://127.0.0.1:$port/webui" \
  "$evidence_dir/cookies.txt" \
  "$evidence_dir/01-bootstrap.headers" \
  "$evidence_dir/01-bootstrap.html"

note "status:       $(head -1 "$evidence_dir/01-bootstrap.headers" | tr -d '\r')"
note "content-type: $(grep -i '^content-type:' "$evidence_dir/01-bootstrap.headers" | tr -d '\r')"
note "set-cookie:   $(grep -i '^set-cookie:' "$evidence_dir/01-bootstrap.headers" | tr -d '\r')"
note "desktop id:   $ZKAU_DTID"

# The session cookie carries neither Secure nor SameSite on this runtime. That
# is a frozen negative fact for Phase 5e, recorded here rather than assumed.
if grep -iq '^set-cookie:.*Secure' "$evidence_dir/01-bootstrap.headers"; then
  note "cookie Secure: present"
else
  note "cookie Secure: ABSENT (frozen negative fact, Phase 5e owns)"
fi

echo
echo "== step 2: onClientInfo =="
# AdempiereWebUI.java:227-236 defers content construction until client
# information arrives, so this event gates every later step.
zkau_client_info "$evidence_dir/02-clientinfo.xml"
zkau_assert_no_error "$evidence_dir/02-clientinfo.xml" onClientInfo \
  || fail "onClientInfo rejected"
note "commands: $(zkau_commands "$evidence_dir/02-clientinfo.xml" | tr '\n' ' ')"

echo
echo "== step 3: locate the login controls by named id =="
# AdempiereIdGenerator delegates to SahiIdGenerator_v1, which preserves an
# explicit zk_component_ID. Addressing named ids rather than positional
# zk_comp_N ordinals keeps the driver stable and makes component drift visible.
login_ok=$(zkau_named_ids "$evidence_dir/01-bootstrap.html" | grep -E '^Ok[0-9]*$' | head -1 || true)
if [[ -z "$login_ok" ]]; then
  fail "no named OK button found in the login bootstrap"
  login_ok="Ok42"
fi
note "login OK button: $login_ok"

user_field=$(python3 - "$evidence_dir/01-bootstrap.html" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
i = s.find('"rowUser"')
m = re.search(r'id="(zk_comp_\d+)"[^>]*z\.type="zul\.vd\.Txbox"', s[i:i + 1200]) if i >= 0 else None
print(m.group(1) if m else '')
PY
)
pass_field=$(python3 - "$evidence_dir/01-bootstrap.html" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'id="(zk_comp_\d+)"[^>]*type="password"', s)
print(m.group(1) if m else '')
PY
)
[[ -n "$user_field" ]] || fail "user id textbox not found"
[[ -n "$pass_field" ]] || fail "password textbox not found"
note "user field: ${user_field:-<none>}  password field: ${pass_field:-<none>}"

echo
echo "== step 4: authenticate =="
zkau_send "$evidence_dir/03-user.xml"  onChange "$user_field" "$oracle_user"
zkau_send "$evidence_dir/04-pass.xml"  onChange "$pass_field" "$oracle_user"
zkau_send "$evidence_dir/05-login.xml" onClick  "$login_ok"
zkau_assert_no_error "$evidence_dir/05-login.xml" login || fail "login rejected"

# A wrong control still returns HTTP 200 with a re-rendered panel, so the spike
# asserts on what the role screen must contain rather than on status alone.
role_ok=$(zkau_named_ids "$evidence_dir/05-login.xml" | grep -E '^Ok[0-9]*$' | head -1 || true)
if grep -q 'grdChooseRole' "$evidence_dir/05-login.xml" && [[ -n "$role_ok" ]]; then
  note "role screen reached; continue button: $role_ok"
else
  fail "login did not reach the role screen"
fi

for row in rowRole rowclient rowOrganisation rowWarehouse; do
  if grep -q "\"$row\"" "$evidence_dir/05-login.xml"; then
    value=$(python3 - "$evidence_dir/05-login.xml" "$row" <<'PY'
import html, re, sys
s = html.unescape(open(sys.argv[1], encoding='utf-8', errors='replace').read())
i = s.find('"%s"' % sys.argv[2])
m = re.search(r'value="([^"]*)"', s[i:i + 900]) if i >= 0 else None
print(m.group(1) if m else '')
PY
)
    note "$row default: ${value:-<empty>}"
  else
    fail "role screen is missing $row"
  fi
done

echo
echo "== step 5: complete role selection =="
# Every selector is pre-populated from the seeded defaults, so the spike does
# not need to post onSelect events to reach the desktop. The capture driver
# still asserts each default explicitly, because a changed default silently
# changes which role the oracle was captured under.
zkau_send "$evidence_dir/06-desktop.xml" onClick "${role_ok:-Ok109}"
zkau_assert_no_error "$evidence_dir/06-desktop.xml" role-ok || fail "role selection rejected"

desktop_bytes=$(wc -c <"$evidence_dir/06-desktop.xml" | tr -d ' ')
note "desktop response bytes: $desktop_bytes"
note "commands: $(zkau_commands "$evidence_dir/06-desktop.xml" | tr '\n' ' ')"

if grep -q 'Application Dictionary' "$evidence_dir/06-desktop.xml"; then
  note "menu tree present"
else
  fail "authenticated desktop does not contain the menu tree"
fi

echo
if (( failures > 0 )); then
  echo "Protocol spike FAILED with $failures assertion failure(s)." >&2
  echo "Evidence: $evidence_dir" >&2
  exit 1
fi

cat <<EOF

Protocol spike PASSED.

Resolved wire format (see scripts/phase5/zk-au-client.sh for the derivation):
  POST /webui/zkau
    Content-Type: application/x-www-form-urlencoded; charset=UTF-8
    ZK-SID: <monotonic sequence id, echoed as <rid>>
    body: dtid=<desktop-id>&cmd.<j>=<event>&uuid.<j>=<component>&data.<j>=<value>...
  Responses are XML <rs><r><c>cmd</c><d>payload</d></r></rs>, not JSON.
  onClientInfo takes exactly eight ordered values and an empty uuid.
  Faults arrive as an <c>alert</c> command under HTTP 200.

Evidence: $evidence_dir
EOF
