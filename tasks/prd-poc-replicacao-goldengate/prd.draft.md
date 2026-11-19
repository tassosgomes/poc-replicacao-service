---
tsg_artifact: prd
status: draft
mode: standalone
capability: null   # standalone — sem backlog/CAP-XXX disponível; atribuir quando o backlog existir
slug: poc-replicacao-goldengate
created: 2026-11-05
origins:
  - path: tasks/prd-poc-replicacao-bdad/prd.md
    version: "2026-10-31 (aprovado)"
  - path: resultados/relatorio.md
    version: "2026-11-02 (recomendação AJUSTAR, rascunho do autor)"
  - path: docs/desenho-alternativas.excalidraw
    version: "2026-11-05 (seção D — GoldenGate)"
  - path: docs/adr-replocacao.md
    version: "2026-10-30 (ADR proposto, pendente de PoC)"
---

# PoC de Replicação BDAD → MAESTRO com Oracle GoldenGate (Oracle → Oracle, sem Kafka)

## Visão Geral

A PoC anterior validou um desenho próprio de replicação: trigger e tabela de eventos no BDAD e um
worker Java que busca o estado atual e o grava no MAESTRO. Ela comprovou a integridade da carga
inicial e da reconciliação, mas não conseguiu medir latência, vazão e recuperação sob carga, porque
o gerador não sustentou as taxas-alvo. O custo da captura por trigger no legado ficou em 20% do
tempo de transação, acima do critério proposto de 15%.

Entre as alternativas desenhadas em `docs/desenho-alternativas.excalidraw`, o **Oracle GoldenGate**
é a única que lê o log de redo sem trigger no BDAD, sem Kafka e sem código próprio de replicação, e
ainda preserva a transação inteira e a ordem de commit. O custo é a licença do produto e uma
dependência de conhecimento especializado.

Esta entrega é a prova de conceito do GoldenGate no **mesmo ambiente, com a mesma massa e os mesmos
critérios** da PoC do worker, para que as duas abordagens possam ser comparadas lado a lado. O
resultado é um relatório comparativo com recomendação: adotar o GoldenGate, manter o worker ou
combinar os dois.

**Para quem:** o time de arquitetura da migração (decisão de abordagem), o time do BDAD (custo da
captura por log na base legada) e quem vai decidir o investimento em licença. **Valor:** decidir
entre construir e operar uma replicação própria ou comprar um produto, com evidência medida e não
com a ficha técnica do fornecedor.

---

## Rastreabilidade

### Capacidade e fronteiras

- **Capacidade:** não há backlog de capacidades (modo standalone). Esta entrega é a avaliação, por
  PoC, da alternativa D do desenho de alternativas, comparada à PoC do worker
  (`tasks/prd-poc-replicacao-bdad`).
- **Escopo desta entrega:** GoldenGate Free em container, replicando titular, obra_musical e
  fonograma do BDAD para uma réplica separada no MAESTRO; UUIDv7 estável na réplica; carga inicial
  alinhada à captura; cenários G1–G11 automatizados; métricas no mesmo formato da PoC do worker;
  relatório comparativo com recomendação.
- **Fora desta entrega:** GoldenGate licenciado (Replicat paralelo, Veridata, alta disponibilidade
  do hub); negociação de licença; implantação em produção; tabelas além das três; Kafka/Confluent;
  qualquer mudança na aplicação Delphi.
- **Domínios atravessados:** nenhum domain doc disponível. O vocabulário vem do ADR e do PRD da PoC
  do worker.
- **Restrições do baseline:** mesmos servidores e limites da PoC do worker (legado no
  `desenv-server`, MAESTRO no `infra-server`, Oracle Database Free 23ai com 2 CPUs e 3 GB por
  container). Diferente da PoC do worker, o BDAD recebe configuração de captura por log (redo
  arquivado, supplemental logging e registro forçado em redo) em vez de trigger e tabela de eventos.

### Regras herdadas e regras desta entrega

As regras RN-01 a RN-10 nasceram no PRD da PoC do worker. Esta entrega herda as que descrevem o
resultado esperado na réplica e substitui as que descreviam o mecanismo do worker.

