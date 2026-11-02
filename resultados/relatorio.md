# Relatório da PoC de replicação BDAD

Fonte: última execução em cada `<cenario>/meta.json`; históricos não são contados novamente. Valores INVALIDO/NAO_EXECUTADO ficam vazios. Os CSVs e logs são a evidência oficial; a aprovação do gerador do relatório não aprova o desenho arquitetural.

## Critérios do PRD

| Métrica | Alvo | Valor medido | Veredito | Evidência |
| --- | --- | --- | --- | --- |
| Latência em regime (C2) | 1.000 ev/s por 600 s; p95 ≤ 5 s; lag sem tendência de alta |  | INVALIDO | [C2](c2/meta.json) |
| Teto de vazão (C3) | ≥ 2.000 ev/s; degraus de 300 s |  | INVALIDO | [C3](c3/meta.json) |
| Integridade após falha (C6, C7) | 0 divergências em ambos |  | INVALIDO | [C6](c6/meta.json), [C7](c7/meta.json) |
| Estabilidade de identidade | 0 UUIDs alterados em C1–C9; C10 não aplicável |  | INVALIDO | [C1](c1/meta.json), [C2](c2/meta.json), [C3](c3/meta.json), [C4](c4/meta.json), [C5](c5/meta.json), [C6](c6/meta.json), [C7](c7/meta.json), [C8](c8/meta.json), [C9](c9/meta.json) |
| Failover (C7) | ≤ 20 s |  | INVALIDO | [C7](c7/meta.json) |
| Correção da captura desligada (C8) | 100% das linhas afetadas corrigidas | 100.00% (55629/55629); C8/linhas_afetadas: 55629; C8/linhas_corrigidas: 55629 | APROVADO | [C8](c8/meta.json) |
| Custo no legado (C10) | Alvo efetivo: <=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente); segundo critério configurado, Q5/Q-T3 pendentes | C10/overhead_captura: {"absoluto_ms": 0.958333333333333, "percentual": 19.998608792431824} | REPROVADO | [C10](c10/meta.json) |
| Rajada (C4) | ≤ 120 s após commit de 50.000 linhas; sem perda/divergência |  | INVALIDO | [C4](c4/meta.json) |
| Transação longa (C5) | 30 s aberta; p95 das demais ≤ 5 s; aplicação após commit ≤ 5 s |  | INVALIDO | [C5](c5/meta.json) |
| Validade dos cenários | 100%; taxa ≥ 98% do alvo e diferença de relógios ≤ 100 ms |  | INVALIDO | Inventário abaixo |

Resultados parciais válidos aparecem no detalhamento; uma métrica composta exige todas as suas evidências. C10 usa o limite efetivo registrado pelo runner; o padrão proposto DT-09 é ≤15% E ≤5 ms, sem substituir o aceite do time do legado. O teto de C3 preserva degraus válidos anteriores a GERADOR_SATURADO.

## Cenários e lacunas

| Cenário | Execução | Estado | Motivo / limitação | Evidência |
| --- | --- | --- | --- | --- |
| C1 | 20261101T193909-3611504-1be720523ec08 | APROVADO |  | [meta](c1/meta.json) |
| C2 | 20261101T194628-3628415-1bee2c7618628 | INVALIDO | TAXA | [meta](c2/meta.json) |
| C3 | 20261101T200134-3662421-1bfca4a304abd | INVALIDO | TAXA | [meta](c3/meta.json) |
| C4 | 20261101T201447-3691421-1c095b6c31422 | INVALIDO | TAXA | [meta](c4/meta.json) |
| C5 | 20261101T202748-3719833-1c15d3a5811a7 | INVALIDO | TAXA | [meta](c5/meta.json) |
| C6 | 20261101T204055-3749091-1c226fe424924 | INVALIDO | TAXA | [meta](c6/meta.json) |
| C7 | 20261101T205405-3777899-1c2f153ff3308 | INVALIDO | TAXA | [meta](c7/meta.json) |
| C8 | 20261101T210742-3807570-1c3c1eef255a7 | APROVADO |  | [meta](c8/meta.json) |
| C9 | 20261101T211604-3826443-1c442a68956c0 | INVALIDO | TAXA | [meta](c9/meta.json) |
| C10 | 20261101T230714-4065994-1caea959101f2 | REPROVADO |  | [meta](c10/meta.json) |

## Medições e diagnóstico por cenário

### C1

