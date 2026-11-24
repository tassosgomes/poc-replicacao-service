#!/usr/bin/env bash
# Limpeza idempotente de archive logs do kf-legado + alarme de disco (equivalente a scripts/gg/limpar-archive.sh).
# CRITERIO SEGURO: o conector Debezium (LogMiner) reinicia a mineracao no SCN do seu offset confirmado. O offset (REST do Connect,
# GET /connectors/kf-oracle/offsets) traz `scn` = menor SCN ainda necessario (inicio da transacao aberta mais antiga ou o ultimo
# processado) e `commit_scn` (ultimo commit emitido). Necessario = MIN(scn, numero antes dos ":" de commit_scn) - MARGEM_SCN (padrao 0).
# So apaga archive log cujo NEXT_CHANGE# <= necessario e nunca os MANTER_ULTIMOS (padrao 3) mais recentes. O offset e gravado a cada
# ~60 s (offset.flush.interval.ms), entao o valor lido so pode estar atrasado (mais conservador). Sem offset (conector novo sem
# nenhum evento) ou sem acesso ao REST, nao apaga nada (um conector parado tambem mantem o offset: nada necessario e apagado).
# Se o historico de schema for perdido ou o offset ficar fora do redo disponivel, o conector exige novo snapshot (when_needed).
# Imagem slim sem rman: remocao por arquivo (rm) no container. Alarme (exit 2): livre no host < MIN_LIVRE_GB (10) ou archive >
# MAX_ARCHIVE_GB (20) depois da limpeza. Exit 1: erro operacional.
# Uso: scripts/kf/limpar-archive.sh [--verificar]   (--verificar so mede e alarma)   Saida: linha JSON em stdout.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
HOST=desenv-server; C=kf-legado; DIR=/opt/oracle/oradata/FREE/arch
MIN_LIVRE_GB=${MIN_LIVRE_GB:-10}; MAX_ARCHIVE_GB=${MAX_ARCHIVE_GB:-20}; MANTER=${MANTER_ULTIMOS:-3}; MARGEM=${MARGEM_SCN:-0}
MODO=limpar; [[ ${1:-} == --verificar ]] && MODO=verificar
sysdba() { ssh_poc $HOST "docker exec -i $C sqlplus -s / as sysdba"; }
medir() {
  local livre arq
  livre=$(ssh_poc $HOST 'df -BG --output=avail /var/lib/docker 2>/dev/null || df -BG --output=avail /' | tail -1 | tr -dc 0-9)
  arq=$(ssh_poc $HOST "docker exec $C du -sb $DIR" | cut -f1)
  echo "$livre $arq"
}
read -r LIVRE0 ARQ0 < <(medir)
APAGADOS=0; REQ=0
if [[ $MODO == limpar ]]; then
  OFF=$(offset_conector || true)
  if [[ -n $OFF ]]; then
    REQ=$(jq -r '[ (.scn // empty | tonumber), (.commit_scn // empty | split(":")[0] | tonumber) ] | min // 0' <<< "$OFF")
    REQ=$(( REQ > MARGEM ? REQ - MARGEM : 0 ))
  fi
  if [[ $REQ -gt 0 ]]; then
    LISTA=$(printf "set heading off feedback off pagesize 0 linesize 300 trimspool on\nselect name from v\$archived_log where name is not null and deleted='NO' and next_change# <= %s order by sequence#;\nexit\n" "$REQ" | sysdba | tr -d '\r' | grep "^$DIR/" | sort -u || true)
    RECENTES=$(printf "set heading off feedback off pagesize 0 linesize 300\nselect name from (select name from v\$archived_log where name is not null and deleted='NO' order by sequence# desc) where rownum <= %s;\nexit\n" "$MANTER" | sysdba | tr -d '\r' | grep "^$DIR/" || true)
    for f in $LISTA; do
      grep -qxF "$f" <<<"$RECENTES" && continue
      ssh_poc $HOST "docker exec $C test -e '$f'" 2>/dev/null || continue
      ssh_poc $HOST "docker exec $C rm -f -- '$f'" && APAGADOS=$((APAGADOS+1))
    done
  fi
fi
read -r LIVRE1 ARQ1 < <(medir)
ARQGB=$(( (ARQ1 + 1073741823) / 1073741824 )); ALARME=false
(( LIVRE1 < MIN_LIVRE_GB )) && ALARME=true; (( ARQGB > MAX_ARCHIVE_GB )) && ALARME=true
jq -nc --arg modo "$MODO" --argjson ap "$APAGADOS" --argjson req "$REQ" --argjson l0 "$LIVRE0" --argjson l1 "$LIVRE1" --argjson a0 "$ARQ0" --argjson a1 "$ARQ1" \
  --argjson minl "$MIN_LIVRE_GB" --argjson maxa "$MAX_ARCHIVE_GB" --argjson alarme "$ALARME" \
  '{modo:$modo, apagados:$ap, scn_necessario_conector:$req, livre_gb_antes:$l0, livre_gb_depois:$l1, archive_bytes_antes:$a0, archive_bytes_depois:$a1, min_livre_gb:$minl, max_archive_gb:$maxa, alarme:$alarme}'
if [[ $ALARME == true ]]; then echo "ALARME DE DISCO: livre=${LIVRE1} GB (min $MIN_LIVRE_GB), archive=${ARQGB} GB (max $MAX_ARCHIVE_GB)" >&2; exit 2; fi
