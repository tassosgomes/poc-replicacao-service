#!/usr/bin/env bash
# MEDICAO EXTRA (descritiva, sem criterio e sem meta.json oficial) do C4 do lab Kafka: queda (SIGKILL) do Kafka Connect ou do broker durante o
# regime. Uso: scripts/kf/extra-queda.sh connect|broker [rotulo]
# Mesmo regime do C6 dos outros labs: TAXA 1000 ev/s por DURACAO_SEG (600), queda em QUEDA_APOS_SEG (180) s apos o inicio da carga e `docker start`
# em REINICIO_APOS_SEG (240) s (pausa de 60 s; scripts/kf/infra-evento.sh). Mede o tempo ate o primeiro balde de 5 s completo com lag <= 5 s (a
# latencia maxima por balde, como C2/GG; scripts/gg/analisar-queda.py), desde a queda e desde o reinicio, o estado da tarefa do conector, a diferenca
# entre eventos no topico e linhas do gerador (duplicatas por at-least-once apos a queda do Connect), e integridade (reconciliacao SQL e UUIDs).
# Os eventos dos workers sao deduplicados por (tabela,codigo,criado_em) para a analise (o primeiro commit vale); o numero de re-entregas e registrado.
# Grava em ${KF_EXTRA_BASE:-resultados/kf/c4/extras}/<execucao>-<alvo>/ (extra.json = resumo). Saida: 0 integro (0 divergencias, 0 UUIDs), 1 caso contrario, 2 falha.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
ALVO=${1:?connect|broker}; ROT=${2:-}
case $ALVO in connect|broker) ;; *) echo "alvo invalido" >&2; exit 2 ;; esac
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
TAXA=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}; QUEDA=${QUEDA_APOS_SEG:-180}; REINI=${REINICIO_APOS_SEG:-240}; POS=${OBSERVACAO_APOS_REINICIO_SEG:-45}
(( DUR < REINI + POS + 30 )) && DUR=$((REINI + POS + 30))
NW=${KF_WORKERS:-1}
EXE=$(date -u +%Y%m%dT%H%M%SZ)-$ALVO${ROT:+-$ROT}; BASE_EXTRA=${KF_EXTRA_BASE:-resultados/kf/c4/extras}; D=$BASE_EXTRA/$EXE; mkdir -p "$D"
exigir_ambiente
INI_RUN=$(date -u +%FT%TZ)
scripts/kf/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/kf/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
scripts/kf/worker-fresco.sh "$NW" | tee "$D/worker-inicio.txt"
scripts/kf/drenar.sh 1800 | tee -a "$D/drenagem-inicial.txt"
read -r _ FIM0 < <(scripts/kf/lag.sh kf-worker --fim)
SD=$D/d1-$TAXA; mkdir -p "$SD"
rel > "$SD/relogio-inicio.json"
OFF=$(jq -r .offset_ms "$SD/relogio-inicio.json"); NOFF=$(awk -v o="$OFF" 'BEGIN{print -o}'); NOFFS=$(awk -v o="$NOFF" 'BEGIN{printf "%.6f", o/1000}')
scripts/kf/gerar.sh "$TAXA" "$DUR" "$SD" > "$SD/gerar.txt" 2>&1 & BG=$!
aguardar_inicio_carga || { kill $BG 2>/dev/null || true; exit 2; }
IEI=$(awk -v i="$(epoch_iso "$LI")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')   # inicio da carga no relogio do infra-server
alvo_t() { awk -v i="$IEI" -v s="$1" 'BEGIN{printf "%.3f", i+s}'; }
scripts/kf/infra-evento.sh "$ALVO" "$(alvo_t "$QUEDA")" $((REINI - QUEDA)) > "$D/evento.json"
jq -c 'del(.estados)' "$D/evento.json"
wait $BG || { cat "$SD/gerar.txt" >&2; exit 2; }
cat "$SD/gerar.txt"
rel > "$SD/relogio-fim.json"
ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
T_DREN0=$(agora_infra)
scripts/kf/drenar.sh 1800 | tee "$SD/drenagem.txt"
T_DREN1=$(agora_infra)
connect_api localhost:18083/connectors/kf-oracle/status | jq -c '{conector:.connector.state,tarefa:.tasks[0].state,trace:(.tasks[0].trace // null | if . then .[0:300] else . end)}' > "$D/conector-estado-final.json"
read -r LAGF FIM1 < <(scripts/kf/lag.sh kf-worker --fim)
scripts/kf/limpar-archive.sh > "$SD/archive.json" || { cat "$SD/archive.json"; exit 2; }
scripts/kf/coletar-eventos.sh "$D/workers" > /dev/null
python3 -I scripts/kf/eventos_kf.py "$D/workers" "$ini" "$fim" "$SD/eventos-bruto.csv" > "$SD/eventos-bruto.txt"
python3 -I scripts/kf/eventos_kf.py "$D/workers" "$ini" "$fim" "$SD/eventos.csv" --dedupe | tee "$SD/eventos.txt"
cut -d, -f1,2 "$SD/eventos.csv" > "$SD/latencias.csv"
python3 -I scripts/gg/analisar-latencia.py "$SD/latencias.csv" "$ini" "$fim" "$NOFF" > "$SD/latencia.json"
jq -nc --argjson ini "$(awk -v i="$(epoch_iso "$ini")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')" --argjson fim "$(awk -v i="$(epoch_iso "$fim")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')" \
  --slurpfile e "$D/evento.json" --arg n "queda_${ALVO}_e_start" \
  '{ini:$ini,fim_carga:$fim,eventos:[{nome:$n,t_queda:$e[0].t_cmd0,t_reinicio:$e[0].t_cmd1,t_rest:$e[0].t_rest,t_ext:$e[0].t_ext,t_rep:null,t_kill_ret:$e[0].t_kill,t_start_cmd0:$e[0].t_start0}]}' > "$SD/entrada-queda.json"
