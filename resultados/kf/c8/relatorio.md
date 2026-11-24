# C8 no lab Kafka (alternativa A)

Execucao `20261123T171131Z` (2026-11-23T17:11:39Z a 2026-11-23T17:20:49Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c8/20261123T171131Z`.

Pipeline: worker Kafka (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12, EVENTOS_CSV); Debezium 3.7.1 LogMiner; Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| linhas_afetadas | 55697 | >0; chaves divergentes antes de religar == chaves distintas do CSV do gerador (so OBRA_MUSICAL) | APROVADO |
| linhas_corrigidas | 55697 | 55697 | APROVADO |
| archive_necessario_preservado | {"antes_limpar": {"scn_necessario": 10068146, "necessarios": 4, "existem": 4, "faltando": 0, "lista_faltando": []}, "depois_limpar": {"scn_necessario": 10068146, "necessarios": 4, "existem": 4, "falta | nenhum archive log necessario (next_change# > scn do offset do conector) apagado | APROVADO |
| recuperado_so_pelo_redo | true | True | APROVADO |
| tempo_ate_drenado_s | 49.907108783721924 | reportado (desde o retorno do resume do conector ate drenar) | SEM_CRITERIO |

## Degraus

| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |
|---|---|---|---|---|---|---|
| 1000 | 999.47 | VALIDO | None | 0 | None | 48.4 |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 8551830.

Relogios (offset legado - novo, ms): [('d1-1000-inicio', -1.731, 6.227), ('d1-1000-fim', -1.755, 4.868)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
