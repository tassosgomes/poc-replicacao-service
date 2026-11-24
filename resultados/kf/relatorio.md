# Relatório da PoC de replicação BDAD

Fonte: última execução em cada `<cenario>/meta.json`; históricos não são contados novamente. Valores INVALIDO/NAO_EXECUTADO ficam vazios. Os CSVs e logs são a evidência oficial; a aprovação do gerador do relatório não aprova o desenho arquitetural.

## Critérios do PRD

| Métrica | Alvo | Valor medido | Veredito | Evidência |
| --- | --- | --- | --- | --- |
| Latência em regime (C2) | 1.000 ev/s por 600 s; p95 ≤ 5 s; lag sem tendência de alta | C2/p95_ms: 6429; C2/tendencia_lag: {"amostras": 35, "crescimento_sustentado": false, "inclinacao_ms_s": 16.080499683287805, "janela_s": 180} | REPROVADO | [C2](c2/meta.json) |
| Teto de vazão (C3) | ≥ 2.000 ev/s; degraus de 300 s | C3/teto_ev_s: 2000 | APROVADO | [C3](c3/meta.json) |
| Integridade após falha (C6, C7) | 0 divergências em ambos | C6/divergencias: 0; C7/divergencias: 0 | APROVADO | [C6](c6/meta.json), [C7](c7/meta.json) |
| Estabilidade de identidade | 0 UUIDs alterados em C1–C9; C10 não aplicável |  | INVALIDO | [C1](c1/meta.json), [C2](c2/meta.json), [C3](c3/meta.json), [C4](c4/meta.json), [C5](c5/meta.json), [C6](c6/meta.json), [C7](c7/meta.json), [C8](c8/meta.json), [C9](c9/meta.json) |
| Failover (C7) | ≤ 20 s | C7/failover_s: 14.4 | APROVADO | [C7](c7/meta.json) |
| Correção da captura desligada (C8) | 100% das linhas afetadas corrigidas | 100.00% (55697/55697); C8/linhas_afetadas: 55697; C8/linhas_corrigidas: 55697 | APROVADO | [C8](c8/meta.json) |
| Custo no legado (C10) | Alvo efetivo: <=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente); segundo critério configurado, Q5/Q-T3 pendentes | C10/overhead_captura: {"absoluto_ms": 0.6109999999999998, "percentual": 10.309626254956548} | APROVADO | [C10](c10/meta.json) |
| Rajada (C4) | ≤ 120 s após commit de 50.000 linhas; sem perda/divergência | C4/recuperacao_rajada_s: 28.73; C4/divergencias: 0; C4/pendentes_final: 0 | APROVADO | [C4](c4/meta.json) |
| Transação longa (C5) | 30 s aberta; p95 das demais ≤ 5 s; aplicação após commit ≤ 5 s | C5/p95_demais_durante_aberta_ms: 5587; C5/aplicacao_apos_commit_ms: 4133.2 | REPROVADO | [C5](c5/meta.json) |
| Validade dos cenários | 100%; taxa ≥ 98% do alvo e diferença de relógios ≤ 100 ms |  | INVALIDO | Inventário abaixo |

Resultados parciais válidos aparecem no detalhamento; uma métrica composta exige todas as suas evidências. C10 usa o limite efetivo registrado pelo runner; o padrão proposto DT-09 é ≤15% E ≤5 ms, sem substituir o aceite do time do legado. O teto de C3 preserva degraus válidos anteriores a GERADOR_SATURADO.

## Cenários e lacunas

| Cenário | Execução | Estado | Motivo / limitação | Evidência |
| --- | --- | --- | --- | --- |
| C1 | 20261123T125636Z | APROVADO |  | [meta](c1/meta.json) |
| C2 | 20261123T131717Z | REPROVADO |  | [meta](c2/meta.json) |
| C3 | 20261123T135846Z-repeticao | APROVADO |  | [meta](c3/meta.json) |
| C4 | 20261123T142814Z | APROVADO |  | [meta](c4/meta.json) |
| C5 | 20261123T144330Z | REPROVADO |  | [meta](c5/meta.json) |
| C6 | 20261123T163933Z | APROVADO |  | [meta](c6/meta.json) |
| C7 | 20261123T165531Z | APROVADO |  | [meta](c7/meta.json) |
| C8 | 20261123T171131Z | APROVADO |  | [meta](c8/meta.json) |
| C9 | 20261123T194659Z | INVALIDO | ESCOPO_REDUZIDO: exige degraus de 300 s; ESCOPO_REDUZIDO: exige regime 1000 ev/s por 600 s; EVIDENCIA_INSUFICIENTE: taxa efetiva ausente | [meta](c9/meta.json) |
| C10 | 20261123T172049Z | APROVADO |  | [meta](c10/meta.json) |

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

