#!/usr/bin/env bash
# C1 carga inicial no lab GoldenGate, no contrato do relatorio. Uso: scripts/gg/c1-carga-inicial.sh [rotulo]  (ou cenario.sh c1)
# Reproduz a carga por SCN com o pipeline em pe: para e recria o REPBDAD (gg/criar-pipeline.sh recriar-replicat), trunca SO as 3 tabelas de
# negocio (e aplicacao_log) do gg-novo, executa scripts/gg/carga-inicial.sh (INSERT AS OF SCN via DB link, UUIDv7 por trigger), tira o snapshot
# "antes", inicia o Replicat com START AFTERCSN <SCN> (gg/criar-pipeline.sh replicat), drena, reconcilia (SQL + jar Java MODO=completo
# DRY_RUN=true), confere contagens, UUIDs (antes x depois: o replay do trail nao pode trocar id) e, se tudo for coerente, grava o snapshot de referencia
# resultados/gg/c1/snapshots/apos-carga.csv (o anterior vira apos-carga-anterior.csv) usado por c2..c5.
# Saidas: resultados/gg/c1/<execucao>/ e resultados/gg/c1/meta.json (contrato do relatorio; o meta antigo do subir fica em meta-spike.json).
# Exit 0 aprovado, 1 reprovado/invalido, 2 falha operacional. Relogios: scripts/medir-relogios.py; duracoes: relogio do desenv-server (agora).
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
ROT=${1:-}
set -a; source scripts/lab-gg.env; set +a
source scripts/gg/comum-gg.sh
source gg/lib.sh
JAR=poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar; [[ -f $JAR ]] || { echo "jar ausente" >&2; exit 2; }
EXE=$(date -u +%Y%m%dT%H%M%SZ)-REPBDAD${ROT:+-$ROT}; D=resultados/gg/c1/$EXE; mkdir -p "$D/snapshots"
st() { gg GET "/$1/info/status" | jq -r '.response.status // "ausente"'; }
[[ $(st extracts/EXTBDAD) == running ]] || { echo "EXTBDAD nao esta running" >&2; exit 2; }
[[ $(st replicats/REPBDAD) == running ]] || { echo "REPBDAD nao esta running" >&2; exit 2; }
[[ $(st replicats/REPCO) != running ]] || { echo "REPCO esta running" >&2; exit 2; }
rel() { python3 scripts/medir-relogios.py | awk '{printf "{\"offset_ms\":%.3f,\"incerteza_ms\":%.3f}", $1/1e6, $2/1e6}'; }
cnt() { # tabelas do banco $1 (legado|novo) -> JSON {"TITULAR":n,...}; $2 = SCN opcional (AS OF SCN no legado, via sysdba)
  local sfx=; [[ -n ${2:-} ]] && sfx=" as of scn $2"
  local q="" t
  if [[ -n ${2:-} ]]; then
    for t in titular obra_musical fonograma; do q+="select '$t='||count(*) from bdad.$t$sfx;"$'\n'; done
    printf "alter session set container=FREEPDB1;\nset heading off feedback off pagesize 0\n%sexit\n" "$q" | ssh -o BatchMode=yes desenv-server 'docker exec -i gg-legado sqlplus -s / as sysdba'
  else
    for t in titular obra_musical fonograma; do q+="select '$t='||count(*) from $t;"$'\n'; done
    printf "set heading off feedback off pagesize 0\n%sexit\n" "$q" | scripts/gg/q.sh "$1"
  fi | tr -d '\r ' | grep = | awk -F= '{printf "%s\"%s\":%s", (NR>1?",":"{"), toupper($1), $2} END{print "}"}'
}
INI_RUN=$(date -u +%FT%TZ)
scripts/gg/limpar-archive.sh > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
scripts/gg/drenar.sh 1800 | tee "$D/drenagem-inicial.txt"
rel > "$D/relogio-inicio.json"
T0=$(agora)
# 1. Replicat parado/recriado e tabelas truncadas (as 48 particoes nao existem aqui: so as 3 tabelas e o log de aplicacao)
gg/criar-pipeline.sh recriar-replicat | tee "$D/recriar-replicat.txt"
[[ $(st replicats/REPBDAD) != running ]] || { echo "REPBDAD ainda running apos recriar" >&2; exit 2; }
printf "truncate table titular;\ntruncate table obra_musical;\ntruncate table fonograma;\ntruncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo > "$D/truncate.txt"
cnt novo > "$D/contagens-apos-truncate.json"
T1=$(agora)
# 2. Carga por SCN (SCN obtido no legado com o Extract ja ativo; precisa ser >= SCN de registro do Extract)
CSN_EXT=$(gg GET /extracts/EXTBDAD | jq -r '.response.registration.csn')
SCN=$(scripts/gg/carga-inicial.sh 2> "$D/carga-inicial.log")
T2=$(agora)
[[ $SCN =~ ^[0-9]+$ ]] && (( SCN >= CSN_EXT )) || { echo "SCN da carga ($SCN) invalido ou anterior ao registro do Extract ($CSN_EXT)" >&2; exit 2; }
cnt legado "$SCN" > "$D/contagens-as-of-scn.json"
scripts/gg/snapshot-ids.sh "c1/$EXE" antes | tee "$D/snapshot-antes.txt"
# 3. Replicat AFTERCSN
T3=$(agora)
gg/criar-pipeline.sh replicat "$SCN" | tee "$D/iniciar-replicat.txt"
for _ in $(seq 1 120); do [[ $(st replicats/REPBDAD) == running ]] && break; sleep 1; done
[[ $(st replicats/REPBDAD) == running ]] || { echo "REPBDAD nao ficou running" >&2; exit 2; }
T4=$(agora)
# drenagem: o Replicat recem-iniciado le o trail desde o inicio ate o SCN; so conta como drenado com a posicao no fim do trail (lag baixo)
# e o aplicacao_log estavel por 30 s
# sonda: uma transacao nova no legado so aparece no aplicacao_log depois que o Replicat percorreu o trail ate o fim (prova que alcancou)
printf "update titular set excluido = 0 where codigo = 1;\ncommit;\nexit\n" | scripts/gg/q.sh legado > /dev/null
fimsonda=$(( $(date +%s) + 3600 ))
until [[ $(printf "set heading off feedback off pagesize 0\nselect count(*) from aplicacao_log where tabela='TITULAR' and codigo=1;\nexit\n" | scripts/gg/q.sh novo | tr -d ' \r\n') -ge 1 ]]; do
  (( $(date +%s) > fimsonda )) && { echo "Replicat nao alcancou a sonda em 3600 s" >&2; exit 2; }; sleep 3; done
