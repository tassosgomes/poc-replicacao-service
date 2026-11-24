#!/usr/bin/env bash
# C4 rajada no lab Kafka (padroes do PRD). Ver scripts/kf/cenario.sh. Uso: scripts/kf/c4-rajada.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c4 "$@"
