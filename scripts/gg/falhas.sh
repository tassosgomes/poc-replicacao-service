#!/usr/bin/env bash
# C6 (queda), C7 (failover adaptado) e C8 (captura parada) no lab GoldenGate. Uso: scripts/gg/falhas.sh c6|c7|c8 [rotulo]
# (atalhos: c6-queda.sh, c7-failover.sh, c8-captura-parada.sh; cenario.sh c6|c7|c8 delega aqui). Grava resultados/gg/<cenario>/<execucao>/ e copia o
# meta.json (contrato do lab Java, ver scripts/gg/meta_gg.py) para resultados/gg/<cenario>/meta.json.
# Regime de TAXA (1000 ev/s) por DURACAO_SEG (600); relogios dos hosts (nao o do WSL2) para todos os instantes: os eventos de falha sao
# executados no infra-server (hub-evento.sh) e a latencia e a do banco gg-novo (mesmo host).
# c6: `docker kill -s KILL gg-hub` QUEDA_APOS_SEG (180) s apos o inicio da carga e `docker start gg-hub` REINICIO_APOS_SEG (240) s; a carga continua
#   ate DURACAO_SEG (>= reinicio + 45 s + folga). Mede o tempo ate o primeiro balde de 5 s com lag <= 5 s, desde a queda e desde o reinicio.
#   VARIANTE=replicat: kill -9 so do processo REPBDAD (autorestart), em QUEDA_APOS_SEG; descritivo (nao promove meta.json).
# c7 (ADAPTADO): `docker restart gg-hub` em FALHA1_APOS_SEG (120) s e `docker kill`+`docker start` em FALHA2_APOS_SEG (330) s; mede o tempo ate
#   EXTBDAD e REPBDAD RUNNING e lag normal (<= 5 s) sob carga, alvo de referencia <= 20 s. Nao ha segunda replica no Free: HA de producao (XAG/RAC) fora.
# c8: drena, PARA o Extract, gera updates so em OBRA_MUSICAL por TRIGGER_DESLIGADO_SEG (60) s (MIX=0,100,0, sem insert/delete), confere (so leitura)
#   as chaves divergentes e o archive, religa o Extract e mede: 100% das chaves afetadas corrigidas so pelo redo, sem reconciliacao/reparo.
# Saida: 0 aprovado; 1 reprovado/invalido; 2 falha operacional.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; trap - ERR; [[ ${EXTRACT_PARADO:-0} == 1 ]] && religar_extract || true; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
CEN=${1:?c6|c7|c8}; ROT=${2:-}
case $CEN in c6|c7|c8) ;; *) echo "cenario invalido: $CEN" >&2; exit 2 ;; esac
set -a; source scripts/lab-gg.env; set +a
source gg/lib.sh; source scripts/gg/comum-cenario.sh
TAXA=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}
VARIANTE=${VARIANTE:-hub}
case $CEN in
  c6) QUEDA=${QUEDA_APOS_SEG:-180}; REINI=${REINICIO_APOS_SEG:-240}; POS=${OBSERVACAO_APOS_REINICIO_SEG:-45}
      if [[ $VARIANTE == replicat ]]; then REINI=$QUEDA; ROT=${ROT:-variante-replicat}; fi
      (( DUR < REINI + POS + 30 )) && DUR=$((REINI + POS + 30)) ;;
  c7) F1=${FALHA1_APOS_SEG:-120}; F2=${FALHA2_APOS_SEG:-330}; (( DUR < F2 + 120 )) && DUR=$((F2 + 120)) ;;
  c8) DUR=${TRIGGER_DESLIGADO_SEG:-60} ;;
