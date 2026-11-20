# Plano: PoC parte 2, alternativa D (Oracle GoldenGate Free)

Status (2026-11-20): Fases 0 a 5 concluídas; decisão humana pendente. Data: 2026-11-19.
Fases: 0 spike concluída; 1 ambiente concluída; 2 ferramentas concluída; 3 calibração concluída; 4 cenários C1 a C10 concluídos (C7 adaptado REPROVADO, demais APROVADOS); 5 consolidação concluída.
Comparativo Java x GG: [`resultados/comparativo-java-gg.md`](../resultados/comparativo-java-gg.md). Recomendação (rascunho, sem decisão): [`resultados/gg/recomendacao-humana.md`](../resultados/gg/recomendacao-humana.md).

## Objetivo

Medir a alternativa D de `docs/desenho-alternativas.excalidraw` (GoldenGate Oracle → Oracle, sem
trigger e sem outbox no BDAD) com a **mesma massa, os mesmos critérios e os mesmos limites de
recurso** da PoC do worker Java, para que os números fiquem lado a lado no relatório.
O lab do worker Java não é desfeito.

## Decisões (2026-11-19)

- **Lab Java parado durante as medições GG:** aprovado. `docker stop`, nunca `rm`; volumes preservados.
- **Exclusão lógica.** A réplica nunca apaga linha: exclusão é `excluido = 1`, mantendo linha
  e UUID. É o caso mais comum e já é o que o gerador faz (`UPDATE … SET excluido = 1`), o que
  o GG replica como um update comum. Um `DELETE` físico eventual na origem é convertido no
  Replicat (`UPDATEDELETES` + `excluido = 1` por `@CASE` no tipo de operação) e contado, como as
  anomalias `ausentes` do worker Java. A reconciliação compara o mesmo estado nos dois labs.
- **GoldenGate já existente no BDAD.** O BDAD real já tem um GoldenGate em uso para um cenário de
  analytics (explica o supplemental logging ALL e os grupos `GGS_*` em OBRA_MUSICAL,
  OBRA_TITULAR e TITULARIDADE). **Nesta PoC consideramos a nossa replicação a única:** o lab
  não simula esse Extract concorrente. Consequências a citar no relatório:
  - o custo no legado (C10) medido aqui é o de um Extract isolado; em produção parte do custo
    (supplemental logging, archivelog, force logging) já é pago, e um segundo Extract ou a
    reutilização do existente muda a conta;
  - licença, skill e operação de GoldenGate já existem na casa, o que pesa a favor da
    alternativa D e deve ser confirmado com o DBA (versão, edição, quem opera, se o trail
    pode ser compartilhado).

## 1. Isolamento do lab Java (decisão principal)

Recomendação: **um lab paralelo, com prefixo próprio, e o lab Java parado (não removido) enquanto
os cenários GG rodam.**

| Item | Lab Java (intocado) | Lab GG (novo) |
|---|---|---|
| Legado | `poc-legado` em desenv-server:1521, volume `poc-legado` | `gg-legado` em desenv-server:1523, volume `gg-legado` |
| Destino | `poc-novo` em infra-server:1522, volume `poc-novo` | `gg-novo` em infra-server:1524, volume `gg-novo` |
| Ponte | worker `poc-worker-*` | `gg-hub` (goldengate-free) em infra-server, volume `gg-hub` |
| Rede | `poc-rede` | `gg-rede` |
| Inventário | `.tsg-flow/v01-inventario` | `.tsg-flow/gg-inventario` |

Por quê:

- **Prefixo `gg-`, nunca `poc-gg-`.** `subir.sh`, `derrubar.sh` e os runners filtram por
  `^/poc-`: um container `poc-gg-*` seria tratado (e removido) como recurso da PoC Java.
- **Parar, não remover.** Durante as medições GG: `docker stop poc-legado poc-novo` (volumes e
  massa ficam). Ao final: `docker start`. Rodar os dois labs juntos não é aceitável: os hosts
  têm 8 vCPU/15 GiB compartilhados, cada Oracle usa 2 CPU/3 GiB e a PoC Java já mostrou o
  legado perto do teto de 2 CPUs (C2–C7 inválidos por TAXA). Medir GG com o lab Java ligado
  contaminaria as duas séries.
- **Base nova em vez de reaproveitar `poc-legado`.** O legado Java tem triggers de captura,
  outbox e alterações deixadas pelo C10 sem replicação. A alternativa D exige origem sem trigger
  e com ARCHIVELOG/FORCE LOGGING; mexer nisso descaracteriza o lab Java.
