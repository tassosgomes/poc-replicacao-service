#!/usr/bin/env python3
"""Monta o meta.json de uma execucao GG no mesmo contrato do lab Java (resultados/cN/meta.json), para que
`bash scripts/relatorio.sh --resultados resultados/gg` consolide. Uso: meta_gg.py DIR_EXECUCAO
DIR_EXECUCAO tem `cenario.json` (escrito por cenario.sh) e, por degrau, `dN-<taxa>/` com gerador.log, latencia.json,
recursos-*.csv, relogio-inicio.json, relogio-fim.json, drenagem.txt. Mais: reconciliacao-sql.txt, reconciliacao-java.log,
uuids.json, archive-antes.json, archive-depois.json, containers.json. Saida: meta.json. Exit 0 aprovado, 1 caso contrario.
Regras (README do lab Java): degrau valido = taxa >= 98% do alvo, senao GERADOR_SATURADO (c3) / INVALIDO; teto = maior alvo valido
sem crescimento sustentado do lag; teto so aprova com degrau valido de 2000 ev/s; sem dado = INVALIDO, nunca APROVADO.
c6/c7: `d1-<taxa>/queda.json` (scripts/gg/analisar-queda.py) e `comparacao-eventos.json`; c8: `d1-<taxa>/c8.json`, `especial/*.json`;
c4/c5: `dN-<taxa>/especial.json` (scripts/gg/analisar-especial.py) e `especial/` (rajada/transacao longa); c1: `carga.json` (scripts/gg/c1-carga.sh)."""
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
    ph["dir"] = step["dir"]
    for k in ("captura", "repeticao", "modo", "supplemental"):
        if k in step:
            ph[k] = step[k]
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

if cen == "c1":
    for tag in ("inicio", "fim"):
        r = load(f"relogio-{tag}.json")
        if r:
            relogios.append({"etapa": f"c1-{tag}", "ts": cfg.get(tag, ""), "skew_ms": r["offset_ms"], "incerteza_ms": r["incerteza_ms"]})
carga = load("carga.json") if cen == "c1" else None
esp = load(f"{cfg['degraus'][0]['dir']}/especial.json", {}) if (cen in ("c4", "c5") and cfg["degraus"]) else {}
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
if cen == "c1":
    drenado = bool(carga) and carga.get("drenado") is True
if cen == "c10":
    drenado = "drenado em" in ((D / "drenagem-final.txt").read_text() if (D / "drenagem-final.txt").exists() else "")
integ = {"pendentes_inicial": 0, "snapshot_antes": "../../c1/snapshots/apos-carga.csv", "pendentes_final": 0 if drenado else None,
         "divergencias": div, "divergencias_sql": div_sql, "divergencias_java": div_java, "reconciliacao": "reconciliacao-java.log",
         "reconciliacao_sql": "reconciliacao-sql.txt", "snapshot_depois": "snapshots/apos.csv", "uuids_alterados": uuid_alt,
         "uuids": uu, "diferencas": "reconciliacao-sql.txt"}
if cen == "c1":
    integ.update(snapshot_antes="snapshots/antes.csv", snapshot_depois="snapshots/apos.csv", contagens_legado=(carga or {}).get("contagens_legado"),
                 contagens_novo=(carga or {}).get("contagens_novo"))

validos = [p for p in degraus if p["grupo"] == cen and p["valido"]]
criterios = [crit("divergencias", div, 0, div == 0), crit("uuids_alterados", uuid_alt, 0, uuid_alt == 0),
             crit("pendentes_final", integ["pendentes_final"], "0 (drenado: Replicat RUNNING, aplicacao_log estavel, lag<=2s)", integ["pendentes_final"] == 0)]
