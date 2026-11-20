#!/usr/bin/env python3
"""Analisa C6/C7: tempo ate o primeiro balde de 5 s com lag <= 5 s (com carga ativa) depois de uma queda do hub.
Uso: analisar-queda.py LATENCIAS_CSV ENTRADA_JSON OFFSET_MS SAIDA_DIR
LATENCIAS_CSV: linhas `aplicado_epoch_s,latencia_bruta_s` (relogio do infra-server) dos eventos da janela de geracao (latencia-janela.sh).
ENTRADA_JSON: {"ini":epoch_infra, "fim_carga":epoch_infra, "eventos":[{"nome","t_queda","t_reinicio","t_rest","t_ext","t_rep"}...]} (epochs do
infra-server, de hub-evento.sh). OFFSET_MS: relogio(infra) - relogio(desenv), subtraido da latencia (como latencia-janela.sh).
Regra: balde = 5 s de aplicado_em; lag do balde = maximo da latencia dos eventos aplicados nele; balde 'completo' = >= 50% da mediana de eventos por
balde no regime anterior a primeira queda; normalizado = primeiro balde completo cujo inicio >= t_reinicio, que termina antes do fim da carga (e da
proxima queda) e com lag <= 5 s. Tempo = fim do balde - t_queda (desde a queda) e fim do balde - t_reinicio (desde o reinicio)."""
import json, math, sys
from pathlib import Path

csv, ent, off, out = sys.argv[1], json.loads(Path(sys.argv[2]).read_text()), float(sys.argv[3]) / 1000, Path(sys.argv[4])
LIM = 5.0
pts = []
for line in Path(csv).read_text().splitlines():
    a, l = line.split(",")
    pts.append((float(a), float(l) - off))
pts.sort()
B = {}
for a, l in pts:
    B.setdefault(int(a // 5) * 5, []).append(l)
ev = ent["eventos"]
q0 = min(e["t_queda"] for e in ev)
base = sorted(len(v) for b, v in B.items() if ent["ini"] + 10 <= b and b + 5 <= q0 - 5)
med = base[len(base) // 2] if base else None
lag_base = max((max(v) for b, v in B.items() if ent["ini"] + 10 <= b and b + 5 <= q0 - 5), default=None)
res = {"mediana_eventos_por_balde_regime": med, "lag_max_regime_antes_s": None if lag_base is None else round(lag_base, 3),
       "baldes_regime": len(base), "metodo": "balde de 5 s de aplicado_em; lag = maximo da latencia (aplicado_em - origem_updated_at, corrigida pelo offset de relogios); completo >= 50% da mediana do regime; normalizado = primeiro balde completo com inicio >= reinicio, fim <= fim da carga, lag <= 5 s", "eventos": []}
ordem = sorted(ev, key=lambda e: e["t_queda"])
for i, e in enumerate(ordem):
    teto = min(ent["fim_carga"], ordem[i + 1]["t_queda"] if i + 1 < len(ordem) else ent["fim_carga"])
    r = dict(e)
    ap = [a for a, _ in pts]
    antes = [a for a in ap if a < e["t_queda"]]
    depois = [a for a in ap if a >= e["t_queda"]]
    r["ultima_aplicacao_antes_s"] = None if not antes else round(e["t_queda"] - antes[-1], 3)  # aplicacao mais recente antes da queda
    r["primeira_aplicacao_apos_queda_apos_reinicio_s"] = next((round(a - e["t_reinicio"], 3) for a in depois if a >= e["t_reinicio"]), None)
    norm = None; baldes = []
    if med:
        for b in sorted(B):
            if b < e["t_reinicio"] - 5 or b >= teto:
                continue
            v = B[b]; completo = len(v) >= 0.5 * med
            baldes.append({"inicio_s": round(b - e["t_queda"], 1), "n": len(v), "lag_max_s": round(max(v), 3), "completo": completo})
            if norm is None and b >= e["t_reinicio"] and b + 5 <= teto and completo and max(v) <= LIM:
                norm = b + 5
                r["balde_normalizado"] = {"inicio_s": round(b - e["t_queda"], 1), "n": len(v), "lag_max_s": round(max(v), 3)}
    r["t_normalizado"] = norm
    r["desde_queda_s"] = None if norm is None else round(norm - e["t_queda"], 2)
    r["desde_reinicio_s"] = None if norm is None else round(norm - e["t_reinicio"], 2)
    for k, a in (("rest", "t_rest"), ("extract_running", "t_ext"), ("replicat_running", "t_rep")):
        r[f"{k}_desde_reinicio_s"] = None if e.get(a) is None else round(e[a] - e["t_reinicio"], 2)
        r[f"{k}_desde_queda_s"] = None if e.get(a) is None else round(e[a] - e["t_queda"], 2)
    if norm is not None:
        pos = [b for b in baldes if b["inicio_s"] >= norm - e["t_queda"] and b["completo"]]
        viol = [b for b in pos if b["lag_max_s"] > LIM]
        r["estavel_apos_normalizar"] = not viol
        r["baldes_violando_apos"] = viol[:5]
        r["pico_lag_s"] = round(max((x["lag_max_s"] for x in baldes), default=0), 2)
    r["baldes"] = baldes
    res["eventos"].append(r)
(out / "queda.json").write_text(json.dumps(res, indent=1) + "\n")
print(json.dumps({"eventos": [{k: x.get(k) for k in ("nome", "desde_queda_s", "desde_reinicio_s", "rest_desde_reinicio_s", "extract_running_desde_reinicio_s", "replicat_running_desde_reinicio_s", "estavel_apos_normalizar", "pico_lag_s")} for x in res["eventos"]]}))
