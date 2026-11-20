-- 100 updates (titular 300..399) enquanto o Replicat esta parado.
begin
  for i in 300 .. 399 loop
    update titular set nome = 'restart ' || i where codigo = i;
    commit;
  end loop;
end;
/
exit
