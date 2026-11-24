# Plano: PoC parte 3, alternativa A (CDC + Kafka + worker)

Status (2026-11-24): Fases 0, 1 e 2 **concluidas**; **Fase 3 concluida (C1 a C10)** com os mesmos numeros e criterios dos labs Java/GG: C1, C3, C4, C6, C7 (failover 14,4 s), C8 e C10 (+0,61 ms, +10,3%)
APROVADOS; C2 (p95 6,43 s) e C5 (p95 5,59 s) **REPROVADOS** pela latencia de captura do Debezium/LogMiner (nenhuma variante de configuracao a corrige, ver notas); C9 descritivo. Extras: queda do Connect e do broker
(com `scheduled.rebalance.max.delay.ms=0` a captura volta em 111 s em vez de 346 s). Comparativo: `resultados/comparativo-java-gg-kafka.md`. **Fase 4 (variante transacional) concluida em 2026-11-24**: modo atual mostra estado parcial em 86% das transacoes multi-tabela, o transacional em 0 de 1.500; C2 p95 5,85 s (REPROVADO, como o atual), C3 teto 2.000 ev/s e C7 11,1 s APROVADOS, com menos CPU. Proxima: Fase 5 (consolidacao). Detalhes em `docs/spike-kafka-notas.md`.

## Objetivo

Medir a alternativa A de `docs/desenho-alternativas.excalidraw` (CDC lendo o redo do BDAD,
Kafka no meio e o worker da PoC aplicando no MAESTRO) com a **mesma massa, os mesmos critérios
(C1–C10) e os mesmos limites de recurso** dos labs Java e GoldenGate, para os três ficarem lado a
lado no relatório. Nenhum dos dois labs anteriores é desfeito.

## Decisões (2026-11-20)

- **Duas etapas.**
  1. **Vanilla no lab:** Apache Kafka (KRaft, um broker) + Kafka Connect com o conector Oracle do
     Debezium (LogMiner), tudo nos servidores do lab. Sem prazo de trial e sem custo, cobre os
     pontos técnicos (versão do Oracle, atomicidade, falhas incluindo o broker).
  2. **Confluent Cloud, só se a etapa 1 passar:** o mesmo worker apontado para um cluster na nuvem
     (troca de configuração), repetindo os critérios sensíveis à rede (C2, C3, C5, latência e custo).
     O que providenciar na Confluent está na seção 6.
- **Conector gerenciado fora do escopo inicial.** Conectores gerenciados do Confluent Cloud
  conectam *para dentro* do Oracle (exigiria expor o Oracle do lab ou rede privada com cluster
  Dedicated). O lab só faz conexões de saída.
- **Mesmo worker, segundo executável.** Nada de cópia: novos módulos `infra-kafka` e
  `bootstrap-kafka` no projeto Maven `worker/`. `domain`, `application`, `infra-oracle` e
  `bootstrap` ficam como estão; o lab Java continua com o mesmo binário. Ver seção 4.
- **Exclusão lógica**, como no GG: o gerador faz `UPDATE … SET excluido = 1`; um `DELETE` físico
  eventual vira `excluido = 1` no worker e é contado.
- **Labs Java e GG parados durante as medições** (`docker stop`, nunca `rm`; volumes preservados).
  O lab GG fica parado para religar quando preciso.
- **GoldenGate existente no BDAD:** como no GG, consideramos nossa captura a única. Registrar no
  relatório que o BDAD já paga supplemental logging e que o conector XStream (alternativa ao
  LogMiner) exige licença de GoldenGate, que a casa já tem.

## 1. Isolamento

Prefixo **`kf-`** (nem `poc-`, filtrado pelos scripts do lab Java, nem `gg-`).

| Item | Lab Java | Lab GG | Lab Kafka (novo) |
|---|---|---|---|
| Legado | `poc-legado` desenv:1521 | `gg-legado` desenv:1523 | `kf-legado` desenv:1525 |
| Destino | `poc-novo` infra:1522 | `gg-novo` infra:1524 | `kf-novo` infra:1526 |
| Ponte | worker `poc-worker-*` | `gg-hub` infra:18443 | `kf-kafka`, `kf-connect`, `kf-worker-*` (infra) |
| Inventário | `.tsg-flow/v01-inventario` | `.tsg-flow/gg-inventario` | `.tsg-flow/kf-inventario` |

