#!/usr/bin/env bash
# Roda o gerador da PoC (jar, subcomando `gerador`) num container gg-gerador-* no desenv-server contra o gg-legado,
# com as variaveis padrao do C2 (THREADS 8, SEMENTE 1, PCT_INSERT 5, PCT_DELETE 1, HOT_PCT 10) e limites 2 CPU/1 GiB.
# Conexao por IP:porta publicada (192.168.0.5:1523): nao existe gg-rede, o gg-legado esta na bridge padrao.
# Amostra `docker stats` a cada ~5 s dos containers do lab GG (e do gerador) nos dois hosts.
# Uso: scripts/gg/gerar.sh TAXA DURACAO_SEG DIR_SAIDA [VAR=VALOR ...]
# Saida em DIR_SAIDA: gerador.log, gerador-linhas.csv, recursos-<host>.csv. Imprime o resumo do gerador (taxa, VALIDO).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TAXA=${1:?TAXA}; DUR=${2:?DURACAO_SEG}; OUT=${3:?DIR_SAIDA}; shift 3
JAR=${POC_JAR:-$ROOT/poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar}
HOST=desenv-server; IMG_BASE=eclipse-temurin:25-jre
mkdir -p "$OUT"
SHA=$(sha256sum "$JAR" | cut -c1-16); IMG="gg-cenarios:$SHA"
ssh -o BatchMode=yes $HOST "docker image inspect $IMG >/dev/null 2>&1" || {
  ssh $HOST "docker create --name gg-artefato-$SHA --network none --cpus=2 --memory=1g $IMG_BASE true >/dev/null"
  tar -cf - --transform 's,.*,app.jar,' -C "$(dirname "$JAR")" "$(basename "$JAR")" | ssh $HOST "docker cp - gg-artefato-$SHA:/"
  ssh $HOST "docker commit gg-artefato-$SHA $IMG >/dev/null; docker rm gg-artefato-$SHA >/dev/null"
}
NAME="gg-gerador-$(date +%s)"
ENVS=(-e TAXA=$TAXA -e DURACAO_SEG=$DUR -e THREADS=${THREADS:-8} -e SEMENTE=${SEMENTE:-1} -e PCT_INSERT=${PCT_INSERT:-5}
  -e PCT_DELETE=${PCT_DELETE:-1} -e HOT_PCT=${HOT_PCT:-10} -e RESULTADOS_DIR=/resultados)
for v in "$@"; do ENVS+=(-e "$v"); done
# Amostrador de recursos (limitado no tempo; termina sozinho)
amostra() { # host containers...
  local h=$1; shift
  ssh -o BatchMode=yes "$h" "end=\$((\$(date +%s)+$DUR+20)); while [ \$(date +%s) -lt \$end ]; do t=\$(date +%s.%N); docker stats --no-stream --format \"\$t,{{.Name}},{{.CPUPerc}},{{.MemUsage}}\" $* 2>/dev/null; sleep 3; done" > "$OUT/recursos-$h.csv" &
}
amostra desenv-server gg-legado "$NAME"; P1=$!
amostra infra-server gg-novo gg-hub; P2=$!
sleep 2
ssh $HOST "docker run -d --name $NAME --cpus=2 --memory=1g --memory-swap=1g ${ENVS[*]} \
 -e LEGADO_URL=jdbc:oracle:thin:@//192.168.0.5:1523/FREEPDB1 -e NOVO_URL=jdbc:oracle:thin:@//192.168.0.11:1524/FREEPDB1 \
 $IMG java -Xmx512m -jar /app.jar gerador" >/dev/null
trap 'ssh $HOST "docker rm -f $NAME >/dev/null 2>&1" || true' EXIT
# polling em vez de `docker wait` (uma sessao ssh pendurada travou o wait por minutos depois do fim do container)
fim=$(( $(date +%s) + DUR + 180 ))
while [[ $(ssh -o BatchMode=yes -o ConnectTimeout=10 $HOST "docker inspect -f '{{.State.Running}}' $NAME" 2>/dev/null || echo true) == true ]]; do
  (( $(date +%s) > fim )) && { echo "gerador excedeu o tempo" >&2; exit 2; }; sleep 5; done
RC=$(ssh $HOST "docker inspect -f '{{.State.ExitCode}}' $NAME")
ssh $HOST "docker logs $NAME 2>&1" > "$OUT/gerador.log"
F=$(ssh $HOST "docker exec $NAME ls /resultados" 2>/dev/null | head -1 || true)
ssh $HOST "docker cp $NAME:/resultados/. - " | tar -xO > "$OUT/gerador-linhas.csv" 2>/dev/null || true
wait $P1 $P2 || true
echo "container=$NAME exit=$RC"
grep -E "inicio_carga|fim_carga|linhas=" "$OUT/gerador.log"
