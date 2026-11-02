**Autor:** Tasso Gomes (RASCUNHO redigido por Claude; vale somente após revisão e confirmação do autor)
**Data:** 2026-11-02
**Decisão:** AJUSTAR — manter o desenho, não aceitar o ADR ainda e repetir as medições de desempenho em ambiente representativo.

**O que a PoC comprovou**
- Integridade da carga inicial (C1): 1.854.195 linhas copiadas em 109,9 s, sem divergência e sem UUID regerado.
- Correção de lacunas (C8): 55.629 de 55.629 chaves corrigidas (100%), sem divergência e sem UUID alterado.
- Mecanismo de fencing do lease (dono, versão e prazo, medidos pelo relógio do banco): mutantes de versão e prazo mortos nos testes da task 8.0.

**O que a PoC não comprovou**
- Latência, vazão, backlog, recuperação de falha e failover sob carga (C2–C7 e C9): todos saíram INVALIDO/TAXA, porque o gerador não sustentou a taxa-alvo. Não há evidência a favor nem contra o desenho nesses pontos. As séries de recursos indicam o legado Oracle perto do limite de 2 CPUs; é indício de gargalo do ambiente, não causa demonstrada.
- Failover A→B: nos testes locais (task 8.0, aceita por mim com ressalvas), mín. 10,7 s, p50 14,5 s, p90 16,5 s e máx. 20,02 s em 17 medições, com uma acima de 20 s. O gate é intermitente. Nada disso foi medido com `docker kill/pause` e `--cpus`.
- Custo no legado (C10): overhead de 19,998% (0,958 ms) contra o critério proposto de ≤15% E ≤5 ms. Passa nos 5 ms e reprova nos 15%. O critério (Q-T3) ainda não foi confirmado pelo time do legado; o percentual é alto por a base ser pequena em ms, e deve ser interpretado com essa confirmação.
- Inspeção visual do painel Kibana e SLA de 30 s de publicação: não executados.

**Riscos**
- Consistência vence disponibilidade (decisão de projeto): o failover A→B é exceção e não deve ser acelerado à custa de consistência.
- O ambiente de teste (Oracle Free, limitado a 2 CPUs pela própria edição, hosts compartilhados, WSL2 com salto de relógio) não representa a produção. Os números não se transferem a PostgreSQL.
- A conta Elastic tem escrita irrestrita; a restrição ao namespace da PoC é feita só por código.

**Próximos passos propostos**
1. Repetir C2–C7 e C9 no servidor de verdade (hardware e banco representativos), com os mesmos critérios, sem afrouxar limites.
2. Confirmar com o time do legado o critério de custo (Q-T3) e as questões Q1–Q3, Q5, Q6 do PRD.
3. Só então decidir sobre o ADR: manter `docs/adr-replocacao.md` como Proposto até as medições válidas; promover a `docs/adr/adr-001` (Accepted) se C2–C7 e C9 passarem.
4. Opcional: spike pequeno da alternativa de replicação dentro do Oracle (database link + job por partição), medindo latência e vazão antes de decidir; não foi medida nesta PoC.

**Compromissos de SLA possíveis com os dados atuais (rascunho)**

| Item | Compromisso possível | Base | Confiança |
|---|---|---|---|
| Integridade | 0 divergências e 0 UUIDs alterados | C1 e C8 válidos; os inválidos também deram 0 | Alta |
| Carga inicial | Cerca de 1,85 milhão de linhas em menos de 2 min (medido: 110 s) | C1 válido | Média (um ambiente, Oracle Free) |
| Correção da captura desligada | 100% das linhas afetadas corrigidas | C8 válido (55.629 de 55.629) | Alta para esse volume |
| Custo no legado | Overhead médio ≤ 5 ms (medido: 0,96 ms); percentual (20%) pendente do critério do legado (Q-T3) | C10 válido | Alta em ms |
| Latência de replicação | Meta provisória: p95 ≤ 5 s (medido: ~0,22–0,27 s, indicativo) | C2/C3 | Baixa até repetir no servidor real |
| Vazão | Demonstrado de forma válida: 500 ev/s (p95 ~0,22 s, sem crescimento sustentado do lag). Indicativo: ~770–790 ev/s, onde o gerador saturou. Não comprometer 1.000 nem 2.000 ev/s | C3 (degrau de 500 válido), C2/C3/C9 | Baixa acima de 500 ev/s |
| Rajada de 50 mil linhas | Meta provisória: recuperação ≤ 120 s (medido: 15 s, indicativo) | C4 inválido | Baixa |
| Failover A→B | Não prometer 20 s; meta provisória ≤ 60 s | Testes da 8.0: p50 14,5 s, p90 16,5 s, máx. 20,02 s; C7 sob carga: 30 s | Baixa a média |

500 ev/s é o único degrau de vazão com medição válida, e não o teto do sistema: acima disso quem saturou foi o gerador de carga. Em C9 houve crescimento sustentado do lag (+2,2 ms/s) a ~767 ev/s, que merece investigação no servidor real.

**Volumes reais do BDAD (documento de sizing CDC, auditoria de 08/05/2026, métrica corrigida: 1 evento por operação)**

Fonte: levantamento fornecido por Tasso Gomes, não verificado pela PoC. Responde em parte à questão Q6 (pico real), antes estimada em 1.000 ev/s.

| Tabela | Eventos/mês | Parte do total |
|---|---:|---:|
| OBRA_TITULAR | 1.518.835 | 16% |
| OBRA_MUSICAL | 687.536 | 7% |
| TITULAR | 194.205 | 2% |
| Soma das três | ~2,4 milhões | ~25% |

- Picos do conjunto de todas as tabelas: 3.514 ev/s em 1 s, 2.523 ev/s em 1 min, 1.295 ev/s em 10 min. A média mensal do total (9,4 milhões) equivale a cerca de 3,6 ev/s; o documento cita "~110 ev/s" para a média de 24 h, divergência a confirmar com quem fez o levantamento.
- Estimativa proporcional para as três tabelas (25% do pico): cerca de 900 ev/s em 1 s, 640 ev/s em 1 min e 330 ev/s em 10 min. Pior caso (todo o pico nessas tabelas): 3.514, 2.523 e 1.295 ev/s. Os picos não são necessariamente proporcionais; isto é estimativa.
- Leitura contra a PoC: os 500 ev/s válidos cobrem o pico de 10 min proporcional e ficam abaixo do pior caso; o patamar indicativo de cerca de 770 ev/s cobre o pico de 1 min proporcional. Picos de 1 s acima de 3.000 ev/s viram backlog de segundos; a rajada do C4 (recuperação indicativa de 15 s para 50 mil linhas) sugere que isso se absorve, mas não foi medido de forma válida.
- **Lacuna de escopo:** OBRA_TITULAR, a maior das três em volume e a tabela de relação, não faz parte da PoC (a carga do C1 cobre TITULAR, OBRA_MUSICAL e FONOGRAMA). O desenho não foi exercitado nela.

**Decisão adicional (2026-11-02):** executar um spike que inclua OBRA_TITULAR na PoC e repita carga e cenários no servidor real com o perfil de pico acima (1 s, 1 min e 10 min), antes de promover o ADR.