| ID | Regra | Situação nesta entrega |
|---|---|---|
| RN-01 | O evento identifica só a linha; o conteúdo vem do estado atual no legado. | **Substituída por RN-G1.** |
| RN-02 | Mudanças do mesmo `codigo` são aplicadas por um único dono e em ordem. | **Reforçada por RN-G2.** |
| RN-03 | Aplicar o mesmo estado mais de uma vez dá o mesmo resultado. | Herdada. |
| RN-04 | Um estado mais antigo nunca sobrescreve um mais novo. | Herdada; garantida por RN-G2. |
| RN-05 | O UUIDv7 de uma linha é atribuído uma vez, na primeira gravação, e nunca muda. | Herdada, sem alteração. |
| RN-06 | Um evento só é descartado depois do efeito confirmado no destino. | Herdada como RN-G3. |
| RN-07 | Exclusão é soft delete; linha inexistente no legado é anomalia. | Herdada, com RN-G4. |
| RN-08 | Referências entre tabelas por `codigo`, sem exigir a linha referenciada. | Herdada. |
| RN-09 | Latência medida do registro no legado à confirmação no destino. | Herdada, com DP-04. |
| RN-10 | A réplica nunca é a fonte da verdade. | Herdada. |
| RN-G1 | A mudança capturada carrega o conteúdo da linha como estava no commit do legado; não há releitura do legado. | Nova. |
| RN-G2 | As transações do legado são aplicadas na réplica inteiras e na ordem de commit: um leitor do MAESTRO nunca vê parte de uma transação. | Nova. |
| RN-G3 | Uma mudança aplicada não é aplicada de novo depois de uma falha, e uma mudança confirmada no legado nunca é perdida. | Nova (substitui RN-06). |
| RN-G4 | Exclusão física no legado nunca apaga o `id` já atribuído na réplica sem ficar registrada no relatório. | Nova. |

---

## Termos Canônicos

Os termos do PRD da PoC do worker continuam valendo. Os termos abaixo são novos ou mudam de sentido.

| Termo | Definição de negócio | Escopo/Fonte |
|---|---|---|
| MAESTRO | Banco Oracle novo, destino da réplica. Na PoC do worker era chamado de "banco novo". | Desenho de alternativas |
| GoldenGate Free | Edição gratuita do Oracle GoldenGate, distribuída em container, limitada a Oracle → Oracle, bancos de até 20 GB e suporte por comunidade. | Documentação Oracle |
| Captura por log | Leitura das mudanças a partir do redo do legado, sem trigger e sem tabela de eventos. | Desenho de alternativas |
| Trilha (trail) | Arquivo em que o GoldenGate guarda as transações capturadas, em ordem, até aplicá-las. | Documentação Oracle |
| Ponto de retomada (checkpoint) | Posição registrada da captura e da aplicação, usada para continuar após falha sem perder nem repetir transações. | Documentação Oracle |
| Pipeline gerenciado | Configuração da captura e da aplicação criada e mantida pela console do GoldenGate Free. | Documentação Oracle |
| Réplica GG | Conjunto de tabelas no MAESTRO alimentado pelo GoldenGate, separado da réplica do worker. | Decisão desta entrega (DP-02) |

---

## Objetivos

1. **Comparar com o worker:** medir o GoldenGate contra os mesmos critérios da PoC do worker
   (latência em regime, teto de vazão, rajada, transação longa, integridade após falha, estabilidade
   de identidade, custo no legado), no mesmo ambiente e com a mesma massa.
2. **Provar UUIDv7 estável sem código de replicação próprio:** o `id` é atribuído na primeira
   gravação e nunca muda, em todos os cenários.
3. **Provar consistência transacional:** nenhum leitor do MAESTRO vê parte de uma transação do
   legado.
4. **Provar retomada sem perda e sem repetição:** depois da queda do GoldenGate ou da
   indisponibilidade do MAESTRO, a réplica converge sem divergência.
5. **Quantificar o custo da captura por log no BDAD** e compará-lo aos 20% medidos com trigger.
6. **Registrar o custo de adoção:** passos, conhecimento exigido, limitações do GoldenGate Free e o
   que muda na versão licenciada.
7. **Entregar uma decisão:** relatório comparativo com recomendação explícita.

---

## Histórias de Usuário

- Como **Arquiteto da migração**, quero os resultados do GoldenGate na mesma tabela de critérios da
  PoC do worker, para escolher a abordagem com números comparáveis.
