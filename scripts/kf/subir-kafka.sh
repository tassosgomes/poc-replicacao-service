#!/usr/bin/env bash
# Sobe kf-kafka (Apache Kafka KRaft, 1 broker) e kf-connect (Kafka Connect + Debezium Oracle) no infra-server.
# Requer a rede kf-rede. Portas publicadas: 19092 (Kafka, para clientes fora da rede) e 18083 (REST do Connect).
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
ssh_poc infra-server "docker volume create kf-kafka >/dev/null; docker run -d --name kf-kafka --network kf-rede --cpus=2 --memory=2g -p 19092:19092 -v kf-kafka:/var/lib/kafka/data \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=INTERNAL://:9092,EXTERNAL://:19092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=INTERNAL://kf-kafka:9092,EXTERNAL://$INFRA_IP:19092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kf-kafka:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false \
  -e KAFKA_HEAP_OPTS='-Xms1g -Xmx1g' \
  --health-cmd '/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1' --health-interval 10s --health-timeout 10s --health-retries 30 \
  $KAFKA_IMG >/dev/null"
for ((i=0;i<60;i++)); do
  [[ $(ssh_poc infra-server "docker inspect --format '{{.State.Health.Status}}' kf-kafka") == healthy ]] && break; sleep 3
done
# Connect: JSON sem schema (key e value); topicos internos com fator de replicacao 1 (um broker).
ssh_poc infra-server "docker run -d --name kf-connect --network kf-rede --cpus=2 --memory=2g -p 18083:8083 \
  -e BOOTSTRAP_SERVERS=kf-kafka:9092 -e GROUP_ID=kf-connect \
  -e CONFIG_STORAGE_TOPIC=kf-connect-configs -e OFFSET_STORAGE_TOPIC=kf-connect-offsets -e STATUS_STORAGE_TOPIC=kf-connect-status \
  -e CONFIG_STORAGE_REPLICATION_FACTOR=1 -e OFFSET_STORAGE_REPLICATION_FACTOR=1 -e STATUS_STORAGE_REPLICATION_FACTOR=1 \
  -e KEY_CONVERTER=org.apache.kafka.connect.json.JsonConverter -e VALUE_CONVERTER=org.apache.kafka.connect.json.JsonConverter \
  -e CONNECT_KEY_CONVERTER_SCHEMAS_ENABLE=false -e CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE=false \
  -e KAFKA_HEAP_OPTS='-Xms512m -Xmx1g' \
  --health-cmd 'curl -sf localhost:8083/connectors >/dev/null' --health-interval 10s --health-timeout 5s --health-retries 30 \
  $CONNECT_IMG >/dev/null"
for ((i=0;i<60;i++)); do
  [[ $(ssh_poc infra-server "docker inspect --format '{{.State.Health.Status}}' kf-connect") == healthy ]] && exit 0; sleep 3
done
echo 'kf-connect nao ficou saudavel' >&2; exit 1
