-- Executado como SYS no PDB da PoC (container poc-legado, descartável): permite ao usuário do
-- legado observar as transações abertas, evidência da V-06 (TX_ABERTA_SEG) e do cenário C5.
whenever sqlerror exit sql.sqlcode
grant select on v_$transaction to bdad;
grant select on v_$session to bdad;
exit
