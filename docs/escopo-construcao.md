# PoC Replicação BDAD — escopo de construção

Oct 30, 2026 · @Tasso

O que vamos construir para validar o desenho do ADR Doc: dois Oracle em Docker, a captura no legado, o worker Java e as ferramentas para gerar carga e medir.

## Repositório e stack

Um repositório único, Java 25 (mínimo 21), Maven, com o driver `ojdbc11` como única dependência de runtime. Sem Spring na PoC, para medir o pipeline sem o custo do framework.

```
poc-replicacao-bdad/
├── docker-compose.yml
├── pom.xml
├── scripts/
│   ├── subir.sh              # sobe os bancos, aplica schemas e massa
│   ├── snapshot-ids.sh       # exporta codigo,id do novo para checar UUIDs
│   └── cenarios/             # c1-carga-inicial.sh ... c10-custo-legado.sh
├── sql/
│   ├── legado/01_schema.sql
│   ├── legado/02_massa.sql
│   └── novo/01_schema.sql
├── src/main/java/br/org/ecad/poc/replicacao/
│   ├── Main.java             # subcomandos: worker, carga-inicial, reconciliacao, gerador
│   ├── Config.java
│   ├── core/                 # portas, TableSpec, Coalescer, Uuid7
│   ├── oracle/               # adaptadores: outbox, busca de estado, MERGE
│   ├── worker/               # lease, loop por partição, métricas
│   ├── carga/                # carga inicial
│   ├── reconciliacao/
│   └── gerador/              # gerador de carga
├── src/test/java/...         # Uuid7, Coalescer, SQL gerado pelo TableSpec
└── resultados/               # CSVs por worker e por cenário
```

Um jar único com subcomandos: `java -jar poc-replicacao.jar worker`.

## Ambiente

Dois containers Oracle Free 23ai, cada um com seu usuário de aplicação em `FREEPDB1`.

| Container | Papel | Porta | Usuário/senha | Limite de memória |
| --- | --- | --- | --- | --- |
| `poc-legado` | BDAD simulado | 1521 | `bdad` / `bdad` | 3 GB |
| `poc-novo` | Banco novo | 1522 | `replica` / `replica` | 3 GB |

Imagem: `gvenzl/oracle-free:23-slim-faststart`, com `healthcheck.sh` no compose. A máquina precisa de cerca de 8 GB livres de RAM e 4 núcleos.

O `scripts/subir.sh` faz, nesta ordem:

1. `docker compose up -d` e espera os dois containers ficarem saudáveis.
2. Aplica `sql/legado/01_schema.sql` e `sql/novo/01_schema.sql` via `sqlplus` dentro de cada container.
3. Roda `sql/legado/02_massa.sql` (volumes configuráveis por `DEFINE`).
4. Imprime a contagem de linhas por tabela.

Aplicar os scripts por `docker exec` em vez da pasta de init da imagem deixa a ordem explícita e permite reaplicar sem recriar o container.

## Banco legado

Três tabelas de cadastro simplificadas, a tabela de eventos e dois tipos de trigger. Na vida real só a tabela de eventos e os triggers de captura são nossos; o trigger de `updated_at` existe só para simular o legado.

| Tabela | Colunas | Massa |
| --- | --- | --- |
| `titular` | `codigo` PK, `nome`, `tipo`, `documento`, `situacao`, `excluido`, `updated_at` | 200.000 |
| `obra_musical` | `codigo` PK, `titulo`, `iswc`, `genero`, `situacao`, `excluido`, `updated_at` | 500.000 |
| `fonograma` | `codigo` PK, `obra_codigo`, `titulo`, `isrc`, `duracao_seg`, `excluido`, `updated_at` | 1.000.000 |

Cada tabela tem sequence própria para o `codigo` e índice em `updated_at` (usado pela reconciliação).

**Tabela de eventos e captura:**

