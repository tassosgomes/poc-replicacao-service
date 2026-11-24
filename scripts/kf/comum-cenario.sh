#!/usr/bin/env bash
# Funcoes comuns dos cenarios do lab Kafka (sourced por cenario.sh, c1-carga.sh, extra-queda.sh). Exige cwd = raiz do repo e
# scripts/lab-kf.env carregado. Instantes: relogio do desenv-server (agora, de comum-kf.sh) ou do infra-server (agora_infra); nunca o do WSL2.
source scripts/kf/comum-kf.sh
agora_infra() { ssh_poc infra-server 'date +%s.%N'; }
# offset de relogios legado-novo (scripts/medir-relogios.py, ponto medio do RTT); com retentativas (o handshake ssh as vezes estoura 20 s)
rel() {
  # offset de relogios legado-novo (scripts/medir-relogios.py, ponto medio do RTT); retenta se o handshake falhar ou se a incerteza passar de 20 ms (amostra com RTT ruim)
  local i out last=
  for i in 1 2 3 4 5 6; do
    out=$(python3 scripts/medir-relogios.py 2>/dev/null) || { sleep 3; continue; }
    last=$(awk '{printf "{\"offset_ms\":%.3f,\"incerteza_ms\":%.3f}", $1/1e6, $2/1e6}' <<< "$out")
    awk '{exit !($2/1e6 <= 20)}' <<< "$out" && { echo "$last"; return 0; }
    sleep 3
  done
  [[ -n $last ]] && { echo "$last"; return 0; }
  return 1
}
BASE=${SNAPSHOT_BASE:-resultados/kf/c1/snapshots/apos-carga.csv}
JAR=poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar
WJAR=worker/bootstrap-kafka/target/replicacao-worker-kafka-0.0.1-SNAPSHOT-exec.jar
epoch_iso() { date -d "$1" +%s.%N; }
connector_state() { connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.tasks[0].state // "?"'; }
exigir_ambiente() {
  local h c
  [[ -s $BASE ]] || { echo "snapshot base ausente: $BASE (rode scripts/kf/c1-carga.sh)" >&2; exit 2; }
  [[ -f $JAR && -f $WJAR ]] || { echo "jar ausente ($JAR / $WJAR)" >&2; exit 2; }
  for h in kf-legado:desenv-server kf-novo:infra-server kf-kafka:infra-server kf-connect:infra-server; do
    [[ $(ssh_poc "${h#*:}" "docker inspect -f '{{.State.Running}}' ${h%%:*}" 2>/dev/null || echo false) == true ]] || { echo "${h%%:*} nao esta rodando" >&2; exit 2; }
  done
  [[ $(connector_state) == RUNNING ]] || { echo "conector kf-oracle nao esta RUNNING" >&2; exit 2; }
  for c in desenv-server:poc-legado infra-server:poc-novo desenv-server:gg-legado infra-server:gg-novo infra-server:gg-hub; do
    [[ $(ssh_poc "${c%%:*}" "docker inspect -f '{{.State.Running}}' ${c#*:} 2>/dev/null || echo ausente") != true ]] || { echo "${c#*:} esta rodando: labs Java/GG devem ficar parados" >&2; exit 2; }
  done
}
# espera o gerador em segundo plano iniciar a carga; define LI (inicio_carga ISO UTC do relogio do desenv-server)
aguardar_inicio_carga() {
  LI=
  for _ in $(seq 1 120); do
    local gn; gn=$(ssh_poc desenv-server "docker ps --filter name=^kf-gerador- --format '{{.Names}}'" | head -1 || true)
    [[ -n $gn ]] && LI=$(ssh_poc desenv-server "docker logs $gn 2>&1" | sed -n 's/.*inicio_carga=//p' | head -1 || true)
    [[ -n $LI ]] && break; sleep 1
  done
  [[ -n $LI ]] || { echo "gerador nao iniciou a carga" >&2; return 1; }
}
# Integridade final: reconciliacao SQL (a oficial no lab Kafka; o subcomando Java conta delete fisico convertido como divergencia, ver notas),
# snapshot de UUIDs contra o de referencia (C1), archive, inventario dos containers e configuracao viva do conector (sem credenciais).
# integridade_final DIR CEN EXE
integridade_final() {
  local D=$1 CEN=$2 EXE=$3
  scripts/kf/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && true || true
  scripts/kf/snapshot-ids.sh "$CEN/$EXE" apos > "$D/snapshot.txt"
  awk -F, 'NR==FNR{b[$1","$2]=$3; next} ($1","$2) in b {pre++; if (b[$1","$2]!=$3) mud++} END{printf "{\"preexistentes_comparados\":%d,\"uuids_alterados\":%d}\n", pre, mud+0}' "$BASE" "$D/snapshots/apos.csv" > "$D/uuids.json"
  scripts/kf/limpar-archive.sh > "$D/archive-depois.json" || { cat "$D/archive-depois.json"; exit 2; }
  inventario_containers > "$D/containers.json"
  connect_api localhost:18083/connectors/kf-oracle/config | jq -S 'with_entries(select(.key|test("password|secret";"i")|not))' > "$D/conector-config.json"
}
inventario_containers() {
  local h
  for h in desenv-server:kf-legado infra-server:kf-novo infra-server:kf-kafka infra-server:kf-connect $(ssh_poc infra-server "docker ps -a --format '{{.Names}}' --filter name=^/kf-worker" | sed 's/^/infra-server:/'); do
    ssh_poc "${h%%:*}" "docker inspect --format '{{json .}}' ${h##*:}" | jq -c '{nome:.Name[1:],id:.Id,imagem:.Config.Image,imagem_id:.Image,cpu:.HostConfig.NanoCpus,memoria:.HostConfig.Memory,swap:.HostConfig.MemorySwap,running:.State.Running,inicio:.State.StartedAt,reinicios:.RestartCount,saudavel:(.State.Health.Status // null),env:([.Config.Env[]|select(test("^(KAFKA_|LEASE_SEG|LOTE_MAX|EVENTOS_CSV|WORKER_ID|JAVA_|KAFKA_HEAP)"))])}'
  done | jq -s .
}
# Executa um comando remoto no infra-server a partir de um instante (epoch do proprio infra-server); imprime {"t0":..,"t1":..} (antes e depois do comando).
# remoto_em ALVO_EPOCH_INFRA 'comando'
remoto_em() {
  { printf 'ALVO=%q\nCMD=%q\n' "$1" "$2"; cat <<'REMOTO'
agora() { date +%s.%N; }
while :; do n=$(agora); awk -v n="$n" -v t="$ALVO" 'BEGIN{exit !(n>=t)}' && break; sleep 0.02; done
t0=$(agora); eval "$CMD" >/dev/null; t1=$(agora)
printf '{"t0":%s,"t1":%s}\n' "$t0" "$t1"
REMOTO
  } | ssh_poc infra-server bash -s
}
# Conector Debezium: estado da tarefa e controle por REST (stop/resume mantem o offset no Connect, KIP-875).
conector_estado() { connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.connector.state + "/" + (.tasks[0].state // "-")'; }
conector_parar() {
  connect_api "-X PUT localhost:18083/connectors/kf-oracle/stop" > /dev/null
  for _ in $(seq 1 60); do [[ $(connect_api localhost:18083/connectors/kf-oracle/status | jq -r '.connector.state') == STOPPED ]] && return 0; sleep 1; done
  echo "conector nao parou" >&2; return 1
}
conector_religar() {
  connect_api "-X PUT localhost:18083/connectors/kf-oracle/resume" > /dev/null
  for _ in $(seq 1 180); do [[ $(connector_state) == RUNNING ]] && return 0; sleep 1; done
  echo "conector nao voltou a RUNNING" >&2; return 1
}
# Leases validos por dono no kf-novo (banco): linhas "dono,n"
leases() {
  printf "set heading off feedback off pagesize 0\nselect dono||','||count(*) from lease_particao where dono is not null and expira_em>sys_extract_utc(systimestamp) group by dono;\nexit\n" | kfq novo | tr -d '\r' | grep -E '^[^,]+,[0-9]+$' || true
}
# Compara eventos aplicados (CSV dos workers, deduplicado) com as linhas do gerador: comparacao-eventos.json em DIR_DEGRAU
# comparar_eventos DIR_DEGRAU FIM_OFFSETS_ANTES FIM_OFFSETS_DEPOIS
comparar_eventos() {
  local SD=$1 f0=$2 f1=$3 lin bruto dedup
  lin=$(sed -n 's/.*linhas=\([0-9]*\) transacoes=.*/\1/p' "$SD/gerador.log" | tail -1)
  bruto=$(jq -r .escritos "$SD/eventos-bruto.json"); dedup=$(jq -r .escritos "$SD/eventos-dedup.json")
  jq -nc --argjson g "$lin" --argjson t "$((f1 - f0))" --argjson b "$bruto" --argjson d "$dedup" \
    '{eventos_gerador:$g,eventos_no_topico:$t,eventos_worker_csv_bruto:$b,eventos_worker_csv_dedup:$d,duplicados_entregues:($b-$d),
      linhas_log:$d,eventos_faltantes_total:([$g-$d,0]|max),eventos_a_mais_total:([$d-$g,0]|max),chaves_com_menos_no_log:null,chaves_com_mais_no_log:null,
      sem_perda:($d>=$g)}' > "$SD/comparacao-eventos.json"
}
