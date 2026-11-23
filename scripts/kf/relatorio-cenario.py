#!/usr/bin/env python3
"""Gera resultados/kf/<cenario>/relatorio.md (curto) a partir de resultados/kf/<cenario>/meta.json. Uso: relatorio-cenario.py c1|c2|c3|c4|c5
Tabela de criterios, degraus, integridade e relogios; notas fixas do lab Kafka. Nao interpreta: a leitura fica em docs/spike-kafka-notas.md."""
import json, sys
from pathlib import Path

cen = sys.argv[1]
base = Path("resultados/kf") / cen
m = json.loads((base / "meta.json").read_text())
L = [f"# {cen.upper()} no lab Kafka (alternativa A)", "",
     f"Execucao `{m['execucao']}` ({m['inicio']} a {m['fim']}), veredito **{m['veredito']}**, validade {m['validade']['estado']} {m['validade']['motivos'] or ''}. Evidencias em `{m['diretorio']}`.", "",
     f"Pipeline: {m['ambiente']['pipeline']}.", "", "## Criterios", "", "| Criterio | Valor | Alvo | Veredito |", "|---|---|---|---|"]
for c in m["criterios"]:
    v = json.dumps(c["valor"], ensure_ascii=False)
    L.append(f"| {c['nome']} | {v[:200]} | {c['alvo']} | {c['veredito']} |")
if m["degraus"]:
    L += ["", "## Degraus", "", "| Alvo ev/s | Taxa efetiva | Validade | p95 (ms) | Eventos medidos | Crescimento sustentado | Drenagem (s) |", "|---|---|---|---|---|---|---|"]
    for p in m["degraus"]:
        L.append(f"| {p['alvo']} | {p['taxa']} | {p['validade']} | {p['p95_ms']} | {p['eventos_medidos']} | {p['tendencia_lag'].get('crescimento_sustentado')} | {p['drenagem_s']} |")
i = m["integridade"]
L += ["", "## Integridade", "",
      f"Divergencias (SQL): {i['divergencias']}; UUIDs alterados: {i['uuids_alterados']} de {(i.get('uuids') or {}).get('preexistentes_comparados')} comparados; "
      f"deletes fisicos convertidos: {i.get('deletes_fisicos_convertidos')}; lag final do consumer group: {i.get('lag_consumer_final')}.", "",
      f"Relogios (offset legado - novo, ms): {[(r['etapa'], r['skew_ms'], r['incerteza_ms']) for r in m['relogios']]}", "",
      "Notas: latencia = commit no kf-novo - `updated_at` da origem, corrigida pelo offset de relogios; reconciliacao SQL (`scripts/kf/reconciliar.sh`) e a oficial; "
      "o subcomando Java `reconciliacao` nao e usado (conta delete fisico convertido como divergencia). Regras de validade iguais as do lab Java/GG.", ""]
(base / "relatorio.md").write_text("\n".join(L))
print(base / "relatorio.md")
