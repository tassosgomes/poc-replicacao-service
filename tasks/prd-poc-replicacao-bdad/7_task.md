---
status: done
task_kind: vertical
blocked_by: ["1.0", "3.0"]
gate: "./mvnw -B -q test -Dtest='V06*Test,V06*IT'"
gate_expect: "11 testes passam"
---

# 7.0 O gerador entrega a taxa pedida e os padrões de carga dos cenários

**Fatia:** V-06 · **Cobre:** RF-07 · **Spec:** `techspec.md#v-06` · **ADR:** —

## Comportamento

O subcomando `gerador` distribui a taxa-alvo entre `THREADS` conexões; transações de 1 a `TX_MAX` linhas; mix de tabelas, inserts, soft deletes e `HOT_PCT` conforme parâmetros; modo rajada (um único `UPDATE` de N linhas); modo transação aberta; taxa alcançada impressa a cada 5 s e resumo final `VALIDO` (≥ 98% da alvo) ou `INVALIDO`. O gerador mede e reporta o tempo médio de transação (insumo do C10) e o log de linhas alteradas por janela (insumo do C8). Detalhe: `techspec.md#v-06`.

Sete testes: (1) `TAXA=2000 DURACAO_SEG=60`, captura ligada, sem worker → taxa ≥ 1.960 e `VALIDO`; (2) `MIX=40,40,20` por 120 s → distribuição dentro de ±3 pontos; (3) `RAJADA_LINHAS=50000` → um `COMMIT` e 50.000 eventos; (4) `TX_ABERTA_SEG=30` → `v$transaction` mostra ≈ 30 s; (5) `HOT_PCT=10` → 10% (±1) das operações em 1% das chaves; (6) alvo inalcançável (`TAXA=20000`) → `INVALIDO`; (7) agendador de taxa e escolha de linhas por mix/hot (unitário).

## Fora do escopo desta task

Cenários que usam o gerador (V-11..V-13); decisão de `GERADOR_SATURADO` por degrau (V-11, DT-07).

## Decisões fechadas

- DP-02: taxa alcançada < 98% → resultado inválido; o gerador só declara, a decisão do cenário é de V-11.
- Semente fixa configurável para repetibilidade (C10).
- O gerador roda no desenv-server, ao lado do legado, em container com limite de 1 GB (DP-06).

## Modificar / Referenciar

- **modificar:** `Main`/`Config` do EN-01 (subcomando `gerador`)
- **ref:** `docs/escopo-construcao.md` (Ferramentas de apoio: parâmetros do gerador)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |

## Pronto quando

- [x] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V06*Test,V06*IT'`
- [x] Os 11 testes (7 originais + validade, conexão indisponível, log por execução, execuções simultâneas) passam contra o legado real.
- [x] Resumo `INVALIDO` aparece sempre que a taxa média fica < 98%.
