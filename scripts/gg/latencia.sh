#!/usr/bin/env bash
# Mede latencia de ~200 updates (titular DE..ATE) e imprime min/p50/p95/max. Uso: scripts/gg/latencia.sh 400 599
set -euo pipefail
D="$(dirname "$0")/../../sql/gg/testes"; de=${1:-100}; ate=${2:-299}
sed "s/&1/$de/g; s/&2/$ate/g" "$D/latencia_origem.sql" | "$(dirname "$0")/q.sh" legado >/dev/null
sleep 15
sed "s/&1/$de/g; s/&2/$ate/g" "$D/latencia_destino.sql" | "$(dirname "$0")/q.sh" novo
