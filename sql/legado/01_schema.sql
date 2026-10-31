whenever sqlerror exit sql.sqlcode
begin
  for r in (select table_name from user_tables where table_name in ('FONOGRAMA','OBRA_MUSICAL','TITULAR','EVENTO_REPLICACAO')) loop
    execute immediate 'drop table ' || r.table_name || ' cascade constraints purge';
  end loop;
  for r in (select sequence_name from user_sequences where sequence_name in ('SEQ_TITULAR','SEQ_OBRA_MUSICAL','SEQ_FONOGRAMA','SEQ_EVENTO')) loop
    execute immediate 'drop sequence ' || r.sequence_name;
  end loop;
end;
/
create table titular (codigo number primary key, nome varchar2(200), tipo varchar2(2), documento varchar2(30), situacao varchar2(20), excluido number(1), updated_at timestamp(6));
create table obra_musical (codigo number primary key, titulo varchar2(200), iswc varchar2(30), genero varchar2(50), situacao varchar2(20), excluido number(1), updated_at timestamp(6));
create table fonograma (codigo number primary key, obra_codigo number not null references obra_musical(codigo), titulo varchar2(200), isrc varchar2(30), duracao_seg number, excluido number(1), updated_at timestamp(6));
create index ix_titular_updated on titular(updated_at);
create index ix_obra_updated on obra_musical(updated_at);
create index ix_fonograma_updated on fonograma(updated_at);
create sequence seq_titular start with 200001;
create sequence seq_obra_musical start with 500001;
create sequence seq_fonograma start with 1000001;
create sequence seq_evento;
create table evento_replicacao (
  id number default seq_evento.nextval primary key,
  tabela varchar2(30) not null,
  codigo number not null,
  op char(1) not null,
  particao number(3) not null,
  criado_em timestamp(6) with time zone default systimestamp not null
);
create index ix_evento_part on evento_replicacao(tabela, particao, id);
-- Simulação do legado: este trigger não faz parte da captura no BDAD real.
create or replace trigger trg_titular_updated
before insert or update on TITULAR for each row
begin
  :new.updated_at := systimestamp;
end;
/
create or replace trigger trg_titular_captura
after insert or update or delete on TITULAR for each row
declare
  v_codigo number;
  v_op char(1);
begin
  if deleting then
    v_codigo := :old.codigo;
    v_op := 'D';
  else
    v_codigo := :new.codigo;
    if inserting then v_op := 'I'; else v_op := 'U'; end if;
  end if;
  insert into evento_replicacao (tabela, codigo, op, particao)
  values ('TITULAR', v_codigo, v_op, ora_hash(v_codigo, 15));
end;
/
-- Simulação do legado: este trigger não faz parte da captura no BDAD real.
create or replace trigger trg_obra_updated
before insert or update on OBRA_MUSICAL for each row
begin
  :new.updated_at := systimestamp;
end;
/
create or replace trigger trg_obra_captura
after insert or update or delete on OBRA_MUSICAL for each row
declare
  v_codigo number;
  v_op char(1);
begin
  if deleting then
    v_codigo := :old.codigo;
    v_op := 'D';
  else
    v_codigo := :new.codigo;
    if inserting then v_op := 'I'; else v_op := 'U'; end if;
  end if;
  insert into evento_replicacao (tabela, codigo, op, particao)
  values ('OBRA_MUSICAL', v_codigo, v_op, ora_hash(v_codigo, 15));
end;
/
-- Simulação do legado: este trigger não faz parte da captura no BDAD real.
create or replace trigger trg_fonograma_updated
before insert or update on FONOGRAMA for each row
begin
  :new.updated_at := systimestamp;
end;
/
create or replace trigger trg_fonograma_captura
after insert or update or delete on FONOGRAMA for each row
declare
  v_codigo number;
  v_op char(1);
begin
  if deleting then
    v_codigo := :old.codigo;
    v_op := 'D';
  else
    v_codigo := :new.codigo;
    if inserting then v_op := 'I'; else v_op := 'U'; end if;
  end if;
  insert into evento_replicacao (tabela, codigo, op, particao)
  values ('FONOGRAMA', v_codigo, v_op, ora_hash(v_codigo, 15));
end;
/
begin
  for r in (select name from user_errors where type = 'TRIGGER' and name in
    ('TRG_TITULAR_UPDATED','TRG_OBRA_UPDATED','TRG_FONOGRAMA_UPDATED',
     'TRG_TITULAR_CAPTURA','TRG_OBRA_CAPTURA','TRG_FONOGRAMA_CAPTURA')) loop
    raise_application_error(-20001, 'Erro de compilação no trigger ' || r.name);
  end loop;
end;
/
exit
