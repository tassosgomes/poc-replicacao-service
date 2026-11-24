#!/usr/bin/env bash
# Fase 4: mede estados parciais visiveis no destino no modo atual do worker ou no transacional, com a mesma carga.
# Carga: regime do C2 (TAXA ev/s, 1000) + gerador de transacoes multi-tabela (sql/kf/testes/atom_gerador.sql: 1 obra + N fonogramas + 1 titular
# por transacao, ATM_TX_S transacoes/s, N 1..12 e 100 a cada 50a) por DURACAO_SEG (300). Sonda no kf-novo (atom_sonda.sql, ~100 ms) em leitura consistente.
# Uso: scripts/kf/atomicidade.sh atual|transacional [rotulo]     Saida: resultados/kf/fase4/atomicidade/<modo>/<execucao>/
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
MODO=${1:?atual|transacional}; ROT=${2:-}
case $MODO in atual|transacional) ;; *) echo "modo invalido" >&2; exit 2 ;; esac
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
TAXA=${TAXA:-1000}; DUR=${DURACAO_SEG:-300}; TXS=${ATM_TX_S:-5}; INTERVALO=${ATM_SONDA_MS:-100}
MAXSEQ=$((TXS * DUR))
BASE_DIR=${KF_SAIDA:-resultados/kf/fase4}/atomicidade/$MODO; mkdir -p "$BASE_DIR"
# base unica por corrida (minutos desde a epoca do desenv-server): os titulares/obras/fonogramas de corridas anteriores ficam no lab
B=$((20000000 + ($(ssh_poc desenv-server date +%s) / 60 % 1000000) * 10000))
EXE=$(date -u +%Y%m%dT%H%M%SZ)${ROT:+-$ROT}; D=$BASE_DIR/$EXE; mkdir -p "$D"
exigir_ambiente
scripts/kf/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/kf/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
# titulares que as transacoes vao atualizar (replicam antes da medicao)
sed "s/&1/$B/g; s/&2/$MAXSEQ/g" sql/kf/testes/atom_setup.sql | scripts/kf/q.sh legado > "$D/setup.txt"
scripts/kf/drenar.sh 1800 | tee -a "$D/drenagem-inicial.txt"
KF_WORKER_ENV="MODO=$MODO" scripts/kf/worker-fresco.sh 1 | tee "$D/worker-inicio.txt"
scripts/kf/drenar.sh 1800 | tee -a "$D/drenagem-inicial.txt"
rel > "$D/relogio-inicio.json"
sed "s/&1/$B/g; s/&2/$MAXSEQ/g; s/&3/$((DUR + 400))/g; s/&4/$INTERVALO/g" sql/kf/testes/atom_sonda.sql | scripts/kf/q.sh novo > "$D/sonda.txt" & SONDA=$!
sleep 3
sed "s/&1/$B/g; s/&2/$MAXSEQ/g; s/&3/$TXS/g; s/&4/$((DUR + 30))/g; s/&5/42/g" sql/kf/testes/atom_gerador.sql | scripts/kf/q.sh legado > "$D/atm-gerador.txt" & ATM=$!
scripts/kf/gerar.sh "$TAXA" "$DUR" "$D" | tee "$D/gerar.txt"
wait $ATM || { echo "gerador atomico falhou" >&2; exit 2; }
rel > "$D/relogio-fim.json"
scripts/kf/drenar.sh 1800 | tee "$D/drenagem.txt"
wait $SONDA || { echo "sonda falhou" >&2; exit 2; }
scripts/kf/coletar-eventos.sh "$D/workers" > /dev/null || true
OFF=$(jq -r '(.offset_ms)' "$D/relogio-inicio.json")
python3 -I scripts/kf/atomicidade_analise.py "$D/atm-gerador.txt" "$D/sonda.txt" "$OFF" > "$D/atomicidade.json"
cat "$D/atomicidade.json"
scripts/kf/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && echo "reconciliacao: 0 divergencias" || { echo "reconciliacao: DIVERGENCIA"; tail -5 "$D/reconciliacao-sql.txt"; }
scripts/kf/lag.sh kf-worker --fim > "$D/lag-final.txt" || true
ssh_poc infra-server "docker logs kf-worker-1 2>&1 | grep -v 'owned partitions' | tail -30" > "$D/worker-log.txt" || true
jq -nc --arg modo "$MODO" --argjson dur "$DUR" --argjson taxa "$TAXA" --argjson txs "$TXS" --argjson b "$B" --slurpfile a "$D/atomicidade.json" \
  '{modo:$modo,duracao_seg:$dur,taxa_fundo:$taxa,tx_multi_por_s:$txs,base:$b,atomicidade:$a[0]}' > "$D/meta.json"
echo "resultado em $D"
