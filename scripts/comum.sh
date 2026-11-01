#!/usr/bin/env bash
# Funções compartilhadas pelos comandos da PoC.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck disable=SC2034 # usado pelos scripts que carregam este arquivo
STATE="$ROOT/.tsg-flow/v01-inventario"
ssh_poc() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$@"; }
inventario() { ssh_poc "$1" 'docker ps --format "{{.Names}} {{.Status}}" | sort' | sed -E 's/ Up .*/ Up/'; }
sql() { ssh_poc "$1" "docker exec -i $2 sqlplus -s $3/$3@localhost:1521/FREEPDB1"; }

# Shared by startup and scenario runners; never source the credentials file.
pre_requisitos_rapidos() {
  [[ -f "$ROOT/.env" ]] && git -C "$ROOT" check-ignore -q .env || return 1
  if git -C "$ROOT" ls-files --error-unmatch .env >/dev/null 2>&1; then return 1; fi
  for host in desenv-server infra-server; do
    ssh_poc "$host" 'docker info >/dev/null' || return 1
    ssh_poc "$host" 'test "$(timedatectl show -p NTPSynchronized --value)" = yes' || return 1
  done
}

# Offset between hosts and half-RTT uncertainty, in nanoseconds.
medir_relogios() {
  python3 "$ROOT/scripts/medir-relogios.py"
}
