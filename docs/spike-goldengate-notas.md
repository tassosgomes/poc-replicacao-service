
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
