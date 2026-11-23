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

---

## Fase 1: ambiente reprodutivel (2026-11-20)

Lab Kafka reconstruido do zero **quatro vezes** (ciclos 1, 2 e 4 com massa completa; ciclo 3 com massa reduzida, para o diagnostico da Fase 2; evidencias em `resultados/kf/c1/ciclo*/`). O ciclo 4 (estado final, 1 worker, sem DML): carga 177,1 s (jar 169,5 s), drenagem 28,3 s, reconciliacao 0 divergencias, total 474,6 s; snapshot de UUIDs `resultados/kf/c1/snapshots/apos-carga.csv` com 1.700.000 linhas, 0 duplicados (sha256 em `resultados/kf/c1/meta.json`). Lab deixado no ar nesse estado. Massa completa = 200k/500k/1M ( `sql/kf/legado/02_massa.sql`, a mesma dos labs Java e GG),
incluindo carga inicial, workers, drenagem e reconciliacao dentro do proprio `subir.sh`. `poc-*` e `gg-*` continuaram parados e identicos
(inventario comparado no `derrubar.sh`: containers, id, instante de parada e volumes). Imagens `kf-worker` antigas removidas (so `kf-`); o
`derrubar.sh` remove as imagens `kf-worker:*` e `kf-poc:*` (o filtro antigo `docker images -q a b` era invalido e nunca removia).

### Comandos

| Comando | O que faz |
|---|---|
| `scripts/kf/subir.sh` | Pre-requisitos (igual aos outros labs; `poc-*`/`gg-*` parados), inventario em `.tsg-flow/kf-inventario`, kf-legado/kf-novo (2 CPU/3 GiB), kf-kafka, kf-connect, esquema + massa completa, topicos (incl. heartbeat), conector em streaming, carga inicial, `KF_WORKERS` workers (padrao 1), drenagem, reconciliacao SQL, limpeza de archive. Tempos em `resultados/kf/c1/subir-tempos.json`. `KF_MASSA_REDUZIDA=1` volta a massa do spike; `KF_CARGA_DML_TAXA=300` roda o gerador durante a carga (teste de alinhamento) |
| `scripts/kf/derrubar.sh` | Remove so `kf-*` (containers, volumes, rede, imagens `kf-worker`/`kf-poc`; sem prune) e compara o inventario |
| `scripts/kf/carga-inicial.sh` | `CargaInicial` do jar da PoC (mesmo codigo do C1 Java) alinhada ao conector (abaixo) |
| `scripts/kf/reconciliar.sh` | Reconciliacao SQL por faixa de 100.000 codigos (contagem + 2 hashes) com tratamento do delete fisico (abaixo) |
| `scripts/kf/drenar.sh` | Drenado = lag do grupo de workers 0 e soma dos end offsets sem crescer por 15 s, com conector RUNNING |
| `scripts/kf/lag.sh [GRUPO] [--fim]` | Lag do consumidor (e soma dos end offsets) |
| `scripts/kf/gerar.sh TAXA DUR DIR` | Gerador da PoC no **desenv-server** (mesmo host do legado, como nos outros labs), com amostragem de `docker stats` dos dois hosts |
| `scripts/kf/snapshot-ids.sh [cen] [nome]` | Snapshot `(tabela,codigo,id)` do kf-novo em `resultados/kf/<cen>/snapshots/` |
| `scripts/kf/limpar-archive.sh [--verificar]`, `verificar-archive-necessario.sh` | Limpeza de archive guiada pelo offset do conector (abaixo) |
| `scripts/kf/calibrar-gerador.sh` | Fase 2 |
| `scripts/lab-kf.env` | `LAB_*` para `scripts/comum.sh`, `scripts/cenarios/lab.py`, `medir-relogios.py`, `coletar-recursos.sh` (mesmo formato do `lab-gg.env`; `RESULTADOS_BASE=resultados/kf`) |

### Alinhamento da carga inicial ao conector (decisao)

Fatos verificados na doc do Debezium 3.7 (pagina do conector Oracle) e no log do conector:

- Nao existe propriedade de SCN inicial para o LogMiner (nenhum `log.mining.start.scn`). `snapshot.mode=no_data` roda o fluxo de snapshot
  (so estrutura) e passa a transmitir **do SCN corrente no momento do snapshot**; o log diz `Snapshot ended ... OracleOffsetContext [scn=N]` e
  `No in-progress transactions will be captured`.
- `when_needed`/`recovery` nao servem para "comecar em S": o primeiro so faz snapshot se nao ha offset; o segundo e para recriar o
  historico de schema. Posicionar o offset a mao (REST de offsets do Connect, KIP-875) funcionaria, mas e frágil e desnecessario.
- `initial` faz o proprio snapshot (consistente, por flashback) e passa 1,85 M linhas pelo Kafka como eventos `r`.

Alternativas avaliadas:

