#!/usr/bin/env python3
"""Consolida o C9 (comparacao descritiva de parametros do Replicat classico) no contrato do lab Java. Uso: meta_c9.py DIR_C9
DIR_C9 tem `cenario.json`, `modos.jsonl` (um modo por linha, com os diretorios `runs/c2/<exec>` e `runs/c3/<exec>` gerados por
scripts/gg/cenario.sh) e o `meta.json` de cada execucao. Saida: comparacao.csv e meta.json. SEM_CRITERIO para a comparacao; os criterios de
integridade (divergencias, uuids_alterados, pendentes_final) somam todas as execucoes."""
import csv, json, sys
from pathlib import Path

D = Path(sys.argv[1])
cfg = json.loads((D / "cenario.json").read_text())
modos = [json.loads(l) for l in (D / "modos.jsonl").read_text().splitlines() if l.strip()]


def crit(nome, valor, alvo, ok):
    return {"nome": nome, "valor": valor, "alvo": alvo, "veredito": "INVALIDO" if valor is None else ("APROVADO" if ok else "REPROVADO")}


rows, degraus, relogios, subs = [], [], [], []
for m in modos:
    for grupo in ("c2", "c3"):
        meta = json.loads((D / m[grupo] / "meta.json").read_text())
        subs.append((m["modo"], grupo, meta))
        relogios += meta.get("relogios", [])
        for p in meta["degraus"]:
            lat = p.get("latencia_s") or {}
            cpu = p.get("cpu_docker_stats") or {}
            q = dict(p, virtual_threads=m["modo"], grupo=grupo, modo=m["modo"], diretorio=m[grupo])
            degraus.append(q)
            rows.append({"modo": m["modo"], "parametros": m["descricao"], "grupo": grupo, "alvo_ev_s": p["alvo"], "taxa_ev_s": p.get("taxa"), "validade": p.get("validade"),
                         "p50_s": lat.get("p50_s"), "p95_s": lat.get("p95_s"), "p99_s": lat.get("p99_s"), "max_s": lat.get("max_s"),
                         "tendencia_ms_s": (p.get("tendencia_lag") or {}).get("inclinacao_ms_s"), "crescimento_sustentado": (p.get("tendencia_lag") or {}).get("crescimento_sustentado"),
                         "lag_final_s": p.get("lag_final_s"), "drenagem_s": p.get("drenagem_s"), "eventos_medidos": p.get("eventos_medidos"),
                         "cpu_legado_media_pct": (cpu.get("gg-legado") or {}).get("media_pct"), "cpu_novo_media_pct": (cpu.get("gg-novo") or {}).get("media_pct"),
                         "cpu_hub_media_pct": (cpu.get("gg-hub") or {}).get("media_pct"), "cpu_gerador_media_pct": (cpu.get("gerador") or {}).get("media_pct"),
                         "divergencias": meta["integridade"].get("divergencias"), "uuids_alterados": meta["integridade"].get("uuids_alterados"), "execucao": m[grupo]})
with (D / "comparacao.csv").open("w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)


def soma(k):
    v = [meta["integridade"].get(k) for _, _, meta in subs]
    return None if any(x is None for x in v) else sum(v)


div, uu = soma("divergencias"), soma("uuids_alterados")
pend = [meta["integridade"].get("pendentes_final") for _, _, meta in subs]
pend_ok = all(x == 0 for x in pend)
criterios = [crit("divergencias", div, 0, div == 0), crit("uuids_alterados", uu, 0, uu == 0),
             crit("pendentes_final", 0 if pend_ok else None, "0 (drenado) em todas as execucoes", pend_ok),
             {"nome": "comparacao_parametros_replicat", "valor": "comparacao.csv", "alvo": "vazao e p95 por modo e degrau (descritivo; nao ha alvo de superioridade)", "veredito": "SEM_CRITERIO"}]
motivos = []
for p in degraus:
    if p.get("taxa") is None:
        motivos.append("EVIDENCIA_INSUFICIENTE: taxa efetiva ausente")
    elif p["taxa"] < .98 * p["alvo"] and p["grupo"] != "c3":
        motivos.append("TAXA")
    elif p["taxa"] < .98 * p["alvo"]:
        motivos.append("TAXA")
if not relogios:
    motivos.append("EVIDENCIA_INSUFICIENTE: relogios ausentes")
elif any(abs(r["skew_ms"]) + r["incerteza_ms"] > 100 for r in relogios):
    motivos.append("RELOGIO")
for modo, grupo, meta in subs:
    for a in ("archive_antes", "archive_depois"):
        x = (meta.get("operacao_especial") or {}).get(a)
        if not x or x.get("alarme"):
            motivos.append(f"DISCO: alarme ou verificacao ausente ({modo}/{grupo}/{a})")
validade = {"estado": "INVALIDO" if motivos else "VALIDO", "motivos": sorted(set(motivos))}
veredito = "INVALIDO" if motivos else ("APROVADO" if all(c["veredito"] in ("APROVADO", "SEM_CRITERIO") for c in criterios) else "INVALIDO" if any(c["veredito"] == "INVALIDO" for c in criterios) else "REPROVADO")
restaur = None
try:
    restaur = json.loads((D / "restauracao-estado.json").read_text())
except (OSError, ValueError):
    pass
ult = subs[-1][2]
meta = {"cenario": "c9", "execucao": cfg["execucao"], "inicio": cfg["inicio"], "fim": cfg["fim"], "diretorio": str(D),
        "ambiente": {"lab": "goldengate", "hosts": ["desenv-server", "infra-server"], "containers": ult["ambiente"].get("containers", []), "replicat": "REPBDAD classico (modos em comparacao.csv)",
                     "limites_gerador": {"cpu": 2, "memoria": 1073741824}, "params_sha256": cfg.get("params_sha256", {}), "jar_sha256": cfg.get("jar_sha256"), "processos": []},
        "validade": validade, "veredito": veredito, "skew_ms": {"inicio": relogios[0]["skew_ms"] if relogios else None, "fim": relogios[-1]["skew_ms"] if relogios else None},
        "skew_incerteza_ms": {"inicio": relogios[0]["incerteza_ms"] if relogios else None, "fim": relogios[-1]["incerteza_ms"] if relogios else None},
        "relogios": relogios, "carga_servidores": [], "criterios": criterios, "degraus": degraus,
        "integridade": {"pendentes_inicial": 0, "pendentes_final": 0 if pend_ok else None, "divergencias": div, "uuids_alterados": uu, "execucoes": [m["c2"] for m in modos] + [m["c3"] for m in modos]},
        "medidas": {"comparacao": "comparacao.csv", "modos": [m["modo"] for m in modos], "eventos_medidos": sum(p.get("eventos_medidos", 0) for p in degraus)},
        "operacao_especial": {"modos": modos, "restauracao_parametros_oficiais": restaur, "coordenado": "nao usado (THREADRANGE pode dividir transacao de origem)",
                              "descritivo": True, "tabela": "comparacao.csv"},
        "parametros": {"taxa": 1000, "duracao_seg": cfg["dur_s"], "degrau_seg": cfg["degrau_s"], "taxa_alta": cfg["taxa_alta"], "replicat": "classico; variantes de GROUPTRANSOPS/BATCHSQL"}}
(D / "meta.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False) + "\n")
print(json.dumps({"veredito": veredito, "validade": validade, "criterios": {c["nome"]: (c["veredito"], c["valor"]) for c in criterios}}, ensure_ascii=False))
sys.exit(0 if veredito == "APROVADO" else 1)
