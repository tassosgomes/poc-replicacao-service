# Spike Kafka (Fase 0, parte infraestrutura)

Data: 2026-11-20. Massa reduzida (1k titulares, 2k obras, 4k fonogramas). Relogio de referencia: desenv-server
(NTP; diferenca para o infra-server medida em 0,46 ms).

## O que subiu

| Container | Host | Imagem | Limites | Portas |
|---|---|---|---|---|
| `kf-legado` | desenv-server | `gvenzl/oracle-free:23-slim-faststart` (Oracle Free 23ai) | 2 CPU / 3 GiB | 1525 |
| `kf-novo` | infra-server | idem | 2 CPU / 3 GiB | 1526 |
| `kf-kafka` | infra-server | `apache/kafka:4.3.1` (KRaft, 1 broker, controller+broker) | 2 CPU / 2 GiB, heap 1 GiB | 19092 (externo, anunciado como 192.168.0.11:19092); 9092 interno na `kf-rede` |
| `kf-connect` | infra-server | `quay.io/debezium/connect:3.7.1.Final` (Kafka Connect 4.3.1 + Debezium 3.7.1.Final) | 2 CPU / 2 GiB, heap 0,5 a 1 GiB | 18083 (REST) |

Volumes `kf-legado`, `kf-novo`, `kf-kafka`; rede `kf-rede` no infra-server (kf-kafka, kf-connect, kf-novo; os workers
`kf-worker-*` entram nela e alcancam `kf-kafka:9092` e `kf-novo:1521`). Portas 1525, 1526, 19092 e 18083 estavam livres
(`ss -ltn`). Nada de `poc-*`, `gg-*` ou outros foi tocado; `derrubar.sh` confere o inventario antes/depois.

Versoes e fonte: Debezium **3.7.1.Final** (release de 2026-11-06; a pagina de release do 3.7 lista Oracle 19c, 21c, **23ai**
e 26ai testados, JDBC 23.26.x; a imagem traz `ojdbc11-23.26.1.0.0`, entao nao ha download de driver). Fontes:
https://debezium.io/releases/3.7/ e https://debezium.io/documentation/reference/stable/connectors/oracle.html
(preparacao do banco, usuario `c##dbzuser`, propriedades). A doc nao cita "23 Free" explicitamente; **funcionou na pratica**
(abaixo). Apache Kafka 4.3.1 escolhido igual ao Kafka embutido na imagem do Connect.

## Origem (kf-legado)

`sql/kf/legado/`: `00_redo.sql` (4 x 512 MB, igual ao GG), `00_debezium.sql` (ARCHIVELOG com destino em
`/opt/oracle/oradata/FREE/arch`, FORCE LOGGING, `ADD SUPPLEMENTAL LOG DATA`, tablespace `logminer_tbs` no CDB e no PDB,
usuario comum `c##dbzuser` com os grants da doc), `01_schema.sql` (3 tabelas, sequences, **supplemental log data (ALL)
COLUMNS por tabela**, e os triggers de `updated_at`, que simulam o legado e nao sao captura; sem outbox), `02_massa.sql`.
Sem `enable_goldengate_replication`, sem streams pool, sem ggadmin. O supplemental ALL COLUMNS e necessario: sem ele o
redo de um UPDATE traz so as colunas alteradas e o evento nao teria o estado completo para o worker (custo em volume de redo
a medir no C10).

## Destino (kf-novo)

`sql/kf/novo/`: mesmas tabelas e colunas do lab Java (`sql/novo/01_schema.sql`) mais `origem_commit_ts` e
`fn_uuid_v7`/`seq_uuid7` como no gg-novo; `lease_particao` (3 x 16 linhas) e `worker_heartbeat`, que o
`LeaseRepositoryAdapter`/`CercaAdapter` usam no destino. O `ReplicationSql.merge` informa `id` (UUIDv7 do worker),
`origem_updated_at` e `replicado_em`, nao usa `origem_commit_ts`. Trigger de BEFORE INSERT so preenche `id` quando nulo
(carga inicial por SQL). Nada de GoldenGate e sem `aplicacao_log`. Verificado: `fn_uuid_v7` devolve UUID, 48 linhas de lease.