1. **Conector antes da carga (escolhida).** Massa commitada, conector `no_data` registrado e em streaming (SCN S0 fixado), carga inicial com os
   workers parados, depois os workers aplicam o topico desde o offset 0. Por que e correta: todo commit posterior a S0 esta no topico (nada se
   perde); o `CargaInicial` le o legado no estado corrente e faz o mesmo `MERGE` guardado por versao do worker (`updated_at` > `origem_updated_at`),
   entao uma linha lida na versao v so e sobrescrita por evento de versao maior, e um evento repetido (versao <= a do destino) e descartado; um
   delete fisico convertido (`excluido=1`, versao do before + 1 us) nunca e desfeito por uma leitura anterior da carga; o UUID e do destino e
   nao muda (guarda e `MERGE` sem tocar `id`). Eventos anteriores ao fim da carga nao perdem nada. Custo: o topico recebe os eventos de S0 ate o
   fim da carga (poucos). A carga e a do C1 Java (comparavel: mesmo `CargaInicial`, mesmas 4 threads).
2. `INSERT ... AS OF SCN S` por DB link (como no GG): instantaneo e consistente por construcao, mas exigiria DB link/grants no kf-novo e foi
   4,4x mais lento no GG; tambem exigiria S >= S0 e o mesmo cuidado com o conector. Nao usada.
3. `snapshot.mode=initial` do Debezium: mais simples operacionalmente e sem script de carga, mas gera 1,85 M eventos no Kafka e depende do
   worker aplicar um evento por linha. **Nao medida nesta fase** (extra opcional para o C1; anotado como pendencia, requer lab novo).

Armadilhas tratadas: (a) a API responde RUNNING antes de o conector fixar o SCN; `registrar-conector.sh` agora espera `Starting streaming`
posterior ao PUT e imprime `SCN_INICIAL`; (b) `no_data` nao captura transacoes abertas no instante do start, por isso o conector e registrado
com o legado sem DML em andamento (no `subir.sh`, antes da carga e do gerador); (c) o offset do Connect so e gravado quando ha registro emitido:
sem heartbeat, um conector ocioso nao tem offset (o limpador de archive nao tem criterio e um reinicio refaria o snapshot no SCN corrente,
perdendo o que veio depois do ultimo evento). Foi ligado `heartbeat.interval.ms=10000` (topico `__debezium-heartbeat.kf`, 1 particao), que faz o
offset andar sem DML (mudanca de configuracao do conector, registrada aqui).

### Resultados do `subir.sh` (massa completa, do zero; relogio do desenv-server)

| Etapa | Ciclo 1 (sem DML, 1 worker) | Ciclo 2 (gerador 300 ev/s durante a carga, 2 workers) |
|---|---|---|
| bancos saudaveis | 96,0 s | 103,8 s |
| legado: redo, archivelog, esquema e massa 1,7 M | 89,1 s | 89,2 s |
| destino | 2,8 s | 2,8 s |
| topicos + conector em streaming | 32,5 s | 35,3 s |
| **carga inicial (CargaInicial, 4 threads)** | **183,1 s** (jar: 178,3 s) | 192,7 s (jar: 185,9 s) |
| workers + drenagem | 32,1 s | 42,3 s (gerador terminou antes; drenagem 34 s) |
| reconciliacao SQL | 7,7 s | 8,2 s |
| total | 444,9 s | 590,0 s |

Carga por tabela (ciclo 1): titular 200.000 a 10.026 linhas/s; obra_musical 500.000 a 11.255; fonograma 1.000.000 a 8.779; 1,7 M linhas. A massa
tem 1,7 M linhas (200k+500k+1M), nao 1,85 M; o 1,85-2 M do C1 Java inclui as linhas inseridas nos ensaios (Java: 231.712+561.993+1.060.490 = 1,85 M em 109,9 s,
~16.800 linhas/s; aqui ~9.500 linhas/s, 1,6x mais lento). Causas provaveis (nao isoladas): LogMiner do conector lendo o kf-legado durante a
leitura da carga (10 a 25% de CPU em repouso), kf-kafka/kf-connect/kf-novo no mesmo host da carga, leitura do legado por rede. Nao foi tentada
otimizacao (mais threads, faixas maiores).

Ciclo 2 (alinhamento com DML concorrente): S0 = 2322429, SCN da carga = 2322567, gerador a 300 ev/s por 240 s (VALIDO, taxa 300,04, 72.010 linhas),
iniciado 10 s depois do conector; **0 divergencias** na reconciliacao (contagem e hash em 20 faixas dos dois lados; linhas 1.001.392 / 501.394 /
200.698), soma dos end offsets dos topicos de tabela = 72.010 = linhas do gerador (nenhum evento a menos nem a mais), lag 0 apos 34 s.

### Reconciliacao e delete fisico

