#!/usr/bin/env bash
# Fase 2 do plano: calibracao do gerador contra o kf-legado. Primeiro com o CONECTOR PAUSADO (PUT /connectors/kf-oracle/pause: nenhum
# LogMiner minerando, custo puro do legado), depois religa, drena e repete com o LogMiner ligado. Patamares dos C2-C7 do lab Java
# (C3: 500, 1000, 2000, 4000; C2/C4-C10: 1000) por DUR s cada, gerador no desenv-server (gerar.sh). Com o conector ligado amostra o
# lag do consumidor a cada ~10 s (tendencia) e mede o tempo de drenagem depois de cada patamar.
# Saida: resultados/kf/calibracao/<execucao>/<modo>-<taxa>/ (recursos CSV ignorados pelo git) e <modo>-<taxa>.txt.
# Uso: scripts/kf/calibrar-gerador.sh [DUR=120] [TAXAS=500,1000,2000,4000]    Variaveis do gerador: THREADS, GERADOR_HOST...
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
cd "$ROOT"
DUR=${1:-120}; TAXAS=${2:-500,1000,2000,4000}; EXE=$(date -u +%Y%m%dT%H%M%SZ); BASE=resultados/kf/calibracao/$EXE; mkdir -p "$BASE"
estado() { connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.tasks[0].state // "?"'; }
cpu() { awk -F, '{gsub("%","",$3); v=$3+0; n[$2]++; s[$2]+=v; if(v>m[$2]) m[$2]=v} END{for(c in n) printf "%s media=%.1f max=%.1f amostras=%d\n", c, s[c]/n[c], m[c], n[c]}' "$@" | sed 's/kf-gerador-[0-9]*/gerador/' | sort; }
rodar() { # modo taxa
  local d="$BASE/$1-$2"; mkdir -p "$d"; echo "== $1 $2 ev/s por $DUR s"
  local amp=
  if [[ $1 == com-conector ]]; then
    ( end=$((SECONDS+DUR+10)); while (( SECONDS < end )); do echo "$(date +%s),$("$ROOT/scripts/kf/lag.sh" 2>/dev/null || echo ?)"; sleep 6; done ) > "$d/lag.csv" & amp=$!
  fi
  scripts/kf/gerar.sh "$2" "$DUR" "$d" | tee "$d.txt"
  [[ -z $amp ]] || wait "$amp" || true
  { echo "recursos (CPU% de docker stats; 100% = 1 CPU; limites: legado/novo/kafka/connect 200%, gerador 200%):"; cpu "$d"/recursos-*.csv; } | tee -a "$d.txt"
  if [[ $1 == com-conector ]]; then
    { echo "lag do consumidor a cada ~6 s (primeiro, maximo, ultimo):"; awk -F, '$2!="?"{if(n==0)p=$2; if($2+0>m)m=$2+0; u=$2; n++} END{print p, m, u}' "$d/lag.csv"; } | tee -a "$d.txt"
    scripts/kf/drenar.sh 1800 | tee -a "$d.txt"
  fi
  scripts/kf/limpar-archive.sh > "$d/archive.json" || { cat "$d/archive.json"; exit 2; }
}
IFS=, read -ra LISTA <<< "$TAXAS"
echo "conector: $(estado)"
connect_api -X PUT localhost:18083/connectors/kf-oracle/pause >/dev/null; sleep 8
echo "conector pausado: $(estado)"
trap 'connect_api -X PUT localhost:18083/connectors/kf-oracle/resume >/dev/null || true' EXIT
for t in "${LISTA[@]}"; do rodar sem-conector "$t"; done
t0=$(agora); connect_api -X PUT localhost:18083/connectors/kf-oracle/resume >/dev/null
trap - EXIT
echo "conector religado: $(estado)"
scripts/kf/drenar.sh 3600 | tee "$BASE/religar-conector.txt"; echo "religar+drenar: $(dur "$t0" "$(agora)") s (relogio do desenv-server)" | tee -a "$BASE/religar-conector.txt"
for t in "${LISTA[@]}"; do rodar com-conector "$t"; done
echo "calibracao em $BASE"
