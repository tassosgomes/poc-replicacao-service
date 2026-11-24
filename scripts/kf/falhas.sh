#!/usr/bin/env bash
# C6 (queda do worker), C7 (dois workers e failover) e C8 (conector parado) no lab Kafka. Uso: scripts/kf/falhas.sh c6|c7|c8 [rotulo]
# (atalhos c6-queda-worker.sh, c7-failover.sh, c8-captura-parada.sh; cenario.sh c6..c8 delega aqui). Grava em resultados/kf/<cenario>/<execucao>/ e copia o meta.json
# (contrato do lab Java/GG, scripts/kf/meta_kf.py) para resultados/kf/<cenario>/meta.json. KF_SAIDA muda a base (variantes nao promovem o meta oficial).
# Regime TAXA (1000 ev/s) por DURACAO_SEG (600); instantes de falha no relogio do infra-server (mesmo host do kf-novo e dos workers). Workers com EVENTOS_CSV.
# c6: `docker kill -s KILL kf-worker-1` QUEDA_APOS_SEG (180) s apos o inicio da carga e novo worker (kf-worker-2, outro id: o CSV de eventos do worker e CREATE_NEW)
#   REINICIO_APOS_SEG (240) s; observa ate >= 45 s depois. Mede o tempo ate o primeiro balde de 5 s completo com lag <= 5 s, desde a queda e desde o reinicio.
# c7 (como o Java: 2 workers): kf-worker-2 sobe SEGUNDA_APOS_SEG (60) s apos o inicio; espera a divisao 24/24 em lease_particao; QUEDA_APOS_SEG (180) s apos o inicio mata o
#   kf-worker-1 (SIGKILL); failover_s = da queda ate o kf-worker-2 possuir os 48 leases validos E registrar uma nova marca `all 48 owned partitions active` (relogio do host do
#   banco; limite 20 s). LEASE_SEG (12, padrao do subir-worker e da decisao V-07 do lab Java) via KF_WORKER_ENV="LEASE_SEG=n".
# c8: drena, PARA o conector (PUT /connectors/kf-oracle/stop; o offset fica no Connect), updates so em OBRA_MUSICAL por TRIGGER_DESLIGADO_SEG (60) s (MIX=0,100,0, sem insert/delete),
#   forca TROCAS_REDO (3) trocas de redo log para gerar archive necessario, confere as chaves divergentes (so leitura) e o archive necessario, limpa archive, RELIGA o conector
#   e mede: 100% das chaves corrigidas so pelo redo, sem reconciliacao/reparo.
# Saida: 0 aprovado; 1 reprovado/invalido; 2 falha operacional.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; trap - ERR; [[ ${CONECTOR_PARADO:-0} == 1 ]] && conector_religar || true; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
CEN=${1:?c6|c7|c8}; ROT=${2:-}
case $CEN in c6|c7|c8) ;; *) echo "cenario invalido: $CEN" >&2; exit 2 ;; esac
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
# Fase 4: no modo transacional (KF_WORKER_ENV="MODO=transacional") ha um unico lease global; o segundo worker fica em espera e o failover e a tomada desse lease
# (em vez de 24/24 e 48 leases por particao). A marca `all 48 owned partitions active` e a mesma nos dois modos.
TXMODE=0; [[ ${KF_WORKER_ENV:-} == *MODO=transacional* ]] && TXMODE=1
if (( TXMODE )); then
  leases() { printf "set heading off feedback off pagesize 0\nselect dono||','||count(*) from lease_global where dono is not null and expira_em>sys_extract_utc(systimestamp) group by dono;\nexit\n" | kfq novo | tr -d '\r' | grep -E '^[^,]+,[0-9]+$' || true; }
  DIVISAO_ESPERADA="kf-worker-1,1 "
else
  DIVISAO_ESPERADA="kf-worker-1,24 kf-worker-2,24 "