`scripts/kf/reconciliar.sh` compara faixas (contagem + 2 hashes). Nas faixas que diferem desce a linha (`codigo|excluido|hash`) e classifica: linha
**ausente no legado e com `excluido=1` no destino** e consistente (delete fisico convertido pelo worker) e e contada a parte; qualquer outra
diferenca (ausente no destino, so-no-destino com `excluido<>1`, hash diferente) e divergencia real (exit 1). Nunca se apaga linha do destino.
Teste (`resultados/kf/c1/teste-delete-fisico.txt`): 3 titulares e 2 fonogramas apagados no legado, apos drenar o destino tem as 5 linhas com
`excluido=1` e a reconciliacao devolve exit 0 com "deletes fisicos convertidos: 5 (fonograma=2,titular=3)"; teste negativo (nome de um titular alterado
no destino) devolve "1 DIVERGENCIAS REAIS" e exit 1, e volta a 0 depois de restaurar o valor. O subcomando Java `reconciliacao` (MODO=completo
DRY_RUN=true) **nao** foi alterado (e do modulo `poc/`, compartilhado com os labs Java e GG): ele conta as mesmas 5 linhas como divergencia
(`total=5`); no lab Kafka a verificacao oficial e o script SQL.

### Limpeza de archive (criterio seguro)

`scripts/kf/limpar-archive.sh` apaga so archive log com `NEXT_CHANGE# <= MIN(scn, numero de commit_scn)` do offset do conector (REST
`GET /connectors/kf-oracle/offsets`), menos `MARGEM_SCN` (0), e nunca os 3 mais recentes. `scn` e o menor SCN que o LogMiner precisa ao reiniciar
(inicio da transacao aberta mais antiga ou ultimo processado), o offset e gravado a cada ~60 s (so pode estar atrasado, logo mais conservador);
sem offset nao apaga nada. O heartbeat mantem o offset andando. `verificar-archive-necessario.sh` confere que tudo com `NEXT_CHANGE# > scn` existe
(vale com o conector parado). Alarme (exit 2): livre < 10 GB ou archive > 20 GB. Imagem slim sem `rman`: remocao por `rm`, como no GG. Teste: com a
massa os archives somam ~1,49 GB (3 arquivos de 512 MB, todos mantidos pela regra dos 3 mais recentes).

### Workers

O lab Java usou **1 worker** nos C2-C5 e C8-C10 e 2 no C7 (no C6, um segundo e criado na queda); todos com 2 CPU / 1 GiB / `memory-swap` 1 GiB /
`-Xmx512m`, `LEASE_SEG=12` no C6/C7. `subir-worker.sh` ja usa exatamente isso; `subir.sh` sobe `KF_WORKERS` (padrao 1; o ciclo 2 usou 2 e
dividiu as 48 particoes 24/24).

---

## Fase 2: calibracao do gerador (2026-11-21)

Lab com massa completa (ciclo 2 do `subir.sh`), 1 gerador (`scripts/kf/gerar.sh`, desenv-server, 2 CPU / 1 GiB, 8 threads, semente 1, mix e
percentuais do C2) e os 2 workers rodando. Patamares dos labs Java/GG: **500, 1.000, 2.000 e 4.000 ev/s** (C3; C2 e C4-C10 usam 1.000). Medicao:
`scripts/kf/calibrar-gerador.sh 120` (120 s por patamar; resultados em `resultados/kf/calibracao/20261121T010044Z/`, `resumo.json`). Primeiro com o
conector **pausado** (`PUT /connectors/kf-oracle/pause`: nenhum LogMiner, custo puro do legado), depois religado (backlog drenado), depois com o
LogMiner ligado. CPU = `docker stats` a cada ~3 s (100% = 1 CPU; limite de cada container = 200%).

| Modo | Alvo | Taxa efetiva | % do alvo | Veredito | tx media | kf-legado CPU media / max | gerador media / max | Drenagem apos o patamar |
|---|---|---|---|---|---|---|---|---|
| conector pausado | 500 | 500,08 | 100% | VALIDO | 5,99 ms | 45,9% / 101,5% | 20,0% / 80,2% | n/a |
| conector pausado | 1.000 | 999,99 | 100% | VALIDO | 8,42 ms | 73,7% / 118,5% | 30,9% / 181,1% | n/a |
| conector pausado | 2.000 | 1.999,98 | 100% | VALIDO | 4,65 ms | 132,7% / 181,1% | 42,4% / 201,4% | n/a |
| conector pausado | 4.000 | 3.707,52 | 92,7% | **INVALIDO** | 5,16 ms | 151,3% / 214,6% | 53,9% / 199,3% | n/a |
| LogMiner ligado | 500 | 500,08 | 100% | VALIDO | 5,11 ms | 78,8% / 175,2% | 18,2% / 151,1% | 27,3 s |
| LogMiner ligado | 1.000 | 999,99 | 100% | VALIDO | 4,62 ms | 113,2% / 152,6% | 26,6% / 192,9% | 27,8 s |
| LogMiner ligado | 2.000 | 2.000,00 | 100% | VALIDO | 4,51 ms | 150,1% / 205,9% | 38,6% / 198,5% | 27,7 s |
| LogMiner ligado | 4.000 | 3.484,77 | 87,1% | **INVALIDO** | 6,81 ms | 192,4% / 212,8% | 50,0% / 198,3% | 41,4 s |

