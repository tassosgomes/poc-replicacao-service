set pagesize 100 linesize 220 verify off
col nome format a16
col id format a34
select codigo, rawtohex(id) id, nome, situacao, excluido, to_char(origem_updated_at,'HH24:MI:SS.FF3') o_upd, to_char(origem_commit_ts,'HH24:MI:SS.FF3') commit_ts from titular where codigo in (900001,10,20) order by codigo;
select 'obra' t, codigo, rawtohex(id) id, titulo from obra_musical where codigo = 900001;
select 'fono' t, codigo, rawtohex(id) id, obra_codigo from fonograma where codigo = 900001;
select tabela, codigo, op, to_char(origem_commit_ts,'HH24:MI:SS.FF3') commit_ts, to_char(aplicado_em,'HH24:MI:SS.FF3') aplicado from aplicacao_log order by aplicado_em;
exit
