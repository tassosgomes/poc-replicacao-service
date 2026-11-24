#!/usr/bin/env bash
# Chaves de OBRA_MUSICAL que diferem entre o kf-legado e o kf-novo (mesmas colunas e versao da reconciliacao: titulo, iswc, genero, situacao,
# excluido e updated_at x origem_updated_at) mais linhas ausentes no destino. Uma linha `DIV,<codigo>` por chave (equivale a sql/gg/novo/04_divergentes_obra.sql
# do lab GG, sem DB link: compara hash por chave dos dois lados). Somente leitura. Uso: scripts/kf/divergentes-obra.sh
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
export LC_ALL=C
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
q() { # versao
  printf "set heading off feedback off pagesize 0 linesize 100 trimspool on\nselect codigo||'|'||ORA_HASH(NVL(titulo,'~')||'|'||NVL(iswc,'~')||'|'||NVL(genero,'~')||'|'||NVL(situacao,'~')||'|'||NVL(TO_CHAR(excluido),'~')||'|'||TO_CHAR(%s,'YYYYMMDDHH24MISSFF6')) from obra_musical;\nexit\n" "$1"
}
q updated_at | kfq legado | tr -d '\r' | grep -E '^[0-9]+\|[0-9]+$' | sort > "$tmp/l" &
q origem_updated_at | kfq novo | tr -d '\r' | grep -E '^[0-9]+\|[0-9]+$' | sort > "$tmp/n" &
wait
join -t'|' -a1 -e MISSING -o 0,1.2,2.2 "$tmp/l" "$tmp/n" | awk -F'|' '$3=="MISSING" || $2!=$3 {print "DIV," $1}' | sort -t, -k2 -n
