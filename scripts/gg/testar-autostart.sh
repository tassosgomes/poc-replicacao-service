#!/usr/bin/env bash
# Testa autostart/autorestart do gg-hub: scripts/gg/testar-autostart.sh restart|kill [CODIGO_INICIAL]
#  restart: `docker restart gg-hub` com 100 updates no legado (um a cada ~0,1 s) cobrindo a queda.
#  kill:    `docker kill gg-hub`, 100 updates com o hub parado, `docker start gg-hub`.
# Mede, no proprio infra-server (relogio NTP, sem o relogio do WSL2), o tempo entre o retorno de
# docker start/restart e REST respondendo, EXTBDAD RUNNING e REPBDAD RUNNING. Depois confere se os 100
# updates foram aplicados exatamente uma vez (aplicacao_log) e se nome origem = nome destino.
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
modo=${1:?restart|kill}; ini=${2:-$([[ $modo == kill ]] && echo 700 || echo 600)}
fim=$((ini + 99)); tag="auto-$modo-$ini"
[[ -r $HOME/.gg-hub-senha ]] || { echo 'falta ~/.gg-hub-senha' >&2; exit 1; }
D="$ROOT/sql/gg/testes"
orig() { ssh_poc desenv-server 'docker exec -i gg-legado sqlplus -s bdad/bdad@localhost:1521/FREEPDB1'; }
dest() { ssh_poc infra-server 'docker exec -i gg-novo sqlplus -s replica/replica@localhost:1521/FREEPDB1'; }
updates=$(cat <<SQL
begin
  for i in $ini .. $fim loop
    update titular set nome = '$tag ' || i where codigo = i;
    commit;
    dbms_session.sleep($([[ $modo == restart ]] && echo 0.1 || echo 0));
  end loop;
end;
/
exit
SQL
)
# Roda no infra-server: espera os dois processos RUNNING (via REST local) e imprime os instantes.
medir='
st() { curl -sk -m 3 -u "oggadmin:$S" "https://localhost:18443/services/Local/adminsrvr/v2/$1" | grep -o "\"status\":\"[a-z]*\"" | head -1 | cut -d\" -f4; }
agora() { date +%s.%N; }
t0=$(agora); rest=; ext=; rep=
for i in $(seq 1 600); do
  e=$(st extracts/EXTBDAD); r=$(st replicats/REPBDAD)
  [[ -z $rest && ( -n $e || -n $r ) ]] && rest=$(agora)
  [[ -z $ext && $e == running ]] && ext=$(agora)
  [[ -z $rep && $r == running ]] && rep=$(agora)
  [[ -n $ext && -n $rep ]] && break
  sleep 1
done
awk -v t0=$t0 -v a=${rest:-0} -v b=${ext:-0} -v c=${rep:-0} "BEGIN{printf \"REST_ok=%.1fs EXTBDAD_RUNNING=%.1fs REPBDAD_RUNNING=%.1fs\n\", (a>0?a-t0:-1), (b>0?b-t0:-1), (c>0?c-t0:-1)}"
'
rodar_remoto() { { printf 'S=%q\n' "$(cat "$HOME/.gg-hub-senha")"; printf '%s\n' "$1"; } | ssh_poc infra-server bash -s; }
echo "== $modo: antes (so status)"; rodar_remoto "$medir"
if [[ $modo == restart ]]; then
  printf '%s\n' "$updates" | orig >/dev/null &
  up=$!
  sleep 1
  rodar_remoto "docker restart gg-hub >/dev/null; $medir" | sed 's/^/tempo desde o retorno de docker restart: /'
  wait $up
else
  rodar_remoto 'docker kill gg-hub >/dev/null'
  printf '%s\n' "$updates" | orig >/dev/null
  echo "100 updates feitos com o hub parado"
  rodar_remoto "docker start gg-hub >/dev/null; $medir" | sed 's/^/tempo desde o retorno de docker start: /'
fi
# espera o apply terminar (ate 120 s) e confere
for i in $(seq 1 60); do
  n=$(printf "set heading off feedback off\nselect count(*) from titular where nome like '$tag %%';\nexit\n" | dest | tr -d ' \r\n')
  [[ $n == 100 ]] && break; sleep 2
done
echo "destino com nome '$tag %': $n/100"
printf "set heading off feedback off\nselect 'aplicacao_log: codigos='||count(distinct codigo)||' linhas='||count(*)||' max_por_codigo='||max(c) from (select codigo, count(*) c from aplicacao_log where tabela='TITULAR' and op='U' and codigo between $ini and $fim group by codigo);\nexit\n" | dest
printf "set heading off feedback off\nselect nome||'|'||codigo from titular where codigo between $ini and $fim order by codigo;\nexit\n" | orig | tr -d '\r' | sort > "${TMPDIR:-/tmp}/o.$$"
printf "set heading off feedback off\nselect nome||'|'||codigo from titular where codigo between $ini and $fim order by codigo;\nexit\n" | dest | tr -d '\r' | sort > "${TMPDIR:-/tmp}/d.$$"
if diff -q "${TMPDIR:-/tmp}/o.$$" "${TMPDIR:-/tmp}/d.$$" >/dev/null; then echo "origem == destino nas 100 linhas"; else echo "DIVERGENCIA origem x destino"; fi
rm -f "${TMPDIR:-/tmp}/o.$$" "${TMPDIR:-/tmp}/d.$$"
