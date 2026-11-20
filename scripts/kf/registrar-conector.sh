#!/usr/bin/env bash
# Registra (ou atualiza) o conector Debezium Oracle "kf-oracle" no kf-connect. Config em scripts/kf/conector-oracle.json
# (o JSON de saida usa KF_DECIMAL e KF_TIME para os modos de mapeamento de tipos; padroes abaixo).
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
DEC=${KF_DECIMAL:-string}; TIME=${KF_TIME:-adaptive}
cfg=$(sed -e "s/__DECIMAL__/$DEC/" -e "s/__TIME__/$TIME/" "$(dirname "$0")/conector-oracle.json" | jq -c .)
printf '%s' "$cfg" | ssh_poc infra-server "curl -s -m 60 -X PUT -H 'Content-Type: application/json' --data-binary @- localhost:18083/connectors/kf-oracle/config" | jq -c '{name, type, tasks}' 
for ((i=0;i<60;i++)); do
  s=$(ssh_poc infra-server "curl -s -m 10 localhost:18083/connectors/kf-oracle/status" | jq -r '.tasks[0].state // "?"')
  [[ $s == RUNNING ]] && { echo 'conector RUNNING'; exit 0; }
  [[ $s == FAILED ]] && { ssh_poc infra-server "curl -s localhost:18083/connectors/kf-oracle/status" | jq -r '.tasks[0].trace' | head -20 >&2; exit 1; }
  sleep 3
done
echo 'conector nao ficou RUNNING' >&2; exit 1
