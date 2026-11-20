#!/usr/bin/env bash
# C9 (descritivo, SEM_CRITERIO) no lab GoldenGate: compara variantes de parametros do Replicat CLASSICO (nunca o coordenado: THREADRANGE pode
# dividir transacao de origem) em C2 (TAXA 1000 ev/s por DURACAO_SEG 600) e um degrau de 2000 ev/s (DEGRAU_SEG 300), via scripts/gg/cenario.sh
# (cada execucao com a reconciliacao e os UUIDs do runner). Modos (MODOS, padrao os tres):
#   oficial        gg/params/REPBDAD.prm        EOFDELAYCSECS 1, GROUPTRANSOPS 100, BATCHSQL
#   sem-batchsql   gg/params/variantes/REPBDAD.sem-batchsql.prm   igual, sem BATCHSQL
#   g1000          gg/params/variantes/REPBDAD.g1000.prm          igual ao oficial com GROUPTRANSOPS 1000 (padrao do GG)
# As execucoes ficam em resultados/gg/c9/runs/<c2|c3>/<execucao> (nao sobrescrevem o meta.json oficial do C2/C3). Ao final (e em qualquer saida)
# volta aos parametros oficiais, confirma Replicat RUNNING e autostart/autorestart, e gera comparacao.csv e meta.json (scripts/gg/meta_c9.py).
# Uso: scripts/gg/c9-comparacao.sh [rotulo]      Saida: 0 (descritivo, vereditos de integridade) / 1 / 2 falha operacional.
set -Eeuo pipefail
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
set -a; source scripts/lab-gg.env; set +a
source gg/lib.sh; source scripts/gg/comum-cenario.sh
MODOS=${MODOS:-oficial sem-batchsql g1000}; DUR1=${DURACAO_SEG:-600}; DEG=${DEGRAU_SEG:-300}; T2=${C9_TAXA_ALTA:-2000}
EXE=$(date -u +%Y%m%dT%H%M%SZ)-REPBDAD${1:+-$1}; D=resultados/gg/c9/$EXE; mkdir -p "$D"; RUNS=$D/runs
prm_do_modo() { case $1 in oficial) echo gg/params/REPBDAD.prm ;; sem-batchsql) echo gg/params/variantes/REPBDAD.sem-batchsql.prm ;; g1000) echo gg/params/variantes/REPBDAD.g1000.prm ;; *) echo "modo invalido: $1" >&2; return 1 ;; esac; }
restaurar() {
  trap - ERR EXIT
  local atual; atual=$(gg GET /replicats/REPBDAD/info/status | jq -r '.response.status // "ausente"')
  gg/aplicar-params.sh REPBDAD gg/params/REPBDAD.prm >> "$D/restauracao.txt" 2>&1 || echo "falha ao restaurar os parametros oficiais" >&2
  gg GET /replicats/REPBDAD | jq -c '{status:.response.status, managedProcessSettings:.response.managedProcessSettings, config:.response.config}' > "$D/restauracao-estado.json" 2>/dev/null || true
}
trap restaurar EXIT
exigir_ambiente
INI_RUN=$(date -u +%FT%TZ)
: > "$D/modos.jsonl"
for M in $MODOS; do
  F=$(prm_do_modo "$M")
  echo "=== modo $M ($F)"
  gg/aplicar-params.sh REPBDAD "$F" | tee -a "$D/aplicar-params.txt"
  scripts/gg/drenar.sh 1800 | tee -a "$D/drenagem-modos.txt"
  DESC="REPBDAD classico, modo $M ($(grep -E '^(GROUPTRANSOPS|BATCHSQL|EOFDELAYCSECS)' "$F" | tr '\n' ' '))"
  mkdir -p "$RUNS"
  R2=0; R3=0
  GG_SAIDA=$RUNS REPLICAT_DESC="$DESC" TAXA=1000 DURACAO_SEG=$DUR1 scripts/gg/cenario.sh c2 "$M" > "$D/run-$M-c2.log" 2>&1 || R2=$?
  E2=$(basename "$(ls -1dt "$RUNS"/c2/*-"$M" | head -1)")
  GG_SAIDA=$RUNS REPLICAT_DESC="$DESC" C3_TAXAS=$T2 DEGRAU_SEG=$DEG scripts/gg/cenario.sh c3 "$M" > "$D/run-$M-c3.log" 2>&1 || R3=$?
  E3=$(basename "$(ls -1dt "$RUNS"/c3/*-"$M" | head -1)")
  [[ $R2 -le 1 && $R3 -le 1 ]] || { echo "falha operacional no modo $M (c2 rc=$R2, c3 rc=$R3): ver $D/run-$M-*.log" >&2; exit 2; }
  jq -nc --arg m "$M" --arg f "$F" --arg sha "$(sha256sum "$F" | cut -c1-16)" --arg d "$DESC" --arg e2 "runs/c2/$E2" --arg e3 "runs/c3/$E3" --argjson r2 "$R2" --argjson r3 "$R3" \
    '{modo:$m,params:$f,params_sha256:$sha,descricao:$d,c2:$e2,c3:$e3,rc_c2:$r2,rc_c3:$r3}' >> "$D/modos.jsonl"
  echo "modo $M: c2 rc=$R2 ($E2), c3 rc=$R3 ($E3)"
done
restaurar
trap 'rc=$?; echo "falha operacional (linha $LINENO, rc=$rc)" >&2; exit 2' ERR
trap - EXIT
scripts/gg/drenar.sh 1800 | tee "$D/drenagem-final.txt"
jq -n --arg e "$EXE" --arg i "$INI_RUN" --arg f "$(date -u +%FT%TZ)" --argjson d "$DUR1" --argjson g "$DEG" --argjson t2 "$T2" --argjson ps "$(psha)" --arg jar "$(sha256sum "$JAR" | cut -d' ' -f1)" \
  '{cenario:"c9",execucao:$e,inicio:$i,fim:$f,dur_s:$d,degrau_s:$g,taxa_alta:$t2,params_sha256:$ps,jar_sha256:$jar}' > "$D/cenario.json"
trap - ERR
RC=0; python3 -I scripts/gg/meta_c9.py "$D" | tee "$D/veredito.json" || RC=$?
[[ -s $D/meta.json ]] || exit 2
cp "$D/meta.json" resultados/gg/c9/meta.json.tmp && mv resultados/gg/c9/meta.json.tmp resultados/gg/c9/meta.json
echo "resultados: $D"
exit $RC
