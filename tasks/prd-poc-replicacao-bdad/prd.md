---
tsg_artifact: prd
status: approved
mode: standalone
capability: null   # standalone — sem backlog/CAP-XXX disponível; atribuir quando o backlog existir
slug: poc-replicacao-bdad
created: 2026-10-31
approved: 2026-10-31
origins:
  - path: docs/adr-replocacao.md
    version: "2026-10-30 (ADR proposto, pendente de PoC)"
  - path: docs/escopo-construcao.md
    version: "2026-10-30"
---

# PoC de Replicação BDAD → Banco Novo (sem CDC)

## Visão Geral

O cadastro musical (obra musical, fonograma, titular e correlatas) continua sendo mantido no BDAD
(Oracle, alimentado por uma aplicação Delphi), mas o novo sistema Java precisa lê-lo com
identificador UUIDv7. O ADR de replicação propõe espelhar essas tabelas, somente leitura, num banco
novo: o legado registra *que* uma linha mudou, e um processo separado busca o estado atual e o grava
no novo, sem CDC, sem Kafka e sem alterar a aplicação Delphi.

O desenho é plausível, mas está **proposto e não validado**. Esta entrega é a prova de conceito que
transforma o ADR em decisão: ela precisa demonstrar, com números, que o desenho (1) sustenta o
volume esperado dentro do SLA, (2) não perde nem corrompe dados diante de falhas, e (3) custa pouco
ao legado. O resultado é uma recomendação objetiva — seguir ou ajustar o desenho — baseada em
evidência reproduzível.

**Para quem:** o time responsável pela migração (decisão de arquitetura) e o time do legado/BDAD
(que precisa aceitar o custo da captura na sua base). **Valor:** reduzir o risco de comprometer a
migração com um desenho de replicação que só falharia em produção.

---

## Rastreabilidade

### Capacidade e fronteiras

- **Capacidade:** não há backlog de capacidades disponível (modo standalone). Esta entrega é a
  validação, por PoC, do ADR "Replicação BDAD para o banco novo (sem CDC)".
- **Escopo desta entrega:** a PoC completa descrita no ADR: três tabelas (titular, obra_musical,
  fonograma), um worker, carga inicial, reconciliação, gerador de carga, coleta de métricas, os dez
  cenários C1–C10 e o relatório final com recomendação.
- **Fora desta entrega:** implantação em produção; tabelas além das três; qualquer integração com a
  aplicação Delphi real; Kafka/CDC (apenas preservada a possibilidade de troca futura).
- **Domínios atravessados:** nenhum domain doc disponível. O vocabulário vem do ADR.
- **Restrições do baseline:** o legado só admite mudanças restritas (somente a tabela de eventos e
  os gatilhos de captura são nossos); infraestrutura sem Kafka/CDC; a PoC roda em dois servidores internos compartilhados (ver DP-06), com rede real entre eles.

### Regras nascidas neste PRD

Sem domain doc, as regras de comportamento abaixo nascem aqui. Elas derivam das decisões D1–D15 do
ADR e são referenciadas nos requisitos.

| ID | Regra |
|---|---|
| RN-01 | O evento de mudança identifica apenas *qual* linha mudou e *qual* operação; o conteúdo vem do estado atual no legado, nunca do evento. |
| RN-02 | Todas as mudanças de uma mesma linha (`codigo`) são processadas por um único dono por vez e em ordem. |
| RN-03 | Aplicar um mesmo estado mais de uma vez produz o mesmo resultado (idempotência). |
| RN-04 | Um estado mais antigo (por `updated_at`) nunca sobrescreve um mais novo na réplica. |
| RN-05 | O identificador UUIDv7 de uma linha é atribuído uma única vez, na primeira gravação, e nunca muda. |
| RN-06 | Um evento só é descartado depois de seu efeito estar confirmado no banco novo. |
| RN-07 | Exclusão é replicada como alteração de estado (soft delete). Linha inexistente no legado é anomalia a investigar, não exclusão. |
| RN-08 | Referências entre tabelas são mantidas por `codigo`, sem exigir que a linha referenciada já exista na réplica. |
| RN-09 | A latência de um evento é medida do ponto de vista do usuário do legado: do registro no legado até a confirmação no banco novo. |
| RN-10 | A réplica nunca é a fonte da verdade: qualquer divergência é corrigida a partir do legado. |

---

## Termos Canônicos

| Termo | Definição de negócio | Escopo/Fonte |
|---|---|---|
| BDAD / legado | Banco Oracle de origem, mantido pela aplicação Delphi; fonte da verdade do cadastro. | ADR |
| Banco novo / réplica | Cópia somente leitura do cadastro, consumida pelo sistema Java. | ADR |
| `codigo` | Chave de negócio da linha no legado (antes o PK). Permanece única e é a referência entre tabelas. | ADR |
| `id` | Identificador UUIDv7 da linha na réplica. | ADR |
| Evento de mudança | Registro de que uma linha foi inserida, alterada ou excluída, sem o conteúdo da linha. | ADR |
| Partição lógica | Subconjunto fixo de `codigo`s de uma tabela (16 por tabela) que tem um único dono por vez. | ADR |
| Lease | Posse temporária e renovável de uma partição por um worker. | ADR |
| Coalesce | Reunir vários eventos do mesmo `codigo` num lote em uma única busca de estado. | ADR |
| Anomalia | Evento cuja linha não existe mais no legado. | ADR |
| Reconciliação | Comparação entre legado e réplica que reinsere como evento toda linha divergente. | ADR |
| Lag | Idade do evento pendente mais antigo. | ADR |
| Rajada | Alteração em massa de muitas linhas numa só transação. | ADR |
| Regime | Carga contínua sustentada (alvo: 1.000 eventos/s). | ADR |

