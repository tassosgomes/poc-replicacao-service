# ADR — Replicação BDAD para o banco novo (sem CDC)

Oct 30, 2026 · @Tasso

## Resumo

**Status:** proposto, pendente de PoC.

Vamos replicar as tabelas de cadastro do BDAD (obra musical, fonograma, titular e outras) para uma cópia somente leitura no banco novo. A captura usa um trigger que grava só a chave e a operação numa tabela de eventos nossa, no próprio Oracle. Um worker Java lê esses eventos, busca o estado atual da linha no legado e faz upsert idempotente no banco novo, gerando o UUIDv7 apenas no insert.

O RabbitMQ sai do caminho enquanto houver um único consumidor. A estrutura separa fonte e destino para trocar a captura por CDC com Kafka (Confluent ou Cloudera) sem reescrever o destino.

## Contexto

O Java novo precisa ler o cadastro do legado com chave UUIDv7, enquanto o cadastro continua sendo feito no BDAD.

- **Origem:** BDAD (Oracle), alimentado por uma aplicação Delphi cliente/servidor. Mudanças no legado são restritas.
- **Destino:** cópia as-is das tabelas no banco novo, somente leitura até o desligamento do cadastro no legado.
- **Chave:** o PK atual virou atributo de negócio, o `codigo` (NUMBER, gerado por sequence). O Java usa UUIDv7 como identificador.
- **Exclusão:** o legado faz soft delete (a confirmar nas tabelas filhas).
- **Versão da linha:** as tabelas têm `updated_at`.
- **Infra disponível:** sem Kafka e sem CDC. Temos RabbitMQ (com Streams).
- **Volume esperado:** até cerca de 1.000 eventos/s (estimativa, sem medição). SLA alvo: 5 s.
- **Referência:** o time do SGD replica para o Elastic com trigger, fila no Oracle, job, serviço WCF, aviso no RabbitMQ e APIs que releem o Oracle. Queremos manter a ideia de "avisar e buscar o estado atual" sem o WCF e sem o consumidor depender do legado.

## Decisão

Captura por trigger numa tabela de eventos nossa e um worker Java de duas pernas que puxa os eventos, busca o estado atual no legado e grava no banco novo.

&#91;embedded content: fluxo de replicação · legado, worker e banco novo\]

O ciclo de cada partição é um lote por vez:

1. Lê até 500 eventos da partição (`tabela`, `particao`), em ordem de `id`.
2. Agrupa por `codigo`.
3. Busca o estado atual das linhas no legado. Linha ausente vira anomalia.
4. Faz `MERGE` no banco novo e comita.
5. Apaga os eventos no legado. Uma queda entre os passos 4 e 5 só causa reprocessamento idempotente.

Fora do ciclo, um job de reconciliação compara origem e réplica e reinsere na tabela de eventos o `codigo` de cada linha divergente.

## Decisões detalhadas

| # | Tema | Decisão | Por quê |
| --- | --- | --- | --- |
| D1 | Conteúdo do evento | Trigger grava só `tabela`, `codigo`, `op`, `particao`, `criado_em`. O worker busca o estado atual. | Trigger leve, sem serializar linha em PL/SQL. Estado atual é idempotente e não depende de ordem. |
| D2 | Consumo da tabela de eventos | O worker apaga o evento depois de aplicar. Sem high-water mark por `id`. | A sequence não segue a ordem de commit: um high-water mark perde eventos de transações longas. |
| D3 | Coalesce | Eventos do mesmo `codigo` no lote viram uma única busca. | Maior ganho de vazão; a operação (I/U/D) não importa quando se aplica o estado. |
| D4 | Busca na origem | `SELECT ... WHERE codigo IN (...)` em blocos de até 1.000. | Limite do `IN` no Oracle; uma ida ao banco por bloco. |
| D5 | Upsert | `MERGE` por `codigo`, aplicando só se `updated_at` recebido ≥ gravado. | Idempotente e protege contra sobrescrever com dado velho. |
| D6 | UUIDv7 | Coluna `id` na própria tabela final, gerada só no insert; `codigo` com unique. | Dispensa tabela de mapeamento; `codigo` segue como chave de negócio. |
| D7 | Referências entre tabelas | Mantidas por `codigo`, sem FK física na réplica. | Fonograma pode chegar antes da obra. |
| D8 | Exclusão | Soft delete replicado como update. Linha não encontrada na origem = anomalia com alerta. | O legado não apaga fisicamente; uma linha sumida indica falha a investigar. |
| D9 | Partições | Partição lógica fixa `ORA_HASH(codigo, 15)` → 16 por tabela, calculada no trigger. | Um `codigo` sempre no mesmo dono, preservando a ordem. Tabela nova = mais 16 partições. |
| D10 | Posse das partições | Lease no banco novo (`lease_particao`) com heartbeat e divisão justa entre réplicas. | Escala com réplicas sem coordenador externo; ativo + standby já cobre 1.000 ev/s. |
| D11 | Concorrência no worker | Um loop por partição, cada um com suas conexões. Virtual threads opcionais. | O gargalo é o banco, não a thread. Virtual threads exigem JDK 24+ (JEP 491) para não fixar o carrier em `synchronized`. |
| D12 | Reconciliação | Job por delta de `updated_at` com janela de sobreposição e verificação completa por faixa de hash. Divergência reinsere o `codigo` na tabela de eventos. | O trigger pode falhar em silêncio (desabilitado, carga direct-path). |
| D13 | Carga inicial | Captura ligada antes da carga. Carga por faixa de `codigo` usando o mesmo `MERGE`. Nunca truncate + insert. | Evita lacuna entre carga e captura e não regera UUIDs. |
| D14 | Retenção da tabela de eventos | Eventos são apagados após aplicados. | Não cria passivo no BDAD. |
| D15 | Geração do `codigo` após a virada | Sequence nova começa acima do máximo do legado, com folga. Rollback exige avançar a sequence do Oracle. | Evita colisão; o cadastro no legado é desligado na virada. |

