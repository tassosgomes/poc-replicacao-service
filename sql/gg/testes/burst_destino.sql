-- Vazao de apply da rajada &1: 4000 entradas no log; taxa = 4000 / (ultimo - primeiro aplicado_em).
set verify off linesize 200
select count(*) aplicadas,
  to_char(min(l.aplicado_em),'HH24:MI:SS.FF3') primeiro, to_char(max(l.aplicado_em),'HH24:MI:SS.FF3') ultimo,
  round(count(*) / nullif((extract(minute from (max(l.aplicado_em)-min(l.aplicado_em)))*60 + extract(second from (max(l.aplicado_em)-min(l.aplicado_em)))),0),0) ops_por_s
from aplicacao_log l join fonograma f on f.codigo = l.codigo
where l.tabela='FONOGRAMA' and l.op='U' and f.titulo like '&1 %' and l.origem_commit_ts > (select max(origem_commit_ts) - interval '20' second from aplicacao_log);
select count(*) titulo_ok from fonograma where titulo like '&1 %';
exit
