#!/usr/bin/env bash
# Sobe o lab Kafka (alternativa A): kf-legado, kf-novo, kf-kafka, kf-connect, topicos e conector Debezium.
# Nunca toca em poc-* nem gg-* (so le). Uso: scripts/kf/subir.sh
# Variaveis: KF_MASSA_REDUZIDA=1 (spike, 1k/2k/4k). KF_WORKERS=N (padrao 1; o lab Java usa 1 worker nos C2-C5 e C8-C10 e 2 no C7).
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
cd "$ROOT"
fail() { printf 'Pre-requisito: %s\n' "$*" >&2; exit 1; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

pre_requisitos_rapidos || fail 'verifique .env ignorado, SSH, Docker e sincronizacao NTP'
[[ ! -e $KF_STATE ]] || fail 'inventario anterior existe; execute scripts/kf/derrubar.sh'
for host in "${KF_HOSTS[@]}"; do
  ssh_poc "$host" 'awk "/MemAvailable/ {exit !(\$2 >= 4194304)}" /proc/meminfo' || fail "$host: RAM livre inferior a 4 GB"
  ssh_poc "$host" 'df -Pk "$(docker info --format "{{.DockerRootDir}}")" | awk "NR==2 {exit !(\$4 >= 10485760)}"' || fail "$host: disco livre inferior a 10 GB"
  ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/kf-; docker volume ls -q --filter name=^kf-; docker network ls -q --filter name=^kf-)"' || fail "$host: recursos kf- ja existem; execute scripts/kf/derrubar.sh"
