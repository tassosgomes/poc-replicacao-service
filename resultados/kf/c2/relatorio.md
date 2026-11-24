# C2 no lab Kafka (alternativa A)

Execucao `20261123T131717Z` (2026-11-23T13:17:20Z a 2026-11-23T13:34:25Z), veredito **REPROVADO**, validade VALIDO . Evidencias em `resultados/kf/c2/20261123T131717Z`.

Pipeline: worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| p95_ms | 6429 | <=5000 | REPROVADO |
| tendencia_lag | {"inclinacao_ms_s": 16.080499683287805, "crescimento_sustentado": false, "janela_s": 180, "amostras": 35} | sem crescimento sustentado | APROVADO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 1000 | 999.99 | VALIDO | 6429 | 600012 | False | 31.2 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 600012.

Relogios (offset legado - novo, ms): [('d1-1000-inicio', 1.677, 5.112), ('d1-1000-fim', 2.087, 5.121)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
