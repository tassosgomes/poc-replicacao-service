#!/usr/bin/env bash
# Fase 3 do plano: calibracao do gerador contra o gg-legado. Primeiro com o EXTRACT PARADO (custo puro do legado, nenhum
# processo de captura), 1000 e 2000 ev/s por DUR s; depois religa o Extract, espera o Replicat zerar o lag e repete.
# Saida: resultados/gg/calibracao/<execucao>/<modo>-<taxa>/ e resumo.json. Uso: scripts/gg/calibrar-gerador.sh [DUR=120]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT/gg"; source ./lib.sh; cd "$ROOT"
DUR=${1:-120}; EXE=$(date -u +%Y%m%dT%H%M%SZ); BASE=resultados/gg/calibracao/$EXE; mkdir -p "$BASE"
stop() { gg POST /commands/execute "{\"name\":\"stop\",\"processName\":\"$1\"}" | ggmsg; }
start() { gg POST /commands/execute "{\"name\":\"start\",\"processName\":\"$1\"}" | ggmsg; }
estado() { gg GET /extracts/EXTBDAD/info/status | jq -r .response.status; }
cpu() { # arquivo csv de recursos -> "container media% max%"
  awk -F, '{gsub("%","",$3); v=$3+0; n[$2]++; s[$2]+=v; if(v>m[$2]) m[$2]=v} END{for(c in n) printf "%s media=%.1f max=%.1f amostras=%d\n", c, s[c]/n[c], m[c], n[c]}' "$@" | sed 's/gg-gerador-[0-9]*/gerador/'
}
rodar() { # modo taxa
  local d="$BASE/$1-$2"; echo "== $1 $2 ev/s por $DUR s"
  scripts/gg/gerar.sh "$2" "$DUR" "$d" | tee "$d.txt"
  { echo "recursos (CPU% de docker stats; 100% = 1 CPU, limite 200%):"; cpu "$d"/recursos-*.csv; } | tee -a "$d.txt"
  if [[ $1 == com-extract ]]; then
    local ini fim; ini=$(sed -n 's/.*inicio_carga=//p' "$d/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$d/gerador.log")
    scripts/gg/drenar.sh 900 | tee -a "$d.txt"
    scripts/gg/latencia-janela.sh "$ini" "$fim" "$d" | jq '{n,p50_s,p95_s,max_s}' | tee -a "$d.txt"
  fi
}
echo "Extract: $(estado)"
if [[ $(estado) == running ]]; then stop EXTBDAD; sleep 5; fi
echo "Extract parado: $(estado)"
rodar sem-extract 1000; rodar sem-extract 2000
t0=$(date +%s); start EXTBDAD; echo "Extract religado: $(estado)"
scripts/gg/drenar.sh 1800 | tee "$BASE/religar-extract.txt"; echo "religar+drenar: $(( $(date +%s)-t0 )) s (relogio do laptop)" | tee -a "$BASE/religar-extract.txt"
rodar com-extract 1000; rodar com-extract 2000
echo "calibracao em $BASE"
