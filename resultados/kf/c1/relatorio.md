# C1 no lab Kafka (alternativa A)

Execucao `20261123T125636Z` (2026-11-23T12:56:36Z a 2026-11-23T13:16:49Z), veredito **APROVADO**, validade VALIDO . Evidencias em `resultados/kf/c1/20261123T125636Z`.

Pipeline: worker Kafka x1 (kf-worker, 2 CPU/1 GiB, -Xmx512m, LEASE_SEG 12); Debezium 3.7.1 LogMiner (snapshot.mode=no_data); Kafka 4.3.1 (1 broker, 16 particoes/topico).

## Criterios

| Criterio | Valor | Alvo | Veredito |
|---|---|---|---|
| divergencias | 0 | 0 | APROVADO |
| uuids_alterados | 0 | 0 | APROVADO |
| pendentes_final | 0 | 0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING) | APROVADO |
| contagens_iguais | {"legado": {"TITULAR": 200000, "OBRA_MUSICAL": 500000, "FONOGRAMA": 1000000}, "novo": {"TITULAR": 200000, "OBRA_MUSICAL": 500000, "FONOGRAMA": 1000000}} | iguais por tabela no escopo declarado | APROVADO |
| carga_inicial | {"tabelas": {"TITULAR": {"linhas": 200000, "linhas_s": 8693.05}, "OBRA_MUSICAL": {"linhas": 500000, "linhas_s": 14710.66}, "FONOGRAMA": {"linhas": 1000000, "linhas_s": 8570.29}}, "tempo_total_s": 173. | linhas/s por tabela e tempo total; linhas copiadas == linhas do legado (sem DML durante a carga) | APROVADO |

## Integridade

Divergencias (SQL): 0; UUIDs alterados: 0 de 1700000 comparados; deletes fisicos convertidos: 0; lag final do consumer group: 0 0.

Relogios (offset legado - novo, ms): [('c1-inicio', 1.03, 5.519), ('c1-fim', 1.293, 4.951)]

Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.
