#!/usr/bin/env bash
# C4 rajada no lab GoldenGate (padroes do PRD). Ver scripts/gg/cenario.sh. Uso: scripts/gg/c4-rajada.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c4 "$@"