## Alternativas consideradas

| Alternativa | Por que não agora |
| --- | --- |
| Solução do SGD (job Oracle → WCF → aviso no RabbitMQ → APIs releem o Oracle) | Um salto a mais (WCF) e os consumidores continuam acoplados ao legado. Mantivemos a ideia de buscar o estado atual, mas no worker. |
| RabbitMQ Streams como transporte | Com um consumidor só, é um salto sem ganho. Volta a valer se outro sistema precisar dos eventos ou se quisermos replay. |
| Evento com diff (só o que mudou) | Exige que nenhum evento se perca e que todos cheguem em ordem. Estado completo se corrige sozinho. |
| Trigger serializando a linha inteira | PL/SQL frágil com LOBs e mudanças de schema; aumenta o custo da transação do legado. |
| Job PL/SQL chamando a API por HTTP (`UTL_HTTP`) | O Oracle passa a depender da disponibilidade da API; retry e timeout ficam dentro do banco. Optamos por pull. |
| High-water mark por `id` do evento | Perde eventos de transações longas que comitam fora de ordem. |
| Competição livre entre réplicas (`SKIP LOCKED`) | Dois workers podem processar o mesmo `codigo` e gravar fora de ordem; a guarda por `updated_at` mitiga, mas `DATE` em segundos gera empate. |
| Tabela de mapeamento `codigo` → UUID | Desnecessária, já que o `codigo` continua sendo gerado e único. |

## Evolução para Confluent ou Cloudera

Quando houver Kafka, a troca deve atingir só o adaptador de entrada. O caminho provável é CDC de log (Debezium com LogMiner ou o conector Oracle CDC da Confluent), que aposenta o trigger, a tabela de eventos e a busca no Oracle.

- **Fonte:** interface `ChangeSource` que entrega `ChangeEvent(tabela, codigo, op, versão, estado?)`. Hoje é a tabela de eventos com busca de estado; amanhã é um tópico com o estado no payload.
- **Destino:** `ReplicaSink` com `MERGE` idempotente e guarda de versão. Não muda.
- **Contrato do evento:** schema versionado (Avro ou JSON Schema) desde já, mesmo só em memória. Vira o contrato do Schema Registry.
- **Ordenação:** partição por `codigo` equivale a um tópico com `codigo` como chave. A semântica não muda na migração.
- **Regra:** nenhuma lógica de negócio em PL/SQL e nenhum detalhe de Oracle no núcleo do worker.

## Consequências e riscos

**Ganhamos:** um componente a menos que a solução do SGD, consumidor desacoplado do legado, réplica idempotente que se corrige no próximo evento e um caminho claro para CDC.

| Risco | Impacto | Mitigação |
| --- | --- | --- |
| Trigger desabilitado ou carga direct-path no legado | Mudanças sem evento | Reconciliação por `updated_at` e por faixa de hash; alerta de divergência |
| `updated_at` preenchido pela aplicação Delphi | Relógio da máquina cliente; caminhos que não preenchem | Confirmar quem preenche; se for a aplicação, usar o trigger de captura como fonte de versão |
| Jobs em massa no legado | Rajada que estoura o SLA de 5 s | Medir na PoC; definir se o SLA vale para rajadas |
| Transação longa no Delphi | Lag aparente, pois `criado_em` é o insert, não o commit | Definir o SLA como commit no legado → commit no novo e documentar |
| Delete físico em tabelas filhas | Linha órfã na réplica | Varredura de `DELETE FROM` no PL/SQL e no Delphi antes de incluir cada tabela |
| Worker pausado com lease vencido | Dois donos da mesma partição por instantes | Margem de segurança antes do vencimento e guarda de versão no `MERGE` |
| Carga extra no BDAD | Leitura por PK + escrita na tabela de eventos | Medir na PoC o custo do trigger e das buscas |

## Escopo da PoC

A PoC precisa provar três coisas: que o desenho sustenta 1.000 eventos/s com p95 até 5 s, que não perde nem corrompe dados em falhas e quanto custa ao legado.

### Ambiente

