#!/usr/bin/env bash
# Aplica o esquema do destino do lab GoldenGate (gg-novo em infra-server).
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
ssh_poc infra-server 'docker exec -i gg-novo sqlplus -s sys/poc-oracle@localhost:1521/FREEPDB1 as sysdba' < "$ROOT/sql/gg/novo/00_goldengate.sql"
sql infra-server gg-novo replica < "$ROOT/sql/gg/novo/01_schema.sql"
