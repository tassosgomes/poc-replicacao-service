#!/usr/bin/env bash
# C6 queda do worker no lab Kafka. Ver scripts/kf/falhas.sh. Uso: scripts/kf/c6-queda-worker.sh [rotulo]
exec "$(dirname "$0")/falhas.sh" c6 "$@"