---

## Objetivos

1. **Provar capacidade:** a 1.000 eventos/s por 10 minutos, com um worker, a latência p95 fica em
   até 5 s e o lag não apresenta tendência de alta.
2. **Medir folga:** identificar a taxa em que o lag passa a crescer; o critério é teto de pelo
   menos 2.000 eventos/s com um worker (folga de 2×).
3. **Provar integridade:** após queda de worker, entrada de segunda réplica, trigger desligado e
   carga inicial, a verificação completa não encontra divergência e nenhum UUID muda.
4. **Provar tolerância a falha de captura:** quando a captura é desligada por 1 minuto, a
   reconciliação corrige 100% das linhas afetadas.
5. **Quantificar o custo ao legado:** medir o overhead percentual da captura no tempo médio de
   transação, para o time do legado decidir se é aceitável.
6. **Entregar uma decisão:** relatório curto, com números contra os critérios, e recomendação
   explícita de seguir ou ajustar o desenho.

---

## Histórias de Usuário

- Como **Arquiteto responsável pela migração**, quero resultados medidos de vazão, latência e
  integridade contra critérios definidos de antemão, para decidir se o ADR passa de proposto a
  aceito.
- Como **Responsável pelo legado (BDAD)**, quero saber quanto a captura aumenta o tempo das
  transações e quanta carga adicional ela gera, para dizer se o legado aceita o desenho.
- Como **Engenheiro da migração**, quero um ambiente que sobe com um comando e cenários
  reexecutáveis, para repetir um resultado ou testar um ajuste no desenho sem montar nada à mão.
- Como **Engenheiro da migração**, quero que a PoC mostre o que acontece quando algo falha (worker
  morto, captura desligada, transação longa, rajada), para conhecer os modos de falha antes da
  produção.
- Como **Engenheiro da migração**, quero que a troca futura da fonte de mudanças (CDC/Kafka) não
  exija reescrever o destino, para que a decisão de hoje não bloqueie a de amanhã.

---

## Funcionalidades Principais

### RF-01: Ambiente reproduzível com massa de dados

**Descrição**: Um único comando, executado a partir do laptop por ssh, sobe dois bancos Oracle independentes — o legado simulado no `desenv-server` e o banco novo no `infra-server` —, aplica os esquemas e carrega a massa de teste: 200.000 titulares (1 em 7 pessoa jurídica), 500.000
obras musicais (5 gêneros) e 1.000.000 fonogramas referenciando obras por `codigo`. Volumes são
configuráveis. Os esquemas são simplificados (`codigo`, colunas de negócio, `excluido`,
`updated_at`). O ambiente pode ser reaplicado sem recriar os bancos. Como os servidores são compartilhados com outras aplicações, a subida não pode alterar nem interromper nenhum serviço já existente neles, e cada banco tem teto de CPU e memória próprio, de modo que a PoC e as aplicações existentes não se prejudiquem.

**Critérios de Aceitação**:

- **Given** os dois servidores acessíveis por ssh, com a memória e o disco necessários livres
  **When** o comando de subida é executado
  **Then** o banco legado fica saudável no `desenv-server`, o banco novo no `infra-server`, os
  esquemas são aplicados e a contagem de linhas por tabela é impressa, batendo com os volumes
  configurados.
- **Given** os dois servidores
  **When** a verificação de pré-requisitos roda antes da subida
  **Then** confirma portas livres para os dois Oracle, memória e disco suficientes, acesso ssh e Docker, e relógios sincronizados entre os servidores; qualquer falha interrompe a subida com mensagem que diz o que corrigir.
- **Given** os containers e serviços que já rodam nos servidores
  **When** a PoC sobe, roda e é removida
  **Then** nenhum deles é parado, reiniciado ou reconfigurado, e a remoção da PoC libera os recursos
  que ela usou (containers, volumes e portas).
- **Given** os dois bancos em execução
  **When** o limite de recursos de cada um é consultado
  **Then** estão registrados (CPU e memória) para constar no relatório.
- **Given** um ambiente já em pé
  **When** os esquemas são reaplicados
  **Then** o ambiente volta ao estado inicial sem recriar os containers.
- **Given** a massa carregada
  **When** a carga termina
  **Then** a captura está ligada e nenhuma linha da massa gerou evento pendente.
- **Given** um volume diferente configurado
  **When** a massa é gerada
  **Then** as contagens refletem o volume configurado, mantendo a proporção de referências
  fonograma → obra válida.

**Prioridade**: Must Have

**Rastreabilidade**: —

---

### RF-02: Captura de mudanças no legado

**Descrição**: Toda inserção, alteração ou exclusão nas três tabelas do legado produz um evento de
mudança com: tabela, `codigo`, operação (I, U, D ou R para reconciliação), partição lógica e
instante de criação. O evento não carrega o conteúdo da linha. A partição é derivada
deterministicamente do `codigo`, de modo que um mesmo `codigo` cai sempre na mesma partição (16
partições por tabela). Só a tabela de eventos e a captura são acrescentadas ao legado; a PoC
simula o preenchimento de `updated_at` do legado por um mecanismo separado, identificado como
simulação.

**Critérios de Aceitação**:

