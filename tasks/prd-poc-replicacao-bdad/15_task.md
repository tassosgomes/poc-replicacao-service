---
status: pending
task_kind: vertical
blocked_by: ["6.0"]
gate: "./mvnw -B -q test -Dtest='V14*Test,V14*IT'"
gate_expect: "7 testes passam"
---

# 15.0 Os resultados aparecem ao vivo no Kibana

**Fatia:** V-14 · **Cobre:** RF-13 · **Spec:** `techspec.md#v-14` · **ADR:** —

## Comportamento

Exportador no laptop (opcional ao cenário) lê os CSVs dos workers e de recursos remotamente (ssh `tail -F`, sem copiar credenciais) e grava no Elasticsearch (`ELASTIC_HOST`) com `cenario` e `execucao`, em índice/namespace exclusivo da PoC, com a conta do `.env`. A criação do painel é entrega desta fatia: definição versionada, importada pela API de objetos salvos do Kibana (`KIBANA_HOST`) de forma idempotente e removível pelo `derrubar.sh` (limpeza opcional de índice e painel). Falha do Elastic não interrompe o cenário. Detalhe: `techspec.md#v-14`.

Sete testes: (1) adaptador HTTP contra servidor local controlado → requisições, cabeçalho de autenticação e corpo corretos, **sem** credencial em log, argumento ou exceção; (2) conta do `.env` escreve no índice da PoC e cria o painel (real); (3) escrita em índice alheio **falha** (real, só verifica a recusa); (4) criar o painel duas vezes não duplica; (5) Elastic indisponível → exportador falha sem derrubar cenário/CSVs; (6) consulta por `execucao` retorna só dados da PoC e a limpeza remove índice e painel sem tocar nada de outra aplicação; (7) `grep` dos valores secretos do `.env` em `resultados/`, nos logs do exportador, em `ps` e no `git diff` → nenhuma ocorrência.

## Fora do escopo desta task

Execução ao vivo durante C2 completo (feita em V-15 quando os cenários forem executados); critérios do relatório, que dependem só dos CSVs.

## Decisões fechadas

- DT-05: exportador só no laptop; credenciais só no `.env`, enviadas apenas no cabeçalho de autenticação.
- DP-10: CSV é o registro oficial; Elastic é complementar e jamais condiciona validade.
- Os valores do `.env` nunca são lidos pelo agente/implementador para impressão; testes leem por variável de ambiente e comparam sem imprimir.
- A conta só pode escrever dados e painel da PoC; se tiver permissão excessiva, registrar como pendência, não contornar.

## Modificar / Referenciar

- **modificar:** `scripts/derrubar.sh` (limpeza opcional de índice e painel da PoC)
- **modificar:** `Main`/`Config` (subcomando ou módulo do exportador no laptop)
- **ref:** `.env` (apenas nomes das chaves: `KIBANA_HOST`, `ELASTIC_HOST`, `KIBANA_ELASTIC_USER`, `KIBANA_ELASTIC_PASSWORD`)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [ ] Gate focalizado passa (exit 0): `./mvnw -B -q test -Dtest='V14*Test,V14*IT'`
- [ ] Os 7 testes passam.
- [ ] Painel mostra eventos/s, p50/p95/p99, pendentes, lag e CPU/sessões dos dois bancos com atraso ≤ 30 s durante um cenário real.
- [ ] Nenhuma credencial em repositório, logs, CSVs ou relatório.
