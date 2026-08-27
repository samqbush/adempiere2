#!/usr/bin/env bash
# Phase 5e: rehearse rollback, and prove it survives the installer's own rebuild.
#
# The interesting failure this exists to catch is not "did the files come back".
# It is this: the installer's `setupWLib` target rebuilds lib/webui.war on every
# install and update by merging zkcustomization.jar, the 2Pack zkpackages jars
# and zkpatches.jar over lib/webuiOriginal.war
# (install/Adempiere/build.xml, target setupWLib). Its companion target
# `backupWebuiOriginal` is guarded by unless="webuiOriginal.exists", so it only
# ever creates that file once.
#
# A rollback that restores lib/webui.war but leaves a DERIVED
# lib/webuiOriginal.war behind therefore looks complete, passes every digest
# check, and silently reintroduces the routed archive on the operator's next
# install. That is the exact shape of an "irreversible" rollback.
#
# The merge is run by ANT, using the `setupWLib` and `backupWebuiOriginal`
# bodies READ OUT OF install/Adempiere/build.xml at run time, with real merge
# inputs (a site customisation jar, a patches jar and a 2Pack package jar) that
# each carry a marker entry. Two things follow that a hand-written unzip/rezip
# could not establish:
#
#   * The merge is not an identity transform. The markers have to appear in the
#     rebuilt archive, so a rehearsal that quietly stopped merging fails.
#   * The precedence is Ant's own `duplicate="preserve"` first-seen-wins over
#     the real zipfileset order, so a site customisation that ships its own
#     WEB-INF/web.xml is observed to WIN - which is why the Phase 5e deployment
#     completeness listener exists.
#
# If the target bodies can no longer be located in install/Adempiere/build.xml,
# the rehearsal fails rather than falling back to an approximation.
#
# The rehearsal never touches the live installed product: it works on copies.
set -euo pipefail

repo_root=${1:?repository root is required}
installed_home=${2:?installed home is required}
zip_archive=${3:?release ZIP is required}
tar_archive=${4:?release TAR is required}
pristine_war=${5:?pristine legacy WAR is required}
evidence_dir=${6:?evidence directory is required}
modern_war=${7:?modern webui-modern.war is required}

manifest_path=config/phase5e-routing.sha256
context_path=tomcat10-api/conf/Catalina/localhost/webui.xml
routed_modern_path=tomcat10-api/phase5e/webui-modern.war
superseded_modern_path=tomcat10-api/webapps/webui-modern.war
superseded_manifest=config/phase5c-web-overlay.sha256
installer_build_file="$repo_root/install/Adempiere/build.xml"

rm -rf "$evidence_dir"
mkdir -p "$evidence_dir/installed" "$evidence_dir/zip" "$evidence_dir/tar"

digest() {
  shasum -a 256 "$1" | awk '{print $1}'
}

logical_war_digest() {
  # A logical digest over the sorted (entry, content-digest) pairs rather than
  # the envelope: two zip encoders produce different envelopes from identical
  # content, and rollback has to be judged on content.
  local archive=$1 work
  work=$(mktemp -d "$evidence_dir/logical.XXXXXX")
  unzip -qq -o "$archive" -d "$work"
  ( cd "$work" && find . -type f -print0 | LC_ALL=C sort -z |
      xargs -0 shasum -a 256 ) | shasum -a 256 | awk '{print $1}'
  rm -rf "$work"
}

pristine_logical=$(logical_war_digest "$pristine_war")

# ---------------------------------------------------------------------------
# 1. Roll back the installed product on a copy.
# ---------------------------------------------------------------------------
cp -R "$installed_home/lib" "$evidence_dir/installed/"
cp -R "$installed_home/config" "$evidence_dir/installed/"
mkdir -p "$evidence_dir/installed/$(dirname "$context_path")"
cp "$installed_home/$context_path" "$evidence_dir/installed/$context_path"
mkdir -p "$evidence_dir/installed/$(dirname "$routed_modern_path")"
cp "$installed_home/$routed_modern_path" \
  "$evidence_dir/installed/$routed_modern_path"