- Mesma imagem (`gvenzl/oracle-free:23-slim-faststart`), mesmos limites `--cpus=2 --memory=3g`,
  mesmos redo logs (4 × 512 MB, `00_redo.sql`).
- O `gg-hub` também com `--cpus=2 --memory=…` explícito (equivalente ao worker de 2 CPU/1 GiB;
  o GG deve precisar de mais memória, registrar o valor usado).

## 2. Restrições do Free que afetam o plano (só PoC, não vão para o desenho)

- **GoldenGate Free não suporta Parallel Replicat com Oracle Database Free.** O desenho prevê
  Replicat paralelo; aqui usaremos Replicat clássico ou coordenado (`THREADS n`), a confirmar
  no spike. É o principal limite de vazão a registrar no relatório.
- GoldenGate Free: banco conectado até 20 GB (CDB inteiro). Oracle Free: 12 GB de dados de
  usuário, 2 CPUs, 2 GB de RAM. A massa atual (~1,85 M linhas) cabe; archive logs não contam
  como dado de usuário, mas ocupam disco.
- **Memória do log mining server.** O Extract integrado usa streams pool dentro dos 2 GB do
  Oracle Free. Limitar com `TRANLOGOPTIONS INTEGRATEDPARAMS (MAX_SGA_SIZE …)` e medir.
- **CPU do legado.** O mining roda dentro do `gg-legado` e disputa as 2 CPUs com o gerador.
  Isso é justamente o custo no legado (C10), mas pode repetir a invalidação por TAXA.
- Sem suporte Oracle, sem Veridata: a conferência continua sendo a reconciliação da PoC.

## 3. Fases

### Fase 0: spike de viabilidade (timebox de 1 a 2 dias, massa reduzida)

Responder com evidência antes de construir os runners:

1. `gg-legado` em ARCHIVELOG, `FORCE LOGGING`, `ENABLE_GOLDENGATE_REPLICATION=TRUE`,
   `c##ggadmin` e `ADD SCHEMATRANDATA bdad`. Funciona na imagem faststart?
2. Imagem `container-registry.oracle.com/goldengate/goldengate-free` sobe no infra-server e
   alcança os dois bancos. Expõe Admin Service/REST e adminclient, ou só a UI de pipelines?
   (Os runners precisam de automação; só UI é bloqueante.)
3. Extract integrado no PDB (`FREEPDB1`) com 2 GB de RAM.
4. Replicat: clássico x coordenado; `KEYCOLS (codigo)`; `COLMAP` com
   `@GETENV('GGHEADER','COMMITTIMESTAMP')`; `DBOPTIONS NOSUPPRESSTRIGGERS`; CDR `USEMAX`.
5. UUIDv7 por trigger `BEFORE INSERT` no destino; update nunca toca `id`.
6. Carga inicial instanciada por SCN (Initial Load do GG ou `expdp FLASHBACK_SCN` + `AFTERCSN`),
   gerando UUID no destino.
7. Exclusão lógica: `UPDATE excluido = 1` replica como update; `DELETE` físico na origem vira
   `excluido = 1` no destino (`UPDATEDELETES` + `@CASE`), sem apagar a linha nem trocar o UUID.

Saída: nota curta em `docs/` com o que funciona, o que não, e os parâmetros escolhidos.
Se o item 2 ou 3 falhar, voltar com o problema antes da Fase 1.

### Fase 1: ambiente reprodutível

**Status (2026-11-20): concluída.** A política de limpeza de archive logs com alarme de disco foi feita na Fase 4 (`scripts/gg/limpar-archive.sh`; a imagem slim não tem `rman`, a limpeza é por arquivo respeitando o `required_checkpoint_scn`). Evidência e tempos em `docs/spike-goldengate-notas.md`, seção "Fase 1". Desvio do plano: o esquema do legado/destino reaproveitou os arquivos do spike (`sql/gg/...`); o `00_redo.sql` foi reutilizado.

- `scripts/gg/subir.sh` e `scripts/gg/derrubar.sh`, mesmos pré-requisitos do `subir.sh`
  (NTP, RAM ≥ 4 GB, disco, portas 1523/1524 livres, relógios ≤ 100 ms), inventário próprio,
  e verificação de que `poc-legado`/`poc-novo` estão **parados e com volume intacto**.