esac
EXE=$(date -u +%Y%m%dT%H%M%SZ)-REPBDAD${ROT:+-$ROT}; D=resultados/gg/$CEN/$EXE; mkdir -p "$D"
exigir_ambiente
INI_RUN=$(date -u +%FT%TZ)
scripts/gg/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/gg/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
S=d1-$TAXA; SD=$D/$S; mkdir -p "$SD" "$D/especial"
printf "truncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo >/dev/null
rel > "$SD/relogio-inicio.json"
OFF=$(jq -r .offset_ms "$SD/relogio-inicio.json"); NOFF=$(awk -v o="$OFF" 'BEGIN{print -o}')   # infra - desenv (ms)
NOFFS=$(awk -v o="$NOFF" 'BEGIN{printf "%.6f", o/1000}')
divergentes() { scripts/gg/q.sh novo < sql/gg/novo/04_divergentes_obra.sql | tr -d '\r' | grep -E '^DIV,[0-9]+$' | cut -d, -f2 | sort -n || true; }
if [[ $CEN == c8 ]]; then
  # ---- C8: Extract parado, updates so em OBRA_MUSICAL, religa sem nenhum reparo ----
  scripts/gg/verificar-archive-necessario.sh > "$D/especial/archive-necessario-antes-parar.json" || true
  parar_extract; T_PARA=$(agora_infra); echo "EXTBDAD parado em $T_PARA (infra)"
  T_PARA_ISO=$(date -u -d "@${T_PARA%.*}" +%FT%TZ)
  capt=$(printf "set heading off feedback off pagesize 0\nalter session set container=FREEPDB1;\nselect 'status='||status||' required='||required_checkpoint_scn from dba_capture;\nexit\n" | ssh -o BatchMode=yes desenv-server 'docker exec -i gg-legado sqlplus -s / as sysdba' | tr -d '\r' | grep '^status=' || true)
  echo "dba_capture com o Extract parado: $capt" | tee "$D/especial/dba-capture-parado.txt"
  PCT_INSERT=0 PCT_DELETE=0 scripts/gg/gerar.sh "$TAXA" "$DUR" "$SD" MIX=0,100,0 | tee "$SD/gerar.txt"
  rel > "$SD/relogio-fim.json"
  # linhas afetadas = chaves distintas de OBRA_MUSICAL no CSV do gerador
  awk -F, 'NR>1{t[$2]++; if($2=="OBRA_MUSICAL") k[$3]=1} END{for(x in k) print x; for(x in t) print "TABELA," x "," t[x] > "/dev/stderr"}' "$SD/gerador-linhas.csv" 2> "$D/especial/tabelas-do-gerador.txt" | sort -n > "$D/especial/linhas-afetadas.csv"
  AFET=$(wc -l < "$D/especial/linhas-afetadas.csv"); echo "chaves afetadas distintas: $AFET; tabelas no CSV do gerador: $(tr '\n' ' ' < "$D/especial/tabelas-do-gerador.txt")"
  # divergencias ANTES (so leitura): chaves que diferem entre legado e destino; e reconciliacao por faixa (informativa)
  divergentes > "$D/especial/divergentes-antes.csv"
  DIV_ANTES=$(wc -l < "$D/especial/divergentes-antes.csv")
  CONFERE=false; cmp -s "$D/especial/divergentes-antes.csv" "$D/especial/linhas-afetadas.csv" && CONFERE=true
  echo "divergentes antes de religar: $DIV_ANTES (afetadas $AFET; conjuntos iguais: $CONFERE)"
  scripts/gg/reconciliar.sh > "$D/especial/reconciliacao-antes.txt" 2>&1 && true || true
  # archive: nada necessario pode ser apagado (verifica antes e depois de rodar o limpar-archive com o Extract parado)
  scripts/gg/verificar-archive-necessario.sh > "$D/especial/archive-necessario-antes-limpar.json" || { cat "$D/especial/archive-necessario-antes-limpar.json"; exit 2; }
  scripts/gg/limpar-archive.sh > "$D/especial/limpar-archive-parado.json" || { cat "$D/especial/limpar-archive-parado.json"; exit 2; }
  scripts/gg/verificar-archive-necessario.sh > "$D/especial/archive-necessario-depois-limpar.json" || { cat "$D/especial/archive-necessario-depois-limpar.json"; exit 2; }
  T_RELIGA_ISO=$(date -u +%FT%TZ); T_RELIGA0=$(agora_infra)
  religar_extract; T_RELIGA1=$(agora_infra)
  scripts/gg/drenar.sh 1800 | tee "$SD/drenagem.txt"
  T_DREN=$(agora_infra)
  ULT=$(printf "set heading off feedback off pagesize 0\nalter session set nls_numeric_characters='.,';\nselect trim(to_char((trunc(max(aplicado_em))-date '1970-01-01')*86400+extract(hour from max(aplicado_em))*3600+extract(minute from max(aplicado_em))*60+extract(second from max(aplicado_em)),'FM9999999999.000000')) from aplicacao_log;\nexit\n" | scripts/gg/q.sh novo | tr -d '\r\n ' | grep -oE '[0-9]+\.[0-9]+' | head -1 || true)
  divergentes > "$D/especial/divergentes-depois.csv"
  DIV_DEPOIS=$(wc -l < "$D/especial/divergentes-depois.csv")
  scripts/gg/verificar-archive-necessario.sh > "$D/especial/archive-necessario-depois-religar.json" || true
  echo "divergentes depois da drenagem: $DIV_DEPOIS"
  # chaves afetadas presentes no aplicacao_log (aplicadas pelo Replicat a partir do redo)
  printf "set heading off feedback off pagesize 0 linesize 200 trimspool on\nselect 'AP,'||codigo from (select distinct codigo from aplicacao_log where tabela='OBRA_MUSICAL');\nexit\n" | scripts/gg/q.sh novo | tr -d '\r' | grep -E '^AP,[0-9]+$' | cut -d, -f2 | sort -n > "$D/especial/aplicadas-no-log.csv" || true
  COBRE=false; [[ -z $(comm -23 "$D/especial/linhas-afetadas.csv" "$D/especial/aplicadas-no-log.csv") ]] && COBRE=true
  jq -n --argjson afet "$AFET" --argjson antes "$DIV_ANTES" --argjson depois "$DIV_DEPOIS" --argjson confere "$CONFERE" --argjson cobre "$COBRE" \
    --arg para "$T_PARA_ISO" --arg religa "$T_RELIGA_ISO" --argjson tp "$T_PARA" --argjson r0 "$T_RELIGA0" --argjson r1 "$T_RELIGA1" --argjson td "$T_DREN" --argjson ult "${ULT:-null}" --arg capt "$capt" \
    '{linhas_afetadas:$afet, divergentes_antes:$antes, afetadas_conferem_antes:$confere, divergentes_depois:$depois, linhas_corrigidas:($afet-$depois), log_cobre_afetadas:$cobre,
      captura_parada_inicio:$para, captura_parada_fim:$religa, parada_s:($r0-$tp), religar_extract_s:($r1-$r0), tempo_ate_drenado_s:($td-$r1),
      ultima_aplicacao_apos_religar_s:(if $ult==null then null else ($ult-$r1) end), dba_capture_parado:$capt,
      reparo_ou_reconciliacao_antes_de_drenar:false}' > "$SD/c8.json"
  cat "$SD/c8.json"
  ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
