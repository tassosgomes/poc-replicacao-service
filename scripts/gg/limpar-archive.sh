#!/usr/bin/env bash
# Limpeza idempotente de archive logs do gg-legado + alarme de disco.
# Regra: so apaga archive log cujo NEXT_CHANGE# <= REQUIRED_CHECKPOINT_SCN da captura registrada (dba_capture, PDB) menos
# margem, e nunca os MANTER_ULTIMOS (padrao 3) mais recentes. required_checkpoint_scn e o SCN a partir do qual o Extract
# integrado precisa do redo ao reiniciar; o Oracle so o avanca com o checkpoint do Extract (incluindo transacoes abertas).
# A imagem gvenzl slim NAO traz o binario rman: sem RMAN a remocao e por arquivo (rm) dentro do container; com RMAN
# disponivel usa `DELETE ARCHIVELOG ... ` sem FORCE (o proprio RMAN respeita a captura). Sem captura registrada, nao apaga nada.
# Alarme (exit 2): livre no host < MIN_LIVRE_GB (10) ou archive > MAX_ARCHIVE_GB (20) depois da limpeza. Exit 1: erro operacional.
# Uso: scripts/gg/limpar-archive.sh [--verificar]   (--verificar so mede e alarma; nao apaga)  Saida: linha JSON em stdout.
set -euo pipefail
HOST=desenv-server; C=gg-legado; DIR=/opt/oracle/oradata/FREE/arch
MIN_LIVRE_GB=${MIN_LIVRE_GB:-10}; MAX_ARCHIVE_GB=${MAX_ARCHIVE_GB:-20}; MANTER=${MANTER_ULTIMOS:-3}
MODO=limpar; [[ ${1:-} == --verificar ]] && MODO=verificar
sysdba() { ssh -o BatchMode=yes -o ConnectTimeout=10 $HOST "docker exec -i $C sqlplus -s / as sysdba"; }
medir() {
  local livre arq
  livre=$(ssh -o BatchMode=yes $HOST "df -BG --output=avail /var/lib/docker 2>/dev/null || df -BG --output=avail /" | tail -1 | tr -dc 0-9)
  arq=$(ssh -o BatchMode=yes $HOST "docker exec $C du -sb $DIR" | cut -f1)
  echo "$livre $arq"
}
read -r LIVRE0 ARQ0 < <(medir)
APAGADOS=0; REQ=0
if [[ $MODO == limpar ]]; then
  OUT=$(printf "set heading off feedback off pagesize 0 linesize 300 trimspool on\nalter session set container=FREEPDB1;\nselect 'REQ='||nvl(min(required_checkpoint_scn),0)||' N='||count(*) from dba_capture where status='ENABLED';\nexit\n" | sysdba | tr -d '\r' | grep '^REQ=' || true)
  [[ -n $OUT ]] || { echo "falha ao consultar dba_capture" >&2; exit 1; }
  REQ=${OUT#REQ=}; REQ=${REQ%% *}
  if [[ $REQ -gt 0 ]]; then
    LISTA=$(printf "set heading off feedback off pagesize 0 linesize 300 trimspool on\nselect name from (select name, row_number() over (order by sequence# desc) rn from v\$archived_log where name is not null and deleted='NO' and next_change# <= %s) where rn > 0 order by 1;\nexit\n" "$REQ" | sysdba | tr -d '\r' | grep "^$DIR/" || true)
    # mantem os MANTER mais recentes dentre todos os archive logs (nao so os elegiveis)
    RECENTES=$(printf "set heading off feedback off pagesize 0 linesize 300\nselect name from (select name from v\$archived_log where name is not null and deleted='NO' order by sequence# desc) where rownum <= %s;\nexit\n" "$MANTER" | sysdba | tr -d '\r' | grep "^$DIR/" || true)
    for f in $LISTA; do
      grep -qxF "$f" <<<"$RECENTES" && continue
      ssh -o BatchMode=yes $HOST "docker exec $C test -e '$f'" 2>/dev/null || continue   # ja removido (idempotente)
      ssh -o BatchMode=yes $HOST "docker exec $C rm -f -- '$f'" && APAGADOS=$((APAGADOS+1))
    done
  fi
fi
read -r LIVRE1 ARQ1 < <(medir)
ARQGB=$(( (ARQ1 + 1073741823) / 1073741824 )); ALARME=false
(( LIVRE1 < MIN_LIVRE_GB )) && ALARME=true; (( ARQGB > MAX_ARCHIVE_GB )) && ALARME=true
jq -nc --arg modo "$MODO" --argjson ap "$APAGADOS" --argjson req "$REQ" --argjson l0 "$LIVRE0" --argjson l1 "$LIVRE1" --argjson a0 "$ARQ0" --argjson a1 "$ARQ1" \
  --argjson minl "$MIN_LIVRE_GB" --argjson maxa "$MAX_ARCHIVE_GB" --argjson alarme "$ALARME" \
  '{modo:$modo, apagados:$ap, required_checkpoint_scn:$req, livre_gb_antes:$l0, livre_gb_depois:$l1, archive_bytes_antes:$a0, archive_bytes_depois:$a1, min_livre_gb:$minl, max_archive_gb:$maxa, alarme:$alarme}'
if [[ $ALARME == true ]]; then echo "ALARME DE DISCO: livre=${LIVRE1} GB (min $MIN_LIVRE_GB), archive=${ARQGB} GB (max $MAX_ARCHIVE_GB)" >&2; exit 2; fi
