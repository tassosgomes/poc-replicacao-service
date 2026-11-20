#!/usr/bin/env bash
# Aplica um arquivo de parametros ao Extract/Replicat ja criado (PATCH config via REST), parando e religando o processo.
# O Replicat retoma do checkpoint (o AFTERCSN so vale no primeiro START). Uso: gg/aplicar-params.sh EXTBDAD|REPBDAD|REPCO arquivo.prm
set -euo pipefail
F0=${2:?arquivo .prm}; [[ -s $F0 ]] || { echo "arquivo inexistente: $F0" >&2; exit 2; }; F0=$(realpath "$F0"); cd "$(dirname "$0")"; source ./lib.sh
P=${1:?processo}; F=$F0
case $P in EXT*) K=extracts ;; *) K=replicats ;; esac
st() { gg GET /$K/$P/info/status | jq -r .response.status; }
gg POST /commands/execute "{\"name\":\"stop\",\"processName\":\"$P\"}" | ggmsg
for i in $(seq 40); do [[ $(st) == stopped ]] && break; sleep 1; done
gg PATCH /$K/$P "$(jq -n --argjson c "$(prm_json "$F")" '{config:$c}')" | ggmsg
gg POST /commands/execute "{\"name\":\"start\",\"processName\":\"$P\"}" | ggmsg
for i in $(seq 60); do [[ $(st) == running ]] && { echo "$P running com $F"; exit 0; }; sleep 1; done
echo "$P nao subiu: $(st)" >&2; exit 1
