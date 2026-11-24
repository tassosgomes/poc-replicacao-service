# C7 no lab Kafka (alternativa A)

Execucao `20261123T165531Z` (2026-11-23T16:55:39Z a 2026-11-23T17:11:31Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c7/20261123T165531Z`.

Pipeline: worker Kafka (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| divisao_24_24 | "divisao: kf-worker-1,24 kf-worker-2,24" | kf-worker-1 e kf-worker-2 com 24 leases cada antes da queda | APROVADO |
| failover_s | 14.4 | <=20 (queda do kf-worker-1 ate o kf-worker-2 ter 48 leases validos e nova marca all 48 owned partitions active; relogio do host do banco) | APROVADO |
| sobrevivente_48 | {"leases": 48, "marcas": 2} | 48 leases validos e marca nova no log do sobrevivente | APROVADO |
| estavel_apos_tomada | false | reportado (baldes completos seguintes com lag <= 5 s) | SEM_CRITERIO |
| tempo_normalizar_desde_queda_s | 118.81 | reportado (primeiro balde de 5 s completo com lag <= 5 s) | SEM_CRITERIO |
| tempo_normalizar_relativo_ao_regime_s | 8.8 | extra descritivo: lag <= max(5 s, maior lag do regime antes da queda) | SEM_CRITERIO |
| eventos_log_x_gerador | {"eventos_gerador": 599741, "eventos_no_topico": 599741, "eventos_worker_csv_bruto": 599741, "eventos_worker_csv_dedup": 599741, "duplicados_entregues": 0, "eventos_faltantes_total": 0, "eventos_a_mai | reportado (re-entregas apos a queda sao registradas; integridade = estado final) | SEM_CRITERIO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 1000 | 999.56 | VALIDO | 5881 | 599741 | True | 28.2 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 8491862.

Relogios (offset legado - novo, ms): [('d1-1000-inicio', -2.14, 4.958), ('d1-1000-fim', -1.834, 4.779)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
