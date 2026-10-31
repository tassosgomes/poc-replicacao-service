#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/comum.sh"
ssh_poc desenv-server 'docker exec -i poc-legado sqlplus -s sys/poc-oracle@localhost:1521/FREE as sysdba' < "$ROOT/sql/legado/00_redo.sql"
ssh_poc desenv-server 'docker exec -i poc-legado sqlplus -s sys/poc-oracle@localhost:1521/FREEPDB1 as sysdba' < "$ROOT/sql/legado/00_privilegios.sql"
sql desenv-server poc-legado bdad < "$ROOT/sql/legado/01_schema.sql"
sql infra-server poc-novo replica < "$ROOT/sql/novo/01_schema.sql"
sql desenv-server poc-legado bdad < "$ROOT/sql/legado/02_massa.sql"
