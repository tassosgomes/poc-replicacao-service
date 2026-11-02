Execute no laptop, com Python 3.12+, Bash, SSH/Docker dos dois hosts configurados, `.env` ignorado,
os dois bancos da PoC carregados e nenhum outro worker/gerador ativo. O `.env` nunca é carregado.
Gere o jar da PoC (`poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar`, que inclui o subcomando `worker`) com `./mvnw -B -q package` antes de executar:

```bash
bash scripts/cenarios/c1-carga-inicial.sh
bash scripts/cenarios/c2-regime.sh
bash scripts/cenarios/c3-teto.sh
bash scripts/cenarios/c4-rajada.sh
bash scripts/cenarios/c5-transacao-longa.sh
bash scripts/cenarios/c6-queda-worker.sh
bash scripts/cenarios/c7-duas-replicas.sh
bash scripts/cenarios/c8-trigger-desligado.sh
bash scripts/cenarios/c9-threads.sh
bash scripts/cenarios/c10-custo-legado.sh
```

Cada chamada cria apenas containers próprios `poc-*`, limitados a 2 CPUs/1 GiB (gerador no
desenv-server; worker/reconciliação no infra-server). Somente esses containers são encerrados e
removidos. Os bancos e os demais serviços permanecem sob vigilância. Falha operacional retorna
exit 2; resultado reprovado/inválido retorna 1; aprovado retorna 0. `SEM_CRITERIO` identifica uma
medição descritiva. Ausência de dados não aprova uma medição.

O jar é transferido uma vez por host e conteúdo, para uma imagem local
`poc-cenarios:<sha256-do-jar>`, reaproveitada nas próximas execuções. Os containers temporários
de preparação também são removidos; somente as imagens cacheadas permanecem. A cópia inicial
mantém a vigilância ativa e não instala arquivos ou ferramentas no host.

Os resultados ficam em `resultados/<cenario>/<execucao>/`; `meta.json` na pasta do cenário é a
cópia atômica da última execução. `diretorio` localiza os artefatos e os caminhos internos são
relativos à execução. Reexecutar preserva todos os resultados anteriores. A pasta contém logs,
CSV por worker/gerador, latências individuais, recursos, inventários/carga, snapshots completos,
diferenças e reconciliação `MODO=completo DRY_RUN=true`. Resultados de produção são versionáveis;
o gate guarda suas evidências temporárias em `.tsg-flow/v11-gate-*/`.

Parâmetros de duração permitem o gate curto sem alterar os limites dos critérios:

| Variável | Padrão | Uso |
|---|---:|---|
| `DURACAO_SEG` | 600 | C2 e regime de C4/C5/C9 |
| `DEGRAU_SEG` | 300 | Duração de cada degrau C3 |
| `TAXA` | 1000 | Taxa do regime |
| `C3_TAXAS` | 500,1000,2000,4000 | Escada; mudar não permite aprovar o teto sem medir 2000 |
| `RAJADA_APOS_SEG` / `RAJADA_LINHAS` | 120 / 50000 | C4 |
| `OBSERVACAO_RECUPERACAO_SEG` | 125 | Observação após o commit; alvo continua 120 s |
| `PATAMAR_JANELA_SEG` | 60 | Janela de amostras do patamar de pendentes do C4 |
| `PREPARAR_REPLICA` / `PREPARAR_TIMEOUT_SEG` / `PREPARAR_THREADS` | false / 3600 / 4 | Antes do cenário, executa a carga inicial idempotente (preserva UUID, nada é apagado) para partir de réplica consistente |
| `TX_APOS_SEG` / `TX_ABERTA_SEG` | 5 / 30 | C5 |
| `QUEDA_APOS_SEG` / `REINICIO_APOS_SEG` | 180 / 240 | C6: SIGKILL e início da nova instância, a partir do começo da carga |
| `SEGUNDA_APOS_SEG` | 60 | C7: início de B; A recebe SIGKILL em `QUEDA_APOS_SEG` após a divisão 24/24 |
| `LEASE_SEG` | 15 | Lease de C6/C7/C8; gate usa 12 s, conforme decisão de V-07 |
| `TRIGGER_DESLIGADO_SEG` | 60 | C8: duração da carga de updates de obra sem captura |
| `DESDE_MIN` / `JANELA_MIN` | 60 / 5 | Janela do delta de C8; ambos zero exercitam deliberadamente uma janela vazia |
| `VIGILANCIA_SEG` | 30 | Intervalo da vigilância dos serviços existentes |
| `DRENAGEM_TIMEOUT_SEG` | 180 | Limite da espera por pendentes=0 |
| `RESULTADOS_BASE` | resultados | Destino dos resultados |
| `POC_JAR` | poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar | Jar a copiar |
| `THREADS`, `SEMENTE`, `PCT_INSERT`, `PCT_DELETE`, `HOT_PCT` | 8, 1, 5, 1, 10 | Gerador de regime |