(A "drenagem" inclui os 15 s de estabilidade do criterio; e o tempo ate lag 0 apos o fim da carga, ou seja, o pipeline completo acompanha ate 3.485
ev/s sem acumular backlog duradouro.)

Conclusoes:

- **2.000 ev/s e atingivel** com o LogMiner ligado, taxa efetiva 100,00% e kf-legado em 150% de CPU medio (75% do limite; picos de amostra de 206%).
  O **teto do gerador contra o kf-legado e entre 2.000 e 4.000 ev/s**: 4.000 pedidos entregam 3.707 ev/s com o conector pausado e **3.485 ev/s
  (87%) com o LogMiner ligado**, com o kf-legado em 192% de 200% (saturado). Registrado como resultado (como o 3.858 do GG e o 794 a 1.000 ev/s do lab
  Java com triggers), nao como falha da fase. O C3 do lab Kafka devera tratar o degrau de 4.000 como GERADOR_SATURADO.
- Custo do LogMiner no legado (descritivo, nao e o C10): +33, +40, +17 e +41 pontos de CPU media a 500/1.000/2.000/4.000 ev/s (parte e o polling
  constante, que em repouso consome 10 a 25%); o tempo medio de transacao nao muda de forma consistente (4,5 a 6,0 ms; o 8,42 ms do patamar de 1.000
  com o conector pausado e um outlier nao explicado, rodou logo apos a pausa).
- **Nenhum evento perdido nem duplicado no Kafka**: a soma dos end offsets dos tres topicos de tabela cresceu exatamente o numero de linhas geradas
  em cada patamar (por exemplo 864.926 linhas geradas com o conector pausado = 864.926 eventos depois de religar). Religar apos 4 patamares sem
  conector (864.926 eventos de backlog) drenou em **207,8 s** (~4.200 ev/s de recuperacao, ~4.500 sem os 15 s de
  estabilidade), sem reconciliacao: o LogMiner recuperou pelo redo. Reconciliacao SQL ao fim da calibracao: 0 divergencias (5 deletes fisicos
  convertidos, os do teste da Fase 1).
- Recursos nos outros containers, com o LogMiner ligado a 2.000 ev/s: kf-connect 36% / 110% (media / max), kf-novo 65% / 177%, cada worker 18-20% / 53%;
  a 4.000: kf-connect 50% / 100%, kf-novo 88% / 202%, workers 21% / 50%. Memoria estavel (kf-connect ~0,76 GiB, kf-novo ~2,0 GiB, workers 0,3-0,45 GiB).
- **Achado de recursos (para o C10/relatorio):** com os 2 workers ligados o kf-kafka gasta **~43% de CPU em repouso** (sem eventos), contra **2,3%** sem
  workers (`repouso-infra.txt`): vem dos 48 consumers por (tabela, particao) com `assign()` manual fazendo poll; e custo do desenho do worker, nao do
  Kafka. A coluna de CPU do kf-kafka nos patamares inclui tambem as JVMs de `kafka-get-offsets`/`kafka-consumer-groups` do `lag.sh`; nao leia o
  numero do kf-kafka como custo do Kafka puro.
- `lag.sh` durante a carga tem skew: lê os end offsets e depois os offsets commitados em chamadas de ~3 s cada, entao a diferenca pode ficar negativa
  (milhares) sob 2.000+ ev/s. O criterio de drenado (lag 0 e end offsets estaveis por 15 s) nao e afetado; para tendencia de lag nos cenarios, usar o
  metodo do worker (`pendentes`) em vez de `lag.sh`.
- Limpeza de archive exercitada de verdade: 3 execucoes apagaram 1 e 2 archives (archive de 2,4 GB para 1,46 GB) conforme `scn` do offset;
  `verificar-archive-necessario.sh` devolveu 0 faltando; livre no desenv-server 49-50 GB (alarme em 10 GB) sem alarme.

### Causa do 500 ev/s invalido na Fase 0 (39 ev/s): NAO era o LogMiner nem o host

Hipoteses testadas (`resultados/kf/calibracao/diagnostico-39-por-s/`): (1) gerador rodando no infra-server (`poc.sh` rodou la na Fase 0) em vez do desenv-server:
com a massa completa, 500 ev/s VALIDO nos dois hosts (500,15 e 500,08 ev/s, conector ligado); (2) CPU do kf-legado com LogMiner: media 79% a 500 ev/s,
longe do limite. **Reproduzido com a massa reduzida do spike (1k/2k/4k)**: 300 ev/s VALIDO (300,22), e logo em seguida 500 ev/s INVALIDO (44 ev/s, 2.668 linhas
em 9.911 transacoes). Causa: o `02_massa.sql` reduzido carrega codigos 1..1.000, mas as sequences continuam em 200.001 / 500.001 / 1.000.001; o 1o
patamar insere linhas (5%) com codigos ~200.499, e o gerador do patamar seguinte sorteia chaves uniformemente em `1..max(codigo)` (`Gerador.chave`),
isto e, em ~200.000 codigos dos quais so ~1.500 existem em TITULAR (2.957 em OBRA_MUSICAL e 4.904 em FONOGRAMA, nas faixas 1..500.957 e 1..1.000.904 correspondentes): ~99% dos `UPDATE ... WHERE codigo=?` afetam 0 linhas, a transacao e contada e a linha nao. Nao e
limite de ambiente. Na massa completa os codigos sao contiguos (1..n, depois as inserções pela sequence) e o efeito nao existe. Regra: nunca calibrar
nem medir com `KF_MASSA_REDUZIDA=1` depois de uma rodada de inserts; reconstruir.

