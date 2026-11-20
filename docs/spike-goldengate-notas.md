
## Hub e automação

Data: 2026-11-19. Host: infra-server (192.168.0.11).

### gg-novo (destino)

- `gg-novo` sobe e fica healthy em segundos (`gvenzl/oracle-free:23-slim-faststart`, 2 CPU, 3 GiB, porta 1524, volume `gg-novo`).
- A role `OGG_APPLY` existe no 23ai Free (também `OGG_APPLY_PROCREP`, `OGG_CAPTURE`, `OGG_SHARED_CAPTURE`). `sql/gg/novo/00_goldengate.sql` cria `ggadmin` com ela.
- `DBMS_CRYPTO` não é público; o 00 concede EXECUTE a `replica` (usado por `fn_uuid_v7`).
- UUIDv7 por trigger validado: versão 7, variante 10, ordenável, mesmo layout do `Uuid7.java`
  (contador de 12 bits via `seq_uuid7`); update não troca `id` (mesmo tentando setar outro); `aplicacao_log` registra I e U.

### gg-hub (goldengate-free): BLOQUEADO no pull (SUPERADO: ver "Fase 0 concluída" abaixo, imagem goldengate-oracle-free)

`docker pull container-registry.oracle.com/goldengate/goldengate-free:latest` (e `:23.9.0.0.2`, e
`goldengate/goldengate-microservices:latest`) no infra-server falha:

    failed to authorize: failed to fetch anonymous token: unexpected status from GET request to
    https://container-registry.oracle.com/auth?scope=repository%3Agoldengate%2Fgoldengate-free%3Apull&service=Oracle+Registry: 401 Unauthorized

O registry exige login (conta Oracle) e aceite de licença no portal. Não foi contornado.
Nada foi criado para `gg-hub` (container/volume). Automação (REST dos Microservices, `adminclient`,
só UI) permanece NÃO verificada.

### Conectividade

- infra-server -> legado (desenv-server 192.168.0.5:1523): TCP OK (`gg-legado` rodando).
- hub -> gg-novo: não testado (sem hub); gg-novo publicado em 192.168.0.11:1524.


---

## Fase 0 concluída (2026-11-19/20): spike ponta a ponta

Versão: GoldenGate Free 23.26.3 (imagem `container-registry.oracle.com/goldengate/goldengate-oracle-free:latest`,
já presente no infra-server). Origem e destino: Oracle Free 23ai (`gvenzl/oracle-free:23-slim-faststart`).

### Hub (`gg-hub`)

- Entrypoint `/usr/local/bin/deployment-main.sh`. Variáveis: `OGG_ADMIN_PWD` (senha do admin; se ausente gera aleatória
  e imprime no log), `OGG_ADMIN` (padrão `oggadmin`), `OGG_DEPLOYMENT` (padrão `Local`). Volumes: `/u02` (deployment,
  persistente) e `/u03` (temporários). Portas expostas 8443 (nginx HTTPS, certificado autoassinado) e 8080 (HTTP).
- `scripts/gg/subir-hub.sh`: `--cpus=2 --memory=4g`, volumes `gg-hub` e `gg-hub-tmp`, `-p 18443:8443`. Senha lida de
  `~/.gg-hub-senha` no laptop (chmod 600) e enviada por stdin/ssh, fora do repo. Fica healthy em ~35 s.
- Um único nginx expõe Service Manager e os serviços do deployment `Local` (Admin, Distribution, Receiver, Performance
  Metrics): `https://192.168.0.11:18443/services/Local/adminsrvr/v2/...` (REST do Admin Service).

### Caminho de automação: REST (principal) + adminclient (exceção)

- **REST responde e cobre a configuração toda**: credenciais (`POST /credentials/OracleGoldenGate/<alias>`),
  `ADD SCHEMATRANDATA` (`POST /connections/OracleGoldenGate.<alias>/trandata/schema`), checkpoint table e heartbeat
  (`/connections/.../tables/checkpoint|heartbeat`), criação de Extract e Replicat (`POST /extracts/<n>`, `/replicats/<n>`,
  `PATCH` para trocar o param file), start/stop (`POST /commands/execute {"name":"start","processName":"..."}`),
  status/lag (`GET /extracts|replicats/<n>/info/status`) e relatórios (`/info/reports/<n>.rpt`).
- **Exceção**: `START REPLICAT ... AFTERCSN <scn>` não achei como expressar no REST: o `/commands/execute` devolve
  `OGG-12111 JSON element '/' does not match any schemas` para todas as variações de `afterCSN` testadas (7 formas).
  Usa-se `adminclient` (existe em `/u01/ogg/bin/adminclient` no container) via `gg/adminclient.sh`
  (`docker exec -i gg-hub adminclient`, `CONNECT https://localhost:8443 DEPLOYMENT Local AS oggadmin PASSWORD ... !`).
  A senha vai por stdin e é mascarada na saída. Tudo é scriptável; nada foi feito só pela UI. Um caminho a aprofundar:
  a UI usa o mesmo endpoint, o schema exato do start com CSN pode ser capturado do tráfego do navegador se quisermos 100% REST.
- Arquivos: `gg/lib.sh` (helper `gg METODO CAMINHO JSON`), `gg/criar-pipeline.sh <SCN>`, `gg/adminclient.sh`,
  `gg/params/EXTBDAD.prm`, `gg/params/REPBDAD.prm` (final, com CDR), `gg/params/REPBDAD.sem-cdr.prm`,
  `gg/params/REPCO.prm` (coordenado). Testes: `sql/gg/testes/*.sql`, `scripts/gg/latencia.sh`, `scripts/gg/rajada.sh`,
  `scripts/gg/q.sh`, `scripts/gg/carga-inicial.sh`, `sql/gg/novo/02_carga_inicial.sql`.
- Nota de formato: o `ggadmin` do PDB basta para Extract integrado (não foi preciso `c##ggadmin`); a `registration`
  do Extract fez `REGISTER` no PDB sem pedir conexão no root.

### Parâmetros finais

Extract `EXTBDAD` (integrado, `TABLE bdad.*;`, trail local `ea`, `TRANLOGOPTIONS INTEGRATEDPARAMS (MAX_SGA_SIZE 128)`).
Sem Distribution Path: hub único e local, não há rede entre hubs no lab; o Replicat lê o trail local `ea`.
No desenho de produção continua havendo path (hub da origem -> hub do destino). O Free expõe Distribution Service
(`distsrvr` está `running`), mas não foi necessário testá-lo.

Replicat `REPBDAD` (clássico/nonintegrated, checkpoint `ggadmin.ggschkpt`): `DBOPTIONS NOSUPPRESSTRIGGERS`,
`REPERROR (DEFAULT, ABEND)`, `UPDATEDELETES`, e por tabela
`MAP bdad.X, TARGET replica.X, KEYCOLS (codigo), COMPARECOLS (ON UPDATE KEYINCLUDING (origem_updated_at)),
RESOLVECONFLICT (UPDATEROWEXISTS, (DEFAULT, USEMAX (origem_updated_at))),
COLMAP (USEDEFAULTS, origem_updated_at = updated_at, excluido = @IF(@STREQ(@GETENV('GGHEADER','OPTYPE'),'DELETE'), 1, excluido),
origem_commit_ts = @GETENV('GGHEADER','COMMITTIMESTAMP'))`. `id` e `replicado_em` não mapeados (trigger do destino).
Nota: em `USEMAX` e `COMPARECOLS` o nome é o da coluna **do destino** (`origem_updated_at`); com `updated_at` o Replicat
abendou com `OGG-00366 Invalid column specified in USEMAX`.

### O que funcionou no Free

| Item | Resultado |
|---|---|
| Hub sobe, REST/adminclient | OK |
| `ADD SCHEMATRANDATA bdad` com ALLCOLS pelo PDB | OK (3 tabelas preparadas) |
| Extract integrado no PDB com 2 GB | OK, `MAX_SGA_SIZE 128`: uso real 19-20 MB de 127 MB alocados (`v$goldengate_capture`) |
| Replicat clássico + `NOSUPPRESSTRIGGERS` | OK: triggers do destino dispararam (UUIDv7, `replicado_em`, `aplicacao_log`) |
| `UPDATEDELETES` + `@IF` no COLMAP | OK: DELETE físico vira `excluido = 1`, linha e UUID preservados (titular 11, fonograma 5 também com CDR ativo) |
| CDR `RESOLVECONFLICT (UPDATEROWEXISTS, (DEFAULT, USEMAX (...)))` | OK: aceito pelo Free/23ai. Teste: destino com `origem_updated_at` +1 h, update da origem descartado (linha 30 manteve o valor), update normal da linha 31 aplicado |
| Replicat **coordenado** (`mode coordinated, maxThreads 4`, `THREADRANGE (1-4, codigo)`) | OK: 4 threads spawnadas, aplicou os updates. Nome de Replicat coordenado tem no máximo 5 caracteres (`REPCO`) |
| Parallel Replicat | NÃO TESTADO (documentado como não suportado no Free com Oracle Free) |
| Carga inicial por SCN | OK: 7000 linhas `AS OF SCN 2336616` via DB link a partir do destino (`sql/gg/novo/02_carga_inicial.sql`), UUID gerado pelo trigger, depois `START REPLICAT ... AFTERCSN 2336616` (`OGG-01373 User requested start after CSN`). GG Initial Load não foi usado |

### Números medidos