mkdir -p "$evidence_dir/installed/tomcat/webapps"
if [[ -f "$installed_home/tomcat/webapps/webui.war" ]]; then
  cp "$installed_home/tomcat/webapps/webui.war" \
    "$evidence_dir/installed/tomcat/webapps/webui.war"
fi

# Rolling back Phase 5e returns the tree to the state Phase 5c/5d left it in:
# the routed artifacts go, the auto-deployed modern archive and its manifest
# come back.
rollback_home() {
  local home=$1
  # Restore the pristine artifact over the routed one...
  cp "$pristine_war" "$home/lib/webui.war"
  # ...and remove the backup the installer would otherwise merge from. Deleting
  # rather than overwriting is deliberate: the next backupWebuiOriginal run then
  # recreates it from the restored pristine webui.war, which is the only state
  # in which a future setupWLib cannot resurrect the overlay.
  rm -f "$home/lib/webuiOriginal.war"
  rm -f "$home/$manifest_path"
  rm -f "$home/$context_path"
  # The Tomcat 9 DEPLOYED copy and any expansion of it are part of the overlay.
  # A rollback that leaves a routed archive in webapps is a rollback the next
  # container start undoes.
  rm -rf "$home/tomcat/webapps/webui"
  if [[ -f "$home/tomcat/webapps/webui.war" ]]; then
    cp "$pristine_war" "$home/tomcat/webapps/webui.war"
  fi
  # The modern archive returns to the Phase 5c/5d location it was moved from.
  rm -rf "$home/tomcat10-api/phase5e"
  mkdir -p "$home/$(dirname "$superseded_modern_path")"
  cp "$modern_war" "$home/$superseded_modern_path"
  mkdir -p "$home/config"
  printf '%s  %s\n' "$(digest "$home/$superseded_modern_path")" \
    "$superseded_modern_path" >"$home/$superseded_manifest"
}

rollback_home "$evidence_dir/installed"

for absent in \
  "$evidence_dir/installed/lib/webuiOriginal.war" \
  "$evidence_dir/installed/$manifest_path" \
  "$evidence_dir/installed/$context_path" \
  "$evidence_dir/installed/tomcat10-api/phase5e" \
  "$evidence_dir/installed/tomcat/webapps/webui"; do
  if [[ -e "$absent" ]]; then
    echo "Rollback left $absent behind" >&2
    exit 1
  fi
done
for present in \
  "$evidence_dir/installed/$superseded_modern_path" \
  "$evidence_dir/installed/$superseded_manifest"; do
  if [[ ! -e "$present" ]]; then
    echo "Rollback did not restore $present" >&2
    exit 1
  fi
done
( cd "$evidence_dir/installed" && shasum -a 256 --check "$superseded_manifest" >/dev/null )

restored_logical=$(logical_war_digest "$evidence_dir/installed/lib/webui.war")
if [[ "$restored_logical" != "$pristine_logical" ]]; then
  echo "The rolled-back webui.war is not the pristine logical artifact" >&2
  exit 1
fi
if [[ -f "$evidence_dir/installed/tomcat/webapps/webui.war" ]] &&
    [[ "$(logical_war_digest "$evidence_dir/installed/tomcat/webapps/webui.war")" \
      != "$pristine_logical" ]]; then
  echo "The rolled-back Tomcat 9 deployment is not the pristine artifact" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 2. Re-run the installer's own merge with Ant, exactly as an operator's next
#    install would, and prove the routed archive does not come back.
# ---------------------------------------------------------------------------
merge_home="$evidence_dir/setupwlib"
mkdir -p "$merge_home/lib" "$merge_home/zkpackages/phase5e-rehearsal/lib" \
  "$merge_home/marker"
