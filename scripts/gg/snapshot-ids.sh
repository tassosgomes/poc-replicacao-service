#!/usr/bin/env bash
# Snapshot (tabela,codigo,id) do gg-novo: CSV completo em resultados/gg/<cenario>/snapshots/<nome>.csv (ignorado pelo
# git) e resumo (linhas, bytes, sha256) em resultados/gg/<cenario>/meta.json, chave "snapshots.<nome>".
# Uso: scripts/gg/snapshot-ids.sh [cenario=c1] [nome=apos-carga]
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
cen=${1:-c1}; nome=${2:-apos-carga}
dir="$ROOT/resultados/gg/$cen"; mkdir -p "$dir/snapshots"; csv="$dir/snapshots/$nome.csv"
: > "$csv"
for t in titular obra_musical fonograma; do
  printf "whenever sqlerror exit failure\nset heading off feedback off pagesize 0 linesize 200 trimspool on verify off\nselect '%s,'||codigo||','||rawtohex(id) from %s order by codigo;\nexit\n" "$t" "$t" \
    | sql infra-server gg-novo replica | tr -d '\r' | grep -E "^$t,[0-9]+,[0-9A-F]{32}$" >> "$csv"
done
awk -F, 'NF!=3 || length($3)!=32 {exit 1}' "$csv" || { echo 'snapshot invalido' >&2; exit 1; }
sha=$(sha256sum "$csv" | cut -d' ' -f1)
dups=$(cut -d, -f3 "$csv" | sort | uniq -d | wc -l)
porTabela=$(cut -d, -f1 "$csv" | sort | uniq -c | awk '{printf "%s%s=%s", (NR>1?",":""), $2, $1}')
meta="$dir/meta.json"; [[ -s $meta ]] || echo '{}' > "$meta"
jq --arg n "$nome" --arg c "$cen" --arg sha "$sha" --argjson linhas "$(wc -l < "$csv")" --argjson bytes "$(wc -c < "$csv")" --argjson dups "$dups" --arg pt "$porTabela" --arg quando "$(date -u +%FT%TZ)" \
  '.snapshots[$n] = {linhas:$linhas, bytes:$bytes, sha256:$sha, uuids_duplicados:$dups, por_tabela:$pt, gerado_em:$quando, arquivo:("resultados/gg/"+$c+"/snapshots/"+$n+".csv (ignorado pelo git)")}' "$meta" > "$meta.tmp" && mv "$meta.tmp" "$meta"
echo "snapshot $nome: $(wc -l < "$csv") linhas, sha256=$sha, uuids duplicados=$dups ($porTabela)"
