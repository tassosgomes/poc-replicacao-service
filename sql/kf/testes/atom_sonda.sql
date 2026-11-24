-- Fase 4 (atomicidade): sonda no kf-novo (usuario replica). A cada ~&4 ms le, em UMA unica consulta (leitura consistente: um SCN para
-- as tres tabelas), o estado de uma janela de transacoes do gerador e classifica cada uma: ausente, completa
-- (obra presente, N fonogramas, titular atualizado) ou PARCIAL. Tipos de parcial: 1 fonograma sem obra, 2 obra com menos fonogramas que N,
-- 4 obra visivel sem titular atualizado, 8 titular atualizado sem obra.
-- &1 = B, &2 = sequencias maximas, &3 = tempo maximo (s), &4 = intervalo (ms).
-- Saida: ATM_TXS,seq,N,primeira_vista,primeiro_parcial,ultimo_parcial,primeira_completa,amostras_parciais,tipos (epoch UTC, relogio do kf-novo).
whenever sqlerror exit failure
set verify off feedback off heading off pagesize 0 linesize 300 serveroutput on size unlimited
declare
  type tnum is table of number index by pls_integer;
  fvista tnum; fpar tnum; lpar tnum; fcom tnum; npar tnum; tipos tnum; nexp tnum;
  b constant number := &1;
  maxseq constant number := &2;
  w constant number := 120;
  lo number := 1;
  t0 number;
  ts number;
  amostras number := 0;
  kind number;
  function s(x number) return varchar2 is begin return case when x is null then '' else to_char(x, 'FM9999999999.000000') end; end;
  function epoch return number is
    t timestamp := sys_extract_utc(systimestamp);
  begin
    return (trunc(cast(t as date)) - date '1970-01-01') * 86400 + extract(hour from t) * 3600 + extract(minute from t) * 60 + extract(second from t);
  end;
begin
  t0 := epoch;
  dbms_output.put_line('ATM_SONDA_INICIO,' || s(t0));
  while epoch - t0 < &3 and lo <= maxseq loop
    ts := epoch;
    for r in (
      select q.seq,
             (select count(*) from obra_musical o where o.codigo = -(b + q.seq)) ob,
             (select max(to_number(substr(o.iswc, 4))) from obra_musical o where o.codigo = -(b + q.seq)) n,
             (select count(*) from fonograma f where f.codigo between -((b + q.seq) * 1000 + 999) and -((b + q.seq) * 1000 + 1)) fo,
             (select count(*) from titular t where t.codigo = -(b + q.seq) and t.documento = 'ATM' || q.seq) ti
        from (select level + lo - 1 seq from dual connect by level <= w) q
       where q.seq <= maxseq) loop
      if r.ob = 0 and r.fo = 0 and r.ti = 0 then
        null;
      else
        if not fvista.exists(r.seq) then fvista(r.seq) := ts; npar(r.seq) := 0; tipos(r.seq) := 0; end if;
        if r.n is not null then nexp(r.seq) := r.n; end if;
        if r.ob = 1 and r.fo = r.n and r.ti = 1 then
          if not fcom.exists(r.seq) then fcom(r.seq) := ts; end if;
        else
          kind := 0;
          if r.fo > 0 and r.ob = 0 then kind := kind + 1; end if;
          if r.ob = 1 and r.fo < r.n then kind := kind + 2; end if;
          if r.ob = 1 and r.ti = 0 then kind := kind + 4; end if;
          if r.ti = 1 and r.ob = 0 then kind := kind + 8; end if;
          if kind = 0 then kind := 16; end if; -- obra com mais fonogramas que N ou combinacao inesperada
          if not fpar.exists(r.seq) then fpar(r.seq) := ts; end if;
          lpar(r.seq) := ts;
          npar(r.seq) := npar(r.seq) + 1;
          tipos(r.seq) := tipos(r.seq) + kind - bitand(tipos(r.seq), kind);
        end if;
      end if;
    end loop;
    amostras := amostras + 1;
    while fcom.exists(lo) loop lo := lo + 1; end loop;
    dbms_session.sleep(&4 / 1000);
  end loop;
  dbms_output.put_line('ATM_SONDA_FIM,' || s(epoch) || ',' || amostras || ',' || (lo - 1));
  for q in 1 .. maxseq loop
    if fvista.exists(q) then
      dbms_output.put_line('ATM_TXS,' || q || ',' || case when nexp.exists(q) then to_char(nexp(q)) else '' end || ',' || s(fvista(q)) || ','
        || case when fpar.exists(q) then s(fpar(q)) else '' end || ',' || case when lpar.exists(q) then s(lpar(q)) else '' end || ','
        || case when fcom.exists(q) then s(fcom(q)) else '' end || ',' || npar(q) || ',' || tipos(q));
    end if;
  end loop;
end;
/
exit
