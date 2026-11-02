# PRD Review — PoC de replicação BDAD (full)

Run: run.qEwDP4W5
Modo: full · Tentativa: 1/3
base_ref: 88b61d35cddfa1d45b670d284d23885d3046948e
validated_commit: fb45109dcfb91732f80953873191561395eb3a33
validated_tree: 4396f8625b9c839b8415247c421e3ba99e7513bd
Branch: feature/poc-replicacao-bdad (19 commits desde a base; 191 arquivos, dos quais 65 Java e o resumo de resultados/)

## Resultado: FULL VALIDATION APROVADA (0 bloqueantes, 6 recomendações)

HEAD e árvore não mudaram durante a revisão. `git status --porcelain` igual ao início (só `flow-state.json` modificado e `.agents/ .claude/ .vscode/ skills-lock.json` não versionados, estado operacional).

## Matriz de evidência (sem CI no repositório; fonte: contexto da chamada e "Verificações do projeto" das tasks)

| Componente | Comando | Exit | Resultado / limite |
|---|---|---|---|
| Java (build+testes unitários) | `./mvnw -B verify` (compile, 32 testes unitários, shade, forbiddenapis main+test, enforcer, spotless) | 0 | 32 testes, 0 falhas. `*IT` não rodam no `verify` por desenho (surefire exclui) |
| Java (formato) | `./mvnw -B -q spotless:check` | 0 | limpo |
| Scripts | `fd -e sh . scripts -X shellcheck -S warning` | 0 | sem avisos |
| V-15 relatório | `bash scripts/testes/test-v15-relatorio.sh` | 0 | "4 verificações passaram" (confere com `gate_expect`) |
| Gates V-01, V-02, V-05(sh), V-08 (shell) | `scripts/testes/test-v0{1,2,5,8}-*.sh` | não executado | dependem de SSH/Docker/sqlplus nos servidores compartilhados; proibido mexer neles nesta chamada |
| ITs V-03, V-04, V-05, V-06, V-07, V-09, V-10, V-14 | `./mvnw -Dtest='V0x*IT'` | não executado | exigem os bancos reais em desenv-server/infra-server (e Elastic); mesma proibição |
| Gates V-11, V-12, V-13 | `test-v11/12/13-*.sh` | não executado | disparam os cenários C1–C10 nos servidores; proibido pela chamada |

Limite declarado: a prova comportamental de replicação, lease/failover, reconciliação e carga inicial vem das revisões focused de cada task (`N.0_task_review.md`, todas com resultado final APROVADA) e dos `meta.json` arquivados; nesta full ela **não foi reexecutada**. Não afirmo espelho completo de CI.

## Sensor de discriminação (executado em `git worktree` temporário, removido; HEAD/status conferidos)

Só fatias com teste sem banco são mensuráveis aqui. Linha de base `V03*Test` exit 0.

| # | Arquivo / mutação | Critério | Testes | Resultado |
|---|---|---|---|---|
| M1 | `core/Uuid7.java`: `max(previous+1, fromClock)` → `fromClock` | UUIDv7 monotônico (V-03) | V03Uuid7Test | morto |
| M2 | `core/Coalescer.java`: último evento → primeiro | coalescência (V-03) | V03Coalescer/ProcessadorParticaoTest | morto |
| M3 | `ProcessadorParticao.java`: contagem de anomalias → 0 | anomalia de chave ausente (V-04) | V03ProcessadorParticaoTest | morto |
| M4 | `Gerador.java`: validade `0,98` → `0,5` | DP-02 / V-06 | V06*Test | morto |
| M5b | `Reconciliacao.hashLinha`: remove `codigo` do hash | hash inclui codigo (V-09) | V09HashTest | morto |
| M5c | idem: remove a versão (`updated_at`) | hash inclui versão (V-09) | V09HashTest | morto |
| M6 | `Metricas.percentil`: `ceil` → `floor` | p95/p99 (V-05) | V05MetricasTest, V11EvidenciasTest | morto |
| M7 | `Metricas.Skew`: limite 100 ms → 1000 ms | validade do relógio ≤100 ms (RF-08) | V05MetricasTest, V11EvidenciasTest | morto |
| M8 | `ConfigExportador.guardIndex`: guarda de namespace → `false` | restrição de namespace Elastic (V-14) | V14ExportadorTest | morto |
| M5 | `hashLinha`: remove o prefixo de comprimento por coluna | endurecimento além da TechSpec | V09HashTest | **sobreviveu** (ver R2) |

