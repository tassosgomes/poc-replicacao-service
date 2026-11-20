#!/usr/bin/env bash
# Sobe o lab Kafka (alternativa A): kf-legado, kf-novo, kf-kafka, kf-connect, topicos e conector Debezium.
# Nunca toca em poc-* nem gg-* (so le). Uso: scripts/kf/subir.sh
# Variaveis: KF_MASSA_REDUZIDA=1 (spike, 1k/2k/4k). Fase 1 acrescenta a carga inicial (CargaInicial + SCN fixo).
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
"$ROOT/scripts/kf/registrar-conector.sh"
T4=$(agora); log "conector registrado; total $(dur "$T0" "$T4") s"
for pair in desenv-server:kf-legado infra-server:kf-novo infra-server:kf-kafka infra-server:kf-connect; do
  ssh_poc "${pair%:*}" "docker inspect --format '{{.Name}} NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}' ${pair#*:}"
done
