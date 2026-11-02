# TechSpec — PoC de Replicação BDAD → Banco Novo (sem CDC)

> **Escopo:** Backend (pipeline de dados e ferramentas de operação; sem UI e sem API HTTP)
> **Modo:** Standalone
> **PRD de origem:** `tasks/prd-poc-replicacao-bdad/prd.md`
> **Contratos de integração:** N/A — não há HTTP, mensageria nem dados compartilhados com outros times. As fronteiras internas entre componentes estão em "Contratos e Fronteiras".
> **Data:** 2026-10-31
> **Status:** Aprovado
> **Handoff:** approved — pode alimentar o Task Creator

Este documento é um contrato, não um blueprint. O desenho de replicação já está decidido no
ADR (`docs/adr-replocacao.md`, D1–D15) e o roteiro de construção em `docs/escopo-construcao.md`;
nenhum dos dois é repetido aqui. Esta spec fixa o que eles deixam em aberto para implementar:
fatias, fronteiras, execução nos servidores, medição dos relógios, tratamento das credenciais e
evidências.

---

## Resumo Executivo

- **Solução:** um único jar Java com subcomandos (`worker`, `carga-inicial`, `reconciliacao`,
  `gerador`) e scripts de orquestração, que valida o desenho do ADR em dois Oracle Free
  (legado no `desenv-server`, banco novo no `infra-server`). Um processo no laptop orquestra por
  ssh, coleta os resultados e alimenta o Kibana.
- **Estratégia:** construir o caminho mais curto primeiro (captura → worker → banco novo, com
  evidência de latência) e só então robustez, ferramentas de integridade e cenários. Cada fatia
  termina em um comando executável com resultado observável.
- **Trade-off primário:** a PoC mede o desenho **em Oracle nos dois lados e em servidores
  compartilhados de homelab** (2 CPUs por banco, vizinhos ruidosos). Ganha-se uma validação barata,
  isolada do laptop e com rede real entre os bancos; abre-se mão de transferir os números de
  gravação para um destino PostgreSQL, que é o padrão do time (DT-01), e da repetibilidade fina
  das medidas (os cenários são validados por critérios de validade, RF-07/RF-08).

---

## Arquitetura da Solução

### Diagrama

```
 laptop (orquestrador)                         desenv-server                    infra-server
 ─────────────────────                         ─────────────                    ────────────
 build jar (Java 25, mvnw)  ──scp──►  jar ──►  [gerador]  (container JRE)       [worker A,B]   (container JRE)
 scripts/subir, cenarios    ──ssh──►           [poc-legado] Oracle Free  ◄─JDBC─ [carga-inicial]
 exportador → Kibana                           (evento_replicacao,       LAN     [reconciliacao]
 coleta CPU/sessões via ssh                     triggers, 3 tabelas)             [poc-novo] Oracle Free
                                                                                  (3 réplicas, lease_particao,
                                                                                   worker_heartbeat)
 resultados/<cenario>/  ◄──scp── CSV por worker, meta.json, snapshots
```

- **Legado (`poc-legado`, `desenv-server`):** tabelas simuladas, `evento_replicacao`, triggers de
  captura e o trigger que simula `updated_at`. Só a tabela de eventos e os triggers de captura
  correspondem ao que existiria no BDAD real (PRD, RF-02).
- **Banco novo (`poc-novo`, `infra-server`):** três réplicas, `lease_particao`, `worker_heartbeat`.
- **Worker / ferramentas:** o worker lê de `ChangeSource`, busca em `StateProvider`, grava em
  `ReplicaSink`. Worker, `carga-inicial`, `reconciliacao` e verificação de snapshot rodam no
  `infra-server`; o `gerador` roda no `desenv-server`, ao lado do legado (DP-06). Não há instalação
  no host dos servidores: cada processo roda em container efêmero com teto de CPU e memória (DT-03).
- **Elastic/Kibana:** só leitura dos resultados, alimentado a partir do laptop (DT-05).

### Bloco Backend

- **Regra de negócio:** vive no núcleo (`core`): coalesce, guarda de versão, atribuição de UUIDv7,
  geração de SQL a partir de `TableSpec`. O núcleo não importa nada de Oracle nem de JDBC
  (RF-12); os adaptadores `oracle` implementam as três portas.
- **Transação e consistência:** uma transação no banco novo (MERGE + commit) por lote; o descarte
  dos eventos no legado ocorre depois, em outra conexão e outra transação. Não há transação
  distribuída: a queda entre as duas só provoca reprocessamento idempotente (RN-03, RN-06).
- **Concorrência:** um loop por partição, cada um com suas conexões; o dono da partição é decidido
  pelo lease. Threads virtuais ligadas por padrão e desligáveis por configuração (C9).
- **Eventos:** não há mensageria. "Evento" é uma linha de `evento_replicacao`.

---

## Mapa de Fatias Verticais

Ordem de construção = coluna `Bloqueado por`. Fatias V-01..V-10 entregam componentes; V-11..V-15
entregam a operação e a decisão. Os caminhos dos servidores e dos scripts seguem
`docs/escopo-construcao.md`, com as correções das decisões DT-01..DT-08.

### V-01: Um comando sobe, verifica e remove o ambiente nos dois servidores

- **Cobre:** RF-01, DP-06, DP-09, DP-12
- **Entrada / gatilho:** `scripts/subir.sh` (e `scripts/derrubar.sh`), executados no laptop com `.env` presente.
- **Processamento:**
  1. **Pré-requisitos** (falha interrompe com mensagem do que corrigir): `.env` existe e consta no
     `.gitignore`; ssh sem senha para `infra-server` e `desenv-server`; Docker acessível; portas
     1521 (legado) e 1522 (novo) livres; RAM livre ≥ 4 GB e disco livre ≥ 10 GB em cada servidor;
     relógios: `timedatectl` reporta sincronizado nos dois e a diferença entre eles é medida (ver
     DT-04) e fica ≤ 100 ms; imagens `gvenzl/oracle-free:23-slim-faststart` e
     `eclipse-temurin:25-jre` baixáveis.
  2. Registra o **inventário dos serviços existentes** (nomes e estado dos containers em execução)
     em cada servidor, para comparação posterior.
  3. Sobe `poc-legado` no `desenv-server` e `poc-novo` no `infra-server`, cada um com `--cpus=2` e
     limite de memória de 3 GB, `healthcheck.sh` da imagem, rede e nomes próprios da PoC (prefixo
     `poc-`), volumes nomeados com o mesmo prefixo.
  4. Aplica os esquemas (tabelas, sequences, índices em `updated_at`, controle do banco novo) por
     `docker exec` + `sqlplus`, reaplicáveis sem recriar o container. A **captura** (V-02) ainda não
     existe aqui.
  5. Gera a massa por `INSERT /*+ APPEND */ … SELECT` com `DEFINE` de volumes (padrão 200.000 /
     500.000 / 1.000.000) e coleta estatísticas.
  6. Imprime contagem por tabela e os limites de CPU/memória dos dois containers (entram no relatório).
  7. `derrubar.sh` remove somente containers, volumes, redes e imagens com prefixo `poc-`/da PoC e
     compara o inventário final com o inicial.
