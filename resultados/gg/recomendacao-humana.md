**Autor:** Tasso Gomes (RASCUNHO redigido por Claude; vale somente após revisão e confirmação do autor)
**Data:** 2026-11-20
**Decisão:** PENDENTE (seguir / ajustar / descartar)

**O que a PoC GG comprovou** (lab GoldenGate Free, Replicat clássico, uma execução por cenário; comparativo em `resultados/comparativo-java-gg.md`)
- Carga inicial por SCN (C1): 2.011.552 linhas em 565,9 s, 0 divergências, 0 UUIDs alterados. O tempo é ~4,7x mais lento em linhas/s que o do worker Java; otimizações não foram medidas.
- Regime e vazão (C2, C3): p95 2,82 s a 1.000 ev/s por 600 s; teto de 2.000 ev/s com p95 2,09 s (4.000 ev/s inválido por saturação do gerador). Piso no Free, sem Parallel Replicat, com BATCHSQL.
- Rajada e transação longa (C4, C5): recuperação em 17,9 s; as demais transações com p95 2,88 s durante os 30 s e a longa aplicada 1,84 s após o commit.
- Queda e captura parada (C6, C8): 0 divergências depois da queda do hub; 55.904 de 55.904 chaves corrigidas pelo redo, sem reconciliação.
- Identidade: 0 UUIDs alterados em todos os cenários; exclusão lógica por `UPDATEDELETES`; UUIDv7 por trigger no destino com `NOSUPPRESSTRIGGERS`.
- Custo no legado (C10, repetido com aquecimento descartado e ordem balanceada): +0,102 ms, +1,74% contra o critério proposto de <= 15% E <= 5 ms (Q-T3 pendente). O efeito não se distingue do ruído entre fases.

**O que a PoC GG não comprovou**
- Failover (C7, adaptado): 25,8 s contra 20 s, REPROVADO. Mede o reinício do hub único (processos RUNNING em ~10 s, mais ~10 s de catch-up e a granularidade do balde); não mede failover entre hubs. HA de produção (XAG/RAC, hub secundário) não foi testada.
- Superioridade sobre o worker Java: o lab Java tem C2 a C7 e C9 INVALIDO/TAXA, então não há evidência a favor nem contra o worker nesses pontos. Só o GG tem evidência válida. O C10 Java (+0,958 ms, +19,99%) não foi repetido com aquecimento descartado e pode ter o mesmo viés.
- Desempenho com Parallel Replicat, Distribution Path, produção Enterprise e com o Extract de analytics que o BDAD já tem (não simulado).
- Custo isolado do supplemental logging (FORCE LOGGING e ALLCOLS ativos nas duas fases do C10; sem supplemental o Replicat abenda com OGG-30426).
- Replicat coordenado: não medido, pois THREADRANGE pode dividir uma transação de origem entre threads.
- Todos os resultados vêm de uma execução por cenário; a latência a 1.000 ev/s usa 66% a 80% da margem em casos isolados (max 4,28 s no C2; evento isolado de 5,24 s no C2 curto).

**Riscos**
- Consistência vence disponibilidade (decisão de projeto): o failover é exceção e não deve ser acelerado à custa de consistência. O Replicat coordenado foi descartado por isso.
- O BDAD real já tem GoldenGate para analytics. Licença por processador, versão, edição, quem opera e se um segundo Extract ou o trail existente podem ser usados não foram confirmados com o DBA; isso muda custo e viabilidade.
- Operação: autostart desfaz STOP manual do Extract; sem supplemental o pipeline para; archive logs crescem com Extract parado (sem RMAN na imagem, a limpeza foi por script). Exige skill de GoldenGate no time.
- O ambiente (Oracle Free 2 CPU, hub único, hosts compartilhados, WSL2) não representa a produção; os números são piso.
- Qualquer decisão depende do critério de custo (Q-T3), ainda não confirmado pelo time do legado.

**Próximos passos propostos**
1. Responder as perguntas ao DBA (seção (e) do comparativo): GoldenGate existente, licença, segundo Extract ou trail compartilhado, impacto do log mining no BDAD.
2. Se a alternativa D seguir, medir em ambiente com Parallel Replicat e HA (ou com hub secundário) o C7 sem adaptação, mantendo o critério de 20 s.
3. Repetir C2 a C7 e C9 do worker Java em ambiente sem saturação de CPU, e o C10 Java com aquecimento descartado e ordem balanceada, para ter comparação válida.
4. Repetir os cenários do GG mais de uma vez (intervalo de confiança) e medir as otimizações da carga inicial e o custo do supplemental.
5. Só então decidir sobre o ADR (`docs/adr-replocacao.md` segue Proposto).
