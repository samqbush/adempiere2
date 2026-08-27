#!/usr/bin/env bash
# Phase 5e: provision the shared Tomcat 9 <-> Tomcat 10 handoff key.
#
# Both runtimes execute under the same operating-system account, so the key is
# an ordinary file owned by that account rather than a credential exchanged over
# a network. Four properties are what make that safe, and this script is
# responsible for all four:
#
#   1. The key is generated here, from the operating system's CSPRNG. The
#      repository ships no key and no placeholder, so there is nothing to forget
#      to replace.
#   2. It is at least 32 bytes.
#   3. It is mode 0600 from the moment it exists - created under a restrictive
#      umask rather than created and then chmod'ed, so there is no window in
#      which it is readable.
#   4. It is written OUTSIDE every archive under ADEMPIERE_HOME, so it cannot be
#      packaged into a WAR, a release ZIP or a support bundle.
#
# The key is never printed, never logged and never echoed. The only thing this
# script reports is its path, its size and its mode.
set -euo pipefail

key_path=${1:?handoff key path is required}
key_bytes=${2:-48}

if [[ "$key_bytes" -lt 32 ]]; then
  echo "A handoff key needs at least 32 bytes; $key_bytes was requested" >&2
  exit 64
fi

key_dir=$(cd "$(dirname "$key_path")" 2>/dev/null && pwd || true)
if [[ -z "$key_dir" ]]; then
  mkdir -p "$(dirname "$key_path")"
  key_dir=$(cd "$(dirname "$key_path")" && pwd)
fi
key_path="$key_dir/$(basename "$key_path")"

case "$key_path" in
  *.war|*.jar|*.zip|*.ear|*.tar|*.tar.gz)
    echo "Refusing to write a handoff key into an archive path: $key_path" >&2
    exit 65
    ;;
esac

if [[ -e "$key_path" && ! -f "$key_path" ]]; then
  echo "The handoff key path exists and is not a regular file: $key_path" >&2
  exit 65
fi

if [[ -f "$key_path" ]]; then
  existing_size=$(wc -c <"$key_path" | tr -d ' ')
  existing_mode=$(stat -f '%Lp' "$key_path" 2>/dev/null || stat -c '%a' "$key_path")
  if [[ "$existing_size" -ge 32 && "$existing_mode" == "600" ]]; then
    # Re-provisioning would invalidate every ticket already in flight and every
    # modern session that has not yet bootstrapped. A usable key is left alone.
    printf 'handoff_key\t%s\nhandoff_key_bytes\t%s\nhandoff_key_mode\t%s\nhandoff_key_action\treused\n' \
      "$key_path" "$existing_size" "$existing_mode"
    exit 0
  fi
  echo "Replacing an unusable handoff key (${existing_size} bytes, mode ${existing_mode})" >&2
  rm -f "$key_path"
fi

# umask first: the file must never exist in a readable state, not even briefly.
umask 077
mkdir -p "$key_dir"
chmod 700 "$key_dir"

if [[ -r /dev/urandom ]]; then
  dd if=/dev/urandom of="$key_path" bs=1 count="$key_bytes" 2>/dev/null
else
  echo "No CSPRNG is available to generate a handoff key" >&2
  exit 70
fi

chmod 600 "$key_path"

size=$(wc -c <"$key_path" | tr -d ' ')
mode=$(stat -f '%Lp' "$key_path" 2>/dev/null || stat -c '%a' "$key_path")
if [[ "$size" != "$key_bytes" || "$mode" != "600" ]]; then
  rm -f "$key_path"
  echo "The generated handoff key is ${size} bytes at mode ${mode}; refusing it" >&2
  exit 70
fi

# A generated key must not be uniform or printable: those are exactly the shapes
# HandoffKey.load rejects as placeholders, and a generator that produced one
# would fail at deployment rather than here.
if LC_ALL=C grep -qc '^[[:print:]]*$' "$key_path" 2>/dev/null &&
   [[ "$(LC_ALL=C tr -d '[:print:]' <"$key_path" | wc -c | tr -d ' ')" == "0" ]]; then
  rm -f "$key_path"
  echo "The generated handoff key is entirely printable; refusing it" >&2
  exit 70
fi

printf 'handoff_key\t%s\nhandoff_key_bytes\t%s\nhandoff_key_mode\t%s\nhandoff_key_action\tgenerated\n' \
  "$key_path" "$size" "$mode"