cp "$evidence_dir/installed/lib/webui.war" "$merge_home/lib/webui.war"

# Real merge inputs. Each carries a marker entry, and the customisation jar also
# carries its own WEB-INF/web.xml so the observed precedence is recorded rather
# than assumed.
printf 'phase5e-rehearsal-customization\n' \
  >"$merge_home/marker/phase5e-customization.marker"
printf '<web-app id="phase5e-rehearsal-customization"/>\n' \
  >"$merge_home/marker/web.xml"
printf 'phase5e-rehearsal-patch\n' >"$merge_home/marker/phase5e-patch.marker"
printf 'phase5e-rehearsal-package\n' >"$merge_home/marker/phase5e-package.marker"

mkdir -p "$merge_home/stage/customization/WEB-INF" \
  "$merge_home/stage/patches" "$merge_home/stage/package"
cp "$merge_home/marker/phase5e-customization.marker" \
  "$merge_home/stage/customization/phase5e-customization.marker"
cp "$merge_home/marker/web.xml" \
  "$merge_home/stage/customization/WEB-INF/web.xml"
cp "$merge_home/marker/phase5e-patch.marker" \
  "$merge_home/stage/patches/phase5e-patch.marker"
cp "$merge_home/marker/phase5e-package.marker" \
  "$merge_home/stage/package/phase5e-package.marker"
( cd "$merge_home/stage/customization" && zip -qr "$merge_home/lib/zkcustomization.jar" . )
( cd "$merge_home/stage/patches" && zip -qr "$merge_home/lib/zkpatches.jar" . )
( cd "$merge_home/stage/package" && zip -qr \
    "$merge_home/zkpackages/phase5e-rehearsal/lib/phase5e-rehearsal.jar" . )

# The real target bodies, read out of the real installer build file. The
# `depends` attribute is the only thing rewritten, because setupInit performs a
# full environment setup that a rollback rehearsal must not run.
python3 - "$installer_build_file" "$merge_home/build.xml" \
  "$merge_home/manifest-exclude.txt" <<'PY'
import re
import sys

source, target = sys.argv[1], sys.argv[2]
text = open(source, encoding='utf-8').read()


def body(name):
    match = re.search(
        r'<target name="%s"[^>]*>.*?</target>' % re.escape(name),
        text, re.S)
    if match is None:
        raise SystemExit(
            'install/Adempiere/build.xml no longer declares target %s; the '
            'Phase 5e rollback rehearsal cannot exercise the real merge' % name)
    return match.group(0)


patternset = re.search(r'<patternset id="manifest\.exclude">.*?</patternset>',
                       text, re.S)
if patternset is None:
    raise SystemExit(
        'install/Adempiere/build.xml no longer declares manifest.exclude')

setup_wlib = body('setupWLib')
if 'lib/webuiOriginal.war' not in setup_wlib:
    raise SystemExit('setupWLib no longer merges from lib/webuiOriginal.war')
# setupInit is an environment setup, not part of the merge under test.
setup_wlib = re.sub(r'depends="[^"]*"', 'depends="backupWebuiOriginal"',
                    setup_wlib, count=1)

open(target, 'w', encoding='utf-8').write(
    '<project name="phase5e-setupwlib-rehearsal" default="setupWLib" '
    'basedir=".">\n'
    + patternset.group(0) + '\n'
    + body('backupWebuiOriginal') + '\n'
    + setup_wlib + '\n'
    + '</project>\n')

# The patternset names the entries the real merge deliberately drops. Reading it
# out of the build file, rather than hard-coding a guess, is what keeps the
# "nothing else was dropped" assertion honest when the installer changes.
excludes = re.findall(r'<exclude name="([^"]+)"\s*/>', patternset.group(0))
if not excludes:
    raise SystemExit('manifest.exclude declares no exclusion')
