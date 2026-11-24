#!/usr/bin/env bash
# Recria SO o container kf-connect (mesma imagem, limites e variaveis do subir-kafka.sh) com variaveis extras de worker do Connect (prefixo CONNECT_, ex.:
# CONNECT_SCHEDULED_REBALANCE_MAX_DELAY_MS=0). Config, offsets e status do conector ficam nos topicos kf-connect-* do Kafka, entao o conector volta sozinho.
# Sem argumentos restaura a configuracao oficial. Uso: scripts/kf/recriar-connect.sh [VAR=VALOR ...]
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
EXTRA=(); for v in "$@"; do EXTRA+=(-e "$v"); done
ssh_poc infra-server "docker rm -f kf-connect >/dev/null 2>&1 || true"
ssh_poc infra-server "docker run -d --name kf-connect --network kf-rede --cpus=2 --memory=2g -p 18083:8083 \
  -e BOOTSTRAP_SERVERS=kf-kafka:9092 -e GROUP_ID=kf-connect \
  -e CONFIG_STORAGE_TOPIC=kf-connect-configs -e OFFSET_STORAGE_TOPIC=kf-connect-offsets -e STATUS_STORAGE_TOPIC=kf-connect-status \
  -e CONFIG_STORAGE_REPLICATION_FACTOR=1 -e OFFSET_STORAGE_REPLICATION_FACTOR=1 -e STATUS_STORAGE_REPLICATION_FACTOR=1 \
  -e KEY_CONVERTER=org.apache.kafka.connect.json.JsonConverter -e VALUE_CONVERTER=org.apache.kafka.connect.json.JsonConverter \
  -e CONNECT_KEY_CONVERTER_SCHEMAS_ENABLE=false -e CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE=false \
  -e KAFKA_HEAP_OPTS='-Xms512m -Xmx1g' ${EXTRA[*]:-} \
  --health-cmd 'curl -sf localhost:8083/connectors >/dev/null' --health-interval 10s --health-timeout 5s --health-retries 30 \
  $CONNECT_IMG >/dev/null"
for ((i=0;i<90;i++)); do
  [[ $(ssh_poc infra-server "docker inspect --format '{{.State.Health.Status}}' kf-connect") == healthy ]] && break; sleep 3
done
for ((i=0;i<120;i++)); do
  [[ $(connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.tasks[0].state // "?"' 2>/dev/null) == RUNNING ]] && { echo "kf-connect recriado (extras: ${EXTRA[*]:-nenhum}); conector RUNNING"; exit 0; }; sleep 3
done
echo "conector nao voltou a RUNNING" >&2; exit 1
