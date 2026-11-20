#!/usr/bin/env python3
"""Analisa o CSV (aplicado_epoch_s,latencia_s) de scripts/gg/latencia-janela.sh. Imprime JSON.
Percentis nearest-rank sobre os eventos da janela de geracao; tendencia pela mesma regra do runner Java
(scripts/cenarios/analise.py: regressao nos ultimos 180 s, inclinacao > 0 e tres blocos com medias crescentes)
sobre o pior caso (maximo) da latencia por balde de 5 s de aplicado_em, o analogo do lag instantaneo."""
import json, math, sys
from datetime import datetime, timezone
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cenarios"))
import analise  # noqa: E402

csv, ini, fim, off = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]) / 1000
pts = []
for line in Path(csv).read_text().splitlines():
    a, l = line.split(",")
    pts.append((float(a), float(l) - off))
def rank(v, p): return v[max(0, math.ceil(len(v) * p) - 1)]
if not pts:
    print(json.dumps({"n": 0})); sys.exit(0)
lat = sorted(l for _, l in pts)
out = {"n": len(lat), "min_s": round(lat[0], 3), "p50_s": round(rank(lat, .5), 3), "p95_s": round(rank(lat, .95), 3),
       "p99_s": round(rank(lat, .99), 3), "max_s": round(lat[-1], 3), "media_s": round(sum(lat) / len(lat), 3),
       "offset_relogio_ms": off * 1000, "metodo": "nearest-rank; aplicado_em - origem_updated_at; eventos com origem_updated_at na janela de geracao"}
buckets = {}
for a, l in pts:
    buckets.setdefault(int(a // 5) * 5, []).append(l)
iso = lambda t: datetime.fromtimestamp(t, timezone.utc).isoformat().replace("+00:00", "Z")
# baldes parciais (inicio/fim da janela, bem menos eventos que a mediana) ficam fora da tendencia: nao sao regime
mediana_n = sorted(len(v) for v in buckets.values())[len(buckets) // 2]
ordered = [b for b in sorted(buckets) if len(buckets[b]) >= 0.5 * mediana_n]
samples = [{"ts": iso(b + 2.5), "lag_ms": max(buckets[b]) * 1000} for b in ordered]
mean_samples = [{"ts": iso(b + 2.5), "lag_ms": sum(buckets[b]) / len(buckets[b]) * 1000} for b in ordered]
end = datetime.fromisoformat(samples[-1]["ts"].replace("Z", "+00:00")).timestamp()
# o fim da janela de aplicacao e o ultimo balde com eventos; usa os baldes ate a ultima aplicacao dos eventos da janela
out["tendencia_max_por_5s"] = analise.trend(samples, ordered[0], end)
out["tendencia_media_por_5s"] = analise.trend(mean_samples, ordered[0], end)
out["baldes_5s"] = [{"t": b - ordered[0], "n": len(buckets[b]), "media_s": round(sum(buckets[b]) / len(buckets[b]), 3),
                     "max_s": round(max(buckets[b]), 3)} for b in ordered]
print(json.dumps(out))