Reconciliação final (7.000 linhas + testes): diferenças = só o esperado: 3 linhas excluídas por DELETE físico mantidas com
`excluido = 1` (titular 10, 11, fonograma 5) e a linha 30, mantida de propósito pela guarda CDR. 0 divergência inesperada.
UUIDs de titular 10 e 20 idênticos antes/depois dos updates.

Relógios: offset desenv-server - infra-server = -1,3 ms, incerteza 5,0 ms (`scripts/medir-relogios.py`), desprezível
frente às latências abaixo. Observação: `COMMITTIMESTAMP` do GGHEADER tem resolução de 1 s (hora de commit do redo Oracle),
por isso a latência foi medida de duas formas: `aplicado_em - origem_updated_at` (hora do DML, microssegundos, ~commit) e
`aplicado_em - origem_commit_ts` (resolução de 1 s, tende a superestimar até 1 s).

Latência, 200 updates em transações individuais, ~2 por segundo, Replicat clássico:

| Métrica | min | p50 | p95 | max |
|---|---|---|---|---|
| aplicado - hora do DML (s) | 1,39 | 2,36 | 3,29 | 4,49 |
| aplicado - commit_ts (s, res. 1 s) | 1,74 | 2,86 | 3,87 | 4,96 |

Mesmo teste com Replicat coordenado (4 threads): DML p50 3,10, p95 4,04, max 4,32; commit_ts p50 3,86, p95 4,90, max 5,00.
A latência é dominada pelo Extract integrado (mining do redo + entrega), não pela aplicação: o lag do heartbeat
(`ggadmin.gg_lag`, `EXTBDAD ==> REPBDAD`) em repouso fica em torno de 3 s. Com poucas transações por segundo o p95
(3,3 s a 4,0 s) já usa 66% a 80% do critério de 5 s: é o número a vigiar nos cenários de regime. Não há ainda dado sob 1.000 ev/s.

Rajada de 4.000 updates com commit individual, uma sessão (a origem produz ~1.050 ops/s): clássico aplicou 3.999 em
3,6 s (1.121 ops/s), coordenado 3.999 em 3,8 s (1.054 ops/s). Os dois acompanham a origem; o teto do Replicat
**não foi atingido**, então o teste de teto (C3) precisa de carga maior e paralela. Os 3.999 e não 4.000 são porque o
fonograma 5 foi deletado antes.

Parada e retomada: Replicat parado, 100 updates na origem, religado: 100/100 aplicados e 100 entradas no log, sem perda
nem duplicação. `docker kill gg-hub` com 100 updates no meio: após `docker start`, Extract e Replicat ficam `abended`
(não reiniciam sozinhos); depois de `START` pela REST, retomaram do checkpoint: 100/100 aplicados, uma aplicação cada
(o Extract gravou novo arquivo de trail `ea000000001`). Para a Fase 1: configurar autostart dos processos ou fazer o start no `subir.sh`.

### Recursos (`docker stats`, limite 2 CPU)

| Container | Repouso | Durante rajada de 4.000 updates |
|---|---|---|
| gg-legado (3 GiB) | CPU 4,5% a 5,2%, 2,20 GiB | CPU até 46% (pico em 1 amostra), 2,20 GiB |
| gg-novo (3 GiB) | CPU 4,7% a 6,5%, 2,37 a 2,43 GiB | CPU até 82%, 2,42 GiB |
| gg-hub (4 GiB) | CPU 7% a 9%, 0,97 a 1,02 GiB | CPU 8% a 18% (50% em uma amostra), 0,97 GiB |

Log mining no gg-legado: streams pool 256 MB (de SGA), `v$goldengate_capture`: 19,5 a 20,2 MB usados de 127,1 MB
alocados, sem pressão. O hub fica em ~1 GiB: 4 GiB de limite é folgado, 2 GiB provavelmente bastam (a confirmar sob carga).
Os percentuais de CPU são de `docker stats` (100% = 1 CPU), amostras de 4 s: indicativos, não C10.

### Decisões e achados para a Fase 1

- Replicat da PoC: **clássico** como base; o coordenado funciona e fica para o C3/C9.
- `ADD SCHEMATRANDATA ... ALLCOLS` é necessário para o CDR (before image completa) e aumenta o volume de redo/trail:
  medir no C10.
- A carga inicial não zera o `aplicacao_log` sozinha: `02_carga_inicial.sql` faz `truncate` depois do load para que a
  carga não entre na latência.
- Reaplicar `00_goldengate.sql` do destino agora também concede `create database link` a `replica`.
- Ordem de criação: Extract iniciado antes do SCN; carga AS OF SCN; Replicat com `AFTERCSN`. `gg/criar-pipeline.sh` faz
  esta sequência (a carga é chamada fora, `scripts/gg/carga-inicial.sh`, entre o Extract e o Replicat; rode os passos
  na ordem do script, ajustando se for automatizar o encadeamento).
- O helper `q.sh`/`sqlplus -s` com here-string de uma linha (`"...; exit"`) só executa o primeiro comando: usar arquivo ou
  várias linhas.
- Não testado: Distribution Path, Parallel Replicat, Extract parado 60 s (C8), carga sustentada de 1.000 ev/s ou mais.

### Go/No-go para a Fase 1

**GO**, com ressalvas medidas. Todos os 3 pontos bloqueantes do plano estão respondidos com evidência: (1) a imagem
Free sobe e tem REST completa e adminclient, a automação é viável (uma única exceção, `AFTERCSN`, resolvida com adminclient);
(2) Extract integrado funciona no PDB com 2 GB e usa ~20 MB de streams pool; (3) todos os requisitos funcionais do desenho
passaram: UUIDv7 por trigger, `KEYCOLS`, `COLMAP` com commit timestamp, `NOSUPPRESSTRIGGERS`, exclusão lógica por
`UPDATEDELETES`, CDR `USEMAX`, carga por SCN e retomada sem perda.
Ressalvas para a Fase 1: (a) a latência base de ~2,4 s (p50) e ~3,3 s (p95) já consome boa parte dos 5 s do critério
com baixa carga; o regime a 1.000 ev/s é o risco principal e precisa ser medido cedo (Fase 3/C2); (b) `COMMITTIMESTAMP` tem resolução
de 1 s, então use `origem_updated_at` para a latência por evento ou aceite ±1 s; (c) processos não sobem sozinhos
depois de um `docker kill`: tratar no `subir.sh`; (d) o teto do Replicat clássico e do coordenado ainda é desconhecido.


---

## Fase 1: ambiente reprodutível (2026-11-19/20)

Lab GG reconstruído do zero duas vezes com a massa completa (200k/500k/1M), processos com autostart/autorestart.
`poc-legado`/`poc-novo` continuaram parados e com containers/volumes idênticos (inventário comparado no `derrubar.sh`).

### Comandos

| Comando | O que faz |
|---|---|
| `scripts/gg/subir.sh` | Pré-requisitos (`.env` ignorado, SSH, Docker, NTP, RAM ≥ 4 GB, disco ≥ 10 GB, portas 1523/1524/18443, relógios ≤ 100 ms, `poc-*` parados e volumes existentes, imagem do hub presente), inventário em `.tsg-flow/gg-inventario`, gg-legado e gg-novo (2 CPU/3 GiB), esquema + massa, hub (2 CPU/4 GiB), `gg/criar-pipeline.sh base`, carga por SCN, `gg/criar-pipeline.sh replicat <SCN>`, contagens e estado. `GG_MASSA_REDUZIDA=1` volta à massa do spike. Tempos em `resultados/gg/c1/subir-tempos.json` |
| `scripts/gg/derrubar.sh` | Remove só `gg-legado`, `gg-novo`, `gg-hub` (`rm -fv`: leva o volume anônimo `/u01/ogg/scripts` do hub) e os volumes `gg-legado`, `gg-novo`, `gg-hub`, `gg-hub-tmp`; compara com o inventário (falha se `poc-*` mudou: container, id, instante de parada, volume) |
| `scripts/gg/testar-autostart.sh restart\|kill` | Teste de autostart/autorestart (abaixo) |
| `scripts/gg/reconciliar.sh` | Reconciliação completa por SQL (hash por faixa) |
| `scripts/gg/snapshot-ids.sh [c1] [nome]` | Snapshot `(tabela,codigo,id)` do gg-novo |
| `gg/criar-pipeline.sh base \| replicat <SCN> \| <SCN>` | O pipeline foi dividido em duas etapas para a carga entrar entre elas; `<SCN>` sozinho mantém o comportamento antigo |

`criar-pipeline.sh` não aborta em erro REST (só imprime `ERROR`); o `subir.sh` verifica `RUNNING` do Extract e do Replicat e que o SCN da carga é ≥ o SCN de registro do Extract.

### Autostart / autorestart (REST, sem adminclient)

Propriedade `managedProcessSettings` do Extract/Replicat no Admin Service (descoberta em
`/services/Local/adminsrvr/v2/metadata-catalog/extract`), aplicada na criação do Extract e por `PATCH` no Replicat depois do
`START ... AFTERCSN` (um autostart antes dele iniciaria o Replicat sem o SCN):

    {"managedProcessSettings":{"autoStart":{"enabled":true,"delay":0},
      "autoRestart":{"enabled":true,"onSuccess":false,"delay":5,"retries":9,"window":60,"disableOnFailure":false}}}

Teste (lab com massa completa, `scripts/gg/testar-autostart.sh`; tempo medido no infra-server, desde o retorno do comando docker):

