# Relatório da PoC de replicação BDAD

Fonte: última execução em cada `<cenario>/meta.json`; históricos não são contados novamente. Valores INVALIDO/NAO_EXECUTADO ficam vazios. Os CSVs e logs são a evidência oficial; a aprovação do gerador do relatório não aprova o desenho arquitetural.

## Critérios do PRD

| Métrica | Alvo | Valor medido | Veredito | Evidência |
| --- | --- | --- | --- | --- |
| Latência em regime (C2) | 1.000 ev/s por 600 s; p95 ≤ 5 s; lag sem tendência de alta | C2/p95_ms: 6429; C2/tendencia_lag: {"amostras": 35, "crescimento_sustentado": false, "inclinacao_ms_s": 16.080499683287805, "janela_s": 180} | REPROVADO | [C2](c2/meta.json) |
| Teto de vazão (C3) | ≥ 2.000 ev/s; degraus de 300 s | C3/teto_ev_s: 2000 | APROVADO | [C3](c3/meta.json) |
| Integridade após falha (C6, C7) | 0 divergências em ambos |  | NAO_EXECUTADO | C6 (sem meta), C7 (sem meta) |
| Estabilidade de identidade | 0 UUIDs alterados em C1–C9; C10 não aplicável |  | NAO_EXECUTADO | [C1](c1/meta.json), [C2](c2/meta.json), [C3](c3/meta.json), [C4](c4/meta.json), [C5](c5/meta.json), C6 (sem meta), C7 (sem meta), C8 (sem meta), C9 (sem meta) |
| Failover (C7) | ≤ 20 s |  | NAO_EXECUTADO | C7 (sem meta) |
| Correção da captura desligada (C8) | 100% das linhas afetadas corrigidas |  | NAO_EXECUTADO | C8 (sem meta) |
| Custo no legado (C10) | ≤ 15% E ≤ 5 ms, segundo critério proposto; Q5/Q-T3 pendentes |  | NAO_EXECUTADO | C10 (sem meta) |
| Rajada (C4) | ≤ 120 s após commit de 50.000 linhas; sem perda/divergência | C4/recuperacao_rajada_s: 28.73; C4/divergencias: 0; C4/pendentes_final: 0 | APROVADO | [C4](c4/meta.json) |
| Transação longa (C5) | 30 s aberta; p95 das demais ≤ 5 s; aplicação após commit ≤ 5 s | C5/p95_demais_durante_aberta_ms: 5587; C5/aplicacao_apos_commit_ms: 4133.2 | REPROVADO | [C5](c5/meta.json) |
| Validade dos cenários | 100%; taxa ≥ 98% do alvo e diferença de relógios ≤ 100 ms |  | NAO_EXECUTADO | Inventário abaixo |

Resultados parciais válidos aparecem no detalhamento; uma métrica composta exige todas as suas evidências. C10 usa o limite efetivo registrado pelo runner; o padrão proposto DT-09 é ≤15% E ≤5 ms, sem substituir o aceite do time do legado. O teto de C3 preserva degraus válidos anteriores a GERADOR_SATURADO.

## Cenários e lacunas

| Cenário | Execução | Estado | Motivo / limitação | Evidência |
| --- | --- | --- | --- | --- |
| C1 | 20261123T125636Z | APROVADO |  | [meta](c1/meta.json) |
| C2 | 20261123T131717Z | REPROVADO |  | [meta](c2/meta.json) |
| C3 | 20261123T135846Z-repeticao | APROVADO |  | [meta](c3/meta.json) |
| C4 | 20261123T142814Z | APROVADO |  | [meta](c4/meta.json) |
| C5 | 20261123T144330Z | REPROVADO |  | [meta](c5/meta.json) |
| C6 |  | NAO_EXECUTADO | meta.json ausente |  |
| C7 |  | NAO_EXECUTADO | meta.json ausente |  |
| C8 |  | NAO_EXECUTADO | meta.json ausente |  |
| C9 |  | NAO_EXECUTADO | meta.json ausente |  |
| C10 |  | NAO_EXECUTADO | meta.json ausente |  |

## Medições e diagnóstico por cenário

### C1

