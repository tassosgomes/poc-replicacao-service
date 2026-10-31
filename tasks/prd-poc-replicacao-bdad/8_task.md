---
status: pending
task_kind: vertical
blocked_by: ["4.0", "6.0"]
gate: "./mvnw -B -q test -Dtest='V07*IT'"
gate_expect: "5 testes passam"
---

# 8.0 Partições são divididas entre réplicas e reassumidas após queda

**Fatia:** V-07 · **Cobre:** RF-04, RN-02, RN-04 · **Spec:** `techspec.md#v-07` · **ADR:** —

## Comportamento

Heartbeat de worker, cota `ceil(48 / workers ativos)`, renovação de lease, claim com `FOR UPDATE SKIP LOCKED`, liberação somente depois de parar os processadores excedentes e parada ordenada no `SIGTERM`; `LEASE_SEG=15` e nenhum processador trabalha com lease que vence em menos de 2 s (`docs/escopo-construcao.md`, Worker). O CSV passa a reportar `particoes` reais.

Cinco testes de integração: (1) um worker → 48 partições com seu `dono` em `lease_particao`; (2) sobe B → converge para 24/24 e a soma de `particoes` dos CSVs é ≤ 48 em todas as janelas (nunca dois donos); (3) `docker kill -s KILL` em A → B chega a 48 em ≤ 20 s (da morte à primeira janela com 48); (4) `SIGTERM` em A → partições liberadas e assumidas por B sem esperar o vencimento do lease; (5) `docker pause` por mais que `LEASE_SEG` e retomada → `origem_updated_at` na réplica nunca regride.

## Fora do escopo desta task

Cenários C6/C7 completos com métricas e veredito (V-12).

## Decisões fechadas

- Cada partição `(tabela, particao)` tem no máximo um dono; 48 linhas pré-criadas em `lease_particao`, e cada tabela nova acrescenta 16.
- Em C7, dois workers no mesmo host compartilham CPU; usar `--cpus` por worker e declarar isso no relatório.
- A guarda de versão (V-03) é a defesa final contra worker pausado, não o lease.

## Modificar / Referenciar

- **modificar:** laço do worker (V-03/V-05): substituir atribuição estática por lease dinâmico, `particoes` no CSV
- **ref:** `docs/escopo-construcao.md` (Worker, tabelas de controle)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V07*IT'`
- [ ] Os 5 testes passam.
- [ ] Failover medido ≤ 20 s e nenhuma partição com dois donos em qualquer janela.
