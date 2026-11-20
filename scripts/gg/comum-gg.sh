#!/usr/bin/env bash
# Funcoes do lab GoldenGate (subir/derrubar). Carrega scripts/comum.sh (somente leitura: nao e alterado).
source "$(dirname "${BASH_SOURCE[0]}")/../comum.sh"
# shellcheck disable=SC2034
GG_STATE="$ROOT/.tsg-flow/gg-inventario"
GG_HOSTS=(desenv-server infra-server)
GG_PORTAS=(desenv-server:1523 infra-server:1524 infra-server:18443)
GG_HUB_IMG=container-registry.oracle.com/goldengate/goldengate-oracle-free:latest
ORA_IMG=gvenzl/oracle-free:23-slim-faststart
# Instante em segundos (relogio NTP do desenv-server): evita o relogio do WSL2, que salta.
agora() { ssh_poc desenv-server 'date +%s.%N'; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
# Inventario dos recursos que NAO sao do lab GG (so leitura): containers (nome + estado), volumes e, para poc-*,
# identidade e instante de parada do container e data de criacao do volume (detecta qualquer mexida no lab Java).
inventario_gg() {
  local h
  for h in "${GG_HOSTS[@]}"; do
    ssh_poc "$h" 'docker ps -a --format "{{.Names}} {{.State}}" | grep -v "^gg-" | sort | sed "s/^/c /"
      docker volume ls -q | grep -v "^gg-" | sort | sed "s/^/v /"
      for c in $(docker ps -a --format "{{.Names}}" | grep "^poc-"); do docker inspect --format "poc {{.Name}} {{.Id}} {{.State.Status}} {{.State.FinishedAt}} {{.RestartCount}}" "$c"; done
      for v in $(docker volume ls -q | grep "^poc-"); do docker volume inspect --format "pocvol {{.Name}} {{.CreatedAt}} {{.Mountpoint}}" "$v"; done' | sed "s/^/$h /"
  done
}
