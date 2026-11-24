-- Fase 4 (modo transacional do worker Kafka). Executar como REPLICA em FREEPDB1 do kf-novo, depois do 01_schema.sql.
-- Idempotente (nao toca nas tabelas de dados nem nos leases por particao do modo atual).
--  kafka_offset: proxima posicao a ler de cada fluxo (particao de topico), gravada na MESMA transacao dos dados.
--  lease_global: o unico lease do modo transacional (um processador ativo; o outro worker espera para failover).
whenever sqlerror exit sql.sqlcode
begin
  for r in (select table_name from user_tables where table_name in ('KAFKA_OFFSET','LEASE_GLOBAL')) loop
    execute immediate 'drop table ' || r.table_name || ' purge';
  end loop;
end;
/
create table kafka_offset (topico varchar2(200) not null, particao number(5) not null, proximo number(19) not null, gravado_em timestamp(6) default sys_extract_utc(systimestamp), primary key (topico, particao));
create table lease_global (nome varchar2(30) primary key, dono varchar2(128), expira_em timestamp(6), versao number(19) default 0 not null);
insert into lease_global (nome) values ('GLOBAL');
commit;
exit
