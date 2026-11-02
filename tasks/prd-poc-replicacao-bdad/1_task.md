---
status: done
task_kind: enabling
blocked_by: []
gate: "./mvnw -B -q verify"
gate_expect: "build sem erros, 0 violações de spotless e forbiddenapis, jar executável lista os 4 subcomandos"
---

# 1.0 Esqueleto do projeto Java com subcomandos e gates de build

**Fatia:** EN-01 · **Cobre:** RF-12, US-05 · **Spec:** `techspec.md#en-01` · **ADR:** —

## Comportamento

Ao final existe um projeto Maven (Java 25, Maven Wrapper) que compila, formata e gera um jar executável cujo `Main` despacha os subcomandos `worker`, `carga-inicial`, `reconciliacao` e `gerador` (cada um ainda sem comportamento: imprime uso e sai com código ≠ 0 se chamado sem implementação) e cuja `Config` lê parâmetros por variável de ambiente ou `-D`. Os gates de build aplicáveis da skill `java` sem Spring estão ligados em `verify`: Spotless, forbiddenapis (`UUID.randomUUID()`, `now()` sem `Clock`, locale/charset padrão, `System.out`) e `jdk-unsafe`. O núcleo (`core`) e os adaptadores (`oracle`) existem como fronteira de pacotes, com `ojdbc11` como única dependência de runtime. Caso negativo: subcomando desconhecido ou `Config` inválida (variável obrigatória ausente) → mensagem clara em uma linha e saída ≠ 0, sem rastro de pilha como única informação.

**Por que é habilitador:** não há comportamento observável de replicação aqui; V-03 (worker) e V-06 (gerador) precisam do mesmo build, `Main` e `Config`, e criá-los dentro de uma delas duplicaria a base na outra. **Desbloqueia:** V-03 e V-06.

## Fora do escopo desta task

Qualquer lógica de replicação, SQL, geração de carga ou acesso a banco (V-03, V-06). Scripts de ambiente (V-01).

## Decisões fechadas

- DT-02: sem Spring, módulo Maven único, sem Flyway, `Uuid7` próprio (implementado em V-03); mantém Clock injetado, `Instant`, proibição de `UUID.randomUUID()`.
- Maven local é 3.8.7: usar sempre o Maven Wrapper.
- Saída de console passa por `System.Logger` (a regra `jdk-system-out` da skill `java` vale); nenhuma credencial em log.
- Convenção de nomes de teste da PoC: testes unitários `V<nn>*Test` e de integração com bancos reais `V<nn>*IT`, onde `<nn>` é o número da fatia da TechSpec; `verify` roda só `*Test`, e os `*IT` rodam apenas quando selecionados por `-Dtest`.
- Pacote base `br.org.ecad.poc.replicacao` (DP-11: repositório `poc-replicacao-service`).

## Modificar / Referenciar

- **modificar:** `.gitignore` (acrescentar `target/` e saídas brutas temporárias, mantendo `.env`; `resultados/` NÃO é ignorado; o arquivo está sem quebra de linha final)
- **ref:** `docs/escopo-construcao.md` (seção Repositório e stack; variáveis de configuração)
- **ref:** `.claude/skills/java/SKILL.md` (gates aplicáveis sem Spring)

## Verificações do projeto

| Componente | Comando | Resultado esperado | Fonte |
|---|---|---|---|
| Java (`pom.xml`, Maven Wrapper) | `./mvnw -B -q spotless:check` | exit 0 | skill `java` (Spotless); sem CI no repositório |
| Java | `./mvnw -B -q verify` | exit 0; forbiddenapis sem violações; testes unitários (`*Test`) de todo o projeto passam | skill `java`, `pom.xml` (EN-01) |

## Pronto quando

- [x] Gate focalizado passa (exit 0): `./mvnw -B -q verify`
- [x] Jar gerado executa `java -jar … <subcomando>` para cada um dos 4 subcomandos e responde com uso, e subcomando inválido sai ≠ 0.
- [x] `git check-ignore .env` e `git check-ignore target/` retornam sucesso; `resultados/` não é ignorado.
- [x] Violação proposital de `UUID.randomUUID()` quebra `verify` (verificado uma vez e revertido).