- Como **Responsável pelo BDAD**, quero saber quanto o registro forçado em redo e o supplemental
  logging custam na transação e no volume de redo, para aceitar ou recusar a captura por log.
- Como **Engenheiro da migração**, quero que o sistema novo continue recebendo um `id` UUIDv7 que
  nunca muda, para não ter que refatorar os sistemas na virada do cadastro.
- Como **Engenheiro da migração**, quero ver o que acontece quando o GoldenGate cai ou o MAESTRO
  fica fora do ar, para conhecer os modos de falha do produto antes de comprá-lo.
- Como **Responsável pela decisão de investimento**, quero saber o que a PoC provou com a edição
  gratuita e o que só a versão licenciada entregaria, para não pagar por uma premissa não testada.

---

## Funcionalidades Principais

### RF-01: Ambiente GoldenGate ao lado dos bancos da PoC

**Descrição**: O GoldenGate Free sobe com um comando, ao lado dos bancos já existentes da PoC, e o
BDAD é configurado para captura por log. A massa de dados é a mesma da PoC do worker. O ambiente do
GoldenGate e o do worker não rodam ao mesmo tempo, para que um não interfira nas medidas do outro.

**Critérios de Aceitação**:

- **Given** os servidores da PoC com os bancos em pé
  **When** o comando de subida do GoldenGate é executado
  **Then** o GoldenGate Free fica disponível, conectado ao BDAD e ao MAESTRO, e o comando termina
  com sucesso sem passos manuais na console.
- **Given** o BDAD recém-criado
  **When** a subida termina
  **Then** o BDAD tem redo arquivado, registro forçado em redo e supplemental logging nas três
  tabelas, e um usuário de captura com os privilégios mínimos documentados.
- **Given** o worker da PoC anterior ligado ou os triggers de captura ativos
  **When** a subida do GoldenGate é solicitada
  **Then** o comando recusa a subida ou desliga worker e triggers antes, e registra o que fez.
- **Given** qualquer banco acima de 20 GB
  **When** o GoldenGate Free tenta operar
  **Then** a subida falha com mensagem que indica o limite da edição gratuita.
- **Given** os containers da PoC em pé
  **When** a subida termina
  **Then** a soma dos limites de memória cabe na memória livre dos servidores e nenhum serviço
  existente é afetado.

**Prioridade**: Must Have

**Rastreabilidade**: RN-10

---

### RF-02: Captura das mudanças pelo log do legado

**Descrição**: Toda inserção, alteração e exclusão nas três tabelas do BDAD é capturada a partir do
redo, sem trigger e sem tabela de eventos. A captura continua mesmo com o MAESTRO fora do ar,
acumulando as transações até que possam ser aplicadas.

**Critérios de Aceitação**:

- **Given** a captura ligada
  **When** uma transação do legado altera linhas das três tabelas e faz commit
  **Then** todas as mudanças da transação são capturadas, e nenhuma mudança de transação desfeita
  (rollback) é capturada.
- **Given** a captura ligada e o registro forçado em redo ativo
  **When** o legado faz uma carga em massa por caminho direto (direct-path) sem registro em redo
  **Then** as linhas carregadas também são capturadas.
- **Given** o MAESTRO indisponível
  **When** o legado continua recebendo transações
  **Then** a captura continua e nada se perde; as transações são aplicadas quando o MAESTRO volta.
- **Given** o BDAD sem trigger de captura e sem tabela de eventos
  **When** a PoC termina
  **Then** nenhum objeto foi criado no esquema de negócio do legado além da configuração de log e
  do usuário de captura.

**Prioridade**: Must Have

**Rastreabilidade**: RN-G1, RN-G3

---

### RF-03: Aplicação na réplica com transação inteira e ordem de commit

**Descrição**: As mudanças capturadas são aplicadas na réplica GG preservando a transação do legado e
a ordem de commit. A aplicação identifica a linha pelo `codigo`.

**Critérios de Aceitação**:

- **Given** uma transação do legado que altera uma obra e um fonograma juntos
  **When** ela é aplicada
  **Then** um leitor do MAESTRO vê as duas alterações ou nenhuma, nunca só uma.
- **Given** duas transações que alteram o mesmo `codigo`
  **When** são aplicadas
  **Then** o estado final na réplica é o da que fez commit por último no legado.