python3 -I scripts/gg/analisar-queda.py "$SD/latencias.csv" "$SD/entrada-queda.json" "$NOFF" "$SD" | tee "$SD/queda.txt"
gzip -f "$SD/eventos.csv" "$SD/eventos-bruto.csv" "$SD/latencias.csv"
SREL=${BASE_EXTRA#resultados/kf/}
integridade_final "$D" "$SREL" "$EXE"
LIN=$(sed -n 's/.*linhas=\([0-9]*\) transacoes=.*/\1/p' "$SD/gerador.log" | tail -1)
jq -n --arg a "$ALVO" --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --argjson q "$QUEDA" --argjson re "$REINI" --argjson dur "$DUR" --argjson taxa "$TAXA" \
  --argjson linhas "${LIN:-null}" --argjson fim0 "$FIM0" --argjson fim1 "$FIM1" --argjson lagf "$LAGF" --argjson dren "$(awk -v a="$T_DREN0" -v b="$T_DREN1" 'BEGIN{printf "%.1f", b-a}')" \
  --slurpfile ev "$D/evento.json" --slurpfile qd "$SD/queda.json" --slurpfile bruto "$SD/eventos-bruto.txt" --slurpfile dedup "$SD/eventos.txt" \
  --slurpfile ger "$SD/relogio-inicio.json" --slurpfile estado "$D/conector-estado-final.json" --slurpfile uu "$D/uuids.json" --slurpfile lat "$SD/latencia.json" \
  --arg rec "$(grep -E 'divergencias|DIVERGENCIAS' "$D/reconciliacao-sql.txt" | tail -1)" \
  '{extra:"c4",alvo:$a,execucao:$e,inicio:$i,fim:$f,descritivo:true,regime:{taxa:$taxa,duracao_s:$dur,queda_apos_s:$q,reinicio_apos_s:$re},
    gerador_linhas:$linhas,eventos_no_topico_durante_o_regime_e_drenagem:($fim1-$fim0),diferenca_topico_menos_linhas:(if $linhas==null then null else ($fim1-$fim0-$linhas) end),
    drenagem_apos_fim_da_carga_s:$dren,lag_consumer_final:$lagf,eventos_worker_csv_bruto:$bruto[0],eventos_worker_csv_dedup:$dedup[0],
    queda:($qd[0].eventos[0]|del(.baldes)),estado_conector_final:$estado[0],estados_da_tarefa:($ev[0].estados),uuids:$uu[0],reconciliacao:$rec,
    latencia_regime_s:($lat[0]|{n,p50_s,p95_s,p99_s,max_s})}' > "$D/extra.json"
jq -c 'del(.estados_da_tarefa)' "$D/extra.json"
trap - ERR
[[ $(jq -r '.reconciliacao|test("0 divergencias")' "$D/extra.json") == true && $(jq -r .uuids.uuids_alterados "$D/extra.json") == 0 ]] && exit 0 || exit 1
