#!/usr/bin/env bash
# Lag total dos workers nos topicos de tabela: soma(end offset) - soma(offset commitado do grupo); particao sem commit conta
# desde 0 (topicos sem retencao por tamanho/tempo no spike). Uso: scripts/kf/lag.sh [GRUPO]    Saida: um inteiro.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
G=${1:-kf-worker}
ssh_poc infra-server "docker exec kf-kafka sh -c '
for t in kf.BDAD.TITULAR kf.BDAD.OBRA_MUSICAL kf.BDAD.FONOGRAMA; do /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic \$t 2>/dev/null | sed \"s/^/END /\"; done
/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group $G 2>/dev/null | awk \"\\\$2 ~ /^kf\\\\.BDAD\\\\./ && \\\$4 ~ /^[0-9]+\\\$/ {print \\\"CUR \\\" \\\$4}\"'" |
  awk '$1=="END"{split($2,a,":"); e+=a[3]} $1=="CUR"{c+=$2} END{print e-c}'