| Cenário | REST responde | EXTBDAD e REPBDAD RUNNING | Resultado |
|---|---|---|---|
| `docker restart gg-hub` com 100 updates (1 a cada ~0,1 s) cobrindo a queda | 6,7 s | 12,2 s | 100/100 aplicados, 1 linha de `aplicacao_log` por código, origem == destino |
| `docker kill gg-hub`, 100 updates com o hub parado, `docker start gg-hub` | 5,6 s | 10,0 s | idem |

Primeira bateria (lab da massa reduzida, antes do rebuild): restart 10,0 s, kill/start 9,9 s, mesmos resultados. Nenhum `START`
manual. Fora do escopo: a política de restart do Docker (`--restart`) não foi ligada de propósito (um `docker kill` a
reiniciaria sozinho e o teste deixaria de testar o autostart); se o host reiniciar, o container do hub precisa de `docker start`.

### Tempos do `subir.sh` (massa completa, do zero; relógio do desenv-server, não do WSL2)

| Etapa | Execução 1 | Execução 2 |
|---|---|---|
| gg-legado e gg-novo saudáveis | 53,2 s | 37,6 s |
| legado: redo, archivelog/GG, esquema e massa 1,7 M linhas | 120,8 s | 121,8 s |
| destino: GG + esquema | 2,4 s | 2,2 s |
| hub saudável e REST | 36,7 s | 36,3 s |
| EXTBDAD RUNNING (após criar credenciais, trandata, heartbeat) | 8,5 s | 8,3 s |
| carga inicial por SCN (total) | 481,7 s | 487,1 s |
| REPBDAD RUNNING após o SCN | 2,2 s | 2,2 s |
| pipeline RUNNING (desde o hub pronto) | 492,4 s | 497,5 s |
| total | 705,6 s | 695,4 s |

O tempo da massa isolada não foi medido (a etapa do legado inclui reinício do CDB para ARCHIVELOG). Os archive logs
ficaram em ~1,5 GB (`/opt/oracle/oradata/FREE/arch`) após a massa na execução 1; não há ainda política RMAN de limpeza.

### Carga inicial por SCN (`INSERT ... AS OF SCN` via DB link, UUIDv7 por trigger; execução 2)

| Tabela | Linhas | Tempo (`set timing`) | Linhas/s |
|---|---|---|---|
| titular | 200.000 | 55,9 s | 3.577 |
| obra_musical | 500.000 | 139,5 s | 3.584 |
| fonograma | 1.000.000 | 288,7 s | 3.464 |
| total | 1.700.000 | 484,1 s | 3.511 |

Execução 1: 54,6 s / 141,3 s / 280,5 s = 476,3 s (3.569 linhas/s). Referência Java (C1, `poc-carga-inicial`): 109,9 s. A carga
GG por DB link com trigger de UUID e de log por linha é ~4,4x mais lenta que o C1 Java, não comparável como "carga do GG"
em si (o GG Initial Load não foi usado e a trigger `aplicacao_log` dobra o custo por linha; `truncate` ao fim). Não foi
tentado otimizar (paralelismo por faixa, `NOLOGGING`, desligar a trigger de log durante a carga). SCN da carga ≥ SCN de
registro do Extract nas duas execuções (2322820 ≥ 2321844; 2322830 ≥ 2321849).

### Integridade da massa completa

- Contagens iguais nos dois lados: titular 200.000, obra_musical 500.000, fonograma 1.000.000.
- **Java `reconciliacao`** (`MODO=completo DRY_RUN=true`, build `./mvnw -B -q -pl poc -am package -DskipTests`, jar rodado no
  laptop com `LEGADO_URL=jdbc:oracle:thin:@//192.168.0.5:1523/FREEPDB1`, `NOVO_URL=jdbc:oracle:thin:@//192.168.0.11:1524/FREEPDB1`,
  usuários padrão bdad/replica): funcionou no lab GG, **0 divergências** nas três tabelas, 19,8 s. Em dry-run ele não toca
  `evento_replicacao` (só o modo não-dry reenfileira), por isso não falhou pela ausência da outbox. Ele compara
  `updated_at` (legado) com `origem_updated_at` (réplica) e as colunas de negócio + `excluido`.
- **`scripts/gg/reconciliar.sh`** (SQL, independente do jar): por faixa de 100.000 códigos, contagem + dois `ORA_HASH`
  (sementes 0 e 1) somados das mesmas colunas: 20 faixas dos dois lados, **0 divergências**. Não é mais forte que o Java
  (também é hash de 32 bits); vale como segunda verificação sem depender do jar. Não houve teste negativo (induzir divergência).
- Snapshot de UUIDs (`scripts/gg/snapshot-ids.sh`): 1.700.000 linhas, 0 UUIDs duplicados, todos com versão 7/variante 10.
  CSV em `resultados/gg/c1/snapshots/apos-carga.csv` (86 MB, ignorado pelo `.gitignore`); `resultados/gg/c1/meta.json`
  guarda contagem, tamanho e sha256 (execução 2: `b401a261...5040`). Depois dos testes de autostart (200 titulares
  atualizados) o snapshot é byte a byte idêntico: update não troca `id`.

### Achados

- `docker rm -f gg-hub` deixa um volume anônimo (`/u01/ogg/scripts`, da imagem) órfão; o `derrubar.sh` agora usa `rm -fv`.
  Um órfão do lab do spike (criado em 2026-11-19 21:03, conteúdo `setup`/`startup` do GG) foi removido à mão.
- Ressalvas ainda abertas para a Fase 4: a carga de 1,7 M linhas leva ~8 min; política RMAN/alarme de disco dos archive logs
  não implementada; Distribution Path e Parallel Replicat não testados.


---

## Fase 3 e C2 curto (2026-11-20)

Lab GG com massa completa. Todas as medições a seguir usam o relógio dos bancos: latência por evento = `aplicado_em` (gg-novo) menos
`origem_updated_at` (hora do DML no legado, `updated_at`; `COMMITTIMESTAMP` tem resolução de 1 s). Offset de relógio
(`scripts/medir-relogios.py` com `lab-gg.env`): desenv-server menos infra-server = +0,03 ms (antes da calibração) e +1,35 ms (C2), incerteza ±5 ms:
desprezível frente a 100 ms (a latência é corrigida pelo offset no C2). Percentis nearest-rank.

### Ferramentas novas

| Arquivo | Uso |
|---|---|
| `scripts/gg/gerar.sh TAXA DUR DIR` | Gerador da PoC (jar, subcomando `gerador`) em container `gg-gerador-*` no desenv-server, 2 CPU/1 GiB, imagem cacheada `gg-cenarios:<sha do jar>` sobre `eclipse-temurin:25-jre` (precisou de um `docker pull` dessa imagem, ausente no host); variáveis do C2 (THREADS 8, SEMENTE 1, PCT_INSERT 5, PCT_DELETE 1, HOT_PCT 10); `docker stats` a cada ~5 s dos 4 containers |
| `scripts/gg/calibrar-gerador.sh` | Fase 3 completa (Extract parado, religa, drena, repete) |
| `scripts/gg/drenar.sh` | Espera `aplicacao_log` estável (15 s) com Extract e Replicat RUNNING |
| `scripts/gg/latencia-janela.sh` + `analisar-latencia.py` | Percentis e tendência (regra do runner Java, `analise.trend`) sobre `aplicacao_log` |
| `scripts/gg/hb-estagios.sh` | Lag por estágio (captura x aplicação) do histórico do heartbeat |
| `scripts/gg/medir-latencia.sh` | Gera carga, drena e mede (usado nos ajustes) |
| `gg/aplicar-params.sh PROC arquivo.prm` | PATCH do param file via REST, stop/start |
| `scripts/gg/c2-curto.sh` | C2 curto reprodutível (resultados em `resultados/gg/c2/<execucao>/`) |
| `sql/gg/novo/03_log_origem_updated_at.sql` | `ALTER TABLE aplicacao_log ADD origem_updated_at` + `CREATE OR REPLACE` dos 3 triggers de log; aplicado no lab em pé sem perder massa nem UUIDs; `01_schema.sql` já traz a coluna |

Conexão do gerador: não existe `gg-rede` e o `gg-legado` está na bridge padrão do Docker; o container do gerador usa o IP:porta publicados
(`jdbc:oracle:thin:@//192.168.0.5:1523/FREEPDB1`), que funcionou com os containers em hosts diferentes. `docker wait` por ssh travou uma vez
por minutos depois de o container terminar; `gerar.sh` agora consulta `docker inspect` a cada 5 s, com limite de tempo.

### Calibração do gerador (120 s por modo, `resultados/gg/calibracao/20261120T011641Z/`)

| Modo | Alvo | Taxa efetiva | Veredito | CPU gg-legado média/máx (limite 200%) | CPU gerador média/máx | tx média |
|---|---|---|---|---|---|---|
| Extract parado | 1.000 | 1000,06 | VALIDO | 90,4% / 128,2% | 31,9% / 174,8% | 5,69 ms |
| Extract parado | 2.000 | 2000,09 | VALIDO | 134,1% / 205,7% | 42,4% / 191,9% | 4,90 ms |
| Extract rodando | 1.000 | 1000,06 | VALIDO | 91,3% / 134,2% | 34,1% / 182,2% | 5,74 ms |
| Extract rodando | 2.000 | 2000,07 | VALIDO | 142,0% / 191,5% | 40,7% / 159,3% | 4,88 ms |