echo "sonda aplicada em $(dur "$T4" "$(agora)") s apos o REPBDAD running"
ESTAVEL_SEG=30 scripts/gg/drenar.sh 3600 | tee "$D/drenagem.txt"
T5=$(agora)
rel > "$D/relogio-fim.json"
# 4. Integridade
scripts/gg/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && true || true
LEGADO_URL=jdbc:oracle:thin:@//192.168.0.5:1523/FREEPDB1 NOVO_URL=jdbc:oracle:thin:@//192.168.0.11:1524/FREEPDB1 MODO=completo DRY_RUN=true \
  java -Xmx512m -jar "$JAR" reconciliacao > "$D/reconciliacao-java.log" 2>&1 && true || true
cnt legado > "$D/contagens-legado.json"; cnt novo > "$D/contagens-novo.json"
# 5. Snapshot de referencia (depois da drenagem) e UUIDs antes x depois
B=resultados/gg/c1/snapshots
[[ -f $B/apos-carga.csv && ! -f $B/apos-carga-anterior.csv ]] && cp "$B/apos-carga.csv" "$B/apos-carga-anterior.csv"
M=resultados/gg/c1/meta.json
if [[ -s $M ]] && ! jq -e .criterios "$M" >/dev/null 2>&1 && [[ ! -f resultados/gg/c1/meta-spike.json ]]; then cp "$M" resultados/gg/c1/meta-spike.json; fi
scripts/gg/snapshot-ids.sh c1 apos-carga | tee "$D/snapshot-apos.txt"
cp "$B/apos-carga.csv" "$D/snapshots/apos.csv"
SNAPS=$(jq -c '.snapshots // {}' "$M" 2>/dev/null || echo '{}')
awk -F, 'NR==FNR{b[$1","$2]=$3; next} ($1","$2) in b {pre++; if (b[$1","$2]!=$3) mud++} END{printf "{\"preexistentes_comparados\":%d,\"uuids_alterados\":%d}\n", pre, mud+0}' "$D/snapshots/antes.csv" "$D/snapshots/apos.csv" > "$D/uuids.json"
scripts/gg/limpar-archive.sh > "$D/archive-depois.json" || { cat "$D/archive-depois.json"; exit 2; }
for h in desenv-server:gg-legado infra-server:gg-novo infra-server:gg-hub; do
  ssh -o BatchMode=yes "${h%%:*}" "docker inspect --format '{{json .}}' ${h##*:}" | jq -c '{nome:.Name[1:],id:.Id,imagem:.Config.Image,imagem_id:.Image,cpu:.HostConfig.NanoCpus,memoria:.HostConfig.Memory,running:.State.Running,inicio:.State.StartedAt,reinicios:.RestartCount,saudavel:(.State.Health.Status // null)}'
