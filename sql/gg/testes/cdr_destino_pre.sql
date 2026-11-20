-- Simula dado mais novo no destino (origem_updated_at no futuro) para a linha 30.
update titular set origem_updated_at = systimestamp + interval '1' hour where codigo = 30;
commit;
exit
