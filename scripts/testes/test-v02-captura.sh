#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
cd "$ROOT"
tmp=$(mktemp -d)
started=false
cleanup() {
  if [[ $started == true && -f $STATE ]]; then "$ROOT/scripts/derrubar.sh" || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT
[[ ! -e $STATE ]] || { echo 'Inventário da PoC já existe.' >&2; exit 1; }
started=true
"$ROOT/scripts/subir.sh" | tee "$tmp/subir"
grep -q 'titular=200000 obra_musical=500000 fonograma=1000000 eventos_pendentes=0' "$tmp/subir"
sql desenv-server poc-legado bdad <<'SQL'
whenever sqlerror exit sql.sqlcode
set serveroutput on
-- Asserções abortam o SQL*Plus se a evidência divergir.
declare
  n number;
begin
  select count(*) into n from evento_replicacao;
  if n != 0 then raise_application_error(-20001, 'eventos após massa'); end if;
  select count(*) into n from user_triggers where trigger_name in
    ('TRG_TITULAR_CAPTURA','TRG_OBRA_CAPTURA','TRG_FONOGRAMA_CAPTURA') and status = 'ENABLED';
  if n != 3 then raise_application_error(-20002, 'captura não habilitada'); end if;
end;
/
update obra_musical set titulo = titulo || 'x' where codigo = 42;
commit;
declare
  n number;
begin
  select count(*) into n from evento_replicacao;
  if n != 1 then raise_application_error(-20003, 'quantidade do update'); end if;
  select count(*) into n from evento_replicacao where tabela = 'OBRA_MUSICAL'
    and codigo = 42 and op = 'U' and particao = ora_hash(42,15);
  if n != 1 then raise_application_error(-20004, 'evento do update'); end if;
  dbms_output.put_line('1/6 update e partição');
end;
/
truncate table evento_replicacao;
insert into titular (codigo, nome) values (2000001, 'teste');
commit;
update titular set nome = 'alterado' where codigo = 2000001;
commit;
delete from titular where codigo = 2000001;
commit;
declare
  n number;
begin
  select count(*) into n from evento_replicacao where tabela = 'TITULAR' and codigo = 2000001
    and particao = ora_hash(2000001,15) and op in ('I','U','D');
  if n != 3 then raise_application_error(-20005, 'eventos I/U/D'); end if;
  select count(distinct op) into n from evento_replicacao;
  if n != 3 then raise_application_error(-20006, 'operações I/U/D'); end if;
  select count(*) into n from evento_replicacao;
  if n != 3 then raise_application_error(-20007, 'eventos extras'); end if;
  dbms_output.put_line('2/6 insert update delete');
end;
/
truncate table evento_replicacao;
update obra_musical set titulo = titulo || 'r' where codigo = 42;
rollback;
declare
  n number;
begin
  select count(*) into n from evento_replicacao;
  if n != 0 then raise_application_error(-20008, 'rollback'); end if;
  dbms_output.put_line('3/6 rollback');
end;
/
update obra_musical set titulo = titulo || 'b' where codigo <= 50000;
commit;
declare
  n number;
begin
  select count(*) into n from evento_replicacao where tabela = 'OBRA_MUSICAL'
    and op = 'U' and particao = ora_hash(codigo,15);
  if n != 50000 then raise_application_error(-20009, 'rajada'); end if;
  select count(*) into n from evento_replicacao;
  if n != 50000 then raise_application_error(-20010, 'eventos extras na rajada'); end if;
  dbms_output.put_line('4/6 rajada');
end;
/
declare
  n number;
begin
  select count(*) into n from user_tab_columns where table_name = 'EVENTO_REPLICACAO';
  if n != 6 then raise_application_error(-20011, 'colunas de evento'); end if;
  select count(*) into n from user_tab_columns where table_name = 'EVENTO_REPLICACAO'
    and column_name in ('ID','TABELA','CODIGO','OP','PARTICAO','CRIADO_EM');
  if n != 6 then raise_application_error(-20012, 'colunas de negócio'); end if;
  dbms_output.put_line('6/6 evento sem dados de negócio');
end;
/
exit
SQL
"$ROOT/scripts/derrubar.sh"
started=false
started=true
"$ROOT/scripts/subir.sh" | tee "$tmp/subir-novamente"
grep -q 'titular=200000 obra_musical=500000 fonograma=1000000 eventos_pendentes=0' "$tmp/subir-novamente"
sql desenv-server poc-legado bdad <<'SQL'
whenever sqlerror exit sql.sqlcode
set serveroutput on
declare
  n number;
begin
  select count(*) into n from evento_replicacao;
  if n != 0 then raise_application_error(-20013, 'eventos após reexecução'); end if;
  select count(*) into n from user_triggers where trigger_name in
    ('TRG_TITULAR_CAPTURA','TRG_OBRA_CAPTURA','TRG_FONOGRAMA_CAPTURA') and status = 'ENABLED';
  if n != 3 then raise_application_error(-20014, 'captura desabilitada após reexecução'); end if;
  dbms_output.put_line('5/6 carga completa repetida e captura habilitada');
end;
/
exit
SQL
"$ROOT/scripts/derrubar.sh"
started=false