```sql
CREATE TABLE evento_replicacao (
  id         NUMBER       DEFAULT seq_evento.NEXTVAL PRIMARY KEY,
  tabela     VARCHAR2(30) NOT NULL,
  codigo     NUMBER       NOT NULL,
  op         CHAR(1)      NOT NULL,   -- I, U, D, R (reconciliação)
  particao   NUMBER(3)    NOT NULL,   -- ORA_HASH(codigo, 15)
  criado_em  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ix_evento_part ON evento_replicacao(tabela, particao, id);

CREATE OR REPLACE TRIGGER trg_obra_captura
AFTER INSERT OR UPDATE OR DELETE ON obra_musical FOR EACH ROW
DECLARE v_cod NUMBER := NVL(:NEW.codigo, :OLD.codigo);
BEGIN
  INSERT INTO evento_replicacao (tabela, codigo, op, particao)
  VALUES ('OBRA_MUSICAL', v_cod,
          CASE WHEN INSERTING THEN 'I' WHEN UPDATING THEN 'U' ELSE 'D' END,
          ORA_HASH(v_cod, 15));
END;
```

**Massa:** `INSERT /*+ APPEND */ ... SELECT` com dois `CONNECT BY LEVEL <= 1000` cruzados (até 1 milhão de linhas por tabela). Os triggers de captura ficam desabilitados durante a massa e são religados no fim, seguido de `DBMS_STATS.GATHER_SCHEMA_STATS`. Os fonogramas referenciam obras por `MOD(n, n_obra) + 1`.

## Banco novo

As tabelas réplica espelham as colunas do legado, trocam o PK por `id` UUIDv7 e guardam a versão de origem. Referências entre tabelas ficam por `codigo`, sem FK física.

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | `RAW(16)` PK | UUIDv7 gerado pelo worker, só no insert |
| `codigo` | `NUMBER` unique | Chave de negócio do legado; chave do `MERGE` |
| colunas de negócio | iguais ao legado | Copiadas do estado atual |
| `origem_updated_at` | `TIMESTAMP(6)` | `updated_at` do legado; guarda de versão |
| `replicado_em` | `TIMESTAMP(6)` | `SYSTIMESTAMP` no momento do `MERGE` |

**Tabelas de controle:**

- `lease_particao (tabela, particao, dono, expira_em, versao)`: 48 linhas pré-criadas (3 tabelas × 16 partições). `versao` é incrementada a cada claim e cerca o commit do dono; tabela nova = mais 16 linhas.
- `worker_heartbeat (worker_id, visto_em)`: usada para contar réplicas ativas e dividir as partições.

**MERGE gerado pelo `TableSpec` (exemplo de obra):**

```sql
MERGE INTO obra_musical t
USING (SELECT ? AS codigo, ? AS titulo, ? AS iswc, ? AS genero,
              ? AS situacao, ? AS excluido, ? AS origem_updated_at FROM dual) s
ON (t.codigo = s.codigo)
WHEN MATCHED THEN UPDATE SET
  t.titulo = s.titulo, t.iswc = s.iswc, t.genero = s.genero,
  t.situacao = s.situacao, t.excluido = s.excluido,
  t.origem_updated_at = s.origem_updated_at, t.replicado_em = SYSTIMESTAMP
  WHERE t.origem_updated_at IS NULL OR s.origem_updated_at >= t.origem_updated_at
WHEN NOT MATCHED THEN INSERT
  (id, codigo, titulo, iswc, genero, situacao, excluido, origem_updated_at, replicado_em)
  VALUES (?, s.codigo, s.titulo, s.iswc, s.genero, s.situacao, s.excluido,
          s.origem_updated_at, SYSTIMESTAMP)
```

## Worker

O núcleo não conhece Oracle: só portas e o `TableSpec`. Trocar a fonte por Kafka no futuro é escrever um novo `ChangeSource`.

