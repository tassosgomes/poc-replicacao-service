#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/comum.sh"
cd "$ROOT"
fail() { printf 'Pré-requisito: %s\n' "$*" >&2; exit 1; }
[[ -f .env ]] || fail 'crie .env no repositório'
git check-ignore -q .env || fail '.env deve ser ignorado pelo git'
if git ls-files --error-unmatch .env >/dev/null 2>&1; then fail '.env não pode ser rastreado pelo git'; fi
[[ ! -e "$STATE" ]] || fail 'inventário anterior existe; execute scripts/derrubar.sh'
for host in desenv-server infra-server; do
  ssh_poc "$host" 'docker info >/dev/null' || fail "$host: corrija SSH e Docker"
  ssh_poc "$host" 'test "$(timedatectl show -p NTPSynchronized --value)" = yes' || fail "$host: sincronize o relógio"
  ssh_poc "$host" 'awk "/MemAvailable/ {exit !(\$2 >= 4194304)}" /proc/meminfo' || fail "$host: RAM livre inferior a 4 GB"
  ssh_poc "$host" 'df -Pk "$(docker info --format "{{.DockerRootDir}}")" | awk "NR==2 {exit !(\$4 >= 10485760)}"' || fail "$host: disco livre inferior a 10 GB"
done
for pair in desenv-server:1521 infra-server:1522; do
  host=${pair%:*}; port=${pair#*:}
  ssh_poc "$host" "! ss -H -lnt 'sport = :$port' | grep -q ." || fail "$host: porta $port ocupada; libere a porta e tente novamente"
done
for host in desenv-server infra-server; do
  ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/poc-; docker volume ls -q --filter name=^poc-; docker network ls -q --filter name=^poc-)"' || fail "$host: recursos poc- já existem; verifique antes de continuar"
done
# Mede cada relógio remoto contra o relógio local pelo ponto médio da ida e volta.
for host in desenv-server infra-server; do
  start=$(date +%s%N); remote=$(ssh_poc "$host" 'date +%s%N'); end=$(date +%s%N)
  offset=$((remote - (start + (end-start)/2)))
  if [[ $host == desenv-server ]]; then a=$offset; else b=$offset; fi
done
delta=$((a-b)); (( delta < 0 )) && delta=$((-delta))
(( delta <= 100000000 )) || fail 'diferença de relógios acima de 100 ms; sincronize os servidores'
for host in desenv-server infra-server; do
  for image in gvenzl/oracle-free:23-slim-faststart eclipse-temurin:25-jre; do
    ssh_poc "$host" "docker pull '$image' >/dev/null" || fail "$host: não foi possível baixar $image"
  done
done
mkdir -p "$(dirname "$STATE")"
{ inventario desenv-server; inventario infra-server; } > "$STATE"
for pair in desenv-server:legado:1521:bdad infra-server:novo:1522:replica; do
  IFS=: read -r host role port user <<< "$pair"
  ssh_poc "$host" "docker network create poc-rede >/dev/null; docker volume create poc-$role >/dev/null; docker run -d --name poc-$role --network poc-rede --cpus=2 --memory=3g -p $port:1521 -v poc-$role:/opt/oracle/oradata -e ORACLE_PASSWORD=poc-oracle -e APP_USER=$user -e APP_USER_PASSWORD=$user --health-cmd healthcheck.sh --health-interval 10s --health-timeout 5s --health-retries 60 gvenzl/oracle-free:23-slim-faststart >/dev/null"
done
for pair in desenv-server:legado infra-server:novo; do
  host=${pair%:*}; role=${pair#*:}
  healthy=false
  for ((i=0;i<180;i++)); do
    status=$(ssh_poc "$host" "docker inspect --format '{{.State.Health.Status}}' poc-$role")
    if [[ $status == healthy ]]; then healthy=true; break; fi
    sleep 10
  done
  [[ $healthy == true ]] || fail "$host: Oracle não ficou saudável"
done
"$ROOT/scripts/aplicar-esquema.sh"
printf 'set heading off feedback off pages 0\nselect '\''titular='\''||(select count(*) from titular)||'\'' obra_musical='\''||(select count(*) from obra_musical)||'\'' fonograma='\''||(select count(*) from fonograma)||'\'' eventos_pendentes='\''||(select count(*) from evento_replicacao) from dual;\nexit\n' | sql desenv-server poc-legado bdad
for pair in desenv-server:legado infra-server:novo; do
  host=${pair%:*}; role=${pair#*:}
  ssh_poc "$host" "docker inspect --format 'poc-$role NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}' poc-$role"
done
