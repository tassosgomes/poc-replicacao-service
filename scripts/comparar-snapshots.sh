#!/usr/bin/env bash
# Saída CSV na saída padrão; redirecione para o arquivo de diferenças desejado.
set -euo pipefail
[[ $# == 2 ]] || { echo "Uso: $0 <antes> <depois>" >&2; exit 2; }
antes=$1 depois=$2
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf 'tabela,codigo,id_antes,id_depois\n'
diferencas=0
for tabela in titular obra_musical fonograma; do
  [[ -f $antes/$tabela.csv && -f $depois/$tabela.csv ]] || { echo "Snapshot incompleto: $tabela" >&2; exit 2; }
  LC_ALL=C sort -t, -k1,1 "$antes/$tabela.csv" > "$tmp/antes"
  LC_ALL=C sort -t, -k1,1 "$depois/$tabela.csv" > "$tmp/depois"
  LC_ALL=C join -t, -1 1 -2 1 "$tmp/antes" "$tmp/depois" |
    awk -F, -v tabela="$tabela" '$2 != $3 {print tabela "," $1 "," $2 "," $3}' > "$tmp/diferencas"
  if [[ -s $tmp/diferencas ]]; then cat "$tmp/diferencas"; diferencas=1; fi
done
exit "$diferencas"