| Pacote | Componente | Responsabilidade |
| --- | --- | --- |
| `core` | `ChangeEvent` | `eventoId, tabela, codigo, op, criadoEm, estado?` (estado nulo = buscar) |
| `core` | `ChangeSource` | `poll(tabela, particao, max)` e `ack(eventos)` |
| `core` | `StateProvider` | `buscar(spec, codigos)`; código ausente = linha não encontrada |
| `core` | `ReplicaSink` | `upsert(spec, linhas)` idempotente e `commit()` |
| `core` | `TableSpec` | Nome e colunas da tabela; gera o `SELECT` e o `MERGE`. Tabela nova = nova entrada |
| `core` | `Coalescer` | Agrupa eventos por `codigo` |
| `core` | `Uuid7` | UUIDv7 monotônico (RFC 9562), 48 bits de ms + contador de 12 bits |
| `oracle` | `OracleOutboxSource` | Lê a partição ordenado por `id`; ack = `DELETE` em batch |
| `oracle` | `OracleStateProvider` | `WHERE codigo IN (...)` em blocos de 1.000, cursor cacheado por tamanho |
| `oracle` | `OracleReplicaSink` | `MERGE` em batch JDBC |
| `worker` | `ProcessadorParticao` | Loop de uma partição, com conexões próprias |
| `worker` | `GerenciadorLease` | Heartbeat, divisão justa, claim, renovação e liberação |
| `worker` | `Metricas` | Contadores, latências e CSV |

**Loop por partição:**

1. Se o lease vence em menos de 2 s, espera.
2. `poll` até `LOTE_MAX` eventos. Vazio: dorme `POLL_MS`.
3. Coalesce e `buscar` o estado.
4. `upsert` e confirmação cercada no novo: dono, `versao` e prazo são revalidados pelo relógio do banco no mesmo bloco server-side que executa o `commit`.
5. `ack` no legado.
6. Registra latência por evento e tempo por fase. Lote cheio: volta direto ao passo 2.

Erro em qualquer passo fecha as conexões, espera 2 s e recomeça; os eventos ainda não apagados são reprocessados.

**Lease (a cada `LEASE_SEG / 3`):**

1. Grava o heartbeat e conta workers vistos nos últimos `LEASE_SEG` segundos.
2. Calcula a cota: `ceil(48 / workers ativos)`.
3. Renova os próprios leases.
4. Acima da cota: para os processadores excedentes e só então libera os leases.
5. Abaixo da cota: `SELECT ... FOR UPDATE SKIP LOCKED` nas partições sem dono ou vencidas, incrementa `versao`, assume e inicia os processadores.
6. No desligamento: para tudo e libera os leases.

**Configuração (variável de ambiente ou `-D`):**

| Chave | Padrão | Uso |
| --- | --- | --- |
| `LEGADO_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | Conexão com o legado |
| `NOVO_URL` | `jdbc:oracle:thin:@//localhost:1522/FREEPDB1` | Conexão com o novo |
| `WORKER_ID` | hostname + sufixo aleatório | Dono nos leases e nome do CSV |
| `LOTE_MAX` | 500 | Eventos por lote |
| `POLL_MS` | 200 | Espera quando a partição está vazia |
| `LEASE_SEG` | 15 | Duração do lease |
| `VIRTUAL_THREADS` | true | Virtual ou platform threads nos loops |
| `RESULTADOS_DIR` | `resultados` | Pasta dos CSVs |

`LEASE_SEG` é parâmetro de projeto: padrão 15 s na PoC e 12 s nos testes de V-07. O ciclo do lease é fixo (`LEASE_SEG / 3`) e nunca é antecipado para reivindicar mais cedo: consistência vence disponibilidade, e o failover espera o lease do morto vencer. Depois de um `kill`, o sobrevivente reivindica entre `LEASE_SEG - LEASE_SEG/3` e `LEASE_SEG + LEASE_SEG/3` (o último heartbeat do morto cai em algum ponto do seu ciclo e o claim acontece no primeiro ciclo do sobrevivente depois que ele sai da janela de heartbeat); e some 1-4 s para os processadores abrirem conexões. **Failover é a tomada real** (decisão aprovada pelo usuário em 2026-10-31): da morte do worker até o sobrevivente ser dono das 48 partições em `lease_particao` com um processador ativo em cada uma (o worker registra `all 48 owned partitions active`), medido pelo relógio do banco; limite mantido em ≤ 20 s. A janela de métricas de 5 s não entra na medida: ela quantiza a observação (somava até 5 s), não o tempo de failover, e o relógio de parede do host de testes (WSL2) salta. O CSV continua tendo de chegar a 48 e a soma de `particoes` entre workers continua ≤ 48 em toda janela. Com 12 s: claim em 8-16 s e tomada real tipicamente em 12-18 s; com 15 s: claim em 10-20 s, fora do critério em boa parte das quedas. Trade-off: lease menor encurta o failover, mas aumenta renovações no banco e reduz a pausa (GC, rede, `docker pause`) que um worker tolera antes de perder as partições; a cerca de dono/`versao`/prazo garante a consistência com qualquer valor. Quem medir C6 deve declarar o `LEASE_SEG` usado.

