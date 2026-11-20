#!/usr/bin/env bash
# Exporta do aplicacao_log do gg-novo todos os eventos cujo origem_updated_at cai em [INICIO, FIM] (UTC, instantes do gerador),
# um por linha: aplicado_epoch_s,latencia_bruta_s,tabela,codigo,commit_ts_origem_s (YYYYMMDDHH24MISS, resolucao de 1 s).
# Usado por c4/c5 (scripts/gg/analisar-especial.py): precisa de tabela/codigo/commit_ts para separar a rajada e a transacao longa
# das demais transacoes. A latencia bruta ainda nao tem o offset de relogio (a analise subtrai). Uso: eventos-janela.sh INICIO FIM CSV
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ini=${1:?}; fim=${2:?}; OUT=${3:?}
conv() { local t=${1%Z}; t=${t/T/ }; echo "${t:0:26}"; }
printf "whenever sqlerror exit failure
set heading off feedback off pagesize 0 linesize 200 trimspool on verify off termout on
alter session set nls_numeric_characters='.,';
with e as (select aplicado_em ap, origem_updated_at ou, tabela, codigo, origem_commit_ts cts from aplicacao_log
  where origem_updated_at >= to_timestamp('%s','YYYY-MM-DD HH24:MI:SS.FF6') and origem_updated_at <= to_timestamp('%s','YYYY-MM-DD HH24:MI:SS.FF6'))
select trim(to_char((trunc(ap)-date '1970-01-01')*86400+extract(hour from ap)*3600+extract(minute from ap)*60+extract(second from ap),'FM9999999999.000000'))
 ||','|| trim(to_char((cast(ap as date)-cast(ou as date))*86400+(extract(second from ap)-trunc(extract(second from ap)))-(extract(second from ou)-trunc(extract(second from ou))),'FM99999.000000'))
 ||','|| tabela ||','|| codigo ||','|| nvl(to_char(cts,'YYYYMMDDHH24MISS'),'0') from e;
exit\n" "$(conv "$ini")" "$(conv "$fim")" | "$ROOT/scripts/gg/q.sh" novo | tr -d '\r' \
  | grep -E '^[0-9]+\.[0-9]+,-?[0-9]*\.[0-9]+,[A-Z_]+,[0-9]+,[0-9]+$' > "$OUT" || true
echo "eventos exportados: $(wc -l < "$OUT")"
