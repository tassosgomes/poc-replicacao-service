#!/usr/bin/env bash
# Roda um subcomando do jar da PoC (carga-inicial, reconciliacao, gerador) num container kf-poc-<ts> contra o lab Kafka, e
# remove o container. Imagem kf-poc:<sha> (temurin + jar). Legado por IP:porta publicada (192.168.0.5:1525), destino por
# IP (192.168.0.11:1526). Limites 2 CPU / 1 GiB, -Xmx512m. Saida do container no stdout.
# Uso: scripts/kf/poc.sh HOST SUBCOMANDO [VAR=VALOR ...]     HOST = desenv-server | infra-server
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
HOST=${1:?host}; CMD=${2:?subcomando}; shift 2
JAR=${POC_JAR:-$ROOT/poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar}
SHA=$(sha256sum "$JAR" | cut -c1-16); IMG="kf-poc:$SHA"
ssh_poc $HOST "docker image inspect $IMG >/dev/null 2>&1" || {
  ssh_poc $HOST "docker create --name kf-artefato-$SHA --network none --cpus=2 --memory=1g eclipse-temurin:25-jre true >/dev/null"
  tar -cf - --transform 's,.*,app.jar,' -C "$(dirname "$JAR")" "$(basename "$JAR")" | ssh_poc $HOST "docker cp - kf-artefato-$SHA:/"
  ssh_poc $HOST "docker commit kf-artefato-$SHA $IMG >/dev/null; docker rm kf-artefato-$SHA >/dev/null"
}
NAME="kf-poc-$CMD-$(date +%s)"
ENVS=(-e LEGADO_URL=jdbc:oracle:thin:@//$DESENV_IP:1525/FREEPDB1 -e NOVO_URL=jdbc:oracle:thin:@//$INFRA_IP:1526/FREEPDB1 -e RESULTADOS_DIR=/resultados)
for v in "$@"; do ENVS+=(-e "$v"); done
trap 'ssh_poc $HOST "docker rm -f $NAME >/dev/null 2>&1" || true' EXIT
ssh_poc $HOST "docker run -d --name $NAME --cpus=2 --memory=1g --memory-swap=1g ${ENVS[*]} $IMG java -Xmx512m -jar /app.jar $CMD" >/dev/null
while [[ $(ssh_poc $HOST "docker inspect -f '{{.State.Running}}' $NAME" 2>/dev/null || echo true) == true ]]; do sleep 3; done
ssh_poc $HOST "docker logs $NAME 2>&1"
exit "$(ssh_poc $HOST "docker inspect -f '{{.State.ExitCode}}' $NAME")"
