# Fase 4: variante transacional (lab Kafka, 2026-11-24)

Resumo; desenho, alternativas, riscos e tabelas completas em `docs/spike-kafka-notas.md` (seção Fase 4) e `resultados/comparativo-java-gg-kafka.md` (seção f).

| Medição | Modo atual | Modo transacional | Dados |
|---|---|---|---|
| Transações multi-tabela com estado parcial (1.500, C2 de fundo) | 1.294 (86,3%); exposição p50 104 ms, max 2,4 s | 0 | `atomicidade/{atual,transacional}/*/meta.json` |
| C2 p95 | 6,43 s REPROVADO (oficial `resultados/kf/c2`) | 5,85 s REPROVADO | `c2/meta.json` |
| C3 teto | 2.000 ev/s | 2.000 ev/s (3ª execução; 1ª e 2ª INVALIDO por gerador: 1.823 e 1.935 ev/s) | `c3/meta.json`, `c3-primeira/`, `c3-tentativas/` |
| C7 failover | 14,4 s | 11,1 s (lease global; adaptado) | `c7/meta.json` |

Reconciliação 0 e UUIDs estáveis (0 alterados em 1,7 M linhas) em todas as execuções. Brutos (CSVs, snapshots) ignorados pelo git, como nos outros labs.
