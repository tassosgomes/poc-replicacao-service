#!/usr/bin/env bash
# Runner generico dos cenarios de regime/teto no lab GoldenGate. Uso: scripts/gg/cenario.sh c2|c3 [rotulo]
# (atalhos: scripts/gg/c2-regime.sh, scripts/gg/c3-teto.sh). Grava em resultados/gg/<cenario>/<execucao>/ e copia o meta.json
# (mesmo contrato do lab Java: criterios/degraus/validade/veredito, ver scripts/gg/meta_gg.py) para resultados/gg/<cenario>/meta.json,
# de modo que `bash scripts/relatorio.sh --resultados resultados/gg` consolide.
# Variaveis: REPLICAT (REPBDAD classico | REPCO coordenado; precisa estar RUNNING e o outro parado), TAXA (c2, 1000), DURACAO_SEG (c2, 600),
# DEGRAU_SEG (c3, 300), C3_TAXAS (500,1000,2000,4000), THREADS, SEMENTE, PCT_INSERT, PCT_DELETE, HOT_PCT, TX_MAX (padroes do C2),
# SNAPSHOT_BASE (resultados/gg/c1/snapshots/apos-carga.csv).
# Cada degrau: drena, trunca aplicacao_log, gera, drena, mede latencia (aplicado_em - origem_updated_at, corrigida pelo offset de relogios),
# tendencia do lag. Regra do lab Java: taxa efetiva < 98% do alvo = GERADOR_SATURADO (c3: interrompe a escada, preserva os degraus validos).
# Antes e depois: verificacao de disco/archive (limpar-archive.sh); alarme = falha operacional. Ao fim: drena, reconcilia (SQL e jar Java
# MODO=completo DRY_RUN=true) e confere os UUIDs pre-existentes contra o snapshot de c1.
# Saida: 0 aprovado; 1 reprovado/invalido; 2 falha operacional.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
CEN=${1:?c2|c3}; ROT=${2:-}
case $CEN in c2|c3) ;; *) echo "cenario invalido: $CEN" >&2; exit 2 ;; esac
set -a; source scripts/lab-gg.env; set +a
REP=${REPLICAT:-REPBDAD}
if [[ $CEN == c2 ]]; then TAXAS=${TAXA:-1000}; DUR=${DURACAO_SEG:-600}; else TAXAS=${C3_TAXAS:-500,1000,2000,4000}; DUR=${DEGRAU_SEG:-300}; fi
EXE=$(date -u +%Y%m%dT%H%M%SZ)-$REP${ROT:+-$ROT}; D=resultados/gg/$CEN/$EXE; mkdir -p "$D"
BASE=${SNAPSHOT_BASE:-resultados/gg/c1/snapshots/apos-carga.csv}
[[ -s $BASE ]] || { echo "snapshot base ausente: $BASE" >&2; exit 2; }
JAR=poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar; [[ -f $JAR ]] || { echo "jar ausente: $JAR" >&2; exit 2; }
source gg/lib.sh
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
  scripts/gg/gerar.sh "$T" "$DUR" "$SD" | tee "$SD/gerar.txt"
  rel > "$SD/relogio-fim.json"
  ini=$(sed -n 's/.*inicio_carga=//p' "$SD/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$SD/gerador.log")
  scripts/gg/drenar.sh 1800 | tee "$SD/drenagem.txt"
  dren=$(sed -n 's/^drenado em \([0-9]*\) s.*/\1/p' "$SD/drenagem.txt")
  scripts/gg/hb-estagios.sh $(( (DUR + 120) / 60 )) > "$SD/heartbeat-estagios.txt" || true
  OFF=$(jq -r .offset_ms "$SD/relogio-inicio.json")
  scripts/gg/latencia-janela.sh "$ini" "$fim" "$SD" "$(awk -v o="$OFF" 'BEGIN{print -o}')" > /dev/null
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
scripts/gg/snapshot-ids.sh "$CEN/$EXE" apos > "$D/snapshot.txt"
awk -F, 'NR==FNR{b[$1","$2]=$3; next} ($1","$2) in b {pre++; if (b[$1","$2]!=$3) mud++} END{printf "{\"preexistentes_comparados\":%d,\"uuids_alterados\":%d}\n", pre, mud+0}' "$BASE" "$D/snapshots/apos.csv" > "$D/uuids.json"
scripts/gg/limpar-archive.sh > "$D/archive-depois.json" || { cat "$D/archive-depois.json"; exit 2; }
# inventario dos containers do lab (sem credenciais)
for h in desenv-server:gg-legado infra-server:gg-novo infra-server:gg-hub; do
  ssh -o BatchMode=yes "${h%%:*}" "docker inspect --format '{{json .}}' ${h##*:}" | jq -c '{nome:.Name[1:],id:.Id,imagem:.Config.Image,imagem_id:.Image,cpu:.HostConfig.NanoCpus,memoria:.HostConfig.Memory,running:.State.Running,inicio:.State.StartedAt,reinicios:.RestartCount,saudavel:(.State.Health.Status // null)}'
done | jq -s . > "$D/containers.json"
case $REP in REPCO) RDESC="REPCO coordenado (THREADS 4; $(grep -E '^(GROUPTRANSOPS|BATCHSQL|EOFDELAYCSECS)' gg/params/REPCO.prm | tr '\n' ' '))" ;;
  *) RDESC="REPBDAD classico ($(grep -E '^(GROUPTRANSOPS|BATCHSQL|EOFDELAYCSECS)' gg/params/REPBDAD.prm | tr '\n' ' '))" ;; esac
PSHA=$(for f in gg/params/*.prm; do jq -nc --arg n "$(basename "$f")" --arg h "$(sha256sum "$f" | cut -c1-16)" '{($n):$h}'; done | jq -s add)
jq -n --arg c "$CEN" --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "$RDESC" --argjson d "$DUR" --arg t "$TAXAS" \
  --argjson ps "$PSHA" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --slurpfile st "$D/degraus.jsonl" \
  '{cenario:$c,execucao:$e,inicio:$i,fim:$f,replicat:$r,dur_s:$d,taxa:(if $c=="c2" then ($t|tonumber) else 1000 end),taxas:($t|split(",")|map(tonumber)),params_sha256:$ps,jar_sha256:$jar,degraus:$st}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/gg/meta_gg.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" "resultados/gg/$CEN/meta.json.tmp" && mv "resultados/gg/$CEN/meta.json.tmp" "resultados/gg/$CEN/meta.json"
echo "resultados: $D"
exit $RC
