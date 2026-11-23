# C5 no lab Kafka (alternativa A)

Execucao `20261123T144330Z` (2026-11-23T14:43:38Z a 2026-11-23T14:58:23Z), veredito **REPROVADO**, validade VALIDO . Evidencias em `resultados/kf/c5/20261123T144330Z`.

Pipeline: worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| p95_demais_durante_aberta_ms | 5587 | <=5000 | REPROVADO |
| aplicacao_apos_commit_ms | 4133.2 | <=5000 | APROVADO |
| latencia_transacao_longa_ms | 34145 | reportada separadamente | SEM_CRITERIO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 1000 | 982.23 | VALIDO | 6960 | 589346 | True | 30.0 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 4966096.

Relogios (offset legado - novo, ms): [('d1-1000-inicio', 4.783, 5.522), ('d1-1000-fim', 5.135, 6.17)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
