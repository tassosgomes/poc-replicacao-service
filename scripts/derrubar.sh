#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/comum.sh"
[[ -f $STATE ]] || { echo 'Inventário inicial ausente; remoção cancelada.' >&2; exit 1; }
for pair in desenv-server:legado infra-server:novo; do
  host=${pair%:*}; role=${pair#*:}
  ssh_poc "$host" "docker rm -f poc-$role >/dev/null 2>&1 || true; docker volume rm poc-$role >/dev/null 2>&1 || true; docker network rm poc-rede >/dev/null 2>&1 || true"
done
actual=$(mktemp)
trap 'rm -f "$actual"' EXIT
{ inventario desenv-server; inventario infra-server; } > "$actual"
if ! diff -u "$STATE" "$actual"; then echo 'Inventário mudou; confira os serviços.' >&2; exit 1; fi
rm -f "$STATE"
echo 'Inventário final igual ao inicial.'
