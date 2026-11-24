# C9 no lab Kafka (alternativa A)

Execucao `20261123T194659Z` (2026-11-23T19:47:07Z a 2026-11-23T21:49:01Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c9/20261123T194659Z`.

Pipeline: worker Kafka x1 em modos (ver modos.jsonl); Debezium 3.7.1 LogMiner; Kafka 4.3.1.

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| comparacao_modos_worker | "comparacao.csv" | vazao e p95 por modo e degrau (descritivo; nao ha alvo de superioridade) | SEM_CRITERIO |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 10 comparados; deletes fisicos convertidos: 0; lag final do consumer group: None.

Relogios (offset legado - novo, ms): [('c1-inicio', -0.476, 4.508), ('c1-fim', -1.16, 5.5)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
