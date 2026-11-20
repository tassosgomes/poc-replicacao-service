# Relatório da PoC de replicação BDAD

Fonte: última execução em cada `<cenario>/meta.json`; históricos não são contados novamente. Valores INVALIDO/NAO_EXECUTADO ficam vazios. Os CSVs e logs são a evidência oficial; a aprovação do gerador do relatório não aprova o desenho arquitetural.

## Critérios do PRD

| Métrica | Alvo | Valor medido | Veredito | Evidência |
| --- | --- | --- | --- | --- |
| Latência em regime (C2) | 1.000 ev/s por 600 s; p95 ≤ 5 s; lag sem tendência de alta | C2/p95_ms: 2819; C2/tendencia_lag: {"amostras": 37, "crescimento_sustentado": false, "inclinacao_ms_s": 2.007899905168326, "janela_s": 180} | APROVADO | [C2](c2/meta.json) |
| Teto de vazão (C3) | ≥ 2.000 ev/s; degraus de 300 s | C3/teto_ev_s: 2000 | APROVADO | [C3](c3/meta.json) |
| Integridade após falha (C6, C7) | 0 divergências em ambos | C6/divergencias: 0; C7/divergencias: 0 | APROVADO | [C6](c6/meta.json), [C7](c7/meta.json) |
| Estabilidade de identidade | 0 UUIDs alterados em C1–C9; C10 não aplicável | C1/uuids_alterados: 0; C2/uuids_alterados: 0; C3/uuids_alterados: 0; C4/uuids_alterados: 0; C5/uuids_alterados: 0; C6/uuids_alterados: 0; C7/uuids_alterados: 0; C8/uuids_alterados: 0; C9/uuids_alterados: 0 | APROVADO | [C1](c1/meta.json), [C2](c2/meta.json), [C3](c3/meta.json), [C4](c4/meta.json), [C5](c5/meta.json), [C6](c6/meta.json), [C7](c7/meta.json), [C8](c8/meta.json), [C9](c9/meta.json) |
| Failover (C7) | ≤ 20 s | C7/failover_s: 25.84 | REPROVADO | [C7](c7/meta.json) |
| Correção da captura desligada (C8) | 100% das linhas afetadas corrigidas | 100.00% (55904/55904); C8/linhas_afetadas: 55904; C8/linhas_corrigidas: 55904 | APROVADO | [C8](c8/meta.json) |
| Custo no legado (C10) | Alvo efetivo: <=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente); segundo critério configurado, Q5/Q-T3 pendentes | C10/overhead_captura: {"absoluto_ms": 0.726, "percentual": 13.044259447804993} | APROVADO | [C10](c10/meta.json) |
| Rajada (C4) | ≤ 120 s após commit de 50.000 linhas; sem perda/divergência | C4/recuperacao_rajada_s: 17.894; C4/divergencias: 0; C4/pendentes_final: 0 | APROVADO | [C4](c4/meta.json) |
| Transação longa (C5) | 30 s aberta; p95 das demais ≤ 5 s; aplicação após commit ≤ 5 s | C5/p95_demais_durante_aberta_ms: 2884; C5/aplicacao_apos_commit_ms: 1837.3 | APROVADO | [C5](c5/meta.json) |
| Validade dos cenários | 100%; taxa ≥ 98% do alvo e diferença de relógios ≤ 100 ms | 10/10 válidos (100%) | APROVADO | Inventário abaixo |

Resultados parciais válidos aparecem no detalhamento; uma métrica composta exige todas as suas evidências. C10 usa o limite efetivo registrado pelo runner; o padrão proposto DT-09 é ≤15% E ≤5 ms, sem substituir o aceite do time do legado. O teto de C3 preserva degraus válidos anteriores a GERADOR_SATURADO.

## Cenários e lacunas

