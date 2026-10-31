-- Executado como SYS no CDB (serviço FREE) do container poc-legado, descartável. A imagem
-- faststart traz 2 grupos de redo de 10 MB: a 2.000 linhas/s o banco passa a esperar
-- "log file switch (checkpoint incomplete)" e a taxa do gerador (V-06) fica limitada pelo
-- legado. Substitui-os por 4 grupos de 512 MB; reaplicar não faz nada.
whenever sqlerror exit sql.sqlcode
declare
  grandes number;
  situacao varchar2(20);
begin
  select count(*) into grandes from v$log where bytes >= 500 * 1024 * 1024;
  if grandes >= 4 then
    return;
  end if;
  for g in 3 .. 6 loop
    execute immediate 'alter database add logfile group ' || g || ' (''/opt/oracle/oradata/FREE/redo0' || g
      || '.log'') size 512m';
  end loop;
  for r in (select group# g from v$log where bytes < 100 * 1024 * 1024) loop
    for i in 1 .. 30 loop
      select status into situacao from v$log where group# = r.g;
      exit when situacao = 'INACTIVE';
      execute immediate 'alter system switch logfile';
      execute immediate 'alter system checkpoint';
    end loop;
    execute immediate 'alter database drop logfile group ' || r.g;
  end loop;
end;
/
exit