## Conector (`scripts/kf/conector-oracle.json`, nome `kf-oracle`)

- `database.dbname=FREE`, `database.pdb.name=FREEPDB1`, `topic.prefix=kf`, `table.include.list=BDAD.TITULAR,BDAD.OBRA_MUSICAL,BDAD.FONOGRAMA`.
- Topicos `kf.BDAD.<TABELA>` (16 particoes, criados por `criar-topicos.sh`, auto-criacao desligada no broker) e
  `kf.transaction` (1 particao). `include.schema.changes=false` (sem isso o conector tenta publicar no topico `kf`).
  `kf.schema-history` com 1 particao e retencao infinita.
- Chave = PK: `{"CODIGO":"900001"}` (struct padrao do Debezium; o particionador do Kafka usa murmur2 sobre os bytes da
  chave, nao `ORA_HASH`: o mesmo codigo cai sempre na mesma particao, mas a particao nao e igual a do lab Java).
  Se o worker preferir chave escalar, usar o SMT `ExtractField$Key` (nao testado).
- `provide.transaction.metadata=true`; JSON sem schema (key e value, no conector e no worker do Connect).
- `decimal.handling.mode=string`: todo `NUMBER` (inclusive `NUMBER(1)`) sai como string exata (`"CODIGO":"900001"`,
  `"EXCLUIDO":"0"`, `"DURACAO_SEG":"215"`). `precise` em JSON sem schema viraria bytes em base64 (VariableScaleDecimal para `NUMBER`
  sem precisao); `double` perde exatidao. Custo: o worker faz `Long.parseLong`. (`precise` e `double` nao foram testados nos eventos.)
- `time.precision.mode=adaptive`: `TIMESTAMP(6)` sai como inteiro em **microssegundos desde a epoch, interpretando o valor como UTC**
  (`UPDATED_AT: 1795208784613938`). O container do legado esta em UTC; o worker converte para `LocalDateTime` em UTC para
  comparar com `origem_updated_at` do destino.
- `tombstones.on.delete=false` (delete gera so o evento `d`, sem mensagem nula depois).
- `snapshot.mode=no_data` (so a estrutura das tabelas vai para o historico de schema; nenhum dado). A carga inicial na Fase 1
  sera a `CargaInicial` do worker lendo o legado AS OF SCN fixo e o conector iniciado nesse SCN (a validar: `no_data` comeca em
  "agora"; para iniciar em SCN antigo usar `snapshot.mode=recovery`/`no_data` com offset gravado, ou subir o conector antes da
  carga e descartar eventos com `commit_scn` <= SCN da carga, como o `AFTERCSN` do GG).
- `log.mining.strategy=online_catalog` (padrao; nao rastreia DDL). Intervalos de sleep do LogMiner reduzidos
  (min 100 / default 300 / max 500 ms, o padrao e 1000 a 3000 ms).
- Reiniciar o conector (PUT de config) retoma do offset gravado (`Snapshot ended ... status=SKIPPED`).

## Resultado da captura (todos os casos capturaram no 23ai Free)

| Caso | Resultado |
|---|---|
| INSERT titular/obra/fonograma | `op:"c"`, `before:null`, `after` com a linha completa |
| UPDATE (colunas de negocio) | `op:"u"`, `before` e `after` completos (por causa do supplemental ALL) |
| UPDATE `excluido = 1` | `op:"u"`, `after.EXCLUIDO:"1"` |
| DELETE fisico | `op:"d"`, `after:null`, `before` com a ultima versao (sem `updated_at` novo), chave presente |
| Transacao multi-tabela (obra + 2 fonogramas) | 3 eventos em 2 topicos de tabela + `BEGIN` e `END` no `kf.transaction` |
| NULL e acentos | `"DOCUMENTO":null`, `"Ação Árvore – çãõ"` preservados (UTF-8) |

Envelope do evento de tabela (valor): `before`, `after`, `op` (`c|u|d`), `ts_ms` (instante em que o conector processou),
`transaction{id,total_order,data_collection_order}` e `source{scn, commit_scn, start_scn, commit_ts_ms, ts_ms, txId, txSeq,
rs_id, row_id, user_name, table, schema, db, snapshot...}`.