medidas = {"eventos_medidos": sum(p["eventos_medidos"] for p in degraus), "relogios_validos": True}
special = {}
if cen == "c1":
    cl, cn = carga.get("contagens_legado"), carga.get("contagens_novo")
    criterios.append(crit("contagens_iguais", {"legado": cl, "novo": cn} if cl and cn else None, "iguais por tabela no escopo declarado", bool(cl) and cl == cn))
    ci = carga.get("carga_inicial")
    ok = bool(ci) and all(ci["tabelas"][t]["linhas"] == carga["contagens_as_of_scn"][t] for t in ci["tabelas"])
    criterios.append(crit("carga_inicial", ci, "linhas/s por tabela e tempo total; linhas copiadas == linhas do legado AS OF SCN", ok))
    medidas.update(carga_inicial=ci, tempo_ate_drenado_s=carga.get("tempo_ate_drenado_s"), scn=carga.get("scn"))
    special = {"escopo": {"codigo_min_inclusivo": 0, "codigo_max_exclusivo": None, "completo": True}, "scn": carga.get("scn"),
               "carga_inicial": ci, "contagens_apos_truncate": carga.get("contagens_apos_truncate"), "tempos_s": carga.get("tempos_s")}
elif cen == "c4":
    p = degraus[0] if degraus else {}
    pat = (esp.get("patamar") or {}).get("maximo_ms")
    rec = esp.get("recuperacao_s") if p.get("valido") else None
    c = crit("recuperacao_rajada_s", rec, "<=120 apos commit", rec is not None and rec <= 120)
    if rec is None and p.get("valido") and esp:
        # observacao concluida sem recuperar com patamar medido e rajada completa: REPROVADO; sem patamar (< 3 amostras) ou rajada incompleta: INVALIDO
        c["veredito"] = "REPROVADO" if pat is not None and (esp.get("rajada") or {}).get("completa") and esp.get("janela_recuperacao_s", 0) >= 120 else "INVALIDO"
    criterios.append(c)
    medidas.update(p95_ms=p.get("p95_ms"), recuperacao_s=esp.get("recuperacao_s"), janela_recuperacao_s=esp.get("janela_recuperacao_s"), patamar_lag_ms=pat,
                   patamar=esp.get("patamar"), rajada=esp.get("rajada"), pico_lag_fundo_apos_commit_ms=esp.get("pico_lag_fundo_apos_commit_ms"),
                   pico_lag_fundo_apos_commit_s=esp.get("pico_lag_fundo_apos_commit_s"))
    special = {"rajada_commit": esp.get("rajada_commit"), "rajada_linhas": cfg.get("rajada_linhas_param"), "commit_iso": esp.get("rajada_commit_iso"),
               "metodo": esp.get("metodo_recuperacao")}
elif cen == "c5":
    p = degraus[0] if degraus else {}
    for nome, chave in (("p95_demais_durante_aberta_ms", "p95_demais_ms"), ("aplicacao_apos_commit_ms", "apos_commit_ms")):
        val = esp.get(chave) if p.get("valido") else None
        criterios.append(crit(nome, val, "<=5000", val is not None and val <= 5000))
    criterios.append({"nome": "latencia_transacao_longa_ms", "valor": esp.get("latencia_longa_ms"), "alvo": "reportada separadamente",
                      "veredito": "SEM_CRITERIO" if esp.get("latencia_longa_ms") is not None else "INVALIDO"})
    medidas.update(p95_ms=p.get("p95_ms"), p95_demais_ms=esp.get("p95_demais_ms"), p50_demais_ms=esp.get("p50_demais_ms"), max_demais_ms=esp.get("max_demais_ms"),
                   latencia_longa_ms=esp.get("latencia_longa_ms"), apos_commit_ms=esp.get("apos_commit_ms"), eventos_durante_aberta=esp.get("eventos_durante_aberta"))
    special = {k: esp.get(k) for k in ("evento_longo", "criado", "commit", "criado_iso", "commit_iso", "aberta_s", "aplicada_em_iso")}
