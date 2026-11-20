#!/usr/bin/env bash
# C6 no lab GoldenGate. Ver scripts/gg/falhas.sh. Uso: scripts/gg/c6-queda.sh [rotulo]
exec "$(dirname "$0")/falhas.sh" c6 "$@"