- **Given** o ambiente em pé e a captura ligada
  **When** um `UPDATE` manual é feito numa linha do legado
  **Then** surge exatamente um evento com tabela, `codigo`, operação `U` e a partição esperada.
- **Given** uma inserção, uma alteração e uma exclusão na mesma linha, cada uma em sua transação
  **When** os eventos são consultados
  **Then** existem três eventos, de operações I, U e D, todos na mesma partição.
- **Given** uma transação revertida (rollback)
  **When** os eventos são consultados
  **Then** nenhum evento dessa transação existe.
- **Given** uma única transação alterando 50.000 linhas
  **When** ela é confirmada
  **Then** existem 50.000 eventos, um por linha.
- **Given** um evento criado
  **When** seu conteúdo é inspecionado
  **Then** não contém colunas de negócio da linha.

**Prioridade**: Must Have

**Rastreabilidade**: RN-01, RN-02

---

### RF-03: Replicação do estado atual para o banco novo

**Descrição**: Para cada partição, o processador lê um lote de até 500 eventos em ordem, reúne os
eventos do mesmo `codigo`, busca o estado atual dessas linhas no legado, grava-as no banco novo e
só então descarta os eventos aplicados. A gravação é idempotente e protegida por versão: só aplica
se o `updated_at` recebido for maior ou igual ao gravado. Cada linha da réplica tem `id` UUIDv7
gerado apenas na primeira gravação, o `codigo` único, a versão de origem e o instante da
replicação. Referências entre tabelas ficam por `codigo`, sem chave estrangeira física. Exclusão
chega como alteração de estado; linha inexistente na origem é registrada como anomalia com alerta.
Uma falha em qualquer passo descarta a tentativa, aguarda e reprocessa os eventos ainda não
descartados.

**Critérios de Aceitação**:

- **Given** o worker em execução e a réplica em dia
  **When** uma linha é inserida ou alterada no legado
  **Then** a linha correspondente aparece ou é atualizada no banco novo em menos de 1 s, e o evento
  é descartado.
- **Given** vários eventos do mesmo `codigo` no mesmo lote
  **When** o lote é processado
  **Then** o estado é buscado e gravado uma única vez e a taxa de coalesce reportada reflete a
  razão eventos/linhas.
- **Given** um evento reprocessado (por exemplo, após falha entre gravação e descarte)
  **When** o lote é aplicado novamente
  **Then** a réplica fica idêntica à de uma única aplicação e o `id` da linha não muda.
- **Given** uma linha com `updated_at` mais novo já gravado na réplica
  **When** chega um estado com `updated_at` mais antigo
  **Then** a réplica mantém o estado mais novo.
- **Given** uma linha excluída logicamente no legado (`excluido` ativo)
  **When** o evento é processado
  **Then** a linha existe na réplica com `excluido` ativo.
- **Given** um evento cuja linha não existe mais no legado
  **When** o lote é processado
  **Then** a anomalia é contada, um alerta é emitido, o evento é descartado e o restante do lote é
  aplicado normalmente.
- **Given** um fonograma cuja obra referenciada ainda não foi replicada
  **When** o fonograma é replicado
  **Then** a gravação é aceita e a referência é mantida por `codigo`.
- **Given** uma queda do banco novo ou do legado durante um lote
  **When** o processador se recupera
  **Then** reprocessa os eventos pendentes sem perda e sem duplicidade de linhas.
- **Given** uma partição sem eventos
  **When** o processador aguarda
  **Then** volta a consultar no intervalo configurado e reage ao próximo evento dentro dele.

**Prioridade**: Must Have

**Rastreabilidade**: RN-01, RN-02, RN-03, RN-04, RN-05, RN-06, RN-07, RN-08

---

### RF-04: Posse e distribuição de partições entre réplicas do worker

**Descrição**: Cada partição (3 tabelas × 16 = 48) tem no máximo um dono por vez, concedido por
lease renovável. Ao subir, cada worker declara que está vivo; as partições são divididas de forma
justa entre os workers ativos. Quando um worker entra, cede partições; quando um worker cai ou para,
seus leases vencem e os demais assumem. No desligamento ordenado, o worker para de processar antes
de liberar os leases. Adicionar uma tabela adiciona mais 16 partições.

**Critérios de Aceitação**:

- **Given** um único worker ativo
  **When** ele estabiliza
  **Then** possui as 48 partições.
- **Given** um worker A com 48 partições
  **When** um worker B sobe
  **Then** as partições convergem para 24/24 sem que nenhuma fique com dois donos processando ao
  mesmo tempo.
- **Given** dois workers com 24 partições cada
  **When** um é morto abruptamente
  **Then** o sobrevivente assume as 48 partições em até 20 s e o lag volta ao patamar normal.
- **Given** um worker pausado cujo lease venceu
  **When** outro worker assume a partição e o primeiro retoma
  **Then** a guarda de versão impede que dado velho sobrescreva dado novo.
- **Given** um desligamento ordenado
  **When** o worker é encerrado
  **Then** os processadores param antes da liberação dos leases e os demais assumem as partições.

**Prioridade**: Must Have

**Rastreabilidade**: RN-02, RN-04

---

### RF-05: Carga inicial

**Descrição**: Copia o conteúdo existente do legado para o banco novo, por faixas de `codigo`, com
a captura já ligada e a mesma regra de gravação do worker. Permite escolher as tabelas e o grau de
paralelismo e informa linhas/s por tabela e tempo total. Nunca esvazia e reinsere linhas já
carregadas e portanto nunca regera UUIDs.