Janela UTC: 2026-11-01T19:39:09.343981Z → 2026-11-01T19:45:38.294845Z. Artefatos: `resultados/c1/20261101T193909-3611504-1be720523ec08`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 | APROVADO |  |  |
| contagens_iguais | {"legado": {"FONOGRAMA": 1060490, "OBRA_MUSICAL": 561993, "TITULAR": 231712}, "novo": {"FONOGRAMA": 1060490, "OBRA_MUSICAL": 561993, "TITULAR": 231712}} | iguais por tabela no escopo declarado | APROVADO |  |  |
| carga_inicial | {"log": "poc-carga-inicial-20261101t193909-3611504-1be720523ec08-1.log", "tabelas": {"FONOGRAMA": {"linhas": 1060490, "linhas_s": 17446.95}, "OBRA_MUSICAL": {"linhas": 561993, "linhas_s": 17735.7}, "TITULAR": {"linhas": 231712, "linhas_s": 13265.67}}, "tempo_total_s": 109.942} | linhas/s por tabela e tempo total | APROVADO |  |  |

### C2

INVALIDO: TAXA. Valores não publicados.

### C3

INVALIDO: TAXA. Valores não publicados.

### C4

INVALIDO: TAXA. Valores não publicados.

### C5

INVALIDO: TAXA. Valores não publicados.

### C6

INVALIDO: TAXA. Valores não publicados.

### C7

INVALIDO: TAXA. Valores não publicados.

### C8

Janela UTC: 2026-11-01T21:07:42.550467Z → 2026-11-01T21:16:04.298334Z. Artefatos: `resultados/c8/20261101T210742-3807570-1c3c1eef255a7`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| divergencias | 0 | 0 | APROVADO |  |  |
| uuids_alterados | 0 | 0 | APROVADO |  |  |
| pendentes_final | 0 | 0 | APROVADO |  |  |
| linhas_afetadas | 55629 | >0; chaves distintas do log conferem com verificacao completa antes do delta | APROVADO |  |  |
| linhas_corrigidas | 55629 | 55629 | APROVADO |  |  |

### C9

INVALIDO: TAXA. Valores não publicados.

### C10

Janela UTC: 2026-11-01T23:07:14.052689Z → 2026-11-02T01:09:33.398045Z. Artefatos: `resultados/c10/20261101T230714-4065994-1caea959101f2`; caminhos internos relativos à execução.

| Critério | Valor | Alvo | Veredito | Causa provável (a investigar) | Ajuste proposto |
| --- | --- | --- | --- | --- | --- |
| repeticoes_alternadas | 12 | 12 | APROVADO |  |  |
| taxa_valida_todos_modos | true | true | APROVADO |  |  |
| overhead_captura | {"absoluto_ms": 0.958333333333333, "percentual": 19.998608792431824} | <=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente) | REPROVADO | Hipótese: custo de trigger/índice e contenção do legado | Conferir dispersão e carga, otimizar captura e repetir pares; confirmar Q-T3 com o legado |

Médias, dispersão e custo das consultas (ms; transporte SSH incluído nas consultas):

| Modo | Medição |
| --- | --- |
| sem_metricas | {"captura_desligada": {"amostras_ms": [4.888, 4.723, 4.765], "desvio_padrao_ms": 0.08574963556773876, "max_ms": 4.888, "media_ms": 4.792, "min_ms": 4.723, "repeticoes": 3}, "captura_ligada": {"amostras_ms": [5.726, 5.778, 5.747], "desvio_padrao_ms": 0.026159765544311032, "max_ms": 5.778, "media_ms": 5.750333333333333, "min_ms": 5.726, "repeticoes": 3}, "overhead_captura": {"absoluto_ms": 0.958333333333333, "percentual": 19.998608792431824}} |
| com_metricas | {"captura_desligada": {"amostras_ms": [4.51, 4.56, 5.705], "desvio_padrao_ms": 0.675962277053979, "max_ms": 5.705, "media_ms": 4.925, "min_ms": 4.51, "repeticoes": 3}, "captura_ligada": {"amostras_ms": [5.757, 5.883, 5.734], "desvio_padrao_ms": 0.08021429631514164, "max_ms": 5.883, "media_ms": 5.791333333333333, "min_ms": 5.734, "repeticoes": 3}, "overhead_captura": {"absoluto_ms": 0.8663333333333334, "percentual": 17.590524534686974}} |
| custo_metricas | {"captura_desligada": {"absoluto_ms": 0.133, "percentual": 2.775459098497496}, "captura_ligada": {"absoluto_ms": 0.04100000000000037, "percentual": 0.7130021448032063}} |

## Ambiente e carga dos servidores