| Cenário | Execução | Estado | Motivo / limitação | Evidência |
| --- | --- | --- | --- | --- |
| C1 | 20261120T041312Z-REPBDAD | APROVADO |  | [meta](c1/meta.json) |
| C2 | 20261120T032036Z-REPBDAD | APROVADO |  | [meta](c2/meta.json) |
| C3 | 20261120T025051Z-REPBDAD-classico | APROVADO |  | [meta](c3/meta.json) |
| C4 | 20261120T034630Z-REPBDAD | APROVADO |  | [meta](c4/meta.json) |
| C5 | 20261120T035943Z-REPBDAD | APROVADO |  | [meta](c5/meta.json) |
| C6 | 20261120T045840Z-REPBDAD | APROVADO |  | [meta](c6/meta.json) |
| C7 | 20261120T052037Z-REPBDAD | REPROVADO |  | [meta](c7/meta.json) |
| C8 | 20261120T054117Z-REPBDAD | APROVADO |  | [meta](c8/meta.json) |
| C9 | 20261120T054721Z-REPBDAD | APROVADO |  | [meta](c9/meta.json) |
| C10 | 20261120T065625Z-REPBDAD | APROVADO |  | [meta](c10/meta.json) |

## Medições e diagnóstico por cenário

### C1

Janela UTC: 2026-11-20T04:13:13Z → 2026-11-20T04:26:20Z. Artefatos: `resultados/gg/c1/20261120T041312Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| contagens_iguais | {"legado": {"FONOGRAMA": 1123358, "OBRA_MUSICAL": 625703, "TITULAR": 262491}, "novo": {"FONOGRAMA": 1123358, "OBRA_MUSICAL": 625703, "TITULAR": 262491}} | iguais por tabela no escopo declarado | APROVADO |  |  |
| carga_inicial | {"log": "carga-inicial.log", "tabelas": {"FONOGRAMA": {"linhas": 1123358, "linhas_s": 3533.46, "segundos": 317.92}, "OBRA_MUSICAL": {"linhas": 625703, "linhas_s": 3622.85, "segundos": 172.71}, "TITULAR": {"linhas": 262491, "linhas_s": 3611.1, "segundos": 72.69}}, "tempo_total_s": 565.9} | linhas/s por tabela e tempo total; linhas copiadas == linhas do legado AS OF SCN | APROVADO |  |  |

### C2

Janela UTC: 2026-11-20T03:20:37Z → 2026-11-20T03:33:37Z. Artefatos: `resultados/gg/c2/20261120T032036Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| p95_ms | 2819 | <=5000 | APROVADO |  |  |
| tendencia_lag | {"amostras": 37, "crescimento_sustentado": false, "inclinacao_ms_s": 2.007899905168326, "janela_s": 180} | sem crescimento sustentado | APROVADO |  |  |

### C3

Janela UTC: 2026-11-20T02:50:51Z → 2026-11-20T03:17:21Z. Artefatos: `resultados/gg/c3/20261120T025051Z-REPBDAD-classico`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| teto_ev_s | 2000 | >=2000 em degrau valido | APROVADO |  |  |

### C4

Janela UTC: 2026-11-20T03:46:30Z → 2026-11-20T03:59:43Z. Artefatos: `resultados/gg/c4/20261120T034630Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| recuperacao_rajada_s | 17.894 | <=120 apos commit | APROVADO |  |  |

### C5

Janela UTC: 2026-11-20T03:59:43Z → 2026-11-20T04:12:57Z. Artefatos: `resultados/gg/c5/20261120T035943Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| p95_demais_durante_aberta_ms | 2884 | <=5000 | APROVADO |  |  |
| aplicacao_apos_commit_ms | 1837.3 | <=5000 | APROVADO |  |  |
| latencia_transacao_longa_ms | 31838 | reportada separadamente | SEM_CRITERIO |  |  |

### C6

