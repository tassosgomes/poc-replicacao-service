-- Latencia = aplicado_em - origem_updated_at (hora do DML na origem, us) e aplicado_em - origem_commit_ts (hora de commit
-- do redo, resolucao de 1 s). Hosts diferentes: offset medido por scripts/medir-relogios.py (ver notas).
set linesize 200 pagesize 50 verify off
with l as (
  select (extract(day from (a.aplicado_em - t.origem_updated_at))*86400 + extract(hour from (a.aplicado_em - t.origem_updated_at))*3600
        + extract(minute from (a.aplicado_em - t.origem_updated_at))*60 + extract(second from (a.aplicado_em - t.origem_updated_at))) lat_upd,
         (extract(day from (a.aplicado_em - a.origem_commit_ts))*86400 + extract(hour from (a.aplicado_em - a.origem_commit_ts))*3600
        + extract(minute from (a.aplicado_em - a.origem_commit_ts))*60 + extract(second from (a.aplicado_em - a.origem_commit_ts))) lat_commit
  from aplicacao_log a join titular t on t.codigo = a.codigo
  where a.tabela = 'TITULAR' and a.op = 'U' and a.codigo between &1 and &2 and a.aplicado_em > systimestamp - interval '3' minute)
select count(*) n,
  round(min(lat_upd),3) upd_min, round(median(lat_upd),3) upd_p50, round(percentile_cont(0.95) within group (order by lat_upd),3) upd_p95, round(max(lat_upd),3) upd_max,
  round(min(lat_commit),3) com_min, round(median(lat_commit),3) com_p50, round(percentile_cont(0.95) within group (order by lat_commit),3) com_p95, round(max(lat_commit),3) com_max
from l;
exit
