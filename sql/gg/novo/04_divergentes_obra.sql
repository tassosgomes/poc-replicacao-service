-- Chaves de OBRA_MUSICAL que diferem entre o legado (bdad, via DB link ggadmin) e o destino (replica): mesmas colunas da reconciliacao
-- (titulo, iswc, genero, situacao, excluido e a versao updated_at x origem_updated_at), mais linhas ausentes no destino.
-- Executar como REPLICA em FREEPDB1 do gg-novo. Saida: uma linha `DIV,<codigo>` por chave. Usado pelo C8 (scripts/gg/falhas.sh).
set heading off feedback off pagesize 0 linesize 200 trimspool on verify off
whenever sqlerror exit sql.sqlcode
begin
  for r in (select db_link from user_db_links where db_link like 'LNK_LEGADO%') loop
    execute immediate 'drop database link ' || r.db_link;
  end loop;
end;
/
create database link lnk_legado connect to ggadmin identified by ggadmin using '192.168.0.5:1523/FREEPDB1';
select 'DIV,' || codigo from (
  select n.codigo from obra_musical n join bdad.obra_musical@lnk_legado l on l.codigo = n.codigo
   where decode(n.origem_updated_at, l.updated_at, 0, 1) = 1 or decode(n.titulo, l.titulo, 0, 1) = 1 or decode(n.iswc, l.iswc, 0, 1) = 1
      or decode(n.genero, l.genero, 0, 1) = 1 or decode(n.situacao, l.situacao, 0, 1) = 1 or decode(n.excluido, l.excluido, 0, 1) = 1
  union all
  select l.codigo from bdad.obra_musical@lnk_legado l where not exists (select 1 from obra_musical n where n.codigo = l.codigo));
drop database link lnk_legado;
exit
