whenever sqlerror exit sql.sqlcode
begin
  for r in (select table_name from user_tables where table_name in ('FONOGRAMA','OBRA_MUSICAL','TITULAR','LEASE_PARTICAO','WORKER_HEARTBEAT')) loop
    execute immediate 'drop table ' || r.table_name || ' cascade constraints purge';
  end loop;
end;
/
create table titular (id raw(16) primary key, codigo number unique not null, nome varchar2(200), tipo varchar2(2), documento varchar2(30), situacao varchar2(20), excluido number(1), origem_updated_at timestamp(6), replicado_em timestamp(6));
create table obra_musical (id raw(16) primary key, codigo number unique not null, titulo varchar2(200), iswc varchar2(30), genero varchar2(50), situacao varchar2(20), excluido number(1), origem_updated_at timestamp(6), replicado_em timestamp(6));
create table fonograma (id raw(16) primary key, codigo number unique not null, obra_codigo number, titulo varchar2(200), isrc varchar2(30), duracao_seg number, excluido number(1), origem_updated_at timestamp(6), replicado_em timestamp(6));
create table lease_particao (tabela varchar2(30) not null, particao number(2) not null, dono varchar2(128), expira_em timestamp(6), versao number(19) default 0 not null, primary key(tabela, particao));
create table worker_heartbeat (worker_id varchar2(128) primary key, visto_em timestamp(6));
insert into lease_particao (tabela, particao) select t.nome, p.n-1 from (select 'TITULAR' nome from dual union all select 'OBRA_MUSICAL' from dual union all select 'FONOGRAMA' from dual) t cross join (select level n from dual connect by level <= 16) p;
commit;
exit
