#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
tmp=$(mktemp -d)
criados=0
consulta() { printf '%s\n' 'whenever sqlerror exit failure' 'whenever oserror exit failure' 'set heading off feedback off pagesize 0 linesize 200' "$1" 'exit' | sql infra-server poc-novo replica; }
limpar() {
  status=$?
  if (( criados )); then
    if ! consulta $'delete from titular where codigo between -8000001700001 and -8000000000001;\ncommit;' >/dev/null; then
      echo 'Falha ao remover dados temporários do teste V-08' >&2
      status=1
    fi
  fi
  rm -rf "$tmp"
  exit "$status"
}
trap limpar EXIT
ocupados=$(consulta 'select count(*) from titular where codigo between -8000001700001 and -8000000000001;' | tr -d '[:space:]')
[[ $ocupados == 0 ]] || { echo 'Faixa reservada ao teste já está ocupada' >&2; exit 1; }
total=$(consulta 'select (select count(*) from titular)+(select count(*) from obra_musical)+(select count(*) from fonograma) from dual;' | tr -d '[:space:]')
[[ $total =~ ^[0-9]+$ ]] || { echo 'Contagem inválida' >&2; exit 1; }
quantidade=$((1700000 - total))
(( quantidade > 0 )) || quantidade=1
criados=1
consulta "$(printf 'insert into titular (id,codigo) select sys_guid(), -8000000000000-level from dual connect by level <= %s;\ncommit;' "$quantidade")" >/dev/null
bash "$ROOT/scripts/snapshot-ids.sh" "$tmp/antes"
bash "$ROOT/scripts/snapshot-ids.sh" "$tmp/igual"
bash "$ROOT/scripts/comparar-snapshots.sh" "$tmp/antes" "$tmp/igual" > "$tmp/diff"
[[ $(wc -l < "$tmp/diff") == 1 ]]
printf '1/4 snapshots iguais: nenhuma diferença\n'
consulta $'update titular set id=hextoraw(\x2711111111111111111111111111111111\x27) where codigo=-8000000000001;\ninsert into titular (id,codigo) values (sys_guid(),-8000001700001);\ncommit;' >/dev/null
bash "$ROOT/scripts/snapshot-ids.sh" "$tmp/depois"
status=0
bash "$ROOT/scripts/comparar-snapshots.sh" "$tmp/antes" "$tmp/depois" > "$tmp/diff" || status=$?
[[ $status == 1 ]]
awk -F, '$1=="titular" && $2=="-8000000000001" && length($3)==32 && $3!=$4 && $4=="11111111111111111111111111111111" {ok=1} END {exit !ok}' "$tmp/diff"
printf '2/4 UUID alterado identificado\n'
[[ $(wc -l < "$tmp/diff") == 2 ]] && ! grep -q -- '-8000001700001' "$tmp/diff"
printf '3/4 linha nova ignorada\n'
linhas=$(sed -n 's/^linhas=//p' "$tmp/antes/medicao.txt")
(( linhas >= 1700000 ))
mkdir -p "$ROOT/.tsg-flow"
cp "$tmp/antes/medicao.txt" "$ROOT/.tsg-flow/v08-snapshot-medicao.txt"
printf '4/4 medição registrada: %s\n' "$(tr '\n' ' ' < "$tmp/antes/medicao.txt")"
