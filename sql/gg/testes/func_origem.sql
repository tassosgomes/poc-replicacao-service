-- Testes funcionais na origem (bdad). Cada passo e confirmado no destino por sql/gg/testes/func_destino.sql.
set echo on
-- T1 insert
insert into titular (codigo, nome, tipo, documento, situacao, excluido) values (900001, 'Teste Insert', 'PF', '900001', 'ATIVO', 0);
commit;
-- T2 update comum (nome)
update titular set nome = 'Teste Update' where codigo = 900001;
commit;
-- T3 exclusao logica pelo legado
update titular set excluido = 1 where codigo = 900001;
commit;
-- T4 DELETE fisico (linha existente na carga: titular 10) vira excluido = 1 no destino
delete from titular where codigo = 10;
commit;
-- T5 transacao multi-tabela (3 tabelas, um commit)
insert into obra_musical (codigo, titulo, iswc, genero, situacao, excluido) values (900001, 'Obra T5', 'T-900001', 'OUTRO', 'ATIVO', 0);
insert into fonograma (codigo, obra_codigo, titulo, isrc, duracao_seg, excluido) values (900001, 900001, 'Fono T5', 'ISRC900001', 200, 0);
update titular set situacao = 'INATIVO' where codigo = 20;
commit;
exit
