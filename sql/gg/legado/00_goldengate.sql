-- Executado como SYS no CDB (serviço FREE) do container gg-legado, descartável. Prepara a origem
-- para o Extract integrado do GoldenGate: ARCHIVELOG, FORCE LOGGING, supplemental logging mínimo,
-- ENABLE_GOLDENGATE_REPLICATION, streams pool e usuários de captura. Reaplicar é seguro (reinicia o CDB a cada execução).
-- Pré-requisito: diretório /opt/oracle/oradata/FREE/arch existe (criado por aplicar-esquema-legado.sh).
whenever sqlerror exit sql.sqlcode
set serveroutput on
-- Sempre reinicia em mount; se já estava em ARCHIVELOG o comando abaixo falha e o erro é ignorado.
alter system set log_archive_dest_1='LOCATION=/opt/oracle/oradata/FREE/arch' scope=both;
whenever sqlerror continue
shutdown immediate
startup mount
alter database archivelog;
whenever sqlerror exit sql.sqlcode
alter database open;
alter pluggable database all open;
alter pluggable database all save state;
whenever sqlerror continue
alter database force logging;
whenever sqlerror exit sql.sqlcode
alter database add supplemental log data;
alter system set enable_goldengate_replication=true scope=both;
alter system set streams_pool_size=256m scope=both;

-- Usuário de captura no CDB (modelo clássico, c##ggadmin).
declare
  n number;
begin
  select count(*) into n from dba_users where username = 'C##GGADMIN';
  if n = 0 then
    execute immediate 'create user c##ggadmin identified by ggadmin container=all';
  end if;
  execute immediate 'grant create session, connect, resource, alter system, select any dictionary, select any table, flashback any table, unlimited tablespace to c##ggadmin container=all';
  -- 23ai: DBMS_GOLDENGATE_AUTH.GRANT_ADMIN_PRIVILEGE está desabilitada (ORA-26988); usa-se a role OGG_CAPTURE.
  execute immediate 'grant ogg_capture to c##ggadmin container=all';
end;
/

-- Usuário de captura local no PDB (modelo 23ai, ggadmin + role OGG_CAPTURE).
alter session set container = FREEPDB1;
declare
  n number;
  r number;
begin
  select count(*) into n from dba_users where username = 'GGADMIN';
  if n = 0 then
    execute immediate 'create user ggadmin identified by ggadmin';
  end if;
  execute immediate 'grant create session, connect, resource, unlimited tablespace, alter system, select any dictionary, select any table, flashback any table to ggadmin';
  select count(*) into r from dba_roles where role = 'OGG_CAPTURE';
  if r > 0 then
    execute immediate 'grant ogg_capture to ggadmin';
    dbms_output.put_line('GGADMIN: role OGG_CAPTURE concedida');
  else
    raise_application_error(-20002, 'role OGG_CAPTURE inexistente; GRANT_ADMIN_PRIVILEGE é desabilitada no 23ai');
  end if;
end;
/
-- Para o Extract integrado ler as transações do bdad.
grant select on v_$transaction to bdad;
grant select on v_$session to bdad;
exit