- **Dois Oracle Free 23ai** em Docker Compose (`gvenzl/oracle-free:23-slim-faststart`): legado na porta 1521, novo na 1522.
- **Worker Java** com JDK 25 (JDK 21 serve, mas sem a correção de pinning do JEP 491).
- **Gerador de carga** Java com taxa alvo, tamanho de transação variável e modo rajada.
- O Oracle Free é limitado a 2 CPUs e 2 GB de RAM, e a rede é local. Os números são um piso de capacidade e um teto de latência de rede; não equivalem à produção.

### Massa

| Tabela | Linhas | Observação |
| --- | --- | --- |
| titular | 200.000 | 1 em 7 pessoa jurídica |
| obra\_musical | 500.000 | 5 gêneros |
| fonograma | 1.000.000 | referencia obra por `codigo` |

Volumes configuráveis. Schemas simplificados, com `codigo`, colunas de negócio, `excluido` e `updated_at`.

### O que construir

1. Schema do legado: tabelas, sequences, trigger que simula o `updated_at` do legado e trigger de captura.
2. Tabela `evento_replicacao` com índice `(tabela, particao, id)`.
3. Schema do novo: tabelas com `id RAW(16)` UUIDv7 e `codigo` unique, `lease_particao`, `worker_heartbeat`.
4. Worker: lease, loop por partição (lê, coalesce, busca, `MERGE`, apaga), métricas em CSV a cada 5 s.
5. Carga inicial por faixa de `codigo`.
6. Reconciliação: delta por `updated_at` e verificação completa por faixa de hash.
7. Gerador de carga: taxa alvo, mix de tabelas, inserts, updates, soft deletes e rajada.

### Cenários

| # | Cenário | Como | O que medir |
| --- | --- | --- | --- |
| C1 | Carga inicial | Copiar 1,7 milhão de linhas com captura ligada | Tempo total, linhas/s, UUIDs únicos |
| C2 | Regime | 1.000 ev/s por 10 min, 1 worker | p50/p95/p99, lag estável |
| C3 | Teto | 500 → 1.000 → 2.000 → 4.000 ev/s, 5 min cada | Taxa em que o lag passa a crescer |
| C4 | Rajada | `UPDATE` de 50.000 linhas numa transação, com regime de fundo | Tempo para drenar e p99 durante a rajada |
| C5 | Transação longa | Transação aberta 30 s com updates | Lag aparente e se algum evento se perde |
| C6 | Queda do worker | `kill -9` no meio do regime | Perda, duplicidade e tempo de retomada |
| C7 | Duas réplicas | Subir a 2ª réplica e depois derrubar uma | Divisão das partições e tempo de failover |
| C8 | Trigger desligado | Desabilitar a captura de uma tabela por 1 min | Se a reconciliação detecta e corrige tudo |
| C9 | Threads | C2 e C3 com virtual e com platform threads | Diferença de vazão e latência |
| C10 | Custo no legado | Mesma carga com e sem trigger de captura | Tempo médio de transação no legado |

### Métricas

- **Vazão:** eventos/s, linhas/s e taxa de coalesce (eventos por linha aplicada).
- **Latência por evento:** commit no novo − `criado_em`, em p50, p95, p99 e máximo.
- **Lag:** agora − menor `criado_em` pendente, e quantidade de pendentes.
- **Tempo por fase do lote:** leitura, busca, `MERGE`, ack.
- **Correção:** anomalias (linha não encontrada), divergências na reconciliação completa.
- **Bancos:** CPU e sessões ativas no legado e no novo.

### Critérios de sucesso

- [ ] C2: p95 ≤ 5 s e lag sem tendência de alta.
- [ ] C3: teto de pelo menos 2.000 ev/s com 1 worker (folga de 2×).
- [ ] C6 e C7: zero divergência na reconciliação completa ao fim do cenário.
- [ ] Nenhum UUID regenerado: snapshot de `codigo, id` antes e depois de cada cenário com zero diferença.
- [ ] C7: failover em até 20 s (lease de 15 s + margem).
- [ ] C8: 100% das linhas afetadas corrigidas pela reconciliação.
- [ ] C10: overhead do trigger dentro do limite a definir com o time do legado.

### Fora do escopo

Deploy em Kubernetes, segurança e gestão de credenciais, observabilidade de produção, Kafka, tabelas além das três e rede real entre ambientes.

### Entregáveis

Repositório da PoC, CSVs de cada cenário e um relatório curto com os números contra os critérios e a recomendação de seguir ou ajustar o desenho.

## Pontos em aberto

- [ ] Quem preenche o `updated_at` no legado: a aplicação Delphi ou o banco? Qual o tipo (`DATE` ou `TIMESTAMP`)?
- [ ] Existe `DELETE` físico nas tabelas filhas (vínculos obra–titular, participações)?
- [ ] Existem jobs em massa nessas tabelas? Com que frequência e volume?
- [ ] O SLA de 5 s vale também para rajadas de jobs ou só para o cadastro feito pelo usuário?
- [ ] Qual overhead aceitável do trigger de captura na transação do legado?
- [ ] Volume real de eventos por segundo no pico (hoje é estimativa de até 1.000).
- [ ] Lista completa das tabelas além de obra, fonograma e titular, e a ordem de entrada.
- [ ] Plano de rollback da virada do cadastro, incluindo o avanço da sequence do Oracle.
