#!/usr/bin/env bash
# Helpers da REST API do GoldenGate Microservices (gg-hub). A senha vem de ~/.gg-hub-senha (nunca do repo).
GG_HOST=${GG_HOST:-192.168.0.11}
GG_PORTA=${GG_HUB_PORTA:-18443}
GG_ADMIN=${GG_ADMIN:-oggadmin}
GG_DEP=${GG_DEP:-Local}
GG_BASE="https://$GG_HOST:$GG_PORTA/services/$GG_DEP/adminsrvr/v2"
GG_SENHA_ARQ=${GG_SENHA_ARQ:-$HOME/.gg-hub-senha}
# gg METODO CAMINHO [JSON]  (corpo opcional; arquivo com @arquivo ou string JSON)
gg() {
  local m=$1 p=$2; shift 2
  local args=(-sk -u "$GG_ADMIN:$(cat "$GG_SENHA_ARQ")" -X "$m" "$GG_BASE$p")
  if [[ $# -gt 0 ]]; then args+=(-H 'Content-Type: application/json' -d "$1"); fi
  curl "${args[@]}"
}
# ggmsg: imprime so as mensagens da resposta
ggmsg() { jq -r '(.messages // [])[] | "\(.severity) \(.code) \(.title)"'; }
# prm_json arquivo.prm -> array JSON com as linhas do param file (sem comentarios -- nem vazias)
prm_json() { grep -vE '^\s*(--.*)?$' "$1" | jq -R . | jq -s .; }
