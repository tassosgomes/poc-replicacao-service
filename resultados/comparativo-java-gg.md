# Comparativo Java x GoldenGate por critério do PRD

Data: 2026-11-20. Fontes: [`relatorio.md`](relatorio.md) e [`resultados/gg/relatorio.md`](gg/relatorio.md) (regenerado com
`bash scripts/relatorio.sh --resultados resultados/gg`: C1 a C10 válidos, C7 REPROVADO, os demais APROVADOS),
`docs/spike-goldengate-notas.md` e os `meta.json` linkados abaixo. Este documento organiza evidências; não escolhe a alternativa
(ver [`gg/recomendacao-humana.md`](gg/recomendacao-humana.md), rascunho).

Leitura principal: no lab Java só C1, C8 e C10 têm medição válida; C2 a C7 e C9 saíram INVALIDO/TAXA. **Onde o Java é inválido
não há evidência a favor nem contra o worker; só o GG tem evidência válida nesses pontos.** Isso não é vitória do GG sobre o Java.

## Tabela por critério

"Inválido" = INVALIDO/TAXA no `meta.json` Java (valores não publicados no relatório).

| Critério | Alvo | Lab Java | Lab GG | Comparável? |
|---|---|---|---|---|
| C1 carga inicial | 0 divergências, 0 UUID regerado; tempo registrado | 109,9 s, 0 divergências, 0 UUIDs alterados, APROVADO ([c1](c1/meta.json)) | 565,9 s para 2,01 M linhas (~3.554 linhas/s), 0 divergências, 0 UUIDs alterados, APROVADO ([c1](gg/c1/meta.json)) | Integridade sim; tempo só com ressalva (b) |
| C2 regime | 1.000 ev/s por 600 s; p95 <= 5 s; lag sem tendência | Inválido, sem evidência ([c2](c2/meta.json)) | p95 2,82 s (p50 1,85; max 4,28), +2,0 ms/s, APROVADO ([c2](gg/c2/meta.json)) | Não: só GG tem medição válida |
| C3 teto | >= 2.000 ev/s em degraus de 300 s | Inválido ([c3](c3/meta.json)) | Teto 2.000 ev/s, p95 2,09 s; 4.000 ev/s inválido por GERADOR_SATURADO (3.858 ev/s), APROVADO ([c3](gg/c3/meta.json)) | Não: só GG |
| C4 rajada | 50.000 linhas; recuperação <= 120 s; sem perda | Inválido ([c4](c4/meta.json)) | Recuperação 17,9 s, 0 divergências, APROVADO ([c4](gg/c4/meta.json)) | Não: só GG |
| C5 transação longa | 30 s aberta; p95 das demais <= 5 s; aplicação <= 5 s após commit | Inválido ([c5](c5/meta.json)) | p95 das demais 2,88 s; aplicação 1,84 s após o commit, APROVADO ([c5](gg/c5/meta.json)) | Não: só GG; protocolo diferente (b) |
| C6 queda | 0 divergências; lag normaliza | Inválido ([c6](c6/meta.json)) | Normaliza 112,7 s da queda / 52,6 s do reinício, 0 divergências, APROVADO ([c6](gg/c6/meta.json)) | Não: só GG |
| C7 failover | <= 20 s | Inválido ([c7](c7/meta.json)) | 25,8 s (processos RUNNING em ~10 s), REPROVADO, adaptado ([c7](gg/c7/meta.json)) | Não; e o GG mede reinício do hub, não failover |
| C8 captura parada | 100% corrigido | 55.629 de 55.629 (100%), APROVADO, por reconciliação com reenfileiramento ([c8](c8/meta.json)) | 55.904 de 55.904 (100%) pelo redo, sem reconciliação, APROVADO ([c8](gg/c8/meta.json)) | Sim no resultado; mecanismos diferentes |
| C9 threads | Descritivo (SEM_CRITERIO) | Inválido (modos de threads do worker) ([c9](c9/meta.json)) | BATCHSQL sustenta 2.000 ev/s; sem ele o lag chega a 57 s; descritivo ([c9](gg/c9/meta.json)) | Não: compara coisas diferentes |
| C10 custo no legado | <= 15% E <= 5 ms (Q-T3 pendente) | +0,958 ms, +19,99%, REPROVADO ([c10](c10/meta.json)) | +0,102 ms, +1,74%, APROVADO ([c10](gg/c10/meta.json)) | Parcial: ver (b) sobre aquecimento |

