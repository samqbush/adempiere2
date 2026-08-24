#!/usr/bin/env bash
# Phase 5b legacy web oracle capture driver.
#
# Drives the installed Tomcat 9 / ZK 3.6 product through the flows that Phase 5c
# onward will have to reproduce on ZK 10 / Jakarta, and records a normalized,
# comparable oracle.
#
# Two levels of proof, per the Phase 5b scoping decision:
#   * deep    - the /webui ZK application, driven at the AU protocol level
#               through bootstrap, authentication, role selection, the menu
#               tree, a read-only window open, and logout.
#   * shallow - the remaining deployed contexts, driven by the reviewed request
#               vectors in contracts/legacy-web-v1/context-request-vectors.tsv,
#               each carrying its own proof_strength.
#
# /ADInterface is deliberately NOT captured here. It is the Phase 4 SOAP surface
# and is already frozen by org.adempiere.webservice/contracts/xfire-v1/.
#
# H6: the capture authenticates as an ordinary seeded user through the ordinary
# login flow. No bypass, debug endpoint, permit-all, or test-only servlet is
# introduced to make the capture possible.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: capture-legacy-web-oracle.sh <port> <output-dir> <oracle-user>" >&2
  exit 64
fi

port=$1
out_dir=$2
oracle_user=$3
repo_root=$(git rev-parse --show-toplevel)
phase5b_root="$repo_root/build/phase5b"

mkdir -p "$(dirname "$out_dir")"
out_dir="$(cd "$(dirname "$out_dir")" && pwd -P)/$(basename "$out_dir")"
if [[ "$out_dir" != "$phase5b_root"* ]]; then
  echo "Phase 5b capture output must stay below $phase5b_root: $out_dir" >&2
  exit 65
fi
if [[ ! "$port" =~ ^[0-9]+$ ]]; then
  echo "Tomcat port must be numeric." >&2
  exit 65
fi

# Pin the capture client's locale and timezone so that anything formatted on
# THIS side of the wire is machine-independent.
#
# Note the limit of this: server-rendered dates come from Tomcat's JVM, not from
# here, so exporting TZ cannot make those reproducible. Two other mechanisms
# cover that. The normalizer rewrites both ISO and Java Date.toString() forms,
# including the zone abbreviation, so a server in MDT and a server in UTC
# produce the same normalized bytes. The Gradle task additionally boots Tomcat
# with -Duser.timezone=UTC, and capture-environment.tsv records the pinned
# values so any drift fails verifyPhase5OracleEnvironment rather than silently
# rebasing the oracle.
export TZ=UTC
export LC_ALL=C
export LANG=C

# shellcheck source=scripts/phase5/zk-au-client.sh
source "$repo_root/scripts/phase5/zk-au-client.sh"
normalize="$repo_root/scripts/phase5/normalize-web-capture.sh"
vectors="$repo_root/contracts/legacy-web-v1/context-request-vectors.tsv"

rm -rf "$out_dir"
mkdir -p "$out_dir/zk-bootstrap" "$out_dir/context-responses" "$out_dir/raw"

flows_tsv="$out_dir/zk-au-flows.tsv"
context_tsv="$out_dir/context-observed.tsv"
session_tsv="$out_dir/session-http-observed.tsv"

printf 'flow\tstep\tmethod\tpath\trequest_shape\thttp_status\tcontent_type\tcharset\tlocation_header\tset_cookie_shape\tdtid_present\tau_command_sequence\tbody_digest\tdisposition\n' >"$flows_tsv"
printf 'context\troute_id\tmethod\tpath\thttp_status\tcontent_type\tcharset\tlocation_header\tproof_strength\tbody_digest\n' >"$context_tsv"
printf 'observation\tvalue\tsource\n' >"$session_tsv"

base="http://127.0.0.1:$port/webui"
jar="$out_dir/raw/cookies.txt"