Portas e versoes (confirmadas na Fase 0, `ss -ltn` nos hosts):

| Componente | Host | Porta | Imagem / versao | Limites |
|---|---|---|---|---|
| `kf-kafka` (KRaft, 1 broker) | infra | 19092 externo (`192.168.0.11:19092`); 9092 interno na `kf-rede` | `apache/kafka:4.3.1` | 2 CPU / 2 GiB, heap 1 GiB |
| `kf-connect` (REST) | infra | 18083 | `quay.io/debezium/connect:3.7.1.Final` (Kafka Connect 4.3.1, ojdbc11 23.26.1) | 2 CPU / 2 GiB |

Rede `kf-rede` (infra): kf-kafka, kf-connect, kf-novo e os `kf-worker-*`. Scripts em `scripts/kf/`.
Mesma imagem Oracle
(`gvenzl/oracle-free:23-slim-faststart`), `--cpus=2 --memory=3g`, redo 4 × 512 MB. Kafka e Connect
com `--cpus`/`--memory` explícitos e registrados (custo de infraestrutura da alternativa).
Origem sem triggers de captura e sem outbox, com ARCHIVELOG + FORCE LOGGING + supplemental
logging, como o `gg-legado`.

## 2. Riscos a provar ou derrubar cedo

1. **Oracle 23ai Free com o Debezium (LogMiner).** A doc da Confluent cita só 19c; o Debezium é a
   aposta. Se falhar, reserva: imagem 19c EE montada localmente (binários baixados pelo usuário).
2. **Atomicidade entre tabelas.** O worker atual aplica por (tabela, partição); um cadastro de obra
   com fonogramas pode aparecer parcial por alguns segundos, **inclusive no lab Java**. O GG
   preserva a transação. Medir com o modo atual e com uma variante transacional (tópico de
   transações do Debezium, `provide.transaction.metadata`).
3. **Versão para delete físico:** o evento não traz `updated_at` novo; desempate por SCN/instante
   do commit.
4. **Memória/CPU:** Kafka + Connect + LogMiner disputam os hosts (8 vCPU / 15 GiB).

## 3. Fases

### Fase 0: spike de viabilidade (massa reduzida)

Status: infraestrutura FEITA (2026-11-20): `kf-legado`, `kf-novo`, `kf-kafka`, `kf-connect` no ar, conector `kf-oracle`
capturando INSERT/UPDATE/DELETE das 3 tabelas e transacao multi-tabela; fixtures em
`worker/infra-kafka/src/test/resources/debezium/`; `scripts/kf/subir.sh` e `derrubar.sh` exercitados. Pendente: worker ponta a ponta.

- Subir `kf-legado`, `kf-novo`, `kf-kafka`, `kf-connect` (Debezium Oracle, versões fixas).
- Capturar INSERT/UPDATE/DELETE das 3 tabelas e uma transação multi-tabela; guardar exemplos dos
  eventos reais (envelope, tipos, tópico de transações) em `docs/spike-kafka-notas.md`.
- Em paralelo: worker com `infra-kafka`/`bootstrap-kafka` e testes unitários sobre o formato do
  Debezium; depois ajustado aos eventos reais.
- **Go/No-go:** 23ai capturando, eventos traduzíveis para `ChangeEvent`, worker aplicando ponta a
  ponta com 0 divergências na reconciliação.

### Fase 1: ambiente reprodutível

