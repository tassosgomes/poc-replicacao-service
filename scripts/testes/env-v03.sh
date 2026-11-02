#!/usr/bin/env bash
# Uso: source scripts/testes/env-v03.sh
# Aponta LEGADO_URL/NOVO_URL para os containers poc-legado/poc-novo (portas 1521/1522 dos
# servidores, endereços resolvidos pelo ssh). Não lê nem imprime o .env.
_h() { ssh -G "$1" | awk '$1=="hostname"{print $2}'; }
LEGADO_URL="jdbc:oracle:thin:@//$(_h desenv-server):1521/FREEPDB1"
export LEGADO_URL
NOVO_URL="jdbc:oracle:thin:@//$(_h infra-server):1522/FREEPDB1"
export NOVO_URL
unset -f _h