- **Saída observável:** dois bancos saudáveis, contagens impressas iguais aos volumes configurados,
  fonograma → obra sempre válida, nenhum serviço existente alterado.
- **Evidência / checkpoint:**
  - `scripts/subir.sh` num ambiente limpo → imprime `titular=200000 obra_musical=500000 fonograma=1000000`; `docker inspect` mostra `NanoCpus` = 2 CPUs e `Memory` = 3 GB nos dois containers.
  - Ocupar a porta 1521 no `desenv-server` e rodar de novo → aborta antes de subir qualquer container, com mensagem citando a porta.
  - Rodar de novo o aplicar-esquema → ambiente volta ao estado inicial, containers mantêm o mesmo ID.
  - `DEFINE` com volume menor (ex.: 1.000/2.000/4.000) → contagens correspondentes e `SELECT COUNT(*) FROM fonograma f WHERE NOT EXISTS (SELECT 1 FROM obra_musical o WHERE o.codigo = f.obra_codigo)` = 0.
  - `scripts/derrubar.sh` → inventário final igual ao inicial e `docker ps -a`/`docker volume ls` sem itens `poc-`.
- **Bloqueado por:** Nenhum

### V-02: Toda alteração no legado gera um evento com a partição certa

- **Cobre:** RF-02, RN-01, RN-02 (parte do legado)
- **Entrada / gatilho:** `INSERT`, `UPDATE` ou `DELETE` em qualquer das três tabelas do legado.
- **Processamento:** `evento_replicacao` (id por sequence, tabela, codigo, op, particao, criado_em
  em `TIMESTAMP WITH TIME ZONE`) com índice `(tabela, particao, id)`; um trigger de captura por
  tabela, `AFTER … FOR EACH ROW`, que grava só a chave, a operação e `ORA_HASH(codigo, 15)`;
  um trigger separado, **identificado como simulação do legado**, que preenche `updated_at`.
  A massa de V-01 passa a carregar com os triggers de captura desabilitados e a religá-los no fim.
- **Saída observável:** um evento por linha alterada, sem colunas de negócio, na partição esperada.
- **Evidência / checkpoint:**
  - `UPDATE obra_musical SET titulo = titulo || 'x' WHERE codigo = 42` + `COMMIT` → `SELECT tabela, codigo, op, particao FROM evento_replicacao` retorna exatamente `(OBRA_MUSICAL, 42, U, ORA_HASH(42,15))`.
  - INSERT, UPDATE e DELETE do mesmo `codigo` em três transações → três linhas I, U, D com a mesma `particao`.
  - `ROLLBACK` após um UPDATE → zero eventos.
  - UPDATE de 50.000 linhas numa transação → 50.000 eventos.
  - Após `subir.sh` com massa completa → `COUNT(*)` de `evento_replicacao` = 0 e triggers `ENABLED`.
  - `DESCRIBE evento_replicacao` → nenhuma coluna de negócio.
- **Bloqueado por:** V-01

### V-03: Uma alteração no legado aparece no banco novo em menos de 1 s

- **Cobre:** RF-03 (caminho principal), RF-12, RN-01, RN-02, RN-03, RN-04, RN-05, RN-06, RN-07 (soft delete), EN-01
- **Entrada / gatilho:** `java -jar … worker` (um worker, as 48 partições atribuídas estaticamente,
  sem lease dinâmico) com alterações no legado.
- **Processamento:** por partição: lê até `LOTE_MAX` eventos em ordem de `id` → coalesce por `codigo`
  → busca o estado atual em blocos de 1.000 → `MERGE` em lote com guarda `origem_updated_at >=`
  → commit no novo → apaga os eventos lidos no legado. UUIDv7 gerado só no ramo de insert do
  `MERGE`. Núcleo (portas, `TableSpec`, `Coalescer`, `Uuid7`) sem dependência de Oracle, com
  testes unitários que rodam sem banco. Soft delete chega como update de estado.
  Inclui o **esquema declarativo**: adicionar uma tabela é adicionar uma entrada de `TableSpec`.
- **Saída observável:** linha correspondente na réplica; evento removido; `id` estável.
- **Evidência / checkpoint:**
  - `mvnw verify` (sem bancos) → passam: UUIDv7 (versão 7, variante RFC 9562, 1.000.000 valores estritamente crescentes, inclusive com várias threads), `Coalescer`, SQL gerado por `TableSpec` para as três tabelas, e um teste com `ChangeSource` em memória alimentando o destino sem alterações no destino.
  - Com o worker rodando e réplica em dia: `UPDATE` no legado + commit → a linha aparece no banco novo com os valores novos em < 1 s e `COUNT(*)` do evento = 0.
  - Dois UPDATEs seguidos na mesma linha → a réplica reflete o segundo e `id` não muda.
  - Reaplicar manualmente o mesmo lote → `SELECT id` idêntico antes/depois.
  - `UPDATE … SET excluido = 1` → réplica com `excluido = 1`, linha presente.
  - Chamar o worker com um estado mais antigo (teste de integração que força `origem_updated_at` menor) → réplica mantém o mais novo.
  - Teste de integração (bancos reais da PoC): aplica um lote de 500 eventos e verifica que os 500 foram apagados.
- **Bloqueado por:** V-02

### V-04: O worker sobrevive às falhas do pipeline sem perder nem duplicar

- **Cobre:** RF-03 (falhas), RN-03, RN-06, RN-07, RN-08
- **Entrada / gatilho:** linha ausente no legado; fonograma antes da obra; queda de conexão ou de
  processo entre os passos do lote.
- **Processamento:** linha ausente na busca → contador de anomalia + alerta no console, evento
  descartado, restante do lote aplicado. Qualquer erro num passo fecha as conexões da partição,
  espera 2 s e recomeça; eventos não apagados são reprocessados. Uma violação de unicidade no
  `MERGE` (`ORA-00001`, por concorrência com outro escritor da mesma linha) é tratada como
  reprocessável, não como erro fatal. Partição vazia: dorme `POLL_MS` e volta a consultar.
