#!/usr/bin/env bash
# Injeta um evento no gg-hub (infra-server) e mede, com o relogio do proprio infra-server (NTP; nunca o do WSL2), quando a REST volta e
# quando EXTBDAD e REPBDAD estao RUNNING. Uso: scripts/gg/hub-evento.sh ACAO [ALVO_EPOCH_INFRA]
#   ACAO: kill (docker kill -s KILL, so a queda) | start | restart | kill-start (kill e start em seguida) | replicat (kill -9 so do processo REPBDAD,
#         dentro do container; mede o autorestart)
#   ALVO_EPOCH_INFRA: espera (no infra-server) ate esse instante antes de agir, para agendar a falha em relacao ao inicio da carga.
# Saida: uma linha JSON com epochs do infra-server: t_cmd0 (antes do comando), t_kill (kill-start: apos o kill), t_cmd1 (apos o comando
# retornar), t_rest, t_ext, t_rep (primeira vez que a REST responde / EXTBDAD RUNNING / REPBDAD RUNNING; replicat: RUNNING com outro processId).
# A senha vem de ~/.gg-hub-senha e vai por stdin (nunca em argv nem na saida).
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
ACAO=${1:?kill|start|restart|kill-start|replicat}; ALVO=${2:-}
[[ -r $HOME/.gg-hub-senha ]] || { echo 'falta ~/.gg-hub-senha' >&2; exit 2; }
corpo=$(cat <<'REMOTO'
agora() { date +%s.%N; }
rest() { curl -sk -m 2 -u "oggadmin:$S" "https://localhost:18443/services/Local/adminsrvr/v2/$1" 2>/dev/null || true; }
stt() { rest "$1/info/status" | grep -o '"status":"[a-z]*"' | head -1 | cut -d'"' -f4 || true; }
pidp() { rest "$1/info/status" | grep -o '"processId":[0-9]*' | head -1 | cut -d: -f2 || true; }
if [ -n "$ALVO" ]; then while :; do n=$(agora); awk -v n="$n" -v t="$ALVO" 'BEGIN{exit !(n>=t)}' && break; sleep 0.02; done; fi
tk=; old=
case $ACAO in
  kill) t0=$(agora); docker kill -s KILL gg-hub >/dev/null; t1=$(agora) ;;
  start) t0=$(agora); docker start gg-hub >/dev/null; t1=$(agora) ;;
  restart) t0=$(agora); docker restart gg-hub >/dev/null; t1=$(agora) ;;
  kill-start) t0=$(agora); docker kill -s KILL gg-hub >/dev/null; tk=$(agora); docker start gg-hub >/dev/null; t1=$(agora) ;;
  replicat) old=$(pidp replicats/REPBDAD)
    pid=$(docker exec gg-hub ps -eo pid,args | awk '/bin\/replicat PARAMFILE/ && !/awk/ {print $1; exit}')
    [ -n "$pid" ] || { echo "processo replicat nao encontrado" >&2; exit 3; }
    t0=$(agora); docker exec gg-hub kill -9 "$pid"; t1=$(agora) ;;
esac
rs=; ex=; rp=
if [ "$ACAO" != kill ]; then
  for i in $(seq 1 1200); do
    e=$(stt extracts/EXTBDAD); r=$(stt replicats/REPBDAD); p=$(pidp replicats/REPBDAD)
    [ -z "$rs" ] && [ -n "$e$r" ] && rs=$(agora)
    [ -z "$ex" ] && [ "$e" = running ] && ex=$(agora)
    if [ -z "$rp" ] && [ "$r" = running ]; then
      if [ "$ACAO" != replicat ] || { [ -n "$p" ] && [ "$p" != "$old" ]; }; then rp=$(agora); fi
    fi
    [ -n "$ex" ] && [ -n "$rp" ] && break
    sleep 0.25
  done
fi
printf '{"acao":"%s","t_cmd0":%s,"t_kill":%s,"t_cmd1":%s,"t_rest":%s,"t_ext":%s,"t_rep":%s}\n' "$ACAO" "$t0" "${tk:-null}" "$t1" "${rs:-null}" "${ex:-null}" "${rp:-null}"
REMOTO
)
{ printf 'S=%q\nACAO=%q\nALVO=%q\n' "$(cat "$HOME/.gg-hub-senha")" "$ACAO" "$ALVO"; printf '%s\n' "$corpo"; } | ssh_poc infra-server bash -s
