# C6 no lab Kafka (alternativa A)

Execucao `20261123T163933Z` (2026-11-23T16:39:41Z a 2026-11-23T16:55:31Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c6/20261123T163933Z`.

Pipeline: worker Kafka (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| tempo_normalizar_s | 147.8 | lag <= 5000 ms em janela completa de 5 s durante carga apos o reinicio (tempo desde a queda) | APROVADO |
| tempo_normalizar_apos_reinicio_s | 87.55 | reportado (desde o docker run do worker substituto) | SEM_CRITERIO |
| estavel_apos_normalizar | false | reportado (baldes completos seguintes com lag <= 5 s ate o fim da carga) | SEM_CRITERIO |
| tempo_normalizar_relativo_ao_regime_s | 92.8 | extra descritivo: lag <= max(5 s, maior lag do regime antes da queda) | SEM_CRITERIO |
| eventos_log_x_gerador | {"eventos_gerador": 599827, "eventos_no_topico": 599827, "eventos_worker_csv_bruto": 599810, "eventos_worker_csv_dedup": 599810, "duplicados_entregues": 0, "eventos_faltantes_total": 17, "eventos_a_ma | reportado (re-entregas apos a queda sao registradas; integridade = estado final) | SEM_CRITERIO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 1000 | 999.71 | VALIDO | 49089 | 599810 | True | 30.1 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 7892121.

Relogios (offset legado - novo, ms): [('d1-1000-inicio', -2.324, 5.319), ('d1-1000-fim', -2.122, 6.149)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
