# Comparativo Java x GoldenGate x Kafka (Debezium) por critério do PRD

Data: 2026-11-24. Estende [`comparativo-java-gg.md`](comparativo-java-gg.md) (mantido) com o lab Kafka (alternativa A: Debezium/LogMiner + Kafka + worker). Fontes: [`relatorio.md`](relatorio.md) (Java),
[`gg/relatorio.md`](gg/relatorio.md), [`kf/relatorio.md`](kf/relatorio.md) (regenerado com `bash scripts/relatorio.sh --resultados resultados/kf`), `docs/spike-kafka-notas.md` (Fase 3, partes 1 e 2) e os `meta.json` linkados.
Este documento organiza evidências; não escolhe a alternativa (decisão humana). Resultados oficiais de C2 e C5 do lab Kafka continuam **REPROVADO**; as variantes de configuração são descritivas e ficam separadas.

Leitura principal: **o Kafka é válido em C1–C10** (C9 com escopo reduzido de 300 s) e reprova só nos dois critérios de latência p95 (C2 e C5), cuja causa está na captura Debezium/LogMiner (piso de ~3,6 s de mediana), não no worker nem no Kafka. Como no
documento anterior, onde o Java é INVALIDO (TAXA) não há evidência a favor nem contra o worker Java: a comparação válida entre os três é C1, C8 e C10; entre GG e Kafka vale tudo.

## Tabela por critério

"Inválido" = INVALIDO/TAXA no `meta.json` Java (valores não publicados no relatório do Java). Todos os números do GG e do Kafka são de execuções VALIDAS.

| Critério | Alvo | Lab Java | Lab GG | Lab Kafka | Comparável? |
|---|---|---|---|---|---|
| C1 carga inicial | 0 divergências, 0 UUID regerado; tempo registrado | 109,9 s (1,85 M linhas), APROVADO | 565,9 s (2,01 M), APROVADO | **173,7 s** de `CargaInicial` (203,5 s até drenado; 1,7 M linhas; 8.693/14.711/8.570 linhas/s), APROVADO ([c1](kf/c1/meta.json)) | Integridade sim; tempo só com ressalva (massas diferentes; Kafka e Java usam o mesmo `CargaInicial`, Kafka ~1,6x mais lento, GG ~4,7x) |
| C2 regime | 1.000 ev/s, 600 s; p95 <= 5 s; sem tendência | Inválido | p95 2,82 s, APROVADO | **p95 6,43 s** (p50 3,90; max 21,3), tendência sem crescimento sustentado, **REPROVADO** ([c2](kf/c2/meta.json)) | GG x Kafka sim |
| C3 teto | >= 2.000 ev/s, degraus de 300 s | Inválido | teto 2.000 (4.000 saturado em 3.858), APROVADO | teto **2.000** (4.000 GERADOR_SATURADO em 3.459), APROVADO na repetição; 1ª execução INVALIDO (1.959 ev/s = 97,96% em 2.000) ([c3](kf/c3/meta.json)); p95 em 2.000: 10,2 s (GG 2,09 s) | GG x Kafka sim; Kafka sustenta a vazão, não a latência |
| C4 rajada | 50.000 linhas; recuperação <= 120 s | Inválido | 17,9 s, APROVADO | **28,7 s**, APROVADO ([c4](kf/c4/meta.json)) | GG x Kafka sim |
| C5 transação longa | 30 s aberta; p95 demais <= 5 s; aplicação <= 5 s após commit | Inválido | p95 2,88 s; 1,84 s, APROVADO | **p95 5,59 s REPROVADO**; 4,13 s após commit OK ([c5](kf/c5/meta.json)) | GG x Kafka sim (protocolo do GG e do Kafka iguais: UPDATE + sleep 30 s) |
| C6 queda | 0 divergências; lag normaliza | Inválido (111,8 s da queda) | 112,7 s da queda / 52,6 s do reinício, APROVADO (queda do hub inteiro) | **147,8 s / 87,6 s**, APROVADO (queda do worker; kf-worker-2 sobe aos 240 s) ([c6](kf/c6/meta.json)) | Não são o mesmo evento (hub x worker); tempo parecido em ordem de grandeza |
| C7 failover | <= 20 s | Inválido (30,0 s, 2 workers) | 25,8 s REPROVADO (adaptado: reinício do hub) | **14,4 s APROVADO** (2 workers, `LEASE_SEG=12`; lease 6 s = 5,6 s; 15 s = 16,1 s) ([c7](kf/c7/meta.json)) | Kafka e Java têm o mesmo mecanismo (lease no banco); GG mede outra coisa |
| C8 captura parada | 100% corrigido | 55.629/55.629 (por delta e reenfileiramento), APROVADO | 55.904/55.904 (pelo redo), APROVADO | **55.697/55.697** (pelo redo, conector `STOPPED`), 4 archives necessários preservados, APROVADO ([c8](kf/c8/meta.json)) | Sim no resultado; Kafka e GG pelo mesmo princípio (redo) |
| C9 | Descritivo | Inválido (threads do worker) | Parâmetros do Replicat; BATCHSQL sustenta 2.000 ev/s | Modos do worker (threads, lote, poll): **nenhum se separa do ruído** (p95 de 5,2 a 5,6 s a 1.000 ev/s; 6,2 a 10,4 s a 2.000); relatório marca ESCOPO_REDUZIDO (C2 de 300 s) ([c9](kf/c9/meta.json)) | Não: comparam coisas diferentes |
| C10 custo no legado | <= 15% E <= 5 ms | +0,958 ms, +19,99% REPROVADO (triggers) | +0,102 ms, +1,74% APROVADO | **+0,611 ms, +10,3% APROVADO**; CPU do legado +17,8 pontos (GG +13,7) ([c10](kf/c10/meta.json)) | Parcial: ver (b) |