Janela UTC: 2026-11-23T16:39:41Z → 2026-11-23T16:55:31Z. Artefatos: `resultados/kf/c6/20261123T163933Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| tempo_normalizar_s | 147.8 | lag <= 5000 ms em janela completa de 5 s durante carga apos o reinicio (tempo desde a queda) | APROVADO |  |  |
| tempo_normalizar_apos_reinicio_s | 87.55 | reportado (desde o docker run do worker substituto) | SEM_CRITERIO |  |  |
| estavel_apos_normalizar | false | reportado (baldes completos seguintes com lag <= 5 s ate o fim da carga) | SEM_CRITERIO |  |  |
| tempo_normalizar_relativo_ao_regime_s | 92.8 | extra descritivo: lag <= max(5 s, maior lag do regime antes da queda) | SEM_CRITERIO |  |  |
| eventos_log_x_gerador | {"duplicados_entregues": 0, "eventos_a_mais_total": 0, "eventos_faltantes_total": 17, "eventos_gerador": 599827, "eventos_no_topico": 599827, "eventos_worker_csv_bruto": 599810, "eventos_worker_csv_dedup": 599810} | reportado (re-entregas apos a queda sao registradas; integridade = estado final) | SEM_CRITERIO |  |  |

### C7

Janela UTC: 2026-11-23T16:55:39Z → 2026-11-23T17:11:31Z. Artefatos: `resultados/kf/c7/20261123T165531Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| divisao_24_24 | divisao: kf-worker-1,24 kf-worker-2,24 | kf-worker-1 e kf-worker-2 com 24 leases cada antes da queda | APROVADO |  |  |
| failover_s | 14.4 | <=20 (queda do kf-worker-1 ate o kf-worker-2 ter 48 leases validos e nova marca all 48 owned partitions active; relogio do host do banco) | APROVADO |  |  |
| sobrevivente_48 | {"leases": 48, "marcas": 2} | 48 leases validos e marca nova no log do sobrevivente | APROVADO |  |  |
| estavel_apos_tomada | false | reportado (baldes completos seguintes com lag <= 5 s) | SEM_CRITERIO |  |  |
| tempo_normalizar_desde_queda_s | 118.81 | reportado (primeiro balde de 5 s completo com lag <= 5 s) | SEM_CRITERIO |  |  |
| tempo_normalizar_relativo_ao_regime_s | 8.8 | extra descritivo: lag <= max(5 s, maior lag do regime antes da queda) | SEM_CRITERIO |  |  |
| eventos_log_x_gerador | {"duplicados_entregues": 0, "eventos_a_mais_total": 0, "eventos_faltantes_total": 0, "eventos_gerador": 599741, "eventos_no_topico": 599741, "eventos_worker_csv_bruto": 599741, "eventos_worker_csv_dedup": 599741} | reportado (re-entregas apos a queda sao registradas; integridade = estado final) | SEM_CRITERIO |  |  |

### C8

