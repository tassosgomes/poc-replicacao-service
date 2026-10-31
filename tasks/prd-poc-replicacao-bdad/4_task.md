---
status: pending
task_kind: vertical
blocked_by: ["1.0", "3.0"]
gate: "./mvnw -B -q test -Dtest='V03*Test,V03*IT'"
gate_expect: "14 testes passam (8 unitários sem banco e 6 de integração)"
---

# 4.0 Uma alteração no legado aparece no banco novo em menos de 1 s

**Fatia:** V-03 · **Cobre:** RF-03, RF-12, RN-01, RN-02, RN-03, RN-04, RN-05, RN-06, RN-07, US-05 · **Spec:** `techspec.md#v-03` · **ADR:** —

## Comportamento

O worker (um processo, as 48 partições atribuídas estaticamente, sem lease dinâmico) lê até `LOTE_MAX` eventos por partição em ordem de `id`, coalesce por `codigo`, busca o estado atual em blocos de 1.000, grava por `MERGE` em lote com guarda `origem_updated_at >=`, faz commit no banco novo e só então apaga os eventos lidos no legado (outra conexão/transação). O `id` UUIDv7 é gerado só no ramo de insert. Núcleo (`ChangeSource`, `StateProvider`, `ReplicaSink`, `TableSpec`, `Coalescer`, `Uuid7`, `ChangeEvent`) sem dependência de Oracle ou JDBC; adicionar tabela é adicionar uma entrada de `TableSpec`. Contratos das portas: `techspec.md#contratos-e-fronteiras`. Soft delete chega como update de estado.

**Unitários (sem banco), 8:** UUIDv7 com versão 7 e variante RFC 9562; 1.000.000 valores estritamente crescentes; o mesmo com várias threads; coalesce; SQL gerado por `TableSpec` para as três tabelas (3 testes); `ChangeSource` em memória alimenta o destino sem alterar o destino.

**Integração com bancos reais da PoC, 6:** `UPDATE` + commit → linha na réplica em < 1 s e evento removido; dois UPDATEs seguidos → réplica com o segundo e `id` igual; reaplicar manualmente o mesmo lote → `id` idêntico; `excluido = 1` → linha presente com `excluido = 1`; estado com `origem_updated_at` menor forçado → réplica mantém o mais novo; lote de 500 eventos → 500 apagados.

## Fora do escopo desta task

Falhas do pipeline, anomalia e retry (V-04); métricas e CSV (V-05); lease dinâmico (V-07).

## Decisões fechadas

- RN-03/RN-04/RN-05/RN-06 conforme `docs/adr-replocacao.md` (D1–D15); `MERGE` e DDL de `docs/escopo-construcao.md`.
- Uma transação no banco novo por lote; descarte no legado depois, sem transação distribuída.
- Threads virtuais ligadas por padrão e desligáveis por configuração (`VIRTUAL_THREADS`), usado em C9.
- `Clock` injetado; instantes são `Instant`; sem `UUID.randomUUID()`.
- Testes de integração rodam contra os containers `poc-` já em pé (configurados por variável de ambiente), não Testcontainers.

## Modificar / Referenciar

- **modificar:** `Main`/`Config` do EN-01 (registrar o subcomando `worker` e suas variáveis)
- **ref:** `docs/escopo-construcao.md` (Worker: MERGE, lote, partições)
- **ref:** `techspec.md#contratos-e-fronteiras` (portas do núcleo)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V03*Test,V03*IT'`
- [ ] Os 8 testes unitários passam sem nenhum banco acessível.
- [ ] Com o worker rodando e réplica em dia, um `UPDATE` no legado aparece no banco novo em < 1 s.
- [ ] Estado mais antigo nunca sobrescreve o mais novo; `id` nunca muda nos reprocessamentos.