fi
TAXA=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}; NW=1
case $CEN in
  c6) QUEDA=${QUEDA_APOS_SEG:-180}; REINI=${REINICIO_APOS_SEG:-240}; POS=${OBSERVACAO_APOS_REINICIO_SEG:-45}; (( DUR < REINI + POS + 30 )) && DUR=$((REINI + POS + 30)) ;;
  c7) SEG=${SEGUNDA_APOS_SEG:-60}; QUEDA=${QUEDA_APOS_SEG:-180}; (( DUR < QUEDA + 45 )) && DUR=$((QUEDA + 45)) ;;
  c8) DUR=${TRIGGER_DESLIGADO_SEG:-60}; TROCAS=${TROCAS_REDO:-3} ;;
esac
SAIDA=${KF_SAIDA:-resultados/kf}
EXE=$(date -u +%Y%m%dT%H%M%SZ)${ROT:+-$ROT}; D=$SAIDA/$CEN/$EXE; mkdir -p "$D"
exigir_ambiente
INI_RUN=$(date -u +%FT%TZ)
scripts/kf/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/kf/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
scripts/kf/worker-fresco.sh "$NW" | tee "$D/worker-inicio.txt"
scripts/kf/drenar.sh 1800 | tee -a "$D/drenagem-inicial.txt"
S=d1-$TAXA; SD=$D/$S; mkdir -p "$SD" "$D/especial"
rel > "$SD/relogio-inicio.json"
OFF=$(jq -r .offset_ms "$SD/relogio-inicio.json"); NOFF=$(awk -v o="$OFF" 'BEGIN{print -o}'); NOFFS=$(awk -v o="$NOFF" 'BEGIN{printf "%.6f", o/1000}')
read -r _ FIM0 < <(scripts/kf/lag.sh kf-worker --fim)
if [[ $CEN == c8 ]]; then
  # ---- C8: conector parado, updates so em OBRA_MUSICAL, religa sem nenhum reparo ----
  scripts/kf/verificar-archive-necessario.sh > "$D/especial/archive-necessario-antes-parar.json" || true
  CONECTOR_PARADO=1; conector_parar; T_PARA=$(agora_infra); T_PARA_ISO=$(date -u +%FT%TZ); echo "conector parado em $T_PARA (infra): $(conector_estado)"
  conector_estado > "$D/especial/estado-conector-parado.txt"
  PCT_INSERT=0 PCT_DELETE=0 scripts/kf/gerar.sh "$TAXA" "$DUR" "$SD" MIX=0,100,0 | tee "$SD/gerar.txt"
  rel > "$SD/relogio-fim.json"
  for i in $(seq 1 "$TROCAS"); do printf "alter system switch logfile;\nexit\n" | ssh_poc desenv-server 'docker exec -i kf-legado sqlplus -s / as sysdba' > /dev/null; sleep 3; done
  printf "alter system archive log current;\nexit\n" | ssh_poc desenv-server 'docker exec -i kf-legado sqlplus -s / as sysdba' > /dev/null
  awk -F, 'NR>1{t[$2]++; if($2=="OBRA_MUSICAL") k[$3]=1} END{for(x in k) print x; for(x in t) print "TABELA," x "," t[x] > "/dev/stderr"}' "$SD/gerador-linhas.csv" 2> "$D/especial/tabelas-do-gerador.txt" | sort -n > "$D/especial/linhas-afetadas.csv"
  AFET=$(wc -l < "$D/especial/linhas-afetadas.csv"); echo "chaves afetadas distintas: $AFET; tabelas no CSV do gerador: $(tr '\n' ' ' < "$D/especial/tabelas-do-gerador.txt")"
  scripts/kf/divergentes-obra.sh | cut -d, -f2 | sort -n > "$D/especial/divergentes-antes.csv"
  DIV_ANTES=$(wc -l < "$D/especial/divergentes-antes.csv")
  CONFERE=false; cmp -s "$D/especial/divergentes-antes.csv" "$D/especial/linhas-afetadas.csv" && CONFERE=true
  echo "divergentes antes de religar: $DIV_ANTES (afetadas $AFET; conjuntos iguais: $CONFERE)"
  scripts/kf/reconciliar.sh > "$D/especial/reconciliacao-antes.txt" 2>&1 && true || true
  scripts/kf/verificar-archive-necessario.sh > "$D/especial/archive-necessario-antes-limpar.json" || { cat "$D/especial/archive-necessario-antes-limpar.json"; exit 2; }
  scripts/kf/limpar-archive.sh > "$D/especial/limpar-archive-parado.json" || { cat "$D/especial/limpar-archive-parado.json"; exit 2; }
  scripts/kf/verificar-archive-necessario.sh > "$D/especial/archive-necessario-depois-limpar.json" || { cat "$D/especial/archive-necessario-depois-limpar.json"; exit 2; }
  T_RELIGA_ISO=$(date -u +%FT%TZ); T_RELIGA0=$(agora_infra)
  conector_religar; CONECTOR_PARADO=0; T_RELIGA1=$(agora_infra)
  scripts/kf/drenar.sh 1800 | tee "$SD/drenagem.txt"
  T_DREN=$(agora_infra)
  scripts/kf/divergentes-obra.sh | cut -d, -f2 | sort -n > "$D/especial/divergentes-depois.csv"
  DIV_DEPOIS=$(wc -l < "$D/especial/divergentes-depois.csv")
  scripts/kf/verificar-archive-necessario.sh > "$D/especial/archive-necessario-depois-religar.json" || true
  echo "divergentes depois da drenagem: $DIV_DEPOIS"
  read -r _ FIM1 < <(scripts/kf/lag.sh kf-worker --fim)
  # ultima aplicacao no destino (replicado_em) e eventos aplicados a partir do redo (end offsets do topico)
  ULT=$(printf "set heading off feedback off pagesize 0\nalter session set nls_numeric_characters='.,';\nselect trim(to_char((trunc(max(replicado_em))-date '1970-01-01')*86400+extract(hour from max(replicado_em))*3600+extract(minute from max(replicado_em))*60+extract(second from max(replicado_em)),'FM9999999999.000000')) from obra_musical;\nexit\n" | scripts/kf/q.sh novo | tr -d '\r ' | grep -E '^[0-9.]+$' | head -1 || true)
  jq -n --argjson afet "$AFET" --argjson antes "$DIV_ANTES" --argjson depois "$DIV_DEPOIS" --argjson confere "$CONFERE" --argjson f0 "$FIM0" --argjson f1 "$FIM1" \
    --arg para "$T_PARA_ISO" --arg religa "$T_RELIGA_ISO" --argjson tp "$T_PARA" --argjson r0 "$T_RELIGA0" --argjson r1 "$T_RELIGA1" --argjson td "$T_DREN" --argjson ult "${ULT:-null}" --argjson trocas "$TROCAS" \
    '{linhas_afetadas:$afet, divergentes_antes:$antes, afetadas_conferem_antes:$confere, divergentes_depois:$depois, linhas_corrigidas:($afet-$depois), log_cobre_afetadas:($depois==0),
      eventos_no_topico_pos_parada:($f1-$f0), trocas_de_redo_forcadas:$trocas,
      captura_parada_inicio:$para, captura_parada_fim:$religa, parada_s:($r0-$tp), religar_conector_s:($r1-$r0), tempo_ate_drenado_s:($td-$r1),
      ultima_aplicacao_apos_religar_s:(if $ult==null then null else ($ult-$r1) end), reparo_ou_reconciliacao_antes_de_drenar:false}' > "$SD/c8.json"
  cat "$SD/c8.json"
  ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