- **Given** um fonograma que referencia uma obra ainda não replicada
  **When** o fonograma é aplicado
  **Then** a aplicação não falha.
- **Given** a mesma mudança entregue de novo à aplicação (reprocessamento após falha)
  **When** é aplicada
  **Then** o estado final da réplica é o mesmo.

**Prioridade**: Must Have

**Rastreabilidade**: RN-G2, RN-02, RN-03, RN-04, RN-08

---

### RF-04: UUIDv7 estável na réplica

**Descrição**: Toda linha da réplica GG recebe um `id` UUIDv7 na primeira gravação. Alterações
posteriores nunca mudam esse `id`. Como o GoldenGate não gera UUIDv7, o mecanismo pertence ao
destino e preferencialmente não exige mexer na configuração gerenciada pelo GoldenGate Free (DP-05).

**Critérios de Aceitação**:

- **Given** uma linha nova no legado
  **When** ela chega à réplica
  **Then** recebe um `id` UUIDv7 válido (versão 7, ordenável pelo instante de criação).
- **Given** uma linha que já existe na réplica
  **When** o legado a altera qualquer número de vezes
  **Then** o `id` não muda.
- **Given** um snapshot de `codigo, id` tirado antes de um cenário
  **When** o cenário termina
  **Then** nenhum `id` mudou, inclusive após falhas, reprocessamento e carga em massa.
- **Given** a carga inicial
  **When** ela termina
  **Then** toda linha da réplica tem `id` UUIDv7 e a captura posterior não o regera.
- **Given** um mecanismo de UUIDv7 que só funcione alterando a configuração gerenciada pelo
  GoldenGate Free
  **When** ele é adotado
  **Then** o desvio é registrado no relatório, com o motivo e o efeito na gestão do pipeline.

**Prioridade**: Must Have

**Rastreabilidade**: RN-05

---

### RF-05: Tratamento de exclusão

**Descrição**: O soft delete do legado (`excluido`) chega à réplica como uma alteração comum. A
exclusão física no legado não pode apagar silenciosamente uma linha com `id` já atribuído.

**Critérios de Aceitação**:

- **Given** uma linha marcada como excluída no legado
  **When** a mudança é aplicada
  **Then** a linha continua na réplica, marcada como excluída e com o mesmo `id`.
- **Given** uma exclusão física no legado
  **When** a mudança é aplicada
  **Then** a linha é marcada como excluída na réplica, mantendo o `id`; se isso não for possível no
  GoldenGate Free sem desvio, o comportamento observado é registrado no relatório e na verificação
  de integridade.
- **Given** uma linha excluída fisicamente e reinserida com o mesmo `codigo`
  **When** as mudanças são aplicadas
  **Then** o comportamento do `id` (mantido ou novo) é registrado no relatório.

**Prioridade**: Should Have

**Rastreabilidade**: RN-07, RN-G4

---

### RF-06: Carga inicial alinhada à captura

**Descrição**: A réplica GG é populada a partir de um retrato consistente do legado, e a captura
continua exatamente do ponto desse retrato, sem lacuna e sem aplicação dupla. A carga roda com o
gerador de carga ligado.

**Critérios de Aceitação**:

- **Given** o legado com a massa completa e o gerador ligado
  **When** a carga inicial termina e a captura assume
  **Then** a verificação completa não encontra divergência.
- **Given** uma linha alterada durante a carga inicial
  **When** a captura assume
  **Then** o estado final na réplica é o mais recente do legado.
- **Given** a carga inicial concluída
  **When** ela é repetida
  **Then** nenhum `id` já atribuído é regerado.

**Prioridade**: Must Have

**Rastreabilidade**: RN-05, RN-G3, RN-10

---

### RF-07: Retomada após falha

**Descrição**: A queda do GoldenGate, a indisponibilidade do MAESTRO ou a parada da captura não
perdem nem repetem transações. Depois da volta, a réplica converge sozinha.

**Critérios de Aceitação**:

- **Given** o GoldenGate morto abruptamente sob carga
  **When** ele é religado
  **Then** a aplicação continua do último ponto confirmado, sem divergência e sem `id` alterado.