open(sys.argv[3], 'w', encoding='utf-8').write('\n'.join(excludes) + '\n')
PY

( cd "$merge_home" && ant -q -f build.xml setupWLib >"$evidence_dir/setupwlib-ant.log" 2>&1 ) || {
  echo "The real installer setupWLib merge failed during the rollback rehearsal" >&2
  cat "$evidence_dir/setupwlib-ant.log" >&2
  exit 1
}

# The merge must not be an identity transform: every marker has to be inside the
# rebuilt archive, or the rehearsal proves nothing about a real install.
rebuilt_entries=$(unzip -Z1 "$merge_home/lib/webui.war")
for marker in \
  phase5e-customization.marker \
  phase5e-patch.marker \
  phase5e-package.marker; do
  if ! grep -Fq "$marker" <<<"$rebuilt_entries"; then
    echo "The setupWLib rehearsal did not merge $marker; the merge was a no-op" >&2
    exit 1
  fi
done

# Ant's duplicate="preserve" is first-seen-wins over the declared zipfileset
# order, and zkcustomization.jar is declared first. The site descriptor
# therefore WINS over the one inside webuiOriginal.war. That is recorded as an
# observed installer property, not asserted as desirable.
merged_web_xml=$(unzip -p "$merge_home/lib/webui.war" WEB-INF/web.xml)
if ! grep -Fq 'phase5e-rehearsal-customization' <<<"$merged_web_xml"; then
  echo "The observed setupWLib precedence changed: a site WEB-INF/web.xml no " \
    "longer wins over lib/webuiOriginal.war" >&2
  exit 1
fi

# And, the point of the rehearsal: none of the Phase 5e overlay comes back.
if grep -Fq 'webui-cohort-bridge.jar' <<<"$rebuilt_entries"; then
  echo "A post-rollback setupWLib rebuild reintroduced the Phase 5e bridge" >&2
  exit 1
fi
if grep -Fq 'phase5eCohortRouter' <<<"$merged_web_xml"; then
  echo "A post-rollback setupWLib rebuild reintroduced the Phase 5e router" >&2
  exit 1
fi
if unzip -p "$merge_home/lib/webui.war" WEB-INF/zk.xml |
    grep -Fq 'CohortDecisionInterceptor'; then
  echo "A post-rollback setupWLib rebuild reintroduced the Phase 5e interceptor" >&2
  exit 1
fi

# Every entry of the pristine archive has to survive the merge EXCEPT the ones
# the installer's own manifest.exclude patternset drops. That exclusion set is
# read out of install/Adempiere/build.xml rather than guessed, so this stays an
# assertion about the real installer: the merge adds, and it drops only what the
# real build file says it drops.
#
# This is the first thing the earlier hand-written unzip/rezip rehearsal hid.
# Ant's <zip> writes no manifest and the patternset excludes META-INF/MANIFEST.MF
# and the signature entries, so a real setupWLib really does drop them - which a
# rehearsal that just re-zipped the same tree could never have shown.
pristine_entries=$(unzip -Z1 "$pristine_war" | LC_ALL=C sort)
unexpected_drops=""
while IFS= read -r entry; do
  [[ -n "$entry" ]] || continue
  allowed=no
  while IFS= read -r pattern; do
    [[ -n "$pattern" ]] || continue
    # shellcheck disable=SC2053
    if [[ "$entry" == $pattern ]]; then
      allowed=yes
      break
    fi
  done <"$merge_home/manifest-exclude.txt"
  if [[ "$allowed" == no ]]; then
    unexpected_drops+="$entry"$'\n'
  fi
done < <(comm -23 <(printf '%s\n' "$pristine_entries") \
  <(printf '%s\n' "$rebuilt_entries" | LC_ALL=C sort))
if [[ -n "$unexpected_drops" ]]; then
  echo "The setupWLib rebuild dropped pristine entries the installer's own " \
    "manifest.exclude does not name:" >&2
  printf '%s' "$unexpected_drops" | head -10 >&2
  exit 1
