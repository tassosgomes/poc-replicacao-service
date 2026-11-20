#!/usr/bin/env bash
# Aplica o esquema do destino do lab Kafka (kf-novo em infra-server).
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
ssh_poc infra-server 'docker exec -i kf-novo sqlplus -s sys/poc-oracle@localhost:1521/FREEPDB1 as sysdba' < "$ROOT/sql/kf/novo/00_grants.sql"
ssh_poc infra-server 'docker exec -i kf-novo sqlplus -s replica/replica@localhost:1521/FREEPDB1' < "$ROOT/sql/kf/novo/01_schema.sql"
