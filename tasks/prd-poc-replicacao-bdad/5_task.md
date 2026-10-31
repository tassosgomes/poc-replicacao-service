---
status: done
task_kind: vertical
blocked_by: ["4.0"]
gate: "./mvnw -B -q test -Dtest='V04*IT'"
gate_expect: "5 testes passam"
---

# 5.0 O worker sobrevive às falhas do pipeline sem perder nem duplicar

**Fatia:** V-04 · **Cobre:** RF-03, RN-03, RN-06, RN-07, RN-08, US-04 · **Spec:** `techspec.md#v-04` · **ADR:** —

## Comportamento

Linha ausente no legado na busca → contador de anomalia, alerta no console, evento descartado e o restante do lote aplicado. Qualquer erro num passo fecha as conexões da partição, espera 2 s e recomeça; eventos não apagados são reprocessados. `ORA-00001` por concorrência com outro escritor da mesma linha é reprocessável, não fatal. Partição vazia: dorme `POLL_MS` e consulta de novo. Detalhe: `techspec.md#v-04`.

Cinco testes de integração: (1) `DELETE` físico no legado com evento já registrado → `anomalias` +1, alerta impresso, resto do lote aplicado; (2) fonograma com `obra_codigo` inexistente na réplica → aceito, e após a obra chegar a referência bate por `codigo`; (3) `docker stop` do banco novo no meio de um lote e religar → worker reconecta sozinho, pendentes voltam a 0, nenhum `codigo` duplicado; (4) falha injetada (configuração de teste) após o commit no novo e antes do descarte → no recomeço o lote é reaplicado, estado final idêntico e `id` inalterado; (5) `ORA-00001` provocado por dois escritores na mesma linha nova → tratado como reprocessável, sem laço de erro.

## Fora do escopo desta task

CSV de métricas (V-05); lease (V-07); carga inicial concorrente com o worker em escala (V-10).

## Decisões fechadas

- RN-07: linha inexistente é anomalia a investigar, nunca exclusão.
- RN-08: sem chave estrangeira física; referência por `codigo`.
- Espera de recuo de 2 s e `POLL_MS` configurável; ponto de falha injetável só por configuração de teste, desligado por padrão.
- Parar/religar o banco novo nos testes acontece somente no container `poc-novo`.

## Modificar / Referenciar

- **modificar:** adaptadores `oracle` e laço do worker de V-03 (tratamento de falha, anomalia, `ORA-00001` reprocessável)
- **ref:** `docs/escopo-construcao.md` (Worker, Riscos)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |

## Pronto quando

- [x] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V04*IT'`
- [x] Os 5 cenários de falha mantêm zero perda e zero linha duplicada.
- [x] Nenhum serviço existente é tocado pelos testes (só `poc-novo`).