Janela UTC: 2026-11-23T12:56:36Z → 2026-11-23T13:16:49Z. Artefatos: `resultados/kf/c1/20261123T125636Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| contagens_iguais | {"legado": {"FONOGRAMA": 1000000, "OBRA_MUSICAL": 500000, "TITULAR": 200000}, "novo": {"FONOGRAMA": 1000000, "OBRA_MUSICAL": 500000, "TITULAR": 200000}} | iguais por tabela no escopo declarado | APROVADO |  |  |
| carga_inicial | {"log": "carga-inicial.log", "tabelas": {"FONOGRAMA": {"linhas": 1000000, "linhas_s": 8570.29}, "OBRA_MUSICAL": {"linhas": 500000, "linhas_s": 14710.66}, "TITULAR": {"linhas": 200000, "linhas_s": 8693.05}}, "tempo_s_relogio_desenv": 289.5, "tempo_total_s": 173.682} | linhas/s por tabela e tempo total; linhas copiadas == linhas do legado (sem DML durante a carga) | APROVADO |  |  |

### C2

Janela UTC: 2026-11-23T13:17:20Z → 2026-11-23T13:34:25Z. Artefatos: `resultados/kf/c2/20261123T131717Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| p95_ms | 6429 | <=5000 | REPROVADO | Hipótese: contenção de CPU, banco, conexões ou rede | Correlacionar recursos.csv, taxa efetiva, lag e logs; ajustar o gargalo medido e repetir o cenário |
| tendencia_lag | {"amostras": 35, "crescimento_sustentado": false, "inclinacao_ms_s": 16.080499683287805, "janela_s": 180} | sem crescimento sustentado | APROVADO |  |  |

### C3

Janela UTC: 2026-11-23T13:58:54Z → 2026-11-23T14:27:48Z. Artefatos: `resultados/kf/c3/20261123T135846Z-repeticao`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| teto_ev_s | 2000 | >=2000 em degrau valido | APROVADO |  |  |

### C4

Janela UTC: 2026-11-23T14:28:22Z → 2026-11-23T14:42:53Z. Artefatos: `resultados/kf/c4/20261123T142814Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| recuperacao_rajada_s | 28.73 | <=120 apos commit | APROVADO |  |  |

### C5

Janela UTC: 2026-11-23T14:43:38Z → 2026-11-23T14:58:23Z. Artefatos: `resultados/kf/c5/20261123T144330Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| p95_demais_durante_aberta_ms | 5587 | <=5000 | REPROVADO | Hipótese: contenção de CPU, banco, conexões ou rede | Correlacionar recursos.csv, taxa efetiva, lag e logs; ajustar o gargalo medido e repetir o cenário |
| aplicacao_apos_commit_ms | 4133.2 | <=5000 | APROVADO |  |  |
| latencia_transacao_longa_ms | 34145 | reportada separadamente | SEM_CRITERIO |  |  |

### C6

NAO_EXECUTADO: meta.json ausente. Valores não publicados.

### C7

NAO_EXECUTADO: meta.json ausente. Valores não publicados.

### C8

NAO_EXECUTADO: meta.json ausente. Valores não publicados.

### C9

NAO_EXECUTADO: meta.json ausente. Valores não publicados.

### C10

NAO_EXECUTADO: meta.json ausente. Valores não publicados.

## Ambiente e carga dos servidores

Servidores compartilhados desenv-server e infra-server: 8 vCPU e 15 GiB por host no planejamento, outras aplicações ativas, rede interna. A PoC usa Oracle Free nos dois lados (DT-01); os números não transferem para PostgreSQL e não equivalem à produção. Este ambiente fornece um piso de capacidade e um teto de latência de rede para comparação, sem garantir esses valores em produção. O hash da reconciliação é probabilístico, não uma prova de igualdade sem risco de colisão. Limites efetivos observados abaixo; valores ausentes não são inferidos.

| Cenário | Container | CPUs | Memória (bytes) | Imagem | Digest / SHA-256 do jar |
| --- | --- | --- | --- | --- | --- |
| C1 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C1 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C1 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C1 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C1 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C2 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C2 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C2 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C2 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C2 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C3 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C3 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C3 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C3 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C3 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C4 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C4 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C4 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C4 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C4 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C5 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C5 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C5 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C5 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C5 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |

CPU 0/memória 0 no inventário Docker significam sem limite configurado. A carga abaixo é load average de 1 minuto do host, não percentual de CPU. O meta preserva inventários antes/durante/depois, identidade, estado e reinícios dos serviços existentes. Cada execução também arquiva recursos.csv com CPU, memória e sessões dos bancos quando coletados; a ausência de amostras não é tratada como carga zero. A carga ambiental é mostrada mesmo para um cenário inválido, sem validar suas medições de resultado.

| Cenário | Host | Etapa | Amostras | Load médio | Load máximo | Fonte |
| --- | --- | --- | --- | --- | --- | --- |

Recursos dos containers: CPU Docker pode chegar a 200% para o limite de 2 CPUs. A marca de proximidade (pico ≥190%) é uma hipótese de saturação a correlacionar com cada degrau, não atribuição causal. Memória é a última amostra; sessões são o máximo observado. As séries e os horários completos ficam nos CSVs.

| Cenário | Container | Amostras | CPU média % | CPU máxima % | Memória | Sessões máx. | Indício | Fonte |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

## Ressalvas aceitas e limites de interpretação

Task 8.0, decisão humana de 2026-10-31 (8.0_task_review.md): em 17 medições independentes, failover min 10,7 s / p50 14,5 s / p90 16,5 s / máx 20,02 s; uma medição acima de 20 s. O gate é intermitente (~1 em 6): failover marginal e corrida no pareamento dos CSVs. A tomada por marca histórica e consulta posterior não comprova atividade simultânea dos 48 processadores. Os testes usaram JVMs locais e sinais POSIX, sem --cpus por worker; não se confundem com estes cenários Docker. LEASE_SEG=12 nos testes e 15 por padrão. O WSL2 apresenta saltos de relógio de parede; durações devem usar relógio do banco ou monotônico. Consistência sobre disponibilidade; failover A→B é exceção.

Task 15.0: conta Elastic com escrita irrestrita (unrestricted_write) aceita pelo usuário em 2026-11-01; restrição de namespace por código, sem prova de recusa por permissão do servidor fora da PoC. CSVs permanecem oficiais. A exportação/visualização no Kibana não é comprovada pelo relatório.

Task 11.0: recomendação não bloqueante de conferir resultado.taxa() do gerador no cenário concorrente (V10CargaInicialIT:129–152) permanece melhoria; a integridade desse teste não comprova a taxa efetiva.

## Pontos em aberto

| Questão | Estado | Resposta / pendência |
| --- | --- | --- |
| SLA de rajada / transação longa | RESPONDIDO no PRD | DP-07: recuperação ≤120 s; DP-08: demais transações ≤5 s e aplicação até 5 s após commit |
| Q1 updated_at | NÃO RESPONDIDO pelo legado | A PoC simula trigger e TIMESTAMP; confirmar fonte e precisão reais |
| Q2 DELETE físico | NÃO RESPONDIDO pelo legado | A PoC cobre soft delete; confirmar existência de exclusão física |
| Q3 jobs em massa | NÃO RESPONDIDO pelo legado | Rajada simulada de 50.000 linhas não estabelece volume/frequência reais |
| Q5 / Q-T3 overhead | NÃO RESPONDIDO pelo legado | ≤15% E ≤5 ms é apenas critério proposto DT-09 |
| Q6 pico real | NÃO RESPONDIDO pelo legado | 1.000 ev/s é estimativa; confirmar volume de produção |
| Q7 tabelas e ordem | PENDENTE pós-PoC | Tasso define o próximo recorte |
| Q8 rollback / sequence | PENDENTE pós-PoC | Tasso define plano da virada |
| Aceite do ADR | PENDENTE de decisão humana | O relatório não aceita nem move docs/adr-replocacao.md |

## Subsídios à decisão e recomendação humana

A tabela de critérios e os diagnósticos acima são evidências, não uma decisão de aceite. Critérios ausentes ou inválidos impedem concluir sucesso; reprovações exigem investigar as hipóteses e repetir a medição. Consistência tem prioridade sobre disponibilidade, conforme decisão do usuário; failover A→B é exceção.

<!-- RECOMENDACAO_HUMANA_PENDENTE -->

PENDENTE DE REDAÇÃO HUMANA: seguir, ajustar (indicando o quê) ou descartar. Registrar autor, data, evidências e riscos em `recomendacao-humana.md` e gerar novamente. O fechamento da PoC depende desse texto; o gerador nunca escolhe a recomendação.
