#!/usr/bin/env bash
# Sobe o lab GoldenGate do zero, com a massa completa (200k/500k/1M): gg-legado, gg-novo, gg-hub, pipeline,
# carga inicial por SCN e Replicat com AFTERCSN. Nunca toca em poc-* (so le). Uso: scripts/gg/subir.sh
# Variaveis: GG_MASSA_REDUZIDA=1 (spike, 1k/2k/4k). Tempos em resultados/gg/c1/subir-tempos.json.
set -euo pipefail
source "$(dirname "$0")/comum-gg.sh"
cd "$ROOT"
source "$ROOT/gg/lib.sh"
fail() { printf 'Pre-requisito: %s\n' "$*" >&2; exit 1; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

pre_requisitos_rapidos || fail 'verifique .env ignorado, SSH, Docker e sincronizacao NTP'
[[ ! -e $GG_STATE ]] || fail 'inventario anterior existe; execute scripts/gg/derrubar.sh'
[[ -r $HOME/.gg-hub-senha ]] || fail 'falta ~/.gg-hub-senha'
for host in "${GG_HOSTS[@]}"; do
  ssh_poc "$host" 'awk "/MemAvailable/ {exit !(\$2 >= 4194304)}" /proc/meminfo' || fail "$host: RAM livre inferior a 4 GB"
  ssh_poc "$host" 'df -Pk "$(docker info --format "{{.DockerRootDir}}")" | awk "NR==2 {exit !(\$4 >= 10485760)}"' || fail "$host: disco livre inferior a 10 GB"
  ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/gg-; docker volume ls -q --filter name=^gg-)"' || fail "$host: recursos gg- ja existem; execute scripts/gg/derrubar.sh"
