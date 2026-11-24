#!/usr/bin/env bash
# C9 (descritivo, SEM_CRITERIO) no lab Kafka: modos do WORKER (o analogo do C9 Java, que variava threads; aqui tambem o lote e o polling). Cada modo roda um C2 curto
# (C9_C2_SEG=300 s a 1.000 ev/s; o C2 oficial tem 600 s e o C9 do GG usou 600) e um degrau de 2.000 ev/s de C9_C3_SEG=300 s, via scripts/kf/cenario.sh com KF_SAIDA proprio (NAO
# sobrescreve o meta.json oficial de c2/c3). Modos (C9_MODOS, "nome=VAR=VALOR VAR=VALOR|..."): oficial (padrao), plataforma (VIRTUAL_THREADS=false), lote-100 (LOTE_MAX=100),
# lote-2000 (LOTE_MAX=2000), poll-rapido (POLL_MS=20 KAFKA_POLL_MS=10). Particoes: fixas em 16 por topico (48 particoes logicas) no desenho; nao ha o que variar no worker.
# Ao fim restaura o worker oficial (worker-fresco sem variaveis). Uso: scripts/kf/c9-comparacao.sh [rotulo]
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
ROT=${1:-}
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
SAIDA=${KF_SAIDA:-resultados/kf}
EXE=$(date -u +%Y%m%dT%H%M%SZ)${ROT:+-$ROT}; D=$SAIDA/c9/$EXE; mkdir -p "$D/runs"; : > "$D/modos.jsonl"
exigir_ambiente
INI_RUN=$(date -u +%FT%TZ)
scripts/kf/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
rel > "$D/relogio-inicio.json"
MODOS=${C9_MODOS:-"oficial=|plataforma=VIRTUAL_THREADS=false|lote-100=LOTE_MAX=100|lote-2000=LOTE_MAX=2000|poll-rapido=POLL_MS=20 KAFKA_POLL_MS=10"}
IFS='|' read -ra LISTA <<< "$MODOS"
for m in "${LISTA[@]}"; do
  nome=${m%%=*}; envs=${m#*=}
  echo "=== modo $nome ($envs)"
  for g in c2 c3; do
    if [[ $g == c2 ]]; then P=(TAXA=1000 DURACAO_SEG=${C9_C2_SEG:-300}); else P=(C3_TAXAS=2000 DEGRAU_SEG=${C9_C3_SEG:-300}); fi
    env KF_SAIDA="$D/runs" KF_WORKER_ENV="$envs" "${P[@]}" scripts/kf/cenario.sh $g "$nome-$g" > "$D/runs/$nome-$g.log" 2>&1 || true
    run=$(ls -d "$D/runs/$g"/2*-"$nome-$g" | tail -1)
    [[ -s $run/meta.json ]] || { echo "sem meta em $run" >&2; exit 2; }
    jq -c --arg modo "$nome" --arg envs "$envs" --arg g "$g" --arg dir "${run#resultados/kf/}" \
      '{modo:$modo,env:$envs,grupo:$g,dir:$dir,veredito:.veredito,validade:.validade.estado,degrau:(.degraus|last|{alvo,taxa,validade,p50_s:.latencia_s.p50_s,p95_ms,p99_s:.latencia_s.p99_s,max_s:.latencia_s.max_s,crescimento:.tendencia_lag.crescimento_sustentado,inclinacao_ms_s:.tendencia_lag.inclinacao_ms_s,drenagem_s,eventos:.eventos_medidos,cpu:(.cpu_docker_stats|with_entries(.value=.value.media_pct))}),
        divergencias:.integridade.divergencias,uuids_alterados:.integridade.uuids_alterados}' "$run/meta.json" >> "$D/modos.jsonl"
    tail -1 "$D/modos.jsonl" | cut -c1-300
  done
done
# restaura o worker oficial
scripts/kf/worker-fresco.sh 1 > /dev/null
scripts/kf/drenar.sh 1800 | tee "$D/drenagem-final.txt"
rel > "$D/relogio-fim.json"
scripts/kf/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && true || true
scripts/kf/limpar-archive.sh > "$D/archive-depois.json" || { cat "$D/archive-depois.json"; exit 2; }
jq -s '{preexistentes_comparados:(map(.uuids_alterados|numbers)|length),uuids_alterados:(map(.uuids_alterados|numbers)|add)}' "$D/modos.jsonl" > "$D/uuids.json"
# comparacao.csv
jq -r '["modo","grupo","alvo_ev_s","taxa_ev_s","validade","p50_s","p95_ms","p99_s","max_s","crescimento_sustentado","inclinacao_ms_s","drenagem_s","cpu_kf_legado","cpu_kf_novo","cpu_kf_kafka","cpu_kf_connect","cpu_worker"],
  (.[]|[.modo,.grupo,.degrau.alvo,.degrau.taxa,.degrau.validade,.degrau.p50_s,.degrau.p95_ms,.degrau.p99_s,.degrau.max_s,.degrau.crescimento,.degrau.inclinacao_ms_s,.degrau.drenagem_s,.degrau.cpu["kf-legado"],.degrau.cpu["kf-novo"],.degrau.cpu["kf-kafka"],.degrau.cpu["kf-connect"],.degrau.cpu["kf-worker-1"]])|@csv' <(jq -s . "$D/modos.jsonl") > "$D/comparacao.csv"
jq -n --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" \
  --argjson ps "$(jq -nc --arg h "$(sha256sum scripts/kf/conector-oracle.json | cut -c1-16)" '{"conector-oracle.json":$h}')" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --arg wjar "$(sha256sum "$WJAR" | cut -d' ' -f1)" \
  '{cenario:"c9",execucao:$e,inicio:$i,fim:$f,pipeline:"worker Kafka x1 em modos (ver modos.jsonl); Debezium 3.7.1 LogMiner; Kafka 4.3.1",workers:1,dur_s:null,taxa:null,taxas:[],params_sha256:$ps,jar_sha256:$jar,worker_jar_sha256:$wjar,degraus:[]}' > "$D/cenario.json"
inventario_containers > "$D/containers.json"
trap - ERR
RC=0; python3 -I scripts/kf/meta_kf.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" "$SAIDA/c9/meta.json.tmp" && mv "$SAIDA/c9/meta.json.tmp" "$SAIDA/c9/meta.json"
echo "resultados: $D"
exit $RC