- **Versao do delete fisico:** o evento nao traz `updated_at` novo; usar `source.commit_scn` (ordena entre transacoes) e
  `source.commit_ts_ms`. Atencao: `ts_ms` e `commit_ts_ms` do `source` tem **resolucao de 1 s** (LogMiner), logo o desempate de
  versao tem de usar `commit_scn`, nao o instante.
- **Topico de transacoes:** chave `{"id":"<txId>"}`; `BEGIN` `{status,id,event_count:null,data_collections:null,ts_ms}`;
  `END` `{status,id,event_count:3,data_collections:[{data_collection:"FREEPDB1.BDAD.OBRA_MUSICAL",event_count:1},
  {...FONOGRAMA,event_count:2}],ts_ms}`. O `END` permite a Fase 4 esperar exatamente os N eventos da transacao.
  Cada evento de tabela traz `transaction.id` e `total_order` (ordem dentro da transacao entre todas as tabelas).
  Entre topicos/particoes diferentes a entrega nao e ordenada (o `END` foi publicado antes dos eventos de tabela em varios casos).
- Em `NUMBER` com `decimal.handling.mode=string`, a **chave** tambem e string.

### Fixtures (`worker/infra-kafka/src/test/resources/debezium/`)

Um arquivo por caso, reais, formato `{topic, partition, offset, timestamp, key, value}` (chaves ordenadas por `jq -S`):
`insert-titular.json`, `update-titular.json`, `update-excluido-titular.json`, `delete-titular.json`,
`insert-obra-musical.json`, `update-obra-musical.json`, `delete-obra-musical.json`, `insert-fonograma.json`,
`update-excluido-fonograma.json`, `delete-fonograma.json`, `tx-begin.json`, `tx-end.json` e `multi-tabela.json`
(`{descricao, transacao, begin, eventos[], end}` da mesma transacao: 1 obra + 2 fonogramas, `eventos` ordenados por `total_order`).

## Medicoes rapidas (nao sao C1 a C10)

Em repouso (conector ligado, sem DML):

| Container | CPU | RAM |
|---|---|---|
| kf-kafka | 3 a 10% (picos 40% no inicio) | ~350 MiB (limite 2 GiB, heap 1 GiB) |
| kf-connect | 2 a 4% | ~770 MiB (limite 2 GiB) |
| kf-legado (com LogMiner lendo) | 10 a 25% de 2 CPU | ~2,2 GiB de 3 GiB |
| kf-novo | 3 a 5% | ~2,0 GiB |

Rajada: 3.000 INSERTs (1 por transacao, commit a cada linha) em 1,2 s no kf-legado (~2.500 tx/s), todos no topico em
~8 s depois do fim da carga. Durante a rajada: kf-kafka pico ~190% de CPU (inclui o consumidor de teste e as chamadas de
`kafka-get-offsets`), 442 MiB; kf-connect pico 16% de CPU, 772 MiB; kf-legado pico 31%; kf-novo ocioso.

Latencia commit -> topico (`updated_at` do legado versus `CreateTime` do registro; relogios a 0,46 ms): rajada com os
padroes do LogMiner (sleep 1 a 3 s): p50 3,9 s, p95 4,6 s, max 4,6 s (min 3,0 s); linhas isoladas (6 amostras): 1,4 a 3,7 s;
com sleep 100/300/500 ms (config final): 1,3 a 2,5 s (6 amostras). Ou seja, dezenas de ms de rede e Kafka, e o resto e o
ciclo do LogMiner e a espera pelo commit; bem abaixo dos 20 s do SLA, mas nao e subsegundo.

## Armadilhas

- **Registrar e inserir logo em seguida perde eventos:** o conector faz o snapshot de schema e fixa o SCN inicial depois que a
  API ja respondeu RUNNING; DML feita nesse intervalo nao e capturada (perdi a primeira rodada de casos assim). Na Fase 1,
  esperar "Starting streaming" no log ou usar o SCN fixo da carga.
- Sem `include.schema.changes=false` o conector publica no topico `kf` (nao existe com auto-criacao desligada).
- Sem supplemental ALL COLUMNS os eventos de UPDATE nao teriam estado completo.
- `docker rm -v` e o `derrubar.sh` removem a rede `kf-rede`; subir/derrubar foi exercitado ponta a ponta (inventario igual).
- O historico de schema (`kf.schema-history`) precisa de 1 particao e retencao infinita; se for apagado o conector nao reinicia
  (e preciso recriar o conector com `snapshot.mode=recovery`).