done
for pair in "${GG_PORTAS[@]}"; do
  host=${pair%:*}; port=${pair#*:}
  ssh_poc "$host" "! ss -H -lnt 'sport = :$port' | grep -q ." || fail "$host: porta $port ocupada; libere a porta e tente novamente"
done
# Lab Java: precisa estar PARADO e com volume e container intactos (so leitura).
for pair in desenv-server:legado infra-server:novo; do
  host=${pair%:*}; role=${pair#*:}
  [[ $(ssh_poc "$host" "docker inspect --format '{{.State.Running}}' poc-$role 2>/dev/null || echo ausente") == false ]] \
    || fail "$host: poc-$role precisa existir e estar PARADO (docker stop poc-$role); nada foi alterado"
  ssh_poc "$host" "docker volume inspect poc-$role >/dev/null 2>&1" || fail "$host: volume poc-$role nao existe"
done
read -r delta _ < <(medir_relogios)
(( delta < 0 )) && delta=$((-delta))
(( delta <= 100000000 )) || fail 'diferenca de relogios acima de 100 ms; sincronize os servidores'
for host in "${GG_HOSTS[@]}"; do
  ssh_poc "$host" "docker image inspect '$ORA_IMG' >/dev/null 2>&1 || docker pull '$ORA_IMG' >/dev/null" || fail "$host: nao foi possivel obter $ORA_IMG"
done
ssh_poc infra-server "docker image inspect '$GG_HUB_IMG' >/dev/null 2>&1" || fail "infra-server: imagem $GG_HUB_IMG ausente (o pull exige login no container-registry.oracle.com)"

mkdir -p "$(dirname "$GG_STATE")" "$ROOT/resultados/gg/c1"
inventario_gg > "$GG_STATE"
log "inventario salvo em $GG_STATE ($(wc -l < "$GG_STATE") linhas); poc-* parados e intactos"

T0=$(agora)
log "criando gg-legado e gg-novo (2 CPU / 3 GiB)"
for pair in desenv-server:legado:1523:bdad infra-server:novo:1524:replica; do
  IFS=: read -r host role port user <<< "$pair"
  ssh_poc "$host" "docker volume create gg-$role >/dev/null; docker run -d --name gg-$role --cpus=2 --memory=3g -p $port:1521 -v gg-$role:/opt/oracle/oradata -e ORACLE_PASSWORD=poc-oracle -e APP_USER=$user -e APP_USER_PASSWORD=$user --health-cmd healthcheck.sh --health-interval 10s --health-timeout 5s --health-retries 60 $ORA_IMG >/dev/null"
done
for pair in desenv-server:legado infra-server:novo; do
  host=${pair%:*}; role=${pair#*:}
  healthy=false
  for ((i=0;i<180;i++)); do
    [[ $(ssh_poc "$host" "docker inspect --format '{{.State.Health.Status}}' gg-$role") == healthy ]] && { healthy=true; break; }
    sleep 5
  done
  [[ $healthy == true ]] || fail "$host: gg-$role nao ficou saudavel"
done
T1=$(agora); log "bancos saudaveis em $(dur "$T0" "$T1") s"

if [[ ${GG_MASSA_REDUZIDA:-0} == 1 ]]; then log 'esquema e massa REDUZIDA no legado'; else log 'esquema e massa completa (200k/500k/1M) no legado'; fi
"$ROOT/scripts/gg/aplicar-esquema-legado.sh"
T2=$(agora); log "legado pronto em $(dur "$T1" "$T2") s"
"$ROOT/scripts/gg/aplicar-esquema-novo.sh"
T3=$(agora); log "destino pronto em $(dur "$T2" "$T3") s"

log "subindo gg-hub"
"$ROOT/scripts/gg/subir-hub.sh"
healthy=false
for ((i=0;i<60;i++)); do
  [[ $(ssh_poc infra-server "docker inspect --format '{{.State.Health.Status}}' gg-hub") == healthy ]] && { healthy=true; break; }
  sleep 5
done
[[ $healthy == true ]] || fail 'gg-hub nao ficou saudavel'
for ((i=0;i<30;i++)); do gg GET /extracts | jq -e .response >/dev/null 2>&1 && break; sleep 2; done
T4=$(agora); log "hub saudavel e REST ok em $(dur "$T3" "$T4") s"

# status de um processo (extracts|replicats NOME)
status() { gg GET "/$1/$2" | jq -r '.response.status // "?"'; }
aguardar() { # tipo nome estado timeout
  for ((i=0;i<$4;i++)); do [[ $(status "$1" "$2") == "$3" ]] && return 0; sleep 1; done; return 1
}
log "pipeline: credenciais, trandata, heartbeat, Extract e Replicat (parado)"
"$ROOT/gg/criar-pipeline.sh" base
aguardar extracts EXTBDAD running 120 || fail 'EXTBDAD nao ficou RUNNING'
T5=$(agora); log "EXTBDAD RUNNING em $(dur "$T4" "$T5") s"
CSN_EXT=$(gg GET /extracts/EXTBDAD | jq -r '.response.registration.csn')

log "carga inicial por SCN"
LOG_CARGA=$(mktemp); trap 'rm -f "$LOG_CARGA"' EXIT
SCN=$("$ROOT/scripts/gg/carga-inicial.sh" 2> "$LOG_CARGA")
T6=$(agora); log "carga inicial concluida em $(dur "$T5" "$T6") s (SCN=$SCN, SCN de registro do Extract=$CSN_EXT)"
cat "$LOG_CARGA"
[[ $SCN =~ ^[0-9]+$ ]] && (( SCN >= CSN_EXT )) || fail "SCN da carga ($SCN) anterior ao registro do Extract ($CSN_EXT)"

"$ROOT/gg/criar-pipeline.sh" replicat "$SCN"
aguardar replicats REPBDAD running 120 || fail 'REPBDAD nao ficou RUNNING'
T7=$(agora); log "REPBDAD RUNNING (AFTERCSN $SCN); pipeline RUNNING em $(dur "$T4" "$T7") s desde o hub pronto"

log "contagens"
printf 'set heading off feedback off pages 0\nselect '\''titular='\''||(select count(*) from titular)||'\'' obra_musical='\''||(select count(*) from obra_musical)||'\'' fonograma='\''||(select count(*) from fonograma) from dual;\nexit\n' > "$LOG_CARGA.q"
echo -n 'legado: '; sql desenv-server gg-legado bdad < "$LOG_CARGA.q" | tr -s '\n'
echo -n 'novo:   '; sql infra-server gg-novo replica < "$LOG_CARGA.q" | tr -s '\n'
rm -f "$LOG_CARGA.q"
echo "processos: EXTBDAD=$(status extracts EXTBDAD) REPBDAD=$(status replicats REPBDAD)"
for pair in desenv-server:gg-legado infra-server:gg-novo infra-server:gg-hub; do
  ssh_poc "${pair%:*}" "docker inspect --format '{{.Name}} NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}' ${pair#*:}"
done
jq -n --arg massa "$([[ ${GG_MASSA_REDUZIDA:-0} == 1 ]] && echo reduzida)" --argjson bancos "$(dur "$T0" "$T1")" --argjson legado "$(dur "$T1" "$T2")" \
  --argjson novo "$(dur "$T2" "$T3")" --argjson hub "$(dur "$T3" "$T4")" --argjson extract "$(dur "$T4" "$T5")" \
  --argjson carga "$(dur "$T5" "$T6")" --argjson replicat "$(dur "$T6" "$T7")" --argjson pipeline "$(dur "$T4" "$T7")" --argjson total "$(dur "$T0" "$T7")" --arg scn "$SCN" \
  '{massa:(if $massa=="" then "completa" else $massa end), scn_carga:$scn, segundos:{bancos_saudaveis:$bancos, esquema_e_massa_legado:$legado, esquema_novo:$novo, hub_saudavel:$hub, extract_running:$extract, carga_inicial:$carga, replicat_running:$replicat, pipeline_running_desde_hub:$pipeline, total:$total}, relogio:"desenv-server (NTP), nao o do WSL2"}' > "$ROOT/resultados/gg/c1/subir-tempos.json"
log "tempos em resultados/gg/c1/subir-tempos.json; total $(dur "$T0" "$T7") s"
