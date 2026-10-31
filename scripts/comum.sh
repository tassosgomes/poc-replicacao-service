#!/usr/bin/env bash
# Funções compartilhadas pelos comandos da PoC.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck disable=SC2034 # usado pelos scripts que carregam este arquivo
STATE="$ROOT/.tsg-flow/v01-inventario"
ssh_poc() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$@"; }
inventario() { ssh_poc "$1" 'docker ps --format "{{.Names}} {{.Status}}" | sort' | sed -E 's/ Up .*/ Up/'; }
sql() { ssh_poc "$1" "docker exec -i $2 sqlplus -s $3/$3@localhost:1521/FREEPDB1"; }
