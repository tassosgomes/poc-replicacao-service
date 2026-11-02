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