- `kf-legado` gasta 10 a 25% de CPU em repouso por causa do polling do LogMiner (com sleep de 300 ms).
- Archive logs do legado ficam em `/opt/oracle/oradata/FREE/arch` e crescem (como no GG, precisara de limpeza nos cenarios longos).
- O LogMiner exige que os redo logs arquivados necessarios existam enquanto o conector estiver atras (relevante no C6/C8).

## Go/No-go de infraestrutura

**GO.** Debezium 3.7.1 (LogMiner) captura o Oracle Free 23ai: INSERT, UPDATE, `excluido=1`, DELETE fisico e transacao
multi-tabela, com envelope completo e metadados de transacao. Kafka KRaft (1 broker) e Connect cabem em 2 CPU / 2 GiB cada
(pico medido: 442 MiB e 772 MiB), e o lab sobe e desce reproduzivelmente com `scripts/kf/`. O reserva 19c nao foi necessario.
Pendencias para o Go/No-go geral do plano (fora do escopo desta parte): worker aplicando ponta a ponta com 0 divergencias, e a
decisao de como alinhar a carga inicial ao SCN do conector na Fase 1.

## Worker ponta a ponta (Fase 0, 2026-11-20)

Worker `bootstrap-kafka` (jar `replicacao-worker-kafka-0.0.1-SNAPSHOT-exec.jar`) aplicando os eventos reais do Debezium no
kf-novo, com os dois `kf-worker-*` no infra-server, na `kf-rede`.

**Ajustes ao formato real** (fixtures em `worker/infra-kafka/src/test/resources/debezium/`, `FixturesReaisDebeziumTest`
com 8 testes, todos verdes): defaults do `FormatoDebezium` = `time.precision.mode=adaptive` (epoch em microssegundos) e
`decimal.handling.mode=string` (colunas numericas, inclusive `CODIGO` e `EXCLUIDO`, chegam como texto); nomes em maiusculas e
topicos `kf.BDAD.<TABELA>` (prefixo padrao `kf.BDAD`), sem nenhuma variavel de ambiente extra. `criadoEm` do evento passou a ser o
`updated_at` do `after` (instante da DML, microssegundos, o mesmo momento em que o lab Java cria a linha da outbox), porque
`source.ts_ms`/`commit_ts_ms` tem resolucao de 1 s e truncada (superestimaria a latencia em ate 1 s); no delete usa
`commit_ts_ms`.

**Versao do delete fisico** (revista): `max(commit_ts_ms, before.updated_at + 1 us)`. Como `commit_ts_ms` e truncado ao segundo, ele
costuma ser ANTERIOR ao `updated_at` em microssegundos da mesma ultima versao (fixture: `…784.000` contra `…784.620694`), entao
quem decide e o `+ 1 us`. Continua correto: o guard do destino (`origem_updated_at` do destino <= novo) so precisa de uma versao
maior que a gravada, e um delete e por definicao posterior a ultima versao que o evento traz. Perde-se so a precisao contra uma
escrita concorrente dentro do mesmo segundo; entre transacoes diferentes da mesma chave a ordem e a do topico (mesma particao).
Risco residual: reinsert da mesma chave com `updated_at` menor que a versao do delete seria descartado (o gerador nao reusa
chaves). Se for preciso desempate melhor, `commit_scn` esta no evento (nao usado hoje).

**Estado inicial do spike** (caminho mais simples e correto com massa reduzida): o conector subiu com `snapshot.mode=no_data` depois
da massa (1k/2k/4k) e nenhum DML ocorreu entre a massa e o inicio do streaming (topicos com offset 0); o destino estava vazio.
Carga: `scripts/kf/poc.sh infra-server carga-inicial` (o `CargaInicial` do worker, 3,1 s, copia `updated_at` para
`origem_updated_at`), com o legado parado de DML; reconciliacao 0 antes de ligar os workers. O alinhamento definitivo entre
CargaInicial e SCN do conector (carga com DML concorrente) e da Fase 1.

