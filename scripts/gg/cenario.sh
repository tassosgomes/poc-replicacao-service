#!/usr/bin/env bash
# Runner generico dos cenarios de regime/teto no lab GoldenGate. Uso: scripts/gg/cenario.sh c1|c2|c3|c4|c5 [rotulo]
# (atalhos: scripts/gg/c2-regime.sh, c3-teto.sh, c4-rajada.sh, c5-transacao-longa.sh, c1-carga-inicial.sh). Grava em resultados/gg/<cenario>/<execucao>/ e copia o meta.json
# (mesmo contrato do lab Java: criterios/degraus/validade/veredito, ver scripts/gg/meta_gg.py) para resultados/gg/<cenario>/meta.json,
# de modo que `bash scripts/relatorio.sh --resultados resultados/gg` consolide.
# Variaveis: REPLICAT (REPBDAD classico | REPCO coordenado; precisa estar RUNNING e o outro parado), TAXA (c2, 1000), DURACAO_SEG (c2, 600),
# DEGRAU_SEG (c3, 300), C3_TAXAS (500,1000,2000,4000), THREADS, SEMENTE, PCT_INSERT, PCT_DELETE, HOT_PCT, TX_MAX (padroes do C2),
# SNAPSHOT_BASE (resultados/gg/c1/snapshots/apos-carga.csv).
# c4 (rajada): regime TAXA (1000) por DURACAO_SEG (600, no minimo RAJADA_APOS_SEG+OBSERVACAO_RECUPERACAO_SEG+30) e, RAJADA_APOS_SEG (120) s apos o
#   inicio da carga, um segundo gerador (RAJADA_LINHAS=50000: uma UPDATE de 50.000 linhas de TITULAR e um COMMIT). Patamar e recuperacao:
#   scripts/gg/analisar-especial.py (patamar = maximo da latencia por balde de 5 s nos 60 s antes do commit, PATAMAR_JANELA_SEG).
# c5 (transacao longa): regime igual; TX_APOS_SEG (5) s apos o inicio, UPDATE de TITULAR 1 em transacao aberta por TX_ABERTA_SEG (30) s
#   (sql/gg/testes/tx_longa.sql; o modo TX_ABERTA_SEG do gerador Java depende de evento_replicacao, inexistente no lab GG).
# c1 (carga inicial): delega a scripts/gg/c1-carga.sh. c6|c7|c8 delegam a scripts/gg/falhas.sh, c9 a c9-comparacao.sh e c10 a c10-custo.sh.
# GG_SAIDA (resultados/gg): base de saida; o c9 a muda para nao sobrescrever o meta.json do c2/c3 oficial.
# Cada degrau: drena, trunca aplicacao_log, gera, drena, mede latencia (aplicado_em - origem_updated_at, corrigida pelo offset de relogios),
# tendencia do lag. Regra do lab Java: taxa efetiva < 98% do alvo = GERADOR_SATURADO (c3: interrompe a escada, preserva os degraus validos).
# Antes e depois: verificacao de disco/archive (limpar-archive.sh); alarme = falha operacional. Ao fim: drena, reconcilia (SQL e jar Java
# MODO=completo DRY_RUN=true) e confere os UUIDs pre-existentes contra o snapshot de c1.
# Saida: 0 aprovado; 1 reprovado/invalido; 2 falha operacional.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
CEN=${1:?c1|c2|c3|c4|c5|c6|c7|c8|c9|c10}; ROT=${2:-}
[[ $CEN == c1 ]] && exec "$(dirname "$0")/c1-carga.sh" "${@:2}"
case $CEN in c6|c7|c8) exec "$(dirname "$0")/falhas.sh" "$@" ;; c9) exec "$(dirname "$0")/c9-comparacao.sh" "${@:2}" ;; c10) exec "$(dirname "$0")/c10-custo.sh" "${@:2}" ;; esac
case $CEN in c2|c3|c4|c5) ;; *) echo "cenario invalido: $CEN" >&2; exit 2 ;; esac
set -a; source scripts/lab-gg.env; set +a
REP=${REPLICAT:-REPBDAD}
if [[ $CEN == c3 ]]; then TAXAS=${C3_TAXAS:-500,1000,2000,4000}; DUR=${DEGRAU_SEG:-300}; else TAXAS=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}; fi
if [[ $CEN == c4 ]]; then DELAY=${RAJADA_APOS_SEG:-120}; OBS=${OBSERVACAO_RECUPERACAO_SEG:-125}; RLIN=${RAJADA_LINHAS:-50000}; (( DUR < DELAY + OBS + 30 )) && DUR=$((DELAY + OBS + 30)); fi
if [[ $CEN == c5 ]]; then DELAY=${TX_APOS_SEG:-5}; HOLD=${TX_ABERTA_SEG:-30}; (( DUR < DELAY + HOLD + 30 )) && DUR=$((DELAY + HOLD + 30)); fi
SAIDA=${GG_SAIDA:-resultados/gg}
EXE=$(date -u +%Y%m%dT%H%M%SZ)-$REP${ROT:+-$ROT}; D=$SAIDA/$CEN/$EXE; mkdir -p "$D"
BASE=${SNAPSHOT_BASE:-resultados/gg/c1/snapshots/apos-carga.csv}
[[ -s $BASE ]] || { echo "snapshot base ausente: $BASE" >&2; exit 2; }
JAR=poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar; [[ -f $JAR ]] || { echo "jar ausente: $JAR" >&2; exit 2; }
source gg/lib.sh
agora() { ssh -o BatchMode=yes desenv-server 'date +%s.%N'; }
st() { gg GET "/$1/info/status" | jq -r .response.status; }
[[ $(st extracts/EXTBDAD) == running ]] || { echo "EXTBDAD nao esta running" >&2; exit 2; }
[[ $(st replicats/$REP) == running ]] || { echo "$REP nao esta running" >&2; exit 2; }
for o in REPBDAD REPCO; do [[ $o == "$REP" ]] && continue
  s=$(gg GET /replicats/$o/info/status | jq -r '.response.status // "ausente"'); [[ $s != running ]] || { echo "$o tambem esta running: um Replicat por vez" >&2; exit 2; }; done
