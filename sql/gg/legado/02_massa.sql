whenever sqlerror exit sql.sqlcode
define n_titular = 200000
define n_obra = 500000
define n_fonograma = 1000000
insert /*+ append */ into titular select n, 'Titular '||n, case when mod(n,7)=0 then 'PJ' else 'PF' end, to_char(n), 'ATIVO', 0, systimestamp from (select rownum n from (select 1 from dual connect by level <= 1000) cross join (select 1 from dual connect by level <= 1000)) where n <= &n_titular;
commit;
insert /*+ append */ into obra_musical select n, 'Obra '||n, to_char(n), 'OUTRO', 'ATIVO', 0, systimestamp from (select rownum n from (select 1 from dual connect by level <= 1000) cross join (select 1 from dual connect by level <= 1000)) where n <= &n_obra;
commit;
insert /*+ append */ into fonograma select n, mod(n-1,&n_obra)+1, 'Fonograma '||n, to_char(n), 180, 0, systimestamp from (select rownum n from (select 1 from dual connect by level <= 1000) cross join (select 1 from dual connect by level <= 1000)) where n <= &n_fonograma;
commit;
begin dbms_stats.gather_schema_stats(user); end;
/
exit
