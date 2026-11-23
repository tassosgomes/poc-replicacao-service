# C4 no lab Kafka (alternativa A)

Execucao `20261123T142814Z` (2026-11-23T14:28:22Z a 2026-11-23T14:42:53Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c4/20261123T142814Z`.

Pipeline: worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| recuperacao_rajada_s | 28.73 | <=120 apos commit | APROVADO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 1000 | 999.97 | VALIDO | 8484 | 599777 | False | 27.9 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 4376749.

Relogios (offset legado - novo, ms): [('d1-1000-inicio', 3.925, 4.746), ('d1-1000-fim', 4.955, 4.755)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