else
  # ---- C6 / C7: regime com falha no hub ----
  scripts/gg/gerar.sh "$TAXA" "$DUR" "$SD" > "$SD/gerar.txt" 2>&1 & BG=$!
  aguardar_inicio_carga || { kill $BG 2>/dev/null || true; exit 2; }
  IE=$(epoch_iso "$LI"); IEI=$(awk -v i="$IE" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')   # inicio da carga no relogio do infra-server
  alvo() { awk -v i="$IEI" -v s="$1" 'BEGIN{printf "%.3f", i+s}'; }
  if [[ $CEN == c6 && $VARIANTE == hub ]]; then
    scripts/gg/hub-evento.sh kill "$(alvo "$QUEDA")" > "$D/especial/evento-kill.json"
    scripts/gg/hub-evento.sh start "$(alvo "$REINI")" > "$D/especial/evento-start.json"
    jq -nc --slurpfile k "$D/especial/evento-kill.json" --slurpfile s "$D/especial/evento-start.json" \
      '[{nome:"kill_hub_e_start",t_queda:$k[0].t_cmd0,t_reinicio:$s[0].t_cmd1,t_rest:$s[0].t_rest,t_ext:$s[0].t_ext,t_rep:$s[0].t_rep,t_kill_ret:$k[0].t_cmd1,t_start_cmd0:$s[0].t_cmd0}]' > "$D/especial/eventos.json"
  elif [[ $CEN == c6 ]]; then
    scripts/gg/hub-evento.sh replicat "$(alvo "$QUEDA")" > "$D/especial/evento-replicat.json"
    jq -nc --slurpfile k "$D/especial/evento-replicat.json" \
      '[{nome:"kill_processo_replicat",t_queda:$k[0].t_cmd0,t_reinicio:$k[0].t_rep,t_rest:$k[0].t_rest,t_ext:$k[0].t_ext,t_rep:$k[0].t_rep,t_kill_ret:$k[0].t_cmd1}]' > "$D/especial/eventos.json"
  else
    scripts/gg/hub-evento.sh restart "$(alvo "$F1")" > "$D/especial/evento-restart.json"
    scripts/gg/hub-evento.sh kill-start "$(alvo "$F2")" > "$D/especial/evento-kill-start.json"
    jq -nc --slurpfile a "$D/especial/evento-restart.json" --slurpfile b "$D/especial/evento-kill-start.json" \
      '[{nome:"docker_restart",t_queda:$a[0].t_cmd0,t_reinicio:$a[0].t_cmd1,t_rest:$a[0].t_rest,t_ext:$a[0].t_ext,t_rep:$a[0].t_rep},
        {nome:"docker_kill_start",t_queda:$b[0].t_cmd0,t_reinicio:$b[0].t_cmd1,t_rest:$b[0].t_rest,t_ext:$b[0].t_ext,t_rep:$b[0].t_rep,t_kill_ret:$b[0].t_kill}]' > "$D/especial/eventos.json"
  fi
  cat "$D/especial/eventos.json"
  wait $BG || { cat "$SD/gerar.txt" >&2; exit 2; }
  cat "$SD/gerar.txt"
  rel > "$SD/relogio-fim.json"
  ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
  scripts/gg/drenar.sh 1800 | tee "$SD/drenagem.txt"