Janela UTC: 2026-11-20T04:58:40Z → 2026-11-20T05:12:08Z. Artefatos: `resultados/gg/c6/20261120T045840Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| tempo_normalizar_s | 112.71 | lag_ms <= 5000 em janela completa de 5 s durante carga apos reinicio (tempo desde a queda) | APROVADO |  |  |
| tempo_normalizar_apos_reinicio_s | 52.55 | reportado (desde o retorno de docker start / REPBDAD de volta) | SEM_CRITERIO |  |  |
| estavel_apos_normalizar | true | reportado (baldes completos seguintes com lag <= 5 s ate o fim da carga) | SEM_CRITERIO |  |  |
| eventos_log_x_gerador | {"chaves_com_mais_no_log": 0, "chaves_com_menos_no_log": 0, "eventos_a_mais_total": 0, "eventos_faltantes_total": 0, "eventos_gerador": 600012, "linhas_log": 600012} | reportado (duplicatas de log apos kill sao registradas; integridade = estado final) | SEM_CRITERIO |  |  |

### C7

Janela UTC: 2026-11-20T05:20:37Z → 2026-11-20T05:34:07Z. Artefatos: `resultados/gg/c7/20261120T052037Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| failover_s | 25.84 | <=20 (referencia, ADAPTADO: sem segunda replica; queda do hub ate EXTBDAD e REPBDAD RUNNING e lag <= 5 s sob carga) | REPROVADO | Hipótese: lease, ciclo de claim e contenção/observação atrasam a tomada | Correlacionar leases e atividade atual; medir com relógio do banco sem afrouxar o limite |
| failover_restart_s | 25.81 | <=20 (referencia, ADAPTADO: sem segunda replica; queda do hub ate EXTBDAD e REPBDAD RUNNING e lag <= 5 s sob carga) | REPROVADO | Hipótese: lease, ciclo de claim e contenção/observação atrasam a tomada | Correlacionar leases e atividade atual; medir com relógio do banco sem afrouxar o limite |
| failover_kill_start_s | 25.84 | <=20 (referencia, ADAPTADO: sem segunda replica; queda do hub ate EXTBDAD e REPBDAD RUNNING e lag <= 5 s sob carga) | REPROVADO | Hipótese: lease, ciclo de claim e contenção/observação atrasam a tomada | Correlacionar leases e atividade atual; medir com relógio do banco sem afrouxar o limite |
| processos_running_restart_s | 9.92 | reportado (EXTBDAD e REPBDAD RUNNING, desde a queda) | SEM_CRITERIO |  |  |
| processos_running_kill_start_s | 10.33 | reportado (EXTBDAD e REPBDAD RUNNING, desde a queda) | SEM_CRITERIO |  |  |
| estavel_apos_restart | true | reportado (baldes completos seguintes com lag <= 5 s) | SEM_CRITERIO |  |  |
| estavel_apos_kill_start | true | reportado (baldes completos seguintes com lag <= 5 s) | SEM_CRITERIO |  |  |
| eventos_log_x_gerador | {"chaves_com_mais_no_log": 0, "chaves_com_menos_no_log": 0, "eventos_a_mais_total": 0, "eventos_faltantes_total": 0, "eventos_gerador": 600012, "linhas_log": 600012} | reportado (duplicatas de log apos kill sao registradas; integridade = estado final) | SEM_CRITERIO |  |  |

### C8

Janela UTC: 2026-11-20T05:41:17Z → 2026-11-20T05:47:03Z. Artefatos: `resultados/gg/c8/20261120T054117Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |
| linhas_afetadas | 55904 | >0; chaves divergentes antes de religar == chaves distintas do CSV do gerador (so OBRA_MUSICAL) | APROVADO |  |  |
| linhas_corrigidas | 55904 | 55904 | APROVADO |  |  |
| archive_necessario_preservado | {"antes_limpar": {"existem": 1, "faltando": 0, "lista_faltando": [], "necessarios": 1, "required_checkpoint_scn": 13890550, "status": "ENABLED"}, "depois_limpar": {"existem": 1, "faltando": 0, "lista_faltando": [], "necessarios": 1, "required_checkpoint_scn": 13890550, "status": "ENABLED"}, "depois_religar": {"existem": 1, "faltando": 0, "lista_faltando": [], "necessarios": 1, "required_checkpoint_scn": 13890550, "status": "ENABLED"}} | nenhum archive log necessario (next_change# > required_checkpoint_scn) apagado | APROVADO |  |  |
| recuperado_so_pelo_redo | true | true | APROVADO |  |  |
| tempo_ate_drenado_s | 42.39680194854736 | reportado (desde o retorno do START do Extract ate drenar) | SEM_CRITERIO |  |  |

### C9

Janela UTC: 2026-11-20T05:47:21Z → 2026-11-20T06:56:12Z. Artefatos: `resultados/gg/c9/20261120T054721Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado) em todas as execucoes | APROVADO |  |  |
| comparacao_parametros_replicat | comparacao.csv | vazao e p95 por modo e degrau (descritivo; nao ha alvo de superioridade) | SEM_CRITERIO |  |  |

