#!/usr/bin/env bash
# C7 no lab GoldenGate. Ver scripts/gg/falhas.sh. Uso: scripts/gg/c7-failover.sh [rotulo]
exec "$(dirname "$0")/falhas.sh" c7 "$@"
