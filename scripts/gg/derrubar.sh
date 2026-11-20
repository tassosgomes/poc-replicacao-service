#!/usr/bin/env bash
# Remove SO o lab GoldenGate: containers gg-legado, gg-novo, gg-hub e volumes gg-legado, gg-novo, gg-hub, gg-hub-tmp
# (nomes exatos; nada de prune; rm -v leva junto o volume anonimo /u01/ogg/scripts do hub). Depois compara o inventario com o de scripts/gg/subir.sh: poc-* e o resto tem de estar iguais.
set -euo pipefail
source "$(dirname "$0")/comum-gg.sh"
if [[ -f $GG_STATE ]]; then base=$GG_STATE
else
  # Lab montado sem subir.sh (ex.: spike): o inventario "antes" e o estado atual sem os recursos gg-.
  echo 'Aviso: inventario inicial ausente; usando o estado atual sem gg- como referencia.' >&2
  base=$(mktemp); inventario_gg > "$base"
fi
actual=$(mktemp); trap 'rm -f "$actual"; [[ $base == "$GG_STATE" ]] || rm -f "$base"' EXIT
ssh_poc desenv-server 'docker rm -fv gg-legado >/dev/null 2>&1 || true; docker volume rm gg-legado >/dev/null 2>&1 || true'
ssh_poc infra-server 'docker rm -fv gg-hub gg-novo >/dev/null 2>&1 || true; docker volume rm gg-hub gg-hub-tmp gg-novo >/dev/null 2>&1 || true'
for host in "${GG_HOSTS[@]}"; do
  ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/gg-; docker volume ls -q --filter name=^gg-)"' || { echo "$host: sobraram recursos gg-" >&2; exit 1; }
done
inventario_gg > "$actual"
# poc-* (containers, volumes e identidade) e o restante: diferenca em poc-* e fatal; em outros, so aviso (outros servicos mudam).
if diff -u <(grep -E ' (poc|pocvol) |[cv] poc-' "$base") <(grep -E ' (poc|pocvol) |[cv] poc-' "$actual"); then echo 'poc-legado/poc-novo: containers e volumes iguais ao inventario inicial.'
else echo 'poc-* mudou; confira o lab Java.' >&2; exit 1; fi
if ! diff -u "$base" "$actual"; then echo 'Aviso: outros recursos mudaram desde o inventario inicial (nao sao poc-* nem gg-).' >&2; else echo 'Inventario completo igual ao inicial.'; fi
rm -f "$GG_STATE"
echo 'Lab GG removido.'