- `sql/gg/legado/`: `00_redo.sql` (reuso), `00_goldengate.sql` (archivelog, force logging,
  parâmetros, usuário), `01_schema.sql` (tabelas + triggers `*_updated` que simulam o Delphi;
  **sem** `*_captura` e sem `evento_replicacao`), `02_massa.sql` (mesma massa:
  200k/500k/1M, mesmo script).
- `sql/gg/novo/01_schema.sql`: mesmas tabelas réplica de `sql/novo` (sem lease/heartbeat), mais
  `origem_commit_ts`, trigger de UUIDv7 e uma tabela de log de aplicação
  (`tabela, codigo, op, origem_commit_ts, aplicado_em`) alimentada por trigger, para p95 por
  evento (equivalente ao `EVENTOS_CSV` do worker). Heartbeat table do GG para lag por processo.
- `gg/` com os arquivos de parâmetro versionados (Extract, Distribution Path, Replicat) e
  script de criação via REST/adminclient.
- Política de limpeza de archive logs já consumidos pelo Extract (RMAN), com alarme de disco.

### Fase 2: reaproveitar as ferramentas da PoC sem quebrar o lab Java

- Java (`gerador`, `reconciliacao`, `snapshot-ids`): já leem `LEGADO_URL`/`NOVO_URL`; basta
  apontar para 1523/1524. Nenhuma mudança no `worker/`.
- Scripts com `poc-legado`/`poc-novo` fixos (`comum.sh`, `snapshot-ids.sh`,
  `coletar-recursos.sh`, `carga_custo.py`, `executor.py`…): parametrizar por variável com
  **padrão igual ao atual**, para o lab Java seguir idêntico. Gate: os testes existentes
  continuam passando contra o lab Java.
- Runners GG em `scripts/gg/cenarios/`, reaproveitando `executor.py`/`analise.py`; a métrica
  do worker é trocada pela tabela de log + lag do GG. Resultados em `resultados/gg/cN/` e
  consolidação por `relatorio.sh --resultados resultados/gg`.

### Fase 3: calibração do gerador (antes de qualquer cenário)

**Status (2026-11-20): concluída.** Taxa efetiva >= 100% do alvo a 1.000 e 2.000 ev/s com e sem Extract; legado sem saturação sustentada; latência de base ajustada; primeiro C2 curto (1.000 ev/s, 300 s) aprovado. Evidência em `docs/spike-goldengate-notas.md`, seção "Fase 3 e C2 curto".

Rodar o gerador contra `gg-legado` com Extract parado e confirmar taxa ≥ 98% do alvo a 1.000 e
2.000 ev/s. Sem trigger de captura o custo por transação é menor e a taxa pode fechar; se não
fechar, os cenários sairão INVALIDO/TAXA de novo e o problema é o ambiente, não a ponte.
Registrar antes de seguir.

### Fase 4: cenários com os mesmos números

| Cenário | Critério (igual ao PRD) | Adaptação para GG |
|---|---|---|
| C1 carga inicial | 0 divergências, 0 UUID regerado; registrar tempo (Java: 109,9 s) | instanciação por SCN + início do Replicat após o SCN |
| C2 regime | 1.000 ev/s por 600 s; p95 ≤ 5 s; lag sem tendência | latência = `aplicado_em − origem_commit_ts`, corrigida pelo offset de relógio medido |
| C3 teto | ≥ 2.000 ev/s, degraus 500/1000/2000/4000 de 300 s | Replicat clássico x coordenado |
| C4 rajada | 50.000 linhas; recuperação ≤ 120 s; sem perda | igual |
| C5 transação longa | 30 s aberta; p95 das demais ≤ 5 s; aplicação ≤ 5 s após commit | igual; esperado que o GG não bloqueie as demais |
| C6 queda | 0 divergências; lag ≤ 5 s após retomada | `docker kill` do processo/hub com carga; retomada pelo checkpoint |
| C7 failover | ≤ 20 s | **adaptado**: não há segunda réplica no Free; medir reinício do hub até lag normal. HA de produção (XAG/RAC) fica fora |
| C8 captura parada | 100% corrigido | Extract parado 60 s com updates; ao religar, deve recuperar tudo pelo redo, sem reconciliação |
| C9 threads | comparação descritiva (SEM_CRITERIO) | Replicat clássico x coordenado com N threads |
| C10 custo no legado | ≤ 15% E ≤ 5 ms (Q-T3 pendente) | ligado = Extract ativo + supplemental logging; desligado = Extract parado. Comparar com os 19,99% / 0,958 ms dos triggers |

