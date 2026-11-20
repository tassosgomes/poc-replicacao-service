-- Executar como SYS em FREEPDB1 (aplicar-esquema-novo.sh): usuario de apply do GoldenGate no destino.
whenever sqlerror exit sql.sqlcode
alter system set enable_goldengate_replication=true scope=both;
declare
  n number;
begin
  select count(*) into n from dba_users where username = 'GGADMIN';
  if n = 0 then
    execute immediate 'create user ggadmin identified by ggadmin default tablespace users quota unlimited on users';
  end if;
end;
/
grant create session, connect, resource, alter session to ggadmin;
-- Modelo 23ai: role OGG_APPLY existe (verificado em gvenzl/oracle-free:23-slim-faststart).
grant ogg_apply to ggadmin;
-- Redundante com a role, mantido por compatibilidade com a forma antiga.
begin
  dbms_goldengate_auth.grant_admin_privilege('GGADMIN');
exception when others then dbms_output.put_line('grant_admin_privilege: ' || sqlerrm);
end;
/
-- DML nas tabelas do schema replica (PoC: privilegios ANY, tabelas sao recriadas pelo 01_schema.sql).
grant select any table, insert any table, update any table, delete any table to ggadmin;
-- Checkpoint table e heartbeat table do GG.
grant create table, create sequence, create view, create procedure, create job to ggadmin;
grant select any dictionary to ggadmin;
-- fn_uuid_v7 usa DBMS_CRYPTO.
grant execute on sys.dbms_crypto to replica;
-- Carga inicial por SCN usa DB link do schema replica ate o legado.
grant create database link to replica;
exit
