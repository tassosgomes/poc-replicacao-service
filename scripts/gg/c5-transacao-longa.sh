#!/usr/bin/env bash
# C5 transacao longa no lab GoldenGate (padroes do PRD). Ver scripts/gg/cenario.sh. Uso: scripts/gg/c5-transacao-longa.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c5 "$@"