**Scripts novos** (`scripts/kf/`): `subir-worker.sh N [VAR=VALOR]` (imagem `kf-worker:<sha>` = temurin 25 JRE + jar,
`--cpus=2 --memory=1g --memory-swap=1g`, `-Xmx512m`, `LEASE_SEG=12`, mesmos limites do worker Java), `coletar-worker.sh`,
`poc.sh HOST SUBCOMANDO` (jar da PoC: `carga-inicial`, `gerador`, `reconciliacao`, em container `kf-poc-*`),
`reconciliar.sh` (SQL, adaptado de `scripts/gg/reconciliar.sh`), `lag.sh` (lag dos workers: end offset - offset commitado,
partição sem commit conta desde 0); `derrubar.sh` agora tambem remove `kf-worker-*`, `kf-poc-*`, `kf-artefato-*` (filtro por
prefixo, sem prune) e as imagens `kf-worker`/`kf-poc`.

### Resultados

| Verificacao | Resultado |
|---|---|
| Divisao de particoes (2 workers) | 24 + 24 leases em `lease_particao`; "all 24 owned partitions active" em cada |
| Rodada 1: gerador 300 linhas/s por 45 s (13.500 linhas, VALIDO) + SQL: transacao multi-tabela (obra + 2 fonogramas + titular), updates, `excluido=1`, NULL e acentos, deletes fisicos nas 3 tabelas (inclusive obra + fonogramas na mesma transacao) | lag 0 logo apos o fim; reconciliacao SQL e Java: so divergia nas 12 linhas apagadas fisicamente (legado nao tem a linha), como esperado |
| Delete fisico | as 12 linhas existem no kf-novo com `excluido = 1` (3 titulares, 3 obras, 6 fonogramas); apagadas a mao do destino e reconciliacao SQL = **0 divergencias** (faixas, contagem e 2 hashes) e Java = 0 |
| UUID estavel | os 7.000 `(codigo, id)` da carga inicial iguais depois de updates e deletes (0 linhas diferentes), 703 novos ids |
| Transacao multi-tabela e NULL/acentos | aplicadas corretamente (`Acao Arvore - ççãõ`, `documento` NULL) |
| Metricas CSV | eventos somados dos 2 workers ~13.600 (= mensagens nos topicos), 0 janelas INVALIDA, 0 WARNING/SEVERE nos logs; latencia max ~4,9 s (p95 de janela ~4,6 s), em linha com os 4 a 5 s do LogMiner medidos antes |
| Resiliencia: `docker kill kf-worker-1` com rajada em curso | kill 18:26:20,13; kf-worker-2 dono das 48 particoes e `all 48 owned partitions active` as 18:26:33,13 (~13 s, `LEASE_SEG=12`); depois da rajada lag 0 e reconciliacao SQL e Java = **0 divergencias** |

Observacao de calibracao (Fase 2): numa segunda rajada pedida a 500 linhas/s o gerador reportou `INVALIDO` (39 linhas/s
efetivas, so 1.566 linhas) enquanto o legado tinha o LogMiner lendo; a 300 linhas/s foi VALIDO. O teto do gerador contra o
kf-legado precisa ser calibrado como nos outros labs.

## Go/No-go geral da Fase 0

**GO.** (1) Oracle 23ai Free capturado pelo Debezium 3.7.1 (LogMiner); (2) eventos reais traduziveis para `ChangeEvent` (fixtures de
insert, update, `excluido=1`, delete fisico e transacao multi-tabela, testes verdes); (3) worker aplicando ponta a ponta com 0
divergencias na reconciliacao (SQL e Java), UUIDs estaveis, delete fisico como `excluido=1`, divisao entre 2 workers e takeover
depois de `kill` com 0 divergencias. Pendencias que ja sao das proximas fases: carga inicial com DML concorrente alinhada ao SCN
(Fase 1), calibracao do gerador (Fase 2), atomicidade multi-tabela (Fase 4: durante a rajada o modo atual aplica por tabela e
particao, entao um cadastro de obra com fonogramas pode aparecer parcial por instantes; nao foi medido ainda), `lag_ms` = 0 e skew
medido contra o destino (ver notas do `MonitorMetricas`), e custo de redo do supplemental ALL COLUMNS (C10).
