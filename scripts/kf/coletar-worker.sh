#!/usr/bin/env bash
# Copia os CSV de metricas de kf-worker-N para DIR e imprime o log do container em DIR/kf-worker-N.log.
# Uso: scripts/kf/coletar-worker.sh N DIR
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
N=${1:?N}; OUT=${2:?DIR}; mkdir -p "$OUT"
ssh_poc infra-server "docker logs kf-worker-$N 2>&1" > "$OUT/kf-worker-$N.log"
ssh_poc infra-server "docker cp kf-worker-$N:/resultados/. -" | tar -x -C "$OUT" 2>/dev/null || true
ls "$OUT"
