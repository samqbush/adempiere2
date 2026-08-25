#!/usr/bin/env bash
# Phase 5b ZK 3.6 Asynchronous Update (AU) client library.
#
# Sourced by the protocol spike and by the capture driver so that both drive the
# legacy ZK 3.6 web UI through exactly one implementation of the wire format.
#
# Wire format, derived from the shipped unminified ZK client
# (zk.jar!/web/js/zk/html/au.org.js) rather than assumed:
#
#   * zkau.send (au.org.js:601-618) builds the POST body as
#         dtid=<desktop-id>
#         &cmd.<j>=<event-name>
#         &uuid.<j>=<component-uuid-or-empty>
#         &data.<j>=<url-encoded-value>      (repeated once per data element)
#     The separator between the index and the field name is a DOT, not an
#     underscore. Underscore forms are rejected by the server with
#     "Illegal request: cmd required".
#   * zkau._sendNow2 (au.org.js:621-624) carries the AU sequence id in the
#     ZK-SID request header. The server echoes it back as <rid> in the response.
#   * Responses are XML: <rs><rid>N</rid><r><c>command</c><d>payload</d></r></rs>
#     They are NOT JSON. Payloads are HTML-escaped, frequently CDATA-wrapped.
#   * zkau.cmd0.clientInfo (au.org.js) sends onClientInfo with an empty uuid and
#     exactly eight ordered data values:
#         timezoneOffset, screen.width, screen.height, screen.colorDepth,
#         innerWidth, innerHeight, innerX, innerY
#     Any other arity is rejected with "Illegal request: wrong data".
#
# The capture pins the eight onClientInfo values to constants so that
# locale/screen-dependent rendering cannot vary between captures.
set -euo pipefail

# Fixed, deterministic client-information vector. Changing any of these values
# changes the oracle and must be a recorded contract change.
ZKAU_TZ_OFFSET=0
ZKAU_SCREEN_WIDTH=1920
ZKAU_SCREEN_HEIGHT=1080
ZKAU_COLOR_DEPTH=24
ZKAU_INNER_WIDTH=1920
ZKAU_INNER_HEIGHT=980
ZKAU_INNER_X=0
ZKAU_INNER_Y=0

# Fixed request headers so that content negotiation cannot vary the capture.
ZKAU_USER_AGENT='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
ZKAU_ACCEPT_LANGUAGE='en-US,en;q=0.9'

# Populated by zkau_bootstrap.
ZKAU_BASE=""
ZKAU_JAR=""
ZKAU_DTID=""
ZKAU_SID=0

zkau_curl() {
  curl --silent --show-error \
    --cookie-jar "$ZKAU_JAR" --cookie "$ZKAU_JAR" \
    --user-agent "$ZKAU_USER_AGENT" \
    --header "Accept-Language: $ZKAU_ACCEPT_LANGUAGE" \
    "$@"
}

# zkau_bootstrap <base-url> <cookie-jar> <headers-out> <body-out>
# Performs the initial GET and extracts the live desktop id. The desktop id is
# random per session and is the primary token the normalizer rewrites.
zkau_bootstrap() {
  ZKAU_BASE=$1
  ZKAU_JAR=$2
  local headers_out=$3 body_out=$4
  ZKAU_SID=0
  : >"$ZKAU_JAR"

  zkau_curl --fail --location \
    --dump-header "$headers_out" \
    --output "$body_out" \
    "$ZKAU_BASE/"

  ZKAU_DTID=$(grep -oE 'z\.dtid="[^"]*"' "$body_out" | head -1 | sed -E 's/.*"([^"]*)"/\1/')
  if [[ -z "$ZKAU_DTID" ]]; then
    echo "Failed to extract ZK desktop id from bootstrap response." >&2
    return 1
  fi
}

# zkau_send <body-out> <cmd> <uuid> [data...]
# Sends a single AU event. The uuid must be a LIVE server-side uuid, never a
# normalized one: normalization is a capture-output concern only.
#
# When ZKAU_HEADERS_OUT is set, response headers are dumped there so the capture
# can record status and Set-Cookie behaviour per step. Faults arrive as an
# <c>alert</c> command under HTTP 200, so headers alone never prove success.
zkau_send() {
  local body_out=$1 cmd=$2 uuid=$3
  shift 3

  local content="dtid=${ZKAU_DTID}&cmd.0=${cmd}&uuid.0=${uuid}"
  local datum
  for datum in "$@"; do
    content+="&data.0=$(zkau_urlencode "$datum")"
  done

  local -a header_args=()
  if [[ -n "${ZKAU_HEADERS_OUT:-}" ]]; then
    header_args=(--dump-header "$ZKAU_HEADERS_OUT")
  fi

  ZKAU_SID=$((ZKAU_SID + 1))
  zkau_curl --fail \
    --request POST \
    --header 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8' \
    --header "ZK-SID: $ZKAU_SID" \
    --data "$content" \
    "${header_args[@]+"${header_args[@]}"}" \
    --output "$body_out" \
    "$ZKAU_BASE/zkau"
}

zkau_urlencode() {
  python3 -c 'import sys,urllib.parse; sys.stdout.write(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}

# zkau_client_info <body-out>
# AdempiereWebUI.java:227-236 constructs desktop content only after client
# information arrives, so this must be the first AU event of every capture.
zkau_client_info() {
  zkau_send "$1" onClientInfo "" \
    "$ZKAU_TZ_OFFSET" "$ZKAU_SCREEN_WIDTH" "$ZKAU_SCREEN_HEIGHT" "$ZKAU_COLOR_DEPTH" \
    "$ZKAU_INNER_WIDTH" "$ZKAU_INNER_HEIGHT" "$ZKAU_INNER_X" "$ZKAU_INNER_Y"
}

# zkau_assert_no_error <body-file> <step-label>
# The server reports protocol and application faults as an "alert" command
# rather than an HTTP error status, so a 200 alone proves nothing.
zkau_assert_no_error() {
  local body=$1 label=$2
  if grep -q '<c>alert</c>' "$body"; then
    echo "AU step '$label' returned an alert:" >&2
    sed -n 's/.*<d>\(.*\)<\/d>.*/  \1/p' "$body" | head -3 >&2
    return 1
  fi
}

# zkau_commands <body-file> -- ordered AU command names in the response.
# Command order and count are stable oracle fields.
zkau_commands() {
  grep -oE '<c>[A-Za-z0-9]+</c>' "$1" | sed -E 's/<\/?c>//g'
}

# zkau_named_ids <body-file>
# Components carrying an explicit zk_component_ID keep a stable, meaningful id
# through AdempiereIdGenerator; those are the ids the capture addresses.
zkau_named_ids() {
  grep -oE 'id="[A-Za-z][A-Za-z0-9_]*"' "$1" \
    | sed -E 's/id="([^"]*)"/\1/' \
    | grep -vE '^(zk_comp_[0-9]+|z_[a-z0-9_]+)$' \
    | sort -u
}
