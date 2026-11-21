#!/usr/bin/env bash
# Reconciliacao completa por SQL entre kf-legado (bdad) e kf-novo (replica): por tabela e faixa de 100.000 codigos,
# contagem + dois hashes ORA_HASH (sementes 0 e 1, somados) das colunas de negocio + excluido + versao
# (updated_at x origem_updated_at), as mesmas colunas do subcomando Java `reconciliacao`.
# Uso: scripts/kf/reconciliar.sh   (saida 0 = sem divergencia; 1 = divergencia; imprime as faixas divergentes)
# DELETE fisico no legado: o worker o converte em excluido=1 no destino e mantem a linha (nunca se apaga linha do destino). Linha
# AUSENTE no legado e com excluido=1 no destino e CONSISTENTE e contada a parte ("deletes fisicos convertidos"). Para isso, nas faixas
# cujo agregado difere, o script desce ao nivel de linha (codigo|excluido|hash) e classifica: so-no-destino com excluido=1 = benigna;
# qualquer outra diferenca (ausente no destino, so-no-destino com excluido<>1, hash diferente) = divergencia real (exit 1).
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
  echo "reconciliacao SQL: 0 divergencias (contagem e hash iguais em todas as faixas); deletes fisicos convertidos: 0"; exit 0
fi
# Faixas que diferem (tabela|faixa): desce ao nivel de linha.
sed -E 's/^[<>] //' "$tmp/diff" | cut -d'|' -f1,2 | grep -E '^[a-z_]+\|[0-9]+$' | sort -u > "$tmp/faixas"
: > "$tmp/l-legado"; : > "$tmp/l-novo"
linhas() { # tabela colunas versao faixa
  local h; h=$(col "$1" "$2" "$3" 2>/dev/null || true)
  printf "set heading off feedback off pagesize 0 linesize 400 trimspool on\nselect 'L|%s|'||codigo||'|'||NVL(excluido,0)||'|'||ORA_HASH(%s) from %s where FLOOR(codigo/100000)=%s order by codigo;\nexit\n" "$1" "$h" "$1" "$4"
}
while IFS='|' read -r t f; do
  linhas "$t" "${COLS[$t]}" updated_at "$f" | kfq legado | tr -d '\r' | grep -E '^L\|' >> "$tmp/l-legado" &
  linhas "$t" "${COLS[$t]}" origem_updated_at "$f" | kfq novo | tr -d '\r' | grep -E '^L\|' >> "$tmp/l-novo" &
  wait
done < "$tmp/faixas"
# chave = tabela|codigo; valor = excluido|hash
awk -F'|' 'NR==FNR {l[$2"|"$3]=$4"|"$5; next}
  {k=$2"|"$3; v=$4"|"$5; n[k]=v}
  END {
    for (k in n) {
      split(k,a,"|")
      if (!(k in l)) { if (substr(n[k],1,2)=="1|") conv[a[1]]++; else {print "so-no-destino-sem-excluido|" k; bad++} }
      else if (l[k]!=n[k]) { print "hash-diferente|" k; bad++ }
    }
    for (k in l) if (!(k in n)) { print "ausente-no-destino|" k; bad++ }
    t=0; for (x in conv) { t+=conv[x]; d=d (d?",":"") x "=" conv[x] }
    print "CONV|" t "|" d; print "BAD|" bad+0
  }' "$tmp/l-legado" "$tmp/l-novo" > "$tmp/classe"
CONV=$(sed -n 's/^CONV|//p' "$tmp/classe"); BAD=$(sed -n 's/^BAD|//p' "$tmp/classe")
if [[ $BAD == 0 ]]; then
  echo "reconciliacao SQL: 0 divergencias; deletes fisicos convertidos (ausentes no legado, excluido=1 no destino): ${CONV%%|*} (${CONV#*|})"
  exit 0
fi
echo "reconciliacao SQL: $BAD DIVERGENCIAS REAIS (deletes fisicos convertidos, nao divergentes: ${CONV%%|*})"
grep -vE '^(CONV|BAD)\|' "$tmp/classe" | head -50
exit 1
