set verify off
-- ~200 updates em transacoes individuais, 100 ms entre elas (~2 updates/s, sao ~20 s). Titular &1..&2 (parametros).
begin
  for i in &1 .. &2 loop
    update titular set nome = 'lat ' || i || ' ' || to_char(systimestamp, 'HH24MISSFF3') where codigo = i;
    commit;
    dbms_session.sleep(0.1);
  end loop;
end;
/
exit
