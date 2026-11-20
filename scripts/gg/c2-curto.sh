#!/usr/bin/env bash
# C2 curto (300 s) = c2-regime.sh com DURACAO_SEG=300 (o relatorio.sh marca ESCOPO_REDUZIDO, como no lab Java). Uso: scripts/gg/c2-curto.sh [rotulo]
DURACAO_SEG=${DURACAO_SEG:-300} exec "$(dirname "$0")/cenario.sh" c2 "${1:-curto}"
