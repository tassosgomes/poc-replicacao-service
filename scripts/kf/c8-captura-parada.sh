#!/usr/bin/env bash
# C8 conector parado no lab Kafka. Ver scripts/kf/falhas.sh. Uso: scripts/kf/c8-captura-parada.sh [rotulo]
exec "$(dirname "$0")/falhas.sh" c8 "$@"