elif cen in ("c6", "c7"):
    p = degraus[0] if degraus else {}
    d0 = cfg["degraus"][0]["dir"] if cfg["degraus"] else ""
    qd = load(f"{d0}/queda.json", {}) or {}
    evs = {e["nome"]: e for e in qd.get("eventos", [])}
    cmpev = load(f"{d0}/comparacao-eventos.json", {}) or {}
    val = p.get("valido")

    def g(ev, k):
        return (evs.get(ev) or {}).get(k) if val else None
    if cen == "c6":
        ev = next(iter(evs), None)
        tq, tr = g(ev, "desde_queda_s"), g(ev, "desde_reinicio_s")
        criterios.append(crit("tempo_normalizar_s", tq, "lag_ms <= 5000 em janela completa de 5 s durante carga apos reinicio (tempo desde a queda)", tq is not None))
        criterios.append({"nome": "tempo_normalizar_apos_reinicio_s", "valor": tr, "alvo": "reportado (desde o retorno de docker start / REPBDAD de volta)", "veredito": "SEM_CRITERIO" if tr is not None else "INVALIDO"})
        est = g(ev, "estavel_apos_normalizar")
        criterios.append({"nome": "estavel_apos_normalizar", "valor": est, "alvo": "reportado (baldes completos seguintes com lag <= 5 s ate o fim da carga)", "veredito": "SEM_CRITERIO" if est is not None else "INVALIDO"})
        medidas.update(p95_ms=p.get("p95_ms"), tempo_normalizar_s=tq, apos_reinicio_s=tr, lag_normal_ms=5000, evento=ev,
                       normalizacao_metodo=qd.get("metodo"), pico_lag_s=g(ev, "pico_lag_s"), amostras=p.get("tendencia_lag", {}).get("amostras"),
                       rest_desde_reinicio_s=g(ev, "rest_desde_reinicio_s"), extract_running_desde_reinicio_s=g(ev, "extract_running_desde_reinicio_s"),
                       replicat_running_desde_reinicio_s=g(ev, "replicat_running_desde_reinicio_s"))
        special = {"queda": {k: (evs.get(ev) or {}).get(k) for k in ("nome", "t_queda", "t_reinicio", "t_kill_ret", "t_start_cmd0", "t_rest", "t_ext", "t_rep")},
                   "variante": cfg.get("variante"), "queda_apos_s": cfg.get("queda_apos_s"), "reinicio_apos_s": cfg.get("reinicio_apos_s"),
                   "observacao_apos_reinicio_s": cfg.get("observacao_apos_reinicio_s"), "evento_resultado": {k: v for k, v in (evs.get(ev) or {}).items() if k != "baldes"}}
        vr = load("../variante-replicat.meta.json")
        if vr and cfg.get("variante") == "hub":
            special["variante_kill_processo_replicat"] = {"execucao": vr.get("execucao"), "descritivo": True, "diretorio": vr.get("diretorio"),
                "tempo_normalizar_s": vr.get("medidas", {}).get("tempo_normalizar_s"), "replicat_running_desde_queda_s": (vr.get("operacao_especial", {}).get("evento_resultado") or {}).get("replicat_running_desde_queda_s"),
                "divergencias": vr.get("integridade", {}).get("divergencias"), "uuids_alterados": vr.get("integridade", {}).get("uuids_alterados"), "veredito": vr.get("veredito")}
    else:
        r, k = "docker_restart", "docker_kill_start"
        fr, fk = g(r, "desde_queda_s"), g(k, "desde_queda_s")
        fo = None if fr is None or fk is None else max(fr, fk)
        alvo = "<=20 (referencia, ADAPTADO: sem segunda replica; queda do hub ate EXTBDAD e REPBDAD RUNNING e lag <= 5 s sob carga)"
        criterios.append(crit("failover_s", None if fo is None else round(fo, 2), alvo, fo is not None and fo <= 20))
        criterios.append(crit("failover_restart_s", fr, alvo, fr is not None and fr <= 20))
        criterios.append(crit("failover_kill_start_s", fk, alvo, fk is not None and fk <= 20))
        for ev, nome in ((r, "processos_running_restart_s"), (k, "processos_running_kill_start_s")):
            a, b = g(ev, "extract_running_desde_queda_s"), g(ev, "replicat_running_desde_queda_s")
            v = None if a is None or b is None else round(max(a, b), 2)
            criterios.append({"nome": nome, "valor": v, "alvo": "reportado (EXTBDAD e REPBDAD RUNNING, desde a queda)", "veredito": "SEM_CRITERIO" if v is not None else "INVALIDO"})
        for ev, nome in ((r, "estavel_apos_restart"), (k, "estavel_apos_kill_start")):
            est = g(ev, "estavel_apos_normalizar")
            criterios.append({"nome": nome, "valor": est, "alvo": "reportado (baldes completos seguintes com lag <= 5 s)", "veredito": "SEM_CRITERIO" if est is not None else "INVALIDO"})
        medidas.update(p95_ms=p.get("p95_ms"), failover_s=None if fo is None else round(fo, 2), failover_restart_s=fr, failover_kill_start_s=fk,
                       failover_restart_desde_retorno_s=g(r, "desde_reinicio_s"), failover_kill_start_desde_retorno_s=g(k, "desde_reinicio_s"),
                       failover_metodo="queda do hub (inicio do docker restart / do docker kill) ate o primeiro balde de 5 s completo com lag <= 5 s (relogio do infra-server e do gg-novo)")
        special = {"queda": {n: {kk: (evs.get(n) or {}).get(kk) for kk in ("t_queda", "t_reinicio", "t_rest", "t_ext", "t_rep")} for n in (r, k)},
                   "failover_s": None if fo is None else round(fo, 2), "failover_metodo": medidas["failover_metodo"], "falha1_apos_s": cfg.get("falha1_apos_s"), "falha2_apos_s": cfg.get("falha2_apos_s"),
                   "eventos_resultado": {n: {kk: v for kk, v in (evs.get(n) or {}).items() if kk != "baldes"} for n in (r, k)},
                   "adaptado": True, "adaptacao": "Sem segunda replica no GoldenGate Free (Oracle Free): nao ha failover para outro no. Mede-se o reinicio do hub unico (docker restart e docker kill + start; autostart dos processos) ate Extract e Replicat RUNNING e lag normal sob carga, contra a referencia de 20 s do PRD. HA de producao (XAG/RAC, hub secundario) fica fora do escopo da PoC."}
    criterios.append({"nome": "eventos_log_x_gerador", "valor": {k2: cmpev.get(k2) for k2 in ("eventos_gerador", "linhas_log", "eventos_faltantes_total", "eventos_a_mais_total", "chaves_com_menos_no_log", "chaves_com_mais_no_log")} if cmpev else None,
                      "alvo": "reportado (duplicatas de log apos kill sao registradas; integridade = estado final)", "veredito": "SEM_CRITERIO" if cmpev else "INVALIDO"})
    medidas["eventos_log"] = cmpev