Sem trigger de captura o gerador fecha 2.000 ev/s com folga: o **legado não satura de forma sustentada** (pico de amostra de 5 s encosta nos 200% só
no 2.000 sem Extract, média de 67% do limite); a taxa não é o problema do ambiente. O custo do Extract no legado, descritivo (não é o C10): +0,9 ponto
de CPU a 1.000 ev/s e +7,9 a 2.000, tempo médio de transação praticamente igual. gg-novo com Extract: 36%/70% de média a 1.000/2.000 ev/s, gg-hub 18%/26%.

Antes de qualquer ajuste, com Extract rodando (latência por evento na janela de geração):

| Carga | n | p50 | p95 | p99 | max | tendência |
|---|---|---|---|---|---|---|
| 1.000 ev/s, 120 s | 120.007 | 3,10 s | 3,82 s | 4,04 s | 5,67 s | média por balde de 5 s +3 ms/s (sem crescimento sustentado); pelo pior caso por balde +12,6 ms/s, crescimento sustentado marginal (lag subindo de ~3,2 para ~4 s ao longo dos 120 s) |
| 2.000 ev/s, 120 s | 240.011 | 10,22 s | 17,16 s | 18,30 s | 18,88 s | +115 ms/s, crescimento sustentado: **o Replicat clássico não acompanha 2.000 ev/s** |

A 2.000 ev/s o apply ficou em ~1.750 a 1.950 ev/s (8,5 a 9,8 mil eventos por 5 s) e o lag cresceu até 19 s. Ao religar o Extract depois dos 240 s de carga
com Extract parado (360.011 operações acumuladas), Extract + Replicat drenaram o atraso em 243 s (inclui 15 s de estabilização), ~1.580 ops/s, sem perda
(o redo foi recuperado do archive log). O 2.000 ev/s sustentado é assunto do C3 (clássico x coordenado); não foi tentado o coordenado aqui.

### Latência de base: diagnóstico

Lag por estágio (heartbeat, `scripts/gg/hb-estagios.sh`) em repouso/carga leve: captura (commit na origem até o Extract) ~2,3 a 3,3 s, aplicação ~0,5 a 1,8 s;
a 1.000 ev/s a captura cai para 0,7 a 1,5 s. No banco de origem a captura está em tempo real: `v$goldengate_capture` mostra `WAITING FOR TRANSACTION` com
`capture_message_create_time` e `enqueue_message_create_time` a 0 s de idade, então a espera está no processo Extract (e no Replicat), não no LogMiner. Isso aponta
para as esperas de polling no fim do dado (`EOFDELAY`), cujo padrão é 1 s. Do ponto de vista da documentação oficial: `EOFDELAY`/`EOFDELAYCSECS` controla a frequência com
que Extract ou Replicat voltam a procurar dados ao chegar ao fim; `GROUPTRANSOPS` (padrão 1000) é um mínimo de operações por transação agrupada do Replicat sem
nunca partir uma transação de origem; `FLUSHSECS/FLUSHCSECS` (padrão 1 s) é o buffer do Extract para destino remoto. Fontes: Oracle GoldenGate Reference
(EOFDELAY, GROUPTRANSOPS, BATCHSQL, FLUSHSECS), "Optional Parameters for Integrated Modes" (21.3), guia "Tuning Replicat Transactions".

### Ajustes e efeito (um de cada vez; `resultados/gg/ajuste-latencia/NN-*`)

Carga leve = 60 ev/s (~20 tx/s) por 60 s (3.607 eventos), repetida quando indicado; a primeira medição depois de reiniciar um processo traz um transitório
(buckets iniciais de 8 s), por isso a repetição.

| Passo | Mudança | p50 | p95 | max | Decisão |
|---|---|---|---|---|---|
| 00 | base (padrões) | 2,56 s | 3,29 s | 3,64 s | |
| 01 / 01b | Extract `FLUSHCSECS 10` | 2,85 / 2,92 | 5,57 / 3,66 | 8,23 / 4,08 | sem ganho; revertido |
| 02 / 02b | Extract `EOFDELAYCSECS 10` | 1,70 / 1,88 | 2,43 / 2,66 | 2,77 / 3,86 | **mantido**: -0,8 s no p50 |
| 03 / 03b | + Replicat `EOFDELAYCSECS 10` | 1,53 / 1,27 | 2,17 / 2,15 | 3,11 / 2,64 | mantido |
| 04 / 04b | ambos `EOFDELAYCSECS 1` (0,01 s) | 1,05 / 1,23 | 1,56 / 1,84 | 3,64 / 2,74 | **mantido**; custo: CPU do hub em repouso 9,7% -> 17,2% |

Carga leve: p50 2,56 -> ~1,15 s, p95 3,29 -> ~1,7 s.

A 1.000 ev/s por 120 s (mesma carga do "antes" da calibração):

| Passo | Mudança | p50 | p95 | p99 | max | CPU gg-novo média |
|---|---|---|---|---|---|---|
| cal. | padrões | 3,10 s | 3,82 s | 4,04 s | 5,67 s | 36% |
| 05 | `EOFDELAYCSECS 1` nos dois | 2,20 | 4,38 | 4,97 | 5,17 | 42% |
| 06 | + Replicat `GROUPTRANSOPS 100` | 2,42 | 3,20 | 4,32 | 4,58 | 56% |
| 07 | + Replicat `BATCHSQL` | 1,91 | 2,84 | 3,09 | 4,23 | 19% |

`EOFDELAYCSECS` pouco muda a 1.000 ev/s (o fim do dado quase nunca é atingido), e piorou o p95 em 05; `GROUPTRANSOPS 100` reduziu a cauda (grupos menores, menos espera pelo
grupo de 1.000 operações) ao custo de mais commits (CPU do destino 42% -> 56%); `BATCHSQL` reduziu a CPU do destino a 19% e a latência. Verificação de consistência com
BATCHSQL: `scripts/gg/reconciliar.sh` e o jar `reconciliacao` sem divergência, UUIDs intactos no C2. Nada disso muda a atomicidade: o Replicat nunca parte uma transação de origem
(`GROUPTRANSOPS` é um mínimo) e o checkpoint segue na tabela `ggadmin.ggschkpt`. Não foi mexido em nada que enfraqueça isso (sem `COMMIT_SERIALIZATION`,
sem `NOCOMMIT`, sem aumento de paralelismo). Ressalva: numa medição isolada com BATCHSQL o `aplicacao_log` teve 111.837 linhas para 120.011 eventos gerados (o log é um por operação
aplicada; hipótese posteriormente refutada: ver "Fase 4", item 2; o gerador daquela medição também gravou 111.837 linhas), com estado final idêntico; nas execuções seguintes (C2) o log teve exatamente o número de
eventos do gerador (300.008).

Parâmetros finais versionados: `gg/params/EXTBDAD.prm` (`EOFDELAYCSECS 1`) e `gg/params/REPBDAD.prm` (`EOFDELAYCSECS 1`, `GROUPTRANSOPS 100`, `BATCHSQL`); aplicados com
`gg/aplicar-params.sh` e usados por `criar-pipeline.sh` em lab novo. `REPCO.prm` (coordenado) não foi alterado nem testado com eles. Achado: o `aplicar-params.sh` inicial usou caminho
relativo e gravou configuração vazia, que deixou Extract e Replicat parados; corrigido (caminho absoluto, falha se o arquivo não existir). O Replicat retomou do checkpoint sem perda.

### C2 curto (`resultados/gg/c2/20261120T022300Z-regime/`, `scripts/gg/c2-curto.sh`)

1.000 ev/s por 300 s, Extract + Replicat clássico (parâmetros finais), sem o coordenado (não foi preciso):

| Critério | Medido | Alvo | Veredito |
|---|---|---|---|
| Taxa efetiva do gerador | 1000,02 ev/s (300.008 linhas, 100.137 transações) | >= 980 | APROVADO |
| p95 (nearest-rank, 300.008 eventos da janela de geração) | 2,89 s (p50 1,85; p99 3,20; max 5,24) | <= 5 s | APROVADO |
| Lag sem tendência de alta (últimos 175,5 s) | inclinação do pior caso por balde de 5 s -3,5 ms/s, da média -4,2 ms/s; sem crescimento sustentado | sem tendência | APROVADO |
| Diferença de relógios | +1,35 ms, incerteza 5,0 ms (início e fim) | <= 100 ms | APROVADO |
| Integridade | reconciliação SQL 0 divergências; jar `reconciliacao` MODO=completo DRY_RUN=true total=0 (19,2 s); 1.700.000 UUIDs pré-existentes comparados com o snapshot de `resultados/gg/c1/`, 0 alterados | 0 | APROVADO |

CPU (docker stats, 64 amostras): gg-legado 107% média (máx 177%, limite 200%), gerador 30%, gg-novo 17%, gg-hub 21%. O lag usado na tendência é o analógo do `lag_ms` do
worker: máximo (e média) da latência dos eventos aplicados em baldes de 5 s, sem os baldes parciais de início e fim. Ressalvas: uma única execução; um evento isolado passou de 5 s (max 5,24 s)
embora o p95 esteja em 2,9 s; o lab é compartilhado (outros containers `infra-*` nos hosts). A 2.000 ev/s o Replicat clássico continua sem acompanhar nos padrões; com os ajustes não foi remedido.


---

## Fase 4: preparação e C3 (2026-11-20)

### 1. Archive logs: limpeza e alarme de disco