**Critérios de Aceitação**:

- **Given** o banco novo vazio, a captura ligada e 1,7 milhão de linhas no legado
  **When** a carga inicial roda e depois o worker zera os pendentes
  **Then** as contagens são iguais nas duas bases e a verificação completa não encontra divergência.
- **Given** alterações ocorridas no legado durante a carga
  **When** a carga e o worker terminam
  **Then** a réplica reflete o estado mais recente de cada linha alterada, sem conflito.
- **Given** uma carga interrompida e reexecutada
  **When** ela termina
  **Then** nenhuma linha tem `id` diferente do da primeira gravação.
- **Given** a opção de escolher tabelas
  **When** apenas uma é indicada
  **Then** somente ela é copiada.
- **Given** o fim da carga
  **When** o resumo é exibido
  **Then** mostra linhas/s por tabela e tempo total.

**Prioridade**: Must Have

**Rastreabilidade**: RN-03, RN-05

---

### RF-06: Reconciliação

**Descrição**: Detecta e corrige divergências entre legado e réplica, com dois modos. O modo
**delta** compara as linhas alteradas numa janela de tempo (com sobreposição) entre as bases. O
modo **completo** compara todas as linhas por faixa de 100.000 `codigo`, aprofundando somente nas
faixas divergentes. Cada divergência (linha ausente ou mais antiga na réplica) gera um evento
reprocessável. Um modo de simulação apenas conta as divergências sem corrigir; é o verificador
oficial de correção ao fim de cada cenário.

**Critérios de Aceitação**:

- **Given** a captura desabilitada numa tabela por 1 minuto com carga ativa
  **When** a captura é religada e a reconciliação delta roda, e depois o worker zera os pendentes
  **Then** a verificação completa não encontra divergência (100% das linhas afetadas corrigidas).
- **Given** bases idênticas
  **When** a verificação completa roda
  **Then** reporta zero divergências.
- **Given** uma linha alterada diretamente na réplica (corrupção simulada)
  **When** a verificação completa roda
  **Then** ela é identificada e a faixa correspondente é apontada.
- **Given** o modo de simulação ativo
  **When** a reconciliação roda sobre bases divergentes
  **Then** informa a quantidade de divergências e não cria nenhum evento.
- **Given** uma linha ausente na réplica
  **When** a reconciliação roda sem simulação
  **Then** um evento de operação R é criado para o `codigo` e a linha é aplicada pelo worker.

**Prioridade**: Must Have

**Rastreabilidade**: RN-10

---

### RF-07: Gerador de carga

**Descrição**: Produz tráfego controlado sobre o legado para exercitar o desenho. Parametrizável
por taxa-alvo (eventos/s), duração, conexões, linhas por transação (uniforme entre 1 e o máximo),
mix de tabelas, percentual de inserts e de soft deletes, percentual de operações concentradas em 1%
das chaves (para exercitar o coalesce), rajada (um único `UPDATE` em massa numa transação) e
transação deliberadamente longa. Informa a taxa efetivamente alcançada a cada 5 s.

**Critérios de Aceitação**:

- **Given** a taxa-alvo de 2.000 eventos/s
  **When** o gerador roda
  **Then** a taxa alcançada é ≥ 98% da alvo.
- **Given** uma taxa-alvo não atingida
  **When** o gerador informa a taxa alcançada
  **Then** o resultado do cenário é marcado como inválido, por o gargalo estar na geração e não no
  desenho.
- **Given** o mix 40/40/20
  **When** o gerador roda por tempo suficiente
  **Then** a distribuição observada de eventos por tabela é compatível com o mix.
- **Given** o modo de rajada com 50.000 linhas
  **When** o gerador executa
  **Then** ocorre um único `UPDATE` de 50.000 linhas numa só transação.
- **Given** o modo de transação aberta de 30 s
  **When** o gerador executa
  **Then** mantém uma transação com alterações aberta por 30 s e a confirma.
- **Given** a opção de concentração de operações ativa
  **When** o gerador roda
  **Then** o percentual configurado das operações recai sobre 1% das chaves.

**Prioridade**: Must Have

**Rastreabilidade**: —

---

### RF-08: Métricas e saída dos resultados

**Descrição**: Cada worker registra, a cada 5 s, em arquivo CSV próprio e no console: partições
ativas, eventos/s, linhas/s, coalesce, latência por evento (p50, p95, p99, máx), pendentes, lag,
anomalias e tempo médio por fase do lote (leitura, busca, gravação, descarte). A latência é o
instante da confirmação no novo menos o instante de criação do evento (RN-09). Como esses dois instantes vêm de servidores diferentes, a diferença entre seus relógios é medida e reportada, e a latência só vale se essa diferença for desprezível diante do SLA. Fora do worker, são coletados por cenário o uso de CPU e memória dos dois bancos e as sessões ativas em cada um. O CSV é o registro oficial dos resultados (DP-10).

**Critérios de Aceitação**:

- **Given** um cenário curto (1 minuto) em regime
  **When** o worker roda
  **Then** o CSV contém todas as colunas preenchidas em cada janela de 5 s.
- **Given** dois workers ativos
  **When** ambos rodam
  **Then** cada um grava seu próprio arquivo, identificável pelo identificador do worker.
- **Given** uma janela sem tráfego
  **When** a métrica é registrada
  **Then** não há erro de cálculo (valores nulos ou zero tratados) e `pendentes` é 0.