- **Saída observável:** zero perda e zero duplicidade de linhas após qualquer falha; anomalias contadas.
- **Evidência / checkpoint:**
  - `DELETE` físico de uma linha no legado (fora do comportamento do legado, só para o teste) + evento já registrado → `anomalias` +1, alerta impresso, lote restante aplicado.
  - Inserir fonograma com `obra_codigo` inexistente na réplica → gravação aceita; depois da obra chegar, ambas existem e a referência bate por `codigo`.
  - Encerrar o container do banco novo no meio de um lote (`docker stop poc-novo`), religar → worker reconecta sozinho, `COUNT(*)` de pendentes volta a 0, nenhuma linha duplicada (`GROUP BY codigo HAVING COUNT(*) > 1` vazio).
  - Simular falha após o commit no novo e antes do descarte (ponto de falha injetável por configuração de teste) → no recomeço o lote é reaplicado, estado final idêntico e `id` inalterado.
- **Bloqueado por:** V-03

### V-05: Cada worker registra métricas, a diferença de relógio e o uso dos bancos

- **Cobre:** RF-08, RN-09, DP-01, DP-08 (separação da latência de transação longa)
- **Entrada / gatilho:** worker em execução; coletor de recursos executado no laptop durante o cenário.
- **Processamento:**
  - CSV `resultados/worker-<WORKER_ID>.csv` a cada 5 s com as colunas definidas em
    `docs/escopo-construcao.md:222-232`, mais `skew_ms` e `skew_incerteza_ms`; mesmo resumo no console.
  - Latência de cada evento = `Instant` do relógio do worker lido logo após o retorno do commit no
    banco novo − `criado_em` do evento (DT-04). Janela sem tráfego: valores nulos tratados, `pendentes = 0`.
  - Eventos cuja `criado_em` precede o commit em mais de 5 s (transação longa) são contados e
    reportados em coluna própria e **excluídos** dos percentis do SLA (DP-08).
  - Coletor de recursos (no laptop, por ssh): `docker stats` dos containers `poc-` e sessões ativas
    em `v$session` dos dois bancos, em CSV por cenário.
- **Saída observável:** CSV por worker e CSV de recursos por cenário.
- **Evidência / checkpoint:**
  - Worker + 1 minuto de carga manual (V-06 não é necessário: laço de `UPDATE`s) → CSV com 12 linhas e nenhuma coluna vazia.
  - Dois workers com `WORKER_ID` distintos → dois arquivos distintos.
  - Sem tráfego por 30 s → linhas com zeros, `pendentes = 0`, sem exceção.
  - Transação aberta 12 s com updates, depois commit → a latência desses eventos aparece só na coluna de transação longa; percentis do SLA não sobem.
  - Alterar o relógio da máquina do worker em +500 ms (ou usar `faketime` no container) → `skew_ms` ≈ 500 ± incerteza e a linha é marcada inválida.
- **Bloqueado por:** V-03

### V-06: O gerador entrega a taxa pedida e os padrões de carga dos cenários

- **Cobre:** RF-07, DP-02
- **Entrada / gatilho:** `java -jar … gerador` com os parâmetros de `docs/escopo-construcao.md:204-214`.
- **Processamento:** taxa-alvo distribuída entre `THREADS` conexões; transações de 1 a `TX_MAX`
  linhas; mix, inserts, soft deletes e `HOT_PCT` conforme parâmetros; modo rajada (um único
  `UPDATE` de N linhas); modo transação aberta; taxa efetivamente alcançada impressa a cada 5 s e
  registrada. **Validade:** o resumo final declara `VALIDO` se taxa média ≥ 98% da alvo e `INVALIDO`
  caso contrário (DT-07 trata o degrau do C3).
- **Saída observável:** linhas alteradas no legado, taxa alcançada, resumo de validade.
- **Evidência / checkpoint:**
  - `TAXA=2000 DURACAO_SEG=60` com captura ligada, sem worker → taxa alcançada ≥ 1.960 ev/s; `VALIDO`.
  - `TAXA=1000 MIX=40,40,20` por 120 s → distribuição de `evento_replicacao.tabela` dentro de ±3 pontos percentuais do mix.
  - `RAJADA_LINHAS=50000` → exatamente um `COMMIT` e 50.000 eventos (V-02).
  - `TX_ABERTA_SEG=30` → `v$transaction` mostra a transação aberta por ≈ 30 s.
  - `HOT_PCT=10` → 10% (±1) das operações recaem em 1% das chaves.
  - Alvo propositalmente inalcançável (ex.: `TAXA=20000`) → resumo `INVALIDO`.
- **Bloqueado por:** V-02

### V-07: Partições são divididas entre réplicas e reassumidas após queda

- **Cobre:** RF-04, RN-02, RN-04
- **Entrada / gatilho:** subir, matar ou encerrar workers.
- **Processamento:** heartbeat, cota `ceil(48 / workers ativos)`, renovação, claim com
  `FOR UPDATE SKIP LOCKED`, liberação só depois de parar os processadores excedentes, parada
  ordenada no desligamento, conforme `docs/escopo-construcao.md:151-169`. `LEASE_SEG` é parâmetro:
  padrão 15 s na PoC e 12 s nos testes de V-07 (com 15 s o claim após um `kill` cai em 10-20 s e
  a tomada passa dos 20 s em boa parte das quedas); o ciclo de lease é fixo em
  `LEASE_SEG / 3` e nunca é antecipado para reivindicar antes (consistência vence disponibilidade).
  Trade-off e limites em `docs/escopo-construcao.md` (Configuração); quem medir C6 declara o valor;
  nenhum processador processa com lease que vence em menos de 2 s.
- **Saída observável:** distribuição de partições por worker (coluna `particoes` do CSV e
  `lease_particao`).
- **Evidência / checkpoint:**
  - Um worker → 48 partições em `lease_particao` com seu `dono`.
  - Sobe o B → `SELECT dono, COUNT(*) … GROUP BY dono` converge para 24/24 e em nenhum instante existem duas linhas processando a mesma `(tabela, particao)` (verificado pelos CSVs: soma de `particoes` ≤ 48 em todas as janelas).
  - `docker kill -s KILL` no A → o B chega a 48 em ≤ 20 s, medido como tomada real: da morte até B ser dono das 48 partições em `lease_particao` com os 24 processadores adicionais ativos, pelo relógio do banco (critério alterado com aprovação do usuário em 2026-10-31; a janela de 5 s do CSV quantizava a medida e não entra nela). O CSV de B ainda precisa chegar a 48.
  - Parar o A com `SIGTERM` → todas as partições do A são liberadas e assumidas pelo B sem esperar o vencimento do lease.
  - Pausar um worker (`docker pause`) por mais de `LEASE_SEG`, retomar → nenhum estado velho sobrescreve um novo (`origem_updated_at` na réplica nunca regride; teste de integração dedicado).