done | jq -s . > "$D/containers.json"
# 6. Tempos por tabela (set timing do sqlplus: "N rows created." seguido de "Elapsed: HH:MM:SS.cc")
TAB=$(awk 'match($0,/^[0-9]+ rows? created/){n=$1} /^Elapsed: /{ if(n!=""){split($2,t,":"); s=t[1]*3600+t[2]*60+t[3]; printf "%s%d,%.2f", (c++?";":""), n, s; n=""} }' "$D/carga-inicial.log")
IFS=';' read -ra LIN <<< "$TAB"; [[ ${#LIN[@]} -eq 3 ]] || { echo "tempos da carga nao encontrados em carga-inicial.log" >&2; exit 2; }
PSHA=$(for f in gg/params/*.prm; do jq -nc --arg n "$(basename "$f")" --arg h "$(sha256sum "$f" | cut -c1-16)" '{($n):$h}'; done | jq -s add)
tt() { awk -v n="${LIN[$1]%,*}" -v s="${LIN[$1]#*,}" 'BEGIN{printf "{\"linhas\":%d,\"segundos\":%s,\"linhas_s\":%.2f}", n, s, n/s}'; }
CI=$(jq -nc --argjson t "$(tt 0)" --argjson o "$(tt 1)" --argjson f "$(tt 2)" --argjson tot "$(dur "$T1" "$T2")" --arg log "carga-inicial.log" \
  '{tabelas:{TITULAR:$t,OBRA_MUSICAL:$o,FONOGRAMA:$f},tempo_total_s:$tot,log:$log}')
DRENADO=$(grep -q '^drenado em' "$D/drenagem.txt" && echo true || echo false)
jq -n --argjson ci "$CI" --argjson cl "$(cat "$D/contagens-legado.json")" --argjson cn "$(cat "$D/contagens-novo.json")" --argjson cs "$(cat "$D/contagens-as-of-scn.json")" \
  --argjson ct "$(cat "$D/contagens-apos-truncate.json")" --argjson dr "$DRENADO" --arg scn "$SCN" --argjson ate "$(dur "$T0" "$T5")" \
  --argjson tempos "$(jq -n --argjson a "$(dur "$T0" "$T1")" --argjson b "$(dur "$T1" "$T2")" --argjson c "$(dur "$T3" "$T4")" --argjson d "$(dur "$T4" "$T5")" '{recriar_replicat_e_truncate:$a,carga:$b,start_replicat_ate_running:$c,drenagem_do_replicat:$d}')" \
  '{carga_inicial:$ci,contagens_legado:$cl,contagens_novo:$cn,contagens_as_of_scn:$cs,contagens_apos_truncate:$ct,drenado:$dr,scn:($scn|tonumber),tempo_ate_drenado_s:$ate,tempos_s:$tempos}' > "$D/carga.json"
jq -n --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "REPBDAD classico ($(grep -E '^(GROUPTRANSOPS|BATCHSQL|EOFDELAYCSECS)' gg/params/REPBDAD.prm | tr '\n' ' '))" \
  --argjson ps "$PSHA" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" \
  '{cenario:"c1",execucao:$e,inicio:$i,fim:$f,replicat:$r,dur_s:null,taxa:null,taxas:[],params_sha256:$ps,jar_sha256:$jar,degraus:[]}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/gg/meta_gg.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
jq --argjson s "$SNAPS" '.snapshots = $s' "$D/meta.json" > "$D/meta.json.tmp" && mv "$D/meta.json.tmp" "$D/meta.json"
cp "$D/meta.json" resultados/gg/c1/meta.json.tmp && mv resultados/gg/c1/meta.json.tmp resultados/gg/c1/meta.json
echo "resultados: $D"
exit $RC