Servidores compartilhados desenv-server e infra-server: 8 vCPU e 15 GiB por host no planejamento, outras aplicações ativas, rede interna. A PoC usa Oracle Free nos dois lados (DT-01); os números não transferem para PostgreSQL e não equivalem à produção. Este ambiente fornece um piso de capacidade e um teto de latência de rede para comparação, sem garantir esses valores em produção. O hash da reconciliação é probabilístico, não uma prova de igualdade sem risco de colisão. Limites efetivos observados abaixo; valores ausentes não são inferidos.

| Cenário | Container | CPUs | Memória (bytes) | Imagem | Digest / SHA-256 do jar |
| --- | --- | --- | --- | --- | --- |
| C1 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C1 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C1 | poc-carga-inicial-20261101t193909-3611504-1be720523ec08-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C1 | poc-worker-20261101t193909-3611504-1be720523ec08-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C1 | poc-reconciliacao-20261101t193909-3611504-1be720523ec08-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C1 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C2 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C2 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C2 | poc-worker-20261101t194628-3628415-1bee2c7618628-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C2 | poc-gerador-20261101t194628-3628415-1bee2c7618628-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C2 | poc-reconciliacao-20261101t194628-3628415-1bee2c7618628-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C2 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C3 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C3 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C3 | poc-worker-20261101t200134-3662421-1bfca4a304abd-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C3 | poc-gerador-20261101t200134-3662421-1bfca4a304abd-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C3 | poc-gerador-20261101t200134-3662421-1bfca4a304abd-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C3 | poc-reconciliacao-20261101t200134-3662421-1bfca4a304abd-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C3 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C4 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C4 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C4 | poc-worker-20261101t201447-3691421-1c095b6c31422-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C4 | poc-gerador-20261101t201447-3691421-1c095b6c31422-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C4 | poc-gerador-20261101t201447-3691421-1c095b6c31422-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C4 | poc-reconciliacao-20261101t201447-3691421-1c095b6c31422-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C4 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C5 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C5 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C5 | poc-worker-20261101t202748-3719833-1c15d3a5811a7-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C5 | poc-gerador-20261101t202748-3719833-1c15d3a5811a7-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C5 | poc-gerador-20261101t202748-3719833-1c15d3a5811a7-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C5 | poc-reconciliacao-20261101t202748-3719833-1c15d3a5811a7-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C5 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C6 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C6 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C6 | poc-worker-20261101t204055-3749091-1c226fe424924-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C6 | poc-gerador-20261101t204055-3749091-1c226fe424924-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C6 | poc-worker-20261101t204055-3749091-1c226fe424924-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C6 | poc-reconciliacao-20261101t204055-3749091-1c226fe424924-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C6 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C7 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C7 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C7 | poc-worker-20261101t205405-3777899-1c2f153ff3308-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C7 | poc-gerador-20261101t205405-3777899-1c2f153ff3308-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C7 | poc-worker-20261101t205405-3777899-1c2f153ff3308-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C7 | poc-reconciliacao-20261101t205405-3777899-1c2f153ff3308-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C7 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C8 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C8 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C8 | poc-worker-20261101t210742-3807570-1c3c1eef255a7-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C8 | poc-gerador-20261101t210742-3807570-1c3c1eef255a7-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C8 | poc-reconciliacao-20261101t210742-3807570-1c3c1eef255a7-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C8 | poc-reconciliacao-20261101t210742-3807570-1c3c1eef255a7-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C8 | poc-worker-20261101t210742-3807570-1c3c1eef255a7-4 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C8 | poc-reconciliacao-20261101t210742-3807570-1c3c1eef255a7-5 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C8 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C9 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C9 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C9 | poc-worker-20261101t211604-3826443-1c442a68956c0-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C9 | poc-worker-20261101t211604-3826443-1c442a68956c0-4 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-5 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-6 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-7 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C9 | poc-reconciliacao-20261101t211604-3826443-1c442a68956c0-8 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:adcbff652066a498d3055d66c40f59d3093839e85d68c19989d4f92d0cfc473c |
| C9 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |
| C10 | poc-legado | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C10 | poc-novo | 2.0 | 3221225472 | gvenzl/oracle-free:23-slim-faststart | sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093 |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-0 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-1 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-2 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-3 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-4 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-5 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-6 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-7 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-8 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-9 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-10 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | poc-gerador-20261101t230714-4065994-1caea959101f2-11 | 2 | 1073741824 | poc-cenarios:9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f | sha256:fc0a6e09436c99e805be1eb49b2b0435022de0957ff5dc4412307e7f3901a7fe |
| C10 | worker/gerador/reconciliação | 2 | 1073741824 | eclipse-temurin:25-jre | 9ad33bacc7bfbad89d544e3770b24551936ef28ec384364cc5bcbcf31c021c9f |

