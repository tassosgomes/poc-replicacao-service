#!/usr/bin/env bash
# Executa comandos do adminclient dentro do gg-hub (os comandos vem do stdin ou de argumentos).
# A senha vem de ~/.gg-hub-senha e viaja por stdin (ssh), nunca em arquivo versionado nem em argv.
# Usado onde a REST nao cobre (ex.: START REPLICAT ... AFTERCSN, o schema do /commands/execute rejeitou as formas testadas).
set -euo pipefail
if [[ $# -gt 0 ]]; then cmds=$(printf '%s\n' "$@"); else cmds=$(cat); fi
{ cat "$HOME/.gg-hub-senha"; printf '%s\n' "$cmds"; } | ssh -o BatchMode=yes infra-server \
  'read -r S; { echo "CONNECT https://localhost:8443 DEPLOYMENT Local AS oggadmin PASSWORD $S !"; cat; } | docker exec -i gg-hub adminclient' | sed -E 's/PASSWORD [^ ]+/PASSWORD ****/'
