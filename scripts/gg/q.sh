#!/usr/bin/env bash
# Executa SQL (stdin) no lab GG: scripts/gg/q.sh legado|novo   (legado = bdad no gg-legado; novo = replica no gg-novo)
set -euo pipefail
case ${1:?legado|novo} in
  legado) ssh -o BatchMode=yes desenv-server 'docker exec -i gg-legado sqlplus -s bdad/bdad@localhost:1521/FREEPDB1' ;;
  novo) ssh -o BatchMode=yes infra-server 'docker exec -i gg-novo sqlplus -s replica/replica@localhost:1521/FREEPDB1' ;;
esac
