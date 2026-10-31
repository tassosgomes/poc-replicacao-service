#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# Repeatedly modify a row while sampling; wait for the workload before returning.
(
  fim=$((SECONDS + 60))
  while (( SECONDS < fim )); do
    printf "whenever sqlerror exit failure\nupdate titular set nome=nome where codigo=(select min(codigo) from titular);\ncommit;\nexit\n" | sql desenv-server poc-legado bdad >/dev/null
    sleep 1
  done
) &
carga=$!
status=0
bash "$ROOT/scripts/coletar-recursos.sh" "$tmp/recursos.csv" 60 || status=$?
wait "$carga" || status=$?
(( status == 0 )) || exit "$status"
awk -F, 'NR>1 {if (NF != 5 || $3 == "" || $4 == "" || $5 !~ /^[0-9]+$/) exit 1; seen[$2]++} END {if (!seen["poc-legado"] || !seen["poc-novo"]) exit 1}' "$tmp/recursos.csv"
printf 'recursos de ambos os bancos coletados durante 60 s\n'