elif cen == "c8":
    p = degraus[0] if degraus else {}
    d0 = cfg["degraus"][0]["dir"] if cfg["degraus"] else ""
    c8 = load(f"{d0}/c8.json", {}) or {}
    ok_afet = bool(c8) and c8.get("linhas_afetadas", 0) > 0 and c8.get("afetadas_conferem_antes") is True and p.get("valido")
    criterios.append(crit("linhas_afetadas", c8.get("linhas_afetadas") if c8 and p.get("valido") else None, ">0; chaves divergentes antes de religar == chaves distintas do CSV do gerador (so OBRA_MUSICAL)", ok_afet))
    corr = c8.get("linhas_corrigidas") if c8 and p.get("valido") else None
    criterios.append(crit("linhas_corrigidas", corr, c8.get("linhas_afetadas"), corr is not None and corr == c8.get("linhas_afetadas") and c8.get("log_cobre_afetadas") is True and c8.get("divergentes_depois") == 0))
    nec = [load(f"especial/archive-necessario-{x}.json") for x in ("antes-limpar", "depois-limpar", "depois-religar")]
    nec_ok = all(n is not None and n.get("faltando") == 0 for n in nec)
    criterios.append(crit("archive_necessario_preservado", {"antes_limpar": nec[0], "depois_limpar": nec[1], "depois_religar": nec[2]} if all(nec) else None, "nenhum archive log necessario (next_change# > required_checkpoint_scn) apagado", nec_ok))
    criterios.append(crit("recuperado_so_pelo_redo", (c8.get("reparo_ou_reconciliacao_antes_de_drenar") is False) if c8 else None, True, bool(c8) and c8.get("reparo_ou_reconciliacao_antes_de_drenar") is False))
    criterios.append({"nome": "tempo_ate_drenado_s", "valor": c8.get("tempo_ate_drenado_s"), "alvo": "reportado (desde o retorno do START do Extract ate drenar)", "veredito": "SEM_CRITERIO" if c8 else "INVALIDO"})
    medidas.update(linhas_afetadas=c8.get("linhas_afetadas"), linhas_corrigidas=corr, tempo_ate_drenado_s=c8.get("tempo_ate_drenado_s"),
                   ultima_aplicacao_apos_religar_s=c8.get("ultima_aplicacao_apos_religar_s"), divergentes_antes=c8.get("divergentes_antes"), divergentes_depois=c8.get("divergentes_depois"))
    special = {"lease_seg": None, "trigger_desligado_inicio": c8.get("captura_parada_inicio"), "trigger_desligado_fim": c8.get("captura_parada_fim"),
               "captura_parada_inicio": c8.get("captura_parada_inicio"), "captura_parada_fim": c8.get("captura_parada_fim"), "captura": "Extract EXTBDAD parado (supplemental logging ativo)",
               "linhas_afetadas": c8.get("linhas_afetadas"), "linhas_afetadas_csv": "especial/linhas-afetadas.csv", "divergencias_antes": c8.get("divergentes_antes"),
               "verificacao_antes": "especial/divergentes-antes.csv", "afetadas_conferem_antes": c8.get("afetadas_conferem_antes"), "linhas_reenfileiradas": None,
               "delta_cobre_afetadas": None, "log_cobre_afetadas": c8.get("log_cobre_afetadas"), "linhas_corrigidas": corr, "dba_capture_parado": c8.get("dba_capture_parado"),
               "parada_s": c8.get("parada_s"), "religar_extract_s": c8.get("religar_extract_s"),
               "reconciliacao": False, "reparo": False, "metodo": "Extract religado retoma do checkpoint e le o redo (archive logs); nenhum delta, reenfileiramento ou reconciliacao reparadora"}
