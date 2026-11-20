# demo-ui: replicação BDAD → MAESTRO na ótica do usuário

Frontend simples (FastAPI + HTML/JS vanilla, sem build) para ver o GoldenGate replicando do legado (BDAD) para a réplica (MAESTRO).

## Como rodar

```
uv run demo-ui/app.py        # abre em http://localhost:8099
```

Requer só `uv` e Python 3.12+ (oracledb em thin mode, sem Oracle Client). Variáveis de ambiente (padrões do lab GG):
`DEMO_LEGADO_DSN` (`192.168.0.5:1523/FREEPDB1`), `DEMO_LEGADO_USER`/`DEMO_LEGADO_SENHA` (`bdad`),
`DEMO_REPLICA_DSN` (`192.168.0.11:1524/FREEPDB1`), `DEMO_REPLICA_USER`/`DEMO_REPLICA_SENHA` (`replica`), `DEMO_PORTA` (`8099`).

## Aviso importante

A UI **escreve DIRETO no legado** (simula o Delphi): cadastro, edição, exclusão lógica e física, tudo com COMMIT.
Na réplica ela **só faz SELECT**; quem escreve lá é o GoldenGate. Cada aba lê no máximo 50 linhas (índice de `updated_at`) e a réplica por `codigo IN (...)`; nunca há SELECT sem filtro.

## Status de cada linha

- **replicado**: a réplica tem a linha, `origem_updated_at` alcançou o `updated_at` da origem e os campos são iguais.
- **pendente**: a linha ainda não existe na réplica, ou a réplica está atrás (`origem_updated_at < updated_at`).
- **excluído**: igual à origem e com `excluido = 1`. DELETE físico na origem também resulta nisso: a réplica não apaga, marca `excluido = 1`.
- **divergente**: `origem_updated_at` em dia, mas algum campo difere. Não deveria acontecer.

Latência = `replicado_em - updated_at` (s). O relógio do WSL2 salta, então valores pequenos podem ter ruído de alguns segundos.
O painel "Acompanhando replicação" mede o tempo no navegador desde o COMMIT, com polling de 500 ms por até 60 s.
