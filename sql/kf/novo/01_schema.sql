-- Destino da alternativa A (Kafka). Executar como REPLICA em FREEPDB1 do kf-novo.
-- Mesmas tabelas do lab Java (sql/novo/01_schema.sql: lease_particao e worker_heartbeat, usados pelo
-- LeaseRepositoryAdapter/CercaAdapter) + origem_commit_ts e fn_uuid_v7 como no gg-novo. Sem nada de GoldenGate
-- e sem triggers de log: o ReplicationSql do worker informa id (UUIDv7 do Uuid7.java) e origem_updated_at.
whenever sqlerror exit sql.sqlcode
begin
  for r in (select table_name from user_tables where table_name in ('FONOGRAMA','OBRA_MUSICAL','TITULAR','LEASE_PARTICAO','WORKER_HEARTBEAT')) loop
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
create table lease_particao (tabela varchar2(30) not null, particao number(2) not null, dono varchar2(128), expira_em timestamp(6), versao number(19) default 0 not null, primary key(tabela, particao));
create table worker_heartbeat (worker_id varchar2(128) primary key, visto_em timestamp(6));
insert into lease_particao (tabela, particao) select t.nome, p.n-1 from (select 'TITULAR' nome from dual union all select 'OBRA_MUSICAL' from dual union all select 'FONOGRAMA' from dual) t cross join (select level n from dual connect by level <= 16) p;
commit;

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
-- Carga inicial (CargaInicial do worker ou SQL) pode omitir o id.
create or replace trigger trg_titular_bi before insert on titular for each row
begin if :new.id is null then :new.id := fn_uuid_v7; end if; end;
/
create or replace trigger trg_obra_musical_bi before insert on obra_musical for each row
begin if :new.id is null then :new.id := fn_uuid_v7; end if; end;
/
create or replace trigger trg_fonograma_bi before insert on fonograma for each row
begin if :new.id is null then :new.id := fn_uuid_v7; end if; end;
/
exit