header_field() {
  # Header name is matched case-insensitively; the value is stripped of the
  # trailing CR that HTTP/1.1 leaves behind.
  awk -v want="$(printf '%s' "$1" | tr 'A-Z' 'a-z')" '
    BEGIN { IGNORECASE = 1 }
    {
      line = $0
      sub(/\r$/, "", line)
      idx = index(line, ":")
      if (idx == 0) next
      name = tolower(substr(line, 1, idx - 1))
      if (name == want) {
        value = substr(line, idx + 1)
        sub(/^ +/, "", value)
        print value
        exit
      }
    }' "$2"
}

http_status() {
  awk 'NR == 1 { sub(/\r$/, ""); print $2; exit }' "$1"
}

# Redirect targets carry the URL-rewritten session id, which is per-session and
# not behaviour. The target itself IS behaviour, so the id is normalized rather
# than the whole header being dropped.
normalize_location() {
  printf '%s' "$1" | sed -E 's/jsessionid=[0-9A-Fa-f]+/jsessionid=<SESSION>/Ig'
}

content_type_only() {
  printf '%s' "$1" | sed -E 's/;.*$//' | tr -d ' '
}

charset_only() {
  # Absent charset is recorded as `none` rather than blank, so a charset that
  # disappears in Phase 5c is a visible change and not an empty cell.
  local value
  value=$(printf '%s' "$1" | sed -nE 's/.*charset=([^;]*).*/\1/Ip' | tr -d ' ')
  printf '%s' "${value:-none}"
}

# Set-Cookie is reduced to its shape. The value is a per-session secret, but the
# attribute set is a security contract that Phase 5e must not silently relax.
set_cookie_shape() {
  local raw=$1
  if [[ -z "$raw" ]]; then
    printf 'none'
    return
  fi
  local name attrs
  name=${raw%%=*}
  attrs=$(printf '%s' "$raw" \
    | tr ';' '\n' \
    | tail -n +2 \
    | sed -E 's/^ +//; s/=.*$//' \
    | grep -v '^$' \
    | tr 'A-Z' 'a-z' \
    | sort \
    | paste -sd, -)
  printf '%s[%s]' "$name" "$attrs"
}

digest() {
  shasum -a 256 "$1" | awk '{print $1}'
}

# Join stdin lines with commas, or emit `none` when there are no lines.
#
# `paste -sd, -` is not portable for the empty case: BSD paste emits nothing
# while GNU paste emits a bare newline, so `sed 's/^$/none/'` produced an empty
# cell on macOS and `none` on Linux for the very same response. That made the
# frozen oracle disagree with itself across platforms. Joining here keeps the
# emitted value a property of the response instead of the host's coreutils.
join_or_none() {
  local joined
  joined=$(tr '\n' ',' | sed 's/,*$//')
  printf '%s' "${joined:-none}"
}

# record_step <flow> <step> <method> <path> <request-shape> <headers> <body> <disposition>
record_step() {
  local flow=$1 step=$2 method=$3 path=$4 shape=$5 headers=$6 body=$7 disposition=$8

  local ctype status location cookie normalized
  status=$(http_status "$headers")
  ctype=$(header_field content-type "$headers")
  location=$(header_field location "$headers")
  cookie=$(header_field set-cookie "$headers")

  normalized="$out_dir/zk-bootstrap/${flow}-$(printf '%02d' "$step")-${disposition}.txt"
  "$normalize" --dtid "$ZKAU_DTID" "$body" >"$normalized"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$flow" "$step" "$method" "$path" "$shape" \
    "$status" "$(content_type_only "$ctype")" "$(charset_only "$ctype")" \
    "$(normalize_location "${location:-none}")" "$(set_cookie_shape "$cookie")" \
    "$([[ -n "$ZKAU_DTID" ]] && echo yes || echo no)" \
    "$(zkau_commands "$body" | join_or_none)" \
    "$(digest "$normalized")" \
    "$disposition" >>"$flows_tsv"
}

echo "== deep oracle: /webui =="

# ---------------------------------------------------------------------------
# Step 1 - bootstrap.
# ---------------------------------------------------------------------------
zkau_bootstrap "$base" "$jar" "$out_dir/raw/01.headers" "$out_dir/raw/01.body"
record_step webui 1 GET /webui/ 'none' \
  "$out_dir/raw/01.headers" "$out_dir/raw/01.body" bootstrap

