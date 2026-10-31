---
status: pending
task_kind: vertical
blocked_by: ["12.0"]
gate: "bash scripts/testes/test-v12-falhas.sh"
gate_expect: "4 verificações passam (exit 0)"
---

# 13.0 Cenários de falha e recuperação provam integridade

**Fatia:** V-12 · **Cobre:** RF-10, RF-04, RF-06, RN-02, RN-06, RN-10, US-04 · **Spec:** `techspec.md#v-12` · **ADR:** —

## Comportamento

Scripts `c6-queda-worker.sh`, `c7-duas-replicas.sh` e `c8-trigger-desligado.sh` em `scripts/cenarios/`, usando a estrutura de V-11. **C6:** `docker kill -s KILL` no worker aos 3 min, novo worker aos 4 min; mede perda, duplicidade e tempo até o lag normalizar. **C7:** A em regime, sobe B, aos 3 min mata A; mede divisão e failover (≤ 20 s). **C8:** trigger de captura de `obra_musical` desabilitado por 1 min com carga, religado, `reconciliacao MODO=delta` e depois o worker; veredito pela verificação completa. Detalhe: `techspec.md#v-12`.

Quatro verificações (durações reduzidas): (1) C6 → `divergencias = 0`, `uuids_alterados = 0`, tempo até normalizar registrado; (2) C7 → 24/24 e `failover_s ≤ 20`; (3) C8 → `linhas_afetadas` (do log do gerador) = linhas corrigidas e `divergencias_finais = 0`; (4) C8 sem reconciliar (`DESDE_MIN` curto demais) → `REPROVADO` com divergências listadas, provando que o veredito não é automático.

## Fora do escopo desta task

C1/C10 (V-13); relatório (V-15).

## Decisões fechadas

- Matar worker sempre com `docker kill -s KILL` no container `poc-` do worker.
- O veredito de integridade usa sempre a verificação completa em `DRY_RUN` e a comparação de snapshots (DP-03).

## Modificar / Referenciar

- **modificar:** biblioteca comum de cenário (V-11) somente se faltar gancho para falhas
- **ref:** `docs/escopo-construcao.md` (Roteiro por cenário)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `bash scripts/testes/test-v12-falhas.sh`
- [ ] As 4 verificações passam.
- [ ] Veredito `REPROVADO` provocado de propósito aparece no `meta.json`.
