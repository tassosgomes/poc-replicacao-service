#!/usr/bin/env bash
# Prepara o kf-legado (origem do lab Kafka). Massa REDUZIDA com KF_MASSA_REDUZIDA=1 (spike).
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
C=kf-legado
sys() { ssh_poc desenv-server "docker exec -i $C sqlplus -s / as sysdba"; }  # bequeath: sobrevive ao shutdown
sql_kf() { ssh_poc desenv-server "docker exec -i $C sqlplus -s bdad/bdad@localhost:1521/FREEPDB1"; }
D="$ROOT/sql/kf/legado"
sys < "$D/00_redo.sql"
ssh_poc desenv-server "docker exec -u root $C bash -c 'mkdir -p /opt/oracle/oradata/FREE/arch && chown oracle:oinstall /opt/oracle/oradata/FREE/arch'"
sys < "$D/00_debezium.sql"
sql_kf < "$D/01_schema.sql"
if [[ ${KF_MASSA_REDUZIDA:-0} == 1 ]]; then
  sed -e 's/define n_titular = 200000/define n_titular = 1000/' -e 's/define n_obra = 500000/define n_obra = 2000/' -e 's/define n_fonograma = 1000000/define n_fonograma = 4000/' "$D/02_massa.sql" | sql_kf
else
  sql_kf < "$D/02_massa.sql"
fi
