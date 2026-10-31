---
status: pending
task_kind: vertical
blocked_by: ["2.0"]
gate: "bash scripts/testes/test-v02-captura.sh"
gate_expect: "6 verificações passam (exit 0)"
---

# 3.0 Toda alteração no legado gera um evento com a partição certa

**Fatia:** V-02 · **Cobre:** RF-02, RN-01, RN-02 · **Spec:** `techspec.md#v-02` · **ADR:** —

## Comportamento

Os triggers de captura (um por tabela, `AFTER … FOR EACH ROW`) gravam em `evento_replicacao` só a chave, a operação e `ORA_HASH(codigo, 15)`; um trigger separado, identificado como simulação do legado, preenche `updated_at`. A carga de massa de V-01 passa a desabilitar os triggers de captura e religá-los no fim. Detalhe: `techspec.md#v-02`.

Verificado pelo teste do gate, em 6 verificações: (1) `UPDATE` + `COMMIT` em `obra_musical` com `codigo=42` → exatamente `(OBRA_MUSICAL, 42, U, ORA_HASH(42,15))`; (2) INSERT, UPDATE e DELETE do mesmo `codigo` em três transações → três eventos I, U, D na mesma partição; (3) `ROLLBACK` → zero eventos; (4) `UPDATE` de 50.000 linhas numa transação → 50.000 eventos; (5) após `subir.sh` completo → zero eventos e triggers `ENABLED`; (6) a tabela de eventos não tem coluna de negócio.

## Fora do escopo desta task

Leitura dos eventos e replicação (V-03); métrica de overhead do trigger (V-13/C10).

## Decisões fechadas

- RN-01: o evento nunca carrega conteúdo da linha.
- `criado_em` é `TIMESTAMP WITH TIME ZONE`; índice `(tabela, particao, id)`; id por sequence.
- Só a tabela de eventos e os triggers de captura correspondem ao que existiria no BDAD real; o trigger de `updated_at` é simulação e deve ser rotulado assim.
- `updated_at` do legado simulado é `TIMESTAMP(6)` (Q1 do PRD segue aberta).

## Modificar / Referenciar

- **modificar:** `scripts/subir.sh` (massa com triggers desabilitados e religados no fim; contagem de eventos pendentes = 0)
- **modificar:** scripts/SQL de esquema do legado (V-01) para incluir `evento_replicacao` e triggers
- **ref:** `docs/escopo-construcao.md` (Banco legado)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `bash scripts/testes/test-v02-captura.sh`
- [ ] Os 6 comportamentos acima observados contra o legado real da PoC.
- [ ] Reexecutar `subir.sh` com massa completa mantém `COUNT(*)` de `evento_replicacao` = 0.
