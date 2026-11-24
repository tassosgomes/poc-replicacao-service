#!/usr/bin/env bash
# Sobe kf-worker-N (worker Kafka, jar bootstrap-kafka) no infra-server, na kf-rede, com os limites do worker do lab Java
# (2 CPU / 1 GiB, memory-swap = memory, JVM -Xmx512m). A imagem kf-worker:<sha do jar> e eclipse-temurin:25-jre + o jar
# (docker cp + commit, sem instalar nada no host). Nunca toca em recursos que nao comecem por kf-.
# Uso: scripts/kf/subir-worker.sh N [VAR=VALOR ...]     (ex.: LEASE_SEG=12 LOTE_MAX=500 KAFKA_TOPIC_PREFIX=kf.BDAD)
# Padroes: KAFKA_BOOTSTRAP_SERVERS=kf-kafka:9092, NOVO_URL=kf-novo:1521, WORKER_ID=kf-worker-N, LEASE_SEG=12.
# Parar:   docker stop kf-worker-N / docker kill kf-worker-N (via ssh infra-server). Metricas: scripts/kf/coletar-worker.sh N DIR
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
N=${1:?numero do worker}; shift
JAR=${KF_WORKER_JAR:-$ROOT/worker/bootstrap-kafka/target/replicacao-worker-kafka-0.0.1-SNAPSHOT-exec.jar}
[[ -f $JAR ]] || { echo "jar ausente: $JAR (./mvnw -f worker/pom.xml package -DskipTests)" >&2; exit 1; }
HOST=infra-server; NAME=kf-worker-$N
SHA=$(sha256sum "$JAR" | cut -c1-16); IMG="kf-worker:$SHA"
ssh_poc $HOST "docker image inspect $IMG >/dev/null 2>&1" || {
  ssh_poc $HOST "docker image inspect eclipse-temurin:25-jre >/dev/null 2>&1 || docker pull eclipse-temurin:25-jre >/dev/null"
  ssh_poc $HOST "docker create --name kf-artefato-$SHA --network none --cpus=2 --memory=1g eclipse-temurin:25-jre true >/dev/null"
  tar -cf - --transform 's,.*,app.jar,' -C "$(dirname "$JAR")" "$(basename "$JAR")" | ssh_poc $HOST "docker cp - kf-artefato-$SHA:/"
  ssh_poc $HOST "docker commit kf-artefato-$SHA $IMG >/dev/null; docker rm kf-artefato-$SHA >/dev/null"
}
ENVS=(-e KAFKA_BOOTSTRAP_SERVERS=kf-kafka:9092 -e NOVO_URL=jdbc:oracle:thin:@//kf-novo:1521/FREEPDB1 -e WORKER_ID=$NAME
  -e LEASE_SEG=12 -e RESULTADOS_DIR=/resultados)
for v in "$@"; do ENVS+=(-e "$v"); done   # os ultimos -e vencem
# KF_ESPERAR_ATE=<epoch do infra-server>: espera esse instante (relogio do proprio infra-server) antes do docker run; KF_TEMPOS_ARQ=<arquivo>: grava "t0 t1" (epochs do
# infra-server antes e depois do docker run). Usados pelos cenarios de falha (C6/C7) para subir o worker substituto em instante agendado.
TT=$(ssh_poc $HOST "[ -z '${KF_ESPERAR_ATE:-}' ] || while :; do n=\$(date +%s.%N); awk -v n=\$n -v t='${KF_ESPERAR_ATE:-0}' 'BEGIN{exit !(n>=t)}' && break; sleep 0.02; done
t0=\$(date +%s.%N); docker run -d --name $NAME --network kf-rede --cpus=2 --memory=1g --memory-swap=1g ${ENVS[*]} $IMG java -Xmx512m -jar /app.jar worker >/dev/null; t1=\$(date +%s.%N); echo \$t0 \$t1")
[[ -z ${KF_TEMPOS_ARQ:-} ]] || echo "$TT" > "$KF_TEMPOS_ARQ"
ssh_poc $HOST "docker inspect --format '{{.Name}} NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}} Swap={{.HostConfig.MemorySwap}} imagem=$IMG' $NAME"
