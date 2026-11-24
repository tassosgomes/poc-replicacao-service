#!/usr/bin/env bash
# Coleta de todos os kf-worker-* (em execucao ou parados/mortos, docker ps -a): CSV de janelas (worker-*.csv), CSV de eventos (eventos-*.csv, comprimido em .csv.gz) e log.
# Uso: scripts/kf/coletar-eventos.sh DIR     Saida: DIR/worker-N/{worker-*.csv,eventos-*.csv.gz,kf-worker-N.log}
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
OUT=${1:?DIR}; mkdir -p "$OUT"
for n in $(ssh_poc infra-server 'docker ps -a --format {{.Names}} --filter name=^/kf-worker-' | sed 's/kf-worker-//' | sort); do
  d="$OUT/worker-$n"; mkdir -p "$d"
  "$(dirname "$0")/coletar-worker.sh" "$n" "$d" > /dev/null
  [[ ! -f $d/eventos-kf-worker-$n.csv ]] || gzip -f "$d/eventos-kf-worker-$n.csv"
done
ls "$OUT"/worker-*/ | head -20
