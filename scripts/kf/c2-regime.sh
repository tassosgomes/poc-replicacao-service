#!/usr/bin/env bash
# C2 regime no lab Kafka (padroes do PRD). Ver scripts/kf/cenario.sh. Uso: scripts/kf/c2-regime.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c2 "$@"