### Ajustes feitos (todos registrados)

Nenhum parametro do gerador ou do conector precisou ser mudado para a calibracao; o ambiente nao foi alterado (Oracles 2 CPU / 3 GiB). Mudancas de
configuracao feitas na Fase 1: `heartbeat.interval.ms=10000` no conector (offset avanca sem DML). `log.mining.sleep.time.*` ficou em 100/300/500 ms
(Fase 0) e `batch.size` no padrao.

---

## Fase 3, parte 1: cenarios C1 a C5 (2026-11-23)

Mesmos numeros e criterios dos labs Java e GG; contrato de `meta.json` identico (`scripts/relatorio.sh --resultados resultados/kf` consolida C1-C5 sem alteracao;
C6-C10 aparecem como NAO_EXECUTADO em `resultados/kf/relatorio.md`). Evidencias em `resultados/kf/c1..c5/<execucao>/`, resumo curto em `resultados/kf/cN/relatorio.md`,
medicoes extras em `resultados/kf/c4/extras/`. CSV/logs/snapshots grandes ficam locais (ignorados pelo git).

### Runner (`scripts/kf/`)

| Arquivo | Funcao |
|---|---|
| `cenario.sh c1..c5 [rotulo]` (+ atalhos `c2-regime.sh`, `c3-teto.sh`, `c4-rajada.sh`, `c5-transacao-longa.sh`) | Runner no formato do `scripts/gg/cenario.sh`; mesmas variaveis (`TAXA`, `DURACAO_SEG`, `DEGRAU_SEG`, `C3_TAXAS`, `RAJADA_*`, `TX_*`, `PATAMAR_JANELA_SEG`...) e `KF_WORKERS` (1) |
| `c1-carga.sh` | C1: derruba e recria o lab (`derrubar.sh` + `subir.sh` com `KF_SNAPSHOT_ANTES=1`), mede e monta `meta.json`; o snapshot apos a drenagem vira a referencia de C2-C5 |
| `meta_kf.py` | Monta `meta.json` (criterios, degraus, validade TAXA/RELOGIO/DISCO/EVIDENCIA, veredito) no contrato do GG/Java; divergencias = so a reconciliacao SQL |
| `worker-fresco.sh`, `coletar-eventos.sh`, `eventos_kf.py` | Workers novos com `EVENTOS_CSV=true`, coleta dos CSV por evento, conversao para o formato dos analisadores do GG (reutilizados: `scripts/gg/analisar-latencia.py`, `analisar-especial.py`, `analisar-queda.py`) |
| `extra-queda.sh connect\|broker`, `infra-evento.sh` | Medicoes extras de queda do Connect e do broker |
| `relatorio-cenario.py cN`, `comum-cenario.sh`, `sql/kf/testes/tx_longa.sql` | Relatorio curto por cenario, funcoes comuns, transacao longa do C5 |
| `subir.sh` (alterado) | Novo gancho opcional `KF_SNAPSHOT_ANTES=1`: snapshot de UUIDs com a carga feita e os workers parados |

**Medicao de latencia.** Workers novos por cenario (`EVENTOS_CSV` e CREATE_NEW: nao reaproveita arquivo de container anterior; offsets e leases ficam no Kafka e no kf-novo).
Latencia por evento = `confirmado_em` (commit no kf-novo, relogio do infra-server) - `criado_em` (= `updated_at` da origem, relogio do legado) - offset de relogios
(`scripts/medir-relogios.py` no inicio e no fim de cada degrau; `|skew|` <= 1,3 ms, incerteza <= 5,5 ms, bem abaixo do limite de 100 ms), so eventos com `criado_em` na janela de geracao
(`inicio_carga`..`fim_carga` do gerador). Tendencia = a mesma regra do Java/GG (maximo da latencia por balde de 5 s de `confirmado_em`, regressao nos ultimos 180 s, tres blocos crescentes),
pois o `lag_ms` do worker Kafka e 0 (ele nao enxerga o legado). Drenado = lag do consumer group 0 e soma dos end offsets estavel por 15 s com o conector RUNNING (`drenar.sh`),
conferido contra `pendentes` do worker. Reconciliacao SQL por faixa e comparacao de UUIDs contra `resultados/kf/c1/snapshots/apos-carga.csv` (1,7 M linhas) em todo cenario.