- **Given** uma transação longa no legado
  **When** o evento é confirmado
    **Then** a latência inclui o tempo até o commit dessa transação (RN-09) e isso está documentado
  no relatório.
- **Given** os dois servidores de banco
  **When** um cenário começa
  **Then** a diferença entre seus relógios é medida e registrada junto aos resultados; se exceder
  100 ms, o cenário é sinalizado como inválido até a sincronização ser corrigida.

**Prioridade**: Must Have

**Rastreabilidade**: RN-09

---

### RF-09: Verificação de identidade (snapshot de IDs)

**Descrição**: Exporta o par `codigo`/`id` da réplica e compara dois snapshots, listando todo UUID
que mudou. Usado antes e depois de cada cenário para provar que nenhum UUID é regerado.

**Critérios de Aceitação**:

- **Given** dois snapshots idênticos
  **When** são comparados
  **Then** o resultado é zero diferenças.
- **Given** dois snapshots com um `id` diferente para o mesmo `codigo`
  **When** são comparados
  **Then** o `codigo` é listado com os dois valores.
- **Given** linhas novas entre os snapshots
  **When** são comparados
  **Then** as novas linhas não são tratadas como alteração.

**Prioridade**: Must Have

**Rastreabilidade**: RN-05

---

### RF-10: Cenários de validação automatizados (C1–C10)

**Descrição**: Cada cenário é executável de ponta a ponta por um único script, que reproduz os
passos, coleta os resultados e os arquiva por cenário. Todo cenário começa com a réplica em dia
(pendentes = 0) e um snapshot de IDs, e termina com verificação completa em modo de simulação e novo
snapshot.

| # | Cenário | Pronto quando |
|---|---|---|
| C1 | Carga inicial de 1,7 milhão de linhas com captura ligada | Contagem igual nas duas bases e verificação completa sem divergência |
| C2 | Regime: 1.000 ev/s por 10 min, 1 worker | 10 min de métricas com p95 e lag |
| C3 | Teto: 500, 1.000, 2.000 e 4.000 ev/s, 5 min cada | Identificada a taxa em que o lag passa a crescer |
| C4 | Rajada de 50.000 linhas sobre regime de fundo de 1.000 ev/s (após 2 min) | Em até 120 s após o commit da rajada: pendentes no patamar anterior e p95 de volta a ≤ 5 s; zero evento perdido e zero divergência (DP-07) |
| C5 | Transação aberta por 30 s sobre regime de fundo | Durante os 30 s, p95 dos eventos das demais transações ≤ 5 s; eventos da transação longa aplicados em até 5 s após o commit; zero perda e zero divergência (DP-08) |
| C6 | `kill -9` no worker aos 3 min; religado aos 4 min | Zero divergência, zero UUID alterado, tempo até o lag normalizar |
| C7 | Worker A em regime; sobe B; aos 3 min mata A | Partições 24/24; B com 48 em até 20 s |
| C8 | Captura de uma tabela desligada por 1 min com carga; religada; reconciliação delta | Verificação completa sem divergência |
| C9 | C2 e C3 repetidos com threads de plataforma em vez de virtuais | Tabela comparando vazão e p95 |
| C10 | Mesma carga com e sem captura | Overhead percentual no tempo médio de transação |

**Critérios de Aceitação**:

- **Given** o ambiente em pé
  **When** o script de um cenário é executado
  **Then** executa os passos de ponta a ponta, sem intervenção manual, e salva os resultados do
  cenário em pasta própria.
- **Given** o gerador que não atingiu a taxa-alvo num cenário
  **When** o cenário termina
  **Then** o cenário é sinalizado como inválido e precisa ser repetido.
- **Given** qualquer cenário concluído
  **When** a verificação final roda
  **Then** o resultado de integridade (divergências e UUIDs alterados) está registrado junto às
  métricas.
- **Given** um cenário já executado
  **When** é executado novamente
  **Then** parte de um estado limpo e comparável (réplica em dia, snapshot novo).

**Prioridade**: Must Have

**Rastreabilidade**: RN-02, RN-03, RN-05, RN-06, RN-10

---

### RF-11: Relatório final e recomendação

**Descrição**: Documento curto que consolida os resultados de cada cenário numa tabela contra os
critérios de sucesso, registra as condições do ambiente e suas limitações, responde cada ponto em
aberto que a PoC conseguiu esclarecer e termina com a recomendação: seguir com o desenho, ajustá-lo
(indicando o quê) ou descartá-lo.

**Critérios de Aceitação**:

- **Given** todos os cenários concluídos
  **When** o relatório é produzido
  **Then** cada critério de sucesso tem seu resultado medido, aprovado ou reprovado, com referência
  aos dados.
- **Given** um critério reprovado
  **When** o relatório é lido
  **Then** aponta a causa provável e o ajuste proposto ao desenho.
- **Given** o relatório
  **When** é lido por quem não participou da PoC
  **Then** declara que o ambiente (bancos de 2 CPUs e 3 GB em servidores compartilhados, rede interna entre eles) fornece um piso de capacidade e um teto de latência de rede, que a carga de outras aplicações dos servidores pode ter influenciado as medidas, e que não equivale à produção.
- **Given** um cenário inválido ou não executado
  **When** o relatório é produzido
  **Then** isso é declarado explicitamente, sem preencher o resultado.

**Prioridade**: Must Have

**Rastreabilidade**: RN-09

---

### RF-12: Fronteira entre fonte de mudanças e destino

