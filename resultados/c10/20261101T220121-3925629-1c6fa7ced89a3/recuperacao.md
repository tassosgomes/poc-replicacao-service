# Recuperação operacional de C10

O coordenador registrou CalledProcessError e falha de limpeza durante a primeira fase.
Em 2026-11-01, após restabelecer o acesso aos hosts, o container próprio estava parado, exit 0.
Seu stdout completo e os CSVs foram recuperados em docker-logs-recuperados.log e gerador-recuperado/.
Somente esse container foi removido; os seis triggers de captura/updated_at estão ENABLED.
Os meta.json originais e o veredito INVALIDO foram preservados. Esta tentativa não mede C10 completo.
A causa específica da falha de comando não foi determinada; não há evidência para atribuí-la ao banco.
