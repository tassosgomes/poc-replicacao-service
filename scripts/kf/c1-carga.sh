#!/usr/bin/env bash
# C1 (carga inicial) no lab Kafka, no contrato do relatorio. Uso: scripts/kf/c1-carga.sh [rotulo]  (ou scripts/kf/cenario.sh c1)
# Caminho da Fase 1: lab reconstruido do ZERO (derrubar.sh + subir.sh, massa completa 200k/500k/1M) com o conector Debezium (no_data) em streaming
# ANTES da carga, `CargaInicial` do jar da PoC (as mesmas 4 threads do C1 Java) com os workers PARADOS, workers aplicam o topico desde o offset 0,
# drenagem (lag 0), reconciliacao SQL e UUIDs (snapshot apos a carga, workers parados, x snapshot depois da drenagem: o replay do topico nao pode
# trocar id). Se tudo for coerente, o snapshot apos a drenagem vira a referencia de C2..C5 (resultados/kf/c1/snapshots/apos-carga.csv; o anterior
# vira apos-carga-anterior.csv). Tempo de carga = tempo_total_s do jar (como no Java, `carga_inicial.tempo_total_s`); tempo ate drenado = tempo do jar + drenagem
# (drenar.sh, relogio do desenv-server); NAO inclui preparo de imagem (docker cp do jar por ssh, minutos), snapshot de UUIDs nem subida do container do worker (em tempos_s). ATENCAO: derruba e recria os bancos e o broker do lab Kafka (so kf-*).
# Saidas: resultados/kf/c1/<execucao>/ e resultados/kf/c1/meta.json (o antigo, so de snapshots, e mesclado). Exit 0 aprovado, 1 reprovado/invalido, 2 falha.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
ROT=${1:-}
set -a; source scripts/lab-kf.env; set +a
source scripts/kf/comum-cenario.sh
[[ -f $JAR && -f $WJAR ]] || { echo "jar ausente" >&2; exit 2; }
EXE=$(date -u +%Y%m%dT%H%M%SZ)${ROT:+-$ROT}; D=resultados/kf/c1/$EXE; mkdir -p "$D/snapshots"
R=resultados/kf/c1; B=$R/snapshots
INI_RUN=$(date -u +%FT%TZ)
[[ -f $B/apos-carga.csv && ! -f $B/apos-carga-anterior.csv ]] && cp "$B/apos-carga.csv" "$B/apos-carga-anterior.csv"
SNAPS=$(jq -c '.snapshots // {}' $R/meta.json 2>/dev/null || echo '{}')
if ssh_poc desenv-server 'docker inspect kf-legado >/dev/null 2>&1'; then scripts/kf/limpar-archive.sh --verificar > "$D/archive-antes.json" || { cat "$D/archive-antes.json"; exit 2; }
else echo '{"alarme":false,"nota":"lab kf ausente antes do C1 (reconstruido do zero)"}' > "$D/archive-antes.json"; fi
rel > "$D/relogio-inicio.json"
T0=$(agora)
echo "== derrubar lab kf (so kf-*) =="; scripts/kf/derrubar.sh | tee "$D/derrubar.txt"
T1=$(agora)
echo "== subir (massa completa, conector antes da carga, CargaInicial, workers, drenagem, reconciliacao) =="
rm -f $R/carga-inicial.log $R/drenagem.txt $R/reconciliacao-sql.txt $R/subir-tempos.json $R/archive-depois-subir.json $R/snapshot-antes.txt
KF_SNAPSHOT_ANTES=1 KF_WORKERS=1 scripts/kf/subir.sh 2>&1 | tee "$D/subir.txt"
T2=$(agora)
rel > "$D/relogio-fim.json"
cp $R/carga-inicial.log $R/drenagem.txt $R/subir-tempos.json $R/snapshot-antes.txt "$D/"; cp $R/reconciliacao-sql.txt "$D/reconciliacao-subir.txt"
# integridade final (apos a drenagem do subir.sh)
scripts/kf/drenar.sh 600 | tee "$D/drenagem-final.txt"
scripts/kf/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && true || true
contagens legado > "$D/contagens-legado.json"; contagens novo > "$D/contagens-novo.json"
scripts/kf/snapshot-ids.sh c1 apos-carga | tee "$D/snapshot-apos.txt"
cp "$B/antes-workers.csv" "$D/snapshots/antes.csv"; cp "$B/apos-carga.csv" "$D/snapshots/apos.csv"
awk -F, 'NR==FNR{b[$1","$2]=$3; next} ($1","$2) in b {pre++; if (b[$1","$2]!=$3) mud++} END{printf "{\"preexistentes_comparados\":%d,\"uuids_alterados\":%d}\n", pre, mud+0}' "$D/snapshots/antes.csv" "$D/snapshots/apos.csv" > "$D/uuids.json"
scripts/kf/limpar-archive.sh > "$D/archive-depois.json" || { cat "$D/archive-depois.json"; exit 2; }
inventario_containers > "$D/containers.json"
connect_api localhost:18083/connectors/kf-oracle/config | jq -S 'with_entries(select(.key|test("password|secret";"i")|not))' > "$D/conector-config.json"
scripts/kf/lag.sh kf-worker --fim > "$D/lag-final.txt" || true
# carga: linhas e tempos por tabela do jar (tabela=X linhas=N linhas_s=R; tempo_total_s=T)
TT=$(sed -n 's/^tempo_total_s=//p' "$D/carga-inicial.log" | tail -1)
CI=$(awk '/^tabela=/{split($1,a,"=");split($2,b,"=");split($3,c,"="); printf "%s\"%s\":{\"linhas\":%d,\"linhas_s\":%s}", (n++?",":""), a[2], b[2], c[2]}' "$D/carga-inicial.log")
CI=$(jq -nc --argjson t "{$CI}" --argjson tot "${TT:-null}" --argjson rel "$(jq .tempos_s "$D/subir-tempos.json")" '{tabelas:$t,tempo_total_s:$tot,log:"carga-inicial.log",tempo_s_relogio_desenv:$rel.carga_inicial}')
DRENADO=$(grep -q '^drenado em' "$D/drenagem-final.txt" && grep -q '^drenado em' "$D/drenagem.txt" && echo true || echo false)
DREN=$(sed -n 's/^drenado em \([0-9.]*\) s.*/\1/p' "$D/drenagem.txt" | tail -1)
jq -n --argjson dren "${DREN:-null}" --argjson ci "$CI" --argjson cl "$(cat "$D/contagens-legado.json")" --argjson cn "$(cat "$D/contagens-novo.json")" --argjson dr "$DRENADO" \
  --slurpfile st "$D/subir-tempos.json" --argjson derr "$(dur "$T0" "$T1")" --argjson tot "$(dur "$T0" "$T2")" \
  '{carga_inicial:$ci,contagens_legado:$cl,contagens_novo:$cn,contagens_as_of_scn:$cl,drenado:$dr,scn:$st[0].carga.scn_carga,scn_inicial_conector:$st[0].carga.scn_inicial_conector,
    tempo_ate_drenado_s:($ci.tempo_total_s + $dren),drenagem_s:$dren,
    tempos_s:($st[0].tempos_s + {derrubar:$derr,total_com_derrubar:$tot})}' > "$D/carga.json"
jq -n --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --arg r "worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12); Debezium 3.7.1 LogMiner (snapshot.mode=no_data); Kafka 4.3.1 (1 broker, 16 particoes/topico)" \
  --argjson ps "$(jq -nc --arg h "$(sha256sum scripts/kf/conector-oracle.json | cut -c1-16)" '{"conector-oracle.json":$h}')" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" --arg wjar "$(sha256sum "$WJAR" | cut -d' ' -f1)" \
  '{cenario:"c1",execucao:$e,inicio:$i,fim:$f,pipeline:$r,workers:1,dur_s:null,taxa:null,taxas:[],params_sha256:$ps,jar_sha256:$jar,worker_jar_sha256:$wjar,degraus:[]}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/kf/meta_kf.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
SNAPS=$(jq -c --argjson a "$SNAPS" '(.snapshots // {}) as $b | $a + $b' $R/meta.json 2>/dev/null || echo "$SNAPS")
jq --argjson s "$SNAPS" '.snapshots = $s' "$D/meta.json" > "$D/meta.json.tmp" && mv "$D/meta.json.tmp" "$D/meta.json"
cp "$D/meta.json" $R/meta.json.tmp && mv $R/meta.json.tmp $R/meta.json
echo "resultados: $D"
exit $RC
