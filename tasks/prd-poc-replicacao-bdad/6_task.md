---
status: pending
task_kind: vertical
blocked_by: ["4.0"]
gate: "./mvnw -B -q test -Dtest='V05*Test,V05*IT'"
gate_expect: "7 testes passam"
---

# 6.0 Cada worker registra métricas, a diferença de relógio e o uso dos bancos

**Fatia:** V-05 · **Cobre:** RF-08, RN-09 · **Spec:** `techspec.md#v-05` · **ADR:** —

## Comportamento

O worker grava a cada 5 s, em `resultados/worker-<WORKER_ID>.csv` e no console, as colunas de `docs/escopo-construcao.md` (Métricas e saída) mais `skew_ms`, `skew_incerteza_ms` e uma coluna de eventos de transação longa. Latência do evento = `Instant` do relógio do worker lido após o retorno do commit no banco novo − `criado_em` (DT-04). Eventos com `criado_em` anterior ao commit em mais de 5 s entram só na coluna de transação longa e ficam fora dos percentis do SLA (DP-08). Estimador de diferença de relógio por ponto médio de RTT (`techspec.md#dt-04`), medido no início, a cada minuto e no fim; > 100 ms marca a janela como inválida. O coletor de recursos (no laptop, por ssh) grava CSV por cenário com `docker stats` dos containers `poc-` e sessões ativas dos dois bancos.

Sete testes: (1) 1 minuto de carga manual → CSV com 12 linhas e nenhuma coluna vazia; (2) dois `WORKER_ID` → dois arquivos distintos; (3) 30 s sem tráfego → zeros e `pendentes = 0`, sem exceção; (4) transação aberta 12 s e commit → latência só na coluna de transação longa, percentis do SLA inalterados; (5) estimador de skew com relógios simulados (offset de +500 ms) → `skew_ms` ≈ 500 ± incerteza e janela inválida; (6) cálculo de percentis e janela vazia (unitário); (7) o CSV não contém nenhuma credencial do `.env`.

## Fora do escopo desta task

Valor de `particoes` por lease (V-07); exportação ao Kibana (V-14); critérios de cenário (V-11).

## Decisões fechadas

- DP-01/RN-09: latência medida do `criado_em` até a confirmação no novo.
- DT-04: sem usar `replicado_em` (lido antes do commit, subestima).
- O CSV é o registro oficial (DP-10).
- Colunas de CPU/memória/sessões dos bancos NÃO vêm do worker; vêm do coletor externo.

## Modificar / Referenciar

- **modificar:** `Main`/`Config` e laço do worker (instrumentação)
- **modificar:** `scripts/` (coletor de recursos, `scripts/coletar-recursos.sh`) e `scripts/testes/test-v05-recursos.sh`
- **ref:** `docs/escopo-construcao.md` (Métricas e saída, colunas do CSV)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |
| Scripts | `bash scripts/testes/test-v05-recursos.sh` | exit 0: CSV de recursos com CPU/memória dos 2 containers `poc-` e sessões dos 2 bancos, sem credenciais | script da task (coletor fora do worker) |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V05*Test,V05*IT'`
- [ ] Os 7 testes passam.
- [ ] O coletor de recursos gera CSV com os dois containers e as sessões dos dois bancos durante 1 minuto de carga.