bootstrap_cookie=$(header_field set-cookie "$out_dir/raw/01.headers")
bootstrap_session=$(printf '%s' "$bootstrap_cookie" | sed -nE 's/^JSESSIONID=([^;]*).*/\1/p')

export ZKAU_HEADERS_OUT="$out_dir/raw/step.headers"

# ---------------------------------------------------------------------------
# Step 2 - client information.
# AdempiereWebUI.java:227-236 defers desktop content construction until this
# event arrives, so it must precede every other AU event. Its eight values are
# pinned in zk-au-client.sh so that screen/locale-dependent rendering cannot
# vary between captures.
# ---------------------------------------------------------------------------
zkau_client_info "$out_dir/raw/02.body"
zkau_assert_no_error "$out_dir/raw/02.body" onClientInfo
record_step webui 2 POST /webui/zkau 'onClientInfo(8 pinned values)' \
  "$ZKAU_HEADERS_OUT" "$out_dir/raw/02.body" clientinfo

# ---------------------------------------------------------------------------
# Steps 3-5 - authentication.
# Controls are located by their generated ids rather than hardcoded, so that a
# changed login form fails the capture loudly instead of silently addressing the
# wrong component. The login panel's OK button carries an explicit
# zk_component_ID, which SahiIdGenerator_v1 preserves.
# ---------------------------------------------------------------------------
user_field=$(python3 - "$out_dir/raw/01.body" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
i = s.find('"rowUser"')
m = re.search(r'id="(zk_comp_\d+)"[^>]*z\.type="zul\.vd\.Txbox"', s[i:i + 1200]) if i >= 0 else None
print(m.group(1) if m else '')
PY
)
pass_field=$(python3 - "$out_dir/raw/01.body" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
m = re.search(r'id="(zk_comp_\d+)"[^>]*type="password"', s)
print(m.group(1) if m else '')
PY
)
login_ok=$(zkau_named_ids "$out_dir/raw/01.body" | grep -E '^Ok[0-9]*$' | head -1 || true)

for control in user_field pass_field login_ok; do
  if [[ -z "${!control}" ]]; then
    echo "Login control '$control' not found; the login form changed." >&2
    exit 1
  fi
done

zkau_send "$out_dir/raw/03.body" onChange "$user_field" "$oracle_user"
record_step webui 3 POST /webui/zkau 'onChange(userid)' \
  "$ZKAU_HEADERS_OUT" "$out_dir/raw/03.body" userid

zkau_send "$out_dir/raw/04.body" onChange "$pass_field" "$oracle_user"
record_step webui 4 POST /webui/zkau 'onChange(password)' \
  "$ZKAU_HEADERS_OUT" "$out_dir/raw/04.body" password

zkau_send "$out_dir/raw/05.body" onClick "$login_ok"
zkau_assert_no_error "$out_dir/raw/05.body" login
record_step webui 5 POST /webui/zkau 'onClick(login-ok)' \
  "$ZKAU_HEADERS_OUT" "$out_dir/raw/05.body" rolescreen

# ---------------------------------------------------------------------------
# Step 6 - role selection.
# Every selector is pre-populated from the oracle user's stored preferences, so
# no onSelect events are required. The defaults are asserted rather than
# assumed: a changed default would silently rebase the entire oracle onto a
# different role, client, org, or warehouse.
# ---------------------------------------------------------------------------
# Expressed as a function rather than an associative array: macOS ships bash 3.2.
expected_role_default() {
  case "$1" in
    rowRole) printf 'GardenWorld Admin' ;;
    rowclient) printf 'GardenWorld' ;;
    rowOrganisation) printf '*' ;;
    rowWarehouse) printf '*' ;;
    *) printf '' ;;
  esac
}
for row in rowRole rowclient rowOrganisation rowWarehouse; do
  expected=$(expected_role_default "$row")
  actual=$(python3 - "$out_dir/raw/05.body" "$row" <<'PY'
import html, re, sys
s = html.unescape(open(sys.argv[1], encoding='utf-8', errors='replace').read())
i = s.find('"%s"' % sys.argv[2])
m = re.search(r'value="([^"]*)"', s[i:i + 900]) if i >= 0 else None
print(m.group(1) if m else '')
PY
)
  if [[ "$actual" != "$expected" ]]; then
    echo "Role screen default for $row is '$actual', expected '$expected'." >&2
    echo "The oracle fixture is not in its frozen state; refusing to capture." >&2
    exit 1
  fi
  printf '%s\t%s\t%s\n' "role_default_$row" "$actual" "runtime-observed" >>"$session_tsv"
