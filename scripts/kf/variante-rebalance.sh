#!/usr/bin/env bash
# VARIANTE DESCRITIVA do extra de queda do broker (C4/C6/C7): recria o kf-connect com CONNECT_SCHEDULED_REBALANCE_MAX_DELAY_MS=0 (padrao do Connect: 300.000 ms = os 5 min de captura parada
# medidos em resultados/kf/c4/extras/*-broker) e repete scripts/kf/extra-queda.sh broker. Ao fim (ou em erro) restaura o kf-connect oficial (sem a variavel). Grava em resultados/kf/c9/rebalance/.
# Uso: scripts/kf/variante-rebalance.sh [rotulo]
set -Eeuo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-kf.sh
trap 'echo "restaurando kf-connect oficial" >&2; scripts/kf/recriar-connect.sh >&2 || true' EXIT
scripts/kf/recriar-connect.sh CONNECT_SCHEDULED_REBALANCE_MAX_DELAY_MS=0
ssh_poc infra-server "docker logs kf-connect 2>&1 | grep -m1 'scheduled.rebalance.max.delay.ms'" | tee /tmp/kf-rebalance-conf.txt || true
mkdir -p resultados/kf/c9/rebalance
cp /tmp/kf-rebalance-conf.txt resultados/kf/c9/rebalance/config-do-worker-connect.txt 2>/dev/null || true
KF_EXTRA_BASE=resultados/kf/c9/rebalance scripts/kf/extra-queda.sh broker "${1:-rebalance0}" || true
