#!/usr/bin/env bash
# Lag por estagio a partir do historico do heartbeat do GG (ggadmin.gg_heartbeat_history no gg-novo), ultimos N minutos (padrao 3):
# captura = INCOMING_EXTRACT_TS - INCOMING_HEARTBEAT_TS (commit do heartbeat na origem -> Extract), aplicacao = REPLICAT_TS - EXTRACT_TS.
# O heartbeat roda a cada 10 s (frequencia configurada em criar-pipeline.sh): poucas amostras, indicativo.
M=${1:-3}
ssh -o BatchMode=yes infra-server 'docker exec -i gg-novo sqlplus -s ggadmin/ggadmin@localhost:1521/FREEPDB1' <<EOF
set linesize 200 pagesize 100 feedback off
alter session set nls_numeric_characters='.,';
with s(h,e,r) as (select incoming_heartbeat_ts, incoming_extract_ts, incoming_replicat_ts from gg_heartbeat_history where heartbeat_received_ts > systimestamp - interval '$M' minute)
select to_char(h,'HH24:MI:SS') hb,
 round((cast(e as date)-cast(h as date))*86400 + (extract(second from e)-trunc(extract(second from e))) - (extract(second from h)-trunc(extract(second from h))),2) captura_s,
 round((cast(r as date)-cast(e as date))*86400 + (extract(second from r)-trunc(extract(second from r))) - (extract(second from e)-trunc(extract(second from e))),2) aplicacao_s
from s order by h;
exit
EOF