- **Given** o MAESTRO indisponível por 1 minuto sob carga
  **When** ele volta
  **Then** o atraso é eliminado sem intervenção, sem divergência, e o tempo até normalizar é medido.
- **Given** uma falha que exija intervenção manual
  **When** ela ocorre
  **Then** o passo manual é registrado no relatório como custo de operação.

**Prioridade**: Must Have

**Rastreabilidade**: RN-G3, RN-03

---

### RF-08: Métricas comparáveis às da PoC do worker

**Descrição**: Cada cenário produz as mesmas métricas e o mesmo formato de resultado da PoC do
worker (CSV como registro oficial), mais o atraso informado pelo próprio GoldenGate e o volume de
redo gerado no legado.

**Critérios de Aceitação**:

- **Given** um cenário concluído
  **When** os resultados são gravados
  **Then** contêm latência por mudança (p50, p95, p99), vazão aplicada, atraso, uso de CPU e memória
  dos bancos e do GoldenGate, volume de redo do legado e a validade do cenário.
- **Given** um cenário em que o gerador não atinge 98% da taxa-alvo ou os relógios divergem mais de
  100 ms
  **When** ele termina
  **Then** é marcado inválido, como na PoC do worker.
- **Given** os resultados das duas PoCs
  **When** são lidos lado a lado
  **Then** as colunas de mesmo nome têm o mesmo significado.

**Prioridade**: Must Have

**Rastreabilidade**: RN-09

---

### RF-09: Verificação de integridade e de identidade

**Descrição**: Ao fim de cada cenário, a réplica GG é comparada ao legado linha a linha, e o
snapshot de `codigo, id` é comparado ao do início, com os mesmos critérios da PoC do worker.

**Critérios de Aceitação**:

- **Given** um cenário concluído com a réplica em dia
  **When** a verificação roda
  **Then** informa o número de linhas divergentes, faltantes e sobrando, e os `id`s alterados.
- **Given** a verificação da PoC do worker
  **When** é usada na réplica GG
  **Then** produz o mesmo formato de resultado, sem reenfileirar nada (só simulação).

**Prioridade**: Must Have

**Rastreabilidade**: RN-05, RN-10

---

### RF-10: Cenários de validação automatizados (G1–G11)

**Descrição**: Cada cenário é executável por um único script, sem intervenção, e arquiva os
resultados em pasta própria. Todo cenário começa com a réplica em dia e um snapshot de IDs, e termina
com verificação completa e novo snapshot. Os cenários espelham C1–C10 da PoC do worker; os que
dependiam do mecanismo do worker são substituídos por equivalentes do GoldenGate.

| # | Cenário | Equivale a | Pronto quando |
|---|---|---|---|
| G1 | Carga inicial da massa completa com gerador ligado | C1 | Verificação sem divergência; todo `id` é UUIDv7 |
| G2 | Regime: 1.000 ev/s por 10 min | C2 | 10 min válidos com p95 e atraso |
| G3 | Teto: 500, 1.000, 2.000 e 4.000 ev/s, 5 min cada | C3 | Identificada a taxa em que o atraso passa a crescer |
| G4 | Rajada de 50.000 linhas numa transação sobre regime de 1.000 ev/s | C4 | Em até 120 s após o commit: atraso no patamar anterior e p95 ≤ 5 s; zero perda e zero divergência |
| G5 | Transação aberta por 30 s sobre regime de fundo | C5 | p95 das demais ≤ 5 s durante os 30 s; transação longa aplicada em até 5 s após o commit |
| G6 | `kill -9` no GoldenGate aos 3 min; religado aos 4 min | C6 | Zero divergência, zero `id` alterado, tempo até normalizar |
| G7 | MAESTRO indisponível por 1 min sob carga | substitui C7 (failover entre workers) | Zero perda e divergência; tempo até normalizar |
| G8 | Carga em massa por caminho direto sem registro em redo, com registro forçado ativo | substitui C8 (captura desligada) | Todas as linhas capturadas, sem reconciliação |
| G9 | Transações que alteram obra e fonograma juntos, com leitor contínuo no MAESTRO | novo | O leitor nunca observa transação parcial |
| G10 | Mesma carga com e sem captura por log | C10 | Overhead percentual e absoluto no tempo de transação e aumento do volume de redo |
| G11 | Alterações repetidas, exclusão lógica, exclusão física e reinserção do mesmo `codigo` | novo | `id` estável nas alterações; comportamento de exclusão física registrado |

