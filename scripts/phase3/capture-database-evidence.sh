#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 10 ]]; then
  echo "Usage: capture-database-evidence.sh <repo> <evidence-dir> <host> <port> <db> <user> <password> <system-user> <system-password> <release>" >&2
  exit 64
fi

repo_root=$1
evidence_dir=$2
db_host=$3
db_port=$4
db_name=$5
db_user=$6
db_password=$7
db_system_user=$8
db_system_password=$9
migration_release=${10}

bash "$repo_root/scripts/phase3/guard-disposable-runtime.sh" \
  "$repo_root" \
  "$repo_root/build/phase3/runtime" \
  "$repo_root/build/phase3/runtime/Adempiere" \
  "$repo_root/build/phase3/install" \
  "$db_host" \
  "$db_name" \
  "$db_user"

mkdir -p "$evidence_dir"

server_version=$(PGPASSWORD="$db_system_password" psql \
  --host="$db_host" --port="$db_port" --username="$db_system_user" \
  --dbname=postgres --tuples-only --no-align \
  --command='SHOW server_version')
if [[ "$server_version" != 14.6* ]]; then
  echo "Expected PostgreSQL 14.6, got $server_version" >&2
  exit 1
fi
printf '%s\n' "$server_version" >"$evidence_dir/postgresql-version.txt"

PGPASSWORD="$db_password" psql \
  --host="$db_host" --port="$db_port" --username="$db_user" \
  --dbname="$db_name" --tuples-only --no-align --field-separator=$'\t' \
  --command='SELECT Version, ReleaseNo FROM AD_System' \
  >"$evidence_dir/database-release.tsv"

if ! grep -Eq '^2023-01-24	3\.9\.4' "$evidence_dir/database-release.tsv"; then
  echo "Unexpected AD_System version/release:" >&2
  cat "$evidence_dir/database-release.tsv" >&2
  exit 1
fi

migration_dirs=()
while IFS= read -r migration_dir; do
  migration_dirs+=("$migration_dir")
done < <(
  find "$repo_root/migration" -mindepth 1 -maxdepth 1 -type d \
    -name "*${migration_release}*" -print | LC_ALL=C sort
)
if [[ ${#migration_dirs[@]} -eq 0 ]]; then
  echo "No migration directories match release $migration_release" >&2
  exit 1
fi

printf '%s\n' "${migration_dirs[@]#"$repo_root/"}" >"$evidence_dir/migration-directories.txt"
{
  printf '# path\tsize\tsha256\n'
  for migration_dir in "${migration_dirs[@]}"; do
    find "$migration_dir" -type f -name '*.xml' -print
  done | LC_ALL=C sort | while IFS= read -r migration_file; do
    relative_path=${migration_file#"$repo_root/"}
    file_size=$(wc -c <"$migration_file" | tr -d ' ')
    file_hash=$(shasum -a 256 "$migration_file" | awk '{print $1}')
    printf '%s\t%s\t%s\n' "$relative_path" "$file_size" "$file_hash"
  done
} >"$evidence_dir/migrations.tsv"

{
  printf '# role\tpath\tsize\tsha256\n'
  seed_input="$repo_root/data/seed/Adempiere_pg.jar"
  printf 'immutable-input\t%s\t%s\t%s\n' \
    "${seed_input#"$repo_root/"}" \
    "$(wc -c <"$seed_input" | tr -d ' ')" \
    "$(shasum -a 256 "$seed_input" | awk '{print $1}')"

  seed_output="$repo_root/build/phase3/runtime/Adempiere/data/Adempiere_pg.dmp"
  if [[ -f "$seed_output" ]]; then
    printf 'generated-output\t%s\t%s\t%s\n' \
      "${seed_output#"$repo_root/"}" \
      "$(wc -c <"$seed_output" | tr -d ' ')" \
      "$(shasum -a 256 "$seed_output" | awk '{print $1}')"
  fi
} >"$evidence_dir/seeds.tsv"

printf 'migration.release=%s\n' "$migration_release" >"$evidence_dir/migration-scope.properties"