**Descrição**: O comportamento de replicação (coalesce, guarda de versão, atribuição de UUID) é
independente da forma como as mudanças são capturadas. Acrescentar uma nova tabela é declarativo
(descrever suas colunas), sem reescrever a lógica de replicação. Isso preserva a possibilidade de
trocar a captura por CDC/Kafka sem alterar o destino.

**Critérios de Aceitação**:

- **Given** os testes automatizados do comportamento de replicação
  **When** executados sem banco de dados
  **Then** passam: coalesce, formação de UUIDv7 (versão, variante e ordem monotônica) e geração dos
  comandos de leitura/gravação a partir da descrição da tabela.
- **Given** uma fonte de mudanças alternativa que entregue eventos no mesmo formato
  **When** conectada ao destino
  **Then** o destino a consome sem alterações.
- **Given** uma nova tabela descrita declarativamente
  **When** adicionada
  **Then** é replicada pelo mesmo caminho, e ganha mais 16 partições.

**Prioridade**: Should Have

**Rastreabilidade**: RN-01, RN-03, RN-05

---

### RF-13: Visualização das métricas no Elastic/Kibana

**Descrição**: Durante os cenários, as métricas do worker e a coleta de CPU, memória e sessões dos
dois bancos ficam visíveis em tempo real num painel do Kibana existente, para acompanhar a execução
e investigar anomalias. É complementar: os critérios do relatório são calculados a partir dos CSVs
(RF-08) e não dependem do Elastic.

**Critérios de Aceitação**:

- **Given** um cenário em execução
  **When** o painel do Kibana é aberto
  **Then** mostra, com atraso de até 30 s, eventos/s, latência (p50/p95/p99), pendentes, lag e
  CPU/sessões dos dois bancos.
- **Given** as credenciais da conta da PoC no `.env`
  **When** o repositório é versionado e os logs e resultados são gerados
  **Then** nenhuma credencial aparece no repositório, nos logs, nos CSVs nem no relatório, e a conta só tem permissão para escrever os dados da PoC e para o painel.
- **Given** o Elastic indisponível
  **When** um cenário roda
  **Then** o worker e os CSVs funcionam normalmente e o cenário continua válido.
- **Given** a PoC concluída
  **When** os dados da PoC são consultados no Elastic
  **Then** estão identificados por cenário e por execução, e separáveis dos dados das demais
  aplicações.
- **Given** os dados da PoC no Elastic
  **When** o ambiente da PoC é removido
  **Then** os dados e painéis da PoC podem ser removidos sem afetar os de outras aplicações.

**Prioridade**: Should Have

**Rastreabilidade**: RN-09

---

## Experiência do Usuário

Os usuários são engenheiros e responsáveis técnicos; não há interface gráfica.

- **Subir e conferir:** um comando sobe o ambiente e imprime as contagens; outro comando executa
  cada papel (worker, carga inicial, reconciliação, gerador).
- **Rodar um cenário:** um script por cenário, sem passos manuais, que deixa os resultados numa pasta
  própria.
- **Acompanhar ao vivo:** o worker imprime no console o mesmo resumo que grava no arquivo a cada 5 s;
  o gerador informa a taxa alcançada a cada 5 s; o painel do Kibana mostra a execução em tempo real.
- **Ler o resultado:** um relatório curto, orientado a decisão, com a tabela de critérios na
  primeira página.
- **Mensagens de falha:** configuração inválida ou banco inacessível produzem mensagem clara, sem
  rastro técnico como única informação.

## Decisões de Produto

| ID | Decisão confirmada | Alternativas descartadas e motivo | Impacto no PRD | Registro |
|---|---|---|---|---|
| DP-01 | A latência se mede do instante de criação do evento até a confirmação no novo, e esse é o critério de SLA (5 s). | Medir só do commit no legado: dependeria de instrumentação no commit, que o legado não permite. | RF-08, RN-09, critério C2 | — |
| DP-02 | Resultado de cenário em que o gerador não atinge a taxa-alvo é inválido. | Aceitar resultado parcial: confundiria limite do gerador com limite do desenho. | RF-07, RF-10 | — |
| DP-03 | A integridade de cada cenário é verificada pela verificação completa em modo de simulação, mais comparação de snapshots de IDs. | Contagem de linhas apenas: não detecta conteúdo divergente nem UUID regerado. | RF-06, RF-09, RF-10 | — |
| DP-04 | A PoC cobre somente titular, obra_musical e fonograma. | Incluir todas as tabelas: lista ainda não definida. | Fora de escopo | — |
| DP-05 | A recomendação final é parte do entregável, não só os números. | Entregar só dados: devolveria a decisão sem análise. | RF-11 | — |
| DP-06 | Os bancos rodam em servidores internos, não no laptop: legado no `desenv-server`, banco novo no `infra-server`. O worker roda no servidor do banco novo e o gerador no do legado; o laptop só orquestra por ssh. | Tudo no laptop: a rede laptop→servidor e o WSL entrariam nas medidas e poderiam virar o gargalo. Worker/gerador em terceiro servidor: consome a RAM já limitada. | RF-01, RF-08, riscos, restrições | — |
| DP-07 | **C4 (rajada):** em até 120 s após o commit da rajada de 50.000 linhas, pendentes voltam ao patamar anterior e p95 volta a ≤ 5 s; zero evento perdido e zero divergência. O SLA de 5 s não se aplica durante a rajada. | SLA de 5 s durante a rajada: inatingível por construção, pois 50.000 eventos chegam de uma vez. Sem critério: C4 não aprovaria nem reprovaria. | RF-10, Métricas, Q3 | — |
| DP-08 | **C5 (transação longa):** durante os 30 s de transação aberta, p95 das demais transações ≤ 5 s (a transação longa não bloqueia as outras); os eventos da transação longa são aplicados em até 5 s após seu commit; zero perda, zero divergência. A latência dos eventos da transação longa é reportada à parte e excluída do p95 do SLA. | Exigir 5 s sobre o evento da transação longa: o tempo da transação pertence ao legado. | RF-10, RF-08, Métricas | — |
| DP-09 | Cada container Oracle tem limite de 3 GB de memória e 2 CPUs (o Oracle Free usa no máximo 2 CPUs e 2 GB de dados). Cabe nos ~9 GB livres de cada servidor. | Limite de 2 GB: sem folga para os processos do próprio Oracle. | RF-01, RF-11 | — |
| DP-10 | O Elastic/Kibana é visualização complementar; o CSV é o registro oficial dos resultados. | Elastic como fonte oficial: os critérios dependeriam da disponibilidade e retenção de um serviço compartilhado. | RF-08, RF-13 | — |
| DP-11 | O repositório da PoC é `poc-replicacao-service`. | `poc-replicacao-bdad` (nome do escopo de construção). | Caminhos e nomes de pacote | — |
| DP-12 | Os pré-requisitos dos servidores (portas, recursos, relógios) são verificados por uma tarefa de verificação antes da subida; o check periódico de relógio será adicionado pelo responsável pela infraestrutura. | Verificar manualmente: não deixa rastro e é esquecido. | RF-01, RF-08 | — |