C9 (threads de plataforma no worker) não se aplica.

**Critérios de Aceitação**:

- **Given** o ambiente GoldenGate em pé
  **When** o script de um cenário é executado
  **Then** roda de ponta a ponta sem intervenção e salva os resultados em pasta própria.
- **Given** um cenário já executado
  **When** é executado de novo
  **Then** parte de um estado limpo e comparável.
- **Given** um cenário em que o gerador não atinge a taxa-alvo
  **When** termina
  **Then** é marcado inválido e precisa ser repetido.

**Prioridade**: Must Have (G1–G8, G10); Should Have (G9, G11)

**Rastreabilidade**: RN-03, RN-05, RN-G2, RN-G3, RN-10

---

### RF-11: Relatório comparativo e recomendação

**Descrição**: Documento curto que põe os resultados do GoldenGate e do worker na mesma tabela de
critérios, registra limitações do ambiente e da edição gratuita, o custo de adoção observado e
termina com uma recomendação: adotar o GoldenGate, manter o worker ou combinar os dois.

**Critérios de Aceitação**:

- **Given** os cenários concluídos
  **When** o relatório é produzido
  **Then** cada critério mostra o resultado do worker, o do GoldenGate e o veredito de cada um.
- **Given** um critério que a PoC do worker deixou inválido
  **When** o relatório o compara
  **Then** declara que a comparação não é possível nesse ponto, sem inferir o resultado do worker.
- **Given** o GoldenGate Free sem Replicat paralelo no Oracle Database Free
  **When** o relatório trata de vazão
  **Then** declara que o resultado é um piso e o que a versão licenciada mudaria.
- **Given** o relatório
  **When** é lido por quem decide o investimento
  **Then** lista o que precisa de licença em produção, os conhecimentos exigidos do time e os
  passos manuais encontrados.

**Prioridade**: Must Have

**Rastreabilidade**: RN-09

---

## Decisões de Produto

| ID | Decisão confirmada | Alternativas descartadas e motivo | Impacto no PRD | Registro |
|---|---|---|---|---|
| DP-01 | Usar o **GoldenGate Free** em container. | GoldenGate 23ai completo montado a partir do instalador (licença de desenvolvedor): exige download com conta Oracle e montagem de imagem; fica como plano B se o Free bloquear UUIDv7 ou exclusão física. OCI GoldenGate: exige nuvem, fora do ambiente da PoC. | RF-01, RF-11 | — |
| DP-02 | Reutilizar os servidores, os bancos e a massa da PoC do worker. A réplica GG fica num esquema separado no MAESTRO. Worker e triggers ficam desligados durante os cenários do GoldenGate. | Bancos novos só para o GoldenGate: a memória livre dos servidores não comporta. Rodar os dois juntos: um distorceria as medidas do outro. | RF-01, RF-10 | — |
| DP-03 | Mesmos critérios, alvos e regras de validade da PoC do worker. | Critérios próprios para o GoldenGate: impediria a comparação. | RF-08, RF-10, Métricas | — |
| DP-04 | A latência é medida do instante da escrita da linha no legado até a gravação na réplica GG, com a mesma regra de relógios da PoC do worker. Como não há tabela de eventos, o instante de origem é a coluna de atualização da linha. | Medir pelo atraso informado pelo GoldenGate: não é comparável à PoC do worker; entra como métrica complementar. | RF-08, RN-09 | — |
| DP-05 | Preferir mecanismos no banco de destino a alterar a configuração gerenciada do GoldenGate Free. Se for inevitável, alterar e registrar como desvio. | Proibir qualquer alteração: poderia impedir UUIDv7 ou exclusão física. Alterar livremente: a própria Oracle avisa que isso afeta a gestão do pipeline no Free. | RF-04, RF-05, RF-11 | — |
| DP-06 | Os resultados de vazão do GoldenGate Free são piso. O Oracle Database Free não tem Replicat paralelo no GoldenGate Free, e a aplicação usa o Replicat integrado. | Montar o GoldenGate completo só para ter Replicat paralelo: custo desproporcional para a PoC; vira recomendação se o piso não atingir o alvo. | RF-11, Métricas | — |
| DP-07 | Nenhuma resolução de conflitos (CDR/ACDR) é configurada. A réplica tem um único escritor e a aplicação segue a ordem de commit; não há conflito a resolver. | ACDR: foi feito para ativo-ativo, cria colunas ocultas de timestamp e não pode ser trocado depois de configurado no Free. | RF-03, RN-04 | — |