fi
observed_drops=$(comm -23 <(printf '%s\n' "$pristine_entries") \
  <(printf '%s\n' "$rebuilt_entries" | LC_ALL=C sort) | tr '\n' ',' |
  sed 's/,$//')

# ---------------------------------------------------------------------------
# 3. Roll back both release archives and prove the same absences.
# ---------------------------------------------------------------------------
unzip -qq "$zip_archive" -d "$evidence_dir/zip"
tar -xzf "$tar_archive" -C "$evidence_dir/tar"
rollback_home "$evidence_dir/zip/Adempiere"
rollback_home "$evidence_dir/tar/Adempiere"

for home in "$evidence_dir/zip/Adempiere" "$evidence_dir/tar/Adempiere"; do
  if unzip -l "$home/lib/webui.war" 'WEB-INF/lib/webui-cohort-bridge.jar' \
      2>/dev/null | grep -Fq 'webui-cohort-bridge.jar'; then
    echo "$home still carries the Phase 5e bridge" >&2
    exit 1
  fi
  if [[ "$(logical_war_digest "$home/lib/webui.war")" != "$pristine_logical" ]]; then
    echo "$home was not restored to the pristine logical artifact" >&2
    exit 1
  fi
  for absent in "$home/$context_path" "$home/$manifest_path" \
      "$home/tomcat10-api/phase5e"; do
    if [[ -e "$absent" ]]; then
      echo "$home still carries $absent after rollback" >&2
      exit 1
    fi
  done
done

( cd "$evidence_dir/zip" && zip -qr "$evidence_dir/Adempiere_394LTS-phase5e-rollback.zip" Adempiere )
tar -czf "$evidence_dir/Adempiere_394LTS-phase5e-rollback.tar.gz" \
  -C "$evidence_dir/tar" Adempiere

# ---------------------------------------------------------------------------
# 4. The handoff key is removed, and Phase 4 is untouched.
# ---------------------------------------------------------------------------
if find "$evidence_dir" -name 'handoff.key' | grep -q .; then
  echo "Rollback evidence carries handoff key material" >&2
  exit 1
fi
for home in "$evidence_dir/zip/Adempiere" "$evidence_dir/tar/Adempiere"; do
  if [[ ! -f "$home/tomcat10-api/webapps/ADInterface.war" ]]; then
    echo "Rollback removed the Phase 4 SOAP application from $home" >&2
    exit 1
  fi
  grep -Fq 'address="127.0.0.1"' "$home/tomcat10-api/conf/server.xml"
done

{
  echo "phase5e-router=removed"
  echo "phase5e-bridge=removed"
  echo "phase5e-context-descriptor=removed"
  echo "phase5e-routed-modern-archive=removed"
  echo "phase5e-handoff-key=removed"
  echo "phase5c-webapps-overlay=restored"
  echo "tomcat9-deployed-webui=restored-pristine"
  echo "webui-original=removed-so-setupwlib-cannot-resurrect"
  echo "pristine-logical-digest=$pristine_logical"
  echo "setupwlib=real-ant-target-from-install/Adempiere/build.xml"
  echo "setupwlib-merge-inputs=zkcustomization.jar,zkpatches.jar,zkpackages/*/lib/*.jar"
  echo "setupwlib-observed-precedence=site-web.xml-wins-over-webuiOriginal.war"
  echo "setupwlib-observed-drops=${observed_drops:-none}"
  echo "setupwlib-allowed-drops=manifest.exclude-from-install/Adempiere/build.xml"
  echo "release-zip=pristine"
  echo "release-tar=pristine"
  echo "phase4-api=untouched"
  echo "legacy-oracle=owned-by-phase5eCohortRoutingSmoke"
} >"$evidence_dir/rollback-evidence.txt"

cat "$evidence_dir/rollback-evidence.txt"
