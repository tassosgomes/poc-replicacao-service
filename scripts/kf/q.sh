#!/usr/bin/env bash
# Executa SQL (stdin) no lab Kafka: scripts/kf/q.sh legado|novo   (legado = bdad no kf-legado; novo = replica no kf-novo)
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
kfq "${1:?legado|novo}"