done
for pair in "${KF_PORTAS[@]}"; do
  host=${pair%:*}; port=${pair#*:}
  ssh_poc "$host" "! ss -H -lnt 'sport = :$port' | grep -q ." || fail "$host: porta $port ocupada"
done
# Labs Java e GG: se existirem, precisam estar PARADOS (so leitura).
for pair in desenv-server:poc-legado infra-server:poc-novo desenv-server:gg-legado infra-server:gg-novo infra-server:gg-hub; do
  host=${pair%:*}; c=${pair#*:}
  st=$(ssh_poc "$host" "docker inspect --format '{{.State.Running}}' $c 2>/dev/null || echo ausente")
  [[ $st != true ]] || fail "$host: $c esta rodando; pare-o (docker stop $c) antes; nada foi alterado"
done
for host in "${KF_HOSTS[@]}"; do
  ssh_poc "$host" "docker image inspect '$ORA_IMG' >/dev/null 2>&1 || docker pull '$ORA_IMG' >/dev/null" || fail "$host: sem $ORA_IMG"
done
ssh_poc infra-server "docker image inspect '$KAFKA_IMG' >/dev/null 2>&1 || docker pull '$KAFKA_IMG' >/dev/null; docker image inspect '$CONNECT_IMG' >/dev/null 2>&1 || docker pull '$CONNECT_IMG' >/dev/null" || fail 'sem imagens do Kafka/Connect'

mkdir -p "$(dirname "$KF_STATE")"
inventario_kf > "$KF_STATE"
log "inventario salvo em $KF_STATE ($(wc -l < "$KF_STATE") linhas)"

T0=$(agora)
log "criando kf-legado e kf-novo (2 CPU / 3 GiB)"
ssh_poc infra-server 'docker network create kf-rede >/dev/null'
for pair in desenv-server:legado:1525:bdad infra-server:novo:1526:replica; do
  IFS=: read -r host role port user <<< "$pair"
  net=''; [[ $role == novo ]] && net='--network kf-rede'
  ssh_poc "$host" "docker volume create kf-$role >/dev/null; docker run -d --name kf-$role $net --cpus=2 --memory=3g -p $port:1521 -v kf-$role:/opt/oracle/oradata -e ORACLE_PASSWORD=poc-oracle -e APP_USER=$user -e APP_USER_PASSWORD=$user --health-cmd healthcheck.sh --health-interval 10s --health-timeout 5s --health-retries 60 $ORA_IMG >/dev/null"
done
log "subindo kf-kafka (KRaft, 2 CPU / 2 GiB, heap 1 GiB) e kf-connect (2 CPU / 2 GiB, heap 1 GiB)"
"$ROOT/scripts/kf/subir-kafka.sh"
for pair in desenv-server:legado infra-server:novo; do
  host=${pair%:*}; role=${pair#*:}
  healthy=false
  for ((i=0;i<180;i++)); do
    [[ $(ssh_poc "$host" "docker inspect --format '{{.State.Health.Status}}' kf-$role") == healthy ]] && { healthy=true; break; }
    sleep 5
  done
  [[ $healthy == true ]] || fail "$host: kf-$role nao ficou saudavel"
done
T1=$(agora); log "bancos saudaveis em $(dur "$T0" "$T1") s"
"$ROOT/scripts/kf/aplicar-esquema-legado.sh"; T2=$(agora); log "legado pronto em $(dur "$T1" "$T2") s"
"$ROOT/scripts/kf/aplicar-esquema-novo.sh"; T3=$(agora); log "destino pronto em $(dur "$T2" "$T3") s"
"$ROOT/scripts/kf/criar-topicos.sh"
# Ordem que garante o alinhamento carga x conector (docs/spike-kafka-notas.md, Fase 1): massa JA commitada no legado -> conector
# no_data em streaming (SCN_INICIAL S0 fixado, sem DML em andamento) -> carga inicial (CargaInicial) com os workers parados ->
# workers aplicam o topico desde o offset 0. Eventos de S0 ate o fim da carga entram no topico e sao aplicados com a guarda de versao.
REG=$("$ROOT/scripts/kf/registrar-conector.sh" | tee /dev/stderr | sed -n 's/.*SCN_INICIAL=\([0-9]*\).*/\1/p')
T4=$(agora); log "conector em streaming (S0=$REG) em $(dur "$T3" "$T4") s (inclui topicos)"
R="$ROOT/resultados/kf/c1"; mkdir -p "$R"
# Carga com DML concorrente opcional (teste de alinhamento): KF_CARGA_DML_TAXA=300 roda o gerador durante a CargaInicial.
GPID=
if [[ -n ${KF_CARGA_DML_TAXA:-} ]]; then
  log "gerador concorrente a ${KF_CARGA_DML_TAXA} ev/s durante a carga"
  "$ROOT/scripts/kf/gerar.sh" "$KF_CARGA_DML_TAXA" "${KF_CARGA_DML_SEG:-240}" "$R/gerador-concorrente" > "$R/gerador-concorrente.txt" 2>&1 & GPID=$!
  sleep 10
fi
CARGA=$(KF_SCN_INICIAL=$REG "$ROOT/scripts/kf/carga-inicial.sh" "$R"); T5=$(agora)
log "carga inicial: $CARGA"
[[ -z $GPID ]] || { wait "$GPID" || true; cat "$R/gerador-concorrente.txt"; }
T6=$(agora)
# C1 (scripts/kf/c1-carga.sh): snapshot de UUIDs com a carga feita e os workers ainda parados (antes x depois do replay do topico).
[[ -z ${KF_SNAPSHOT_ANTES:-} ]] || "$ROOT/scripts/kf/snapshot-ids.sh" c1 antes-workers | tee "$R/snapshot-antes.txt"
NW=${KF_WORKERS:-1}
for ((w=1; w<=NW; w++)); do "$ROOT/scripts/kf/subir-worker.sh" "$w" > /dev/null; done
log "$NW worker(s) no ar; drenando"
"$ROOT/scripts/kf/drenar.sh" 1800 | tee "$R/drenagem.txt"; T7=$(agora)
"$ROOT/scripts/kf/reconciliar.sh" > "$R/reconciliacao-sql.txt" 2>&1 && REC=0 || REC=1
cat "$R/reconciliacao-sql.txt"; T8=$(agora)
"$ROOT/scripts/kf/limpar-archive.sh" > "$R/archive-depois-subir.json" || true
jq -nc --argjson carga "$CARGA" --argjson massa_reduzida "${KF_MASSA_REDUZIDA:-0}" --argjson workers "$NW" --argjson rec "$REC" \
  --argjson bancos "$(dur "$T0" "$T1")" --argjson legado "$(dur "$T1" "$T2")" --argjson destino "$(dur "$T2" "$T3")" \
  --argjson conector "$(dur "$T3" "$T4")" --argjson carga_s "$(dur "$T4" "$T5")" --argjson concorrente "$(dur "$T5" "$T6")" \
  --argjson drenagem "$(dur "$T6" "$T7")" --argjson reconciliacao "$(dur "$T7" "$T8")" --argjson total "$(dur "$T0" "$T8")" \
  '{massa_reduzida:$massa_reduzida, workers:$workers, divergencias_sql:$rec, carga:$carga, tempos_s:{bancos_saudaveis:$bancos, legado_esquema_e_massa:$legado, destino:$destino, topicos_e_conector:$conector, carga_inicial:$carga_s, espera_gerador_concorrente:$concorrente, workers_e_drenagem:$drenagem, reconciliacao:$reconciliacao, total:$total}}' > "$R/subir-tempos.json"
log "total $(dur "$T0" "$T8") s; tempos em $R/subir-tempos.json"
for pair in desenv-server:kf-legado infra-server:kf-novo infra-server:kf-kafka infra-server:kf-connect; do
  ssh_poc "${pair%:*}" "docker inspect --format '{{.Name}} NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}' ${pair#*:}"
done
[[ $REC == 0 ]] || { echo 'reconciliacao com divergencias' >&2; exit 1; }
