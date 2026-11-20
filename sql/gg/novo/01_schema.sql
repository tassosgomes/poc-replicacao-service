-- Destino da alternativa D (GoldenGate). Executar como usuario REPLICA em FREEPDB1.
whenever sqlerror exit sql.sqlcode
begin
  for r in (select table_name from user_tables where table_name in ('FONOGRAMA','OBRA_MUSICAL','TITULAR','APLICACAO_LOG')) loop
    execute immediate 'drop table ' || r.table_name || ' cascade constraints purge';
  end loop;
  for r in (select sequence_name from user_sequences where sequence_name = 'SEQ_UUID7') loop
    execute immediate 'drop sequence ' || r.sequence_name;
  end loop;
end;
/
create table titular (id raw(16) primary key, codigo number unique not null, nome varchar2(200), tipo varchar2(2), documento varchar2(30), situacao varchar2(20), excluido number(1), origem_updated_at timestamp(6), replicado_em timestamp(6), origem_commit_ts timestamp(6));
create table obra_musical (id raw(16) primary key, codigo number unique not null, titulo varchar2(200), iswc varchar2(30), genero varchar2(50), situacao varchar2(20), excluido number(1), origem_updated_at timestamp(6), replicado_em timestamp(6), origem_commit_ts timestamp(6));
create table fonograma (id raw(16) primary key, codigo number unique not null, obra_codigo number, titulo varchar2(200), isrc varchar2(30), duracao_seg number, excluido number(1), origem_updated_at timestamp(6), replicado_em timestamp(6), origem_commit_ts timestamp(6));

create table aplicacao_log (tabela varchar2(30), codigo number, op char(1), origem_commit_ts timestamp(6), aplicado_em timestamp(6) default systimestamp, origem_updated_at timestamp(6));
create index ix_aplicacao_log_em on aplicacao_log(aplicado_em);

-- Contador de 12 bits (rand_a) como no Uuid7.java do worker: ordena dentro do mesmo milissegundo.
create sequence seq_uuid7 minvalue 0 maxvalue 4095 cycle nocache;

-- UUIDv7 (RFC 9562), layout do Uuid7.java: 48 bits de epoch ms (UTC), versao 7, contador de 12 bits,
-- variante 10 e 62 bits aleatorios. Big-endian, ordena como o UUID.
create or replace function fn_uuid_v7 return raw is
  v_ts    timestamp(3) := sys_extract_utc(systimestamp);
  v_ms    number;
  v_cnt   number := seq_uuid7.nextval;
  v_rnd   raw(8) := dbms_crypto.randombytes(8);
  v_last  raw(1);
begin
  v_ms := (trunc(v_ts) - date '1970-01-01') * 86400000
        + extract(hour from v_ts) * 3600000 + extract(minute from v_ts) * 60000
        + floor(extract(second from v_ts) * 1000);
  -- variante: dois bits mais significativos do 9o byte = 10
  v_last := utl_raw.bit_or(utl_raw.bit_and(utl_raw.substr(v_rnd, 1, 1), hextoraw('3F')), hextoraw('80'));
  return hextoraw(
      lpad(trim(to_char(v_ms, 'FMXXXXXXXXXXXX')), 12, '0')
   || '7' || lpad(trim(to_char(v_cnt, 'FMXXX')), 3, '0')
   || rawtohex(v_last) || rawtohex(utl_raw.substr(v_rnd, 2, 7)));
end;
/

create or replace trigger trg_titular_bi before insert on titular for each row
begin
  if :new.id is null then :new.id := fn_uuid_v7; end if;
  :new.replicado_em := systimestamp;
end;
/
create or replace trigger trg_titular_bu before update on titular for each row
begin
  :new.id := :old.id;
  :new.replicado_em := systimestamp;
end;
/
create or replace trigger trg_titular_log after insert or update on titular for each row
declare
  v_op char(1) := case when inserting then 'I' else 'U' end;
begin
  insert into aplicacao_log (tabela, codigo, op, origem_commit_ts, origem_updated_at) values ('TITULAR', :new.codigo, v_op, :new.origem_commit_ts, :new.origem_updated_at);
end;
/
create or replace trigger trg_obra_musical_bi before insert on obra_musical for each row
begin
  if :new.id is null then :new.id := fn_uuid_v7; end if;
  :new.replicado_em := systimestamp;
end;
/
create or replace trigger trg_obra_musical_bu before update on obra_musical for each row
begin
  :new.id := :old.id;
  :new.replicado_em := systimestamp;
end;
/
create or replace trigger trg_obra_musical_log after insert or update on obra_musical for each row
declare
  v_op char(1) := case when inserting then 'I' else 'U' end;
begin
  insert into aplicacao_log (tabela, codigo, op, origem_commit_ts, origem_updated_at) values ('OBRA_MUSICAL', :new.codigo, v_op, :new.origem_commit_ts, :new.origem_updated_at);
end;
/
create or replace trigger trg_fonograma_bi before insert on fonograma for each row
begin
  if :new.id is null then :new.id := fn_uuid_v7; end if;
  :new.replicado_em := systimestamp;
end;
/
create or replace trigger trg_fonograma_bu before update on fonograma for each row
begin
  :new.id := :old.id;
  :new.replicado_em := systimestamp;
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