- A imagem `gvenzl/oracle-free:23-slim-faststart` **não traz o binário `rman`** (só as mensagens `rman*.msb`), então não há `CONFIGURE ARCHIVELOG DELETION POLICY`
  nem `DELETE ARCHIVELOG` no gg-legado. Pela documentação/notas Oracle (A-Team, Doc ID 2137576.1, 2580979.1) o RMAN integrado ao GG não apaga log ainda exigido pelo
  Extract integrado (sem `FORCE`); essa proteção não existe sem RMAN, então o script a reproduz.
- `scripts/gg/limpar-archive.sh` (idempotente): lê `required_checkpoint_scn` de `dba_capture` (no PDB `FREEPDB1`; é o SCN a partir do qual o Extract integrado precisa do
  redo ao reiniciar) e apaga, por `rm` dentro do container, só os archive logs com `NEXT_CHANGE# <= required_checkpoint_scn`, mantendo sempre os 3 mais recentes
  (`MANTER_ULTIMOS`). Sem captura habilitada, não apaga nada. `--verificar` só mede. Alarme (exit 2): livre no host < 10 GB (`MIN_LIVRE_GB`) ou archive > 20 GB
  (`MAX_ARCHIVE_GB`). Saída em JSON. As linhas apagadas continuam em `v$archived_log` (o controlfile não é atualizado sem RMAN); só `rman` as marcaria `DELETED`, inofensivo para o Extract.
- Teste: 3 `switch logfile` + carga (500 ev/s, 30 s) + drenagem: 6 logs, 3 apagados (1,59 GB -> 159 MB), segunda execução apagou 0; depois, mais carga: Extract e Replicat `running`, sem
  `OGG-00868`/`ORA-01291` no relatório do Extract, aplicação seguiu. Primeira execução real removeu 6 dos 9 logs de ~4,3 GB (-> 1,4 GB). Ressalva: um Extract parado por muito
  tempo deixa o `required_checkpoint_scn` parado e o archive cresce até o alarme (comportamento correto: preserva o redo, e avisa).
- Integrado em `scripts/gg/cenario.sh`: roda antes e depois da execução e após cada degrau (`archive-antes.json`, `archive-depois.json`, `dN-*/archive.json`); alarme = exit 2 e
  `validade` INVALIDO (`DISCO`). No C3 clássico o archive chegou a 2,9 GB no pico (degrau 4000) e voltou a 1,5 GB; livre no host 57-60 GB.

### 2. Ressalva do BATCHSQL (111.837 x 120.011): causa e conclusão

**Não houve perda de disparo de trigger.** Evidência:

- Na medição 07 (`resultados/gg/ajuste-latencia/07-1000evs-g100-batchsql`) o **próprio gerador** gravou 111.837 linhas (`gerador-linhas.csv`, `linhas=111837 taxa=916,11 ... INVALIDO`: o
  legado teve uma queda de taxa no fim, janelas de 285 e 252 ev/s). O log do destino teve exatamente 111.837 linhas: igual. O número 120.011 era a meta nominal de outra medição
  (120.007 do "antes"), comparado por engano; a medição 07 era uma execução com taxa inválida e não uma perda.
- `scripts/gg/comparar-eventos.sh` compara por `(tabela, codigo)` o CSV do gerador com o `aplicacao_log`. Com BATCHSQL ligado: (a) 1.000 ev/s, 60 s: 60.014 eventos = 60.014 linhas de log, 0 chaves
  com diferença; (b) pior caso para coalescência, `HOT_PCT=100 TX_MAX=30` (chaves quentes, mais de uma atualização da mesma chave na mesma transação, 25 repetições): 60.068 = 60.068, 0
  diferença. O log tem uma linha por operação aplicada, incluindo repetições na mesma transação. Evidência em `resultados/gg/investigacao-batchsql/`.
- Conclusão: BATCHSQL não distorce a métrica de latência; não foi preciso medir a alternativa sem BATCHSQL. A regra operacional fica: o gerador deve comparar `linhas` do próprio log com o `aplicacao_log`
  (o `comparar-eventos.sh` faz isso), nunca com a meta nominal.

### 3. Runner generalizado

`scripts/gg/cenario.sh c2|c3 [rotulo]` (atalhos `c2-regime.sh`, `c3-teto.sh`; `c2-curto.sh` agora é o C2 com 300 s). Grava `resultados/gg/<cenario>/<execucao>/` e copia o `meta.json`
para `resultados/gg/<cenario>/meta.json`; `scripts/gg/meta_gg.py` monta o meta no contrato do lab Java (`criterios`, `degraus`, `validade`, `veredito`, `relogios`, `integridade`; nomes
`p95_ms`, `tendencia_lag`, `teto_ev_s`, `divergencias`, `uuids_alterados`, `pendentes_final`). `bash scripts/relatorio.sh --resultados resultados/gg` consolida (testado; o C3 aparece
APROVADO; C2 curto aparece INVALIDO por ESCOPO_REDUZIDO, como no Java). `pendentes_final` é 0 quando a drenagem fechou (Replicat RUNNING, `aplicacao_log` estável 15 s, lag <= 2 s); não há contagem de pendentes como
no worker. `drenar.sh` aceita `REPLICAT=`. Exit codes: 0 aprovado, 1 reprovado/inválido, 2 falha operacional (`trap ERR`, alarme de disco). Regras: degrau válido = taxa >= 98% do alvo, senão
`GERADOR_SATURADO` (interrompe a escada do C3); teto = maior alvo válido sem crescimento sustentado; aprova só com degrau válido de 2.000. O `c1/meta.json` do lab GG (só snapshots) não tem o contrato e o relatório o marca inválido: não foi alterado.

### 4. C3 (teto): Replicat clássico, execução `resultados/gg/c3/20261120T025051Z-REPBDAD-classico/`

Parâmetros atuais (EXTBDAD `EOFDELAYCSECS 1`; REPBDAD `EOFDELAYCSECS 1`, `GROUPTRANSOPS 100`, `BATCHSQL`), escada 500, 1.000, 2.000, 4.000, 300 s por degrau, drenagem entre degraus, relógios medidos em cada degrau (|offset|+incerteza <= 100 ms).

| Degrau (ev/s) | Taxa efetiva | Validade | p50 / p95 / p99 / max (s) | Tendência do lag (últimos ~180 s) | Drenagem pós-carga | CPU média (máx) gg-legado / gg-novo / gg-hub |
|---|---|---|---|---|---|---|
| 500 | 500,05 | VALIDO | 1,26 / 1,95 / 3,15 / 4,44 | -2,3 ms/s, sem crescimento | 18 s | 59% (84) / 16% (113) / 20% (70) |
| 1.000 | 1000,03 | VALIDO | 1,79 / 2,79 / 3,08 / 3,87 | -3,1 ms/s, sem crescimento | 18 s | 107% (198) / 22% (164) / 19% (29) |
| 2.000 | 2000,03 | VALIDO | 1,47 / 2,09 / 2,44 / 3,21 | -2,3 ms/s, sem crescimento | 18 s | 147% (189) / 35% (80) / 21% (77) |
| 4.000 | 3858,38 (96,5%) | GERADOR_SATURADO | 12,9 / 38,0 / 40,0 / 43,8 | +162,5 ms/s, crescimento sustentado | 45 s | 178% (211) / 55% (103) / 16% (42) |

Veredito por critério: `teto_ev_s` = 2.000, **APROVADO** (alvo >= 2.000 em degrau válido); `divergencias` 0 (SQL 0; jar `reconciliacao` MODO=completo DRY_RUN=true total=0, 20,3 s) APROVADO; `uuids_alterados` 0 de 1.700.000
pré-existentes comparados com o snapshot de `resultados/gg/c1/` APROVADO; `pendentes_final` 0 APROVADO. Veredito geral APROVADO, exit 0.

Leitura: com os ajustes da Fase 3 (`GROUPTRANSOPS 100`, `BATCHSQL`, `EOFDELAYCSECS 1`) o Replicat **clássico** (uma thread de aplicação) acompanhou 2.000 ev/s com p95 2,1 s e lag sem
tendência de alta; o "não acompanha 2.000 ev/s" da Fase 3 era dos parâmetros padrão. A 4.000 ev/s o gerador (2 CPU, 1 GiB) e o legado (CPU média de 178% de 200%) não sustentam a taxa (3.858, < 98%): degrau inválido e fora do
teto, como no lab Java. O lag cresceu 162 ms/s e chegou a 43,8 s, e drenou em 45 s depois da carga; isso indica que, perto de 3.900 ev/s, o clássico já fica atrás, mas é uma observação de degrau inválido, não um teto medido. O teto
medido é um **piso**: o clássico, no Free sem Parallel Replicat, sustenta pelo menos 2.000 ev/s neste ambiente (a 4.000 o gerador/legado limita o teste); não se extrapola para produção.

**Replicat coordenado: não foi medido.** O critério (>= 2.000) foi aprovado no clássico, então a condição "se reprovar" não ocorreu. Além disso, a documentação Oracle (About Coordinated Replicat, 23ai)
diz que, com THREADRANGE, "the source transactions could be split across CR processes such that the integrity of the total source transaction is not maintained": cada thread confirma sua parte separadamente. Isso quebra a atomicidade
de transação de origem exigida na PoC (consistência acima de velocidade); só as transações de barreira (DDL, mudança de chave primária) ficam inteiras. O `REPCO.prm` ficou como estava (com um aviso no arquivo); o `EOFDELAYCSECS`/`GROUPTRANSOPS`/`BATCHSQL` não foram acrescentados
a ele. O teste do spike (4 threads) mostrou que o coordenado funciona, mas sem ganho a medir aqui e com esse risco. Lab final: Replicat clássico `REPBDAD` ativo (nunca foi trocado), `EXTBDAD` ativo.