CPU 0/memória 0 no inventário Docker significam sem limite configurado. A carga abaixo é load average de 1 minuto do host, não percentual de CPU. O meta preserva inventários antes/durante/depois, identidade, estado e reinícios dos serviços existentes. Cada execução também arquiva recursos.csv com CPU, memória e sessões dos bancos quando coletados; a ausência de amostras não é tratada como carga zero. A carga ambiental é mostrada mesmo para um cenário inválido, sem validar suas medições de resultado.

| Cenário | Host | Etapa | Amostras | Load médio | Load máximo | Fonte |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | desenv-server | antes | 1 | 0.740 | 0.740 | [c1/meta.json](c1/meta.json) |
| C1 | infra-server | antes | 1 | 0.580 | 0.580 | [c1/meta.json](c1/meta.json) |
| C1 | desenv-server | durante | 11 | 1.367 | 2.610 | [c1/meta.json](c1/meta.json) |
| C1 | infra-server | durante | 11 | 1.898 | 3.340 | [c1/meta.json](c1/meta.json) |
| C1 | desenv-server | depois | 2 | 2.520 | 2.890 | [c1/meta.json](c1/meta.json) |
| C1 | infra-server | depois | 2 | 1.880 | 2.030 | [c1/meta.json](c1/meta.json) |
| C2 | desenv-server | antes | 1 | 1.690 | 1.690 | [c2/meta.json](c2/meta.json) |
| C2 | infra-server | antes | 1 | 0.860 | 0.860 | [c2/meta.json](c2/meta.json) |
| C2 | desenv-server | durante | 26 | 5.866 | 14.160 | [c2/meta.json](c2/meta.json) |
| C2 | infra-server | durante | 26 | 2.187 | 5.440 | [c2/meta.json](c2/meta.json) |
| C2 | desenv-server | depois | 2 | 3.650 | 3.970 | [c2/meta.json](c2/meta.json) |
| C2 | infra-server | depois | 2 | 3.040 | 3.060 | [c2/meta.json](c2/meta.json) |
| C3 | desenv-server | antes | 1 | 2.890 | 2.890 | [c3/meta.json](c3/meta.json) |
| C3 | infra-server | antes | 1 | 3.020 | 3.020 | [c3/meta.json](c3/meta.json) |
| C3 | desenv-server | durante | 21 | 3.580 | 7.340 | [c3/meta.json](c3/meta.json) |
| C3 | infra-server | durante | 21 | 2.995 | 6.660 | [c3/meta.json](c3/meta.json) |
| C3 | desenv-server | depois | 2 | 2.010 | 2.220 | [c3/meta.json](c3/meta.json) |
| C3 | infra-server | depois | 2 | 2.035 | 2.290 | [c3/meta.json](c3/meta.json) |
| C4 | desenv-server | antes | 1 | 1.880 | 1.880 | [c4/meta.json](c4/meta.json) |
| C4 | infra-server | antes | 1 | 1.590 | 1.590 | [c4/meta.json](c4/meta.json) |
| C4 | desenv-server | durante | 23 | 6.082 | 12.910 | [c4/meta.json](c4/meta.json) |
| C4 | infra-server | durante | 23 | 2.936 | 5.540 | [c4/meta.json](c4/meta.json) |
| C4 | desenv-server | depois | 2 | 2.275 | 2.500 | [c4/meta.json](c4/meta.json) |
| C4 | infra-server | depois | 2 | 2.590 | 3.020 | [c4/meta.json](c4/meta.json) |
| C5 | desenv-server | antes | 1 | 1.900 | 1.900 | [c5/meta.json](c5/meta.json) |
| C5 | infra-server | antes | 1 | 1.910 | 1.910 | [c5/meta.json](c5/meta.json) |
| C5 | desenv-server | durante | 22 | 5.256 | 9.720 | [c5/meta.json](c5/meta.json) |
| C5 | infra-server | durante | 22 | 2.578 | 4.750 | [c5/meta.json](c5/meta.json) |
| C5 | desenv-server | depois | 2 | 4.845 | 5.210 | [c5/meta.json](c5/meta.json) |
| C5 | infra-server | depois | 2 | 2.640 | 2.990 | [c5/meta.json](c5/meta.json) |
| C6 | desenv-server | antes | 1 | 4.120 | 4.120 | [c6/meta.json](c6/meta.json) |
| C6 | infra-server | antes | 1 | 1.940 | 1.940 | [c6/meta.json](c6/meta.json) |
| C6 | desenv-server | durante | 23 | 7.889 | 15.840 | [c6/meta.json](c6/meta.json) |
| C6 | infra-server | durante | 23 | 2.656 | 4.700 | [c6/meta.json](c6/meta.json) |
| C6 | desenv-server | depois | 2 | 5.200 | 5.530 | [c6/meta.json](c6/meta.json) |
| C6 | infra-server | depois | 2 | 2.335 | 2.580 | [c6/meta.json](c6/meta.json) |
| C7 | desenv-server | antes | 1 | 4.420 | 4.420 | [c7/meta.json](c7/meta.json) |
| C7 | infra-server | antes | 1 | 2.080 | 2.080 | [c7/meta.json](c7/meta.json) |
| C7 | desenv-server | durante | 22 | 9.490 | 15.920 | [c7/meta.json](c7/meta.json) |
| C7 | infra-server | durante | 22 | 2.486 | 4.370 | [c7/meta.json](c7/meta.json) |
| C7 | desenv-server | depois | 2 | 4.645 | 4.860 | [c7/meta.json](c7/meta.json) |
| C7 | infra-server | depois | 2 | 1.900 | 2.040 | [c7/meta.json](c7/meta.json) |
| C8 | desenv-server | antes | 1 | 3.830 | 3.830 | [c8/meta.json](c8/meta.json) |
| C8 | infra-server | antes | 1 | 1.810 | 1.810 | [c8/meta.json](c8/meta.json) |
| C8 | desenv-server | durante | 16 | 2.845 | 4.520 | [c8/meta.json](c8/meta.json) |
| C8 | infra-server | durante | 16 | 1.738 | 3.140 | [c8/meta.json](c8/meta.json) |
| C8 | desenv-server | depois | 2 | 3.295 | 3.770 | [c8/meta.json](c8/meta.json) |
| C8 | infra-server | depois | 2 | 2.010 | 2.180 | [c8/meta.json](c8/meta.json) |
| C9 | desenv-server | antes | 1 | 2.590 | 2.590 | [c9/meta.json](c9/meta.json) |
| C9 | infra-server | antes | 1 | 1.770 | 1.770 | [c9/meta.json](c9/meta.json) |
| C9 | desenv-server | durante | 80 | 5.329 | 12.560 | [c9/meta.json](c9/meta.json) |
| C9 | infra-server | durante | 80 | 2.944 | 8.290 | [c9/meta.json](c9/meta.json) |
| C9 | desenv-server | depois | 2 | 2.705 | 3.010 | [c9/meta.json](c9/meta.json) |
| C9 | infra-server | depois | 2 | 2.325 | 2.560 | [c9/meta.json](c9/meta.json) |
| C10 | desenv-server | antes | 1 | 0.370 | 0.370 | [c10/meta.json](c10/meta.json) |
| C10 | infra-server | antes | 1 | 1.600 | 1.600 | [c10/meta.json](c10/meta.json) |
| C10 | desenv-server | durante | 260 | 2.600 | 4.530 | [c10/meta.json](c10/meta.json) |
| C10 | infra-server | durante | 260 | 0.712 | 5.210 | [c10/meta.json](c10/meta.json) |
| C10 | desenv-server | depois | 2 | 3.045 | 3.100 | [c10/meta.json](c10/meta.json) |
| C10 | infra-server | depois | 2 | 1.035 | 1.120 | [c10/meta.json](c10/meta.json) |

