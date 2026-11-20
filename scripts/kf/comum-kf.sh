#!/usr/bin/env bash
# Funcoes do lab Kafka (subir/derrubar). Carrega scripts/comum.sh (somente leitura: nao e alterado).
source "$(dirname "${BASH_SOURCE[0]}")/../comum.sh"
# shellcheck disable=SC2034
KF_STATE="$ROOT/.tsg-flow/kf-inventario"
KF_HOSTS=(desenv-server infra-server)
# Portas do lab: Oracle 1525/1526, Kafka (listener externo) 19092, Kafka Connect REST 18083.
KF_PORTAS=(desenv-server:1525 infra-server:1526 infra-server:19092 infra-server:18083)
ORA_IMG=gvenzl/oracle-free:23-slim-faststart
KAFKA_IMG=apache/kafka:4.3.1
CONNECT_IMG=quay.io/debezium/connect:3.7.1.Final
INFRA_IP=192.168.0.11
DESENV_IP=192.168.0.5
# Instante em segundos (relogio NTP do desenv-server): evita o relogio do WSL2, que salta.
agora() { ssh_poc desenv-server 'date +%s.%N'; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
# Inventario dos recursos que NAO sao do lab kf (so leitura): containers (nome + estado), volumes e, para poc-* e gg-*,
# identidade e instante de parada do container e data de criacao do volume (detecta qualquer mexida nos outros labs).
inventario_kf() {
  local h
  for h in "${KF_HOSTS[@]}"; do
    ssh_poc "$h" 'docker ps -a --format "{{.Names}} {{.State}}" | grep -v "^kf-" | sort | sed "s/^/c /"
      docker volume ls -q | grep -v "^kf-" | sort | sed "s/^/v /"
      docker network ls --format "{{.Name}}" | grep -v "^kf-" | sort | sed "s/^/n /"
      for c in $(docker ps -a --format "{{.Names}}" | grep -E "^(poc|gg)-"); do docker inspect --format "lab {{.Name}} {{.Id}} {{.State.Status}} {{.State.FinishedAt}} {{.RestartCount}}" "$c"; done
      for v in $(docker volume ls -q | grep -E "^(poc|gg)-"); do docker volume inspect --format "labvol {{.Name}} {{.CreatedAt}} {{.Mountpoint}}" "$v"; done' | sed "s/^/$h /"
  done
}
# Executa SQL (stdin): kfq legado|novo
kfq() {
  case ${1:?legado|novo} in
    legado) ssh_poc desenv-server 'docker exec -i kf-legado sqlplus -s bdad/bdad@localhost:1521/FREEPDB1' ;;
    novo) ssh_poc infra-server 'docker exec -i kf-novo sqlplus -s replica/replica@localhost:1521/FREEPDB1' ;;
  esac
}
connect_api() { ssh_poc infra-server "curl -s -m 30 $*"; }