Não mensurável aqui (sem teste unitário sem banco; sem execução, declarado): fencing do lease (V-07; a 8.0 relata mutantes de versão/prazo mortos nos ITs), `OracleReplicaSink` (MERGE sem regerar id), CargaInicial, snapshots. Nenhuma mutação sobrevivente derivada de critério de aceite da TechSpec.

## Revisão semântica (resumo)

- **Rastreabilidade:** 16/16 tasks `done`, 16 checkboxes em `tasks.md`, uma revisão por task. Cada fatia V-01…V-15 tem commit próprio, código e gate. RF-11/RN-09/US-01 cobertos por `scripts/relatorio.py` e `resultados/relatorio.md`.
- **Identidade estável:** `OracleReplicaSink` só gera id para `codigo` inexistente (consulta em bloco antes do MERGE; MERGE nunca escreve o id); ORA-00001 vira `ReprocessavelException`. C1 e C8: 0 UUID alterado.
- **Ordem destino→ack e cerca:** `ProcessadorParticao` autoriza, confirma o destino com validação do lease no mesmo ato, e só então faz o ack; recusa lança `LeasePerdidoException`.
- **Segurança:** `.env` ignorado e não versionado; nenhum segredo real no diff (credenciais de laboratório em `subir.sh`, ver R1); Elastic com guardas de namespace por código (aceite de escrita irrestrita registrado pelo usuário); `.tsg-flow/` ignorado.
- **Versionamento de `resultados/`:** só o resumo (≈30 MB rastreados; brutos 2,3 GiB locais e ignorados), conforme decisão do usuário; README corrigido.
- **Relatório final:** inclui (a)–(e) exigidos (ressalvas da 8.0, consistência>disponibilidade, Elastic, taxa do gerador da 11.0, LEASE_SEG 12/15). Estado medido: C1 e C8 APROVADOS; C10 REPROVADO (19,9986% / 0,958 ms contra ≤15% E ≤5 ms); C2–C7 e C9 INVALIDO/TAXA, sem valor preenchido. Aceito pelo usuário (repetir no servidor real); não é defeito da entrega.

## Bloqueantes

Nenhum.

## Recomendações (não bloqueantes)

1. **R1 — Credenciais e porta (`scripts/subir.sh`):** senhas de laboratório fixas (`ORACLE_PASSWORD=poc-oracle`, senha do app = usuário) e porta publicada `-p $port:1521` em todas as interfaces do homelab. Aceitável para PoC descartável; mover para `.env` e/ou publicar só na rede interna se o ambiente for reutilizado.
2. **R2 — Teste de hash (`V09HashTest`):** só confere substrings; a mutação que remove o prefixo de comprimento (anti-ambiguidade com `;` nos dados) sobrevive. É endurecimento além da TechSpec, mas vale um caso com valores `'a;b','c'` vs `'a','b;c'` no `V09ReconciliacaoIT`.
3. **R3 — Evidência não reexecutada nesta full:** ITs e gates de cenário dependem dos servidores. Reexecutar `V03–V07, V09, V10, V14 IT` e os gates V-01/V-02/V-08 antes de usar o resultado como base de decisão.
4. **R4 — Lacuna de medição:** C2–C7 e C9 INVALIDO/TAXA e C10 REPROVADO. Repetir em ambiente representativo e fechar o limite de custo (Q-T3) com o time do legado; conferir `resultado.taxa()` no cenário concorrente de `V10CargaInicialIT:129-152`.
5. **R5 — Recomendação humana é rascunho:** `resultados/recomendacao-humana.md` (AJUSTAR) precisa de confirmação do autor; aceite do ADR segue decisão humana.
6. **R6 — Peso dos metadados:** `resultados/c10/meta.json` (≈7 MB) está duplicado no diretório da execução; considerar manter só uma cópia ou reduzir amostras.

## Limitações da revisão

Sem CI no repositório. Não reexecutei cenários C1–C10 nem toquei em infra-server/desenv-server (instrução da chamada). Valores do `.env` não foram lidos. Sensor limitado às fatias com teste sem banco.
