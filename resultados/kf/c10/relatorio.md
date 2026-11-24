# C10 no lab Kafka (alternativa A)

Execucao `20261123T172049Z` (2026-11-23T17:20:55Z a 2026-11-23T19:13:23Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c10/20261123T172049Z`.

Pipeline: worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1.

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| repeticoes_alternadas | 8 | 8 | APROVADO |
| taxa_valida_todos_modos | true | True | APROVADO |
| overhead_captura | {"absoluto_ms": 0.6109999999999998, "percentual": 10.309626254956548} | <=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente) | APROVADO |
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 1000 | 999.55 | VALIDO | None | 0 | None | None |
| 1000 | 999.55 | VALIDO | None | 0 | None | None |
| 1000 | 999.55 | VALIDO | None | 0 | None | None |
| 1000 | 999.55 | VALIDO | None | 0 | None | None |
| 1000 | 999.55 | VALIDO | None | 0 | None | None |
| 1000 | 999.55 | VALIDO | None | 0 | None | None |
| 1000 | 999.55 | VALIDO | None | 0 | None | None |
| 1000 | 999.55 | VALIDO | None | 0 | None | None |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 13649546.

Relogios (offset legado - novo, ms): [('p01-ligada-inicio', -1.431, 4.915), ('p08-ligada-fim', 1.211, 5.212)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
