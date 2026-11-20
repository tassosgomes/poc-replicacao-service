#!/usr/bin/env bash
# C10 custo no legado (POR ULTIMO) no lab GoldenGate. Uso: scripts/gg/c10-custo.sh [rotulo]   (atalho: c10-custo-legado.sh)
# >= C10_REPETICOES (3) pares ALTERNADOS captura ligada/desligada, mesma taxa (1000 ev/s), duracao (DURACAO_SEG, 300), semente, threads e mix,
# so updates (PCT_INSERT=0 PCT_DELETE=0; mix padrao do gerador 40,40,20, TX_MAX padrao 5, HOT_PCT 10, como o C10 Java). Metrica: media de tempo de
# transacao do proprio gerador (`transacao_media_ms` do gerador.log), como o C10 Java. Criterio: <= 15% E <= 5 ms (Q-T3 pendente).
#   ligada    = Extract EXTBDAD RUNNING (e drenado antes da fase, para o catch-up nao entrar na medicao); supplemental logging ALLCOLS ativo
#   desligada = Extract PARADO; FORCE LOGGING e supplemental logging (ADD SCHEMATRANDATA ALLCOLS) CONTINUAM ativos: o custo do supplemental NAO e isolado.
# Variante descritiva (C10_VARIANTE_SUPPLEMENTAL=true, padrao): Extract parado + DELETE SCHEMATRANDATA bdad (supplemental removido), mesma carga; depois
#   ADD SCHEMATRANDATA ... ALLCOLS e Extract religado. Consulta de metricas (com/sem): nao aplicavel (nao ha worker nem evento_replicacao).
# Ao fim: Extract ligado, drena (o Extract retoma pelo redo: nada se perde), reconcilia SQL + jar Java e confere os UUIDs.
# Saida: 0 aprovado; 1 reprovado/invalido; 2 falha operacional. Variaveis: TAXA, DURACAO_SEG, C10_REPETICOES, THREADS, SEMENTE, HOT_PCT.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; trap - ERR; [[ ${EXTRACT_PARADO:-0} == 1 ]] && religar_extract || true; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
ROT=${1:-}
set -a; source scripts/lab-gg.env; set +a
source gg/lib.sh; source scripts/gg/comum-cenario.sh
# Revisao 2026-11-20 (remove a ressalva de aquecimento): fase de AQUECIMENTO descartada (captura ligada, nao entra na media), 600 s por fase (igual ao Java),
# ORDEM alternada balanceada por par (C10_ORDEM, padrao "LD,DL,LD,DL" = 4 pares; L=ligada, D=desligada), amostragem do status do Extract a cada 30 s nas fases
# desligadas (status-extract.csv; qualquer amostra diferente de stopped invalida a execucao). A variante sem supplemental NAO e repetida (padrao false).
TAXA=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}; AQUEC=${C10_AQUECIMENTO_SEG:-300}
ORDEM=${C10_ORDEM:-LD,DL,LD,DL}; IFS=, read -ra PARES <<< "$ORDEM"; REPS=${#PARES[@]}
(( REPS >= 3 )) || { echo "C10 exige >= 3 pares" >&2; exit 2; }
for pr in "${PARES[@]}"; do [[ $pr == LD || $pr == DL ]] || { echo "C10_ORDEM invalida: $pr" >&2; exit 2; }; done
VARSUP=${C10_VARIANTE_SUPPLEMENTAL:-false}
EXE=$(date -u +%Y%m%dT%H%M%SZ)-REPBDAD${ROT:+-$ROT}; D=resultados/gg/c10/$EXE; mkdir -p "$D"
exigir_ambiente
ORIG=OracleGoldenGate.ggorigem
INI_RUN=$(date -u +%FT%TZ)
supp() { # contagem de grupos de supplemental logging nas tabelas de bdad (PDB) e schemas preparados
  printf "set heading off feedback off pagesize 0\nalter session set container=FREEPDB1;\nselect 'grupos_tabela='||count(*) from dba_log_groups where owner='BDAD';\nselect 'schemas_preparados='||count(*) from dba_capture_prepared_schemas where schema_name='BDAD';\nselect 'force_logging='||force_logging from v\$database;\nselect 'supplemental_min='||supplemental_log_data_min from v\$database;\nexit\n" \
    | ssh -o BatchMode=yes desenv-server 'docker exec -i gg-legado sqlplus -s / as sysdba' | tr -d '\r' | grep -E '^(grupos_tabela|schemas_preparados|force_logging|supplemental_min)=' | paste -sd' '
}
supp > "$D/supplemental-inicial.txt"; cat "$D/supplemental-inicial.txt"
scripts/gg/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/gg/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
printf "truncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo >/dev/null
if (( AQUEC > 0 )); then  # aquecimento: mesma carga, captura ligada, descartado (fora do degraus.jsonl, nao entra em nenhuma media)
  echo "=== aquecimento ${AQUEC} s (captura ligada, descartado)"; mkdir -p "$D/aquecimento"
  [[ $(st extracts/EXTBDAD) == running ]] || religar_extract
  AQ_INI=$(date -u +%FT%TZ)
  PCT_INSERT=0 PCT_DELETE=0 scripts/gg/gerar.sh "$TAXA" "$AQUEC" "$D/aquecimento" > "$D/aquecimento/gerar.txt"; cat "$D/aquecimento/gerar.txt"
  scripts/gg/drenar.sh 3600 | tee "$D/aquecimento/drenagem.txt"; printf "truncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo >/dev/null
fi
N=0
fase() { # nome captura(true|false) repeticao [supplemental(true|false)]
  local nome=$1 cap=$2 rep=$3 sup=${4:-true}; N=$((N+1)); local S; S=$(printf 'p%02d-%s' "$N" "$nome"); local SD=$D/$S; mkdir -p "$SD"
  echo "=== fase $N: $nome (repeticao $rep)"
  if [[ $cap == true ]]; then
    [[ $(st extracts/EXTBDAD) == running ]] || religar_extract
    scripts/gg/drenar.sh 3600 | tee "$SD/drenagem-previa.txt"; printf "truncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo >/dev/null
  else
    [[ $(st extracts/EXTBDAD) == running ]] && scripts/gg/drenar.sh 3600 | tee "$SD/drenagem-previa.txt"   # fora da janela medida
    [[ $(st extracts/EXTBDAD) == stopped ]] || parar_extract
    : > "$SD/status-extract.csv"
  fi
  [[ $N == 1 ]] && rel > "$SD/relogio-inicio.json"
  scripts/gg/limpar-archive.sh > "$SD/archive.json" || { cat "$SD/archive.json"; exit 2; }
  echo "extract=$(st extracts/EXTBDAD) replicat=$(st replicats/REPBDAD) $(supp)" | tee "$SD/estado-antes.txt"
  local AMO=
  if [[ $cap == false ]]; then  # amostra o status do Extract (antes, a cada 30 s e depois); fica parado a fase inteira ou a execucao e invalida
    echo "$(date -u +%FT%TZ),$(st extracts/EXTBDAD)" >> "$SD/status-extract.csv"
    ( while :; do sleep 30; echo "$(date -u +%FT%TZ),$(st extracts/EXTBDAD)" >> "$SD/status-extract.csv"; done ) & AMO=$!
  fi
  PCT_INSERT=0 PCT_DELETE=0 scripts/gg/gerar.sh "$TAXA" "$DUR" "$SD" > "$SD/gerar.txt"; cat "$SD/gerar.txt"
  if [[ -n $AMO ]]; then
    kill "$AMO" 2>/dev/null || true; wait "$AMO" 2>/dev/null || true
    echo "$(date -u +%FT%TZ),$(st extracts/EXTBDAD)" >> "$SD/status-extract.csv"
    echo "status do Extract na fase: $(wc -l < "$SD/status-extract.csv") amostras, nao-stopped=$(grep -vc ',stopped$' "$SD/status-extract.csv" || true)"
    [[ $(grep -vc ',stopped$' "$SD/status-extract.csv" || true) == 0 ]] || { echo "Extract NAO ficou parado em $S: execucao invalida" >&2; exit 2; }
  fi
  echo "extract=$(st extracts/EXTBDAD) replicat=$(st replicats/REPBDAD) $(supp)" | tee "$SD/estado-depois.txt"
  local ini fim; ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
  jq -nc --arg dir "$S" --argjson alvo "$TAXA" --arg ini "$ini" --arg fim "$fim" --argjson cap "$cap" --argjson rep "$rep" --arg nome "$nome" --argjson sup "$sup" --argjson pos "$N" \
    '{dir:$dir,alvo:$alvo,inicio_iso:$ini,fim_iso:$fim,drenagem_s:null,captura:$cap,repeticao:$rep,modo:$nome,supplemental:$sup,posicao:$pos}' >> "$D/degraus.jsonl"
}
for r in $(seq 1 "$REPS"); do
  for m in $(echo "${PARES[r-1]}" | fold -w1); do
    if [[ $m == L ]]; then fase ligada true "$r"; else fase desligada false "$r"; religar_extract; fi   # religa apos cada desligada; a drenagem ocorre fora da janela
  done
done
if [[ $VARSUP == true ]]; then
  # Variante DESCRITIVA. Sem supplemental logging o redo gerado nao e aplicavel pelo Replicat (OGG-30426, chave ausente no trail; ver
  # docs/spike-goldengate-notas.md). Por isso: (1) drena antes (nada pendente); (2) Extract parado + DELETE SCHEMATRANDATA; (3) fase de carga; (4) REPBDAD
  # PARADO antes de religar o Extract (o Extract le o redo da janela e grava o trail, que o Replicat nao consegue aplicar); (5) ADD SCHEMATRANDATA ALLCOLS;
  # (6) com o Extract em dia, reposiciona o REPBDAD no fim do trail (descarta so o trecho da janela) e (7) repara as chaves da janela com um UPDATE de
  # "toque" no legado (reparar-por-toque.sh), que o Extract captura inteiro (ALLCOLS). Consistencia: nada fica divergente (reconciliacao ao final).
  echo "=== variante: Extract parado e supplemental logging removido (DELETE SCHEMATRANDATA)"
  religar_extract; scripts/gg/drenar.sh 3600 | tee "$D/drenagem-antes-variante.txt"
  parar_extract
  gg POST /connections/$ORIG/trandata/schema '{"operation":"delete","schemaName":"bdad"}' | ggmsg | tee "$D/delete-schematrandata.txt"
  supp | tee "$D/supplemental-removido.txt"
  grep -q 'schemas_preparados=0' "$D/supplemental-removido.txt" || { echo "supplemental nao foi removido" >&2; gg POST /connections/$ORIG/trandata/schema '{"operation":"add","schemaName":"bdad","allColumns":true}' | ggmsg; exit 2; }
  fase desligada-sem-supplemental false 1 false
  VS=$D/$(jq -r 'select(.supplemental==false).dir' "$D/degraus.jsonl")
  gg POST /commands/execute '{"name":"stop","processName":"REPBDAD"}' | ggmsg
  for _ in $(seq 1 60); do [[ $(st replicats/REPBDAD) == stopped ]] && break; sleep 1; done
  gg POST /connections/$ORIG/trandata/schema '{"operation":"add","schemaName":"bdad","allColumns":true}' | ggmsg | tee "$D/add-schematrandata.txt"
  supp | tee "$D/supplemental-restaurado.txt"
  diff <(sed 's/ force_logging.*//' "$D/supplemental-inicial.txt") <(sed 's/ force_logging.*//' "$D/supplemental-restaurado.txt") > /dev/null || { echo "supplemental nao restaurado igual ao inicial" >&2; exit 2; }
  religar_extract; T_EXT=$(agora_infra)
  ok=0
  for _ in $(seq 1 900); do l=$(gg GET /extracts/EXTBDAD/info/status | jq -r '.response.lag // 999'); if (( l <= 2 )); then ok=$((ok+1)); else ok=0; fi; (( ok >= 3 )) && break; sleep 4; done
  (( ok >= 3 )) || { echo "Extract nao alcancou o fim do redo" >&2; exit 2; }
  echo "Extract em dia $(awk -v a="$T_EXT" -v b="$(agora_infra)" 'BEGIN{printf "%.0f", b-a}') s apos religar" | tee "$D/variante-extract-em-dia.txt"
  read -r SEQ SIZ < <(ssh -o BatchMode=yes infra-server 'docker exec gg-hub sh -c "f=\$(ls /u02/Deployment/var/lib/data/ea0* | sort | tail -1); echo \${f##*/ea} \$(stat -c %s \$f)"')
  SEQ=$((10#$SEQ)); echo "fim do trail: seq $SEQ rba $SIZ" | tee "$D/variante-reposicionamento.txt"
  gg/adminclient.sh "ALTER REPLICAT REPBDAD, EXTSEQNO $SEQ, EXTRBA $SIZ" 2>&1 | grep -E "OGG-|ERROR|altered" | tee -a "$D/variante-reposicionamento.txt" || true
  gg POST /commands/execute '{"name":"start","processName":"REPBDAD"}' | ggmsg
  for _ in $(seq 1 60); do [[ $(st replicats/REPBDAD) == running ]] && break; sleep 1; done
  [[ $(st replicats/REPBDAD) == running ]] || { echo "REPBDAD nao subiu apos o reposicionamento" >&2; exit 2; }
  scripts/gg/reparar-por-toque.sh "$VS/gerador-linhas.csv" 2>&1 | tee "$D/variante-reparo-toque.txt"
fi
religar_extract
rel > "$D/relogio-fim.json"; cp "$D/relogio-fim.json" "$(ls -d "$D"/p*-* | tail -1)/relogio-fim.json"
scripts/gg/drenar.sh 3600 | tee "$D/drenagem-final.txt"
[[ $(st extracts/EXTBDAD) == running && $(st replicats/REPBDAD) == running ]]
scripts/gg/limpar-archive.sh > "$D/archive-pos.json" || { cat "$D/archive-pos.json"; exit 2; }
scripts/gg/verificar-archive-necessario.sh > "$D/archive-necessario-final.json" || true
echo "extract=$(st extracts/EXTBDAD) replicat=$(st replicats/REPBDAD) $(supp)" | tee "$D/estado-final.txt"
gg GET /extracts/EXTBDAD | jq -c '.response.managedProcessSettings' > "$D/autostart-extract.json"; gg GET /replicats/REPBDAD | jq -c '.response.managedProcessSettings' > "$D/autostart-replicat.json"
integridade_final "$D" c10 "$EXE"
jq -n --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "$(RDESC_OFICIAL)" --argjson d "$DUR" --argjson t "$TAXA" --argjson reps "$REPS" --arg vs "$VARSUP" --argjson aq "$AQUEC" --arg ordem "$ORDEM" --arg aqini "${AQ_INI:-}" \
  --argjson ps "$(psha)" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --slurpfile st "$D/degraus.jsonl" \
  '{cenario:"c10",execucao:$e,inicio:$i,fim:$f,replicat:$r,dur_s:$d,taxa:$t,taxas:[$t],repeticoes:$reps,aquecimento:{duracao_s:$aq,captura:true,descartado:true,inicio:$aqini,dir:"aquecimento"},ordem:$ordem,variante_supplemental:($vs=="true"),params_sha256:$ps,jar_sha256:$jar,degraus:$st}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/gg/meta_gg.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" resultados/gg/c10/meta.json.tmp && mv resultados/gg/c10/meta.json.tmp resultados/gg/c10/meta.json
echo "resultados: $D"
exit $RC
