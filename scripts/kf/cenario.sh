#!/usr/bin/env bash
# Runner dos cenarios de regime/teto/rajada/transacao longa no lab Kafka (alternativa A: Debezium + Kafka + worker). Uso:
#   scripts/kf/cenario.sh c1|c2|c3|c4|c5 [rotulo]
# Grava em resultados/kf/<cenario>/<execucao>/ e copia o meta.json (MESMO contrato do lab Java/GG: criterios/degraus/validade/veredito, ver
# scripts/kf/meta_kf.py) para resultados/kf/<cenario>/meta.json, de modo que `bash scripts/relatorio.sh --resultados resultados/kf` consolide.
# Variaveis (as mesmas do lab GG): TAXA (c2, 1000), DURACAO_SEG (c2, 600), DEGRAU_SEG (c3, 300), C3_TAXAS (500,1000,2000,4000), THREADS, SEMENTE,
#   PCT_INSERT, PCT_DELETE, HOT_PCT, TX_MAX (padroes do C2), SNAPSHOT_BASE (resultados/kf/c1/snapshots/apos-carga.csv), KF_WORKERS (1, como o
#   lab Java em C2-C5), RAJADA_APOS_SEG (120), RAJADA_LINHAS (50000), OBSERVACAO_RECUPERACAO_SEG (125), PATAMAR_JANELA_SEG (60),
#   TX_APOS_SEG (5), TX_ABERTA_SEG (30).
# Medicao: workers NOVOS (scripts/kf/worker-fresco.sh) com EVENTOS_CSV=true; latencia por evento = confirmado_em (commit no kf-novo, relogio
#   do infra-server) - criado_em (= updated_at da origem, relogio do legado) - offset de relogios medido (scripts/medir-relogios.py), eventos
#   com criado_em na janela de geracao (scripts/kf/eventos_kf.py converte para o formato dos analisadores do lab GG, reutilizados).
#   Drenado = lag do consumer group 0 e end offsets sem crescer (scripts/kf/drenar.sh) com o conector RUNNING.
#   Regra do lab Java: taxa efetiva < 98% do alvo = GERADOR_SATURADO (c3: interrompe a escada) / INVALIDO.
# c4: regime TAXA por DURACAO_SEG (>= RAJADA_APOS_SEG+OBSERVACAO_RECUPERACAO_SEG+30) e, RAJADA_APOS_SEG s apos o inicio, um segundo gerador
#   (RAJADA_LINHAS: uma UPDATE de 50.000 linhas de TITULAR e um COMMIT). c5: TX_APOS_SEG s apos o inicio, UPDATE de TITULAR 1 em transacao
#   aberta por TX_ABERTA_SEG s (sql/kf/testes/tx_longa.sql). c1: delega a scripts/kf/c1-carga.sh.
# c6|c7|c8 delegam a scripts/kf/falhas.sh, c9 a c9-comparacao.sh e c10 a c10-custo.sh.
# Saida: 0 aprovado; 1 reprovado/invalido; 2 falha operacional.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
CEN=${1:?c1|c2|c3|c4|c5}; ROT=${2:-}
[[ $CEN == c1 ]] && exec "$(dirname "$0")/c1-carga.sh" "${@:2}"
case $CEN in c6|c7|c8) exec "$(dirname "$0")/falhas.sh" "$@" ;; c9) exec "$(dirname "$0")/c9-comparacao.sh" "${@:2}" ;; c10) exec "$(dirname "$0")/c10-custo.sh" "${@:2}" ;; esac
case $CEN in c2|c3|c4|c5) ;; *) echo "cenario invalido: $CEN" >&2; exit 2 ;; esac
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
if [[ $CEN == c3 ]]; then TAXAS=${C3_TAXAS:-500,1000,2000,4000}; DUR=${DEGRAU_SEG:-300}; else TAXAS=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}; fi
if [[ $CEN == c4 ]]; then DELAY=${RAJADA_APOS_SEG:-120}; OBS=${OBSERVACAO_RECUPERACAO_SEG:-125}; RLIN=${RAJADA_LINHAS:-50000}; (( DUR < DELAY + OBS + 30 )) && DUR=$((DELAY + OBS + 30)); fi
if [[ $CEN == c5 ]]; then DELAY=${TX_APOS_SEG:-5}; HOLD=${TX_ABERTA_SEG:-30}; (( DUR < DELAY + HOLD + 30 )) && DUR=$((DELAY + HOLD + 30)); fi
NW=${KF_WORKERS:-1}
SAIDA=${KF_SAIDA:-resultados/kf}
EXE=$(date -u +%Y%m%dT%H%M%SZ)${ROT:+-$ROT}; D=$SAIDA/$CEN/$EXE; mkdir -p "$D"
exigir_ambiente
INI_RUN=$(date -u +%FT%TZ)
scripts/kf/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/kf/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
scripts/kf/worker-fresco.sh "$NW" | tee "$D/worker-inicio.txt"
scripts/kf/drenar.sh 1800 | tee -a "$D/drenagem-inicial.txt"
STEPS=()
IFS=, read -ra LISTA <<< "$TAXAS"; n=0
for T in "${LISTA[@]}"; do
  n=$((n+1)); S=d$n-$T; SD=$D/$S; mkdir -p "$SD"
  rel > "$SD/relogio-inicio.json"
  if [[ $CEN == c4 || $CEN == c5 ]]; then
    # regime em segundo plano; a operacao especial parte DELAY s depois do inicio_carga do gerador de fundo (relogio do desenv-server)
    scripts/kf/gerar.sh "$T" "$DUR" "$SD" > "$SD/gerar.txt" 2>&1 & BG=$!
    aguardar_inicio_carga || { kill $BG 2>/dev/null || true; exit 2; }
    ie=$(epoch_iso "$LI"); fa=$(awk -v d="$DELAY" -v i="$ie" -v n="$(agora)" 'BEGIN{r=d-(n-i); print (r>0?r:0)}'); sleep "$fa"
    mkdir -p "$D/especial"
    if [[ $CEN == c4 ]]; then
      scripts/kf/gerar.sh "$T" 10 "$D/especial" RAJADA_LINHAS=$RLIN > "$D/especial/gerar.txt" 2>&1 || { cat "$D/especial/gerar.txt" >&2; exit 2; }
    else
      sed "s/&1/$HOLD/g" sql/kf/testes/tx_longa.sql | scripts/kf/q.sh legado > "$D/especial/tx-longa.txt" || { cat "$D/especial/tx-longa.txt" >&2; exit 2; }
    fi
    wait $BG || { cat "$SD/gerar.txt" >&2; exit 2; }
    cat "$SD/gerar.txt"
  else
    scripts/kf/gerar.sh "$T" "$DUR" "$SD" | tee "$SD/gerar.txt"
  fi
  rel > "$SD/relogio-fim.json"
  ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
  scripts/kf/drenar.sh 1800 | tee "$SD/drenagem.txt"
  dren=$(sed -n 's/^drenado em \([0-9.]*\) s.*/\1/p' "$SD/drenagem.txt")
  jq -nc --arg dir "$S" --argjson alvo "$T" --arg ini "$ini" --arg fim "$fim" --argjson dren "${dren:-null}" \
    '{dir:$dir,alvo:$alvo,inicio_iso:$ini,fim_iso:$fim,drenagem_s:$dren}' >> "$D/degraus.jsonl"
  scripts/kf/limpar-archive.sh > "$SD/archive.json" || { cat "$SD/archive.json"; exit 2; }
  taxa=$(sed -n 's/.*linhas=[0-9]* transacoes=[0-9]* taxa=\([0-9.]*\) .*/\1/p' "$SD/gerador.log" | tail -1)
  if [[ $CEN == c3 ]] && awk -v t="${taxa:-0}" -v a="$T" 'BEGIN{exit !(t < a*0.98)}'; then echo "GERADOR_SATURADO em $T ev/s (taxa $taxa): escada interrompida"; break; fi