done

role_ok=$(zkau_named_ids "$out_dir/raw/05.body" | grep -E '^Ok[0-9]*$' | head -1 || true)
[[ -n "$role_ok" ]] || { echo "Role screen OK button not found." >&2; exit 1; }

zkau_send "$out_dir/raw/06.body" onClick "$role_ok"
zkau_assert_no_error "$out_dir/raw/06.body" role-ok
record_step webui 6 POST /webui/zkau 'onClick(role-ok)' \
  "$ZKAU_HEADERS_OUT" "$out_dir/raw/06.body" desktop

grep -q 'Application Dictionary' "$out_dir/raw/06.body" \
  || { echo "Authenticated desktop is missing the menu tree." >&2; exit 1; }

# ---------------------------------------------------------------------------
# Step 7 - open a window from the menu tree.
# MenuPanel.java:161,180 registers ON_CLICK on the Treerow, not on the Tree and
# not on the Treeitem, so the capture clicks the row. "Error Message" is chosen
# because it is a small, stable, read-only maintenance window: opening it
# exercises window construction, tab building, and field rendering without
# creating or modifying business data.
# ---------------------------------------------------------------------------
read -r menu_row menu_label < <(python3 - "$out_dir/raw/06.body" <<'PY'
import html, re, sys
s = html.unescape(open(sys.argv[1], encoding='utf-8', errors='replace').read())
for row, item, label in re.findall(
        r'<tr id="(zk_comp_\d+)" z\.type="Trow"[^>]*z\.pitem="(zk_comp_\d+)"[^>]*>.*?mWindow\.png[^>]*/>\s*([^<]{2,40})', s):
    if label.strip() == 'Error Message':
        print(row, label.strip().replace(' ', '_'))
        break
PY
)
[[ -n "${menu_row:-}" ]] || { echo "Menu item 'Error Message' not found in the tree." >&2; exit 1; }
printf 'menu_window_row\t%s\t%s\n' "$menu_row" "runtime-observed" >>"$session_tsv"

zkau_send "$out_dir/raw/07.body" onClick "$menu_row"
zkau_assert_no_error "$out_dir/raw/07.body" window-open
record_step webui 7 POST /webui/zkau "onClick(menu-row:$menu_label)" \
  "$ZKAU_HEADERS_OUT" "$out_dir/raw/07.body" window

# ---------------------------------------------------------------------------
# Step 8 - logout.
# AdempiereWebUI.java:349-367 clears application caches and redirects to
# index.zul but never calls HttpSession.invalidate(). The capture records that
# negative behaviour as an observed fact so Phase 5e inherits an accurate
# baseline rather than an assumed one.
# ---------------------------------------------------------------------------
logout_button=$(python3 - "$out_dir/raw/06.body" <<'PY'
import html, re, sys
s = html.unescape(open(sys.argv[1], encoding='utf-8', errors='replace').read())
m = re.search(r'id="(zk_comp_\d+)"[^>]*>Log Out<', s)
print(m.group(1) if m else '')
PY
)
[[ -n "$logout_button" ]] || { echo "Logout control not found." >&2; exit 1; }

zkau_send "$out_dir/raw/08.body" onClick "$logout_button"
zkau_assert_no_error "$out_dir/raw/08.body" logout
record_step webui 8 POST /webui/zkau 'onClick(logout)' \
  "$ZKAU_HEADERS_OUT" "$out_dir/raw/08.body" logout

unset ZKAU_HEADERS_OUT