elif cen == "c10":
    import statistics

    def dist(v):
        return {"repeticoes": len(v), "media_ms": statistics.mean(v), "desvio_padrao_ms": statistics.stdev(v) if len(v) > 1 else 0, "min_ms": min(v), "max_ms": max(v), "amostras_ms": v}

    def ovh(a, b):
        return {"absoluto_ms": a - b, "percentual": 100 * (a - b) / b if b > 0 else None}
    ok_ph = lambda p: p.get("valido") and (p.get("transacoes") or 0) > 0 and (p.get("transacao_media_ms") or 0) > 0
    pr = [p for p in degraus if p.get("supplemental", True)]
    on = [p["transacao_media_ms"] for p in pr if p.get("captura") is True and ok_ph(p)]
    off = [p["transacao_media_ms"] for p in pr if p.get("captura") is False and ok_ph(p)]
    reps = cfg.get("repeticoes", 3)
    completo = len(on) == reps and len(off) == reps and reps >= 3
    criterios.insert(0, crit("repeticoes_alternadas", len(pr), 2 * reps, len(pr) == 2 * reps and completo))
    comparavel = bool(degraus) and all(ok_ph(p) for p in degraus)
    criterios.insert(1, crit("taxa_valida_todos_modos", comparavel, True, comparavel))
    custo = None
    if completo:
        custo = {"sem_metricas": {"captura_ligada": dist(on), "captura_desligada": dist(off), "overhead_captura": ovh(statistics.mean(on), statistics.mean(off))},
                 "com_metricas": "nao aplicavel (nao ha worker nem evento_replicacao no lab GG; nao ha consulta de metricas no legado)"}
    ov = custo["sem_metricas"]["overhead_captura"] if custo else None
    criterios.insert(2, crit("overhead_captura", ov, "<=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente)",
                             ov is not None and ov["percentual"] is not None and ov["percentual"] <= 15 and ov["absoluto_ms"] <= 5))
    var = [p for p in degraus if p.get("supplemental") is False]
    if custo and var and ok_ph(var[0]):
        v = var[0]["transacao_media_ms"]
        custo["variante_sem_supplemental"] = {"descritiva": True, "captura": "Extract parado e DELETE SCHEMATRANDATA (supplemental removido; FORCE LOGGING mantido)", "transacao_media_ms": v, "taxa": var[0]["taxa"],
            "overhead_ligada_vs_sem_supplemental": ovh(statistics.mean(on), v), "overhead_desligada_vs_sem_supplemental": ovh(statistics.mean(off), v), "diretorio": var[0].get("dir")}
    cpu = {}
    for nome, sel in (("ligada", lambda p: p.get("captura") is True and p.get("supplemental", True)), ("desligada", lambda p: p.get("captura") is False and p.get("supplemental", True)),
                      ("desligada_sem_supplemental", lambda p: p.get("supplemental") is False)):
        v = [(p["cpu_docker_stats"].get("gg-legado") or {}).get("media_pct") for p in degraus if sel(p) and p.get("cpu_docker_stats")]
        v = [x for x in v if x is not None]
        if v:
            cpu[nome] = round(statistics.mean(v), 1)
    medidas.update(custo_legado=custo, cpu_media_gg_legado_pct=cpu, ordem_das_fases=[(p["dir"], p.get("captura"), p.get("transacao_media_ms")) for p in degraus],
                   comparacao_java={"overhead_percentual": 19.998608792431824, "overhead_absoluto_ms": 0.958333333333333, "observacao": "triggers de captura no legado Java (resultados/c10): captura ligada 5,750 ms x desligada 4,792 ms"})
    special = {"carga": {"taxa": cfg.get("taxa"), "duracao_seg_por_fase": cfg.get("dur_s"), "semente": 1, "threads": 8, "pct_insert": 0, "pct_delete": 0, "hot_pct": 10, "tx_max": 5, "mix": "40,40,20 (padrao do gerador)"},
               "criterio": "segundo criterio proposto; Q-T3 pendente", "limite_pct": 15, "limite_ms": 5, "limite_ativo": True, "repeticoes": reps,
               "consulta_metricas": "nao aplicavel", "captura_desligada": "Extract EXTBDAD parado; FORCE LOGGING e supplemental logging (ALLCOLS) continuam ativos: o custo do supplemental nao e isolado",
               "variante_supplemental": cfg.get("variante_supplemental"), "supplemental_inicial": (D / "supplemental-inicial.txt").read_text().strip() if (D / "supplemental-inicial.txt").exists() else None,
               "supplemental_removido": (D / "supplemental-removido.txt").read_text().strip() if (D / "supplemental-removido.txt").exists() else None,
               "supplemental_restaurado": (D / "supplemental-restaurado.txt").read_text().strip() if (D / "supplemental-restaurado.txt").exists() else None,
               "estado_final": (D / "estado-final.txt").read_text().strip() if (D / "estado-final.txt").exists() else None,
               "integridade": "aplicavel aqui (diferente do Java): o Extract retoma pelo redo, nada fica sem replicacao; drenado com Extract ligado e reconciliado"}
