# Worker de replicação

O serviço replica alterações da outbox do legado para o banco de destino. Cada unidade de trabalho
é uma combinação de tabela e partição. Um lease define qual instância pode processá-la.

## Onde começar a leitura

1. [Main.java](bootstrap/src/main/java/br/org/ecad/poc/replicacao/bootstrap/Main.java): valida a configuração e inicia o serviço.
2. [Worker.java](bootstrap/src/main/java/br/org/ecad/poc/replicacao/bootstrap/execucao/Worker.java): compõe os componentes e controla início e encerramento.
3. [CoordenadorParticoes.java](application/src/main/java/br/org/ecad/poc/replicacao/application/coordenacao/CoordenadorParticoes.java): renova leases e distribui partições.
4. [ExecutorParticao.java](bootstrap/src/main/java/br/org/ecad/poc/replicacao/bootstrap/execucao/ExecutorParticao.java): mantém uma partição em execução e recupera falhas.
5. [ProcessadorParticao.java](application/src/main/java/br/org/ecad/poc/replicacao/application/replicacao/ProcessadorParticao.java): executa um lote, na ordem de consistência.


## Módulos e responsabilidades

```text
worker/
├── domain/               eventos, linhas, tabelas, agrupamento e identidade da partição
├── application/          processamento de lote, coordenação e contratos de saída
├── infra-oracle/         JDBC, SQL, outbox, estado atual, réplica, leases e fencing
├── bootstrap/            CLI, configuração, threads, recuperação e métricas CSV
└── architecture-tests/   regras executáveis das fronteiras
```

As dependências seguem esta direção:

```text
application ──→ domain
infra-oracle ──→ application ──→ domain
bootstrap ──→ application + infra-oracle
```

O `bootstrap` conhece os adapters para montar o processo CLI e administrar suas conexões.
O domínio e a aplicação não conhecem JDBC, configuração de ambiente nem escrita de CSV.
A infraestrutura implementa os contratos da aplicação e não chama seus casos de uso.
A tecnologia aparece no pacote `infra.oracle`; os nomes das classes expressam sua responsabilidade.

Este serviço mantém o modelo existente de Java com JDBC e transações explícitas. A reorganização
não introduz Spring, JPA nem alterações de versões da stack.

## Coordenação

A cada `LEASE_SEG / 3`, o coordenador:

1. Registra o heartbeat, conta instâncias ativas e renova seus leases; confirma a transação.
2. Para executores de partições que perdeu e atualiza os prazos das que ainda possui.
3. Calcula a quota por instância; para cada excedente, encerra o executor antes de liberar seu lease.
4. Adquire partições disponíveis ou expiradas e confirma antes de iniciar seus executores.

O executor espera quando seu prazo local vence. Falhas de conexão ou processamento encerram os
recursos da tentativa e permitem reconexão. O fencing continua protegendo o commit contra um
executor que perdeu o lease ou ficou pausado durante a execução.

## Processamento de um lote

```text
Ler eventos da partição
  → agrupar eventos por chave
  → usar o estado do evento ou buscar o estado atual no legado
  → aplicar as linhas no destino de forma idempotente e protegida pela versão
  → validar lease e confirmar o destino na mesma chamada PL/SQL
  → verificar o prazo local e confirmar/remover os eventos na origem
  → registrar as métricas do lote
```

Os commits pertencem a bancos diferentes. Uma queda após confirmar o destino e antes de confirmar
a origem deixa eventos para reprocessamento idempotente. Uma chave ausente na origem é contabilizada
como anomalia e seus eventos são descartados para não bloquear a partição.

No encerramento, todos os executores são sinalizados antes de aguardar suas threads. Só depois
são liberados os leases e removido o heartbeat. O monitor termina com uma última amostra.

## Build e validação

Execute os comandos a partir da raiz do repositório:

```bash
./mvnw -pl worker/bootstrap -am package
java -jar worker/bootstrap/target/replicacao-worker-0.0.1-SNAPSHOT-exec.jar worker
./mvnw verify
```

`verify` executa testes unitários, testes de arquitetura, formatação e gates do build, incluindo a
PoC. Os testes `*IT` são compilados, mas só executam quando selecionados explicitamente, usando os
bancos reais da PoC:

```bash
./mvnw -pl worker/bootstrap -am -Dtest=V07CercaIT test
```

`V07CercaIT` restringe a leitura aos eventos criados pelo próprio teste. As suítes completas
`V03ReplicacaoIT`, `V04ResilienciaIT`, `V05MetricasIT` e `V07LeaseIT` também estão disponíveis.
Elas dependem do estado da fila e do controle de leases; `V04ResilienciaIT` inclui parar e reiniciar
o container do destino. Execute essas suítes em um ambiente reservado para os testes.

Testes unitários acompanham o módulo da classe testada. Os testes de integração do serviço ficam
no `bootstrap`, onde todos os componentes estão disponíveis. O `test-jar` mantém `BancosPoc`
disponível para os testes do módulo `poc`. O worker não depende da PoC.
