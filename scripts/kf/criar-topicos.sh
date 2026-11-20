#!/usr/bin/env bash
# Cria os topicos do lab (auto-criacao desligada no broker): 16 particoes por tabela (paridade com ORA_HASH(codigo,15)),
# 1 particao para o topico de transacoes do Debezium e para o historico de schema.
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
PARTICOES=${KF_PARTICOES:-16}
kt() { ssh_poc infra-server "docker exec kf-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 $*"; }
for t in TITULAR OBRA_MUSICAL FONOGRAMA; do
  kt "--create --if-not-exists --topic kf.BDAD.$t --partitions $PARTICOES --replication-factor 1"
done
kt "--create --if-not-exists --topic kf.transaction --partitions 1 --replication-factor 1"
# O historico de schema do Debezium exige exatamente 1 particao e retencao infinita.
kt "--create --if-not-exists --topic kf.schema-history --partitions 1 --replication-factor 1 --config retention.ms=-1"
kt "--list"
