# poc-replicacao-service

PoC de replicação por captura transacional, particionamento com lease e reconciliação. Os dois
Oracle Free rodam nos hosts internos compartilhados; worker e gerador usam containers próprios
`poc-*`. Não há instalação de ferramentas nos hosts. As instruções e parâmetros dos dez
cenários estão em [scripts/cenarios/README.md](scripts/cenarios/README.md).

Compile com `./mvnw -B -q package`, confira os pré-requisitos de SSH/Docker e execute os scripts
de cenário no laptop. Use as durações padrão para o experimento completo: regime de 600 s,
degraus de 300 s, rajada de 50.000 linhas e transação aberta de 30 s. C1 recria somente a réplica
de negócio da PoC; C10 alterna a captura e deve rodar por último, pois deixa alterações sem
replicação por definição. Não execute cenários simultaneamente nos mesmos bancos.

Depois dos cenários, gere a consolidação local (Python 3.12+, sem banco ou Elastic):

```bash
bash scripts/relatorio.sh
# Para outra coleção isolada de evidências:
bash scripts/relatorio.sh --resultados /caminho/resultados
```

O [relatório da PoC](resultados/relatorio.md) usa só a última execução de cada
`resultados/c1/meta.json` até `resultados/c10/meta.json`; os históricos e seus CSVs/logs ficam
preservados locais e ignorados — só o resumo (relatorio.md, recomendacao-humana.md, meta.json
e arquivos pequenos de diffs/recuperação) é versionado. Valores de cenários inválidos ou ausentes ficam vazios, com estado
e motivo explícitos. `nao-executado.md` na pasta de um cenário permite justificar sua ausência;
isso não aprova o critério. Execuções reduzidas de gates não comprovam o experimento completo.
Reprovações trazem hipóteses e ajustes para investigação, sem alterar os vereditos dos runners.

A recomendação exige autoria humana: analise os números e riscos e escreva
`resultados/recomendacao-humana.md` com autor, data e decisão (seguir, ajustar indicando o quê,
ou descartar). Gere novamente para incorporar esse texto sem sobrescrevê-lo. Enquanto ele não
existir, o relatório mantém `RECOMENDACAO_HUMANA_PENDENTE`; o fechamento da PoC continua pendente.
Aceitar/mover o ADR e confirmar o limite C10 com o time do legado são decisões externas.

Gate do consolidador: `bash scripts/testes/test-v15-relatorio.sh` (quatro verificações sintéticas).
Check dos scripts: `fd -e sh . scripts -X shellcheck -S warning`. Um gate aprovado valida o
consolidador; os resultados reais e as ressalvas no relatório determinam o que foi demonstrado.

Exportação opcional para Elastic/Kibana (V-14) roda **somente no laptop**. CSVs são o registro
oficial; indisponibilidade do exportador não interfere em cenários, workers ou critérios.

Compile com `./mvnw -B -q package`. Mantenha as chaves `ELASTIC_HOST`, `KIBANA_HOST`,
`KIBANA_ELASTIC_USER`, `KIBANA_ELASTIC_PASSWORD` somente no `.env` ignorado do laptop. O Java
lê esse arquivo diretamente; não use `source`, `-D`, argumentos, imagens ou cópias remotas para
transportar credenciais. Valores entre aspas simples/duplas são aceitos, sem interpolação.

Para instalar/reinstalar o painel: `bash scripts/exportar.sh painel`. A definição versionada
fica em `src/main/resources/poc-kibana.ndjson`: data view, seis visualizações e painel com IDs
fixos. Importação usa `overwrite=true`, no espaço exclusivo `poc-replicacao-bdad`.

Durante um cenário, em outro terminal do laptop, use o identificador exato de sua execução,
o nome do contêiner/worker e os caminhos reais (o runner imprime/cria os resultados em
`resultados/C2/<execucao>/`; `docker ps --filter name=poc-worker` identifica o worker):

```bash
export EXPORTADOR_CENARIO=C2
export EXPORTADOR_EXECUCAO=<execucao>
export EXPORTADOR_FONTES="infra-server:<poc-worker-container>:/resultados/worker-<worker-id>.csv
local:resultados/C2/<execucao>/recursos.csv"
bash scripts/exportar.sh seguir
```

Cada linha de `EXPORTADOR_FONTES` é uma fonte independente: `host:container:/resultados/arquivo.csv`,
`host:/caminho/arquivo.csv` (hosts permitidos: `infra-server`, `desenv-server`) ou
`local:caminho/arquivo.csv`. O exportador executa `ssh tail -F`/`docker exec tail -F` ou `tail -F`
local, sem enviar o `.env` nem suas variáveis aos subprocessos. Encerre com Ctrl+C. Em caso de
falha HTTP ele encerra com código 1 e mensagem genérica; reinicie para reler CSVs. IDs de
documentos determinísticos tornam esse replay idempotente. Nenhuma linha de CSV é alterada.

Abra o endereço `KIBANA_HOST` seguido de
`/s/poc-replicacao-bdad/app/dashboards#/view/poc-replicacao-bdad-painel` e filtre `cenario` e
`execucao`. O painel atualiza a cada 5 s e mostra eventos/s, p50/p95/p99, pendentes, lag,
CPU e sessões de `poc-legado`/`poc-novo`. Os percentis são os das janelas do worker, com máximo
por intervalo exibido; não são percentis globais recalculados. O coletor de recursos do runner
amostra a cada 30 s; a latência da exportação é medida a partir de cada amostra escrita.

Limpeza opcional e repetível, sem derrubar bancos:

```bash
bash scripts/derrubar.sh --limpar-elastic <execucao>
```

Remove só o índice daquela execução (prefixo + identificador codificado em hexadecimal) e os
oito objetos fixos do painel. Outras execuções mantêm seus índices; reinstale o painel se
precisar visualizá-las. O espaço permanece vazio para evitar remover objetos acrescentados
manualmente. `derrubar.sh` sem argumentos mantém o teardown e inventário dos contêineres.

**Pendência de segurança aceita:** em 2026-11-01 o usuário autorizou a conta da PoC com
`unrestricted_write`. O preflight apenas registra essa condição. A restrição de escrita/remoção
ao namespace é feita por código, incluindo recusa de aliases; esta conta não permite provar
uma recusa de escrita pelo Elastic fora da PoC. Nenhum usuário, role ou serviço é alterado.

Gate V-14: `./mvnw -B -q test -Dtest='V14*Test,V14*IT'` (3 testes locais + 4 de integração,
sem skips). Os IT leem o `.env` em memória e comparam segredos silenciosamente. Usam o espaço
`poc-replicacao-bdad-teste`; o teste do processo real exercita também o painel da PoC e o limpa.
A validação visual durante C2 completo permanece na V-15.