if cen == "c2":
    p = degraus[0] if degraus else {}
    v = p.get("p95_ms") if p.get("valido") else None
    criterios.append(crit("p95_ms", v, "<=5000", v is not None and v <= 5000))
    tr = p.get("tendencia_lag", {}) if p.get("valido") else {}
    rising = tr.get("crescimento_sustentado")
    criterios.append(crit("tendencia_lag", tr if rising is not None else None, "sem crescimento sustentado", rising is False))
    medidas["p95_ms"] = p.get("p95_ms")
elif cen == "c3":
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
if cen != "c1" and (not degraus or any(p["taxa"] is None for p in degraus)):
    motivos.append("EVIDENCIA_INSUFICIENTE: taxa efetiva ausente")
if cen == "c10" and any(not p["valido"] for p in degraus):
    motivos.append("TAXA")
if cen in ("c2", "c4", "c5", "c6", "c7", "c8") and degraus and not degraus[0]["valido"]:
    motivos.append("TAXA")
if cen in ("c4", "c5") and not esp:
    motivos.append("EVIDENCIA_INSUFICIENTE: analise da operacao especial ausente")
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
    "APROVADO" if all(c["veredito"] in ("APROVADO", "SEM_CRITERIO") for c in criterios) else
    "INVALIDO" if any(c["veredito"] == "INVALIDO" for c in criterios) else "REPROVADO")

