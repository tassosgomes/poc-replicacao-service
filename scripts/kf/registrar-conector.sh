#!/usr/bin/env bash
# Registra (ou atualiza) o conector Debezium Oracle "kf-oracle" no kf-connect. Config em scripts/kf/conector-oracle.json
# (KF_DECIMAL e KF_TIME escolhem o mapeamento de tipos; padroes abaixo).
# ARMADILHA tratada: a API responde RUNNING antes de o conector fixar o SCN inicial; DML nesse intervalo se perderia. Por isso o
# script so termina depois de "Starting streaming" no log do kf-connect (posterior ao PUT) e imprime o SCN fixado pelo snapshot
# (SCN_INICIAL=<n>, linha "Snapshot ended ... scn=" do log). Tudo que for commitado depois desse ponto entra no topico.
# Limite do snapshot.mode=no_data: "No in-progress transactions will be captured" (transacao aberta no instante do start nao e
# capturada); registre com o legado sem DML em andamento (o subir.sh registra antes da carga, sem gerador).
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
DEC=${KF_DECIMAL:-string}; TIME=${KF_TIME:-adaptive}
cfg=$(sed -e "s/__DECIMAL__/$DEC/" -e "s/__TIME__/$TIME/" "$(dirname "$0")/conector-oracle.json" | jq -c .)
n0=$(ssh_poc infra-server "docker logs kf-connect 2>&1 | grep -c 'Starting streaming'" || true)
printf '%s' "$cfg" | ssh_poc infra-server "curl -s -m 60 -X PUT -H 'Content-Type: application/json' --data-binary @- localhost:18083/connectors/kf-oracle/config" | jq -c '{name, type, tasks}'
for ((i=0;i<120;i++)); do
  s=$(ssh_poc infra-server "curl -s -m 10 localhost:18083/connectors/kf-oracle/status" | jq -r '.tasks[0].state // "?"')
  [[ $s == FAILED ]] && { ssh_poc infra-server "curl -s localhost:18083/connectors/kf-oracle/status" | jq -r '.tasks[0].trace' | head -20 >&2; exit 1; }
  n=$(ssh_poc infra-server "docker logs kf-connect 2>&1 | grep -c 'Starting streaming'" || true)
  if [[ $s == RUNNING && $n -gt $n0 ]]; then
    scn=$(ssh_poc infra-server "docker logs kf-connect 2>&1 | grep 'Snapshot ended' | tail -1" | sed -n 's/.*OracleOffsetContext \[scn=\([0-9]*\).*/\1/p')
    echo "conector RUNNING e em streaming; SCN_INICIAL=$scn"; exit 0
  fi
  sleep 2
done
echo 'conector nao entrou em streaming' >&2; exit 1