---

## Restrições Técnicas de Alto Nível

- O legado é Oracle e a aplicação Delphi não pode ser alterada; só a tabela de eventos e a captura
  são acrescentadas.
- Sem Kafka e sem CDC na infraestrutura atual.
- A PoC roda em dois servidores internos compartilhados (`desenv-server` e `infra-server`, 8 vCPU e 15 GB de RAM cada, com cerca de 9 GB livres), com acesso por ssh e Docker. Esses servidores já hospedam outras aplicações, que não podem ser prejudicadas.
- Elastic e Kibana já existentes podem ser usados para visualização, com uma conta dedicada à PoC. As credenciais ficam num arquivo `.env` local, que não é versionado nem aparece em logs, CSVs ou relatório.
- Os servidores são de desenvolvimento de um homelab e podem receber carga a qualquer momento, sem janela restrita.
- Os bancos da PoC são limitados em CPU e memória (DP-09); os números não equivalem à produção.
- Sem framework de aplicação, para medir o pipeline sem seu custo.
- Nenhuma regra de negócio dentro do banco legado.

---

## Não-Objetivos (Fora de Escopo)

- Implantação em Kubernetes ou qualquer ambiente de produção.
- Segurança, gestão de credenciais e observabilidade de produção.
- Kafka, Confluent, Cloudera e CDC (apenas a possibilidade de troca é preservada, RF-12).
- Tabelas além de titular, obra_musical e fonograma.
- Rede de produção ou de longa distância entre ambientes: a PoC usa a rede interna entre os dois servidores.
- Escrita da réplica pelo sistema novo: a réplica é somente leitura.
- Plano de virada e rollback do cadastro, incluindo a sequence do Oracle (D15).
- Tabelas filhas, vínculos obra–titular e participações.
- Alteração da aplicação Delphi.

---

## Plano de Rollout Faseado

Em ordem de dependência, seguindo o backlog de construção.

### Fase 1 — Caminho básico ponta a ponta

- **Funcionalidades incluídas**: RF-01, RF-02, RF-03 (com posse fixa de partições), RF-08, RF-12.
- **Critérios para avançar à Fase 2**: um `UPDATE` no legado aparece na réplica em menos de 1 s; um
  C2 curto (1 min) gera CSV com todas as colunas; testes sem banco passam.

### Fase 2 — Robustez e integridade

- **Funcionalidades adicionais**: RF-04, RF-05, RF-06, RF-07, RF-09, RF-13.
- **Critérios para avançar à Fase 3**: C7 divide 24/24 e assume 48 após queda; C1 conclui sem
  divergência; C8 corrigido; gerador alcança ≥ 98% da taxa-alvo a 2.000 ev/s; comparação de
  snapshots lista UUIDs alterados.

### Fase 3 — Validação e decisão

- **Funcionalidades restantes**: RF-10, RF-11.
- **Critérios de sucesso:** todos os critérios de sucesso da seção Métricas medidos; relatório
  entregue com recomendação.

---

## Métricas de Sucesso

Prazo: ao término da Fase 3. Todos os valores são para um worker, salvo indicação.

| Métrica | Definição | Alvo |
|---|---|---|
| Latência em regime (C2) | p95 da latência por evento a 1.000 ev/s por 10 min | ≤ 5 s, lag sem tendência de alta |
| Teto de vazão (C3) | Maior taxa sem crescimento do lag | ≥ 2.000 ev/s |
| Integridade após falha (C6, C7) | Divergências na verificação completa ao fim do cenário | 0 |
| Estabilidade de identidade | Diferenças em snapshot `codigo, id` antes/depois de cada cenário | 0 |
| Failover (C7) | Tempo até o sobrevivente assumir as 48 partições | ≤ 20 s |
| Correção da captura desligada (C8) | Linhas afetadas corrigidas pela reconciliação | 100% |
| Custo no legado (C10) | Overhead percentual no tempo médio de transação com captura | Limite a definir com o time do legado (ver Questões em Aberto) |
| Rajada (C4) | Tempo até pendentes e p95 voltarem ao normal | ≤ 120 s, sem perda |
| Transação longa (C5) | p95 das demais transações durante os 30 s; aplicação após o commit | ≤ 5 s; ≤ 5 s |
| Validade dos cenários | Cenários com taxa alcançada ≥ 98% da alvo e diferença entre relógios ≤ 100 ms | 100% dos cenários considerados |

