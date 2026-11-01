#!/usr/bin/env bash
# Executar no infra-server; em outro host, a consulta usa SSH para poc-novo.
set -euo pipefail
[[ $# == 1 ]] || { echo "Uso: $0 <diretorio-destino>" >&2; exit 2; }
dest=$1
[[ ! -e $dest ]] || { echo "Destino já existe: $dest" >&2; exit 2; }
mkdir -p "$(dirname "$dest")"
tmp=$(mktemp -d "${dest}.tmp.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT
inicio=$(date +%s)
for tabela in titular obra_musical fonograma; do
  query=$(printf "whenever sqlerror exit failure\nwhenever oserror exit failure\nset heading off feedback off pagesize 0 linesize 200 trimspool on verify off echo off\nselect to_char(codigo, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''') || ',' || rawtohex(id) from %s order by codigo;\nexit\n" "$tabela")
  if [[ ${POC_SNAPSHOT_LOCAL:-0} == 1 ]]; then
    printf '%s\n' "$query" | docker exec -i poc-novo sqlplus -s replica/replica@localhost:1521/FREEPDB1 > "$tmp/$tabela.csv"
  else
    printf '%s\n' "$query" | ssh -o BatchMode=yes -o ConnectTimeout=10 infra-server 'docker exec -i poc-novo sqlplus -s replica/replica@localhost:1521/FREEPDB1' > "$tmp/$tabela.csv"
  fi
  # SQL*Plus pode sinalizar erros de comando sem retornar erro do SQL.
  if LC_ALL=C grep -Eq '^(SP2-|ORA-|ERROR)' "$tmp/$tabela.csv"; then
    echo "Falha SQL*Plus ao exportar $tabela" >&2; exit 1
  fi
  if ! LC_ALL=C awk -F, 'NF != 2 || $1 !~ /^-?[0-9]+$/ || $2 !~ /^[0-9A-F]+$/ || length($2) != 32 { exit 1 }' "$tmp/$tabela.csv"; then
    echo "Snapshot inválido para $tabela" >&2; exit 1
  fi
done
linhas=$(cat "$tmp"/*.csv | wc -l)
bytes=$(wc -c < <(cat "$tmp"/*.csv))
printf 'linhas=%s\nbytes=%s\nduracao_seg=%s\n' "$linhas" "$bytes" "$(( $(date +%s) - inicio ))" > "$tmp/medicao.txt"
mv "$tmp" "$dest"
