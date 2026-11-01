---
status: done
task_kind: vertical
blocked_by: ["4.0", "7.0", "9.0", "10.0"]
gate: "./mvnw -B -q test -Dtest='V10*IT'"
gate_expect: "4 testes passam"
---

# 11.0 A carga inicial copia 1,7 milhão de linhas sem regerar UUID

**Fatia:** V-10 · **Cobre:** RF-05, RN-03, RN-05 · **Spec:** `techspec.md#v-10` · **ADR:** —

## Comportamento

Subcomando `carga-inicial` com `THREADS` e `TABELAS`: lê por faixas de 10.000 `codigo` e aplica o mesmo `MERGE` do worker, com a captura ligada; não trunca nem reinsere; interrupção e reexecução não alteram `id`; imprime linhas/s por tabela e tempo total. Detalhe: `techspec.md#v-10`.

Quatro testes: (1) réplica vazia, captura ligada, `THREADS=4` → resumo impresso; depois do worker zerar pendentes, contagens iguais e `reconciliacao completo DRY_RUN=true` = 0; (2) carga morta no meio e reexecutada → snapshot de IDs antes e depois sem `id` alterado; (3) gerador `TAXA=500` durante a carga → réplica reflete o estado mais recente, sem erro de unicidade escapando; (4) `TABELAS=titular` → só `titular` copiada.

Nota de plano: esta task depende também de 7.0 (gerador), pois a evidência (3) o usa; a TechSpec não listava V-06 como bloqueio de V-10.

## Fora do escopo desta task

Cenário C1 automatizado com `meta.json` (V-13).

## Decisões fechadas

- Nunca esvaziar e reinserir: UUID é imutável (RN-05).
- Captura ligada durante a carga; o worker reconcilia as alterações concorrentes.
- Concorrência carga × worker na mesma linha nova é resolvida por `ORA-00001` reprocessável (V-04).

## Modificar / Referenciar

- **modificar:** `Main`/`Config` do EN-01 (subcomando `carga-inicial`)
- **ref:** `docs/escopo-construcao.md` (Ferramentas de apoio: carga inicial)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |

## Pronto quando

- [x] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V10*IT'`
- [x] Os 4 testes passam.
- [x] Resumo mostra linhas/s por tabela e tempo total.