---

## Restrições Técnicas de Alto Nível

- Sem Kafka, Confluent ou qualquer fila externa.
- A aplicação Delphi não é alterada. No BDAD, só a configuração de log e o usuário de captura são
  acrescentados; nenhum trigger ou objeto no esquema de negócio.
- Mesmos servidores compartilhados da PoC do worker (8 vCPU e 15 GB, cerca de 9 GB livres, com
  outras aplicações que não podem ser prejudicadas).
- GoldenGate Free: só Oracle → Oracle, bancos de até 20 GB (o Oracle Database Free já limita os
  dados a 12 GB), suporte só por comunidade, sem Replicat paralelo no Oracle Database Free.
- A captura do GoldenGate roda dentro do legado e consome CPU dele, que já ficou perto do limite de
  2 CPUs na PoC do worker.
- Credenciais da console e dos usuários de captura e aplicação ficam fora do controle de versão.

---

## Não-Objetivos (Fora de Escopo)

- GoldenGate licenciado, Veridata, alta disponibilidade do hub e negociação de licença.
- Replicação ativo-ativo ou escrita na réplica pelo sistema novo.
- Captura de DDL e mudanças de esquema durante a replicação.
- Tabelas além de titular, obra_musical e fonograma.
- Implantação em produção ou em Kubernetes.
- Alterar a PoC do worker ou refazer seus cenários.
- Plano de virada do cadastro e rollback.

---

## Plano de Rollout Faseado

### Fase 1 — Caminho básico

- **Funcionalidades incluídas**: RF-01, RF-02, RF-03, RF-04.
- **Critérios para avançar à Fase 2**: um `UPDATE` no legado aparece na réplica GG em menos de 1 s;
  uma linha nova recebe UUIDv7 e uma alteração posterior não o muda; o mecanismo de UUIDv7 está
  definido (com ou sem desvio, DP-05).

### Fase 2 — Robustez e integridade

- **Funcionalidades adicionais**: RF-05, RF-06, RF-07, RF-08, RF-09.
- **Critérios para avançar à Fase 3**: G1 sem divergência; G6 e G7 sem perda; gerador alcança 98%
  da taxa-alvo a 2.000 ev/s com a captura por log ligada.

### Fase 3 — Validação e decisão

- **Funcionalidades restantes**: RF-10, RF-11.
- **Critérios de sucesso**: todas as métricas medidas ou declaradas inválidas com motivo; relatório
  comparativo entregue com recomendação.

---

## Métricas de Sucesso

Prazo: ao término da Fase 3. Os alvos são os da PoC do worker, salvo os marcados como novos.

| Métrica | Definição | Alvo |
|---|---|---|
| Latência em regime (G2) | p95 da latência por mudança a 1.000 ev/s por 10 min | ≤ 5 s, atraso sem tendência de alta |
| Teto de vazão (G3) | Maior taxa sem crescimento do atraso | ≥ 2.000 ev/s (piso, DP-06) |
| Rajada (G4) | Tempo até atraso e p95 voltarem ao normal | ≤ 120 s, sem perda |
| Transação longa (G5) | p95 das demais transações durante os 30 s; aplicação após o commit | ≤ 5 s; ≤ 5 s |
| Integridade após falha (G6, G7) | Divergências na verificação completa | 0 |
| Estabilidade de identidade | `id`s alterados entre snapshots, em todos os cenários | 0 |
| Captura sem lacuna (G8) | Linhas de carga direct-path presentes na réplica | 100%, sem reconciliação |
| Consistência transacional (G9) — nova | Leituras que observam transação parcial | 0 |
| Custo no legado (G10) | Overhead no tempo médio de transação com captura por log | ≤ 15% E ≤ 5 ms (critério proposto, pendente Q5 da PoC do worker); comparar com os 20% do trigger |
| Volume de redo (G10) — nova | Aumento do redo gerado com supplemental logging e registro forçado | Medido e reportado; limite com o time do BDAD |
| Validade dos cenários | Taxa ≥ 98% do alvo e relógios ≤ 100 ms | 100% |