Dois pontos de leitura:

- A comparação válida hoje é C1 (integridade), C8 e C10. C10 é o único em que os dois têm número válido e os vereditos diferem,
  e o número Java pode ter o viés descrito em (b).
- O C6 Java tem número no `meta.json` (111,8 s da queda), mas o cenário é INVALIDO/TAXA (regime a 885 ev/s) e não entra como evidência.

## (a) O que cada lab mediu e o que não é comparável

- **Lab Java:** captura por trigger + outbox no legado, worker de duas pernas com lease. Taxa-alvo não foi sustentada em C2 a C7 e C9;
  as séries de recursos indicam o legado perto do teto de 2 CPUs (indício, não causa demonstrada).
- **Lab GG:** Extract integrado lendo o redo, Replicat clássico no hub, sem trigger de captura no legado. O gerador fechou 1.000 e 2.000 ev/s
  com e sem Extract (calibração, `docs/spike-goldengate-notas.md`), então os cenários saíram válidos.
- **Não comparável:** latência, teto, rajada, transação longa, queda, failover e threads (C2 a C7, C9). Não há medição Java válida;
  dizer que o GG "venceu" seria falso. Também não se infere que o worker não sustentaria a carga: a invalidação veio do ambiente (legado com trigger perto de 2 CPUs).
- **C9:** o Java comparava threads do worker; o GG compara parâmetros do Replicat clássico (GROUPTRANSOPS, BATCHSQL). Não são a mesma pergunta.
- **C7:** no Java há dois workers e lease; no GG há um hub único que reinicia. Não é comparação de mecanismo de failover.

## (b) Diferenças de ambiente e protocolo

- **Igual nos dois labs:** imagem `gvenzl/oracle-free:23-slim-faststart`, `--cpus=2 --memory=3g` nos bancos, mesmos redo logs (4 x 512 MB), mesma massa
  (200k/500k/1M linhas iniciais), gerador de 2 CPU/1 GiB, mesmos critérios e relógios medidos. Hosts compartilhados e WSL2 nos dois.
- **Origem sem trigger de captura no GG.** O legado GG mantém só os triggers `*_updated` que simulam o Delphi; não há `*_captura` nem `evento_replicacao`.
  Isso explica em parte por que o gerador fecha a taxa lá e não no Java, e faz do C10 uma comparação entre mecanismos (trigger x Extract), não de configuração.
- **Massa cresce entre cenários.** O C1 do GG rodou com 2,01 M linhas (o legado já tinha recebido inserções dos cenários anteriores); o C1 Java com ~1,85 M.
- **C10:** o GG foi repetido com aquecimento de 300 s descartado, 600 s por fase e 4 pares em ordem balanceada (L D D L L D D L). A primeira execução GG (3 pares de 300 s,
  sempre ligada primeiro) deu +0,726 ms / +13,04% e estava inflada pelo aquecimento da primeira fase ligada. **O C10 Java não teve aquecimento descartado nem ordem balanceada:
  o +0,958 ms / +19,99% pode ter o mesmo viés e não foi repetido.** Não se pode concluir que a captura por trigger custa mais que o Extract sem repetir o Java com o mesmo protocolo.
  No GG o ruído entre fases (desvio ~0,45 ms, ~8%) é maior que o efeito medido; leitura correta: custo pequeno, da ordem de 0 a 1 ms, não distinguível do ruído com 4 pares.
