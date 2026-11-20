#!/usr/bin/env bash
# Carga inicial instanciada por SCN: obtem o SCN no legado, carrega o destino (vazio) AS OF SCN via DB link
# e imprime o SCN (use em AFTERCSN do Replicat). O Extract ja deve estar rodando antes do SCN.
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
SCN=$(ssh_poc desenv-server 'docker exec -i gg-legado sqlplus -s / as sysdba' <<< "alter session set container=FREEPDB1;
set heading off feedback off
select current_scn from v\$database;
exit" | tr -d ' \r' | grep -E '^[0-9]+$')
echo "SCN=$SCN" >&2
sql infra-server gg-novo replica < <(sed "s/&1/$SCN/g" "$ROOT/sql/gg/novo/02_carga_inicial.sql") >&2
echo "$SCN"