---

## Riscos e Mitigações

- **Resultado enganoso por ambiente reduzido e compartilhado** (2 CPUs por banco; servidores com outras aplicações): vizinhos ruidosos podem distorcer medidas — Mitigação: registrar a carga dos servidores durante cada cenário, repetir cenários suspeitos e tratar os números como piso de capacidade.
- **PoC prejudicando aplicações existentes nos servidores** (memória, CPU, disco, I/O) — Mitigação: tetos de recursos por container (DP-09), monitoramento durante os cenários e interrupção imediata se um serviço existente degradar.
- **Diferença de relógio entre os servidores distorcendo a latência** — Mitigação: verificação de sincronização nos pré-requisitos (RF-01), medição e registro da diferença; cenário inválido acima de 100 ms (RF-08).
- **Vazamento de credenciais do Elastic** — Mitigação: `.env` fora do controle de versão (RF-13).
- **Estimativa de volume sem medição** (1.000 ev/s): a PoC pode validar a carga errada —
  Mitigação: C3 explora até 4× o alvo; obter o volume real no pico do legado.
- **Critério de overhead sem limite definido:** C10 poderia terminar sem veredito — Mitigação:
  definir o limite com o time do legado antes da execução (ver Questões em Aberto).
- **Falta de dados do legado real** (quem preenche `updated_at`, delete físico, jobs em massa):
  a PoC simula comportamentos que podem não valer em produção — Mitigação: registrar no relatório
  quais conclusões dependem dessas respostas.
- **Capacidade insuficiente** ao rodar banco, worker ou gerador com carga alta: o gerador ou os bancos podem limitar o resultado — Mitigação: validade do cenário condicionada à taxa alcançada (DP-02).
- **Dependência do time do legado** para aceitar o custo da captura — Mitigação: entregar o número
  de overhead com método e condições claros.

---

## Alternativas Consideradas

### Abordagem Escolhida: captura por trigger + worker que busca o estado atual

- **Descrição**: o legado registra só a chave e a operação; um worker lê, busca o estado atual e
  grava de forma idempotente no banco novo.
- **Por que foi escolhida**: simples, desacopla o consumidor do legado, corrige-se sozinha no
  próximo evento e preserva o caminho para CDC (ADR).

### Alternativa Rejeitada 1: solução do SGD (job Oracle → WCF → aviso no RabbitMQ → APIs releem o Oracle)

- **Trade-offs**: reaproveita experiência interna, mas inclui um salto a mais (WCF) e mantém os
  consumidores acoplados ao legado.
- **Por que foi rejeitada**: acoplamento e componente extra.

### Alternativa Rejeitada 2: RabbitMQ Streams como transporte

- **Trade-offs**: habilita replay e múltiplos consumidores, mas com um único consumidor é um salto
  sem ganho.
- **Por que foi rejeitada**: volta a valer se outro sistema precisar dos eventos.

### Alternativa Rejeitada 3: evento com diff ou com a linha serializada

- **Trade-offs**: reduz leitura no legado, mas exige que nenhum evento se perca e chegue em ordem, e
  complica a captura e aumenta o custo da transação do legado.
- **Por que foi rejeitada**: estado completo se corrige sozinho.

### Alternativa Rejeitada 4: API chamada pelo próprio banco

- **Trade-offs**: entrega imediata, mas o Oracle passa a depender da disponibilidade da API.
- **Por que foi rejeitada**: retry e timeout ficam dentro do banco.

---

## Questões em Aberto

| # | Questão | Quem responde | Prazo desejável | Impacto se não resolvida |
|---|---|---|---|---|
| Q1 | Quem preenche `updated_at` no legado (aplicação Delphi ou banco) e com que tipo (`DATE`/`TIMESTAMP`)? | Time do legado | Antes do relatório | A guarda de versão pode falhar em empates (resolução em segundos) ou com relógio de cliente; pode exigir usar a captura como fonte de versão. |
| Q2 | Existe `DELETE` físico nas tabelas? | Time do legado | Antes de incluir cada tabela | Linhas órfãs na réplica; a PoC só cobre soft delete. |
| Q3 | Existem jobs em massa nessas tabelas, com que frequência e volume? | Time do legado | Antes do relatório | C4 pode não representar a rajada real (o critério de DP-07 vale para a rajada simulada de 50.000 linhas). |
| Q5 | Qual o overhead aceitável da captura na transação do legado? | Time do legado | Antes de C10 | C10 sem veredito. |
| Q6 | Qual o volume real de eventos/s no pico? | Time do legado | Antes do relatório | O alvo de 1.000 ev/s permanece uma estimativa. |
| Q7 | Lista completa de tabelas e ordem de entrada. | Tasso | Pós-PoC | Fora desta entrega; define o próximo recorte. |
| Q8 | Plano de rollback da virada, incluindo avanço da sequence do Oracle. | Tasso | Pós-PoC | Fora desta entrega. |