## Ferramentas de apoio

Três subcomandos além do worker, todos reutilizando o `TableSpec` e o `MERGE`.

**`carga-inicial`**

- Lê o legado por faixas de `codigo` (`BETWEEN ? AND ?`, faixa de 10.000) e aplica com o mesmo `MERGE` do worker.
- `THREADS` faixas em paralelo (padrão 4) e `TABELAS` para escolher quais copiar.
- Roda com a captura ligada; os eventos gerados no meio da carga são aplicados pelo worker depois, sem conflito.
- Saída: linhas/s por tabela e tempo total.

**`reconciliacao`**

- **Modo `delta`:** lê `codigo, updated_at` do legado com `updated_at > agora − DESDE_MIN − JANELA_MIN` e compara com `origem_updated_at` no novo.
- **Modo `completo`:** soma `ORA_HASH` de todas as colunas por faixa de 100.000 `codigo` nos dois bancos; nas faixas divergentes, compara linha a linha.
- Divergência (ausente ou mais antiga no novo) gera um evento `op = 'R'` na tabela de eventos.
- `DRY_RUN=true` só conta divergências; é o verificador de correção ao fim de cada cenário.

**`gerador`**

| Parâmetro | Padrão | Efeito |
| --- | --- | --- |
| `TAXA` | 1000 | Eventos/s alvo (linhas alteradas por segundo) |
| `DURACAO_SEG` | 600 | Tempo de execução |
| `THREADS` | 8 | Conexões simultâneas no legado |
| `TX_MAX` | 5 | Linhas por transação, uniforme entre 1 e o valor |
| `MIX` | `40,40,20` | Peso de fonograma, obra e titular |
| `PCT_INSERT` / `PCT_DELETE` | 5 / 1 | Inserts novos e soft deletes no total |
| `HOT_PCT` | 10 | Percentual das operações em 1% das chaves, para exercitar o coalesce |
| `RAJADA_LINHAS` | 0 | Se maior que zero, executa um único `UPDATE` desse tamanho numa transação |
| `TX_ABERTA_SEG` | 0 | Se maior que zero, mantém uma transação aberta esse tempo (cenário C5) |

O gerador imprime a taxa alcançada a cada 5 s; se não chegar à alvo, o gargalo é o legado ou a máquina, e o resultado do cenário não vale.

## Métricas e saída

Cada worker grava uma linha a cada 5 s em `resultados/worker-<WORKER_ID>.csv` e imprime o mesmo resumo no console.

| Coluna | Definição |
| --- | --- |
| `ts` | Fim da janela (UTC) |
| `particoes` | Partições ativas neste worker |
| `eventos_s` / `linhas_s` | Eventos consumidos e linhas aplicadas por segundo |
| `coalesce` | `eventos / linhas` na janela |
| `p50_ms` `p95_ms` `p99_ms` `max_ms` | Latência por evento: instante do commit no novo − `criado_em` |
| `pendentes` | `COUNT(*)` da tabela de eventos |
| `lag_ms` | Agora − `MIN(criado_em)` dos pendentes |
| `anomalias` | Códigos não encontrados na origem |
| `ms_poll` `ms_busca` `ms_upsert` `ms_ack` | Tempo médio por lote em cada fase |

Como `criado_em` é gravado dentro da transação do legado, a latência inclui o tempo até o commit dessa transação. É o comportamento desejado: mede do ponto de vista do usuário do legado.

