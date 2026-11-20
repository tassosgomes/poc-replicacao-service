#!/usr/bin/env bash
# Rajada de 4000 updates com commit individual; imprime o fim na origem e a vazao de apply. Uso: scripts/gg/rajada.sh TAG
set -euo pipefail
D="$(dirname "$0")/../../sql/gg/testes"; tag=${1:?tag}
sed "s/&1/$tag/g" "$D/burst_origem.sql" | "$(dirname "$0")/q.sh" legado | grep -A1 burst_fim | tail -1
sleep 30
sed "s/&1/$tag/g" "$D/burst_destino.sql" | "$(dirname "$0")/q.sh" novo