**Status (2026-11-21): concluída.** `subir.sh` (massa completa, conector, carga, workers, drenagem, reconciliação) e `derrubar.sh` testados do zero
quatro vezes (massa completa sem DML, massa completa com gerador a 300 ev/s durante a carga, massa reduzida, massa completa de novo), inventário
`poc-*`/`gg-*` idêntico em todas. Carga completa (1,7 M linhas, `CargaInicial`, 4 threads) em ~183 s (Java C1: 109,9 s para 1,85 M). Alinhamento
carga x SCN: conector `no_data` em streaming **antes** da carga, workers depois; 0 divergências com DML concorrente. Reconciliação trata delete físico
convertido. `scripts/lab-kf.env`, `snapshot-ids.sh`, `limpar-archive.sh` (critério: offset do conector), `drenar.sh`/`lag.sh`. Pendente para a Fase 3:
`cenario.sh`/`meta_kf.py` (runner dos C1-C10 e contrato de `meta.json`).
`scripts/kf/subir.sh`, `derrubar.sh` (só `kf-`, comparando inventário), carga inicial
(`CargaInicial` + início do conector num SCN fixo), reconciliação. `scripts/lab-kf.env` para os
runners parametrizados por `LAB_*`.

### Fase 2: calibração do gerador (como nos outros labs)

**Status (2026-11-21): concluída.** Patamares 500/1.000/2.000 ev/s: taxa efetiva 100% com o LogMiner ligado e desligado (kf-legado 150% de CPU a
2.000 ev/s); **4.000 ev/s não é atingível** (3.485 ev/s = 87% com LogMiner; 3.708 com o conector pausado; kf-legado saturado): registrado como teto,
degrau do C3 será GERADOR_SATURADO. O 500 ev/s inválido da Fase 0 (39 ev/s) foi **reproduzido e explicado**: efeito da massa reduzida (espaço de chaves
esparso depois de inserts), não do LogMiner nem do host. `scripts/kf/calibrar-gerador.sh`; resultados em `resultados/kf/calibracao/`.

### Fase 3: cenários C1–C10 com o modo atual do worker
Mesmos números e critérios. C4/C6/C7 incluem também queda do broker e do Connect.

**Parte 1 (C1–C5) concluída em 2026-11-23:** `scripts/kf/cenario.sh` + `meta_kf.py` (mesmo contrato do GG/Java), resultados em `resultados/kf/c1..c5/` e extras em `resultados/kf/c4/extras/`.
Resumo: C1 APROVADO (173,7 s de `CargaInicial`, 203,5 s até drenado), C2 REPROVADO (p95 6.429 ms), C3 APROVADO (teto 2.000 ev/s; 4.000 GERADOR_SATURADO; 1ª execução INVALIDA por 1.959 ev/s),
C4 APROVADO (28,7 s), C5 REPROVADO (p95 5.587 ms; após o commit 4,1 s). 
**Parte 2 (C6–C10) concluída em 2026-11-24:** C6 APROVADO (147,8 s da queda), C7 APROVADO (14,4 s), C8 APROVADO (55.697/55.697 pelo redo), C9 descritivo (modos do worker não separam do ruído), C10 APROVADO (+0,611 ms, +10,3%); variantes de `log.mining.*` e do rebalance do Connect nas notas. Configuração oficial do conector e do Connect restaurada.

### Fase 4: variante transacional
**Status (2026-11-24): concluída.** `MODO=transacional` no `bootstrap-kafka` (padrão continua `atual`): `ProcessadorTransacional` em `application` (ao lado do
`ProcessadorParticao`, intocado), um processador ativo com lease global, um consumer sobre as 48 partições de tabela + `kf.transaction`, transações aplicadas na
ordem do END e agrupadas num commit, offsets gravados no MAESTRO (`kafka_offset`) na mesma transação dos dados. Resultados em `resultados/kf/fase4/` e seção Fase 4 de
`docs/spike-kafka-notas.md` (desenho, alternativas, limites): estados parciais **86,3% das transações (modo atual) x 0 (transacional)**; C2 p95 5,85 s (atual 6,43 s,
ambos REPROVADOS pela captura), C3 teto 2.000 ev/s (3ª execução válida), C7 11,1 s (atual 14,4 s); CPU do worker, broker e destino menor no transacional. Pendências: C4/C5
no modo transacional, partida a quente, a mesma sonda no lab GG.

