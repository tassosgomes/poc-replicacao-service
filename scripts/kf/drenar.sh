#!/usr/bin/env bash
# Espera o pipeline drenar: lag do grupo de workers = 0 e soma dos end offsets dos topicos de tabela sem crescer por ESTAVEL_SEG
# (padrao 15 s; o LogMiner leva ate ~5 s para publicar um commit), com o conector RUNNING. Imprime o tempo (relogio do desenv-server).
# Uso: scripts/kf/drenar.sh [timeout_seg=900]     Exit 1 em timeout.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
TO=${1:-900}; EST=${ESTAVEL_SEG:-15}; ini=$(agora); ant=-1; desde=$ini
while :; do
  read -r lag fim < <("$(dirname "$0")/lag.sh" "${KF_GRUPO:-kf-worker}" --fim)
  st=$(connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.tasks[0].state // "?"')
  now=$(agora)
  if [[ $fim != "$ant" ]]; then ant=$fim; desde=$now; fi
  if [[ $st == RUNNING && $lag -eq 0 ]] && awk -v a="$desde" -v b="$now" -v e="$EST" 'BEGIN{exit !(b-a>=e)}'; then
    echo "drenado em $(dur "$ini" "$now") s (lag=$lag, end offsets=$fim, conector=$st)"; exit 0
  fi
  awk -v a="$ini" -v b="$now" -v t="$TO" 'BEGIN{exit !(b-a>t)}' && { echo "timeout drenando (lag=$lag fim=$fim conector=$st)" >&2; exit 1; }
  sleep 3
done