## (a) O que é comparável e o que não é

- **GG x Kafka (ambos sem trigger de captura no legado, mesma massa, mesmos hosts, mesmo gerador):** comparação direta em C1–C5, C8, C10. A diferença material é a **latência de captura**: GG p95 2,8 s (p50 1,85 s), Kafka p95 5,6–6,4 s (p50 3,6–3,9 s, mesmo a 500 ev/s).
  O Debezium/LogMiner entrega em rajadas a cada ~3 s e não tem botão de configuração para isso no 3.7.1: nenhuma das 6 variantes legítimas testadas (poll, filtro no banco, logs mínimos, sem metadados de transação, combinação) tirou o p95 de 5,3–6,2 s, e `redo_log_catalog` piorou para 215 s.
  Em compensação o Kafka sustenta 2.000 ev/s (drena em ~30 s depois) e a recuperação de rajada e o failover do worker cumprem os critérios.
- **Java x Kafka:** o worker é o mesmo código nos dois (domínio e aplicação), a pergunta é a fonte (outbox por trigger x Kafka). Só C1, C7 (mesmo mecanismo de lease), C8 e C10 têm número Java válido ou comparável. Em C7 o Java deu 30,0 s (inválido, mas indicativo, `LEASE_SEG` do gate) contra 14,4 s do Kafka com `LEASE_SEG=12`: os dois
  mecanismos são iguais, a medição Java não é aceita.
- **C6, C7 e C9 são adaptados em cada lab** (Java: kill do worker + novo; GG: reinício do hub; Kafka: kill do worker + novo, e dois workers no C7). Ver o texto de cada `meta.json`.

## (b) Diferenças de ambiente e protocolo

- **Igual nos três:** imagem Oracle `gvenzl/oracle-free:23-slim-faststart` (2 CPU / 3 GiB), redo de 4 x 512 MB, massa 200k/500k/1M, gerador de 2 CPU / 1 GiB, critérios e relógios medidos (`|skew|` <= 1,3 ms no Kafka). Java e Kafka: worker de 2 CPU / 1 GiB, 1 worker em C2–C5 e C8–C10.
- **Latência por evento no Kafka** = commit do worker no kf-novo menos `updated_at` da origem, corrigida pelo offset de relógios; no GG, `aplicado_em` do `aplicacao_log` (trigger no destino); no Java, o CSV do worker. A latência de cada lab inclui o custo do seu próprio registro.
- **C1:** o Kafka reconstrói o lab do zero (conector antes da carga, `CargaInicial`, workers, drenagem); o GG usa `INSERT ... AS OF SCN` por DB link. O UUID do C1 no Kafka é verificado **sem replay** (o tópico fica com 0 eventos), diferente do GG que reaplica o trail.
- **C10:** protocolo do GG repetido (aquecimento de 300 s descartado, 600 s por fase, 4 pares balanceados). "Desligada" = conector Debezium parado (offset preservado). **O supplemental logging (ALL COLUMNS, FORCE LOGGING) fica ligado nas duas fases, como no GG: o custo do supplemental não é isolado** e a variante sem supplemental não foi feita (o redo
  incompleto travaria/corromperia o worker; no GG foi inconclusiva e quebrou o Replicat). Os pares do Kafka dependem da ordem (LD: +27% e +39%; DL: -12% e -7%); média +10,3% com desvio entre fases (~1 ms) do tamanho do efeito: custo da ordem de 0 a 1 ms, não separado do ruído.
- **C8:** no Kafka forçaram-se 3 trocas de redo durante a parada para haver archives necessários de verdade (4 no total); o GG tinha 1.
- **Massa cresce entre cenários** nos três; o Kafka reconstruiu a massa no C1 (1,7 M linhas) e depois rodou C2–C10 sobre ela.

## (c) Custos de infraestrutura (alternativa A)

Medidos no lab Kafka (`resultados/kf/c10/meta.json`, `docker stats`, 100% = 1 CPU, limite 200%); **valores de pisos do lab, não de produção**:

