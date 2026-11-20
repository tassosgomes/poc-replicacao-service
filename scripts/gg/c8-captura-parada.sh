#!/usr/bin/env bash
# C8 no lab GoldenGate. Ver scripts/gg/falhas.sh. Uso: scripts/gg/c8-captura-parada.sh [rotulo]
exec "$(dirname "$0")/falhas.sh" c8 "$@"
