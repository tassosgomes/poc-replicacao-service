#!/usr/bin/env bash
# Confere que os archive logs ainda necessarios ao Extract (NEXT_CHANGE# > required_checkpoint_scn de dba_capture, QUALQUER status, inclusive
# Extract parado) existem no gg-legado. Imprime uma linha JSON {required_checkpoint_scn, status, necessarios, existem, faltando, lista_faltando}.
# Exit 0 = nada necessario faltando; exit 1 = falta algum. Uso: scripts/gg/verificar-archive-necessario.sh
set -euo pipefail
HOST=desenv-server; C=gg-legado
sysdba() { ssh -o BatchMode=yes -o ConnectTimeout=10 $HOST "docker exec -i $C sqlplus -s / as sysdba"; }
R=$(printf "set heading off feedback off pagesize 0 linesize 300 trimspool on\nalter session set container=FREEPDB1;\nselect 'REQ='||required_checkpoint_scn||' ST='||status from dba_capture;\nexit\n" | sysdba | tr -d '\r' | grep '^REQ=' | head -1)
REQ=${R#REQ=}; REQ=${REQ%% *}; ST=${R##*ST=}
[[ $REQ =~ ^[0-9]+$ ]] || { echo "falha ao consultar dba_capture: $R" >&2; exit 2; }
LISTA=$(printf "set heading off feedback off pagesize 0 linesize 300 trimspool on\nselect name from v\$archived_log where name is not null and next_change# > %s order by sequence#;\nexit\n" "$REQ" | sysdba | tr -d '\r' | grep '^/' | sort -u || true)
N=0; OK=0; FALTA=()
for f in $LISTA; do
  N=$((N+1))
  if ssh -o BatchMode=yes $HOST "docker exec $C test -e '$f'"; then OK=$((OK+1)); else FALTA+=("$f"); fi
done
jq -nc --argjson req "$REQ" --arg st "$ST" --argjson n "$N" --argjson ok "$OK" --argjson f "$(printf '%s\n' "${FALTA[@]:-}" | grep . | jq -R . | jq -s .)" \
  '{required_checkpoint_scn:$req, status:$st, necessarios:$n, existem:$ok, faltando:($n-$ok), lista_faltando:$f}'
(( ${#FALTA[@]} == 0 ))
