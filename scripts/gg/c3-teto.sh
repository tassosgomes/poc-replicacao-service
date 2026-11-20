#!/usr/bin/env bash
# C3 teto no lab GoldenGate (escada 500,1000,2000,4000 ev/s, degraus de 300 s). Ver scripts/gg/cenario.sh. Uso: scripts/gg/c3-teto.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c3 "$@"