skew = {"inicio": relogios[0]["skew_ms"] if relogios else None, "fim": relogios[-1]["skew_ms"] if relogios else None}
inc = {"inicio": relogios[0]["incerteza_ms"] if relogios else None, "fim": relogios[-1]["incerteza_ms"] if relogios else None}
cont = load("containers.json", [])
meta = {"cenario": cen, "execucao": cfg["execucao"], "inicio": cfg["inicio"], "fim": cfg["fim"],
        "diretorio": str(D) if str(D).startswith("resultados/") else f"resultados/gg/{cen}/{cfg['execucao']}",
        "ambiente": {"lab": "goldengate", "hosts": ["desenv-server", "infra-server"], "containers": cont,
                     "replicat": cfg["replicat"], "limites_gerador": {"cpu": 2, "memoria": 1073741824},
                     "params_sha256": cfg.get("params_sha256", {}), "jar_sha256": cfg.get("jar_sha256"), "processos": []},
        "validade": validade, "veredito": veredito, "skew_ms": skew, "skew_incerteza_ms": inc, "relogios": relogios,
        "carga_servidores": [], "integridade": integ, "criterios": criterios, "degraus": degraus, "medidas": medidas,
        "operacao_especial": {**special, "archive_antes": load("archive-antes.json"), "archive_depois": load("archive-depois.json")},
        "parametros": {"taxa": cfg.get("taxa"), "duracao_seg": cfg.get("dur_s") if cen != "c3" else None, "degrau_seg": cfg.get("dur_s") if cen == "c3" else None,
                       "c3_taxas": cfg.get("taxas") if cen == "c3" else None, "replicat": cfg["replicat"]}}
if cen == "c7":
    meta["adaptado"] = True
    meta["adaptacao"] = special.get("adaptacao")
if cen == "c10":
    meta["parametros"]["repeticoes"] = cfg.get("repeticoes")
if cen in ("c6", "c7"):
    meta["parametros"]["variante"] = cfg.get("variante")
if cen == "c3":
    meta["parametros"].pop("duracao_seg")
else:
    meta["parametros"].pop("c3_taxas"); meta["parametros"].pop("degrau_seg")
if cen in ("c4", "c5"):
    meta["parametros"].update(delay_s=cfg.get("delay_s"), hold_s=cfg.get("hold_s"), rajada_linhas=cfg.get("rajada_linhas_param"))
if cen == "c1":
    meta["parametros"] = {"replicat": cfg["replicat"], "metodo": "INSERT AS OF SCN via DB link + START REPLICAT AFTERCSN"}
(D / "meta.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False) + "\n")
print(json.dumps({"veredito": veredito, "validade": validade, "criterios": {c["nome"]: (c["veredito"], c["valor"]) for c in criterios},
                  "degraus": [(p["alvo"], p["taxa"], p["validade"], p["p95_ms"], p["tendencia_lag"].get("crescimento_sustentado")) for p in degraus]}, ensure_ascii=False))
sys.exit(0 if veredito == "APROVADO" else 1)
