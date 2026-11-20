#!/usr/bin/env bash
# C1 carga inicial no lab GoldenGate (padroes do PRD). Ver scripts/gg/cenario.sh. Uso: scripts/gg/c1-carga-inicial.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c1 "$@"