**Parametros** (iguais aos dos labs anteriores): C2 1.000 ev/s por 600 s; C3 degraus 500/1.000/2.000/4.000 ev/s de 300 s; C4 regime 1.000 ev/s por 600 s e, aos 120 s, UPDATE unico de 50.000
linhas de TITULAR (`RAJADA_LINHAS=50000`), observacao de 125 s apos o commit; C5 regime 1.000 ev/s por 600 s e, aos 5 s, UPDATE de TITULAR 1 com 30 s de transacao aberta; gerador 8 threads, semente 1,
5% insert, 1% delete, 10% hot, 2 CPU/1 GiB; **1 worker** (2 CPU/1 GiB, `-Xmx512m`, `LEASE_SEG=12`), como o lab Java em C2-C5. Nenhum limite de recurso, parametro do conector ou do worker foi
alterado; o conector e o da Fase 1/2 (`snapshot.mode=no_data`, heartbeat 10 s).

### Resultados

| Cenario | Medido | Criterio / limiar | Veredito | Java | GG |
|---|---|---|---|---|---|
| C1 carga inicial | `CargaInicial` 173,7 s (TITULAR 8.693, OBRA 14.711, FONOGRAMA 8.570 linhas/s) para 1,7 M linhas; 203,5 s ate drenado; 0 divergencias; 0 de 1.700.000 UUIDs alterados; contagens iguais | 0 divergencias, 0 UUID, contagens iguais, tempo registrado | **APROVADO** | 109,9 s (1,85 M linhas), APROVADO | 565,9 s (2,01 M linhas), APROVADO |
| C2 regime | taxa 999,99 ev/s (VALIDO); **p95 6.429 ms** (p50 3.903, p99 11.368, max 21.292); tendencia +16,1 ms/s, sem crescimento sustentado | p95 <= 5.000 ms e sem tendencia de alta | **REPROVADO** (p95) | INVALIDO (TAXA) | p95 2.819 ms, APROVADO |
| C3 teto | execucao 2 (repeticao): 500 (VALIDO), 1.000 (VALIDO), 2.000 (1.998,6 ev/s, VALIDO), **4.000: GERADOR_SATURADO (3.459 ev/s, 86,5%)**; teto = 2.000 ev/s; p95 por degrau 5.399 / 5.586 / 10.182 ms | teto >= 2.000 ev/s em degrau valido | **APROVADO** (execucao 2); execucao 1 **INVALIDO** | INVALIDO (TAXA) | teto 2.000 (4.000 saturado em 3.858), APROVADO |
| C4 rajada | 50.000 linhas aplicadas 2,2 a 18,8 s apos o commit; recuperacao **28,7 s** (patamar 5.629 ms); 0 divergencias, 0 UUIDs | recuperacao <= 120 s | **APROVADO** | INVALIDO (TAXA) | 17,9 s, APROVADO |
| C5 transacao longa | taxa valida; **p95 das demais durante a aberta 5.587 ms**; aplicacao apos commit 4.133 ms; latencia da longa 34.145 ms (SEM_CRITERIO) | p95 das demais <= 5.000 ms; aplicacao <= 5.000 ms apos commit | **REPROVADO** (p95) | INVALIDO (TAXA) | p95 2.884 ms; 1.837 ms apos commit, APROVADO |

Todos os cinco com validade VALIDA (taxa >= 98%, relogios, archive sem alarme), reconciliacao SQL = 0 e 0 UUIDs alterados (C1 a C5, e tambem nas duas execucoes de C3).

**C3 teve duas execucoes e as duas ficam registradas.** Execucao 1 (`20261123T133610Z`): 500 e 1.000 ev/s validos (p95 4.586 / 6.101 ms), 2.000 ev/s com **1.959,11 ev/s = 97,96% do alvo** (limite
98% = 1.960): GERADOR_SATURADO, escada interrompida, teto 1.000, veredito INVALIDO. A causa e o legado: duas janelas de 5 s do gerador a 776 e 1.103 ev/s no meio do degrau
(kf-legado a 153% de CPU media; troca de redo log/archive, como os dips do GG), e a calibracao da Fase 2 (120 s) tinha dado 100%. Nada foi alterado; o cenario foi **repetido com os mesmos parametros**
(execucao 2, `20261123T135846Z-repeticao`), que e a que alimenta `resultados/kf/c3/meta.json` (regra do contrato: ultima execucao). O degrau de 500 ev/s da execucao 2 sai com
`crescimento_sustentado = true` (inclinacao +3,0 ms/s sobre um sinal de ~5 s): nao altera o teto (maior alvo valido sem crescimento = 2.000), mas mostra que a regra de tendencia e ruidosa nessa escala.
Em 2.000 ev/s o p95 e 10,2 s (max 23,3 s) e em 4.000 o pipeline acumula lag (inclinacao +111 ms/s, p95 30,4 s), embora drene em ~30 s apos o fim da carga.

### Diagnostico: a latencia vem do Debezium/LogMiner, nao do worker