### Ressalvas

Uma única execução do C3 (um degrau de cada). Lab compartilhado. `COMMITTIMESTAMP` tem resolução de 1 s (usa-se `origem_updated_at`). A latência inclui o trigger `aplicacao_log` + índice no destino. O C2 completo de 600 s (critério do PRD) ainda não foi executado
no lab GG (`scripts/gg/c2-regime.sh`).


---

## Fase 4: C1, C2, C4, C5 (2026-11-20)

Todos com Replicat clássico `REPBDAD` (EOFDELAYCSECS 1, GROUPTRANSOPS 100, BATCHSQL), Extract `EXTBDAD`, gerador 2 CPU/1 GiB, relógios medidos no início e no fim (|offset|+incerteza <= 100 ms em todos), `limpar-archive.sh` antes/depois (sem alarme),
reconciliação SQL + jar Java (`MODO=completo DRY_RUN=true`) = 0 divergências, UUIDs pré-existentes comparados com o snapshot de referência = 0 alterados. Runner: `scripts/gg/cenario.sh c1|c2|c3|c4|c5` (atalhos `c1-carga-inicial.sh`, `c2-regime.sh`, `c3-teto.sh`,
`c4-rajada.sh`, `c5-transacao-longa.sh`); `meta.json` no contrato do relatório; `bash scripts/relatorio.sh --resultados resultados/gg` mostra C1 a C5 APROVADO.

### C2 completo (`resultados/gg/c2/20261120T032036Z-REPBDAD/`): APROVADO

1.000 ev/s por 600 s: taxa 1000,02 ev/s (600.012 linhas). Latência `aplicado_em - origem_updated_at` (corrigida pelo offset): p50 1,85 s, **p95 2,82 s** (alvo <= 5 s), p99 3,11 s, max 4,28 s. Tendência do lag (últimos 180 s, 37 amostras): +2,0 ms/s,
sem crescimento sustentado (médias dos três blocos não crescem). Relógios +1,0 ms (incerteza 4,7 ms). CPU média: gg-legado 107% (máx 147), gerador 28%, gg-novo 20%, gg-hub 20%. Drenagem 19 s. Uma execução.

### C4 rajada (`resultados/gg/c4/20261120T034630Z-REPBDAD/`): APROVADO

Regime de 1.000 ev/s por 600 s (taxa 1000,02; p95 de fundo 2,89 s) e, 120 s depois do início, um segundo gerador (`RAJADA_LINHAS=50000`: uma UPDATE de 50.000 linhas de TITULAR e um COMMIT, `gerador-linhas.csv`). Medição (`scripts/gg/analisar-especial.py`, só relógios
dos bancos e do gerador; instantes de aplicação do gg-novo trazidos para a linha do tempo do desenv-server pelo offset):

- Patamar = **máximo** da latência por balde de 5 s (eventos de fundo, por `aplicado_em`) nos 60 s antes do commit: 11 amostras (mediana 3,23 s), **patamar 3,71 s**.
- A rajada foi aplicada inteira entre 0,12 s e 8,11 s depois do commit (latência das linhas da rajada p50 5,25 s, max 7,81 s; ela inclui os segundos da própria UPDATE); o balde com o pico do fundo (9,90 s) é o de 2,9 a 7,9 s depois do commit: o Replicat aplica
  a transação de 50.000 linhas inteira antes das seguintes.
- **Recuperação = 17,9 s** (primeiro balde de 5 s inteiro após o commit, regime ativo, rajada inteira aplicada, lag máximo 3,19 s <= 3,71 s, p95 2,86 s <= 5 s). Alvo <= 120 s. Sem perda: 50.161 eventos classificados como rajada (>= 50.000, ver ressalva),
  0 divergências, drenou.
- Ressalvas: (1) `COMMITTIMESTAMP` tem 1 s de resolução, então a rajada é identificada por `TITULAR`, `codigo <= chave máxima` e commit_ts do commit; transações de fundo do mesmo segundo nessa faixa entram na conta (161 eventos, 0,3%). (2) O recorte de "balde completo" é
  só "inteiro e com eventos de fundo" (como na especificação); a variante que exige >= 50% da mediana de eventos por balde também é gravada (`recuperacao_s_estrita_50pct_mediana`, 17,9 s). Num teste curto (descartado) a rajada reduziu a taxa do gerador por alguns segundos (linhas
  bloqueadas pela UPDATE) e com a regra de 50% a recuperação era 25 s maior: a regra "estrita" confunde falta de eventos com lag. (3) A rajada tira ~1 balde (5 s) de vazão do fundo: `janela_linhas` do gerador caiu a 938 ev/s no balde do commit. (4) Uma execução.

### C5 transação longa (`resultados/gg/c5/20261120T035943Z-REPBDAD/`): APROVADO

O modo `TX_ABERTA_SEG` do gerador Java lê `evento_replicacao` (inexistente no lab GG), então a transação longa é `sql/gg/testes/tx_longa.sql` rodada no gg-legado 5 s depois do início: `UPDATE titular SET excluido = 0 WHERE codigo = 1`, `dbms_session.sleep(30)`, `COMMIT`, com os instantes do banco legado (UTC). A
linha é identificada no `aplicacao_log` por `TITULAR/1` com `origem_updated_at` entre os instantes antes/depois do UPDATE (exatamente 1 candidato, senão INVALIDO).

| Critério | Medido | Alvo | Veredito |
|---|---|---|---|
| Transação aberta | 30,00 s (04:00:23,989 a 04:00:53,990 UTC, relógio do legado) | >= 30 s | ok |
| p95 das demais durante a abertura (29.293 eventos) | **2,88 s** (p50 1,72; max 3,33) | <= 5 s | APROVADO |
| Aplicação da longa após o commit | **1,84 s** (aplicada 04:00:55,828) | <= 5 s | APROVADO |
| Latência da longa (reportada) | 31,84 s | SEM_CRITERIO | - |
| Integridade (estado final) | 0 divergências, 0 UUIDs alterados, drenado | 0 | APROVADO |

O GG não bloqueou as demais: a transação só é emitida no commit e as outras seguem na ordem de commit. Taxa 1000,01 ev/s, p95 geral 2,75 s. Ressalva: o instante do commit é lido logo após o `COMMIT` retornar (ms de atraso, favorável ao critério por décimos de ms).

### C1 carga inicial (`resultados/gg/c1/20261120T041312Z-REPBDAD/`): APROVADO

`scripts/gg/c1-carga.sh`: para e recria o REPBDAD (`gg/criar-pipeline.sh recriar-replicat`: stop, DELETE e POST pela REST), trunca só `titular`, `obra_musical`, `fonograma` e `aplicacao_log` do gg-novo, `carga-inicial.sh` (SCN no legado, `INSERT ... AS OF SCN` via
DB link, UUIDv7 por trigger), snapshot "antes", `START REPLICAT AFTERCSN <SCN>` + autostart, uma transação-sonda no legado (só quando ela aparece no `aplicacao_log` o Replicat percorreu o trail até o fim), drenagem, reconciliações, snapshot de referência.

- Escopo completo: o legado já tinha crescido com as inserções dos cenários anteriores (SCN 9.779.205): titular 262.491, obra_musical 625.703, fonograma 1.123.358 (2.011.552 linhas; antes 1.700.000), contagens iguais nos dois lados e iguais às contagens `AS OF SCN` do legado.
- Linhas/s: titular 3.611 (72,7 s), obra_musical 3.623 (172,7 s), fonograma 3.533 (317,9 s); **carga 565,9 s** (2,01 M linhas, ~3.554/s; com a massa antiga de 1,7 M o ritmo equivale aos 484 s anteriores), contra 109,9 s do Java (1,85 M, ~16.800/s). Do início ao drenado: 666,3 s
  (Replicat running em 1,6 s, sonda aplicada 15,5 s depois, drenagem 47,6 s: o trail é relido até o SCN, 1,4 GB).
- 0 divergências (SQL e Java 21,6 s), pendentes 0, 0 UUIDs alterados entre o snapshot "antes" (logo após a carga) e o "depois" (2.011.552 comparados). Os UUIDs são recriados pelo truncate/carga por definição.
- **Novo snapshot de referência**: `resultados/gg/c1/snapshots/apos-carga.csv` (2.011.552 linhas, sha256 `e33f0149...1a1`, 0 duplicados); o anterior (1,7 M) ficou em `apos-carga-anterior.csv` e o meta do `subir.sh` em `meta-spike.json`. Os CSVs são ignorados pelo git.

#### Como acelerar a carga (não implementado; fica fora do `worker/`)

