set heading off feedback off pagesize 0 linesize 400 trimspool on
select 'T|'||codigo||'|'||nome||'|'||tipo||'|'||documento||'|'||situacao||'|'||excluido from titular order by codigo;
select 'O|'||codigo||'|'||titulo||'|'||iswc||'|'||genero||'|'||situacao||'|'||excluido from obra_musical order by codigo;
select 'F|'||codigo||'|'||obra_codigo||'|'||titulo||'|'||isrc||'|'||duracao_seg||'|'||excluido from fonograma order by codigo;
exit