- Amostra de 1.500 mensagens do topico `kf.BDAD.TITULAR` apos o C2 (`resultados/kf/c2/20261123T131717Z/decomposicao-latencia.json`): `updated_at` da origem ate o `ts_ms` do Debezium
  p50 3,36 s / p95 5,26 s / max 6,36 s; ate o `CreateTime` do record no Kafka p50 3,65 s / p95 5,40 s. O worker soma ~0,3 a 1 s (p50 total do C2: 3,90 s). Ou seja, o p95 acima de 5 s ja nasce na captura.
  E o patamar de 4 a 5 s do LogMiner anotado na Fase 0 (`log.mining.sleep.time.*` em 100/300/500 ms; nenhum parametro de mineracao foi ajustado neste lab).
- **Paradas de LogMiner na troca de redo log:** o log do Connect tem 12 episodios de `No logs available yet (attempt 1..4)` desde o C1 (9 ate o fim do C4; os demais incluem C5 e os extras). No C4, um deles (14:34:19Z) coincide com um pico de **26,5 s** de latencia,
  161 s apos o commit da rajada (19.944 eventos acumulados em 5 s), sem relacao com a rajada. Explica o `max_s` de 21 a 23 s e parte do p95 do C2, C3 e C5. Hipotese (nao testada): `log.mining.*`/tamanho do redo
  poderiam encurtar; fica fora das medicoes oficiais, que seguem a configuracao da Fase 1.
- Recursos medios por cenario (docker stats; 100% = 1 CPU, limite de cada um = 200%): C2 kf-legado 120%, kf-kafka 43%, kf-novo 38%, worker 31%, kf-connect 20%; C3 a 2.000 ev/s kf-legado 153%, kf-novo 65%, kf-kafka 44%;
  a 4.000 kf-legado 187% (saturado), kf-novo 98%. O kf-kafka em ~35 a 55% mesmo ocioso reflete os 48 consumers com `assign()` (achado da Fase 2).

### C4: rajada e a classificacao dos eventos

O worker nao ve o `commit_ts` da origem, entao os eventos da rajada sao os de TITULAR com codigo <= 50.000 e `criado_em` em [commit - (duracao da transacao + 1 s), commit + 0,5 s]
(`eventos_kf.py`; a duracao da UPDATE foi 2,37 s). O analisador do GG recebe esse proxy no campo `cts`. Classificadas 50.206 linhas (206 de excesso ambiguo = eventos de fundo da mesma janela, mesma ordem dos 161 do GG).
O patamar (maximo do lag de fundo por balde de 5 s nos 60 s antes do commit) foi 5.629 ms, ja acima do p95 de 5 s do regime; a recuperacao foi medida contra ele e contra p95 <= 5.000 ms do balde: **28,7 s**.
Nao ha criterio de latencia da propria rajada (p50 14,2 s, p95 17,1 s, max 19,0 s, sem criterio no PRD).

### C5: transacao longa

O Debezium so emite a transacao longa no commit: a linha tem `updated_at` 30 s antes e chega 4,1 s depois do commit (34,1 s de latencia, SEM_CRITERIO). A transacao aberta **nao atrasa as demais**
(max 6,46 s durante a abertura, p50 4,1 s; 30.012 eventos de fundo medidos), mas o p95 delas (5.587 ms) fica acima do limite pela mesma latencia de captura do C2: reprovou pelo p95, nao pela transacao longa.

### Medicoes extras do C4 (descritivas, sem criterio): queda do Connect e do broker

Regime de 1.000 ev/s por 600 s, `docker kill -s KILL` aos 180 s e `docker start` aos 240 s (os parametros do C6 dos outros labs; `scripts/kf/extra-queda.sh`; `resultados/kf/c4/extras/`). Eventos dos workers
deduplicados por `(tabela, codigo, criado_em)` na analise (o primeiro commit vale); re-entregas contadas a parte. Em ambos: 0 divergencias, 0 UUIDs alterados, lag final 0, conector RUNNING no fim.

| Evento | REST/porta de volta | Tarefa RUNNING | Normaliza (balde de 5 s completo com lag <= 5 s) | Efeitos |
|---|---|---|---|---|
| kill do kf-connect (SIGKILL, `docker kill` levou 10 s), start 60 s depois | 44,8 s apos o start | 44,8 s apos o start | **179,4 s da queda / 109,1 s do start**; pico de lag 137 s; nao estavel logo depois (baldes de 5,2 a 6,2 s) | **26.472 eventos duplicados no topico (4,4%)**: o Connect reemite desde o ultimo offset gravado (flush ~60 s); o guard de versao do worker os descarta. Drenagem 47 s apos o fim da carga |
| kill do kf-kafka (SIGKILL), start 60 s depois | porta 9092 em 34,0 s apos o start | a REST mostrou RUNNING o tempo todo (**estado enganoso**) | **NAO normalizou durante a carga**: primeira aplicacao 346 s apos o start; p50 de latencia do regime 176 s, max 412 s | 0 duplicados. Ver abaixo. Drenagem 72 s apos o fim da carga |

