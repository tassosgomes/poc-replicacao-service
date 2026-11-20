#!/usr/bin/env python3
"""Monta o meta.json de uma execucao GG no mesmo contrato do lab Java (resultados/cN/meta.json), para que
`bash scripts/relatorio.sh --resultados resultados/gg` consolide. Uso: meta_gg.py DIR_EXECUCAO
DIR_EXECUCAO tem `cenario.json` (escrito por cenario.sh) e, por degrau, `dN-<taxa>/` com gerador.log, latencia.json,
recursos-*.csv, relogio-inicio.json, relogio-fim.json, drenagem.txt. Mais: reconciliacao-sql.txt, reconciliacao-java.log,
uuids.json, archive-antes.json, archive-depois.json, containers.json. Saida: meta.json. Exit 0 aprovado, 1 caso contrario.
Regras (README do lab Java): degrau valido = taxa >= 98% do alvo, senao GERADOR_SATURADO (c3) / INVALIDO; teto = maior alvo valido
sem crescimento sustentado do lag; teto so aprova com degrau valido de 2000 ev/s; sem dado = INVALIDO, nunca APROVADO."""
import json, re, sys
from pathlib import Path

D = Path(sys.argv[1])
cfg = json.loads((D / "cenario.json").read_text())
cen = cfg["cenario"]


def crit(nome, valor, alvo, ok):
    v = "INVALIDO" if valor is None else ("APROVADO" if ok else "REPROVADO")
    return {"nome": nome, "valor": valor, "alvo": alvo, "veredito": v}


def load(p, default=None):
    try:
        return json.loads((D / p).read_text())
    except (OSError, ValueError):
        return default


def cpu_stats(dd):
    acc = {}
    for f in dd.glob("recursos-*.csv"):
        for line in f.read_text().splitlines():
            t = line.split(",")
            if len(t) >= 3:
                n = re.sub(r"gg-gerador-\d+", "gerador", t[1])
                try:
                    v = float(t[2].rstrip("%"))
                except ValueError:
                    continue
                e = acc.setdefault(n, [0, 0.0, 0.0]); e[0] += 1; e[1] += v; e[2] = max(e[2], v)
    return {k: {"media_pct": round(v[1] / v[0], 1), "max_pct": v[2], "amostras": v[0]} for k, v in acc.items()}


degraus, relogios = [], []
for step in cfg["degraus"]:
    dd = D / step["dir"]
    log = (dd / "gerador.log").read_text() if (dd / "gerador.log").exists() else ""
    m = re.search(r"linhas=(\d+) transacoes=(\d+) taxa=([\d.]+) alvo=(\d+) transacao_media_ms=([\d.]+)", log)
    lat = load(f"{step['dir']}/latencia.json", {}) or {}
    ph = {"grupo": cen, "linhas": None, "transacoes": None, "taxa": None, "alvo": step["alvo"], "valido": False,
          "inicio": step.get("inicio_iso"), "fim": step.get("fim_iso"), "validade": "INVALIDO", "p95_ms": None,
          "eventos_medidos": lat.get("n", 0), "tendencia_lag": {"inclinacao_ms_s": None, "crescimento_sustentado": None, "janela_s": 0, "amostras": 0},
          "drenagem_s": step.get("drenagem_s"), "cpu_docker_stats": cpu_stats(dd)}
    if m:
        taxa = float(m[3])
        ph.update(linhas=int(m[1]), transacoes=int(m[2]), taxa=taxa, transacao_media_ms=float(m[5]), valido=taxa >= step["alvo"] * .98)
        ph["validade"] = "VALIDO" if ph["valido"] else ("GERADOR_SATURADO" if cen == "c3" else "INVALIDO")
    if lat.get("n"):
        ph["p95_ms"] = round(lat["p95_s"] * 1000)
        ph["latencia_s"] = {k: lat[k] for k in ("min_s", "p50_s", "p95_s", "p99_s", "max_s", "media_s")}
        ph["tendencia_lag"] = lat["tendencia_max_por_5s"]
        ph["tendencia_lag_media"] = lat["tendencia_media_por_5s"]
        ph["lag_final_s"] = lat.get("baldes_5s", [{}])[-1].get("max_s")
    degraus.append(ph)
    for tag in ("inicio", "fim"):
        r = load(f"{step['dir']}/relogio-{tag}.json")
        if r:
            relogios.append({"etapa": f"{step['dir']}-{tag}", "ts": step.get(f"{tag}_iso", ""),
                             "skew_ms": r["offset_ms"], "incerteza_ms": r["incerteza_ms"]})

