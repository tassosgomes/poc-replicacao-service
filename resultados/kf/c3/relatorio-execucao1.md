# C3 no lab Kafka (alternativa A)

Execucao `20261123T133610Z` (2026-11-23T13:36:16Z a 2026-11-23T13:58:21Z), veredito **INVALIDO**, validade VALIDO . Evidencias em `resultados/kf/c3/20261123T133610Z`.

Pipeline: worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| teto_ev_s | 1000 | >=2000 em degrau valido | INVALIDO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 500 | 500.05 | VALIDO | 4586 | 150016 | False | 29.7 |
| 1000 | 1000.03 | VALIDO | 6101 | 300008 | False | 41.4 |
| 2000 | 1959.11 | GERADOR_SATURADO | 7077 | 589373 | False | 43.3 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 1639409.

Relogios (offset legado - novo, ms): [('d1-500-inicio', 2.362, 4.5), ('d1-500-fim', 2.195, 5.3), ('d2-1000-inicio', 2.445, 4.548), ('d2-1000-fim', 2.301, 5.529), ('d3-2000-inicio', 3.045, 5.231), ('d3-2000-fim', 2.956, 4.743)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