Fora do worker, coletar por cenário: `docker stats` dos dois containers (CPU e memória) e sessões ativas em `v$session` de cada banco. O relatório consolida os CSVs por cenário numa tabela contra os critérios do ADR.

## Roteiro por cenário

Todo cenário começa com a réplica em dia (pendentes = 0), um `snapshot-ids.sh` antes e termina com `reconciliacao` em modo `completo` com `DRY_RUN=true` e um novo snapshot para comparar.

| # | Passos | Pronto quando |
| --- | --- | --- |
| C1 | Truncar o novo; `carga-inicial` com `THREADS=4`; depois subir o worker até zerar pendentes | Contagem igual nas duas bases e verificação completa sem divergência |
| C2 | 1 worker; `gerador TAXA=1000 DURACAO_SEG=600` | 10 min de CSV com `p95_ms` e `lag_ms` |
| C3 | 1 worker; `gerador` a 500, 1.000, 2.000 e 4.000, 5 min cada | Identificada a taxa em que `lag_ms` passa a crescer |
| C4 | `gerador TAXA=1000` de fundo; após 2 min, `gerador RAJADA_LINHAS=50000` | Tempo até `pendentes` voltar ao patamar anterior |
| C5 | `gerador TAXA=1000` de fundo; em paralelo, `gerador TX_ABERTA_SEG=30` | `lag_ms` registrado e verificação completa sem divergência |
| C6 | 1 worker com `gerador TAXA=1000`; `kill -9` aos 3 min; subir de novo aos 4 min | Zero divergência, zero UUID alterado, tempo até `lag_ms` normalizar |
| C7 | Worker A em regime; subir worker B; aos 3 min `kill -9` no A | Partições divididas 24/24; B com 48 em até 20 s |
| C8 | `ALTER TRIGGER trg_obra_captura DISABLE` por 1 min com carga; religar; `reconciliacao` modo `delta` | Verificação completa sem divergência após a reconciliação |
| C9 | Repetir C2 e C3 com `VIRTUAL_THREADS=false` | Tabela comparando vazão e p95 |
| C10 | `gerador TAXA=1000` com e sem os triggers de captura; medir tempo médio de transação no gerador | Overhead em percentual |

Cada cenário tem um script em `scripts/cenarios/` que executa os passos e copia os CSVs para `resultados/<cenario>/`.

## Backlog

Em ordem de dependência; cada item termina com o critério de pronto.

- [ ] **Ambiente:** compose + `subir.sh`. Pronto: os dois bancos saudáveis e as contagens da massa impressas.
- [ ] **Schemas:** legado, tabela de eventos, triggers e novo. Pronto: um `UPDATE` manual no legado gera um evento com a partição certa.
- [ ] **Núcleo:** portas, `TableSpec`, `Coalescer`, `Uuid7` com testes. Pronto: testes de versão/variante e ordem do UUIDv7, coalesce e SQL gerado passando.
- [ ] **Adaptadores Oracle:** outbox, busca de estado, `MERGE`. Pronto: teste de integração aplica um lote e apaga os eventos.
- [ ] **Worker com 1 réplica:** loop por partição com leases fixos. Pronto: `UPDATE` no legado aparece no novo em menos de 1 s.
- [ ] **Métricas:** CSV e console. Pronto: C2 curto (1 min) gera CSV com todas as colunas preenchidas.
- [ ] **Lease dinâmico:** heartbeat, cota, claim e liberação. Pronto: C7 divide 24/24 e assume 48 após queda.
- [ ] **Carga inicial.** Pronto: C1 concluído e verificação completa sem divergência.
- [ ] **Reconciliação** delta e completa. Pronto: C8 corrigido.
- [ ] **Gerador de carga** com rajada e transação aberta. Pronto: taxa alcançada ≥ 98% da alvo a 2.000 ev/s.
- [ ] **Snapshot de IDs.** Pronto: compara dois snapshots e lista UUIDs alterados.
- [ ] **Scripts de cenário C1–C10.** Pronto: cada um roda de ponta a ponta e salva os CSVs.
- [ ] **Execução e relatório.** Pronto: tabela de resultados contra os critérios do ADR e recomendação.
