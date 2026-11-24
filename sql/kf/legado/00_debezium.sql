-- Executado como SYS no CDB (servico FREE) do container kf-legado, descartavel. Prepara a origem para o conector
-- Oracle do Debezium (LogMiner): ARCHIVELOG, FORCE LOGGING, supplemental logging minimo e usuario de captura
-- c##dbzuser (privilegios da doc do Debezium, secao "Creating a user account for the connector").
-- Pre-requisito: diretorio /opt/oracle/oradata/FREE/arch existe (criado por aplicar-esquema-legado.sh).
-- Reaplicar e seguro (reinicia o CDB a cada execucao).
whenever sqlerror exit sql.sqlcode
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

-- Tablespace do usuario de captura (tabelas internas do LogMiner), no CDB e no PDB.
declare n number;
begin
  select count(*) into n from dba_tablespaces where tablespace_name = 'LOGMINER_TBS';
  if n = 0 then
    execute immediate 'create tablespace logminer_tbs datafile ''/opt/oracle/oradata/FREE/logminer_tbs.dbf'' size 25m reuse autoextend on maxsize unlimited';
  end if;
end;
/
alter session set container = FREEPDB1;
declare n number;
begin
  select count(*) into n from dba_tablespaces where tablespace_name = 'LOGMINER_TBS';
  if n = 0 then
    execute immediate 'create tablespace logminer_tbs datafile ''/opt/oracle/oradata/FREE/FREEPDB1/logminer_tbs.dbf'' size 25m reuse autoextend on maxsize unlimited';
  end if;
end;
/
alter session set container = CDB$ROOT;
declare n number;
begin
  select count(*) into n from dba_users where username = 'C##DBZUSER';
  if n = 0 then
    execute immediate 'create user c##dbzuser identified by dbz default tablespace logminer_tbs quota unlimited on logminer_tbs container=all';
  end if;
end;
/
grant create session to c##dbzuser container=all;
grant set container to c##dbzuser container=all;
grant select on v_$database to c##dbzuser container=all;
grant flashback any table to c##dbzuser container=all;
grant select any table to c##dbzuser container=all;
grant select_catalog_role to c##dbzuser container=all;
grant execute_catalog_role to c##dbzuser container=all;
grant select any transaction to c##dbzuser container=all;
grant logmining to c##dbzuser container=all;
grant create table to c##dbzuser container=all;
grant lock any table to c##dbzuser container=all;
grant create sequence to c##dbzuser container=all;
grant execute on dbms_logmnr to c##dbzuser container=all;
grant execute on dbms_logmnr_d to c##dbzuser container=all;
grant select on v_$log to c##dbzuser container=all;
grant select on v_$log_history to c##dbzuser container=all;
grant select on v_$logmnr_logs to c##dbzuser container=all;
grant select on v_$logmnr_contents to c##dbzuser container=all;
grant select on v_$logmnr_parameters to c##dbzuser container=all;
grant select on v_$logfile to c##dbzuser container=all;
grant select on v_$archived_log to c##dbzuser container=all;
grant select on v_$archive_dest_status to c##dbzuser container=all;
grant select on v_$transaction to c##dbzuser container=all;
grant select on v_$mystat to c##dbzuser container=all;
grant select on v_$statname to c##dbzuser container=all;
exit
