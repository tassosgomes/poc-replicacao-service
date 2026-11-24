#!/usr/bin/env bash
# Confere que os archive logs ainda necessarios ao conector (NEXT_CHANGE# > MIN(scn, commit_scn) do offset do Connect, mesmo com o
# conector parado) existem no kf-legado. Uma linha JSON {scn_necessario, necessarios, existem, faltando, lista_faltando}.
# Exit 0 = nada necessario faltando; 1 = falta algum; 2 = sem offset. Uso: scripts/kf/verificar-archive-necessario.sh
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
HOST=desenv-server; C=kf-legado
sysdba() { ssh_poc $HOST "docker exec -i $C sqlplus -s / as sysdba"; }
OFF=$(offset_conector || true); [[ -n $OFF ]] || { echo "sem offset do conector" >&2; exit 2; }
REQ=$(jq -r '[ (.scn // empty | tonumber), (.commit_scn // empty | split(":")[0] | tonumber) ] | min // 0' <<< "$OFF")
LISTA=$(printf "set heading off feedback off pagesize 0 linesize 300 trimspool on\nselect name from v\$archived_log where name is not null and next_change# > %s order by sequence#;\nexit\n" "$REQ" | sysdba | tr -d '\r' | grep '^/' | sort -u || true)
N=0; OK=0; FALTA=()
for f in $LISTA; do
  N=$((N+1))
  if ssh_poc $HOST "docker exec $C test -e '$f'"; then OK=$((OK+1)); else FALTA+=("$f"); fi
done
jq -nc --argjson req "$REQ" --argjson n "$N" --argjson ok "$OK" --argjson f "$(printf '%s\n' "${FALTA[@]:-}" | grep . | jq -R . | jq -s .)" \
  '{scn_necessario:$req, necessarios:$n, existem:$ok, faltando:($n-$ok), lista_faltando:$f}'
(( ${#FALTA[@]} == 0 ))