- **C5 adaptado:** o modo `TX_ABERTA_SEG` do gerador lê `evento_replicacao`, que não existe no lab GG; a transação longa é um `UPDATE` + `dbms_session.sleep(30)` + `COMMIT` por SQL (`sql/gg/testes/tx_longa.sql`).
- **C7 adaptado:** sem segunda réplica no Free; mede o reinício do hub único (`adaptado: true` no meta).
- **C8:** no GG o Extract é parado com autostart desabilitado e confirmado `stopped`; nada é reenfileirado nem reconciliado.
- **Carga inicial (C1):** o GG usou `INSERT ... AS OF SCN` via DB link com UUIDv7 por trigger e `aplicacao_log` por linha; o GG Initial Load e `expdp` não foram usados.
  Resultado ~4,7x mais lento em linhas/s (~3.554 contra ~16.800); em tempo total, 565,9 s contra 109,9 s, com massas diferentes. Otimizações (desligar o log durante a carga, sessões paralelas,
  direct-path com UUID no SELECT) estão descritas nas notas e **não foram medidas**.
- **Latência por evento no GG** = `aplicado_em` menos `origem_updated_at` (`COMMITTIMESTAMP` tem resolução de 1 s), com `aplicacao_log` alimentado por trigger no destino; a medida inclui esse custo.

## (c) Limites do Free que não vão para o desenho

Todos tratados como piso; nenhum é extrapolado para produção (o desenho excalidraw representa Enterprise):

- Sem Parallel Replicat: foi usado Replicat clássico (uma thread de aplicação). O teto de 2.000 ev/s é um piso.
- Oracle Free de 2 CPU/2 GB de SGA e hub de 2 CPU/4 GiB; o mining roda dentro das 2 CPUs do legado e disputa com o gerador.
- Hub único, local, sem Distribution Path e sem HA (daí o C7 adaptado).
- Imagem slim sem `rman`: a limpeza de archive foi feita por script (`scripts/gg/limpar-archive.sh`) respeitando o `required_checkpoint_scn`. Em produção seria RMAN.
- Sem Veridata e sem suporte Oracle: a conferência é a reconciliação da PoC (hash de 32 bits, probabilístico).
- Replicat coordenado com THREADRANGE não foi medido (ver (d)).

## (d) Achados operacionais

- **Autostart desfaz STOP manual.** O Extract "parado" voltou a rodar ~6 s depois por `managedProcessSettings`; a primeira execução do C8 foi descartada. Correção: desabilitar autostart/autorestart, parar, confirmar `stopped` 12 s depois, e reabilitar ao religar.
  Ressalva: as medições da Fase 3 com "Extract parado" não registraram essa confirmação.
- **Sem supplemental logging o Replicat abenda** (`OGG-30426`, coluna-chave ausente no trail). Nada se perde em silêncio, mas o pipeline para; a recuperação exigiu reposicionar o Replicat e reaplicar "toques" nas chaves da janela.
  O supplemental (ALLCOLS) é obrigatório, não opcional.
- **Replicat coordenado com THREADRANGE** pode dividir uma transação de origem entre threads (documentação Oracle), quebrando a atomicidade exigida. Funciona no spike (4 threads) mas foi descartado; o REPCO.prm tem aviso.
- **BATCHSQL sustenta 2.000 ev/s.** Sem ele o clássico fica atrás (p95 54,8 s, lag até 57 s no C9). GROUPTRANSOPS 100 x 1000 empata a 1.000 ev/s; a 2.000 a diferença (p95 4,0 x 2,2 s) está dentro do ruído de uma execução. Parâmetros oficiais mantidos.
- **EOFDELAYCSECS reduziu a latência de base** (carga leve: p50 2,56 -> ~1,15 s; p95 3,29 -> ~1,7 s), ao custo de CPU do hub em repouso (9,7% -> 17,2%). A 1.000 ev/s pouco muda; o efeito vem de BATCHSQL/GROUPTRANSOPS.
- Automação: REST do Admin Service cobre quase tudo; só `START REPLICAT ... AFTERCSN` exigiu `adminclient`. Após `docker kill` os processos só voltam com autostart configurado (~10 s).
- Archive logs: um Extract parado por muito tempo deixa `required_checkpoint_scn` parado e o archive cresce até o alarme (comportamento correto, mas exige vigilância).

