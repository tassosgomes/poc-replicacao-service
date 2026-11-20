#!/usr/bin/env bash
# Remove SO o lab Kafka: containers kf-legado, kf-novo, kf-kafka, kf-connect, volumes kf-legado, kf-novo, kf-kafka e rede kf-rede
# (nomes exatos; nada de prune). Depois compara o inventario com o de scripts/kf/subir.sh: poc-*, gg-* e o resto iguais.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
if [[ -f $KF_STATE ]]; then base=$KF_STATE
else
  echo 'Aviso: inventario inicial ausente; usando o estado atual sem kf- como referencia.' >&2
  base=$(mktemp); inventario_kf > "$base"
fi
actual=$(mktemp); trap 'rm -f "$actual"; [[ $base == "$KF_STATE" ]] || rm -f "$base"' EXIT
ssh_poc desenv-server 'docker rm -fv kf-legado >/dev/null 2>&1 || true; docker volume rm kf-legado >/dev/null 2>&1 || true'
ssh_poc infra-server 'docker rm -fv kf-connect kf-kafka kf-novo >/dev/null 2>&1 || true; docker rm -fv $(docker ps -aq --filter name=^/kf-worker) >/dev/null 2>&1 || true; docker volume rm kf-kafka kf-novo >/dev/null 2>&1 || true; docker network rm kf-rede >/dev/null 2>&1 || true'
# Containers kf-* restantes (kf-worker-N, kf-poc-*, kf-artefato-*; filtro por prefixo, nunca prune) e imagens kf-worker/kf-poc.
for host in "${KF_HOSTS[@]}"; do
  ssh_poc "$host" 'ids=$(docker ps -aq --filter name=^/kf-); [ -z "$ids" ] || docker rm -fv $ids >/dev/null 2>&1 || true
    imgs=$(docker images -q kf-worker kf-poc | sort -u); [ -z "$imgs" ] || docker rmi $imgs >/dev/null 2>&1 || true'
done
for host in "${KF_HOSTS[@]}"; do
  ssh_poc "$host" 'test -z "$(docker ps -aq --filter name=^/kf-; docker volume ls -q --filter name=^kf-; docker network ls -q --filter name=^kf-)"' || { echo "$host: sobraram recursos kf-" >&2; exit 1; }
done
inventario_kf > "$actual"
if diff -u <(grep -E ' (lab|labvol) ' "$base") <(grep -E ' (lab|labvol) ' "$actual"); then echo 'poc-* e gg-*: containers e volumes iguais ao inventario inicial.'
else echo 'poc-*/gg-* mudou; confira os outros labs.' >&2; exit 1; fi
if ! diff -u "$base" "$actual"; then echo 'Aviso: outros recursos mudaram desde o inventario inicial (nao sao poc-*, gg-* nem kf-).' >&2; else echo 'Inventario completo igual ao inicial.'; fi
rm -f "$KF_STATE"
echo 'Lab Kafka removido.'
