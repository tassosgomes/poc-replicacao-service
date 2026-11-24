-- C5 no lab Kafka: transacao longa no kf-legado (copia de sql/gg/testes/tx_longa.sql) (usuario bdad). Equivale ao modo TX_ABERTA_SEG do gerador Java, que depende da
-- tabela evento_replicacao (so existe no lab Java). Define &1 = segundos com a transacao aberta. Mesma linha do gerador
-- (TITULAR codigo 1, excluido = 0). Todos os instantes sao do relogio do banco legado, em UTC.
whenever sqlerror exit failure
set verify off feedback off heading off pagesize 0 linesize 200 serveroutput on
declare
  fmt constant varchar2(40) := 'YYYY-MM-DD"T"HH24:MI:SS.FF6"Z"';
  t0 varchar2(40); t1 varchar2(40); tc varchar2(40);
begin
  t0 := to_char(sys_extract_utc(systimestamp), fmt);
  update titular set excluido = 0 where codigo = 1;
  if sql%rowcount <> 1 then raise_application_error(-20001, 'titular 1 ausente'); end if;
  t1 := to_char(sys_extract_utc(systimestamp), fmt);
  dbms_output.put_line('TX_LONGA t0=' || t0 || ' t1=' || t1);
  dbms_session.sleep(&1);
  commit;
  tc := to_char(sys_extract_utc(systimestamp), fmt);
  dbms_output.put_line('TX_LONGA commit=' || tc);
end;
/
exit