export REPLICAT=$REP
rel() { python3 scripts/medir-relogios.py | awk '{printf "{\"offset_ms\":%.3f,\"incerteza_ms\":%.3f}", $1/1e6, $2/1e6}'; }
INI_RUN=$(date -u +%FT%TZ)
scripts/gg/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/gg/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
STEPS=()
IFS=, read -ra LISTA <<< "$TAXAS"; n=0
for T in "${LISTA[@]}"; do
  n=$((n+1)); S=d$n-$T; SD=$D/$S; mkdir -p "$SD"
  printf "truncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo >/dev/null
  rel > "$SD/relogio-inicio.json"
  if [[ $CEN == c4 || $CEN == c5 ]]; then
    # regime em segundo plano; a operacao especial parte DELAY s depois do inicio_carga do gerador de fundo (relogio do desenv-server)
    scripts/gg/gerar.sh "$T" "$DUR" "$SD" > "$SD/gerar.txt" 2>&1 & BG=$!
    li=
    for _ in $(seq 1 120); do
      gn=$(ssh -o BatchMode=yes desenv-server "docker ps --filter name=^gg-gerador- --format '{{.Names}}'" | head -1 || true)
      [[ -n $gn ]] && li=$(ssh -o BatchMode=yes desenv-server "docker logs $gn 2>&1" | sed -n 's/.*inicio_carga=//p' | head -1 || true)
      [[ -n $li ]] && break; sleep 1
    done
    [[ -n $li ]] || { echo "gerador de fundo nao iniciou a carga" >&2; kill $BG 2>/dev/null || true; exit 2; }
    ie=$(date -d "$li" +%s.%N); fa=$(awk -v d="$DELAY" -v i="$ie" -v n="$(agora)" 'BEGIN{r=d-(n-i); print (r>0?r:0)}'); sleep "$fa"
    mkdir -p "$D/especial"
    if [[ $CEN == c4 ]]; then
      scripts/gg/gerar.sh "$T" 10 "$D/especial" RAJADA_LINHAS=$RLIN > "$D/especial/gerar.txt" 2>&1 || { cat "$D/especial/gerar.txt" >&2; exit 2; }
    else
      sed "s/&1/$HOLD/g" sql/gg/testes/tx_longa.sql | scripts/gg/q.sh legado > "$D/especial/tx-longa.txt" || { cat "$D/especial/tx-longa.txt" >&2; exit 2; }
    fi
    wait $BG || { cat "$SD/gerar.txt" >&2; exit 2; }
    cat "$SD/gerar.txt"
  else
  scripts/gg/gerar.sh "$T" "$DUR" "$SD" | tee "$SD/gerar.txt"
  fi
  rel > "$SD/relogio-fim.json"
  ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
  scripts/gg/drenar.sh 1800 | tee "$SD/drenagem.txt"
  dren=$(sed -n 's/^drenado em \([0-9]*\) s.*/\1/p' "$SD/drenagem.txt")
  scripts/gg/hb-estagios.sh $(( (DUR + 120) / 60 )) > "$SD/heartbeat-estagios.txt" || true
  OFF=$(jq -r .offset_ms "$SD/relogio-inicio.json")
  NOFF=$(awk -v o="$OFF" 'BEGIN{print -o}')   # relogio(infra-server) - relogio(desenv-server), ms
  if [[ $CEN == c4 || $CEN == c5 ]]; then
    scripts/gg/eventos-janela.sh "$ini" "$fim" "$SD/eventos.csv" | tee "$SD/eventos.txt"
    if [[ $CEN == c4 ]]; then
      rc=$(sed -n '2p' "$D/especial/gerador-linhas.csv" | cut -d, -f1); kmax=$(awk -F, 'NR>1 && $3>m{m=$3} END{print m+0}' "$D/especial/gerador-linhas.csv")
      rl=$(sed -n 's/.*linhas=\([0-9]*\) transacoes=.*/\1/p' "$D/especial/gerar.txt" | tail -1)
      jq -nc --arg c "$rc" --argjson n "${rl:-0}" --argjson k "${kmax:-0}" --arg i "$ini" --arg f "$fim" --argjson j "${PATAMAR_JANELA_SEG:-60}" \
        '{commit:$c,rajada_linhas:$n,chave_max:$k,ini:$i,fim:$f,patamar_janela_s:$j}' > "$SD/entrada-especial.json"
    else
      jq -nc --arg t0 "$(sed -n 's/.*TX_LONGA t0=\([^ ]*\) t1=.*/\1/p' "$D/especial/tx-longa.txt")" --arg t1 "$(sed -n 's/.*t1=\([^ ]*\).*/\1/p' "$D/especial/tx-longa.txt")" \
        --arg c "$(sed -n 's/.*TX_LONGA commit=\(.*\)/\1/p' "$D/especial/tx-longa.txt")" --arg i "$ini" --arg f "$fim" '{t0:$t0,t1:$t1,commit:$c,ini:$i,fim:$f}' > "$SD/entrada-especial.json"
    fi
    python3 -I scripts/gg/analisar-especial.py $CEN "$SD/eventos.csv" "$SD/entrada-especial.json" "$NOFF" "$SD" > "$SD/especial.txt"
    gzip -f "$SD/eventos.csv"
  else
  scripts/gg/latencia-janela.sh "$ini" "$fim" "$SD" "$NOFF" > /dev/null
  fi
  jq -nc --arg dir "$S" --argjson alvo "$T" --arg ini "$ini" --arg fim "$fim" --argjson dren "${dren:-null}" \
    '{dir:$dir,alvo:$alvo,inicio_iso:$ini,fim_iso:$fim,drenagem_s:$dren}' >> "$D/degraus.jsonl"
  scripts/gg/limpar-archive.sh > "$SD/archive.json" || { cat "$SD/archive.json"; exit 2; }
  taxa=$(sed -n 's/.*linhas=[0-9]* transacoes=[0-9]* taxa=\([0-9.]*\) .*/\1/p' "$SD/gerador.log" | tail -1)
  if [[ $CEN == c3 ]] && awk -v t="${taxa:-0}" -v a="$T" 'BEGIN{exit !(t < a*0.98)}'; then echo "GERADOR_SATURADO em $T ev/s (taxa $taxa): escada interrompida"; break; fi
