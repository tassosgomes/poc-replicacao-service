#!/usr/bin/env bash
# Espera o pipeline drenar: Extract e Replicat RUNNING e o aplicacao_log do gg-novo sem crescer por ESTAVEL_SEG (padrao 15 s),
# com o lag do Replicat <= 2 s. Imprime o tempo (relogio monotonico do laptop) ate drenar. Uso: scripts/gg/drenar.sh [timeout_seg=900]
set -euo pipefail
cd "$(dirname "$0")/../../gg"; source ./lib.sh
TO=${1:-900}; EST=${ESTAVEL_SEG:-15}; ini=$(date +%s); ant=-1; desde=$(date +%s)
while :; do
  n=$(printf "set heading off feedback off pagesize 0\nselect count(*) from aplicacao_log;\nexit\n" | ../scripts/gg/q.sh novo | tr -d ' \r\n')
  st=$(gg GET /replicats/REPBDAD/info/status | jq -r '.response|"\(.status) \(.lag)"')
  se=$(gg GET /extracts/EXTBDAD/info/status | jq -r '.response.status')
  agora=$(date +%s)
  if [[ $n != "$ant" ]]; then ant=$n; desde=$agora; fi
  lag=${st#* }
  if [[ $se == running && ${st%% *} == running && $((agora-desde)) -ge $EST ]]; then
    echo "drenado em $((agora-ini)) s (aplicacao_log=$n, lag reportado do replicat=$lag s)"; exit 0
  fi
  (( agora-ini > TO )) && { echo "timeout drenando (extract=$se replicat=$st log=$n)" >&2; exit 1; }
  sleep 3
done
