#!/usr/bin/env bash
# C5 transacao longa no lab Kafka (padroes do PRD). Ver scripts/kf/cenario.sh. Uso: scripts/kf/c5-transacao-longa.sh [rotulo]
exec "$(dirname "$0")/cenario.sh" c5 "$@"
