---
status: pending
task_kind: vertical
blocked_by: ["12.0", "13.0", "14.0"]
gate: "bash scripts/testes/test-v15-relatorio.sh"
gate_expect: "4 verificações passam (exit 0)"
---

# 16.0 O relatório consolida os números contra os critérios e recomenda

**Fatia:** V-15 · **Cobre:** RF-11, RN-09, US-01 · **Spec:** `techspec.md#v-15` · **ADR:** —

## Comportamento

`scripts/relatorio.sh` consolida os `meta.json` de `resultados/` em `resultados/relatorio.md`: tabela com todos os critérios de Métricas de Sucesso do PRD (valor medido e veredito), lista de cenários inválidos ou não executados sem preencher valor, condições do ambiente (limites de CPU/memória, ambiente compartilhado, rede interna, não equivale à produção), carga dos servidores por cenário, pontos em aberto respondidos e não respondidos, e a seção de recomendação (seguir, ajustar com indicação, descartar) com marcador explícito para texto humano. Critério reprovado traz causa provável e ajuste. Detalhe: `techspec.md#v-15`.

Gate, 4 verificações com `meta.json` sintéticos: (1) todas as linhas dos critérios do PRD presentes com valor e veredito; (2) critério reprovado provocado → causa provável e ajuste; (3) cenário ausente em `resultados/` → `NAO_EXECUTADO`, nunca com valor; (4) limites de CPU/memória e carga dos servidores presentes. Entrega adicional da task: executar os cenários C1–C10 completos (durações do PRD), arquivar em `resultados/` (versionado) e gerar o relatório real; a recomendação final é redigida por quem analisa os números e o marcador é substituído.

## Fora do escopo desta task

Aceitação do ADR (mover para `docs/adr/` como `Accepted` é decisão humana pós-relatório); validação do critério C10 com o time do legado.

## Decisões fechadas

- DP-05: a recomendação é parte do entregável, mas é texto humano; o gerador nunca a produz.
- Declarar limitação DT-01 (Oracle nos dois lados, números não transferem para PostgreSQL) e o hash probabilístico.
- Cenário inválido ou não executado é declarado, jamais preenchido.

## Modificar / Referenciar

- **modificar:** `README.md` (instruções de uso da PoC e referência ao relatório)
- **ref:** `resultados/*/meta.json` (contrato de resultado)
- **ref:** `docs/adr-replocacao.md` (critérios de sucesso e pontos em aberto)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `bash scripts/testes/test-v15-relatorio.sh`
- [ ] As 4 verificações passam.
- [ ] `resultados/` contém os 10 cenários (ou `NAO_EXECUTADO` justificado) e `resultados/relatorio.md` gerado.
- [ ] Seção de recomendação preenchida por humano antes do fechamento da PoC (não verificada pelo gate).
