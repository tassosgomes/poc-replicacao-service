#!/usr/bin/env bash
# C7 dois workers e failover no lab Kafka. Ver scripts/kf/falhas.sh. Uso: scripts/kf/c7-failover.sh [rotulo]
exec "$(dirname "$0")/falhas.sh" c7 "$@"