done
# integridade
scripts/gg/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && true || true
LEGADO_URL=jdbc:oracle:thin:@//192.168.0.5:1523/FREEPDB1 NOVO_URL=jdbc:oracle:thin:@//192.168.0.11:1524/FREEPDB1 MODO=completo DRY_RUN=true \
  java -Xmx512m -jar "$JAR" reconciliacao > "$D/reconciliacao-java.log" 2>&1 && true || true
SREL=${SAIDA#resultados/gg}; SREL=${SREL#/}
scripts/gg/snapshot-ids.sh "${SREL:+$SREL/}$CEN/$EXE" apos > "$D/snapshot.txt"
awk -F, 'NR==FNR{b[$1","$2]=$3; next} ($1","$2) in b {pre++; if (b[$1","$2]!=$3) mud++} END{printf "{\"preexistentes_comparados\":%d,\"uuids_alterados\":%d}\n", pre, mud+0}' "$BASE" "$D/snapshots/apos.csv" > "$D/uuids.json"
scripts/gg/limpar-archive.sh > "$D/archive-depois.json" || { cat "$D/archive-depois.json"; exit 2; }
# inventario dos containers do lab (sem credenciais)
for h in desenv-server:gg-legado infra-server:gg-novo infra-server:gg-hub; do
  ssh -o BatchMode=yes "${h%%:*}" "docker inspect --format '{{json .}}' ${h##*:}" | jq -c '{nome:.Name[1:],id:.Id,imagem:.Config.Image,imagem_id:.Image,cpu:.HostConfig.NanoCpus,memoria:.HostConfig.Memory,running:.State.Running,inicio:.State.StartedAt,reinicios:.RestartCount,saudavel:(.State.Health.Status // null)}'
done | jq -s . > "$D/containers.json"
case $REP in REPCO) RDESC="REPCO coordenado (THREADS 4; $(grep -E '^(GROUPTRANSOPS|BATCHSQL|EOFDELAYCSECS)' gg/params/REPCO.prm | tr '\n' ' '))" ;;
  *) RDESC="REPBDAD classico ($(grep -E '^(GROUPTRANSOPS|BATCHSQL|EOFDELAYCSECS)' gg/params/REPBDAD.prm | tr '\n' ' '))" ;; esac
