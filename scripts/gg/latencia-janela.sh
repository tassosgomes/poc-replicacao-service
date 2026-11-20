#!/usr/bin/env bash
# Latencia por evento no gg-novo: aplicado_em - origem_updated_at (aplicacao_log) dos eventos cujo origem_updated_at cai em
# [INICIO, FIM] (instantes UTC do gerador: inicio_carga/fim_carga). Exporta o CSV (ap_epoch_s,lat_s) e analisa em Python
# (nearest-rank, tendencia como no runner Java). Uso: scripts/gg/latencia-janela.sh INICIO FIM DIR_SAIDA [offset_ms=0]
#   offset_ms: relogio(infra-server) - relogio(desenv-server), de scripts/medir-relogios.py; subtraido da latencia.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ini=${1:?}; fim=${2:?}; OUT=${3:?}; OFF=${4:-0}
conv() { local t=${1%Z}; t=${t/T/ }; echo "${t:0:26}"; }
mkdir -p "$OUT"
printf "whenever sqlerror exit failure
set heading off feedback off pagesize 0 linesize 100 trimspool on verify off termout on
alter session set nls_numeric_characters='.,';
with e as (select aplicado_em ap, origem_updated_at ou from aplicacao_log
  where origem_updated_at >= to_timestamp('%s','YYYY-MM-DD HH24:MI:SS.FF6') and origem_updated_at <= to_timestamp('%s','YYYY-MM-DD HH24:MI:SS.FF6'))
select trim(to_char((trunc(ap)-date '1970-01-01')*86400+extract(hour from ap)*3600+extract(minute from ap)*60+extract(second from ap),'FM9999999999.000000'))
 ||','|| trim(to_char((cast(ap as date)-cast(ou as date))*86400+(extract(second from ap)-trunc(extract(second from ap)))-(extract(second from ou)-trunc(extract(second from ou))),'FM99999.000000')) from e;
exit\n" "$(conv "$ini")" "$(conv "$fim")" | "$ROOT/scripts/gg/q.sh" novo | tr -d '\r' | grep -E '^[0-9]+\.[0-9]+,-?[0-9]*\.[0-9]+$' > "$OUT/latencias.csv" || true
python3 -I "$ROOT/scripts/gg/analisar-latencia.py" "$OUT/latencias.csv" "$ini" "$fim" "$OFF" > "$OUT/latencia.json"
jq -c . "$OUT/latencia.json"