- **Bloqueado por:** V-03, V-05

### V-08: Dois snapshots dizem se algum UUID mudou

- **Cobre:** RF-09, RN-05, DP-03
- **Entrada / gatilho:** `scripts/snapshot-ids.sh <destino>` antes e depois; `scripts/comparar-snapshots.sh a b`.
- **Processamento:** exporta `codigo,id` das três réplicas ordenado; a comparação lista
  `codigo`, `id_antes`, `id_depois` para todo `codigo` presente nos dois com `id` diferente e
  ignora `codigo` presente em só um deles. Resultado em arquivo e código de saída ≠ 0 se houver diferença.
- **Saída observável:** arquivo de diferenças e código de saída.
- **Evidência / checkpoint:**
  - Dois snapshots seguidos sem alteração → zero diferenças, saída 0.
  - Alterar manualmente um `id` na réplica entre dois snapshots → o `codigo` é listado com os dois valores; saída ≠ 0.
  - Inserir linhas novas entre dois snapshots → não aparecem como diferença.
  - Tempo e volume (1,7 milhão de linhas) registrados para dimensionar o custo por cenário.
- **Bloqueado por:** V-03

### V-09: A reconciliação encontra e reenfileira toda divergência

- **Cobre:** RF-06, RN-10, DP-03
- **Entrada / gatilho:** `java -jar … reconciliacao` com `MODO=delta|completo` e `DRY_RUN`.
- **Processamento:** `delta`: linhas do legado com `updated_at` acima de `agora − DESDE_MIN − JANELA_MIN`
  comparadas a `origem_updated_at`. `completo`: soma de hash por faixa de 100.000 `codigo` nos dois
  bancos; nas faixas divergentes, comparação linha a linha; colunas comparadas são as de negócio e
  a versão (`updated_at` ↔ `origem_updated_at`), sem `id` e `replicado_em`. Divergência (ausente
  ou mais antiga) gera evento `op = 'R'`, exceto com `DRY_RUN=true`, que só conta e imprime
  total e faixas. Saída em formato legível por máquina para o runner (V-12) usar como veredito.
- **Saída observável:** contagem de divergências por tabela e faixa; eventos `R`.
- **Evidência / checkpoint:**
  - Bases idênticas → `completo` com `DRY_RUN=true` retorna 0 divergências em ≤ tempo medido e registrado.
  - `UPDATE` direto na réplica de uma linha (corrupção simulada) → faixa e `codigo` apontados.
  - Apagar uma linha da réplica → `delta` e `completo` a detectam; sem `DRY_RUN` cria um evento `R` e o worker reaplica; nova verificação → 0.
  - Com `DRY_RUN=true` em bases divergentes → conta e não cria evento (`COUNT(*)` de `evento_replicacao` inalterado).
- **Bloqueado por:** V-03

### V-10: A carga inicial copia 1,7 milhão de linhas sem regerar UUID

- **Cobre:** RF-05, RN-03, RN-05
- **Entrada / gatilho:** `java -jar … carga-inicial` com `THREADS` e `TABELAS`.
- **Processamento:** lê por faixas de `codigo` (10.000) e aplica o mesmo `MERGE` do worker, com a
  captura ligada; não trunca nem reinsere. Interrupção e reexecução retomam sem alterar `id`.
  Resumo: linhas/s por tabela e tempo total.
- **Saída observável:** réplica completa; resumo.
- **Evidência / checkpoint:**
  - Réplica vazia, captura ligada, `THREADS=4` → resumo impresso; depois do worker zerar pendentes, `COUNT(*)` iguais nas duas bases e `reconciliacao MODO=completo DRY_RUN=true` = 0.
  - Matar a carga no meio e reexecutar → snapshot de IDs antes (parcial) e depois sem nenhum `id` alterado (V-08).
  - Rodar o gerador com `TAXA=500` durante a carga → ao final, a réplica reflete o estado mais recente das linhas alteradas, sem erro de unicidade escapando.
  - `TABELAS=titular` → só `titular` copiada.
- **Bloqueado por:** V-03, V-08, V-09

### V-11: Um cenário de carga roda sozinho, é validado e arquiva seus resultados

- **Cobre:** RF-10 (estrutura comum e C2, C3, C4, C5, C9), DP-02, DP-07, DP-08
- **Entrada / gatilho:** `scripts/cenarios/c2-regime.sh`, `c3-teto.sh`, `c4-rajada.sh`, `c5-transacao-longa.sh`, `c9-threads.sh`, e a biblioteca comum que todos usam.
- **Processamento (comum a todos os cenários):**
  1. Pré-requisitos de V-01 (rápido) e inventário dos serviços existentes.
  2. Estado inicial: réplica em dia (`pendentes = 0`), snapshot de IDs (V-08).
  3. Executa os passos do cenário (workers, gerador, falhas) com os tempos de `docs/escopo-construcao.md:242-253`.
  4. Durante a execução: coleta de recursos (V-05) e vigilância dos serviços existentes (DT-06).
  5. Estado final: aguarda pendentes = 0 (com limite de tempo), `reconciliacao completo DRY_RUN=true`, novo snapshot e comparação.
  6. Grava `resultados/<cenario>/` com CSVs, snapshots, diferenças, saída da reconciliação e
     `meta.json` (ver "Contratos e Fronteiras").
  - **C3:** degraus 500 → 1.000 → 2.000 → 4.000 ev/s, 5 min cada; o veredito do degrau é o
    crescimento do `lag_ms` (inclinação positiva sustentada nos últimos 3 min do degrau). Degrau cujo
    `gerador` não alcança 98% é `GERADOR_SATURADO`, encerra a escada e não reprova o C3 (DT-07).
  - **C4:** regime de fundo de 1.000 ev/s; rajada de 50.000 linhas aos 2 min; mede o tempo até
    `pendentes` voltar ao patamar anterior e p95 de volta a ≤ 5 s (DP-07).
  - **C5:** regime de fundo + transação de 30 s em paralelo (DP-08).
  - **C9:** repete C2 e C3 com `VIRTUAL_THREADS=false` e gera tabela comparativa de vazão e p95.
- **Saída observável:** pasta de resultados por cenário com veredito por critério (`APROVADO`,
  `REPROVADO`, `INVALIDO`, `NAO_EXECUTADO`).
