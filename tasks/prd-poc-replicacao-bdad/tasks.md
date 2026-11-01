# Plano de Implementação — PoC de Replicação BDAD → Banco Novo (sem CDC)

> **TechSpec de origem:** [techspec.md](techspec.md) (status Aprovado, 2026-10-31)
> **Escopo:** Backend (pipeline de dados e ferramentas de operação; sem UI nem API HTTP)
> **ADRs pertinentes:** nenhuma ADR numerada (`docs/adr/` não existe). Decisão em validação: [docs/adr-replocacao.md](../../docs/adr-replocacao.md) (proposto, pendente de PoC).
> **Status do plano:** Confirmado para implementação

## Visão Geral

Ao final, a PoC sobe dois Oracle nos servidores `desenv-server` (legado) e `infra-server` (banco novo) com um comando, captura mudanças por trigger, replica por worker com guarda de versão e UUIDv7 estável, tolera falhas, reconcilia divergências, executa os cenários C1–C10 sem intervenção, e consolida os números em `resultados/relatorio.md` com recomendação. Cada tarefa abaixo é uma fatia da TechSpec (V-nn = tarefa (nn+1).0, exceto o habilitador EN-01 = 1.0).

Três regras valem para todas as tasks: (1) os valores do `.env` nunca são lidos, impressos ou copiados (só os nomes das chaves); (2) nada é instalado nos hosts nem se altera serviço existente nos servidores compartilhados; (3) nomes de teste seguem `V<nn>*Test` (unitário) e `V<nn>*IT` (bancos reais), definidos em 1.0.

**Numeração de histórias (o PRD não as numera):** US-01 Arquiteto (resultados medidos), US-02 Responsável pelo legado (custo da captura), US-03 Engenheiro (ambiente com um comando e cenários reexecutáveis), US-04 Engenheiro (modos de falha), US-05 Engenheiro (troca futura da fonte de mudanças).

**Desvio em relação à TechSpec:** a task 11.0 (V-10) também depende de 7.0 (V-06, gerador), pois sua evidência de carga concorrente usa o gerador.

## Fases

### Fase 1 — Caminho básico ponta a ponta (tasks 1.0–6.0)

Ambiente sobe, um `UPDATE` no legado aparece na réplica em < 1 s, o worker sobrevive a falhas e grava CSV com todas as colunas; testes do núcleo passam sem banco. Checkpoints: gates de 2.0, 3.0, 4.0, 5.0 e 6.0.

### Fase 2 — Robustez e integridade (tasks 7.0–11.0 e 15.0)

Gerador alcança ≥ 98% da taxa, partições dividem 24/24 e reassumem 48 em ≤ 20 s, snapshots listam UUIDs alterados, reconciliação corrige divergências, carga inicial sem divergência, painel do Kibana (Should Have, não bloqueia o relatório).

### Fase 3 — Validação e decisão (tasks 12.0–14.0 e 16.0)

Cenários C1–C10 automatizados com veredito calculado e relatório final com recomendação.

## Mapa de Entrega

| Fatia | Task | Comportamento observável | Gate | Bloqueado por |
|---|---|---|---|---|
| V-01 | 2.0 | Um comando sobe, verifica e remove o ambiente nos dois servidores | `bash scripts/testes/test-v01-ambiente.sh` | Nenhum |
| V-02 | 3.0 | Alteração no legado gera evento com partição certa | `bash scripts/testes/test-v02-captura.sh` | 2.0 |
| V-03 | 4.0 | Alteração no legado aparece no banco novo em < 1 s | `./mvnw -B -q test -Dtest='V03*Test,V03*IT'` | 1.0, 3.0 |
| V-04 | 5.0 | Worker sobrevive a falhas sem perder nem duplicar | `./mvnw -B -q test -Dtest='V04*IT'` | 4.0 |
| V-05 | 6.0 | CSV de métricas, skew de relógio e recursos dos bancos | `./mvnw -B -q test -Dtest='V05*Test,V05*IT'` | 4.0 |
| V-06 | 7.0 | Gerador entrega taxa e padrões de carga | `./mvnw -B -q test -Dtest='V06*Test,V06*IT'` | 1.0, 3.0 |
| V-07 | 8.0 | Partições divididas e reassumidas após queda | `./mvnw -B -q test -Dtest='V07*IT'` | 4.0, 6.0 |
| V-08 | 9.0 | Snapshots dizem se algum UUID mudou | `bash scripts/testes/test-v08-snapshots.sh` | 4.0 |
| V-09 | 10.0 | Reconciliação encontra e reenfileira divergências | `./mvnw -B -q test -Dtest='V09*Test,V09*IT'` | 4.0 |
| V-10 | 11.0 | Carga inicial de 1,7 mi de linhas sem regerar UUID | `./mvnw -B -q test -Dtest='V10*IT'` | 4.0, 7.0, 9.0, 10.0 |
| V-11 | 12.0 | Estrutura comum de cenário + C2, C3, C4, C5, C9 | `bash scripts/testes/test-v11-cenarios.sh` | 6.0, 7.0, 8.0, 9.0, 10.0 |
| V-12 | 13.0 | C6, C7, C8: falha e recuperação com integridade | `bash scripts/testes/test-v12-falhas.sh` | 12.0 |
| V-13 | 14.0 | C1 e C10 (custo no legado) | `bash scripts/testes/test-v13-c1-c10.sh` | 11.0, 12.0 |
| V-14 | 15.0 | Resultados ao vivo no Kibana, painel idempotente | `./mvnw -B -q test -Dtest='V14*Test,V14*IT'` | 6.0 |
| V-15 | 16.0 | Relatório com critérios, limitações e recomendação | `bash scripts/testes/test-v15-relatorio.sh` | 12.0, 13.0, 14.0 |

