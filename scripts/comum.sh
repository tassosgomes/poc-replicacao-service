#!/usr/bin/env bash
# Funções compartilhadas pelos comandos da PoC.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck disable=SC2034 # usado pelos scripts que carregam este arquivo
STATE="$ROOT/.tsg-flow/v01-inventario"
# Alvo do lab: padroes do lab Java; para outro lab exporte LAB_* (veja scripts/lab-gg.env).
LAB_HOST_LEGADO=${LAB_HOST_LEGADO:-desenv-server}
LAB_HOST_NOVO=${LAB_HOST_NOVO:-infra-server}
LAB_CONTAINER_LEGADO=${LAB_CONTAINER_LEGADO:-poc-legado}
LAB_CONTAINER_NOVO=${LAB_CONTAINER_NOVO:-poc-novo}
LAB_USUARIO_LEGADO=${LAB_USUARIO_LEGADO:-bdad}
LAB_USUARIO_NOVO=${LAB_USUARIO_NOVO:-replica}
LAB_PORTA_LEGADO=${LAB_PORTA_LEGADO:-1521}
LAB_PORTA_NOVO=${LAB_PORTA_NOVO:-1522}
LAB_PORTA_INTERNA=${LAB_PORTA_INTERNA:-1521}
LAB_SERVICO=${LAB_SERVICO:-FREEPDB1}
LAB_REDE=${LAB_REDE:-poc-rede}
LAB_PREFIXO=${LAB_PREFIXO:-poc-}
# shellcheck disable=SC2034 # lidos por scripts que carregam este arquivo e por subprocessos
export LAB_HOST_LEGADO LAB_HOST_NOVO LAB_CONTAINER_LEGADO LAB_CONTAINER_NOVO LAB_USUARIO_LEGADO LAB_USUARIO_NOVO LAB_PORTA_LEGADO LAB_PORTA_NOVO LAB_PORTA_INTERNA LAB_SERVICO LAB_REDE LAB_PREFIXO
ssh_poc() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$@"; }
inventario() { ssh_poc "$1" 'docker ps --format "{{.Names}} {{.Status}}" | sort' | sed -E 's/ Up .*/ Up/'; }
sql() { ssh_poc "$1" "docker exec -i $2 sqlplus -s $3/$3@localhost:${LAB_PORTA_INTERNA}/${LAB_SERVICO}"; }

# Shared by startup and scenario runners; never source the credentials file.
pre_requisitos_rapidos() {
  [[ -f "$ROOT/.env" ]] && git -C "$ROOT" check-ignore -q .env || return 1
  if git -C "$ROOT" ls-files --error-unmatch .env >/dev/null 2>&1; then return 1; fi
  for host in "$LAB_HOST_LEGADO" "$LAB_HOST_NOVO"; do
    ssh_poc "$host" 'docker info >/dev/null' || return 1
    ssh_poc "$host" 'test "$(timedatectl show -p NTPSynchronized --value)" = yes' || return 1
  done
}

# Offset between hosts and half-RTT uncertainty, in nanoseconds.
medir_relogios() {
  python3 "$ROOT/scripts/medir-relogios.py"
}