- **Evidência / checkpoint:**
  - `c2-regime.sh` com `DURACAO_SEG=600` → `resultados/c2/` com CSV de 10 min, `meta.json` com p95, tendência do lag, validade e comparação de snapshots; resultado `APROVADO`/`REPROVADO` calculado, não digitado.
  - Cenário com `TAXA` inalcançável → `meta.json` com `INVALIDO` e o runner não declara `APROVADO`.
  - Cenário com diferença de relógio > 100 ms (forçada) → `INVALIDO` por relógio.
  - Parar manualmente um container de outra aplicação (`docker stop` de um container existente, só no teste) durante o cenário → runner aborta o gerador, registra `ABORTADO_SERVICO_EXISTENTE` e não declara resultado.
  - Reexecutar o mesmo cenário → começa com pendentes = 0 e snapshot novo.
- **Bloqueado por:** V-05, V-06, V-07, V-08, V-09

### V-12: Cenários de falha e recuperação provam integridade

- **Cobre:** RF-10 (C6, C7, C8), RF-04, RF-06, RN-06, RN-10
- **Entrada / gatilho:** `c6-queda-worker.sh`, `c7-duas-replicas.sh`, `c8-trigger-desligado.sh`.
- **Processamento:** usa a estrutura de V-11. **C6:** worker em regime; `docker kill -s KILL` aos
  3 min; novo worker aos 4 min; mede perda, duplicidade e tempo até `lag_ms` normalizar. **C7:**
  worker A em regime, sobe B, aos 3 min mata A; mede divisão e failover (≤ 20 s). **C8:** desabilita o
  trigger de captura de `obra_musical` por 1 min com carga, religa, roda `reconciliacao
  MODO=delta` e depois o worker; veredito pela verificação completa.
- **Saída observável:** pastas de resultados com o veredito de integridade.
- **Evidência / checkpoint:**
  - C6 e C7 → `meta.json` com `divergencias = 0`, `uuids_alterados = 0`, `failover_s ≤ 20` (C7).
  - C8 → `linhas_afetadas` (calculada pelo gerador pelo log de updates durante a janela) = linhas corrigidas; `divergencias_finais = 0`.
  - Tentar C8 sem reconciliar (`DESDE_MIN` curto demais, teste) → `REPROVADO` com divergências listadas, provando que o veredito não é automático.
- **Bloqueado por:** V-11

### V-13: C1, C10 e a medição de custo no legado

- **Cobre:** RF-10 (C1, C10), RF-05, objetivo 5
- **Entrada / gatilho:** `c1-carga-inicial.sh`, `c10-custo-legado.sh`.
- **Processamento:** **C1:** trunca o banco novo, executa `carga-inicial` com captura ligada, sobe o
  worker até pendentes = 0 e verifica. **C10:** mesma carga (taxa e semente fixas) com captura
  ligada e depois com os triggers de captura desabilitados; compara o tempo médio de
  transação medido no próprio gerador; repete cada modo pelo menos 3 vezes alternadas, para reduzir o
  efeito de vizinhos ruidosos, e reporta média e dispersão. O limite de aceitação é parâmetro e tem
  por padrão o critério da DT-09 (Q5 do PRD); com o limite desligado, o veredito é `SEM_CRITERIO` e o número é só reportado.
- **Saída observável:** `resultados/c1/`, `resultados/c10/`.
- **Evidência / checkpoint:**
  - C1 → contagens iguais, `divergencias = 0`, linhas/s por tabela e tempo total em `meta.json`.
  - C10 → overhead percentual e absoluto (ms por transação), dispersão entre repetições e veredito (`APROVADO`/`REPROVADO`/`SEM_CRITERIO`).
- **Bloqueado por:** V-11

### V-14: Os resultados aparecem ao vivo no Kibana

- **Cobre:** RF-13, DP-10
- **Entrada / gatilho:** processo exportador no laptop durante os cenários (opcional: o cenário não depende dele).
- **Processamento:** lê os CSVs dos workers e de recursos remotamente (ssh `tail -F`, sem copiar
  credenciais para os servidores — DT-05) e grava no Elastic com identificação de `cenario` e `execucao`,
  num índice/espaço de nomes exclusivo da PoC, usando a conta dedicada do `.env`. Cria o painel no
  Kibana. Falha do Elastic não interrompe o cenário. Os dados são escritos direto no Elasticsearch
  (`ELASTIC_HOST`); o painel é criado pela API de objetos salvos do Kibana (`KIBANA_HOST`). **O painel
  ainda não existe e sua criação é entrega desta fatia:** definição versionada no repositório,
  importável de forma idempotente (reexecutar não duplica) e removível pelo `derrubar.sh`.
- **Saída observável:** painel com eventos/s, p50/p95/p99, pendentes, lag e CPU/sessões dos dois bancos.
- **Evidência / checkpoint:**
    - Painel inexistente no Kibana → o comando de criação o cria; executar de novo não cria um segundo; o `derrubar.sh` o remove sem tocar nos painéis de outras aplicações.
  - Primeiro passo da fatia: confirmar com a conta do `.env` que ela escreve no índice da PoC e cria o painel, e que não tem escrita fora do namespace da PoC (tentativa de escrita em índice alheio deve falhar).
  - Durante `c2-regime.sh`, o painel (URL composta a partir de `KIBANA_HOST` do `.env`) mostra os valores do CSV com atraso ≤ 30 s.
  - Derrubar a conexão com o Elastic durante um cenário → CSVs e veredito intactos, cenário `APROVADO`/`REPROVADO` normal.
  - Consulta por `execucao` retorna só dados da PoC; `derrubar.sh` (com a opção de limpeza) remove índice e painel e confirma que nenhum dado de outra aplicação mudou.
  - `grep` das credenciais do `.env` em `resultados/`, nos logs do exportador, em `ps` e no `git diff` → nenhuma ocorrência.
- **Bloqueado por:** V-05

### V-15: O relatório consolida os números contra os critérios e recomenda

- **Cobre:** RF-11, DP-05, objetivo 6
- **Entrada / gatilho:** execução completa de V-11, V-12 e V-13; `scripts/relatorio.sh` (ou equivalente) consolida os `meta.json`.
- **Processamento:** gera tabela de critérios com valores medidos e veredito, lista cenários
  inválidos/não executados sem preencher, declara as condições do ambiente e a limitação do
  ambiente compartilhado, os pontos em aberto respondidos e não respondidos, e a recomendação
  (seguir, ajustar com indicação, descartar), escrita por quem analisa os números. O gerador do
  documento preenche as tabelas; a recomendação é texto humano.
