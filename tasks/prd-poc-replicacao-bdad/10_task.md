---
status: pending
task_kind: vertical
blocked_by: ["4.0"]
gate: "./mvnw -B -q test -Dtest='V09*Test,V09*IT'"
gate_expect: "5 testes passam"
---

# 10.0 A reconciliação encontra e reenfileira toda divergência

**Fatia:** V-09 · **Cobre:** RF-06, RN-10 · **Spec:** `techspec.md#v-09` · **ADR:** —

## Comportamento

Subcomando `reconciliacao` com `MODO=delta|completo` e `DRY_RUN`. `delta`: linhas do legado com `updated_at` acima de `agora − DESDE_MIN − JANELA_MIN`, comparadas a `origem_updated_at`. `completo`: soma de hash por faixa de 100.000 `codigo` nos dois bancos e, nas faixas divergentes, comparação linha a linha; colunas comparadas = negócio + versão, sem `id` e `replicado_em`; o hash inclui o `codigo` por linha. Divergência (ausente ou mais antiga) gera evento `R`, exceto com `DRY_RUN=true`, que só conta e imprime total e faixas, em formato legível por máquina. Detalhe: `techspec.md#v-09`.

Cinco testes: (1) bases idênticas → `completo` + `DRY_RUN` = 0, com tempo registrado; (2) `UPDATE` direto na réplica → faixa e `codigo` apontados; (3) linha apagada da réplica → `delta` e `completo` a detectam, sem `DRY_RUN` cria um evento `R`, o worker reaplica e nova verificação = 0; (4) `DRY_RUN=true` em bases divergentes → conta e `COUNT(*)` de `evento_replicacao` inalterado; (5) cálculo do hash por linha inclui o `codigo` (unitário).

## Fora do escopo desta task

Uso como verificador de cenário (V-11..V-13).

## Decisões fechadas

- RN-10: a réplica nunca é a verdade; corrige-se a partir do legado.
- Hash de 32 bits (`ORA_HASH`) é probabilístico: o relatório declara a limitação; `delta` complementa.
- Em `DRY_RUN`, nenhum evento é criado.

## Modificar / Referenciar

- **modificar:** `Main`/`Config` do EN-01 (subcomando `reconciliacao`)
- **ref:** `docs/escopo-construcao.md` (Ferramentas de apoio: reconciliação)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V09*Test,V09*IT'`
- [ ] Os 5 testes passam.
- [ ] Saída de `DRY_RUN` interpretável por script (contagem total e por faixa).
