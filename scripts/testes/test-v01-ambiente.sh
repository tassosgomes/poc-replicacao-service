#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../comum.sh"
cd "$ROOT"
tmp=$(mktemp -d)
started=false
cleanup() {
  ssh_poc desenv-server 'docker rm -f poc-teste-porta >/dev/null 2>&1 || true' || true
  if [[ $started == true && -f $STATE ]]; then "$ROOT/scripts/derrubar.sh" || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT
[[ ! -e $STATE ]] || { echo 'Inventário da PoC já existe.' >&2; exit 1; }
for host in desenv-server infra-server; do
  ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/poc-; docker volume ls -q --filter name=^poc-; docker network ls -q --filter name=^poc-)"'
done
{ inventario desenv-server; inventario infra-server; } > "$tmp/before"
ssh_poc desenv-server 'docker pull gvenzl/oracle-free:23-slim-faststart >/dev/null && docker run -d --name poc-teste-porta -p 1521:1521 --entrypoint sleep gvenzl/oracle-free:23-slim-faststart 600 >/dev/null'
if "$ROOT/scripts/subir.sh" > "$tmp/porta" 2>&1; then echo 'Porta ocupada aceita' >&2; exit 1; fi
grep -q '1521' "$tmp/porta"
for host in desenv-server infra-server; do ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/poc-legado$ --filter name=^/poc-novo$)"'; done
ssh_poc desenv-server 'docker rm -f poc-teste-porta >/dev/null'
echo '2/6 porta ocupada'
started=true
"$ROOT/scripts/subir.sh" | tee "$tmp/subir"
grep -q 'titular=200000 obra_musical=500000 fonograma=1000000' "$tmp/subir"
for pair in desenv-server:legado infra-server:novo; do
  host=${pair%:*}; role=${pair#*:}
  [[ $(ssh_poc "$host" "docker inspect --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' poc-$role") == '2000000000 3221225472' ]]
done
echo '1/6 contagens e limites'
old=$(ssh_poc desenv-server 'docker inspect --format "{{.Id}}" poc-legado'); new=$(ssh_poc infra-server 'docker inspect --format "{{.Id}}" poc-novo')
# Aplique volumes menores sem alterar o arquivo versionado.
sql desenv-server poc-legado bdad < "$ROOT/sql/legado/01_schema.sql"
sql infra-server poc-novo replica < "$ROOT/sql/novo/01_schema.sql"
sed -e 's/define n_titular = 200000/define n_titular = 1000/' -e 's/define n_obra = 500000/define n_obra = 2000/' -e 's/define n_fonograma = 1000000/define n_fonograma = 4000/' "$ROOT/sql/legado/02_massa.sql" | sql desenv-server poc-legado bdad
[[ $old == "$(ssh_poc desenv-server 'docker inspect --format "{{.Id}}" poc-legado')" ]]
[[ $new == "$(ssh_poc infra-server 'docker inspect --format "{{.Id}}" poc-novo')" ]]
echo '3/6 reaplicação preserva IDs'
printf 'whenever sqlerror exit sql.sqlcode\nset heading off feedback off pages 0\nselect (select count(*) from titular)||'\'','\''||(select count(*) from obra_musical)||'\'','\''||(select count(*) from fonograma)||'\'','\''||(select count(*) from fonograma f where not exists (select 1 from obra_musical o where o.codigo=f.obra_codigo)) from dual;\nexit\n' | sql desenv-server poc-legado bdad > "$tmp/count"
grep -q '1000,2000,4000,0' "$tmp/count"
echo '4/6 massa reduzida'
"$ROOT/scripts/derrubar.sh"
started=false
{ inventario desenv-server; inventario infra-server; } > "$tmp/after"
diff -u "$tmp/before" "$tmp/after"
for host in desenv-server infra-server; do ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/poc-; docker volume ls -q --filter name=^poc-; docker network ls -q --filter name=^poc-)"'; done
echo '5/6 inventário e limpeza'
git check-ignore -q .env
if git ls-files --error-unmatch .env >/dev/null 2>&1; then exit 1; fi
# O transporte dos esquemas usa stdin de arquivos SQL, sem cópia de arquivos.
! rg -n '(^|[[:space:]])(scp|rsync)([[:space:]]|$)' scripts/subir.sh scripts/aplicar-esquema.sh scripts/comum.sh
echo '6/6 .env ignorado e não copiado'