O início aguarda as 48 partições do worker, drena pendências e exporta um snapshot novo. Inventário
e recursos são coletados durante a execução, a cada 30 s; a vigilância compara identidade,
reinícios, instante de partida, estado running e saída de healthy. Relógios são medidos no início,
a cada minuto e no fim, pelo ponto médio do RTT entre o laptop e cada host, usando uma âncora
monotônica comum que não depende dos saltos do relógio de parede do WSL. Os tempos de espera
usam relógio monotônico. Identidades/digests das imagens, SHA-256 do jar e limites ficam no meta.
Nenhuma configuração de credenciais nem `docker inspect` completo é exportado.

O p95 usa nearest-rank sobre os eventos efetivamente confirmados, incluindo eventos lentos.
`EVENTOS_CSV=true` habilita essa evidência no worker do cenário, sem alterar o CSV legado de janelas.
O p95 do C2 usa somente sua janela de geração, excluindo a drenagem inicial. A tendência usa
regressão linear nos últimos 180 s do degrau e exige inclinação positiva e médias estritamente
crescentes em três blocos consecutivos para classificar crescimento sustentado. Em execuções
curtas a janela é 60% do período; menos de três amostras não produz aprovação. O meta registra
janela, número de amostras e inclinação. O teto do C3 só aprova com degrau válido de 2000 ev/s;
`GERADOR_SATURADO` interrompe a escada e preserva as medições válidas.

No C4, o patamar de pendentes é a **banda de regime**, medida com várias amostras do worker (uma
por 5 s) tomadas entre o início da carga de fundo e o commit da rajada, limitadas aos últimos
`PATAMAR_JANELA_SEG` (60 s). O commit é o instante registrado pelo gerador; antes dele as linhas da
rajada não são visíveis ao worker, e só os relógios do worker/gerador são usados (nunca o do laptop).
O patamar é o **máximo** dessas amostras (a mediana também é gravada em `medidas.patamar`, com
janela e número de amostras). Com carga de fundo, `pendentes` oscila naturalmente; por isso uma única
leitura instantânea não serve. Menos de 3 amostras não definem patamar: a recuperação não é
calculada e o critério sai `INVALIDO`, nunca `REPROVADO`. A recuperação usa a primeira janela completa
de 5 s após o commit com pendentes <= patamar e p95 <= 5000 ms, enquanto o regime ainda está ativo; o
tempo parte do commit. Janelas
parciais de encerramento não comprovam recuperação. No C5, o gerador identifica o evento de sua
própria transação antes de confirmar; o runner correlaciona esse ID com o CSV do worker, separa
sua latência e mede o tempo após o commit. O p95 das demais transações usa os eventos criados
durante a abertura. Isso evita classificar toda latência >5 s como transação longa. Integridade é
verificada pelo estado final, sem exigir igualdade de contagens que ignoraria o coalescing.

C9 executa C2 e C3 nos dois modos na mesma chamada, drenando antes de trocar de worker. A tabela
`comparacao.csv` referencia os mesmos dados dos degraus, com taxa, p95 e validade. A comparação
tem `SEM_CRITERIO` por não haver alvo de superioridade entre modos; os critérios dos cenários
repetidos e de integridade continuam calculados.

O gate `bash scripts/testes/test-v11-cenarios.sh` usa os bancos reais e durações reduzidas. Sua primeira
execução usa `PREPARAR_REPLICA=true` para completar a réplica deixada incompleta por ITs anteriores
(carga inicial idempotente e protegida por versão, sem apagar dados), e exige `divergencias = 0`. Somente
o container que ele próprio cria com nome `v11-servico-teste-*` é parado para exercitar a vigilância.
O gate verifica o comportamento do runner e a coerência de seus vereditos. Se a réplica já estiver
incompleta, a reconciliação completa deve reprovar o cenário; o teste exige esse `REPROVADO` e
exit 1 coerentes, sem esconder divergências nem executar C1 para reparar a massa. Erro operacional,
medição inválida inesperada ou evidência ausente reprovam o gate. Passar o gate não comprova que
as execuções completas da PoC foram aprovadas; elas pertencem à V-15.

C6 registra o container morto por `docker kill -s KILL` (exit 137), o início do reinício e a primeira
janela completa durante a carga em que `lag_ms <= 5000`. Informa o tempo desde a queda e desde o
reinício. A carga dura ao menos até 45 s após o reinício para observar a recuperação. Perda e
duplicidade são julgadas pelo estado final completo e pela preservação dos UUIDs, sem exigir
igualdade entre contagens de eventos que ignoraria o coalescing.

C7 espera a divisão 24/24 no banco antes da queda e exige também a divisão nos CSVs dos workers.
O failover usa o relógio do banco: desde antes do KILL até B possuir os 48 leases válidos e
registrar uma nova marca `all 48 owned partitions active`. O limite permanece 20 s; o CSV de 5 s
comprova a tomada e a soma das partições (<=48), sem compor a duração. `leases.csv` e os logs
preservam essas evidências. Com o lease padrão de 15 s o limite pode ser reprovado; não é
reduzido automaticamente pelo runner. O gate declara 12 s, como V-07.