Janela UTC: 2026-11-23T17:11:39Z → 2026-11-23T17:20:49Z. Artefatos: `resultados/kf/c8/20261123T171131Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |
| linhas_afetadas | 55697 | >0; chaves divergentes antes de religar == chaves distintas do CSV do gerador (so OBRA_MUSICAL) | APROVADO |  |  |
| linhas_corrigidas | 55697 | 55697 | APROVADO |  |  |
| archive_necessario_preservado | {"antes_limpar": {"existem": 4, "faltando": 0, "lista_faltando": [], "necessarios": 4, "scn_necessario": 10068146}, "depois_limpar": {"existem": 4, "faltando": 0, "lista_faltando": [], "necessarios": 4, "scn_necessario": 10068146}, "depois_religar": {"existem": 0, "faltando": 0, "lista_faltando": [], "necessarios": 0, "scn_necessario": 10127692}} | nenhum archive log necessario (next_change# > scn do offset do conector) apagado | APROVADO |  |  |
| recuperado_so_pelo_redo | true | true | APROVADO |  |  |
| tempo_ate_drenado_s | 49.907108783721924 | reportado (desde o retorno do resume do conector ate drenar) | SEM_CRITERIO |  |  |

### C9

INVALIDO: ESCOPO_REDUZIDO: exige degraus de 300 s; ESCOPO_REDUZIDO: exige regime 1000 ev/s por 600 s; EVIDENCIA_INSUFICIENTE: taxa efetiva ausente. Valores não publicados.

### C10

Janela UTC: 2026-11-23T17:20:55Z → 2026-11-23T19:13:23Z. Artefatos: `resultados/kf/c10/20261123T172049Z`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| repeticoes_alternadas | 8 | 8 | APROVADO |  |  |
| taxa_valida_todos_modos | true | true | APROVADO |  |  |
| overhead_captura | {"absoluto_ms": 0.6109999999999998, "percentual": 10.309626254956548} | <=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente) | APROVADO |  |  |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |  |  |

Médias, dispersão e custo das consultas (ms; transporte SSH incluído nas consultas):

| Modo | Medição |
| --- | --- |
| sem_metricas | {"captura_desligada": {"amostras_ms": [5.411, 5.893, 5.541, 6.861], "desvio_padrao_ms": 0.6554293758038822, "max_ms": 6.861, "media_ms": 5.9265, "min_ms": 5.411, "repeticoes": 4}, "captura_ligada": {"amostras_ms": [6.88, 5.202, 7.699, 6.369], "desvio_padrao_ms": 1.0453613410363582, "max_ms": 7.699, "media_ms": 6.5375, "min_ms": 5.202, "repeticoes": 4}, "overhead_captura": {"absoluto_ms": 0.6109999999999998, "percentual": 10.309626254956548}} |
| com_metricas | nao aplicavel (nao ha evento_replicacao nem consulta de metricas no legado do lab Kafka) |
| pares | [{"desligada_ms": 5.411, "diferenca_ms": 1.4690000000000003, "diferenca_pct": 27.148401404546302, "ligada_ms": 6.88, "ordem": "LD", "repeticao": 1}, {"desligada_ms": 5.893, "diferenca_ms": -0.6909999999999998, "diferenca_pct": -11.72577634481588, "ligada_ms": 5.202, "ordem": "DL", "repeticao": 2}, {"desligada_ms": 5.541, "diferenca_ms": 2.1579999999999995, "diferenca_pct": 38.9460386211875, "ligada_ms": 7.699, "ordem": "LD", "repeticao": 3}, {"desligada_ms": 6.861, "diferenca_ms": -0.492, "diferenca_pct": -7.170966331438566, "ligada_ms": 6.369, "ordem": "DL", "repeticao": 4}] |
| diferenca_media_dos_pares_ms | 0.611 |
| tendencia_temporal | {"inclinacao_ms_por_fase_todas": 0.060880952380952404, "media_primeira_metade_ms": 5.8465, "media_segunda_metade_ms": 6.6175, "sequencia": [[1, "L", 6.88], [2, "D", 5.411], [3, "D", 5.893], [4, "L", 5.202], [5, "L", 7.699], [6, "D", 5.541], [7, "D", 6.861], [8, "L", 6.369]]} |

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
| C6 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C6 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C6 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C6 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C6 | kf-worker-2 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C6 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C7 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C7 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C7 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C7 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C7 | kf-worker-2 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C7 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C8 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C8 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C8 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C8 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C8 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C9 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C9 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C9 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C9 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C9 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |
| C10 | kf-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C10 | kf-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C10 | kf-kafka | 2.0 | 2147483648 | apache/kafka:4.3.1 | sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837 |
| C10 | kf-connect | 2.0 | 2147483648 | quay.io/debezium/connect:3.7.1.Final | sha256:1589366704e1e016bad42b15fbdb364ba48780c61d0094197ca8632cb1271539 |
| C10 | kf-worker-1 | 2.0 | 1073741824 | kf-worker:7a76e8c7fd9597dc | sha256:3ec63411ec957c0e4d77d0b61eb9eb05f226903610473c4dc5761589185269c6 |

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
