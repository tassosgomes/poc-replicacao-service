-- Carga inicial instanciada por SCN (executar como REPLICA em FREEPDB1 do gg-novo). Define &1 = SCN.
set verify off
-- Le o legado AS OF SCN via DB link (ggadmin tem SELECT ANY TABLE + FLASHBACK ANY TABLE); o UUIDv7 nasce
-- no trigger BEFORE INSERT do destino (id nao e informado). Requer destino vazio.
whenever sqlerror exit sql.sqlcode
define scn = &1
begin
  for r in (select db_link from user_db_links where db_link like 'LNK_LEGADO%') loop
    execute immediate 'drop database link ' || r.db_link;
  end loop;
end;
/
create database link lnk_legado connect to ggadmin identified by ggadmin using '192.168.0.5:1523/FREEPDB1';
insert into titular (codigo, nome, tipo, documento, situacao, excluido, origem_updated_at, origem_commit_ts)
  select codigo, nome, tipo, documento, situacao, excluido, updated_at, null from bdad.titular@lnk_legado as of scn &scn;
insert into obra_musical (codigo, titulo, iswc, genero, situacao, excluido, origem_updated_at, origem_commit_ts)
  select codigo, titulo, iswc, genero, situacao, excluido, updated_at, null from bdad.obra_musical@lnk_legado as of scn &scn;
insert into fonograma (codigo, obra_codigo, titulo, isrc, duracao_seg, excluido, origem_updated_at, origem_commit_ts)
  select codigo, obra_codigo, titulo, isrc, duracao_seg, excluido, updated_at, null from bdad.fonograma@lnk_legado as of scn &scn;
commit;
-- A carga nao e latencia de replicacao: limpa o log de aplicacao.
truncate table aplicacao_log;
drop database link lnk_legado;
select 'titular' t, count(*) from titular union all select 'obra', count(*) from obra_musical union all select 'fonograma', count(*) from fonograma;
exit
