#!/usr/bin/env bash
# Laptop only: Java reads .env directly; the shell never sources credentials.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
[[ -f .env ]] && git check-ignore -q .env || { echo '.env local ignorado obrigatório' >&2; exit 1; }
if git ls-files --error-unmatch .env >/dev/null 2>&1; then exit 1; fi
export EXPORTADOR_ACAO=${1:-seguir}
if [[ $# -ge 2 ]]; then export EXPORTADOR_EXECUCAO=$2; fi
exec java -jar poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar exportador
