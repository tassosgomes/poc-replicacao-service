---
status: pending
task_kind: vertical
blocked_by: ["6.0", "7.0", "8.0", "9.0", "10.0"]
gate: "bash scripts/testes/test-v11-cenarios.sh"
gate_expect: "7 verificações passam (exit 0)"
---

# 12.0 Um cenário de carga roda sozinho, é validado e arquiva seus resultados

**Fatia:** V-11 · **Cobre:** RF-10, RF-08, RN-06, RN-10, US-01, US-03 · **Spec:** `techspec.md#v-11` · **ADR:** —

## Comportamento

Biblioteca comum de cenário e os scripts `c2-regime.sh`, `c3-teto.sh`, `c4-rajada.sh`, `c5-transacao-longa.sh`, `c9-threads.sh` em `scripts/cenarios/`. Cada execução: pré-requisitos rápidos e inventário de serviços; estado inicial (pendentes 0, snapshot); passos do cenário; coleta de recursos e vigilância a cada 30 s dos serviços existentes (DT-06); estado final (pendentes 0, `reconciliacao completo DRY_RUN=true`, snapshot e comparação); e `resultados/<cenario>/` com CSVs, snapshots, diferenças, saída da reconciliação e `meta.json` (contrato em `techspec.md#contratos-e-fronteiras`). O veredito de cada critério é calculado, nunca digitado. Detalhe e regras de C3/C4/C5/C9: `techspec.md#v-11`.

Gate com durações reduzidas (variáveis de duração do cenário), 7 verificações: (1) C2 curto → `meta.json` com p95, tendência do lag, validade e snapshots; (2) taxa inalcançável → `INVALIDO` e nunca `APROVADO`; (3) diferença de relógio forçada > 100 ms → `INVALIDO` por relógio; (4) parar um container existente (só no teste, container de teste fora da PoC) → gerador abortado e `ABORTADO_SERVICO_EXISTENTE`; (5) reexecutar → começa com pendentes = 0 e snapshot novo; (6) C3 com degrau saturado → `GERADOR_SATURADO`, escada encerrada, degraus válidos preservados (DT-07); (7) C4, C5 e C9 em duração curta produzem `meta.json` com critérios e vereditos coerentes com DP-07, DP-08 e a tabela comparativa de C9.

## Fora do escopo desta task

Execuções completas (10 min, 5 min por degrau) e o relatório (V-15); cenários C6–C8 (V-12) e C1/C10 (V-13).

## Decisões fechadas

- DP-02, DP-07, DP-08 e DT-06, DT-07 conforme PRD/TechSpec; vereditos `APROVADO`/`REPROVADO`/`INVALIDO`/`SEM_CRITERIO`/`NAO_EXECUTADO`.
- A vigilância só detecta indisponibilidade dos serviços existentes, não latência.
- Resultados em `resultados/` são versionados (entregável); nunca contêm credenciais.
- Teste de parar container existente usa um container descartável criado para o teste, nunca um serviço real do homelab.

## Modificar / Referenciar

- **modificar:** `scripts/subir.sh`/biblioteca de pré-requisitos (reaproveitar a verificação rápida)
- **ref:** `docs/escopo-construcao.md` (Roteiro por cenário)
- **ref:** `techspec.md#contratos-e-fronteiras` (`meta.json`)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `bash scripts/testes/test-v11-cenarios.sh`
- [ ] As 7 verificações passam.
- [ ] `meta.json` contém todos os campos mínimos do contrato e vereditos calculados.