**Status da Fase 4 (2026-11-20): preparação e C3 concluídos.** Runner `scripts/gg/cenario.sh` (c2/c3, meta.json no contrato do lab Java), limpeza de archive integrada. O C3 com Replicat clássico foi APROVADO (teto 2.000 ev/s válido, p95 2,1 s; 4.000 ev/s inválido por GERADOR_SATURADO a 3.858 ev/s); o coordenado não foi medido (critério aprovado no clássico; THREADRANGE pode dividir transação de origem entre threads, o que viola a atomicidade exigida). A ressalva do BATCHSQL (111.837 x 120.011) era comparação com a meta nominal: sem perda. Detalhes na seção "Fase 4" de `docs/spike-goldengate-notas.md`. C2 (600 s), C4, C5 e C1 também **APROVADOS** (2026-11-20; `scripts/gg/cenario.sh c1..c5`): C2 p95 2,82 s; C4 recuperação 17,9 s (patamar 3,71 s); C5 p95 das demais 2,88 s e aplicação da longa 1,84 s após o commit (transação longa por SQL, o modo do gerador Java depende de `evento_replicacao`); C1 carga por SCN em 565,9 s para 2,01 M linhas (~3.550 linhas/s contra ~16.800 do Java; formas de acelerar documentadas, não implementadas). Detalhes na seção "Fase 4: C1, C2, C4, C5" de `docs/spike-goldengate-notas.md`. **C6 a C10 concluídos (2026-11-20):** C6 APROVADO (normaliza 112,7 s da queda / 52,6 s do reinício, 0 divergências), C7 adaptado REPROVADO contra 20 s (25,8 s, processos RUNNING em ~10 s), C8 APROVADO (55.904/55.904 pelo redo, sem reconciliação), C9 descritivo (BATCHSQL sustenta 2.000 ev/s; sem ele o lag cresce até 57 s), C10 APROVADO com ressalva (+13,04% / +0,726 ms contra 19,99% / 0,958 ms do Java; efeito de aquecimento na série ligada; variante sem supplemental inconclusiva e o supplemental logging se mostrou obrigatório). `relatorio.sh --resultados resultados/gg` mostra C1 a C10 válidos. Achado: STOP manual do Extract é desfeito pelo autostart (tratado em `comum-cenario.sh`). Detalhes na seção "Fase 4: C6 a C10" de `docs/spike-goldengate-notas.md`. Próxima: Fase 5 (consolidação).

Ordem: C1 → C2 → C3 → C4 → C5 → C6 → C7 → C8 → C9 → C10 (C10 por último, como no lab Java).

### Fase 5: consolidação

**Status (2026-11-20): concluída, exceto o retorno do lab Java.** `resultados/gg/relatorio.md` regenerado (C1 a C10 válidos), comparativo em `resultados/comparativo-java-gg.md` e rascunho de recomendação em `resultados/gg/recomendacao-humana.md` (decisão PENDENTE; o autor decide). O retorno do lab Java (`docker start poc-legado poc-novo` e conferência de saúde e contagens) **não foi feito**: fica para quando o lab GG não for mais necessário.

- `resultados/gg/relatorio.md` e uma tabela comparativa Java x GG por critério.
- Ressalvas explícitas: Free sem Parallel Replicat, 2 CPUs/2 GB, hosts compartilhados,
  relógio do WSL2 (usar relógio dos bancos para durações).
- Recomendação humana em `resultados/gg/recomendacao-humana.md`, como no lab Java.
- Retornar o lab Java: `docker start poc-legado poc-novo` e conferir saúde e contagens.

## 4. Riscos

| Risco | Efeito | Mitigação |
|---|---|---|
| GG Free sem automação (só UI) | runners inviáveis | spike item 2; fallback: adminclient no container |
| Mining estoura 2 GB/2 CPUs do Oracle Free | Extract instável ou taxa inválida | `MAX_SGA_SIZE`, Fase 3, registrar como limite do ambiente |
| Archive logs enchem o disco | banco para | RMAN + alarme na vigilância |
| Teto de vazão limitado pelo Replicat não paralelo | C3 reprova por limite da edição | registrar como piso; não extrapolar para produção |
| Alterar scripts compartilhados quebra o lab Java | perde reprodutibilidade | padrões atuais nas variáveis + gates existentes |

## 5. Em aberto

- Com o DBA, sobre o GoldenGate de analytics no BDAD: versão/edição, licença por processador
  já paga, se um segundo Extract é aceitável ou se o trail existente pode ser reutilizado.
  Não bloqueia a PoC; entra na recomendação.