Gargalo medido: ~3.500 linhas/s numa sessão do gg-novo (2 CPU), com 3 triggers por linha (UUIDv7 em PL/SQL com `dbms_crypto` e sequence, `replicado_em`, e insert em `aplicacao_log` + índice) e leitura linha a linha pelo DB link.
1. Desabilitar `trg_*_log` durante a carga (o log é truncado ao fim de qualquer forma): remove um insert indexado por linha; a trigger de UUID e a leitura pelo link continuam.
2. Rodar as três tabelas (ou faixas de `codigo` do fonograma) em sessões paralelas, mesmo SCN: o tempo cai para o da maior fatia (~318 s hoje; com 2 CPUs no gg-novo o ganho prático é de ~1,5 a 2x).
3. Gerar o UUID no SELECT em vez da trigger (função SQL com `UTL_RAW`/`SYS_GUID` + timestamp) e `INSERT /*+ APPEND PARALLEL */` em direct-path: o direct-path só vale sem trigger de linha ativa (o Oracle volta para o caminho convencional se há trigger), então exige desabilitar as triggers `_bi`/`_bu`/`_log`
   só durante a carga. Preserva UUIDv7 desde que a função mantenha a ordem (48 bits de ms + contador) e o SCN; o contador de 12 bits precisa de uma sequence por sessão ou bits aleatórios.
4. Alternativa de produção: `expdp FLASHBACK_SCN` + `impdp` ou o Initial Load do GG (não usados aqui), com UUID atribuído em UPDATE posterior.
Com 1 e 2 juntos é razoável esperar 250 a 300 s; chegar perto de 110 s do Java provavelmente exige 3 (e o CPU do destino de 2 vCPU como limite). Nada disso foi medido: são hipóteses a testar.

### Arquivos desta etapa

`scripts/gg/cenario.sh` (c1 a c5), `scripts/gg/c1-carga.sh`, `scripts/gg/{c1-carga-inicial,c4-rajada,c5-transacao-longa}.sh`, `scripts/gg/eventos-janela.sh`, `scripts/gg/analisar-especial.py`, `scripts/gg/meta_gg.py` (c1, c4, c5), `sql/gg/testes/tx_longa.sql`, `gg/criar-pipeline.sh` (`recriar-replicat`).
Estado final do lab: gg-legado, gg-novo e gg-hub no ar; EXTBDAD e REPBDAD RUNNING com autostart; lab Java parado e intocado; archive 1,4 GB, 58 GB livres.


---

## Fase 4: C6 a C10 (2026-11-20)

Mesmas condições de C1-C5 (Replicat clássico `REPBDAD` EOFDELAYCSECS 1 / GROUPTRANSOPS 100 / BATCHSQL, Extract `EXTBDAD`, gerador 2 CPU/1 GiB, 1.000 ev/s, relógios dos hosts, `limpar-archive.sh` antes/depois,
reconciliação SQL + jar Java e UUIDs contra `resultados/gg/c1/snapshots/apos-carga.csv`: 0 divergências e 0 UUIDs alterados em todas as execuções). `bash scripts/relatorio.sh --resultados resultados/gg`: C1 a C10 todos válidos
(10/10); C7 REPROVADO, os demais APROVADOS. Runners: `scripts/gg/falhas.sh c6|c7|c8` (atalhos `c6-queda.sh`, `c7-failover.sh`, `c8-captura-parada.sh`), `scripts/gg/c9-comparacao.sh`, `scripts/gg/c10-custo.sh`; `cenario.sh c6..c10` delega.
Ferramentas novas: `hub-evento.sh` (kill/start/restart/kill do Replicat no infra-server, instantes do relógio do infra-server, mesmo host do `gg-novo`), `analisar-queda.py`, `verificar-archive-necessario.sh`,
`reparar-por-toque.sh`, `comum-cenario.sh`, `meta_c9.py`, `sql/gg/novo/04_divergentes_obra.sql`, variantes `gg/params/variantes/`.

### Achado transversal: STOP manual do Extract é desfeito pelo Admin Service

Na primeira execução do C8 o Extract "parado" voltou a rodar ~6 s depois (autostart/autorestart de `managedProcessSettings`): as 60 s de updates foram capturadas e aplicadas em tempo real (0 divergências antes de religar), então a execução
foi descartada (nenhum `meta.json` dela foi mantido). Correção em `comum-cenario.sh`: `parar_extract` faz `PATCH` desabilitando autoStart/autoRestart, `STOP`, e confirma que continua `stopped` 12 s depois; `religar_extract` faz `START` e reabilita
os mesmos valores de `criar-pipeline.sh` (confirmado por `GET`). Os `trap`s de erro religam o Extract. **Ressalva**: as medições da Fase 3 com "Extract parado" não registraram a confirmação posterior; o backlog de 360 mil operações e a CPU
sugerem que o stop valeu naquelas execuções, mas o custo "sem Extract" daquela tabela deve ser lido com essa cautela. C8 e C10 abaixo já usam o stop confirmado.

### C6 queda: APROVADO (`resultados/gg/c6/20261120T045840Z-REPBDAD/`)

1.000 ev/s por 600 s (taxa 1000,02). `docker kill -s KILL gg-hub` aos 180 s e `docker start gg-hub` aos 240 s da carga (alvos no relógio do infra-server).

| Medida | Valor |
|---|---|
| REST de volta após o reinício | 4,8 s |
| EXTBDAD / REPBDAD RUNNING após o reinício (autostart) | 9,9 s / 9,9 s |
| **Primeiro balde de 5 s completo com lag <= 5 s**, desde a queda | **112,7 s** |
| idem, desde o reinício | **52,6 s** (balde com lag máx. 3,04 s, 3.999 eventos) |
| Pico de lag | 77,5 s; estável depois (nenhum balde posterior > 5 s) |
| p95 da janela inteira (informativo) | 55,9 s |
| Integridade | 0 divergências (SQL e jar), 0 UUIDs alterados de 2.011.552, drenado |
| Log x gerador (por `(tabela, codigo)`) | 600.012 eventos = 600.012 linhas; 0 faltantes, 0 duplicatas (o Replicat não reaplicou nada: checkpoint transacional) |

Critério do lab Java: o `tempo_normalizar_s` só exige que a normalização ocorra (aprova se medido); Java: 111,8 s desde a queda / 57,3 s desde o reinício (INVALIDO por TAXA, regime a 885 ev/s).
GG: 112,7 s / 52,6 s com taxa válida. Parecido em tempo, mas por razões distintas (aqui a queda é do hub inteiro e o backlog de 60 s é drenado pelo Replicat). A carga seguiu 360 s após o reinício.

Variante descritiva, kill -9 só do processo `REPBDAD` dentro do hub (`VARIANTE=replicat`, 300 s, queda aos 120 s; `resultados/gg/c6/20261120T051219Z-REPBDAD-variante-replicat/`): o autorestart (5 s) recolocou o Replicat RUNNING em 5,7 s;
primeiro balde com lag <= 5 s a 12,4 s da queda; pico 7,6 s; 0 divergências, 0 UUIDs alterados, log = gerador (300.008 = 300.008). Não promove o `meta.json` do C6 (referenciada em `operacao_especial`).

### C7 failover (ADAPTADO): REPROVADO contra a referência de 20 s (`resultados/gg/c7/20261120T052037Z-REPBDAD/`)

Sem segunda réplica no Free: mede-se o reinício do hub único sob 1.000 ev/s (600 s; `docker restart` aos 120 s e `docker kill`+`docker start` aos 330 s). HA de produção (XAG/RAC, hub secundário) fora do escopo; `meta.json` tem `adaptado: true`.

| Evento | REST | EXTBDAD e REPBDAD RUNNING | Primeiro balde de 5 s com lag <= 5 s | Pico de lag |
|---|---|---|---|---|
| `docker restart gg-hub` | 4,9 s | 9,9 s | **25,8 s** | 15,2 s |
| `docker kill` + `docker start` | 5,2 s | 10,3 s | **25,8 s** | 15,7 s |

Tempos desde o início do comando (o `docker restart` devolveu em 0,4 s; o hub cai rápido). `failover_s` = 25,84 s (máximo dos dois) contra 20 s: **REPROVADO por 5,8 s**. Decomposição: ~10 s para os processos
voltarem, ~10 s de catch-up do atraso (a primeira aplicação após o reinício vem 10,3 s depois) e a granularidade do balde de 5 s (o balde normalizado começa em 20,8 s). Os processos RUNNING em ~10 s ficam dentro de 20 s; o que passa é "lag normal". Estável depois, 0 divergências,
0 UUIDs alterados, log = gerador (600.012). Java: 30,0 s (INVALIDO por TAXA, 2 workers com lease). Não é comparação de mecanismo: lá há worker concorrente, aqui o mesmo hub reinicia.

### C8 captura parada: APROVADO (`resultados/gg/c8/20261120T054117Z-REPBDAD/`)

Drena; PARA o Extract (confirmado `stopped`); 60 s de updates só em `OBRA_MUSICAL` (`MIX=0,100,0`, `PCT_INSERT=0 PCT_DELETE=0`, 60.014 linhas); religa o Extract; nenhum reparo, delta ou reconciliação até drenar.

