#!/usr/bin/env bash
# Funcoes comuns dos cenarios C6 a C10 do lab GoldenGate (sourced por falhas.sh, c9-comparacao.sh e c10-custo.sh). Exige: cwd = raiz do repo,
# scripts/lab-gg.env carregado e gg/lib.sh carregado (funcao gg).
agora() { ssh -o BatchMode=yes desenv-server 'date +%s.%N'; }
agora_infra() { ssh -o BatchMode=yes infra-server 'date +%s.%N'; }
st() { gg GET "/$1/info/status" | jq -r '.response.status // "ausente"'; }
rel() { python3 scripts/medir-relogios.py | awk '{printf "{\"offset_ms\":%.3f,\"incerteza_ms\":%.3f}", $1/1e6, $2/1e6}'; }
BASE=${SNAPSHOT_BASE:-resultados/gg/c1/snapshots/apos-carga.csv}
JAR=poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar
exigir_ambiente() {
  [[ -s $BASE ]] || { echo "snapshot base ausente: $BASE" >&2; exit 2; }
  [[ -f $JAR ]] || { echo "jar ausente: $JAR" >&2; exit 2; }
  [[ $(st extracts/EXTBDAD) == running ]] || { echo "EXTBDAD nao esta running" >&2; exit 2; }
  [[ $(st replicats/REPBDAD) == running ]] || { echo "REPBDAD nao esta running" >&2; exit 2; }
  [[ $(st replicats/REPCO) != running ]] || { echo "REPCO esta running: um Replicat por vez" >&2; exit 2; }
}
# espera o gerador em segundo plano iniciar a carga; define LI (inicio_carga ISO UTC do relogio do desenv-server)
aguardar_inicio_carga() {
  LI=
  for _ in $(seq 1 120); do
    gn=$(ssh -o BatchMode=yes desenv-server "docker ps --filter name=^gg-gerador- --format '{{.Names}}'" | head -1 || true)
    [[ -n $gn ]] && LI=$(ssh -o BatchMode=yes desenv-server "docker logs $gn 2>&1" | sed -n 's/.*inicio_carga=//p' | head -1 || true)
    [[ -n $LI ]] && break; sleep 1
  done
  [[ -n $LI ]] || { echo "gerador nao iniciou a carga" >&2; return 1; }
}
# Controle do Extract pela REST. ACHADO (C8): um STOP manual do Extract e DESFEITO pelo Admin Service em ~6 s enquanto autoStart/autoRestart estao
# habilitados (managedProcessSettings), entao o Extract "parado" voltava a minerar o redo. Por isso parar_extract desabilita autoStart/autoRestart antes do
# STOP e religar_extract inicia e REABILITA (mesmos valores de gg/criar-pipeline.sh). EXTRACT_PARADO=1 enquanto estiver parado (os traps de erro religam).
MANAGED_EXT_ON='{"managedProcessSettings":{"autoStart":{"enabled":true,"delay":0},"autoRestart":{"enabled":true,"onSuccess":false,"delay":5,"retries":9,"window":60,"disableOnFailure":false}}}'
MANAGED_EXT_OFF='{"managedProcessSettings":{"autoStart":{"enabled":false,"delay":0},"autoRestart":{"enabled":false,"onSuccess":false,"delay":5,"retries":9,"window":60,"disableOnFailure":false}}}'
EXTRACT_PARADO=0
parar_extract() {
  EXTRACT_PARADO=1
  gg PATCH /extracts/EXTBDAD "$MANAGED_EXT_OFF" >/dev/null
  gg POST /commands/execute '{"name":"stop","processName":"EXTBDAD"}' >/dev/null
  for _ in $(seq 1 60); do [[ $(st extracts/EXTBDAD) == stopped ]] && break; sleep 1; done
  [[ $(st extracts/EXTBDAD) == stopped ]] || { echo "EXTBDAD nao parou" >&2; return 1; }
  sleep 12   # confirma que nada o religa (autoRestart tinha atraso de 5 s)
  [[ $(st extracts/EXTBDAD) == stopped ]] || { echo "EXTBDAD religou sozinho" >&2; return 1; }
}
religar_extract() {
  gg POST /commands/execute '{"name":"start","processName":"EXTBDAD"}' >/dev/null
  for _ in $(seq 1 120); do [[ $(st extracts/EXTBDAD) == running ]] && break; sleep 1; done
  [[ $(st extracts/EXTBDAD) == running ]] || { echo "EXTBDAD nao subiu" >&2; return 1; }
  gg PATCH /extracts/EXTBDAD "$MANAGED_EXT_ON" >/dev/null
  EXTRACT_PARADO=0
}
# Integridade final (igual ao cenario.sh): reconciliacao SQL + jar Java, snapshot de UUIDs contra o de referencia, archive, containers.
# integridade_final DIR CEN EXE
integridade_final() {
  local D=$1 CEN=$2 EXE=$3
  scripts/gg/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && true || true
  LEGADO_URL=jdbc:oracle:thin:@//192.168.0.5:1523/FREEPDB1 NOVO_URL=jdbc:oracle:thin:@//192.168.0.11:1524/FREEPDB1 MODO=completo DRY_RUN=true \
    java -Xmx512m -jar "$JAR" reconciliacao > "$D/reconciliacao-java.log" 2>&1 && true || true
  scripts/gg/snapshot-ids.sh "$CEN/$EXE" apos > "$D/snapshot.txt"
  awk -F, 'NR==FNR{b[$1","$2]=$3; next} ($1","$2) in b {pre++; if (b[$1","$2]!=$3) mud++} END{printf "{\"preexistentes_comparados\":%d,\"uuids_alterados\":%d}\n", pre, mud+0}' "$BASE" "$D/snapshots/apos.csv" > "$D/uuids.json"
  scripts/gg/limpar-archive.sh > "$D/archive-depois.json" || { cat "$D/archive-depois.json"; exit 2; }
  for h in desenv-server:gg-legado infra-server:gg-novo infra-server:gg-hub; do
    ssh -o BatchMode=yes "${h%%:*}" "docker inspect --format '{{json .}}' ${h##*:}" | jq -c '{nome:.Name[1:],id:.Id,imagem:.Config.Image,imagem_id:.Image,cpu:.HostConfig.NanoCpus,memoria:.HostConfig.Memory,running:.State.Running,inicio:.State.StartedAt,reinicios:.RestartCount,saudavel:(.State.Health.Status // null)}'
  done | jq -s . > "$D/containers.json"
}
psha() { for f in gg/params/*.prm; do jq -nc --arg n "$(basename "$f")" --arg h "$(sha256sum "$f" | cut -c1-16)" '{($n):$h}'; done | jq -s add; }
RDESC_OFICIAL() { echo "REPBDAD classico ($(grep -E '^(GROUPTRANSOPS|BATCHSQL|EOFDELAYCSECS)' gg/params/REPBDAD.prm | tr '\n' ' '))"; }
epoch_iso() { date -d "$1" +%s.%N; }   # instante absoluto (independe do salto do relogio do WSL2)
