#!/usr/bin/env bash
# Recria os workers do lab Kafka do zero para um cenario: para (docker stop, graceful) e remove TODOS os kf-worker-* e sobe N novos
# (scripts/kf/subir-worker.sh, 2 CPU / 1 GiB / -Xmx512m, mesmos limites do worker Java) com EVENTOS_CSV=true (um CSV por evento: criado_em =
# updated_at da origem, confirmado_em = commit no destino). Container novo porque o arquivo de eventos e CREATE_NEW (nao reaproveita o de
# um container anterior); offsets do consumer group e leases ficam no Kafka / kf-novo, entao nada e reprocessado. Espera as 48 particoes ativas.
# Uso: scripts/kf/worker-fresco.sh [N=1] [VAR=VALOR ...]     So toca em recursos kf-.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
N=${1:-1}; shift || true
ssh_poc infra-server 'ids=$(docker ps -aq --filter name=^/kf-worker); [ -z "$ids" ] || { docker stop $ids >/dev/null; docker rm $ids >/dev/null; }'
for ((w=1; w<=N; w++)); do "$(dirname "$0")/subir-worker.sh" "$w" EVENTOS_CSV=true "$@" > /dev/null; done
ini=$(agora)
for ((i=0;i<180;i++)); do
  ok=0
  for ((w=1; w<=N; w++)); do ssh_poc infra-server "docker logs kf-worker-$w 2>&1 | grep -q 'owned partitions active'" && ok=$((ok+1)) || true; done
  [[ $ok -eq $N ]] && break; sleep 2
done
[[ $ok -eq $N ]] || { echo "workers nao ficaram ativos" >&2; exit 2; }
echo "$N worker(s) ativos em $(dur "$ini" "$(agora)") s"
