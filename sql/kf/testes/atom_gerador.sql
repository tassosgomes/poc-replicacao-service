-- Fase 4 (atomicidade): gerador de transacoes multi-tabela no kf-legado (usuario bdad). Cada transacao, num unico COMMIT:
-- INSERT de 1 obra (codigo -(B+seq), codigos NEGATIVOS para nao alterar o MAX(codigo) que o gerador do C2 usa, iswc = 'ATM'||N, N = quantidade de fonogramas), INSERT de N fonogramas
-- (codigo (B+seq)*1000+i, obra_codigo B+seq) e UPDATE de 1 titular pre-criado (codigo B+seq, documento = 'ATM'||seq).
-- N sorteado em 1..12; a cada 50a transacao N = 100 (transacao grande). Taxa fixa de &3 transacoes por segundo (relogio monotonico).
-- &1 = B, &2 = sequencias maximas, &3 = transacoes/s, &4 = duracao (s), &5 = semente.
-- Saida: ATM_TX,seq,N,instante do commit (epoch UTC, relogio do kf-legado).
whenever sqlerror exit failure
set verify off feedback off heading off pagesize 0 linesize 200 serveroutput on size unlimited
declare
  b constant number := &1;
  maxseq constant number := &2;
  taxa constant number := &3;
  dur constant number := &4;
  inicio number := dbms_utility.get_time;
  seq number := 0;
  n number;
  alvo number;
  agora number;
  function epoch return number is
    t timestamp := sys_extract_utc(systimestamp);
  begin
    return (trunc(cast(t as date)) - date '1970-01-01') * 86400 + extract(hour from t) * 3600 + extract(minute from t) * 60 + extract(second from t);
  end;
begin
  dbms_random.seed(&5);
  dbms_output.put_line('ATM_INICIO,' || to_char(epoch, 'FM9999999999.000000'));
  while seq < maxseq and (dbms_utility.get_time - inicio) / 100 < dur loop
    seq := seq + 1;
    n := case when mod(seq, 50) = 0 then 100 else trunc(dbms_random.value(1, 13)) end;
    insert into obra_musical (codigo, titulo, iswc, genero, situacao, excluido) values (-(b + seq), 'ATM obra ' || seq, 'ATM' || n, 'ATM', 'ATIVO', 0);
    for i in 1 .. n loop
      insert into fonograma (codigo, obra_codigo, titulo, isrc, duracao_seg, excluido) values (-((b + seq) * 1000 + i), -(b + seq), 'ATM fono ' || seq || '/' || i, 'ATM' || seq, 180, 0);
    end loop;
    update titular set documento = 'ATM' || seq where codigo = -(b + seq);
    if sql%rowcount <> 1 then raise_application_error(-20001, 'titular ' || (b + seq) || ' ausente'); end if;
    commit;
    dbms_output.put_line('ATM_TX,' || seq || ',' || n || ',' || to_char(epoch, 'FM9999999999.000000'));
    alvo := seq / taxa;
    agora := (dbms_utility.get_time - inicio) / 100;
    if alvo > agora then dbms_session.sleep(alvo - agora); end if;
  end loop;
  dbms_output.put_line('ATM_FIM,' || seq || ',' || to_char(epoch, 'FM9999999999.000000'));
end;
/
exit