post_logout_session=$(awk '/JSESSIONID/ { print $7 }' "$jar" | tail -1)
{
  printf 'bootstrap_set_cookie_shape\t%s\tobserved\n' "$(set_cookie_shape "$bootstrap_cookie")"
  printf 'logout_redirect_target\t%s\tobserved\n' \
    "$(grep -A1 '<c>redirect</c>' "$out_dir/raw/08.body" | sed -nE 's:.*<d>(.*)</d>.*:\1:p' | head -1)"
  if [[ "$bootstrap_session" == "$post_logout_session" ]]; then
    printf 'logout_invalidates_http_session\tno\tobserved (session id unchanged across logout)\n'
  else
    printf 'logout_invalidates_http_session\tyes\tobserved (session id rotated across logout)\n'
  fi
} >>"$session_tsv"

# The session cookie the container issues after logout is still accepted, which
# is the direct consequence of never invalidating. Recorded rather than judged;
# remediation is Phase 5e's decision, not the oracle's.
zkau_curl --silent --output /dev/null --write-out '%{http_code}' \
  --dump-header "$out_dir/raw/09.headers" "$base/" >/dev/null
printf 'post_logout_bootstrap_status\t%s\tobserved\n' \
  "$(http_status "$out_dir/raw/09.headers")" >>"$session_tsv"

echo "  deep oracle: $(($(wc -l <"$flows_tsv") - 1)) steps recorded"

# ---------------------------------------------------------------------------
# Shallow oracle.
# Driven from the reviewed request-vector table rather than from whatever the
# capture happened to reach, so that coverage is asserted against the Phase 5a
# route inventory (H4).
# ---------------------------------------------------------------------------
echo "== shallow oracle: remaining deployed contexts =="

if [[ ! -f "$vectors" ]]; then
  echo "Missing request vectors: $vectors" >&2
  exit 66
fi

shallow_jar="$out_dir/raw/shallow-cookies.txt"
while IFS=$'\t' read -r route_id context method path body_shape expected_status proof_strength notes; do
  [[ "$route_id" == route_id || -z "$route_id" ]] && continue
  [[ "$route_id" == \#* ]] && continue

  : >"$shallow_jar"
  safe_name=$(printf '%s' "$route_id" | tr -c 'A-Za-z0-9._-' '_')
  headers="$out_dir/raw/ctx-$safe_name.headers"
  raw_body="$out_dir/raw/ctx-$safe_name.body"

  # Each vector gets a fresh cookie jar so that one context cannot leak session
  # state into another and make an unauthenticated route look reachable.
  ZKAU_JAR=$shallow_jar
  set +e
  zkau_curl --request "$method" --dump-header "$headers" --output "$raw_body" \
    "http://127.0.0.1:$port$path"
  curl_status=$?
  set -e
  if (( curl_status != 0 )); then
    echo "Request vector $route_id ($method $path) failed at the transport level." >&2
    exit 1
  fi

  ctype=$(header_field content-type "$headers")
  location=$(header_field location "$headers")
  status=$(http_status "$headers")
  normalized="$out_dir/context-responses/$safe_name.txt"
  ZKAU_DTID="" "$normalize" "$raw_body" >"$normalized"

  if [[ "$status" != "$expected_status" ]]; then
    echo "Request vector $route_id returned HTTP $status, expected $expected_status." >&2
    exit 1
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$context" "$route_id" "$method" "$path" "$status" \
    "$(content_type_only "$ctype")" "$(charset_only "$ctype")" \
    "$(normalize_location "${location:-none}")" "$proof_strength" "$(digest "$normalized")" >>"$context_tsv"
done <"$vectors"

echo "  shallow oracle: $(($(wc -l <"$context_tsv") - 1)) request vectors recorded"

# ---------------------------------------------------------------------------
# Served static-asset contract.
#
# Generated as part of the capture, not as a one-off, so the smoke re-proves on
# every run that packaged assets are still served with the same bytes and that
# the catch-all contexts still shadow theirs.
# ---------------------------------------------------------------------------
echo
echo "== served static-asset contract =="
"$repo_root/scripts/phase5/generate-static-asset-contract.sh" \
  "$repo_root/contracts/legacy-web-v1/installed-web-assets.tsv" \
  "$port" \
  "$phase5b_root/generated/static-asset-contract.tsv"
cp "$phase5b_root/generated/static-asset-contract.tsv" "$out_dir/static-asset-contract.tsv"

echo
echo "Capture written to $out_dir"