done
# lag do consumer group (end offsets - commitado) e do worker: evidencia do estado final
scripts/kf/lag.sh kf-worker --fim > "$D/lag-final.txt" || true
# eventos dos workers (um CSV por evento) e analise de latencia por degrau
scripts/kf/coletar-eventos.sh "$D/workers" > /dev/null
while read -r linha; do
  S=$(jq -r .dir <<< "$linha"); SD=$D/$S; ini=$(jq -r .inicio_iso <<< "$linha"); fim=$(jq -r .fim_iso <<< "$linha")
  OFF=$(jq -r .offset_ms "$SD/relogio-inicio.json"); NOFF=$(awk -v o="$OFF" 'BEGIN{print -o}')   # relogio(infra-server) - relogio(desenv-server), ms
  RAJ=()
  if [[ $CEN == c4 ]]; then
    rc=$(sed -n '2p' "$D/especial/gerador-linhas.csv" | cut -d, -f1); kmax=$(awk -F, 'NR>1 && $3>m{m=$3} END{print m+0}' "$D/especial/gerador-linhas.csv")
    rl=$(sed -n 's/.*linhas=\([0-9]*\) transacoes=.*/\1/p' "$D/especial/gerar.txt" | tail -1)
    txms=$(sed -n 's/.*transacao_media_ms=\([0-9.]*\).*/\1/p' "$D/especial/gerar.txt" | tail -1)
    RAJ=(--rajada "$rc" "$kmax" "$(awk -v t="${txms:-3000}" 'BEGIN{printf "%.3f", t/1000+1}')")
  fi
  python3 -I scripts/kf/eventos_kf.py "$D/workers" "$ini" "$fim" "$SD/eventos.csv" "${RAJ[@]}" | tee "$SD/eventos.txt"
  if [[ $CEN == c4 || $CEN == c5 ]]; then
    if [[ $CEN == c4 ]]; then
      jq -nc --arg c "$rc" --argjson n "${rl:-0}" --argjson k "${kmax:-0}" --arg i "$ini" --arg f "$fim" --argjson j "${PATAMAR_JANELA_SEG:-60}" \
        '{commit:$c,rajada_linhas:$n,chave_max:$k,ini:$i,fim:$f,patamar_janela_s:$j}' > "$SD/entrada-especial.json"
    else
      jq -nc --arg t0 "$(sed -n 's/.*TX_LONGA t0=\([^ ]*\) t1=.*/\1/p' "$D/especial/tx-longa.txt")" --arg t1 "$(sed -n 's/.*t1=\([^ ]*\).*/\1/p' "$D/especial/tx-longa.txt")" \
        --arg c "$(sed -n 's/.*TX_LONGA commit=\(.*\)/\1/p' "$D/especial/tx-longa.txt")" --arg i "$ini" --arg f "$fim" '{t0:$t0,t1:$t1,commit:$c,ini:$i,fim:$f}' > "$SD/entrada-especial.json"
    fi
    python3 -I scripts/gg/analisar-especial.py $CEN "$SD/eventos.csv" "$SD/entrada-especial.json" "$NOFF" "$SD" > "$SD/especial.txt"
  else
    cut -d, -f1,2 "$SD/eventos.csv" > "$SD/latencias.csv"
    python3 -I scripts/gg/analisar-latencia.py "$SD/latencias.csv" "$ini" "$fim" "$NOFF" > "$SD/latencia.json"; jq -c . "$SD/latencia.json"
  fi
  gzip -f "$SD/eventos.csv"; [[ ! -f $SD/latencias.csv ]] || gzip -f "$SD/latencias.csv"
