#!/usr/bin/env bash
# Roda o gerador da PoC (jar, subcomando `gerador`) num container kf-gerador-* no DESENV-SERVER (o mesmo host do kf-legado, como nos labs
# Java e GG) contra o kf-legado, com as variaveis padrao do C2 (THREADS 8, SEMENTE 1, PCT_INSERT 5, PCT_DELETE 1, HOT_PCT 10) e limites
# 2 CPU/1 GiB. Conexao por IP:porta publicada (192.168.0.5:1525). Amostra `docker stats` a cada ~3 s dos containers do lab Kafka nos dois
# hosts. GERADOR_HOST=infra-server roda o gerador no outro host (so para diagnostico; ver docs/spike-kafka-notas.md, Fase 2).
# Uso: scripts/kf/gerar.sh TAXA DURACAO_SEG DIR_SAIDA [VAR=VALOR ...]
# Saida em DIR_SAIDA: gerador.log, gerador-linhas.csv, recursos-<host>.csv. Imprime o resumo do gerador (taxa, VALIDO).
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
TAXA=${1:?TAXA}; DUR=${2:?DURACAO_SEG}; OUT=${3:?DIR_SAIDA}; shift 3
JAR=${POC_JAR:-$ROOT/poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar}
HOST=${GERADOR_HOST:-desenv-server}; IMG_BASE=eclipse-temurin:25-jre
mkdir -p "$OUT"
SHA=$(sha256sum "$JAR" | cut -c1-16); IMG="kf-poc:$SHA"
ssh_poc $HOST "docker image inspect $IMG >/dev/null 2>&1" || {
  ssh_poc $HOST "docker image inspect $IMG_BASE >/dev/null 2>&1 || docker pull $IMG_BASE >/dev/null"
  ssh_poc $HOST "docker create --name kf-artefato-$SHA --network none --cpus=2 --memory=1g $IMG_BASE true >/dev/null"
  tar -cf - --transform 's,.*,app.jar,' -C "$(dirname "$JAR")" "$(basename "$JAR")" | ssh_poc $HOST "docker cp - kf-artefato-$SHA:/"
  ssh_poc $HOST "docker commit kf-artefato-$SHA $IMG >/dev/null; docker rm kf-artefato-$SHA >/dev/null"
}
NAME="kf-gerador-$(date +%s)"
ENVS=(-e TAXA=$TAXA -e DURACAO_SEG=$DUR -e THREADS=${THREADS:-8} -e SEMENTE=${SEMENTE:-1} -e PCT_INSERT=${PCT_INSERT:-5}
  -e PCT_DELETE=${PCT_DELETE:-1} -e HOT_PCT=${HOT_PCT:-10} -e RESULTADOS_DIR=/resultados)
for v in "$@"; do ENVS+=(-e "$v"); done
amostra() { # host containers...  (nomes ausentes sao ignorados pelo docker stats)
  local h=$1; shift
  ssh_poc "$h" "end=\$((\$(date +%s)+$DUR+20)); while [ \$(date +%s) -lt \$end ]; do t=\$(date +%s.%N); docker stats --no-stream --format \"\$t,{{.Name}},{{.CPUPerc}},{{.MemUsage}}\" $* 2>/dev/null; sleep 3; done" > "$OUT/recursos-$h.csv" &
}
INFRA_NAMES="kf-novo kf-kafka kf-connect $(ssh_poc infra-server 'docker ps --format {{.Names}} --filter name=^/kf-worker' | tr '\n' ' ')"
amostra desenv-server kf-legado "$NAME"; P1=$!
amostra infra-server $INFRA_NAMES; P2=$!
sleep 2
ssh_poc $HOST "docker run -d --name $NAME --cpus=2 --memory=1g --memory-swap=1g ${ENVS[*]} \
 -e LEGADO_URL=jdbc:oracle:thin:@//$DESENV_IP:1525/FREEPDB1 -e NOVO_URL=jdbc:oracle:thin:@//$INFRA_IP:1526/FREEPDB1 \
 $IMG java -Xmx512m -jar /app.jar gerador" >/dev/null
trap 'ssh_poc $HOST "docker rm -f $NAME >/dev/null 2>&1" || true' EXIT
fim=$(( $(date +%s) + DUR + 180 ))
while [[ $(ssh_poc $HOST "docker inspect -f '{{.State.Running}}' $NAME" 2>/dev/null || echo true) == true ]]; do
  (( $(date +%s) > fim )) && { echo "gerador excedeu o tempo" >&2; exit 2; }; sleep 5; done
RC=$(ssh_poc $HOST "docker inspect -f '{{.State.ExitCode}}' $NAME")
ssh_poc $HOST "docker logs $NAME 2>&1" > "$OUT/gerador.log"
ssh_poc $HOST "docker cp $NAME:/resultados/. - " | tar -xO > "$OUT/gerador-linhas.csv" 2>/dev/null || true
wait $P1 $P2 || true
echo "container=$NAME exit=$RC"
grep -E "inicio_carga|fim_carga|linhas=" "$OUT/gerador.log"
