#!/usr/bin/env bash
# Queda (SIGKILL) e reinicio do Kafka Connect ou do broker do lab Kafka no infra-server, com tempos do relogio do PROPRIO infra-server (NTP;
# nunca o do WSL2). Uso: scripts/kf/infra-evento.sh connect|broker ALVO_EPOCH_INFRA [PAUSA_SEG=60]
#   Espera ALVO (epoch do infra-server), `docker kill -s KILL kf-connect|kf-kafka`, aguarda PAUSA_SEG (como o C6: queda em 180 s, reinicio em 240 s),
#   `docker start`, e mede: t_rest (REST do Connect responde / porta 9092 do broker aceita), t_ext (tarefa do conector kf-oracle RUNNING) e
#   amostra o estado da tarefa a cada ~2 s por 180 s apos o start (campo `estados`).
#   Se a tarefa ficar FAILED, NAO intervem: so registra (a intervencao manual, se necessaria, e decidida por quem analisa).
# Saida: uma linha JSON {acao,t_cmd0,t_kill,t_start0,t_cmd1,t_rest,t_ext,estados:[...]} com epochs do infra-server. So toca em kf-connect/kf-kafka.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
ALVO_C=${1:?connect|broker}; ALVO=${2:?ALVO_EPOCH_INFRA}; PAUSA=${3:-60}
case $ALVO_C in connect) CT=kf-connect ;; broker) CT=kf-kafka ;; *) echo "alvo invalido" >&2; exit 2 ;; esac
corpo=$(cat <<'REMOTO'
agora() { date +%s.%N; }
rest() { curl -s -m 2 localhost:18083/connectors/kf-oracle/status 2>/dev/null || true; }
tarefa() { rest | grep -o '"tasks":\[{[^]]*' | grep -o '"state":"[A-Z]*"' | head -1 | cut -d'"' -f4 || true; }
porta() { docker exec kf-kafka bash -c 'exec 3<>/dev/tcp/127.0.0.1/9092' >/dev/null 2>&1 && echo ok || true; }
while :; do n=$(agora); awk -v n="$n" -v t="$ALVO" 'BEGIN{exit !(n>=t)}' && break; sleep 0.02; done
t0=$(agora); docker kill -s KILL "$CT" >/dev/null; tk=$(agora)
sleep "$PAUSA"
ts=$(agora); docker start "$CT" >/dev/null; t1=$(agora)
rs=; ex=; est=
for i in $(seq 1 90); do
  now=$(agora)
  if [ "$ACAO" = connect ]; then [ -z "$rs" ] && [ -n "$(rest)" ] && rs=$now; else [ -z "$rs" ] && [ -n "$(porta)" ] && rs=$now; fi
  st=$(tarefa); [ -n "$st" ] || st=-
  est="$est${est:+,}{\"t\":$now,\"estado\":\"$st\"}"
  [ -z "$ex" ] && [ "$st" = RUNNING ] && [ -n "$rs" ] && ex=$now
  sleep 2
done
printf '{"acao":"%s","t_cmd0":%s,"t_kill":%s,"t_start0":%s,"t_cmd1":%s,"t_rest":%s,"t_ext":%s,"estados":[%s]}\n' "$ACAO" "$t0" "$tk" "$ts" "$t1" "${rs:-null}" "${ex:-null}" "$est"
REMOTO
)
{ printf 'ACAO=%q\nCT=%q\nALVO=%q\nPAUSA=%q\n' "$ALVO_C" "$CT" "$ALVO" "$PAUSA"; printf '%s\n' "$corpo"; } | ssh_poc infra-server bash -s
