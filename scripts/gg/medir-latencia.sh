#!/usr/bin/env bash
# Mede a latencia por evento (aplicado_em - origem_updated_at) para uma carga: TAXA ev/s por DUR s, drena e analisa.
# Uso: scripts/gg/medir-latencia.sh TAXA DUR DIR_SAIDA [offset_ms=0]. Trunca o aplicacao_log (so log; nao toca nas tabelas de negocio).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
TAXA=$1; DUR=$2; OUT=$3; OFF=${4:-0}
scripts/gg/drenar.sh 900 >/dev/null
printf "truncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo >/dev/null
scripts/gg/gerar.sh "$TAXA" "$DUR" "$OUT" | grep -E "linhas=.*taxa"
ini=$(sed -n 's/.*inicio_carga=//p' "$OUT/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$OUT/gerador.log")
scripts/gg/drenar.sh 900
scripts/gg/hb-estagios.sh 3 > "$OUT/heartbeat-estagios.txt"
scripts/gg/latencia-janela.sh "$ini" "$fim" "$OUT" "$OFF" | jq -c '{n,min_s,p50_s,p95_s,p99_s,max_s,media_s,incl_media_ms_s:.tendencia_media_por_5s.inclinacao_ms_s,incl_max_ms_s:.tendencia_max_por_5s.inclinacao_ms_s}'