# integridade
sql_txt = (D / "reconciliacao-sql.txt").read_text() if (D / "reconciliacao-sql.txt").exists() else ""
java_txt = (D / "reconciliacao-java.log").read_text() if (D / "reconciliacao-java.log").exists() else ""
mj = re.search(r"^total=(\d+) tempo_ms", java_txt, re.M)
div_java = int(mj[1]) if mj else None
if "0 divergencias" in sql_txt:
    div_sql = 0
elif "DIVERGENCIAS" in sql_txt:
    div_sql = sum(1 for l in sql_txt.splitlines() if l.startswith("<"))
else:
    div_sql = None
div = None if (div_java is None or div_sql is None) else div_java + div_sql
uu = load("uuids.json")
uuid_alt = uu["uuids_alterados"] if uu and uu.get("preexistentes_comparados", 0) > 0 else None
drenado = all(s.get("drenagem_s") is not None for s in cfg["degraus"]) and bool(cfg["degraus"])
integ = {"pendentes_inicial": 0, "snapshot_antes": "../../c1/snapshots/apos-carga.csv", "pendentes_final": 0 if drenado else None,
         "divergencias": div, "divergencias_sql": div_sql, "divergencias_java": div_java, "reconciliacao": "reconciliacao-java.log",
         "reconciliacao_sql": "reconciliacao-sql.txt", "snapshot_depois": "snapshots/apos.csv", "uuids_alterados": uuid_alt,
         "uuids": uu, "diferencas": "reconciliacao-sql.txt"}

validos = [p for p in degraus if p["grupo"] == cen and p["valido"]]
criterios = [crit("divergencias", div, 0, div == 0), crit("uuids_alterados", uuid_alt, 0, uuid_alt == 0),
             crit("pendentes_final", integ["pendentes_final"], "0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s)", integ["pendentes_final"] == 0)]
medidas = {"eventos_medidos": sum(p["eventos_medidos"] for p in degraus), "relogios_validos": True}
if cen == "c2":
    p = degraus[0] if degraus else {}
    v = p.get("p95_ms") if p.get("valido") else None
    criterios.append(crit("p95_ms", v, "<=5000", v is not None and v <= 5000))
    tr = p.get("tendencia_lag", {}) if p.get("valido") else {}
    rising = tr.get("crescimento_sustentado")
    criterios.append(crit("tendencia_lag", tr if rising is not None else None, "sem crescimento sustentado", rising is False))
    medidas["p95_ms"] = p.get("p95_ms")
else:
    cand = [p["alvo"] for p in validos if p["tendencia_lag"]["crescimento_sustentado"] is False]
    suficiente = bool(validos) and all(p["tendencia_lag"]["crescimento_sustentado"] is not None for p in validos)
    teto = max(cand, default=0) if suficiente else None
    c = crit("teto_ev_s", teto, ">=2000 em degrau valido", teto is not None and teto >= 2000)
    if not any(p["alvo"] == 2000 for p in validos) or not suficiente:
        c["veredito"] = "INVALIDO"
    criterios.append(c)
    medidas["teto_ev_s"] = teto
    medidas["degraus_validos"] = [p["alvo"] for p in validos]
    medidas["p95_ms"] = max((p["p95_ms"] for p in validos if p["p95_ms"] is not None), default=None)
    medidas["eventos_da_janela_por_s"] = {str(p["alvo"]): round(p["eventos_medidos"] / cfg["dur_s"], 1) for p in degraus if p["eventos_medidos"]}  # eventos com updated_at na janela / duracao (todos aplicados apos a drenagem), nao e taxa de apply instantanea

