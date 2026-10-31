#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/comum.sh"
sql desenv-server poc-legado bdad < "$ROOT/sql/legado/01_schema.sql"
sql infra-server poc-novo replica < "$ROOT/sql/novo/01_schema.sql"
sql desenv-server poc-legado bdad < "$ROOT/sql/legado/02_massa.sql"