- **Saída observável:** `resultados/relatorio.md`.
- **Evidência / checkpoint:**
  - Todas as linhas dos critérios do PRD (Métricas de Sucesso) aparecem com valor e veredito; um critério reprovado (provocado em teste) traz causa provável e ajuste.
  - Cenário removido de `resultados/` → aparece como `NAO_EXECUTADO`, nunca com valor.
  - O relatório inclui os limites de CPU/memória dos containers e a carga dos servidores durante cada cenário.
- **Bloqueado por:** V-11, V-12, V-13 (V-14 não bloqueia)

### Habilitadores inevitáveis

| Habilitador | Por que não cabe numa fatia | Menor escopo | Primeira fatia desbloqueada |
|---|---|---|---|
| EN-01 — esqueleto do projeto Java | Sem comportamento observável por si; V-03 e V-06 exigem build, `Main` com subcomandos e `Config`. Fazê-lo dentro de V-03 duplicaria a base em V-06. | `pom.xml` com `ojdbc11` como única dependência de runtime, Maven Wrapper, `Main` que despacha subcomandos, `Config` por variável de ambiente ou `-D`, gates de build da skill `java` aplicáveis sem Spring (DT-02) | V-03 (e V-06) |

---

## Contratos e Fronteiras

### Portas do núcleo

Contrato entre `core` e `oracle` e a fronteira para CDC futuro (RF-12). Comportamento, não assinatura:

| Porta | Comportamento obrigatório |
|---|---|
| `ChangeSource` | Entrega eventos de uma `(tabela, partição)` em ordem estável. Entregar não remove: só o `ack` de eventos já aplicados remove. Eventos não confirmados são entregues de novo. |
| `StateProvider` | Dado um conjunto de `codigo`, devolve o estado atual das linhas existentes; `codigo` ausente é informado explicitamente como inexistente, nunca como erro. |
| `ReplicaSink` | Aplicação idempotente com guarda de versão; atribui o `id` somente quando a linha é nova; o commit torna o lote durável; uma violação de unicidade por concorrência é sinalizada como reprocessável. |
| `TableSpec` | Descreve colunas e gera leitura e gravação; acrescentar tabela não toca o núcleo. |
| `ChangeEvent` | `(eventoId, tabela, codigo, op, criadoEm, versão?, estado?)`; `estado` nulo significa "buscar no `StateProvider`". |

### Esquemas físicos

DDL, colunas e tipos do legado, de `evento_replicacao`, das réplicas e das tabelas de controle estão
em `docs/escopo-construcao.md:58-129`; a PoC os adota sem alteração, com estes ajustes:

- `updated_at` do legado simulado é `TIMESTAMP(6)`, igual a `origem_updated_at` no banco novo (a
  verificação por hash precisa de tipos equivalentes). O tipo real do BDAD é a Q1 do PRD; as
  conclusões sobre empate de versão dependem dela e entram no relatório.
- `lease_particao` tem 48 linhas pré-criadas; cada tabela nova acrescenta 16. Cada claim incrementa
  `versao`, usada com dono e prazo para cercar atomicamente o commit no relógio do banco.

### Registro de resultado de cenário (`meta.json`)

Contrato entre os scripts de cenário (V-11..V-13) e o relatório (V-15). Campos mínimos, todos
calculados, nunca digitados:

| Campo | Conteúdo |
|---|---|
| `cenario`, `execucao`, `inicio`, `fim` | Identificação e janela (UTC) |
| `ambiente` | Limites de CPU/memória dos containers, versões das imagens e do jar, hosts |
| `validade` | `VALIDO` ou `INVALIDO` com motivo (`TAXA`, `RELOGIO`, `ABORTADO_SERVICO_EXISTENTE`) e, por degrau, `GERADOR_SATURADO` |
| `skew_ms` / `skew_incerteza_ms` | Diferença de relógio medida no início e no fim |
| `carga_servidores` | Carga média e containers existentes antes/durante/depois |
| `criterios[]` | Para cada critério: nome, valor medido, alvo, veredito (`APROVADO`, `REPROVADO`, `INVALIDO`, `SEM_CRITERIO`, `NAO_EXECUTADO`) |
| `integridade` | `divergencias`, `uuids_alterados`, caminhos dos snapshots e da saída da reconciliação |

### Segredos e dados sensíveis

| Dado | Criado | Copiado / persistido | Lido por | Descartado |
|---|---|---|---|---|
| Credenciais Elastic/Kibana (`KIBANA_HOST`, `ELASTIC_HOST`, `KIBANA_ELASTIC_USER`, `KIBANA_ELASTIC_PASSWORD`; as duas primeiras são endereços, as duas últimas são segredos) | Pelo responsável, em `.env` no laptop | **Somente** no `.env` do laptop, ignorado pelo git (`.gitignore` já contém `.env`). Nunca vão para os servidores, imagens, `docker inspect`, linha de comando, CSVs, `meta.json`, relatório ou logs. | Exportador no laptop, por variável de ambiente; enviadas só no cabeçalho de autenticação | Fim do processo exportador |
| Senhas dos Oracle da PoC (`bdad`, `replica`) | Compose da PoC | No ambiente dos containers `poc-`; descartáveis; removidas com `derrubar.sh` | Jar via variável de ambiente | `derrubar.sh` |
| `.env` | — | `subir.sh` aborta se `git check-ignore .env` falhar ou se o arquivo estiver rastreado | — | — |

---

## Arquivos

### A modificar

| Caminho | Fatia | O que muda |
|---|---|---|
| `.gitignore` | V-01 / EN-01 | Acrescentar o que o build gera e não deve ser versionado (`target/`, saídas brutas temporárias), mantendo `.env`. `resultados/` **é** versionado (entregável). O arquivo está sem quebra de linha final: tratar ao editar. |
| `README.md` | V-15 | Instruções de uso da PoC e referência ao relatório. |
| `flow-state.json` | — | Registrar a TechSpec (feito pelo fluxo, não pela implementação). |

### A referenciar (não alterar)

| Caminho | Por que consultar |
|---|---|
| `docs/adr-replocacao.md` | Decisões D1–D15, critérios de sucesso, cenários |
| `docs/escopo-construcao.md` | DDL, MERGE, componentes, variáveis de configuração, colunas do CSV, roteiro de cenários |
| `tasks/prd-poc-replicacao-bdad/prd.md` | RF, RN, DP, métricas de sucesso |
| `.claude/skills/java/SKILL.md` e `references/` | Convenções e gates de build que se aplicam sem Spring; desvios na seção Decisões Técnicas |

