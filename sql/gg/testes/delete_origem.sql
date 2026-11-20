-- DELETE fisico com CDR ativo (UPDATEDELETES + USEMAX) em duas tabelas; excluido=1 pelo legado em outra.
delete from titular where codigo = 11;
delete from fonograma where codigo = 5;
update obra_musical set excluido = 1 where codigo = 7;
commit;
exit