RDESC=${REPLICAT_DESC:-$RDESC}   # c9: descricao do modo (parametros da variante)
PSHA=$(for f in gg/params/*.prm; do jq -nc --arg n "$(basename "$f")" --arg h "$(sha256sum "$f" | cut -c1-16)" '{($n):$h}'; done | jq -s add)
jq -n --arg c "$CEN" --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "$RDESC" --argjson d "$DUR" --arg t "$TAXAS" --argjson delay "${DELAY:-null}" --argjson hold "${HOLD:-null}" --argjson rlin "${RLIN:-null}" \
  --argjson ps "$PSHA" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --slurpfile st "$D/degraus.jsonl" \
  '{cenario:$c,execucao:$e,inicio:$i,fim:$f,replicat:$r,dur_s:$d,taxa:(if $c=="c3" then 1000 else ($t|tonumber) end),taxas:($t|split(",")|map(tonumber)),params_sha256:$ps,jar_sha256:$jar,degraus:$st,delay_s:$delay,hold_s:$hold,rajada_linhas_param:$rlin}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/gg/meta_gg.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" "$SAIDA/$CEN/meta.json.tmp" && mv "$SAIDA/$CEN/meta.json.tmp" "$SAIDA/$CEN/meta.json"
echo "resultados: $D"
exit $RC
