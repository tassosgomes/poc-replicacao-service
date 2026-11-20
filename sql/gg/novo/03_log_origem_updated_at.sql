-- Incremental sobre o lab em pe (nao recria tabelas, preserva massa e UUIDs): acrescenta origem_updated_at ao aplicacao_log
-- (latencia por evento = aplicado_em - origem_updated_at; COMMITTIMESTAMP tem resolucao de 1 s). Executar como REPLICA em FREEPDB1.
whenever sqlerror exit sql.sqlcode
alter table aplicacao_log add (origem_updated_at timestamp(6));
create or replace trigger trg_titular_log after insert or update on titular for each row
declare
  v_op char(1) := case when inserting then 'I' else 'U' end;
begin
  insert into aplicacao_log (tabela, codigo, op, origem_commit_ts, origem_updated_at) values ('TITULAR', :new.codigo, v_op, :new.origem_commit_ts, :new.origem_updated_at);
end;
/
create or replace trigger trg_obra_musical_log after insert or update on obra_musical for each row
declare
  v_op char(1) := case when inserting then 'I' else 'U' end;
begin
  insert into aplicacao_log (tabela, codigo, op, origem_commit_ts, origem_updated_at) values ('OBRA_MUSICAL', :new.codigo, v_op, :new.origem_commit_ts, :new.origem_updated_at);
end;
/
create or replace trigger trg_fonograma_log after insert or update on fonograma for each row
declare
  v_op char(1) := case when inserting then 'I' else 'U' end;
begin
  insert into aplicacao_log (tabela, codigo, op, origem_commit_ts, origem_updated_at) values ('FONOGRAMA', :new.codigo, v_op, :new.origem_commit_ts, :new.origem_updated_at);
end;
/
exit
