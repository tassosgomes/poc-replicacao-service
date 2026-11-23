# C3 no lab Kafka (alternativa A)

Execucao `20261123T135846Z-repeticao` (2026-11-23T13:58:54Z a 2026-11-23T14:27:48Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c3/20261123T135846Z-repeticao`.

Pipeline: worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| teto_ev_s | 2000 | >=2000 em degrau valido | APROVADO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 500 | 500.01 | VALIDO | 5399 | 150002 | True | 27.8 |
| 1000 | 999.98 | VALIDO | 5586 | 299995 | False | 29.1 |
| 2000 | 1998.61 | VALIDO | 10182 | 599595 | False | 29.9 |
| 4000 | 3459.15 | GERADOR_SATURADO | 30408 | 1037765 | True | 29.6 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 3726766.

Relogios (offset legado - novo, ms): [('d1-500-inicio', 2.571, 5.519), ('d1-500-fim', 2.97, 4.865), ('d2-1000-inicio', 3.011, 4.585), ('d2-1000-fim', 3.419, 4.561), ('d3-2000-inicio', 3.314, 4.841), ('d3-2000-fim', 4.108, 5.421), ('d4-4000-inicio', 3.983, 5.419), ('d4-4000-fim', 3.641, 4.753)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
