#!/usr/bin/env bash
# VARIANTES DESCRITIVAS (fora dos oficiais) da configuracao do conector Debezium para a latencia de captura: cada variante aplica overrides sobre
# scripts/kf/conector-oracle.json, roda um C2 ENCURTADO (VARIANTE_C2_SEG=300 s a 1.000 ev/s; o oficial tem 600 s) via cenario.sh com KF_SAIDA proprio e registra p50/p95/p99/max, tendencia e a CPU
# media de cada container (kf-legado = custo no legado). Ordem: controle (configuracao oficial no mesmo dia), depois as variantes (VARIANTES="nome={json}|..."). Ao fim RESTAURA a configuracao
# oficial do conector (aplicar-variante-conector.sh sem argumento) e confere. Nota: log.mining.sleep.time.* e log.mining.batch.size.* NAO existem no Debezium 3.7.1 (o validate do Connect nao os lista;
# os 100/300/500 ms da Fase 0 eram ignorados); os botoes reais sao poll.interval.ms, max.batch.size, log.mining.log.count.min, log.mining.query.filter.mode, log.mining.strategy etc.
# Uso: scripts/kf/variantes-conector.sh [rotulo]
set -Eeuo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
ROT=${1:-}
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
BASE=resultados/kf/c9/log-mining/$(date -u +%Y%m%dT%H%M%SZ)${ROT:+-$ROT}; mkdir -p "$BASE"; : > "$BASE/variantes.jsonl"
restaurar() { scripts/kf/aplicar-variante-conector.sh > "$BASE/restaurar.txt" 2>&1 || true; }
trap 'echo "interrompido; restaurando configuracao oficial" >&2; restaurar' ERR
VARIANTES=${VARIANTES:-'controle={}|poll100={"poll.interval.ms":"100"}|filtro-in={"log.mining.query.filter.mode":"in"}|logs-min1={"log.mining.log.count.min":"1"}|sem-tx-meta={"provide.transaction.metadata":"false"}|combinado={"poll.interval.ms":"100","log.mining.query.filter.mode":"in","log.mining.log.count.min":"1","provide.transaction.metadata":"false"}|redo-catalog={"log.mining.strategy":"redo_log_catalog"}'}
IFS='|' read -ra LISTA <<< "$VARIANTES"
for v in "${LISTA[@]}"; do
  nome=${v%%=*}; ov=${v#*=}
  echo "=== variante $nome: $ov"
  if [[ $nome == controle ]]; then scripts/kf/aplicar-variante-conector.sh > "$BASE/aplicar-$nome.txt" 2>&1 || true
  elif ! scripts/kf/aplicar-variante-conector.sh "$ov" > "$BASE/aplicar-$nome.txt" 2>&1; then
    cat "$BASE/aplicar-$nome.txt" | tail -5
    jq -nc --arg n "$nome" --arg ov "$ov" '{variante:$n,overrides:$ov,resultado:"NAO_APLICOU (tarefa FAILED ou nao entrou em streaming)"}' >> "$BASE/variantes.jsonl"; scripts/kf/aplicar-variante-conector.sh > /dev/null 2>&1 || true; continue; fi
  env KF_SAIDA="$BASE/runs" TAXA=1000 DURACAO_SEG=${VARIANTE_C2_SEG:-300} scripts/kf/cenario.sh c2 "$nome" > "$BASE/$nome.log" 2>&1 || true
  run=$(ls -d "$BASE/runs/c2"/2*-"$nome" | tail -1)
  jq -c --arg n "$nome" --arg ov "$ov" --arg dir "${run#resultados/kf/}" '{variante:$n,overrides:$ov,dir:$dir,veredito:.veredito,validade:.validade.estado,taxa:.degraus[0].taxa,p50_s:.degraus[0].latencia_s.p50_s,p95_ms:.degraus[0].p95_ms,p99_s:.degraus[0].latencia_s.p99_s,max_s:.degraus[0].latencia_s.max_s,
     crescimento:.degraus[0].tendencia_lag.crescimento_sustentado,inclinacao_ms_s:.degraus[0].tendencia_lag.inclinacao_ms_s,eventos:.degraus[0].eventos_medidos,divergencias:.integridade.divergencias,uuids_alterados:.integridade.uuids_alterados,
     cpu_media_pct:(.degraus[0].cpu_docker_stats|with_entries(.value=.value.media_pct))}' "$run/meta.json" >> "$BASE/variantes.jsonl"
  tail -1 "$BASE/variantes.jsonl" | cut -c1-400
done
trap - ERR
restaurar
cat "$BASE/restaurar.txt"
jq -S 'with_entries(select(.key|test("password|secret";"i")|not))' <(connect_api localhost:18083/connectors/kf-oracle/config) > "$BASE/conector-config-final.json"
echo "configuracao final == oficial? $(diff <(sed -e 's/__DECIMAL__/string/;s/__TIME__/adaptive/' scripts/kf/conector-oracle.json | jq -S 'with_entries(select(.key|test("password|secret";"i")|not))') <(jq -S 'del(.name)' "$BASE/conector-config-final.json") > /dev/null && echo sim || echo NAO)" | tee "$BASE/config-final-confere.txt"
echo "resultados: $BASE"