| Container | Repouso (sem DML, 1 worker) | C10 ligada | C10 desligada (conector parado) | Memória média |
|---|---|---|---|---|
| kf-kafka (broker, 2 CPU / 2 GiB) | **42,4%** | 41,8% | 37,2% | 1,1 GiB |
| kf-connect (2 CPU / 2 GiB) | 2,7% | 16,4% | 0,9% | 0,8 GiB |
| kf-worker-1 (2 CPU / 1 GiB) | 15,5% | 21,8% | 10,9% | 0,56 GiB |
| kf-novo (destino) | 4,5% | 35,8% | 6,3% | 2,7 GiB |
| kf-legado (origem) | 8,3% | **112,5%** | 94,7% | 2,1 GiB |

- **Custo no legado:** o LogMiner soma **+17,8 pontos de CPU** (de 200%) com a mesma carga, contra +13,7 do Extract do GG; o supplemental ALL COLUMNS fica nos dois e gera mais redo (não isolado).
- **Infra fixa da alternativa A, em repouso:** broker + Connect + 1 worker ~ 0,6 CPU e ~2,5 GiB, **sem tráfego**. Os ~42% do broker vêm dos 48 consumers por (tabela, partição) com `assign()` manual fazendo poll (2,3% sem workers, Fase 2); é custo do desenho do worker, não do Kafka. Sob 2.000 ev/s: kf-novo 65%, kf-kafka 44%, kf-connect 32%, worker 31%.
- **Ressalvas:** broker de um nó, sem replicação, sem HA e sem ZooKeeper/KRaft de 3 nós; Connect de um worker; o GG usa um hub de 2 CPU / 4 GiB. O Confluent Cloud (etapa 2) trocaria parte desse custo por serviço gerenciado e por rede.

## (d) Achados operacionais do Kafka

- **Queda do broker para a captura por 5 min (Connect de um worker).** O Connect perde o coordenador do grupo em 3 s, revoga a atribuição e **para a tarefa**; ela só volta depois de `scheduled.rebalance.max.delay.ms` (padrão 300.000 ms) mesmo com o broker de volta; nada se perde (redo retido, 0 divergências). Com o parâmetro em 0 a primeira aplicação veio 111 s depois do start (346 s com o padrão), mas com
  **54.280 duplicatas no tópico (9%)**. Um Connect de vários workers migraria a tarefa.
- **Duplicatas na volta do Connect:** `kill -9` do Connect reemite desde o último offset gravado (**26.472 eventos, 4,4%**); o guard de versão do worker descarta e o estado final fica correto (0 divergências, 0 UUIDs). Worker morto (C6/C7) não gerou duplicatas.
- **Monitoramento enganoso:** com o broker fora a REST do Connect mostrou a tarefa `RUNNING` o tempo todo e `pendentes` ficou 0 porque o lag não pôde ser medido. Em produção usar o instante do último evento publicado/aplicado, não esses dois sinais.
- **Pausas do LogMiner na troca de redo** (`No logs available yet`, 12 episódios no Connect): picos de 20–26 s de latência sem relação com a carga; explicam o `max` de 21–23 s e parte do p95.
- **`log.mining.sleep.time.*` e `log.mining.batch.size.*` não existem no Debezium 3.7.1** (ignorados); só `poll.interval.ms`, `max.batch.size`, `log.mining.log.count.min`, `log.mining.query.filter.mode`, `log.mining.strategy` e semelhantes; nenhum mexe no piso de ~3,6 s.
- **Failover pelo lease:** 5,6 s (`LEASE_SEG=6`), 14,1–14,6 s (12, três medidas), 16,1 s (15): ~ lease + 4 s no pior caso; a 12 s o limite de 20 s do PRD é cumprido com ~5 s de folga. O conector e o broker não participam do failover do worker.
- **`EVENTOS_CSV` do worker é CREATE_NEW:** worker substituto precisa de outro id. A queda de um worker deixa o CSV de eventos até ~17 linhas aquém do gerador (o lote em voo não é escrito) sem perda de dado (reconciliação 0).
- **Autostart:** diferente do GG (que desfaz o STOP manual do Extract em ~6 s), `PUT /connectors/kf-oracle/stop` mantém o conector parado de fato (status `STOPPED` amostrado a cada 30 s em todas as fases desligadas do C10).
- **Atomicidade multi-tabela: não medida.** O worker aplica por (tabela, partição); um cadastro de obra com fonogramas pode aparecer parcial por instantes, **também no Java**. O GG preserva a transação. O tópico de transações do Debezium (`provide.transaction.metadata`) está habilitado e a **variante transacional é a Fase 4**, ainda não feita.

## (e) Pendências e o que este documento não decide

- Fase 4 (atomicidade e custo em vazão) e Fase 5 (consolidação e recomendação humana).
- C9 com C2 de 600 s se for preciso o relatório do repo aceitar o escopo; variante sem supplemental do C10; `snapshot.mode=initial` do Debezium no C1; Connect com mais de um worker; XStream no lugar do LogMiner; Confluent Cloud (etapa 2).
- O Java segue sem medição válida em C2–C7 e C9: o comparativo do worker Java x Kafka depende de repetir o lab Java com origem que feche a taxa.
- Tudo é piso do Oracle Free (2 CPU / 2 GB de SGA, 1 broker, 1 Connect, sem HA); os números de produção (Oracle EE, vários brokers, Connect distribuído) não podem ser extrapolados daqui.
