#!/usr/bin/env bash
# Reconciliacao completa por SQL entre kf-legado (bdad) e kf-novo (replica): por tabela e faixa de 100.000 codigos,
# contagem + dois hashes ORA_HASH (sementes 0 e 1, somados) das colunas de negocio + excluido + versao
# (updated_at x origem_updated_at), as mesmas colunas do subcomando Java `reconciliacao`.
# Uso: scripts/kf/reconciliar.sh   (saida 0 = sem divergencia; 1 = divergencia; imprime as faixas divergentes)
# Adaptado de scripts/gg/reconciliar.sh para o lab Kafka. O subcomando Java `reconciliacao` (scripts/kf/poc.sh) e a verificacao dependente do jar.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
col() { # tabela colunas versao_expr
  local -a campos=("TO_CHAR(codigo,'TM9','NLS_NUMERIC_CHARACTERS=''.,''')")
  local c partes=
  for c in $2; do campos+=("CAST($c AS VARCHAR2(4000))"); done
  campos+=("TO_CHAR($3,'YYYYMMDDHH24MISSFF6')")
  for c in "${campos[@]}"; do partes+="${partes:+ || }NVL(TO_CHAR(LENGTH($c)),'-1') || ':' || $c || ';'"; done
  echo "$partes"
}
consulta() { # tabela colunas versao
  local h; h=$(col "$@")
  printf "set heading off feedback off pagesize 0 linesize 300 trimspool on\nselect '%s|'||FLOOR(codigo/100000)||'|'||COUNT(*)||'|'||SUM(ORA_HASH(%s))||'|'||SUM(ORA_HASH(%s,4294967295,1)) from %s group by FLOOR(codigo/100000) order by 1;\nexit\n" "$1" "$h" "$h" "$1"
}
declare -A COLS=( [titular]='nome tipo documento situacao excluido' [obra_musical]='titulo iswc genero situacao excluido' [fonograma]='obra_codigo titulo isrc duracao_seg excluido' )
for t in titular obra_musical fonograma; do
  consulta $t "${COLS[$t]}" updated_at | kfq legado | tr -d '\r' | grep -E "^$t\|" >> "$tmp/legado" &
  consulta $t "${COLS[$t]}" origem_updated_at | kfq novo | tr -d '\r' | grep -E "^$t\|" >> "$tmp/novo" &
done
wait
sort -o "$tmp/legado" "$tmp/legado"; sort -o "$tmp/novo" "$tmp/novo"
echo "faixas comparadas: legado=$(wc -l < "$tmp/legado") novo=$(wc -l < "$tmp/novo")"
awk -F'|' '{n[$1]+=$3} END{for(t in n) print "linhas " t "=" n[t]}' "$tmp/legado" | sort
if diff "$tmp/legado" "$tmp/novo" > "$tmp/diff"; then
  echo "reconciliacao SQL: 0 divergencias (contagem e hash iguais em todas as faixas)"
else
  echo "reconciliacao SQL: DIVERGENCIAS (< legado, > novo: tabela|faixa|contagem|hash0|hash1)"; cat "$tmp/diff"; exit 1
fi
