-- Fase 4 (atomicidade): titulares pre-criados que cada transacao multi-tabela vai atualizar. Usuario bdad no kf-legado.
-- Codigos NEGATIVOS: o gerador do C2 sorteia chaves em 1..MAX(codigo); codigos altos (positivos) esparsariam o espaco de chaves dele.
-- &1 = base B da corrida (codigo = B + seq), &2 = quantidade de sequencias.
whenever sqlerror exit failure
set verify off feedback off heading off
insert into titular (codigo, nome, tipo, documento, situacao, excluido)
select -(&1 + level), 'ATM titular', 'PF', 'ATM0', 'ATIVO', 0 from dual connect by level <= &2;
commit;
exit