done < "$D/degraus.jsonl"
# integridade
SREL=${SAIDA#resultados/kf}; SREL=${SREL#/}
integridade_final "$D" "${SREL:+$SREL/}$CEN" "$EXE"
PSHA=$(jq -nc --arg h "$(sha256sum scripts/kf/conector-oracle.json | cut -c1-16)" '{"conector-oracle.json":$h}')
WSHA=$(sha256sum "$WJAR" | cut -d' ' -f1)
RDESC="worker Kafka x$NW (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico)"
jq -n --arg c "$CEN" --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "$RDESC" --argjson d "$DUR" --arg t "$TAXAS" --argjson delay "${DELAY:-null}" --argjson hold "${HOLD:-null}" --argjson rlin "${RLIN:-null}" \
  --argjson ps "$PSHA" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --arg wjar "$WSHA" --argjson nw "$NW" --slurpfile st "$D/degraus.jsonl" \
  '{cenario:$c,execucao:$e,inicio:$i,fim:$f,pipeline:$r,workers:$nw,dur_s:$d,taxa:(if $c=="c3" then 1000 else ($t|tonumber) end),taxas:($t|split(",")|map(tonumber)),params_sha256:$ps,jar_sha256:$jar,worker_jar_sha256:$wjar,degraus:$st,delay_s:$delay,hold_s:$hold,rajada_linhas_param:$rlin}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/kf/meta_kf.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" "$SAIDA/$CEN/meta.json.tmp" && mv "$SAIDA/$CEN/meta.json.tmp" "$SAIDA/$CEN/meta.json"
echo "resultados: $D"
exit $RC