# validade (mesmas razoes do relatorio.py: TAXA, RELOGIO, escopo, evidencia)
motivos = []
if not degraus or any(p["taxa"] is None for p in degraus):
    motivos.append("EVIDENCIA_INSUFICIENTE: taxa efetiva ausente")
if cen == "c2" and degraus and not degraus[0]["valido"]:
    motivos.append("TAXA")
if cen == "c3" and degraus and not any(p["valido"] for p in degraus):
    motivos.append("TAXA")
if not relogios:
    motivos.append("EVIDENCIA_INSUFICIENTE: relogios ausentes")
elif any(abs(r["skew_ms"]) + r["incerteza_ms"] > 100 for r in relogios):
    motivos.append("RELOGIO")
for k in ("archive_antes", "archive_depois"):
    a = load(f"{k.replace('_', '-')}.json")
    if not a or a.get("alarme"):
        motivos.append("DISCO: alarme ou verificacao ausente (" + k + ")")
validade = {"estado": "INVALIDO" if motivos else "VALIDO", "motivos": sorted(set(motivos))}
veredito = "INVALIDO" if motivos else (
    "APROVADO" if all(c["veredito"] == "APROVADO" for c in criterios) else
    "INVALIDO" if any(c["veredito"] == "INVALIDO" for c in criterios) else "REPROVADO")

skew = {"inicio": relogios[0]["skew_ms"] if relogios else None, "fim": relogios[-1]["skew_ms"] if relogios else None}
inc = {"inicio": relogios[0]["incerteza_ms"] if relogios else None, "fim": relogios[-1]["incerteza_ms"] if relogios else None}
cont = load("containers.json", [])
meta = {"cenario": cen, "execucao": cfg["execucao"], "inicio": cfg["inicio"], "fim": cfg["fim"],
        "diretorio": f"resultados/gg/{cen}/{cfg['execucao']}",
        "ambiente": {"lab": "goldengate", "hosts": ["desenv-server", "infra-server"], "containers": cont,
                     "replicat": cfg["replicat"], "limites_gerador": {"cpu": 2, "memoria": 1073741824},
                     "params_sha256": cfg.get("params_sha256", {}), "jar_sha256": cfg.get("jar_sha256"), "processos": []},
        "validade": validade, "veredito": veredito, "skew_ms": skew, "skew_incerteza_ms": inc, "relogios": relogios,
        "carga_servidores": [], "integridade": integ, "criterios": criterios, "degraus": degraus, "medidas": medidas,
        "operacao_especial": {"archive_antes": load("archive-antes.json"), "archive_depois": load("archive-depois.json")},
        "parametros": {"taxa": cfg.get("taxa"), "duracao_seg": cfg.get("dur_s") if cen == "c2" else None, "degrau_seg": cfg.get("dur_s") if cen == "c3" else None,
                       "c3_taxas": cfg.get("taxas") if cen == "c3" else None, "replicat": cfg["replicat"]}}
if cen == "c2":
    meta["parametros"].pop("c3_taxas"); meta["parametros"].pop("degrau_seg")
else:
    meta["parametros"].pop("duracao_seg")
(D / "meta.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False) + "\n")
print(json.dumps({"veredito": veredito, "validade": validade, "criterios": {c["nome"]: (c["veredito"], c["valor"]) for c in criterios},
                  "degraus": [(p["alvo"], p["taxa"], p["validade"], p["p95_ms"], p["tendencia_lag"].get("crescimento_sustentado")) for p in degraus]}, ensure_ascii=False))
sys.exit(0 if veredito == "APROVADO" else 1)