---

## Análise de Impacto

| Componente | Tipo | Impacto e risco | Ação requerida |
|---|---|---|---|
| `desenv-server` (8 vCPU, ~9 GB RAM livres, 80 GB livres de disco, carga ~1,1) | Compartilhado | Recebe `poc-legado` (2 CPUs, 3 GB) e o gerador (limite de 1 GB). Aplicações existentes e Kibana convivem no host. Orçamento total de RAM da PoC ≈ 4 GB. | Limites de cgroup por container, vigilância dos serviços existentes (DT-06), `derrubar.sh` com comparação de inventário |
| `infra-server` (8 vCPU, ~9 GB RAM livres, 385 GB livres, `postgres`, `rabbitmq`, `otel-collector` existentes) | Compartilhado | Recebe `poc-novo` (2 CPUs, 3 GB) e workers (limite de 1 GB cada, dois em C7). Orçamento ≈ 5 GB no pico (C7). | Idem; nenhum uso do Postgres/RabbitMQ existentes |
| Portas 1521/1522 de ambos | Rede | Livres hoje (verificado). Oracle exposto na rede interna com senhas descartáveis. | Verificação no pré-requisito; remoção em `derrubar.sh` |
| Elastic / Kibana | Compartilhado | Dados e painel da PoC convivem com os de outras aplicações. | Namespace exclusivo, limpeza em `derrubar.sh` |
| `.env` | Segredo | Contém credenciais de um serviço compartilhado. | Ver "Segredos e dados sensíveis" |

---

## Riscos e Preocupações

Não há código ainda; os locais referem as definições de origem que ainda serão implementadas.

| Preocupação | Local | Impacto | Mitigação |
|---|---|---|---|
| `MERGE` concorrente entre `carga-inicial` e worker na mesma linha nova pode violar a unicidade de `codigo` | `docs/escopo-construcao.md:115-129`, `:192` | Erro no lote; sem tratamento, o worker entra em laço de erro | `ReplicaSink` sinaliza como reprocessável e o recomeço do lote reaplica (V-04, V-10) |
| Hash por faixa (`ORA_HASH`, 32 bits) pode, com probabilidade residual, igualar faixas divergentes | `docs/escopo-construcao.md:198` | "Zero divergência" é probabilístico | Incluir `codigo` no hash por linha; `delta` complementa; o relatório declara a limitação |
| `SELECT COUNT(*)` de `pendentes` a cada 5 s com milhares de eventos | `docs/escopo-construcao.md:229` | Custo extra no legado, contaminando C10 | Medir a custo da própria consulta em C10 com e sem métricas; a consulta usa o índice `(tabela, particao, id)` |
| Dois workers no mesmo host em C7 compartilham CPU e rede | `docs/adr-replocacao.md:140` | Failover medido sob contenção, mais pessimista | Documentar no relatório; `--cpus` por worker |
| Gerador a 4.000 ev/s limitado pelos 2 CPUs do Oracle Free do legado | `docs/escopo-construcao.md:246`, `:67` | Degrau inválido por limite do legado, não do desenho | DT-07: `GERADOR_SATURADO` não reprova o C3; o relatório diz quem saturou (CPU do legado, do gerador ou do worker) com `docker stats` |
| Diferença de relógio entre os servidores contamina a latência | `docs/adr-replocacao.md:148`, PRD RF-08 | Percentis falsos | DT-04: medida com incerteza; inválido > 100 ms. Estado atual: ambos sincronizados via NTP, desvios medidos de 0,1 e 0,4 ms em 2026-10-31 |
| Triggers de captura e `evento_replicacao` rodando no legado de 2 CPUs com o gerador de 8 threads | `docs/escopo-construcao.md:208` | Gargalo no legado reduz a taxa alcançada | Taxa efetiva e validade em todo cenário (RF-07); `THREADS` ajustável |
| Aplicações existentes degradadas pela PoC | PRD, Riscos | Impacto em serviços do homelab | DT-06: tetos de cgroup, vigilância e aborto |
| Oracle Free 23ai exige memória e tempo de partida longos (`faststart`) | `docs/escopo-construcao.md:47` | `subir.sh` falha por timeout | Espera por `healthcheck.sh` com limite configurável e mensagem do último estado |

---

## Decisões Técnicas

- **DT-01 — Oracle nos dois lados da PoC (desvio do padrão `java`: PostgreSQL).**
  - **Racional:** o ADR e o escopo definem dois Oracle e o responsável confirmou em 2026-10-31.
    `MERGE`, `RAW(16)`, `ORA_HASH` e a hash por faixa são parte do que se mede.
  - **Trade-offs:** os números de gravação não se transferem para um destino PostgreSQL
    (`INSERT … ON CONFLICT`, UUID nativo); a PoC deve declarar isso no relatório.
  - **Alternativas rejeitadas:** destino PostgreSQL — reescreveria o `MERGE`, os tipos e a verificação
    por hash e afastaria a PoC do roteiro aprovado. Se o relatório recomendar seguir, o próximo passo
    é repetir a medição no destino de produção.
- **DT-02 — Sem Spring, módulo único, sem Flyway e com `Uuid7` próprio (desvios da skill `java`).**
  - **Racional:** o PRD (restrição) e o escopo exigem medir o pipeline sem o custo do framework e com
    `ojdbc11` como única dependência de runtime; UUIDv7 monotônico é parte do que se testa.
  - **Mantém da skill:** Java 25, `Clock` injetado, `Instant` para instantes, sem `UUID.randomUUID()`,
    formatação, assinaturas `forbiddenapis` e `jdk-unsafe` aplicáveis sem Spring, testes unitários do núcleo
    sem banco. O Maven local é 3.8.7 (abaixo do 3.9 exigido): usar Maven Wrapper.
  - **Trade-offs:** o código não segue Clean Architecture em módulos Maven nem a camada `@UseCase`;
    é uma PoC descartável cuja fronteira útil é `core` × `oracle` (RF-12).
- **DT-03 — Execução em containers efêmeros, sem instalar nada nos hosts.**
  - **Racional:** o `infra-server` não tem Java e o `desenv-server` tem Java 21; instalar JDK 25 mexeria nos
    servidores compartilhados. As imagens `eclipse-temurin:25-jre` e `gvenzl/oracle-free:23-slim-faststart`
    foram confirmadas como baixáveis nos dois servidores. O jar é compilado no laptop (Java 25.0.4) e copiado.
  - **Trade-offs:** overhead de container desprezível; `docker kill` serve de `kill -9` nos cenários.
