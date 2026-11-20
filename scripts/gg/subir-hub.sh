#!/usr/bin/env bash
# Sobe o gg-hub (GoldenGate Free 23ai para Oracle) no infra-server. Reprodutivel.
# A senha do admin (oggadmin) vem de ~/.gg-hub-senha (laptop) e viaja por stdin/ssh; nunca grava em arquivo do repo.
# Uso: scripts/gg/subir-hub.sh [--recriar]   (--recriar remove o container gg-hub e mantem os volumes)
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
IMG=container-registry.oracle.com/goldengate/goldengate-oracle-free:latest
PORTA=${GG_HUB_PORTA:-18443}
[[ -r $HOME/.gg-hub-senha ]] || { echo "falta ~/.gg-hub-senha" >&2; exit 1; }
if [[ ${1:-} == --recriar ]]; then ssh_poc infra-server 'docker rm -f gg-hub >/dev/null 2>&1 || true'; fi
# Variaveis do entrypoint (/usr/local/bin/deployment-main.sh): OGG_ADMIN_PWD (senha do admin), OGG_ADMIN
# (padrao oggadmin), OGG_DEPLOYMENT (padrao Local). Volumes: /u02 (deployment, persistente) e /u03 (temporarios).
# 8443 = nginx HTTPS (autoassinado) com todos os servicos; 8080 = HTTP interno, nao publicado.
ssh_poc infra-server "docker volume create gg-hub >/dev/null && docker volume create gg-hub-tmp >/dev/null
  read -r SENHA
  docker run -d --name gg-hub --cpus=2 --memory=4g \
    -e OGG_ADMIN=oggadmin -e OGG_ADMIN_PWD=\"\$SENHA\" -e OGG_DEPLOYMENT=Local \
    -v gg-hub:/u02 -v gg-hub-tmp:/u03 -p ${PORTA}:8443 $IMG" < "$HOME/.gg-hub-senha"
echo "gg-hub iniciando; aguarde healthy: ssh infra-server docker ps --filter name=gg-hub"