Comparação de threads: SEM_CRITERIO (nenhum alvo de superioridade).

| Virtual threads | Grupo | Alvo ev/s | Taxa ev/s | p95 ms | Validade |
| --- | --- | --- | --- | --- | --- |
| oficial | c2 | 1000 | 1000.02 | 2836 | VALIDO |
| oficial | c3 | 2000 | 1999.99 | 3956 | VALIDO |
| sem-batchsql | c2 | 1000 | 999.97 | 3949 | VALIDO |
| sem-batchsql | c3 | 2000 | 1999.86 | 54805 | VALIDO |
| g1000 | c2 | 1000 | 999.92 | 2877 | VALIDO |
| g1000 | c3 | 2000 | 1999.87 | 2219 | VALIDO |

### C10

Janela UTC: 2026-11-20T06:56:26Z → 2026-11-20T07:56:09Z. Artefatos: `resultados/gg/c10/20261120T065625Z-REPBDAD`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| repeticoes_alternadas | 6 | 6 | APROVADO |  |  |
| taxa_valida_todos_modos | true | true | APROVADO |  |  |
| overhead_captura | {"absoluto_ms": 0.726, "percentual": 13.044259447804993} | <=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente) | APROVADO |  |  |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s) | APROVADO |  |  |

Médias, dispersão e custo das consultas (ms; transporte SSH incluído nas consultas):

| Modo | Medição |
| --- | --- |
| sem_metricas | {"captura_desligada": {"amostras_ms": [5.679, 5.526, 5.492], "desvio_padrao_ms": 0.09961090971039956, "max_ms": 5.679, "media_ms": 5.565666666666667, "min_ms": 5.492, "repeticoes": 3}, "captura_ligada": {"amostras_ms": [7.113, 6.103, 5.659], "desvio_padrao_ms": 0.74513443977133, "max_ms": 7.113, "media_ms": 6.291666666666667, "min_ms": 5.659, "repeticoes": 3}, "overhead_captura": {"absoluto_ms": 0.726, "percentual": 13.044259447804993}} |
| com_metricas | nao aplicavel (nao ha worker nem evento_replicacao no lab GG; nao ha consulta de metricas no legado) |
| variante_sem_supplemental | {"captura": "Extract parado e DELETE SCHEMATRANDATA (supplemental removido; FORCE LOGGING mantido)", "descritiva": true, "diretorio": "p07-desligada-sem-supplemental", "overhead_desligada_vs_sem_supplemental": {"absoluto_ms": -1.7143333333333333, "percentual": -23.5485347985348}, "overhead_ligada_vs_sem_supplemental": {"absoluto_ms": -0.9883333333333333, "percentual": -13.576007326007325}, "taxa": 999.97, "transacao_media_ms": 7.28} |

## Ambiente e carga dos servidores

Servidores compartilhados desenv-server e infra-server: 8 vCPU e 15 GiB por host no planejamento, outras aplicações ativas, rede interna. A PoC usa Oracle Free nos dois lados (DT-01); os números não transferem para PostgreSQL e não equivalem à produção. Este ambiente fornece um piso de capacidade e um teto de latência de rede para comparação, sem garantir esses valores em produção. O hash da reconciliação é probabilístico, não uma prova de igualdade sem risco de colisão. Limites efetivos observados abaixo; valores ausentes não são inferidos.

| Cenário | Container | CPUs | Memória (bytes) | Imagem | Digest / SHA-256 do jar |
| --- | --- | --- | --- | --- | --- |
| C1 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C1 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C1 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C2 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C2 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C2 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C3 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C3 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C3 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C4 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C4 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C4 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C5 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C5 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C5 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C6 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C6 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C6 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C7 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C7 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C7 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C8 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C8 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C8 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C9 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C9 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C9 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |
| C10 | gg-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C10 | gg-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C10 | gg-hub | 2.0 | 4294967296 | container-registry.oracle.com/goldengate/goldengate-oracle-free:latest | sha256:259252f26280e211dbd812955f9d2a72a2b7867165ae0178df34e3df0ffd094f |

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