- **DT-04 — Latência medida com o relógio do worker e erro de relógio estimado por ida e volta.**
  - **Racional:** `criado_em` vem do relógio do servidor do legado e o fim da medição vem do worker, no
    outro servidor. O worker lê `SYSTIMESTAMP` do legado e estima a diferença de relógio por ponto médio
    do tempo de ida e volta, com incerteza igual à metade desse tempo; mede no início, a cada minuto e no fim do cenário.
  - **Trade-offs:** a incerteza fica da ordem do RTT na LAN (milissegundos), pequena frente aos 5 s.
  - **Alternativas rejeitadas:** usar o relógio do banco novo (`replicado_em`) — é lido antes do commit, subestima a latência.
- **DT-05 — O exportador para o Elastic roda no laptop.**
  - **Racional:** mantém as credenciais fora dos servidores (regra de proteção do PRD, RF-13).
  - **Trade-offs:** o laptop precisa estar ligado durante o cenário para ver o painel; os CSVs, que são o
    registro oficial, não dependem dele (DP-10).
  - **Alternativas rejeitadas:** exportador nos servidores — exigiria copiar o `.env` para discos compartilhados.
- **DT-06 — Vigilância dos serviços existentes durante cenários.**
  - **Racional:** o PRD exige interromper se um serviço existente degradar. Operacionalização mínima: o
    inventário de containers existentes é comparado a cada 30 s; se algum deixar de estar `running`,
    reiniciar ou sair do estado `healthy`, o runner encerra o gerador e marca `ABORTADO_SERVICO_EXISTENTE`.
  - **Trade-offs:** não detecta degradação de latência dos outros serviços; só indisponibilidade.
- **DT-07 — Interpretação de DP-02 para a escada do C3.**
  - **Racional:** a taxa de 4.000 ev/s pode superar o que o legado de 2 CPUs aceita. Tratar o degrau como
    cenário inteiro inválido apagaria os degraus válidos já medidos. Um degrau cujo gerador não alcança 98%
    é registrado como `GERADOR_SATURADO` e encerra a escada; o C3 é decidido com os degraus válidos.
  - **Trade-offs:** o teto "≥ 2.000 ev/s" só é aprovado se o degrau de 2.000 for válido; se o gerador saturar
    antes, o C3 fica `INVALIDO` e é repetido com mais conexões ou outro host. Confirmada pelo responsável em 2026-10-31.
- **DT-08 — C10 repete as medições.**
  - **Racional:** num servidor compartilhado, uma única medição com e sem trigger confundiria ruído com
    custo da captura. Três repetições alternadas e dispersão reportada.

- **DT-09 — Critério padrão de aceitação do C10 (proposto, a confirmar com o time do legado).**
  - **Decisão:** a captura é aceitável se, a 1.000 ev/s, o tempo médio de transação no gerador aumentar **no
    máximo 15% e no máximo 5 ms** em relação à execução sem captura; os dois limites valem juntos e o
    relatório mostra ambos.
  - **Racional:** o trigger adiciona um INSERT indexado por linha alterada, e o BDAD é usado por uma aplicação
    cliente/servidor interativa. O percentual sozinho engana: uma transação de 2 ms com +40% (0,8 ms) é
    imperceptível, e uma de 100 ms com +10% (10 ms) já pesa. O teto absoluto protege o usuário e o percentual
    protege o banco sob carga em lote.
  - **Trade-offs:** os números são de um Oracle Free de 2 CPUs com trigger simples, que pode ser mais lento ou
    mais rápido que o BDAD real; por isso são parâmetros e o resultado é reportado mesmo quando o veredito é
    `SEM_CRITERIO`.
  - **Alternativas rejeitadas:** só percentual (distorcido por transações curtas); só absoluto (ignora a
    carga em lote); 5%, que um INSERT adicional por linha dificilmente cumpre e reprovaria o desenho por um
    critério sem lastro.

> Nenhuma ADR nova: o desenho já está no ADR do projeto (`docs/adr-replocacao.md`, status "proposto,
> pendente de PoC"), e as decisões acima são locais a esta PoC. Não existe `docs/adr/` nem decisão `Accepted`
> conflitante. Se a PoC aprovar o desenho, o ADR de replicação deve ser movido para `docs/adr/` como `Accepted`
> (ação do relatório, V-15).

---

## Verificação

Só o que foge do padrão.

- **Cenários críticos não óbvios:**
  - Queda do banco novo ou do worker entre o commit no novo e o descarte no legado (V-04, C6).
  - Dois escritores na mesma linha nova (carga inicial + worker) (V-04, V-10).
  - Lease vencido com worker pausado que retoma (V-07).
  - Transação longa: eventos só ficam visíveis ao commit; o worker nunca os vê antes; a latência é
    reportada à parte (V-05, C5).
  - Rajada de 50.000 eventos distribuídos nas 16 partições da tabela (C4).
  - Dados injetados com defeito para provar que o veredito não é automático (V-12, V-15).
- **Dados ou ambiente especiais:** os dois servidores, `.env` com as quatro chaves de Elastic/Kibana, ssh sem
  senha para `infra-server` e `desenv-server`, Docker em ambos, Java 25 e `mvnw` no laptop. Os
  testes unitários do núcleo e a compilação não precisam de servidores; os testes de integração
  usam os bancos da PoC (não Testcontainers — o ambiente real é parte da evidência) e rodam contra
  os containers `poc-` já em pé. Nenhum teste toca serviços existentes.
- **Observabilidade além do padrão:** o CSV por worker (colunas do escopo + relógio + transação
  longa) e o CSV de recursos são o produto; logs do worker em linha simples no console, sem
  credenciais.
- **Verificação dos contratos:** N/A (sem contratos HTTP/mensagens/dados externos).

---

## Questões em Aberto

- [ ] **Q-T3 — Confirmar o critério da DT-09** (≤ 15% e ≤ 5 ms de overhead no tempo médio de transação). — Time do legado — sem confirmação o C10 roda com o padrão e o veredito é registrado como "segundo critério proposto".
- [ ] Demais pendências de legado do PRD (Q1, Q2, Q3, Q6, Q7, Q8) seguem como estão e não bloqueiam a implementação.

---

## Architecture Decision Records

Nenhuma ADR nova. A decisão arquitetural em validação é o ADR de replicação do projeto,
`docs/adr-replocacao.md` (status: proposto, pendente de PoC). Não existe `docs/adr/index.md`; a primeira
ADR numerada a ser criada deve partir de `adr-001`, e o ADR de replicação é o candidato natural, após
a recomendação de V-15.
