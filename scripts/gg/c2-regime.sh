#!/usr/bin/env bash
# C2 regime no lab GoldenGate (1.000 ev/s por 600 s, padroes do PRD). Ver scripts/gg/cenario.sh. Uso: scripts/gg/c2-regime.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c2 "$@"