Recursos dos containers: CPU Docker pode chegar a 200% para o limite de 2 CPUs. A marca de proximidade (pico ≥190%) é uma hipótese de saturação a correlacionar com cada degrau, não atribuição causal. Memória é a última amostra; sessões são o máximo observado. As séries e os horários completos ficam nos CSVs.

| Cenário | Container | Amostras | CPU média % | CPU máxima % | Memória | Sessões máx. | Indício | Fonte |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C1 | poc-legado | 6 | 65.14 | 91.06 | 2.21GiB / 3GiB | 8 |  | [CSV](c1/20261101T193909-3611504-1be720523ec08/recursos.csv) |
| C1 | poc-novo | 6 | 51.70 | 115.85 | 2.161GiB / 3GiB | 1 |  | [CSV](c1/20261101T193909-3611504-1be720523ec08/recursos.csv) |
| C1 | poc-worker-20261101t193909-3611504-1be720523ec08-2 | 6 | 27.74 | 69.08 | 170.2MiB / 1GiB | 0 |  | [CSV](c1/20261101T193909-3611504-1be720523ec08/recursos.csv) |
| C2 | poc-legado | 25 | 164.96 | 209.45 | 2.187GiB / 3GiB | 23 | próximo de 2 CPUs em ao menos uma amostra | [CSV](c2/20261101T194628-3628415-1bee2c7618628/recursos.csv) |
| C2 | poc-novo | 25 | 66.80 | 130.23 | 2.186GiB / 3GiB | 4 |  | [CSV](c2/20261101T194628-3628415-1bee2c7618628/recursos.csv) |
| C2 | poc-worker-20261101t194628-3628415-1bee2c7618628-0 | 25 | 60.57 | 109.28 | 623.8MiB / 1GiB | 0 |  | [CSV](c2/20261101T194628-3628415-1bee2c7618628/recursos.csv) |
| C2 | poc-gerador-20261101t194628-3628415-1bee2c7618628-2 | 16 | 15.27 | 83.00 | 322.5MiB / 1GiB | 0 |  | [CSV](c2/20261101T194628-3628415-1bee2c7618628/recursos.csv) |
| C2 | poc-reconciliacao-20261101t194628-3628415-1bee2c7618628-3 | 1 | 0.01 | 0.01 | 63.73MiB / 1GiB | 0 |  | [CSV](c2/20261101T194628-3628415-1bee2c7618628/recursos.csv) |
| C3 | poc-legado | 22 | 160.34 | 209.41 | 2.193GiB / 3GiB | 40 | próximo de 2 CPUs em ao menos uma amostra | [CSV](c3/20261101T200134-3662421-1bfca4a304abd/recursos.csv) |
| C3 | poc-novo | 22 | 69.78 | 121.06 | 2.172GiB / 3GiB | 7 |  | [CSV](c3/20261101T200134-3662421-1bfca4a304abd/recursos.csv) |
| C3 | poc-worker-20261101t200134-3662421-1bfca4a304abd-0 | 22 | 65.63 | 164.66 | 628MiB / 1GiB | 0 |  | [CSV](c3/20261101T200134-3662421-1bfca4a304abd/recursos.csv) |
| C3 | poc-gerador-20261101t200134-3662421-1bfca4a304abd-1 | 8 | 12.40 | 25.83 | 170.4MiB / 1GiB | 0 |  | [CSV](c3/20261101T200134-3662421-1bfca4a304abd/recursos.csv) |
| C3 | poc-gerador-20261101t200134-3662421-1bfca4a304abd-2 | 8 | 11.15 | 12.14 | 222.4MiB / 1GiB | 0 |  | [CSV](c3/20261101T200134-3662421-1bfca4a304abd/recursos.csv) |
| C3 | poc-reconciliacao-20261101t200134-3662421-1bfca4a304abd-3 | 1 | 0.03 | 0.03 | 68.17MiB / 1GiB | 0 |  | [CSV](c3/20261101T200134-3662421-1bfca4a304abd/recursos.csv) |
| C4 | poc-legado | 21 | 172.59 | 212.62 | 2.214GiB / 3GiB | 34 | próximo de 2 CPUs em ao menos uma amostra | [CSV](c4/20261101T201447-3691421-1c095b6c31422/recursos.csv) |
| C4 | poc-novo | 21 | 79.29 | 120.58 | 2.197GiB / 3GiB | 5 |  | [CSV](c4/20261101T201447-3691421-1c095b6c31422/recursos.csv) |
| C4 | poc-worker-20261101t201447-3691421-1c095b6c31422-0 | 21 | 59.52 | 130.22 | 623.2MiB / 1GiB | 0 |  | [CSV](c4/20261101T201447-3691421-1c095b6c31422/recursos.csv) |
| C4 | poc-gerador-20261101t201447-3691421-1c095b6c31422-1 | 16 | 11.75 | 43.40 | 319.4MiB / 1GiB | 0 |  | [CSV](c4/20261101T201447-3691421-1c095b6c31422/recursos.csv) |
| C4 | poc-gerador-20261101t201447-3691421-1c095b6c31422-2 | 1 | 0.02 | 0.02 | 67.71MiB / 1GiB | 0 |  | [CSV](c4/20261101T201447-3691421-1c095b6c31422/recursos.csv) |
| C4 | poc-reconciliacao-20261101t201447-3691421-1c095b6c31422-3 | 1 | 0.03 | 0.03 | 67.99MiB / 1GiB | 0 |  | [CSV](c4/20261101T201447-3691421-1c095b6c31422/recursos.csv) |
| C5 | poc-legado | 21 | 180.96 | 207.81 | 2.195GiB / 3GiB | 39 | próximo de 2 CPUs em ao menos uma amostra | [CSV](c5/20261101T202748-3719833-1c15d3a5811a7/recursos.csv) |
| C5 | poc-novo | 21 | 87.81 | 132.86 | 2.178GiB / 3GiB | 4 |  | [CSV](c5/20261101T202748-3719833-1c15d3a5811a7/recursos.csv) |
| C5 | poc-worker-20261101t202748-3719833-1c15d3a5811a7-0 | 21 | 63.35 | 98.14 | 628.4MiB / 1GiB | 0 |  | [CSV](c5/20261101T202748-3719833-1c15d3a5811a7/recursos.csv) |
| C5 | poc-gerador-20261101t202748-3719833-1c15d3a5811a7-1 | 16 | 11.29 | 25.31 | 321.8MiB / 1GiB | 0 |  | [CSV](c5/20261101T202748-3719833-1c15d3a5811a7/recursos.csv) |
| C5 | poc-gerador-20261101t202748-3719833-1c15d3a5811a7-2 | 1 | 0.00 | 0.00 | 0B / 0B | 0 |  | [CSV](c5/20261101T202748-3719833-1c15d3a5811a7/recursos.csv) |
| C5 | poc-reconciliacao-20261101t202748-3719833-1c15d3a5811a7-3 | 1 | 0.03 | 0.03 | 66.8MiB / 1GiB | 0 |  | [CSV](c5/20261101T202748-3719833-1c15d3a5811a7/recursos.csv) |
| C6 | poc-legado | 21 | 154.72 | 209.16 | 2.671GiB / 3GiB | 42 | próximo de 2 CPUs em ao menos uma amostra | [CSV](c6/20261101T204055-3749091-1c226fe424924/recursos.csv) |
| C6 | poc-novo | 21 | 85.22 | 156.59 | 2.194GiB / 3GiB | 49 |  | [CSV](c6/20261101T204055-3749091-1c226fe424924/recursos.csv) |
| C6 | poc-worker-20261101t204055-3749091-1c226fe424924-0 | 7 | 71.32 | 102.89 | 426.6MiB / 1GiB | 0 |  | [CSV](c6/20261101T204055-3749091-1c226fe424924/recursos.csv) |
| C6 | poc-gerador-20261101t204055-3749091-1c226fe424924-1 | 15 | 12.25 | 32.55 | 360MiB / 1GiB | 0 |  | [CSV](c6/20261101T204055-3749091-1c226fe424924/recursos.csv) |
| C6 | poc-worker-20261101t204055-3749091-1c226fe424924-2 | 14 | 65.52 | 126.42 | 624.4MiB / 1GiB | 0 |  | [CSV](c6/20261101T204055-3749091-1c226fe424924/recursos.csv) |
| C6 | poc-reconciliacao-20261101t204055-3749091-1c226fe424924-3 | 1 | 1.85 | 1.85 | 65.01MiB / 1GiB | 0 |  | [CSV](c6/20261101T204055-3749091-1c226fe424924/recursos.csv) |
| C7 | poc-legado | 22 | 167.98 | 209.20 | 2.73GiB / 3GiB | 36 | próximo de 2 CPUs em ao menos uma amostra | [CSV](c7/20261101T205405-3777899-1c2f153ff3308/recursos.csv) |
| C7 | poc-novo | 22 | 74.45 | 130.97 | 2.209GiB / 3GiB | 6 |  | [CSV](c7/20261101T205405-3777899-1c2f153ff3308/recursos.csv) |
| C7 | poc-worker-20261101t205405-3777899-1c2f153ff3308-0 | 7 | 61.55 | 157.72 | 335.1MiB / 1GiB | 0 |  | [CSV](c7/20261101T205405-3777899-1c2f153ff3308/recursos.csv) |
| C7 | poc-gerador-20261101t205405-3777899-1c2f153ff3308-1 | 16 | 14.29 | 64.38 | 321.7MiB / 1GiB | 0 |  | [CSV](c7/20261101T205405-3777899-1c2f153ff3308/recursos.csv) |
| C7 | poc-worker-20261101t205405-3777899-1c2f153ff3308-2 | 18 | 54.95 | 97.62 | 623.4MiB / 1GiB | 0 |  | [CSV](c7/20261101T205405-3777899-1c2f153ff3308/recursos.csv) |
| C8 | poc-legado | 6 | 89.05 | 168.50 | 2.707GiB / 3GiB | 3 |  | [CSV](c8/20261101T210742-3807570-1c3c1eef255a7/recursos.csv) |
| C8 | poc-novo | 6 | 61.44 | 127.02 | 2.195GiB / 3GiB | 2 |  | [CSV](c8/20261101T210742-3807570-1c3c1eef255a7/recursos.csv) |
| C8 | poc-worker-20261101t210742-3807570-1c3c1eef255a7-0 | 2 | 24.88 | 33.84 | 117.3MiB / 1GiB | 0 |  | [CSV](c8/20261101T210742-3807570-1c3c1eef255a7/recursos.csv) |
| C8 | poc-worker-20261101t210742-3807570-1c3c1eef255a7-4 | 4 | 55.57 | 171.82 | 208.8MiB / 1GiB | 0 |  | [CSV](c8/20261101T210742-3807570-1c3c1eef255a7/recursos.csv) |
| C9 | poc-legado | 72 | 182.66 | 211.59 | 2.767GiB / 3GiB | 39 | próximo de 2 CPUs em ao menos uma amostra | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-novo | 72 | 79.89 | 124.96 | 2.218GiB / 3GiB | 4 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-worker-20261101t211604-3826443-1c442a68956c0-0 | 35 | 67.43 | 107.93 | 632.1MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-1 | 16 | 14.53 | 65.46 | 320.1MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-2 | 8 | 20.70 | 82.87 | 169.1MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-3 | 8 | 13.75 | 35.70 | 218.8MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-worker-20261101t211604-3826443-1c442a68956c0-4 | 37 | 64.20 | 157.14 | 647.8MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-5 | 16 | 10.26 | 13.59 | 324MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-6 | 8 | 11.15 | 17.41 | 177.3MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-gerador-20261101t211604-3826443-1c442a68956c0-7 | 8 | 10.89 | 14.41 | 215.3MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |
| C9 | poc-reconciliacao-20261101t211604-3826443-1c442a68956c0-8 | 1 | 0.01 | 0.01 | 68.89MiB / 1GiB | 0 |  | [CSV](c9/20261101T211604-3826443-1c442a68956c0/recursos.csv) |

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