### Fase 5: consolidação
Comparativo Java × GG × Kafka; rascunho de recomendação (decisão humana).

### Etapa 2: Confluent Cloud (se a etapa 1 passar)
Mesmo `kf-worker` e mesmo `kf-connect` apontando para o cluster na nuvem; repetir C2, C3, C5 e medir
custo.

## 4. Worker

```
worker/
  domain/, application/, infra-oracle/, bootstrap/   inalterados
  infra-kafka/       KafkaChangeSource (assign manual = partição lógica; ack = commit de offset
                     após o commit no MAESTRO), tradução Debezium → ChangeEvent, lag do consumidor
  bootstrap-kafka/   Main/Config próprios; sem conexão com o legado (lease e cerca no MAESTRO)
```

- Tópicos com 16 partições (paridade com `ORA_HASH(codigo, 15)`), chave = `codigo`.
- `StateProvider` não é usado: o evento traz o estado.
- Mesmo binário para vanilla e Confluent Cloud (SASL_SSL por configuração).
- Testes de arquitetura cobrem os módulos novos; ITs V03/V04 do lab Java passam sem alteração.

### Worker (Fase 0)

Estado (2026-11-20): `infra-kafka` e `bootstrap-kafka` implementados, verdes com fixtures reais do Debezium
(`mvnw -f worker/pom.xml verify`) e validados ponta a ponta no lab (`scripts/kf/subir-worker.sh`, `reconciliar.sh`):
resultados na seção "Worker ponta a ponta" de `docs/spike-kafka-notas.md`.

- `KafkaChangeSource`: um consumer por (tabela, partição) com `assign()` manual; `poll` devolve até
  `max` em ordem de offset (excedente fica em buffer, sem re-fetch); sem `ack`, os mesmos eventos
  voltam; ao reabrir, posiciona no offset commitado; `ack` = `commitSync(maior offset + 1)`, nunca
  além de evento entregue e não confirmado. Erro de tradução descarta o consumer da partição (que
  trava nela, consistência sobre disponibilidade).
- Tradução: `versao` = `updated_at` do `after`; delete físico vira `excluido = 1` a partir do
  `before` (exige supplemental logging de todas as colunas) com versão
  `max(source.ts_ms, before.updated_at + 1 µs)`; tombstone ignorado. Tipos configuráveis
  (`DEBEZIUM_TEMPORAL`, `DEBEZIUM_COLUNAS_NUMERICAS`, `DEBEZIUM_ESCALA_BASE64`).
- `bootstrap-kafka` (`replicacao-worker-kafka-*-exec.jar`): lease/cerca/sink no MAESTRO, sem legado;
  `pendentes` = lag do consumidor, `lag_ms` = 0, skew medido contra o destino. Config:
  `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_TOPIC_PREFIX` (padrão `kf.BDAD`), `KAFKA_TOPICS`,
  `KAFKA_GROUP_ID`, `KAFKA_PROPERTIES_FILE` e `KAFKA_PROP_*` (SASL_SSL da etapa 2).
- Fase 4 (variante transacional): feita como outro processador em `application` (`ProcessadorTransacional`), escolhido por `MODO` em `Worker` do `bootstrap-kafka`
  (`ExecutorTransacional`); ver a Fase 4 acima.

## 5. Em aberto

- Formato dos eventos (JSON sem schema na etapa 1; Avro + Schema Registry na etapa 2?).
- Carga inicial: `CargaInicial` (comparável no C1) vs snapshot do Debezium (medir como extra).

## 6. Confluent Cloud (etapa 2, providenciar só quando chegar lá)

Environment `poc-replicacao` com Stream Governance Essentials; cluster Basic em `sa-east-1` (AWS)
ou `southamerica-east1` (GCP); service account `sa-poc-replicacao` com API key do cluster e do
Schema Registry; bootstrap e URL do registry; credenciais em `~/.kf-cloud.env` (chmod 600, nunca
no repo); saída liberada dos servidores para 9092/443; alerta de orçamento.