C8 drena a réplica e para o worker, desabilita somente `trg_obra_captura`, gera updates somente
em `OBRA_MUSICAL` (sem inserts/deletes) e encerra o gerador antes de religar o trigger. A contagem
é de chaves distintas no CSV do gerador, preservadas em `linhas-afetadas.csv`; a verificação
completa antes do delta confere o mesmo conjunto. Executa delta com `DRY_RUN=false`, inicia o
worker e verifica novamente o estado completo. O meta informa afetadas, reenfileiradas,
corrigidas e divergências finais. O trigger é religado em `finally`, inclusive em interrupções.

O gate `bash scripts/testes/test-v12-falhas.sh` executa quatro verificações reais com durações
reduzidas. Primeiro prepara a réplica com a carga inicial idempotente, antes de tirar os snapshots
ou injetar qualquer falha. Exige aprovação de C6, C7 e C8; depois repete C8 com
`DESDE_MIN=0 JANELA_MIN=0` e exige `REPROVADO` com todas as chaves divergentes listadas. As
evidências ficam em `.tsg-flow/v12-gate-*/`. Esse último caso deixa divergências deliberadas na
réplica, com captura habilitada e sem workers ativos; o preparo inicial da próxima execução
permite repetir o gate. Nenhum reparo posterior é usado para alterar o veredito desse caso.

C1 trunca somente as três tabelas de negócio do banco novo (preserva as 48 partições), executa
`carga-inicial` com captura habilitada e então inicia o worker, drena e compara contagens e dados
por reconciliação completa em `DRY_RUN`. `medidas.carga_inicial` guarda linhas/s por tabela e tempo
total extraídos do log. Os snapshots para conferir UUIDs começam após a carga, pois o cenário
recria os IDs ao truncar. O padrão cobre todos os códigos; `C1_CODIGO_MAX` permite uma execução
reduzida, com limite exclusivo aplicado tanto à cópia quanto às contagens e à reconciliação.
`operacao_especial.escopo` identifica explicitamente esse recorte; ele não comprova integridade
fora da faixa. C1 reduzido deixa a réplica parcial; para a execução completa use o padrão.

C10 executa pelo menos três pares alternados (captura ligada/desligada), primeiro sem a consulta
de métricas e depois com ela. Não executa worker durante as medições, isolando o custo no legado.
O gerador usa a mesma taxa, duração, semente, threads e mix nos quatro modos; usa somente updates
(`PCT_INSERT=0 PCT_DELETE=0`) para manter a população de chaves estável entre repetições. Somente
os três triggers `*_captura` são alternados; `*_updated` são conferidos habilitados em cada fase.
A captura é restaurada inclusive em erro/interrupção, após parar os geradores da execução.

No modo com métricas, o coordenador consulta `COUNT(*), MIN(criado_em)` de `evento_replicacao`
no início e a cada 5 s durante a carga, em uma thread independente da vigilância por SSH.
Essa thread é encerrada e aguardada antes de concluir a fase, inclusive em erro/interrupção.
No modo sem métricas não consulta pendentes dentro dessa janela.
O meta guarda as consultas e seu tempo monotônico incluindo transporte SSH/conexão SQLPlus;
esse tempo não é apresentado como tempo SQL puro. A diferença entre as médias de transação
com/sem consultas quantifica sua interferência em cada estado de captura. Esse experimento
mede a consulta de backlog, não o custo de todo o monitor JDBC do worker. A vigilância de
serviços/relógios continua igual nos dois modos. O tamanho do backlog antes/depois de cada fase
é registrado, pois acumula eventos durante o experimento e pode afetar o custo observado.

`medidas.custo_legado` traz médias, amostras, desvio padrão amostral, mínimo/máximo, overhead
absoluto e percentual da captura em ambos os modos e custo das consultas. O critério principal
usa a comparação **sem métricas**, para não incluir essa interferência. Padrões:
`C10_REPETICOES=3` (mínimo 3 por modo), `C10_LIMITE_ATIVO=true`, `C10_LIMITE_PCT=15` e
`C10_LIMITE_MS=5`. Exige simultaneamente os dois limites inclusivos, segundo critério proposto
(Q-T3 ainda pendente). `C10_LIMITE_ATIVO=false` preserva os números e produz `SEM_CRITERIO`,
exit 0; taxa abaixo de 98%, ausência de consultas no modo com métricas ou evidência incompleta
continuam invalidando a execução. Use `DURACAO_SEG` maior que 5 s para observar consultas.
As alterações sem captura não são reparadas por C10; a integridade da réplica é explicitamente
não aplicável neste cenário. Prepare/reconcilie a réplica antes do próximo cenário de replicação.

O gate `bash scripts/testes/test-v13-c1-c10.sh` executa três verificações reais: C1 com até 2000
chaves por tabela; C10 com 12 s por fase e três repetições por modo; nova execução C10 com o limite
desligado. Confere os números contra os logs e o veredito contra os dois limites, aceitando que
a medição curta em hosts compartilhados produza `REPROVADO`. Verifica também restauração dos
triggers e ausência de containers abandonados. As evidências ficam em `.tsg-flow/v13-gate-*/`.
A execução completa e sua consolidação pertencem à task 16.0.