---

## Riscos e Mitigações

- **Gerador sem capacidade, como na PoC do worker:** C2–C7 saíram inválidos por taxa — Mitigação:
  Fase 2 só avança com o gerador validado a 2.000 ev/s; o ajuste do gerador é pré-requisito desta
  PoC, não resultado dela.
- **Legado sem CPU para gerador e captura juntos:** a captura do GoldenGate roda dentro do legado,
  que tem 2 CPUs — Mitigação: registrar CPU do legado por cenário e declarar no relatório quando o
  limite da edição Free do banco for o gargalo.
- **Edição gratuita não representar a licenciada** (sem Replicat paralelo, suporte só por
  comunidade) — Mitigação: DP-06; o relatório separa o que foi provado do que é premissa.
- **UUIDv7 ou exclusão física exigirem alterar o pipeline gerenciado** — Mitigação: DP-05; plano B
  com o GoldenGate completo (DP-01).
- **Memória dos servidores compartilhados** com um container a mais — Mitigação: limite de memória
  do GoldenGate definido na TechSpec e verificado na subida (RF-01).
- **Licença em produção desconhecida:** a PoC pode recomendar algo inviável no orçamento —
  Mitigação: Q-G1 respondida antes do relatório.
- **Time do BDAD recusar registro forçado em redo e supplemental logging** — Mitigação: G10 entrega
  o custo medido e o aumento de redo para a decisão.

---

## Alternativas Consideradas

### Abordagem Escolhida: GoldenGate Free, Oracle → Oracle

- **Descrição**: captura por log no legado, trilha e aplicação no MAESTRO pelo GoldenGate Free em
  container, com UUIDv7 gerado no destino.
- **Por que foi escolhida**: única alternativa sem trigger no legado, sem Kafka e sem código de
  replicação próprio que preserva transação e ordem de commit; tem edição gratuita que roda no
  ambiente atual.

### Alternativa Rejeitada 1: CDC com Confluent

- **Descrição**: conector Oracle CDC, Kafka e consumer próprio.
- **Trade-offs**: desacopla e permite replay, mas exige licença e plataforma Kafka e continua
  precisando de consumer próprio para UUIDv7.
- **Por que foi rejeitada para esta PoC**: o pedido é Oracle → Oracle sem Kafka.

### Alternativa Rejeitada 2: job com stored procedure

- **Descrição**: o mesmo desenho do worker, em PL/SQL agendado no MAESTRO.
- **Trade-offs**: sem infra nova, mas mantém o trigger no legado (o custo de 20%) e move a lógica
  para o banco.
- **Por que foi rejeitada para esta PoC**: não muda a captura, que foi o ponto reprovado.

### Alternativa Rejeitada 3: DB link direto

- **Descrição**: o MAESTRO lê o cadastro do BDAD por views ou MViews.
- **Trade-offs**: entrega rápida, mas sem UUIDv7 e com custo de refatoração na virada.
- **Por que foi rejeitada para esta PoC**: abre mão do UUIDv7, requisito da migração.

---

## Questões em Aberto

| # | Questão | Quem responde | Prazo desejável | Impacto se não resolvida |
|---|---|---|---|---|
| Q-G1 | Qual o custo de licença do GoldenGate para BDAD e MAESTRO em produção (por processador, origem e destino) ou como serviço? | Tasso / compras com a Oracle | Antes do relatório | A recomendação pode ser inviável no orçamento. |
| Q-G2 | O time do BDAD aceita redo arquivado, registro forçado em redo e supplemental logging na base de produção? | Time do BDAD | Antes do relatório | Sem aceite, a captura por log não é possível. |
| Q-G3 | Há exclusão física nas tabelas reais (Q2 da PoC do worker)? | Time do legado | Antes de G11 | Define se RF-05 é crítico ou só registro. |
| Q-G4 | A aceitação do registro em redo muda o limite de overhead do C10 (Q5 da PoC do worker)? | Time do legado | Antes de G10 | G10 sem veredito definitivo. |
| Q-G5 | O time aceita operar GoldenGate (instalação, patch, atualização), ou isso exige contratação ou suporte externo? | Tasso | Antes do relatório | Custo de adoção incompleto no relatório. |
