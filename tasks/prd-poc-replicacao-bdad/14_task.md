---
status: pending
task_kind: vertical
blocked_by: ["11.0", "12.0"]
gate: "bash scripts/testes/test-v13-c1-c10.sh"
gate_expect: "3 verificações passam (exit 0)"
---

# 14.0 C1, C10 e a medição de custo no legado

**Fatia:** V-13 · **Cobre:** RF-10, RF-05, US-02 · **Spec:** `techspec.md#v-13` · **ADR:** —

## Comportamento

`c1-carga-inicial.sh` e `c10-custo-legado.sh`. **C1:** trunca o banco novo, executa `carga-inicial` com captura ligada, sobe o worker até pendentes = 0 e verifica. **C10:** mesma carga (taxa e semente fixas) com captura ligada e com os triggers desabilitados; compara o tempo médio de transação medido no gerador; ≥ 3 repetições alternadas por modo; reporta média, dispersão, overhead percentual e absoluto (ms). O limite de aceitação é parâmetro, com padrão da DT-09 (≤ 15% **e** ≤ 5 ms); com o limite desligado o veredito é `SEM_CRITERIO` e o número é só reportado. Detalhe: `techspec.md#v-13`.

Três verificações (volume e duração reduzidos): (1) C1 → contagens iguais, `divergencias = 0`, linhas/s por tabela e tempo total em `meta.json`; (2) C10 → ≥ 3 repetições alternadas, overhead percentual e absoluto, dispersão e veredito `APROVADO`/`REPROVADO` pelo critério padrão; (3) C10 com limite desligado → `SEM_CRITERIO` com o número reportado.

## Fora do escopo desta task

Execução completa dos dois cenários e consolidação (V-15). Confirmação do limite com o time do legado (Q-T3).

## Decisões fechadas

- DT-08/DT-09: critério padrão proposto, a confirmar com o time do legado (Q-T3); se não confirmado, o relatório registra "segundo critério proposto".
- Custo da própria consulta de `pendentes` deve ser medido com e sem métricas para não contaminar C10.
- Religar/desligar apenas os triggers de captura; o trigger de simulação de `updated_at` permanece ligado nos dois modos.

## Modificar / Referenciar

- **modificar:** biblioteca comum de cenário (V-11) se faltar gancho
- **ref:** `docs/escopo-construcao.md` (Roteiro por cenário)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `bash scripts/testes/test-v13-c1-c10.sh`
- [ ] As 3 verificações passam.
- [ ] O resultado de C10 traz percentual, valor absoluto, dispersão e veredito.