else
  # ---- C6 / C7: regime com falha de worker ----
  scripts/kf/gerar.sh "$TAXA" "$DUR" "$SD" > "$SD/gerar.txt" 2>&1 & BG=$!
  aguardar_inicio_carga || { kill $BG 2>/dev/null || true; exit 2; }
  IEI=$(awk -v i="$(epoch_iso "$LI")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')   # inicio da carga no relogio do infra-server
  alvo() { awk -v i="$IEI" -v s="$1" 'BEGIN{printf "%.3f", i+s}'; }
  marca() { ssh_poc infra-server "docker logs $1 2>&1 | grep -c 'owned partitions active'" || true; }
  if [[ $CEN == c6 ]]; then
    remoto_em "$(alvo "$QUEDA")" "docker kill -s KILL kf-worker-1" > "$D/especial/evento-kill.json"
    ssh_poc infra-server "docker inspect -f '{{.State.ExitCode}}' kf-worker-1" > "$D/especial/exit-worker-1.txt"
    KF_ESPERAR_ATE=$(alvo "$REINI") KF_TEMPOS_ARQ="$D/especial/evento-start.txt" scripts/kf/subir-worker.sh 2 EVENTOS_CSV=true ${KF_WORKER_ENV:-} > /dev/null
    TREP=null
    for _ in $(seq 1 240); do
      if [[ $(marca kf-worker-2) -ge 1 ]]; then TREP=$(agora_infra); break; fi; sleep 0.5; done
    read -r S0 S1 < "$D/especial/evento-start.txt"
    jq -nc --slurpfile k "$D/especial/evento-kill.json" --argjson s0 "$S0" --argjson s1 "$S1" --argjson rep "$TREP" \
      '[{nome:"kill_worker_e_novo_worker",t_queda:$k[0].t0,t_reinicio:$s1,t_rest:null,t_ext:null,t_rep:$rep,t_kill_ret:$k[0].t1,t_start_cmd0:$s0}]' > "$D/especial/eventos.json"
  else
    KF_ESPERAR_ATE=$(alvo "$SEG") scripts/kf/subir-worker.sh 2 EVENTOS_CSV=true ${KF_WORKER_ENV:-} > /dev/null
    for _ in $(seq 1 180); do
      L=$(leases | sort | tr '\n' ' '); [[ $L == "$DIVISAO_ESPERADA" ]] && break; sleep 1; done
    [[ $L == "$DIVISAO_ESPERADA" ]] || { echo "workers nao dividiram 24/24 ($L)" >&2; kill $BG 2>/dev/null || true; exit 2; }
    echo "divisao: $L" | tee "$D/especial/divisao.txt"
    PREV=$(marca kf-worker-2)
    # queda do kf-worker-1 e polling da tomada (leases do kf-worker-2 = 48 validos e nova marca), no relogio do infra-server
    { printf 'ALVO=%q\nPREV=%q\nTXMODE=%q\n' "$(alvo "$QUEDA")" "$PREV" "$TXMODE"; cat <<'REMOTO'
agora() { date +%s.%N; }
while :; do n=$(agora); awk -v n="$n" -v t="$ALVO" 'BEGIN{exit !(n>=t)}' && break; sleep 0.02; done
t0=$(agora); docker kill -s KILL kf-worker-1 >/dev/null; t1=$(agora)
tt=null; for i in $(seq 1 600); do
  TAB=lease_particao; NEED=48; [ "$TXMODE" = 1 ] && { TAB=lease_global; NEED=1; }
  c=$(printf "set heading off feedback off pagesize 0\nselect count(*) from $TAB where dono='kf-worker-2' and expira_em>sys_extract_utc(systimestamp);\nexit\n" | docker exec -i kf-novo sqlplus -s replica/replica@localhost:1521/FREEPDB1 | tr -d ' \t\r\n')
  m=$(docker logs kf-worker-2 2>&1 | grep -c 'owned partitions active')
  if [ "$c" = "$NEED" ] && [ "$m" -gt "$PREV" ]; then tt=$(agora); break; fi
  sleep 0.2
done
printf '{"t0":%s,"t1":%s,"t_tomada":%s,"leases":%s,"marcas":%s}\n' "$t0" "$t1" "$tt" "${c:-null}" "${m:-null}"
REMOTO
    } | ssh_poc infra-server bash -s > "$D/especial/evento-failover.json"
    ssh_poc infra-server "docker inspect -f '{{.State.ExitCode}}' kf-worker-1" > "$D/especial/exit-worker-1.txt"
    jq -c . "$D/especial/evento-failover.json"
    jq -nc --slurpfile k "$D/especial/evento-failover.json" \
      '[{nome:"kill_worker_1",t_queda:$k[0].t0,t_reinicio:$k[0].t1,t_rest:null,t_ext:null,t_rep:$k[0].t_tomada,t_kill_ret:$k[0].t1}]' > "$D/especial/eventos.json"
  fi
  cat "$D/especial/eventos.json"
  wait $BG || { cat "$SD/gerar.txt" >&2; exit 2; }
  cat "$SD/gerar.txt"
  rel > "$SD/relogio-fim.json"
  ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
  scripts/kf/drenar.sh 1800 | tee "$SD/drenagem.txt"
  read -r _ FIM1 < <(scripts/kf/lag.sh kf-worker --fim)
fi
dren=$(sed -n 's/^drenado em \([0-9.]*\) s.*/\1/p' "$SD/drenagem.txt")
scripts/kf/lag.sh kf-worker --fim > "$D/lag-final.txt" || true
scripts/kf/coletar-eventos.sh "$D/workers" > /dev/null
if [[ $CEN != c8 ]]; then
  python3 -I scripts/kf/eventos_kf.py "$D/workers" "$ini" "$fim" "$SD/eventos-bruto.csv" > "$SD/eventos-bruto.json"
  python3 -I scripts/kf/eventos_kf.py "$D/workers" "$ini" "$fim" "$SD/eventos.csv" --dedupe | tee "$SD/eventos.txt" > "$SD/eventos-dedup.json"
  cut -d, -f1,2 "$SD/eventos.csv" > "$SD/latencias.csv"
  python3 -I scripts/gg/analisar-latencia.py "$SD/latencias.csv" "$ini" "$fim" "$NOFF" > "$SD/latencia.json"; jq -c '{n,p50_s,p95_s,max_s}' "$SD/latencia.json"
  jq -nc --argjson ini "$(awk -v i="$(epoch_iso "$ini")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')" --argjson fim "$(awk -v i="$(epoch_iso "$fim")" -v o="$NOFFS" 'BEGIN{printf "%.3f", i+o}')" \
    --slurpfile e "$D/especial/eventos.json" '{ini:$ini,fim_carga:$fim,eventos:$e[0]}' > "$SD/entrada-queda.json"
  python3 -I scripts/gg/analisar-queda.py "$SD/latencias.csv" "$SD/entrada-queda.json" "$NOFF" "$SD" | tee "$SD/queda.txt"
  comparar_eventos "$SD" "$FIM0" "$FIM1"
  jq -c . "$SD/comparacao-eventos.json"
  gzip -f "$SD/eventos.csv" "$SD/eventos-bruto.csv" "$SD/latencias.csv"
fi
jq -nc --arg dir "$S" --argjson alvo "$TAXA" --arg ini "$ini" --arg fim "$fim" --argjson dren "${dren:-null}" '{dir:$dir,alvo:$alvo,inicio_iso:$ini,fim_iso:$fim,drenagem_s:$dren}' >> "$D/degraus.jsonl"
scripts/kf/limpar-archive.sh > "$SD/archive.json" || { cat "$SD/archive.json"; exit 2; }
SREL=${SAIDA#resultados/kf}; SREL=${SREL#/}
integridade_final "$D" "${SREL:+$SREL/}$CEN" "$EXE"
LEASE=$(grep -o 'LEASE_SEG=[0-9]*' <<< "${KF_WORKER_ENV:-} LEASE_SEG=12" | head -1 | cut -d= -f2)
RDESC="worker Kafka (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG $LEASE, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico)"
jq -n --arg c "$CEN" --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "$RDESC" --argjson d "$DUR" --argjson t "$TAXA" \
  --argjson q "${QUEDA:-null}" --argjson re "${REINI:-null}" --argjson seg "${SEG:-null}" --argjson pos "${POS:-null}" --argjson lease "$LEASE" --argjson nw "$([[ $CEN == c7 ]] && echo 2 || echo 1)" \
  --argjson ps "$(jq -nc --arg h "$(sha256sum scripts/kf/conector-oracle.json | cut -c1-16)" '{"conector-oracle.json":$h}')" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --arg wjar "$(sha256sum "$WJAR" | cut -d' ' -f1)" --slurpfile st "$D/degraus.jsonl" \
  '{cenario:$c,execucao:$e,inicio:$i,fim:$f,pipeline:$r,workers:$nw,dur_s:$d,taxa:$t,taxas:[$t],queda_apos_s:$q,reinicio_apos_s:$re,segunda_apos_s:$seg,observacao_apos_reinicio_s:$pos,lease_seg:$lease,params_sha256:$ps,jar_sha256:$jar,worker_jar_sha256:$wjar,degraus:$st}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/kf/meta_kf.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" "$SAIDA/$CEN/meta.json.tmp" && mv "$SAIDA/$CEN/meta.json.tmp" "$SAIDA/$CEN/meta.json"
echo "resultados: $D"
exit $RC