Texto fornecido em `recomendacao-humana.md` (não gerado pelo consolidador):

**Autor:** Tasso Gomes (RASCUNHO redigido por Claude; vale somente após revisão e confirmação do autor)
**Data:** 2026-11-02
**Decisão:** AJUSTAR — manter o desenho, não aceitar o ADR ainda e repetir as medições de desempenho em ambiente representativo.

**O que a PoC comprovou**
- Integridade da carga inicial (C1): 1.854.195 linhas copiadas em 109,9 s, sem divergência e sem UUID regerado.
- Correção de lacunas (C8): 55.629 de 55.629 chaves corrigidas (100%), sem divergência e sem UUID alterado.
- Mecanismo de fencing do lease (dono, versão e prazo, medidos pelo relógio do banco): mutantes de versão e prazo mortos nos testes da task 8.0.

**O que a PoC não comprovou**
- Latência, vazão, backlog, recuperação de falha e failover sob carga (C2–C7 e C9): todos saíram INVALIDO/TAXA, porque o gerador não sustentou a taxa-alvo. Não há evidência a favor nem contra o desenho nesses pontos. As séries de recursos indicam o legado Oracle perto do limite de 2 CPUs; é indício de gargalo do ambiente, não causa demonstrada.
- Failover A→B: nos testes locais (task 8.0, aceita por mim com ressalvas), mín. 10,7 s, p50 14,5 s, p90 16,5 s e máx. 20,02 s em 17 medições, com uma acima de 20 s. O gate é intermitente. Nada disso foi medido com `docker kill/pause` e `--cpus`.
- Custo no legado (C10): overhead de 19,998% (0,958 ms) contra o critério proposto de ≤15% E ≤5 ms. Passa nos 5 ms e reprova nos 15%. O critério (Q-T3) ainda não foi confirmado pelo time do legado; o percentual é alto por a base ser pequena em ms, e deve ser interpretado com essa confirmação.
- Inspeção visual do painel Kibana e SLA de 30 s de publicação: não executados.

**Riscos**
- Consistência vence disponibilidade (decisão de projeto): o failover A→B é exceção e não deve ser acelerado à custa de consistência.
- O ambiente de teste (Oracle Free, limitado a 2 CPUs pela própria edição, hosts compartilhados, WSL2 com salto de relógio) não representa a produção. Os números não se transferem a PostgreSQL.
- A conta Elastic tem escrita irrestrita; a restrição ao namespace da PoC é feita só por código.

**Próximos passos propostos**
1. Repetir C2–C7 e C9 no servidor de verdade (hardware e banco representativos), com os mesmos critérios, sem afrouxar limites.
2. Confirmar com o time do legado o critério de custo (Q-T3) e as questões Q1–Q3, Q5, Q6 do PRD.
3. Só então decidir sobre o ADR: manter `docs/adr-replocacao.md` como Proposto até as medições válidas; promover a `docs/adr/adr-001` (Accepted) se C2–C7 e C9 passarem.
4. Opcional: spike pequeno da alternativa de replicação dentro do Oracle (database link + job por partição), medindo latência e vazão antes de decidir; não foi medida nesta PoC.
