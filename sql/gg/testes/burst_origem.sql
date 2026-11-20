-- Rajada: 4000 updates (fonograma 1..4000), um commit por update, sem pausa. &1 = tag.
set verify off
begin
  for i in 1 .. 4000 loop
    update fonograma set titulo = '&1 ' || i where codigo = i;
    commit;
  end loop;
end;
/
select 'burst_fim', to_char(systimestamp,'HH24:MI:SS.FF3') from dual;
exit
