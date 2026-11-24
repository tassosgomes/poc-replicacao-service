#!/usr/bin/env bash
# C10 custo no legado no lab Kafka, protocolo do C10 do GG (aquecimento descartado, ordem balanceada). Uso: scripts/kf/c10-custo.sh [rotulo]
# Pares de fases "ligada" (conector Debezium kf-oracle RUNNING, drenado antes da fase) e "desligada" (conector PARADO por PUT /stop; offset preservado no Connect), mesma taxa
# (1000 ev/s), 600 s por fase, semente, threads e mix, so updates (PCT_INSERT=0 PCT_DELETE=0; mix 40,40,20, TX_MAX 5, HOT_PCT 10), como o C10 Java/GG. AQUECIMENTO de 300 s
# com o conector ligado (descartado), ordem balanceada C10_ORDEM (padrao LD,DL,LD,DL = L D D L L D D L). Metrica: media de tempo de transacao do gerador
# (`transacao_media_ms`). Criterio: <= 15% E <= 5 ms (Q-T3 pendente). Supplemental logging ALL COLUMNS e FORCE LOGGING seguem ativos nas duas: o custo do supplemental NAO e isolado
# (variante sem supplemental nao feita: o redo sem as colunas quebraria/corromperia o pipeline ao religar o conector; ver docs/spike-kafka-notas.md). Durante as fases desligadas
# o status do conector e amostrado a cada 30 s (qualquer amostra diferente de STOPPED invalida a execucao). Ao fim: drena com o conector ligado, reconcilia, confere UUIDs.
# Extra: custo de infraestrutura = CPU/memoria medias por container (docker stats a cada ~3 s em cada fase) e uma amostra de REPOUSO de 60 s (so os workers consumindo) antes do aquecimento.
# Saida: 0 aprovado; 1 reprovado/invalido; 2 falha operacional. Variaveis: TAXA, DURACAO_SEG, C10_AQUECIMENTO_SEG, C10_ORDEM, THREADS, SEMENTE, HOT_PCT, KF_SAIDA.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; trap - ERR; [[ ${CONECTOR_PARADO:-0} == 1 ]] && conector_religar || true; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
ROT=${1:-}
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
TAXA=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}; AQUEC=${C10_AQUECIMENTO_SEG:-300}
ORDEM=${C10_ORDEM:-LD,DL,LD,DL}; IFS=, read -ra PARES <<< "$ORDEM"; REPS=${#PARES[@]}
(( REPS >= 3 )) || { echo "C10 exige >= 3 pares" >&2; exit 2; }
for pr in "${PARES[@]}"; do [[ $pr == LD || $pr == DL ]] || { echo "C10_ORDEM invalida: $pr" >&2; exit 2; }; done
SAIDA=${KF_SAIDA:-resultados/kf}
EXE=$(date -u +%Y%m%dT%H%M%SZ)${ROT:+-$ROT}; D=$SAIDA/c10/$EXE; mkdir -p "$D"
exigir_ambiente
INI_RUN=$(date -u +%FT%TZ)
supp() { printf "set heading off feedback off pagesize 0\nselect 'supplemental_min='||supplemental_log_data_min||' force_logging='||force_logging from v\$database;\nalter session set container=FREEPDB1;\nselect 'grupos_all_columns_tabelas='||count(*) from dba_log_groups where owner='BDAD' and log_group_type='ALL COLUMN LOGGING';\nexit\n" \
  | ssh_poc desenv-server 'docker exec -i kf-legado sqlplus -s / as sysdba' | tr -d '\r' | grep -E '^(supplemental|grupos)' | paste -sd' '; }
supp > "$D/supplemental-inicial.txt"; cat "$D/supplemental-inicial.txt"
scripts/kf/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/kf/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
scripts/kf/worker-fresco.sh 1 | tee "$D/worker-inicio.txt"
scripts/kf/drenar.sh 1800 | tee -a "$D/drenagem-inicial.txt"
# repouso: 60 s sem DML, conector RUNNING, 1 worker consumindo
mkdir -p "$D/repouso"
for h in desenv-server:kf-legado infra-server:"kf-novo kf-kafka kf-connect kf-worker-1"; do
  ssh_poc "${h%%:*}" "end=\$((\$(date +%s)+60)); while [ \$(date +%s) -lt \$end ]; do t=\$(date +%s.%N); docker stats --no-stream --format \"\$t,{{.Name}},{{.CPUPerc}},{{.MemUsage}}\" ${h#*:} 2>/dev/null; sleep 3; done" > "$D/repouso/recursos-${h%%:*}.csv" &
done
wait
python3 -I - "$D" <<'PY'
import sys, json, re, glob
d = sys.argv[1]; acc = {}
for f in glob.glob(d + "/repouso/recursos-*.csv"):
    for l in open(f):
        t = l.strip().split(",")
        if len(t) >= 4:
            v = float(t[2].rstrip("%")); e = acc.setdefault(t[1], [0, 0.0, 0.0]); e[0] += 1; e[1] += v; e[2] = max(e[2], v)
json.dump({k: {"media_pct": round(v[1] / v[0], 1), "max_pct": v[2], "amostras": v[0]} for k, v in acc.items()} | {"_descricao": "60 s sem DML, conector RUNNING, 1 kf-worker com 48 consumers assign() ativos"}, open(d + "/repouso.json", "w"), indent=1)
PY
jq -c . "$D/repouso.json"
if (( AQUEC > 0 )); then
  echo "=== aquecimento ${AQUEC} s (conector ligado, descartado)"; mkdir -p "$D/aquecimento"; AQ_INI=$(date -u +%FT%TZ)
  PCT_INSERT=0 PCT_DELETE=0 scripts/kf/gerar.sh "$TAXA" "$AQUEC" "$D/aquecimento" > "$D/aquecimento/gerar.txt"; cat "$D/aquecimento/gerar.txt"
  scripts/kf/drenar.sh 3600 | tee "$D/aquecimento/drenagem.txt"
fi
N=0
fase() { # nome captura(true|false) repeticao
  local nome=$1 cap=$2 rep=$3; N=$((N+1)); local S; S=$(printf 'p%02d-%s' "$N" "$nome"); local SD=$D/$S; mkdir -p "$SD"
  echo "=== fase $N: $nome (repeticao $rep)"
  if [[ $cap == true ]]; then
    [[ $(connector_state) == RUNNING ]] || conector_religar
    scripts/kf/drenar.sh 3600 | tee "$SD/drenagem-previa.txt"
  else
    scripts/kf/drenar.sh 3600 | tee "$SD/drenagem-previa.txt"   # fora da janela medida
    CONECTOR_PARADO=1; conector_parar; : > "$SD/status-conector.csv"
  fi
  [[ $N == 1 ]] && rel > "$SD/relogio-inicio.json"
  scripts/kf/limpar-archive.sh > "$SD/archive.json" || { cat "$SD/archive.json"; exit 2; }
  echo "conector=$(conector_estado) $(supp)" | tee "$SD/estado-antes.txt"
  local AMO=
  if [[ $cap == false ]]; then
    echo "$(date -u +%FT%TZ),$(conector_estado)" >> "$SD/status-conector.csv"
    ( while :; do sleep 30; echo "$(date -u +%FT%TZ),$(conector_estado)" >> "$SD/status-conector.csv"; done ) & AMO=$!
  fi
  PCT_INSERT=0 PCT_DELETE=0 scripts/kf/gerar.sh "$TAXA" "$DUR" "$SD" > "$SD/gerar.txt"; cat "$SD/gerar.txt"
  if [[ -n $AMO ]]; then
    kill "$AMO" 2>/dev/null || true; wait "$AMO" 2>/dev/null || true
    echo "$(date -u +%FT%TZ),$(conector_estado)" >> "$SD/status-conector.csv"
    echo "status do conector na fase: $(wc -l < "$SD/status-conector.csv") amostras, nao-STOPPED=$(grep -vc ',STOPPED/' "$SD/status-conector.csv" || true)"
    [[ $(grep -vc ',STOPPED/' "$SD/status-conector.csv" || true) == 0 ]] || { echo "conector NAO ficou parado em $S: execucao invalida" >&2; exit 2; }
  fi
  echo "conector=$(conector_estado) $(supp)" | tee "$SD/estado-depois.txt"
  local ini fim; ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
  jq -nc --arg dir "$S" --argjson alvo "$TAXA" --arg ini "$ini" --arg fim "$fim" --argjson cap "$cap" --argjson rep "$rep" --arg nome "$nome" --argjson pos "$N" \
    '{dir:$dir,alvo:$alvo,inicio_iso:$ini,fim_iso:$fim,drenagem_s:null,captura:$cap,repeticao:$rep,modo:$nome,supplemental:true,posicao:$pos}' >> "$D/degraus.jsonl"
}
for r in $(seq 1 "$REPS"); do
  for m in $(echo "${PARES[r-1]}" | fold -w1); do
    if [[ $m == L ]]; then fase ligada true "$r"; else fase desligada false "$r"; conector_religar; CONECTOR_PARADO=0; fi   # religa apos cada desligada; a drenagem ocorre fora da janela
  done
done
[[ $(connector_state) == RUNNING ]] || conector_religar
rel > "$D/relogio-fim.json"; cp "$D/relogio-fim.json" "$(ls -d "$D"/p*-* | tail -1)/relogio-fim.json"
scripts/kf/drenar.sh 3600 | tee "$D/drenagem-final.txt"
scripts/kf/limpar-archive.sh > "$D/archive-pos.json" || { cat "$D/archive-pos.json"; exit 2; }
scripts/kf/verificar-archive-necessario.sh > "$D/archive-necessario-final.json" || true
echo "conector=$(conector_estado) $(supp)" | tee "$D/estado-final.txt"
scripts/kf/lag.sh kf-worker --fim > "$D/lag-final.txt" || true
SREL=${SAIDA#resultados/kf}; SREL=${SREL#/}
integridade_final "$D" "${SREL:+$SREL/}c10" "$EXE"
jq -n --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1" --argjson d "$DUR" --argjson t "$TAXA" --argjson reps "$REPS" --argjson aq "$AQUEC" --arg ordem "$ORDEM" --arg aqini "${AQ_INI:-}" \
  --argjson ps "$(jq -nc --arg h "$(sha256sum scripts/kf/conector-oracle.json | cut -c1-16)" '{"conector-oracle.json":$h}')" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --arg wjar "$(sha256sum "$WJAR" | cut -d' ' -f1)" --slurpfile st "$D/degraus.jsonl" \
  '{cenario:"c10",execucao:$e,inicio:$i,fim:$f,pipeline:$r,workers:1,dur_s:$d,taxa:$t,taxas:[$t],repeticoes:$reps,aquecimento:{duracao_s:$aq,captura:true,descartado:true,inicio:$aqini,dir:"aquecimento"},ordem:$ordem,params_sha256:$ps,jar_sha256:$jar,worker_jar_sha256:$wjar,degraus:$st}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/kf/meta_kf.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" "$SAIDA/c10/meta.json.tmp" && mv "$SAIDA/c10/meta.json.tmp" "$SAIDA/c10/meta.json"
echo "resultados: $D"
exit $RC
