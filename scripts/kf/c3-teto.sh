#!/usr/bin/env bash
# C3 teto no lab Kafka (padroes do PRD). Ver scripts/kf/cenario.sh. Uso: scripts/kf/c3-teto.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c3 "$@"
