#!/usr/bin/env bash
# Phase 5b normalizer.
#
# Implements contracts/legacy-web-v1/normalization-policy.md. Reduces a captured
# legacy web response to a comparable form by replacing ONLY the fields the
# policy classifies as `normalized`.
#
# This normalizer is deliberately narrow. Component uuids (zk_comp_<n>) are NOT
# normalized, because AdempiereIdGenerator delegates to SahiIdGenerator_v1,
# which makes them a deterministic function of the component construction path.
# Leaving them literal is what allows the oracle to detect changed component
# counts, changed construction order, and changed command targets.
#
# Desktop normalization is value-driven rather than pattern-driven: the actual
# desktop id is replaced literally wherever it occurs. ZK 3.6 derives several
# tokens from one desktop id (the `z.dtid` attribute, the
# `zk.process('clientInfo', ...)` argument, the `zkCmsp.start(...)` server-push
# bootstrap, and dynamic resource URLs under /zkau/view/<dtid>/), so a
# value-driven rule collapses them consistently while still failing if their
# relationship to each other changes.
#
# The transformation runs in Python rather than bash/sed. Authenticated desktop
# responses exceed 600 KB, where bash parameter-expansion substitution is
# quadratic and takes minutes, and BSD sed lacks the word-boundary support an
# earlier draft assumed.
#
# The output is a comparison artifact only. It must never be fed back into a
# request: the capture driver keeps a separate unnormalized copy for building
# subsequent requests.
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: normalize-web-capture.sh [--dtid <desktop-id>] [file]

Reads stdin when file is omitted. --dtid supplies the live desktop id for
captures that do not themselves carry a z.dtid attribute; the capture driver
always knows it, and AU responses never restate it.
EOF
  exit 64
}

explicit_dtid=${ZKAU_DTID:-}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dtid)
      [[ $# -ge 2 ]] || usage
      explicit_dtid=$2
      shift 2
      ;;
    -h|--help) usage ;;
    --) shift; break ;;
    -*) usage ;;
    *) break ;;
  esac
done

if [[ $# -gt 1 ]]; then
  usage
fi

input=${1:--}
if [[ "$input" != "-" && ! -f "$input" ]]; then
  echo "No such capture file: $input" >&2
  exit 66
fi

exec python3 - "$input" "$explicit_dtid" <<'PY'
import re
import sys

path, explicit_dtid = sys.argv[1], sys.argv[2]
if path == "-":
    body = sys.stdin.read()
else:
    with open(path, encoding="utf-8", errors="surrogateescape") as handle:
        body = handle.read()

# Value-driven desktop normalization. The desktop id is supplied by the capture
# driver when known, because ZK restates `z.dtid` only in the bootstrap
# document; AU responses embed the same id without ever naming it. Falling back
# to extraction keeps the normalizer usable standalone. Absent a desktop id
# (static assets, plain servlet responses) this is a no-op.
desktop_id = explicit_dtid
if not desktop_id:
    match = re.search(r'z\.dtid="([^"]*)"', body)
    desktop_id = match.group(1) if match else ""

if desktop_id:
    body = body.replace(desktop_id, "<DTID>")

# Each rule below corresponds to a `normalized` field in the policy. Rules are
# narrow on purpose: a broad rule would absorb a real regression.
RULES = (
    # Session identity. The cookie value and the URL-rewritten form are the same
    # secret in two encodings; both are session-scoped, neither is behaviour.
    (r'(?i)(jsessionid=)[0-9A-Fa-f]+', r'\1<SESSION>'),
    # Residual desktop id occurrences when the value was not recoverable.
    (r'dtid="[^"]*"', 'dtid="<DTID>"'),
    (r'"dtid":"[^"]*"', '"dtid":"<DTID>"'),
    # Page uuid. ZK derives it from the desktop id, so it is session-scoped.
    # Anchored to the ZK page-uuid shape so component uuids cannot be caught.
    (r'\bz_[a-z0-9]{1,8}_[0-9]+\b', '<PAGEUUID>'),
    (r'z\.zidsp="[^"]*"', 'z.zidsp="<PAGEUUID>"'),
    # ZK build cache-buster in static resource URLs.
    (r'/zkau/web/[0-9]+/', '/zkau/web/<ZKVER>/'),
    # Database-generated session id.
    (r'AD_Session_ID=[0-9]+', 'AD_Session_ID=<AD_SESSION>'),
    # Wall-clock timestamps rendered into payloads.
    (r'[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?', '<TIMESTAMP>'),
    # Java Date.toString() ("Mon Aug 24 12:16:50 MDT 2026"). Several legacy
    # servlets render it straight into the response body, and the web-store
    # servlets emit it URL-encoded, so both encodings are normalized. Without
    # this rule the shallow oracle is not reproducible.
    (r'[A-Z][a-z]{2} [A-Z][a-z]{2} [0-9]{1,2} [0-9]{2}:[0-9]{2}:[0-9]{2} [A-Z]{2,5} [0-9]{4}', '<TIMESTAMP>'),
    (r'[A-Z][a-z]{2}\+[A-Z][a-z]{2}\+[0-9]{1,2}\+[0-9]{2}%3A[0-9]{2}%3A[0-9]{2}\+[A-Z]{2,5}\+[0-9]{4}', '<TIMESTAMP>'),
    # Response headers. Content-Length is reduced to a presence class rather
    # than dropped, so an empty-vs-nonempty body change still fails.
    (r'(?im)^(Date|Expires|Last-Modified):.*$', r'\1: <HTTP-DATE>'),
    (r'(?im)^(ETag):.*$', r'\1: <ETAG>'),
    (r'(?im)^(Content-Length): *0 *$', r'\1: <EMPTY>'),
    (r'(?im)^(Content-Length): *[0-9]+ *$', r'\1: <NONEMPTY>'),
)

for pattern, replacement in RULES:
    body = re.sub(pattern, replacement, body)

sys.stdout.write(body if body.endswith("\n") else body + "\n")
PY