### Habilitadores

| Enabler | Task | Por que não cabe numa fatia | Desbloqueia |
|---|---|---|---|
| EN-01 | 1.0 | Build, `Main` e `Config` são exigidos por V-03 (worker) e V-06 (gerador); criá-los dentro de uma duplicaria a base na outra. Sem comportamento de replicação por si. | V-03 (4.0) e V-06 (7.0) |

**Caminho crítico:** 2.0 → 3.0 → 4.0 → 6.0 → 8.0 → 12.0 → 13.0/14.0 → 16.0. **Paralelizáveis:** 1.0 com 2.0; 5.0, 6.0, 9.0, 10.0 após 4.0; 7.0 após 3.0; 15.0 após 6.0.

## Tasks

- [x] 1.0 Esqueleto do projeto Java com subcomandos e gates de build (EN-01)
- [x] 2.0 Um comando sobe, verifica e remove o ambiente nos dois servidores (V-01)
- [x] 3.0 Toda alteração no legado gera um evento com a partição certa (V-02)
- [x] 4.0 Uma alteração no legado aparece no banco novo em menos de 1 s (V-03)
- [x] 5.0 O worker sobrevive às falhas do pipeline sem perder nem duplicar (V-04)
- [x] 6.0 Cada worker registra métricas, a diferença de relógio e o uso dos bancos (V-05)
- [x] 7.0 O gerador entrega a taxa pedida e os padrões de carga dos cenários (V-06)
- [x] 8.0 Partições são divididas entre réplicas e reassumidas após queda (V-07)
- [x] 9.0 Dois snapshots dizem se algum UUID mudou (V-08)
- [x] 10.0 A reconciliação encontra e reenfileira toda divergência (V-09)
- [x] 11.0 A carga inicial copia 1,7 milhão de linhas sem regerar UUID (V-10)
- [x] 12.0 Um cenário de carga roda sozinho, é validado e arquiva seus resultados (V-11)
- [x] 13.0 Cenários de falha e recuperação provam integridade (V-12)
- [ ] 14.0 C1, C10 e a medição de custo no legado (V-13)
- [ ] 15.0 Os resultados aparecem ao vivo no Kibana (V-14)
- [ ] 16.0 O relatório consolida os números contra os critérios e recomenda (V-15)

## Verificação herdada

Não há CI no repositório (sem `.github/`, sem pipeline) e o código ainda não existe; as fontes abaixo são as alternativas disponíveis. Não declaro paridade com CI.

| Componente | Fonte | Checks e limites | Estado da base | Resolução planejada |
|---|---|---|---|---|
| Java (`pom.xml`) | skill `java` (gates aplicáveis sem Spring, DT-02), Maven Wrapper | `./mvnw -B -q spotless:check`; `./mvnw -B -q verify` (forbiddenapis, `jdk-unsafe`, `jdk-system-out`, testes `*Test`). Cobertura: a skill sugere > 80% para regra de negócio quando o projeto não define limite; **não há limite definido no projeto**, não é exigido por task | Sem código (não medido) | Gates criados em 1.0 |
| Scripts (`scripts/`) | `shellcheck` local | `fd -e sh . scripts -X shellcheck -S warning` | Sem scripts (não medido) | Aplicado a partir de 2.0 |
| Testes de integração e de cenário | Bancos reais da PoC nos servidores | Rodam só com o ambiente em pé (2.0) e `.env`/ssh disponíveis; fora de `verify` | N/A | Pré-requisitos reproduzíveis em 2.0 |

## Cobertura

| Requisito | Task(s) |
|---|---|
| RF-01 | 2.0 |
| RF-02 | 3.0 |
| RF-03 | 4.0, 5.0 |
| RF-04 | 8.0, 13.0 |
| RF-05 | 11.0, 14.0 |
| RF-06 | 10.0, 13.0 |
| RF-07 | 7.0 |
| RF-08 | 6.0, 12.0 |
| RF-09 | 9.0 |
| RF-10 | 12.0, 13.0, 14.0 |
| RF-11 | 16.0 |
| RF-12 | 1.0, 4.0 |
| RF-13 | 15.0 |
| RN-01 | 3.0, 4.0 |
| RN-02 | 3.0, 4.0, 8.0, 13.0 |
| RN-03 | 4.0, 5.0, 11.0 |
| RN-04 | 4.0, 8.0 |
| RN-05 | 4.0, 9.0, 11.0 |
| RN-06 | 4.0, 5.0, 12.0, 13.0 |
| RN-07 | 4.0, 5.0 |
| RN-08 | 5.0 |
| RN-09 | 6.0, 16.0 |
| RN-10 | 10.0, 12.0, 13.0 |
| US-01 | 12.0, 16.0 |
| US-02 | 14.0 |
| US-03 | 2.0, 12.0 |
| US-04 | 5.0, 13.0 |
| US-05 | 1.0, 4.0 |

**Categorias sem task própria, com justificativa:** observabilidade e segurança de credenciais estão embutidas em 6.0, 15.0 e 2.0 (não há observabilidade de produção, PRD Não-Objetivos); migração de dados = carga inicial (11.0); rollout/rollback e tabelas além das três estão fora de escopo (PRD).

> Verificado por `python3 .claude/skills/tsg-flow-task-creator/scripts/validate_plan.py tasks/prd-poc-replicacao-bdad/` antes do handoff.
