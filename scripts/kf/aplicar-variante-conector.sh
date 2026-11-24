#!/usr/bin/env bash
# Aplica uma VARIANTE da configuracao do conector kf-oracle: scripts/kf/conector-oracle.json + overrides (objeto JSON), via PUT /connectors/kf-oracle/config. Espera o
# conector reiniciar e entrar em streaming (como registrar-conector.sh). O offset e preservado (snapshot.mode=no_data; o conector retoma do offset). Sem argumento,
# restaura a configuracao OFICIAL (equivale a registrar-conector.sh). Uso: scripts/kf/aplicar-variante-conector.sh ['{"poll.interval.ms":"100"}']
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
OV=${1:-'{}'}
cfg=$(sed -e "s/__DECIMAL__/string/" -e "s/__TIME__/adaptive/" "$(dirname "$0")/conector-oracle.json" | jq -c --argjson ov "$OV" '. + $ov')
n0=$(ssh_poc infra-server "docker logs kf-connect 2>&1 | grep -c 'Starting streaming'" || true)
printf '%s' "$cfg" | ssh_poc infra-server "curl -s -m 60 -X PUT -H 'Content-Type: application/json' --data-binary @- localhost:18083/connectors/kf-oracle/config" | jq -c '{name, type, tasks}'
for ((i=0;i<150;i++)); do
  s=$(connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.tasks[0].state // "?"')
  [[ $s == FAILED ]] && { connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.tasks[0].trace' | head -12 >&2; echo "tarefa FAILED com $OV" >&2; exit 1; }
  n=$(ssh_poc infra-server "docker logs kf-connect 2>&1 | grep -c 'Starting streaming'" || true)
  if [[ $s == RUNNING && $n -gt $n0 ]]; then echo "variante aplicada ($OV); conector em streaming"; exit 0; fi
  sleep 2
done
echo "conector nao entrou em streaming" >&2; exit 1