## (e) Contexto de produção: o BDAD já tem GoldenGate

Os DDLs reais mostram supplemental logging ALL e grupos `GGS_*` em OBRA_MUSICAL, OBRA_TITULAR e TITULARIDADE, o que indica um GoldenGate em uso para analytics.
Nesta PoC a nossa replicação foi considerada a única: o lab não simula esse Extract concorrente. Consequências:

- **Custo no legado (C10):** o +1,74% medido é de um Extract isolado. Em produção parte do custo (supplemental logging, archivelog, force logging) já é pago; um segundo Extract ou a reutilização do existente muda a conta, e esse efeito não foi medido.
- **Licença:** GoldenGate é licenciado por processador na origem e no destino. Se já existe licença on-prem, o BYOL reduz a estimativa de `docs/custo-confluent-vs-oci-goldengate.md` (~US$ 236 por OCPU/mês contra o preço com licença incluída). Não substitui cotação.
- **Skill e operação:** a casa já tem quem opere GoldenGate, o que pesa a favor da alternativa D (custo de skill menor que o do desenho sem GG). Precisa ser confirmado com o DBA.

Perguntas para o DBA:

1. Versão e edição do GoldenGate em produção; quem opera; qual o plano de patch/upgrade?
2. Licença por processador já paga cobre origem e destino do nosso caso? Serve para BYOL na OCI?
3. Podemos adicionar um segundo Extract no mesmo hub/BDAD, ou o trail existente pode ser compartilhado? Qual o impacto de um segundo Extract no log mining do BDAD?
4. O BDAD já está em ARCHIVELOG com FORCE LOGGING? Qual o volume diário de redo e a política de retenção/limpeza de archive?
5. O supplemental logging ALL existente cobre TITULAR, OBRA_MUSICAL e FONOGRAMA ou só as três tabelas citadas?
6. Existe Parallel Replicat e HA (XAG/RAC) no ambiente atual? Qual o RTO aceito para falha do hub (C7)?
7. Confirma o critério de custo no legado (Q-T3)?

## (f) Riscos e pendências

- **Execuções únicas.** Cada cenário rodou uma vez (C10 GG, 4 pares). Sem repetição não há intervalo de confiança; um evento isolado passou de 5 s no C2 (max 4,28 s na execução final, 5,24 s no curto).
- **C7 REPROVADO (25,8 s contra 20 s).** O número mede reinício do hub único até lag normal: ~10 s para os processos voltarem, ~10 s de catch-up e a granularidade do balde de 5 s. Não mede failover entre hubs; HA de produção ficou fora. Aprovaria "processos RUNNING" (~10 s) e reprova "lag normal".
- **Custo do supplemental logging não isolado.** FORCE LOGGING e ALLCOLS ficam ativos nas duas fases do C10; a variante sem supplemental foi inconclusiva e quebra o Replicat.
- **Carga inicial ~4,7x mais lenta** que a do Java; otimizações e `expdp`/Initial Load não medidos.
- **C10 Java sem repetição** com aquecimento descartado e ordem balanceada; o veredito REPROVADO do Java pode mudar.
- **Q-T3 pendente:** o critério de custo (<= 15% E <= 5 ms) continua proposto, sem confirmação do time do legado.
- Alternativa B/C (job e DB link dentro do Oracle) segue não medida.
- Sem simulação do Extract de analytics existente, sem Distribution Path, sem Parallel Replicat.
- Hash da reconciliação é probabilístico (32 bits); sem teste negativo induzindo divergência no GG.
