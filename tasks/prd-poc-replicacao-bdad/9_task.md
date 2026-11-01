---
status: done
task_kind: vertical
blocked_by: ["4.0"]
gate: "bash scripts/testes/test-v08-snapshots.sh"
gate_expect: "4 verificações passam (exit 0)"
---

# 9.0 Dois snapshots dizem se algum UUID mudou

**Fatia:** V-08 · **Cobre:** RF-09, RN-05 · **Spec:** `techspec.md#v-08` · **ADR:** —

## Comportamento

`scripts/snapshot-ids.sh <destino>` exporta `codigo,id` das três réplicas, ordenado; `scripts/comparar-snapshots.sh a b` lista `codigo`, `id_antes`, `id_depois` para todo `codigo` presente nos dois com `id` diferente, ignora `codigo` presente em só um deles e sai com código ≠ 0 se houver diferença.

Quatro verificações: (1) dois snapshots seguidos sem alteração → zero diferenças, saída 0; (2) `id` alterado manualmente na réplica → `codigo` listado com os dois valores, saída ≠ 0; (3) linhas novas entre os snapshots → não são diferença; (4) tempo e volume do snapshot de 1,7 milhão de linhas registrados em arquivo, para dimensionar o custo por cenário. Detalhe: `techspec.md#v-08`.

## Fora do escopo desta task

Uso dentro dos cenários (V-11..V-13).

## Decisões fechadas

- Comparação por `codigo`; ordem estável; saída em arquivo e código de saída.
- Execução no infra-server, ao lado do banco novo, sem copiar segredos.

## Modificar / Referenciar

- **modificar:** nada preexistente além de `scripts/` próprio da fatia
- **ref:** `docs/escopo-construcao.md` (Ferramentas de apoio)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `bash scripts/testes/test-v08-snapshots.sh`
- [ ] As 4 verificações passam.
- [ ] Tempo e volume do snapshot completo ficam registrados.
