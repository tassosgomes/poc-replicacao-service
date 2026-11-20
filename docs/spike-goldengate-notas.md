
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