**Achado do broker (importante para C6/C7 e para producao):** com o broker fora por 3 s o Connect perdeu o coordenador do grupo, "revogou a atribuicao anterior" e **parou a tarefa** (`Connector 'kf-oracle' failed to
properly shut down, has become unresponsive`; Debezium `LogMiner session stopped due to an error / Producer failure`). Depois do broker voltar o worker do Connect reentrou no grupo (15:21:20Z), mas a tarefa so foi
reiniciada em **15:26:20Z, exatamente 5 min depois**: com um unico worker o lider espera `scheduled.rebalance.max.delay.ms` (padrao 300.000 ms) antes de reatribuir. Nao houve alteracao de configuracao; o remedio
provavel (`scheduled.rebalance.max.delay.ms=0` num Connect de um no, ou tarefa reiniciada pela REST) nao foi medido. Nada se perdeu: o LogMiner retomou do offset e o redo ficou disponivel (archive limpo pelo
offset do conector). No mesmo intervalo os consumers do worker registraram `UnknownHostException: kf-kafka` (DNS da rede Docker sem o container) e `lag measurement failed`, e `pendentes` ficou 0 (lag nao medido,
nao "zero real"): em producao o monitoramento nao pode confiar em `pendentes = 0` nem no estado da tarefa na REST durante queda do broker; usar o instante do ultimo evento publicado/aplicado.

### Armadilhas desta parte

1. `EVENTOS_CSV` do worker usa `CREATE_NEW`: reiniciar o mesmo container (ou `docker start` apos kill) falha; por isso `worker-fresco.sh` recria o container, e uma queda de worker no C6 deve subir **outro id** (ex.: `kf-worker-2`).
2. `derrubar.sh` remove as imagens `kf-worker`/`kf-poc`; recriar leva minutos (`docker cp` do jar de 31 MB e de 7,9 MB por ssh: ~4 min e ~1,5 min). Por isso o `carga_inicial` por relogio (289,5 s) difere do `tempo_total_s` do jar (173,7 s) e o C1 reporta o do jar,
   como o Java; o preparo de imagem, o snapshot de UUIDs (~1,5 min) e a subida do container ficam em `tempos_s` do `carga.json` e nao entram no tempo ate drenado (203,5 s = jar + drenagem de 29,8 s).
3. **O UUID do C1 e verificado sem replay**: com o conector em streaming antes da carga e sem DML, o topico fica com 0 eventos (end offsets 0), entao "antes x depois dos workers" compara 1,7 M iguais sem que o worker aplique nada.
   A estabilidade do UUID sob replay/update/delete esta nos testes da Fase 0 e, em carga real, em todo C2-C5 (1,7 M ids da base comparados depois de 600 mil a 1,6 M de eventos por cenario). Diferente do GG, que reaplica o trail inteiro.
4. O subcomando Java `reconciliacao` nao foi rodado (conta delete fisico convertido como divergencia); o `meta.json` marca `divergencias_java: null` e a reconciliacao SQL e a oficial.
5. `medir-relogios.py` estourou por timeout uma vez no handshake ssh com o infra-server (transitorio); `rel()` em `comum-cenario.sh` tenta ate 4 vezes.
6. `drenar.sh` leva ~30 s no minimo (15 s de estabilidade + 2 JVMs de `kafka-get-offsets`/`consumer-groups` por iteracao); o tempo de drenagem reportado tem esse piso.
7. Ha um processo antigo (pid 1619488, `scripts/gg/cenario.sh c4 teste`, de uma sessao anterior, ~1 dia e 8 h) preso no laptop; nao foi tocado e nao interfere (lab GG parado).
8. Disco do desenv-server: 51 GB livres antes, 49 GB depois; archive de 1,0 a 1,5 GB (limpeza pelo offset do conector funcionando); nenhum alarme.

### O que fica para C6 a C10

- C6/C7: queda do worker (id novo, `LEASE_SEG=12`, takeover ~13 s da Fase 0) e failover com 2 workers; **incluir queda do Connect e do broker** com os achados acima (5 min de `scheduled.rebalance.max.delay.ms`, duplicatas no Connect, `pendentes` enganoso); decidir se se mede tambem com a variacao de configuracao.
- C8: conector parado (analogo ao Extract parado do GG): confirmar que o offset do conector e o archive necessario sao preservados (`verificar-archive-necessario.sh`); o worker nao participa.
- C9: modos do worker Kafka (lote, partitions) em vez de threads do Java; o p95 de 5 s ja nasce no Debezium, entao o teste util e o ajuste de `log.mining.*`, a registrar como variante e nao como cenario oficial.
- C10: custo no legado = LogMiner + supplemental ALL COLUMNS (como o GG; repetir o protocolo do GG: aquecimento descartado, 4 pares em ordem balanceada, supplemental isolado).
- Extras opcionais pendentes: `snapshot.mode=initial` do Debezium no C1 (nao medido); execucao com `log.mining.*` ajustado e com `scheduled.rebalance.max.delay.ms=0` (variantes descritivas, fora do oficial).