fi
dren=$(sed -n 's/^drenado em \([0-9]*\) s.*/\1/p' "$SD/drenagem.txt")
scripts/gg/hb-estagios.sh $(( (DUR + 120) / 60 )) > "$SD/heartbeat-estagios.txt" || true
scripts/gg/latencia-janela.sh "$ini" "$fim" "$SD" "$NOFF" > /dev/null
if [[ $CEN != c8 ]]; then
  jq -nc --argjson ini "$(awk -v i="$(epoch_iso "$ini")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')" --argjson fim "$(awk -v i="$(epoch_iso "$fim")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')" \
    --slurpfile e "$D/especial/eventos.json" '{ini:$ini,fim_carga:$fim,eventos:$e[0]}' > "$SD/entrada-queda.json"
  python3 -I scripts/gg/analisar-queda.py "$SD/latencias.csv" "$SD/entrada-queda.json" "$NOFF" "$SD" | tee "$SD/queda.txt"
  scripts/gg/comparar-eventos.sh "$SD" > "$SD/comparacao-eventos.txt"
  jq -c '{eventos_gerador,linhas_log,chaves_com_menos_no_log,eventos_faltantes_total,chaves_com_mais_no_log,eventos_a_mais_total,sem_perda}' "$SD/comparacao-eventos.json"
fi
jq -nc --arg dir "$S" --argjson alvo "$TAXA" --arg ini "$ini" --arg fim "$fim" --argjson dren "${dren:-null}" '{dir:$dir,alvo:$alvo,inicio_iso:$ini,fim_iso:$fim,drenagem_s:$dren}' >> "$D/degraus.jsonl"
scripts/gg/limpar-archive.sh > "$SD/archive.json" || { cat "$SD/archive.json"; exit 2; }
integridade_final "$D" "$CEN" "$EXE"
jq -n --arg c "$CEN" --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "$(RDESC_OFICIAL)" --argjson d "$DUR" --argjson t "$TAXA" --arg var "$VARIANTE" \
  --argjson q "${QUEDA:-null}" --argjson re "${REINI:-null}" --argjson f1 "${F1:-null}" --argjson f2 "${F2:-null}" --argjson pos "${POS:-null}" \
  --argjson ps "$(psha)" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --slurpfile st "$D/degraus.jsonl" \
  '{cenario:$c,execucao:$e,inicio:$i,fim:$f,replicat:$r,dur_s:$d,taxa:$t,taxas:[$t],variante:$var,queda_apos_s:$q,reinicio_apos_s:$re,falha1_apos_s:$f1,falha2_apos_s:$f2,observacao_apos_reinicio_s:$pos,params_sha256:$ps,jar_sha256:$jar,degraus:$st}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/gg/meta_gg.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
if [[ $CEN == c6 && $VARIANTE == replicat ]]; then
  cp "$D/meta.json" "resultados/gg/c6/variante-replicat.meta.json"   # descritivo: nao promove o meta.json principal
else
  cp "$D/meta.json" "resultados/gg/$CEN/meta.json.tmp" && mv "resultados/gg/$CEN/meta.json.tmp" "resultados/gg/$CEN/meta.json"
fi
echo "resultados: $D"
exit $RC
