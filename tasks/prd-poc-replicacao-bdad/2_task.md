---
status: done
task_kind: vertical
blocked_by: []
gate: "bash scripts/testes/test-v01-ambiente.sh"
gate_expect: "6 verificações passam (exit 0)"
---

# 2.0 Um comando sobe, verifica e remove o ambiente nos dois servidores

**Fatia:** V-01 · **Cobre:** RF-01, US-03 · **Spec:** `techspec.md#v-01` · **ADR:** —

## Comportamento

`scripts/subir.sh`, executado no laptop com `.env` presente, verifica os pré-requisitos, sobe `poc-legado` (desenv-server) e `poc-novo` (infra-server), aplica os esquemas, gera a massa e imprime as contagens e os limites de CPU/memória; `scripts/derrubar.sh` remove tudo o que é da PoC. Detalhe do processamento, dos pré-requisitos e das evidências: `techspec.md#v-01`.

Casos negativos que precisam funcionar: porta 1521 ocupada no desenv-server → aborta **antes** de subir qualquer container, citando a porta e o que corrigir; `.env` ausente, rastreado pelo git ou não ignorado → aborta; relógios dessincronizados ou diferença > 100 ms → aborta; RAM livre < 4 GB ou disco < 10 GB → aborta com mensagem; reaplicar os esquemas volta ao estado inicial sem recriar containers (mesmo ID). A massa carrega com a captura ainda inexistente (ela chega em V-02).

O teste do gate (`scripts/testes/test-v01-ambiente.sh`) comprova 6 coisas: (1) contagens iguais aos volumes e `NanoCpus`/`Memory` corretos nos dois containers; (2) porta ocupada (por container temporário com prefixo `poc-`, removido ao final) aborta antes de subir; (3) reaplicar esquema mantém o ID dos containers; (4) volume reduzido (`DEFINE`) respeita a proporção e não deixa fonograma órfão; (5) `derrubar.sh` deixa o inventário final igual ao inicial e sem itens `poc-`; (6) `.env` ignorado pelo git e ausente de qualquer arquivo copiado aos servidores.

## Fora do escopo desta task

Triggers de captura e `evento_replicacao` (V-02), o jar Java (EN-01/V-03), vigilância periódica de serviços durante cenários (V-11), painel do Kibana (V-14).

## Decisões fechadas

- DP-06/DT-03: nada é instalado nos hosts; cada processo roda em container efêmero com prefixo `poc-` e teto de CPU/memória; imagens `gvenzl/oracle-free:23-slim-faststart` e `eclipse-temurin:25-jre`.
- DP-09: 2 CPUs e 3 GB por Oracle; portas 1521 (legado) e 1522 (novo).
- Os serviços existentes nos servidores (inclusive `postgres`, `rabbitmq`, `otel-collector` no infra-server) nunca são parados, reiniciados ou reconfigurados; apenas leitura do inventário.
- `.env`: só os nomes das chaves são conhecidos; os valores nunca são lidos, impressos ou copiados pelos scripts (exceto uso futuro pelo exportador, V-14).
- DP-12: a verificação de pré-requisitos é parte deste comando; o check periódico de relógio é do responsável pela infraestrutura.

## Modificar / Referenciar

- **modificar:** `.gitignore` (garantir `.env`; ver EN-01)
- **ref:** `docs/escopo-construcao.md` (Ambiente, Banco legado, Banco novo: DDL e variáveis)
- **ref:** `tasks/prd-poc-replicacao-bdad/techspec.md` (DT-03, DT-04, V-01)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Scripts (`scripts/`) | `fd -e sh . scripts -X shellcheck -S warning` | exit 0 | `shellcheck` local; sem CI no repositório |

## Pronto quando

- [x] Gate focalizado passa (exit 0): `bash scripts/testes/test-v01-ambiente.sh`
- [x] Ambiente limpo → `subir.sh` imprime `titular=200000 obra_musical=500000 fonograma=1000000` e os limites de recursos registrados.
- [x] Porta ocupada → aborta antes de subir qualquer container, com mensagem citando a porta.
- [x] `derrubar.sh` → inventário final igual ao inicial; containers e volumes `poc-` removidos; nenhum serviço existente alterado.