- Chaves afetadas distintas (CSV do gerador): **55.904**. Antes de religar, a comparação por chave legado x destino (DB link, só leitura; `04_divergentes_obra.sql`) achou exatamente essas 55.904 divergentes (conjuntos idênticos); depois da drenagem: 0. **Corrigidas: 55.904 de 55.904 (100%)**, todas presentes no `aplicacao_log` (aplicadas pelo Replicat a partir do redo).
- Extract parado 112 s (janela dos 60 s de carga mais as verificações). Religar: START em 0,5 s; última aplicação 21,2 s depois; drenado em 42,4 s (inclui 15 s de estabilização). Java: 55.629 corrigidas de 55.629 por delta (reconciliação) com reenfileiramento; aqui nada foi reenfileirado.
- Archive: `dba_capture` continua `ENABLED` com `required_checkpoint_scn` mesmo com o Extract parado; `limpar-archive.sh` rodou antes de religar (apagou 1 log já consumido, 1,91 GB -> 1,43 GB) e `verificar-archive-necessario.sh` (logs com `NEXT_CHANGE# > required_checkpoint_scn`) mostrou antes/depois: 1 necessário, 1 existente, 0 faltando; depois de religar também 0 faltando. Ressalva: só 1 log necessário (a janela estava quase toda nos redo logs online), prova fraca de proteção de muitos archives.
- Integridade final: 0 divergências (SQL e jar), 0 UUIDs alterados.

### C9 comparação de parâmetros do Replicat clássico: descritivo (`resultados/gg/c9/20261120T054721Z-REPBDAD/`, `comparacao.csv`)

Aqui o C9 compara modos do Replicat clássico, nunca o coordenado (THREADRANGE pode dividir transação de origem). Cada modo: C2 (1.000 ev/s, 600 s, para o relatório aceitar o escopo; o pedido era 300 s) e um degrau de 2.000 ev/s (300 s).
Modos: `oficial` (GROUPTRANSOPS 100 + BATCHSQL), `sem-batchsql` (GROUPTRANSOPS 100), `g1000` (GROUPTRANSOPS 1000 + BATCHSQL). Execuções em `resultados/gg/c9/<exec>/runs/` (não sobrescrevem o C2/C3 oficiais).

| Modo | Alvo ev/s | Taxa | p50 / p95 / p99 / max (s) | Tendência do lag | Drenagem | CPU média gg-novo |
|---|---|---|---|---|---|---|
| oficial | 1.000 | 1000,02 | 1,82 / 2,84 / 3,12 / 4,28 | +0,4 ms/s, sem crescimento | 17 s | 20% |
| oficial | 2.000 | 1999,99 | 1,44 / 3,96 / 5,17 / 7,77 | +5,8 ms/s, sem crescimento sustentado | 17 s | 53% |
| sem-batchsql | 1.000 | 999,97 | 2,37 / 3,95 / 8,84 / 10,97 | -1,0 ms/s, sem crescimento | 15 s | 49% |
| sem-batchsql | 2.000 | 1999,86 | 30,9 / 54,8 / 56,8 / 57,2 | +137,8 ms/s, **crescimento sustentado** | 56 s | 69% |
| g1000 | 1.000 | 999,92 | 1,86 / 2,88 / 3,21 / 4,71 | -3,6 ms/s, sem crescimento | 15 s | 20% |
| g1000 | 2.000 | 1999,87 | 1,36 / 2,22 / 2,63 / 4,62 | -1,9 ms/s, sem crescimento | 15 s | 34% |

Leitura: BATCHSQL é o que sustenta 2.000 ev/s (sem ele o Replicat clássico fica atrás e o lag chega a 57 s, CPU do destino 2,5x maior a 1.000 ev/s); GROUPTRANSOPS 100 x 1000 praticamente empata a 1.000 ev/s e, nesta execução, 1000 foi melhor a 2.000 ev/s (p95 2,2 x 4,0 s;
diferença dentro do que uma única execução não separa de ruído; o p95 do oficial a 2.000 ev/s foi 2,1 s no C3). Não se trocam os parâmetros oficiais por isso. 0 divergências e 0 UUIDs alterados nas seis execuções. Após o C9 voltaram os parâmetros oficiais (`aplicar-params.sh`; sha256 do `REPBDAD.prm` conferido, config no REST com
BATCHSQL e GROUPTRANSOPS 100, autostart/autorestart intactos). Java C9 comparava modos de threads do worker (todos INVALIDO por TAXA): não comparável.
Falha do script: o `c9-comparacao.sh` apanhou `meta.json` em vez do diretório da execução ao montar o `modos.jsonl`; corrigido, e o `modos.jsonl`/`meta.json` do C9 foram regerados dos dados existentes (sem repetir medição).

### C10 custo no legado: APROVADO com ressalva (`resultados/gg/c10/20261120T065625Z-REPBDAD/`)

3 pares alternados (ligada, desligada) a 1.000 ev/s, **300 s por fase** (o C10 Java usou 600 s por fase), só updates, semente 1, 8 threads, HOT_PCT 10, mix 40/40/20, taxa 999,97 a 999,55 (todas válidas). "Ligada" = Extract RUNNING (drenado antes da fase); "desligada" = Extract PARADO
(confirmado); **FORCE LOGGING e supplemental logging ALLCOLS continuam ativos nas duas: o custo do supplemental logging não é isolado**. Métrica = `transacao_media_ms` do gerador (igual ao Java). Consulta de métricas com/sem: não aplicável (sem worker nem `evento_replicacao`).

| Fase | tx média (ms) | | Fase | tx média (ms) |
|---|---|---|---|---|
| 1 ligada | 7,113 | | 2 desligada | 5,679 |
| 3 ligada | 6,103 | | 4 desligada | 5,526 |
| 5 ligada | 5,659 | | 6 desligada | 5,492 |

Média ligada 6,292 ms (desvio 0,745), desligada 5,566 ms (desvio 0,100): **+0,726 ms, +13,04%** contra o critério proposto <= 15% E <= 5 ms (Q-T3 pendente): **APROVADO**, mas com folga curta de 2 pontos percentuais. Java (triggers): +0,958 ms, +19,99% (REPROVADO).
CPU média do gg-legado: 104,4% ligada x 94,4% desligada (+10 pontos de 200%).
Ressalva importante: a série "ligada" cai fase a fase (7,11 -> 6,10 -> 5,66 ms) e a primeira fase é um outlier provável de aquecimento; só no último par a diferença é 5,659 x 5,492 ms (+3,0%). O resultado de 13% depende de manter a primeira fase; como a ordem sempre foi ligada-primeiro, não se separa efeito do Extract de aquecimento. Recomendado
repetir com a fase ligada 1 descartada ou com pares de ordem invertida antes de citar o número como custo do Extract.

Variante descritiva sem supplemental logging (`DELETE SCHEMATRANDATA bdad`, Extract parado, mesma carga): tx média **7,280 ms**, maior que as duas séries principais (ligada 6,29; desligada 5,57), ou seja, **a variante não isola o custo do supplemental logging** (ruído do ambiente é da mesma ordem do efeito; CPU do legado 89,9%). Registrar como inconclusiva.
Achado funcional sobre essa variante: sem supplemental logging o redo gerado **não é aplicável**: numa sonda curta (10 s de updates com o schematrandata removido e Extract religado depois de `ADD SCHEMATRANDATA`) o Replicat abendou com `OGG-30426 Error mapping update record, because key column(s) of table ... is/are missing from the trail`;
nada é perdido em silêncio, mas o pipeline para. Para voltar sem perda foi preciso: reposicionar o Replicat no fim do trail (`ALTER REPLICAT REPBDAD, EXTSEQNO n, EXTRBA r` via adminclient; o autorestart reiniciou o processo sozinho) e reaplicar no legado um UPDATE de "toque" (`reparar-por-toque.sh`, `excluido = excluido`) nas chaves da janela,
que o Extract captura inteiras (ALLCOLS). A variante do C10 automatiza isso (Replicat parado antes de religar o Extract, depois reposicionar, tocar as 269.694 chaves distintas (110.628 fonograma, 106.758 obra_musical, 52.308 titular; 301 comandos), drenar 491 s): ao final 0 divergências, 0 UUIDs alterados, Extract e Replicat RUNNING, supplemental restaurado idêntico ao inicial (`schemas_preparados=1`), autostart/autorestart do Extract conferido.
Conclusão para o desenho: **o supplemental logging (ALLCOLS) não é opcional**, e desligá-lo com captura ativa não é um modo de operação.

Perda no C10: o C10 Java deixava alterações sem replicação (captura de trigger desligada); aqui nada se perde porque o Extract retoma pelo redo: ao final, drenado com Extract ligado (107 s depois do 3º par, 491 s depois da variante), reconciliação SQL e jar Java 0 divergências, 0 UUIDs alterados de 2.011.552.
Tempos de reinício do Extract não entram na medição de custo (a fase ligada só começa com o pipeline drenado).

### Problemas e correções desta etapa

1. Stop do Extract desfeito pelo autostart/autorestart (acima); C8 reexecutado após corrigir.
2. Sonda da variante sem supplemental quebrou o Replicat (`OGG-30426`): reposição manual descrita acima (a sonda de 10 s foi feita antes do C6; o lab voltou a 0 divergências antes de medir).
3. `reparar-por-toque.sh`: primeira versão gerava linhas SQL*Plus > 5.000 caracteres; passou a quebrar a lista de chaves em linhas de 100.
4. `meta_gg.py` c10: `KeyError: 'dir'` na primeira consolidação (`ordem_das_fases`); corrigido e o meta regerado dos dados existentes (sem repetir a medição).
5. `c9-comparacao.sh`: seleção do diretório da execução (acima).

### Estado final do lab

gg-legado, gg-novo e gg-hub no ar; EXTBDAD e REPBDAD RUNNING com autostart/autorestart conferidos; parâmetros oficiais do Replicat restaurados; supplemental logging (`ADD SCHEMATRANDATA bdad ALLCOLS`) ativo; lab Java parado e intocado; `resultados/gg/relatorio.md` regenerado com C1 a C10.
