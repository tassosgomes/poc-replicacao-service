#!/usr/bin/env python3
"""Monta o meta.json de uma execucao do lab Kafka no MESMO contrato do lab Java/GG (resultados/cN/meta.json, scripts/gg/meta_gg.py), para que
`bash scripts/relatorio.sh --resultados resultados/kf` consolide. Uso: meta_kf.py DIR_EXECUCAO
DIR_EXECUCAO tem `cenario.json` (escrito por cenario.sh / c1-carga.sh) e, por degrau, `dN-<taxa>/` com gerador.log, latencia.json (c2/c3) ou
especial.json (c4/c5, scripts/gg/analisar-especial.py sobre scripts/kf/eventos_kf.py), recursos-*.csv, relogio-inicio.json, relogio-fim.json,
drenagem.txt. Mais: reconciliacao-sql.txt, uuids.json, archive-antes.json, archive-depois.json, containers.json. c1: `carga.json`.
Regras (README do lab Java): degrau valido = taxa >= 98% do alvo, senao GERADOR_SATURADO (c3) / INVALIDO; teto = maior alvo valido sem
crescimento sustentado da latencia maxima por balde de 5 s; teto so aprova com degrau valido de 2000 ev/s; sem dado = INVALIDO, nunca APROVADO.
Diferencas para o GG (registradas em docs/spike-kafka-notas.md): divergencias = so a reconciliacao SQL (a do jar Java conta delete fisico
convertido como divergencia e nao e rodada); o worker Kafka nao enxerga o legado, entao `lag_ms` do worker e 0 e a tendencia usa a latencia
(confirmado_em - criado_em) como no GG. Saida: meta.json. Exit 0 aprovado, 1 caso contrario."""
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
                n = re.sub(r"kf-gerador-\d+", "gerador", t[1])
                try:
                    v = float(t[2].rstrip("%"))
                except ValueError:
                    continue
                e = acc.setdefault(n, [0, 0.0, 0.0, 0.0, 0.0]); e[0] += 1; e[1] += v; e[2] = max(e[2], v)
                try:
                    mem = t[3].split("/")[0].strip()
                    mib = float(re.sub(r"[A-Za-z]+$", "", mem)) * ({"GiB": 1024, "MiB": 1, "KiB": 1 / 1024}.get(re.search(r"[A-Za-z]+$", mem)[0], 1))
                    e[3] += mib; e[4] = max(e[4], mib)
                except (IndexError, ValueError, TypeError):
                    pass
    return {k: {"media_pct": round(v[1] / v[0], 1), "max_pct": v[2], "amostras": v[0], "mem_media_mib": round(v[3] / v[0]), "mem_max_mib": round(v[4])} for k, v in acc.items()}


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
    for k in ("captura", "repeticao", "modo", "supplemental", "posicao"):
        if k in step:
            ph[k] = step[k]
    if lat.get("n"):
        ph["p95_ms"] = round(lat["p95_s"] * 1000)
        ph["latencia_s"] = {k: lat[k] for k in ("min_s", "p50_s", "p95_s", "p99_s", "max_s", "media_s")}
        ph["tendencia_lag"] = lat["tendencia_max_por_5s"]
        ph["tendencia_lag_media"] = lat["tendencia_media_por_5s"]
        ph["lag_final_s"] = lat.get("baldes_5s", [{}])[-1].get("max_s")
    ev = (dd / "eventos.txt")
    if ev.exists():
        try:
            ph["eventos_worker_csv"] = json.loads(ev.read_text().strip().splitlines()[-1])
        except ValueError:
            pass
    degraus.append(ph)
    for tag in ("inicio", "fim"):
        r = load(f"{step['dir']}/relogio-{tag}.json")
        if r:
            relogios.append({"etapa": f"{step['dir']}-{tag}", "ts": step.get(f"{tag}_iso", ""),
                             "skew_ms": r["offset_ms"], "incerteza_ms": r["incerteza_ms"]})

if cen in ("c1", "c9"):
    for tag in ("inicio", "fim"):
        r = load(f"relogio-{tag}.json")
        if r:
            relogios.append({"etapa": f"c1-{tag}", "ts": cfg.get(tag, ""), "skew_ms": r["offset_ms"], "incerteza_ms": r["incerteza_ms"]})
carga = load("carga.json") if cen == "c1" else None
esp = load(f"{cfg['degraus'][0]['dir']}/especial.json", {}) if (cen in ("c4", "c5") and cfg["degraus"]) else {}
# integridade (SQL: a oficial no lab Kafka)
sql_txt = (D / "reconciliacao-sql.txt").read_text() if (D / "reconciliacao-sql.txt").exists() else ""
if "0 divergencias" in sql_txt:
    div_sql = 0
elif "DIVERGENCIAS" in sql_txt:
    m_ = re.search(r"(\d+) DIVERGENCIAS REAIS", sql_txt)
    div_sql = int(m_[1]) if m_ else 1
else:
    div_sql = None
conv = re.search(r"deletes fisicos convertidos[^:]*: (\d+)", sql_txt)
div = div_sql
uu = load("uuids.json")
uuid_alt = uu["uuids_alterados"] if uu and uu.get("preexistentes_comparados", 0) > 0 else None
drenado = all(s.get("drenagem_s") is not None for s in cfg["degraus"]) and bool(cfg["degraus"])
if cen == "c1":
    drenado = bool(carga) and carga.get("drenado") is True
if cen == "c10":
    drenado = "drenado em" in ((D / "drenagem-final.txt").read_text() if (D / "drenagem-final.txt").exists() else "")
if cen == "c9":
    drenado = True
integ = {"pendentes_inicial": 0, "snapshot_antes": "../../c1/snapshots/apos-carga.csv", "pendentes_final": 0 if drenado else None,
         "divergencias": div, "divergencias_sql": div_sql, "divergencias_java": None,
         "divergencias_java_nota": "subcomando Java `reconciliacao` nao rodado no lab Kafka: conta delete fisico convertido (excluido=1) como divergencia",
         "deletes_fisicos_convertidos": int(conv[1]) if conv else None, "reconciliacao_sql": "reconciliacao-sql.txt",
         "snapshot_depois": "snapshots/apos.csv", "uuids_alterados": uuid_alt, "uuids": uu, "diferencas": "reconciliacao-sql.txt"}
lf = (D / "lag-final.txt")
if lf.exists():
    integ["lag_consumer_final"] = lf.read_text().strip()
if cen == "c1":
    integ.update(snapshot_antes="snapshots/antes.csv", snapshot_depois="snapshots/apos.csv", contagens_legado=(carga or {}).get("contagens_legado"),
                 contagens_novo=(carga or {}).get("contagens_novo"))

validos = [p for p in degraus if p["grupo"] == cen and p["valido"]]
criterios = [crit("divergencias", div, 0, div == 0), crit("uuids_alterados", uuid_alt, 0, uuid_alt == 0),
             crit("pendentes_final", integ["pendentes_final"], "0 (drenado: lag do consumer group 0, end offsets estaveis 15 s, conector RUNNING)", integ["pendentes_final"] == 0)]
medidas = {"eventos_medidos": sum(p["eventos_medidos"] for p in degraus), "relogios_validos": True}
special = {}
if cen == "c1":
    carga = carga or {}
    cl, cn = carga.get("contagens_legado"), carga.get("contagens_novo")
    criterios.append(crit("contagens_iguais", {"legado": cl, "novo": cn} if cl and cn else None, "iguais por tabela no escopo declarado", bool(cl) and cl == cn))
    ci = carga.get("carga_inicial")
    ok = bool(ci) and bool(ci.get("tabelas")) and all(ci["tabelas"][t]["linhas"] == carga["contagens_as_of_scn"][t] for t in ci["tabelas"])
    criterios.append(crit("carga_inicial", ci, "linhas/s por tabela e tempo total; linhas copiadas == linhas do legado (sem DML durante a carga)", ok))
    medidas.update(carga_inicial=ci, tempo_ate_drenado_s=carga.get("tempo_ate_drenado_s"), scn=carga.get("scn"))
    special = {"escopo": {"codigo_min_inclusivo": 0, "codigo_max_exclusivo": None, "completo": True}, "scn": carga.get("scn"),
               "scn_inicial_conector": carga.get("scn_inicial_conector"), "carga_inicial": ci, "tempos_s": carga.get("tempos_s"),
               "metodo": "lab reconstruido do zero; conector Debezium (no_data) em streaming antes da carga; CargaInicial (4 threads) com workers parados; workers aplicam o topico desde o offset 0"}
elif cen == "c4":
    p = degraus[0] if degraus else {}
    pat = (esp.get("patamar") or {}).get("maximo_ms")
    rec = esp.get("recuperacao_s") if p.get("valido") else None
    c = crit("recuperacao_rajada_s", rec, "<=120 apos commit", rec is not None and rec <= 120)
    if rec is None and p.get("valido") and esp:
        c["veredito"] = "REPROVADO" if pat is not None and (esp.get("rajada") or {}).get("completa") and esp.get("janela_recuperacao_s", 0) >= 120 else "INVALIDO"
    criterios.append(c)
    medidas.update(p95_ms=p.get("p95_ms"), recuperacao_s=esp.get("recuperacao_s"), janela_recuperacao_s=esp.get("janela_recuperacao_s"), patamar_lag_ms=pat,
                   patamar=esp.get("patamar"), rajada=esp.get("rajada"), pico_lag_fundo_apos_commit_ms=esp.get("pico_lag_fundo_apos_commit_ms"),
                   pico_lag_fundo_apos_commit_s=esp.get("pico_lag_fundo_apos_commit_s"),
                   recuperacao_s_estrita_50pct_mediana=esp.get("recuperacao_s_estrita_50pct_mediana"))
    special = {"rajada_commit": esp.get("rajada_commit"), "rajada_linhas": cfg.get("rajada_linhas_param"), "commit_iso": esp.get("rajada_commit_iso"),
               "metodo": esp.get("metodo_recuperacao"),
               "classificacao_rajada": "o worker nao ve o commit_ts da origem: eventos de TITULAR com codigo <= chave_max e criado_em em [commit - (transacao_media+1 s), commit + 0.5 s] (scripts/kf/eventos_kf.py)"}
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
if cen in ("c6", "c7"):
    p = degraus[0] if degraus else {}
    d0 = cfg["degraus"][0]["dir"] if cfg["degraus"] else ""
    qd = load(f"{d0}/queda.json", {}) or {}
    evs = {e["nome"]: e for e in qd.get("eventos", [])}
    cmpev = load(f"{d0}/comparacao-eventos.json", {}) or {}
    val = p.get("valido")
    ev = next(iter(evs), None)
    e0 = evs.get(ev) or {}

    def g(k):
        return e0.get(k) if val else None

    def relativo():
        # normalizacao relativa (extra, descritiva): primeiro balde completo apos o reinicio com lag <= max(5 s, maior lag do regime anterior a queda)
        if not val or not e0.get("baldes"):
            return None
        lim = max(5.0, qd.get("lag_max_regime_antes_s") or 0)
        ini = (e0.get("t_reinicio") or 0) - (e0.get("t_queda") or 0)
        for b in e0["baldes"]:
            if b["inicio_s"] >= ini and b["completo"] and b["lag_max_s"] <= lim:
                return round(b["inicio_s"] + 5, 2)
        return None
    rel_ = relativo()
    if cen == "c6":
        tq, tr = g("desde_queda_s"), g("desde_reinicio_s")
        criterios.append(crit("tempo_normalizar_s", tq, "lag <= 5000 ms em janela completa de 5 s durante carga apos o reinicio (tempo desde a queda)", tq is not None))
        criterios.append({"nome": "tempo_normalizar_apos_reinicio_s", "valor": tr, "alvo": "reportado (desde o docker run do worker substituto)", "veredito": "SEM_CRITERIO" if tr is not None else "INVALIDO"})
        est = g("estavel_apos_normalizar")
        criterios.append({"nome": "estavel_apos_normalizar", "valor": est, "alvo": "reportado (baldes completos seguintes com lag <= 5 s ate o fim da carga)", "veredito": "SEM_CRITERIO" if est is not None else "INVALIDO"})
        criterios.append({"nome": "tempo_normalizar_relativo_ao_regime_s", "valor": rel_, "alvo": "extra descritivo: lag <= max(5 s, maior lag do regime antes da queda)", "veredito": "SEM_CRITERIO" if rel_ is not None else "INVALIDO"})
        medidas.update(p95_ms=p.get("p95_ms"), tempo_normalizar_s=tq, apos_reinicio_s=tr, lag_normal_ms=5000, evento=ev, pico_lag_s=g("pico_lag_s"),
                       tempo_normalizar_relativo_s=rel_, lag_max_regime_antes_s=qd.get("lag_max_regime_antes_s"), replicat_running_desde_reinicio_s=g("replicat_running_desde_reinicio_s"),
                       worker_ativo_48_desde_reinicio_s=g("replicat_running_desde_reinicio_s"), normalizacao_metodo=qd.get("metodo"))
        special = {"queda": {k: e0.get(k) for k in ("nome", "t_queda", "t_reinicio", "t_kill_ret", "t_start_cmd0", "t_rep")}, "lease_seg": cfg.get("lease_seg"),
                   "queda_apos_s": cfg.get("queda_apos_s"), "reinicio_apos_s": cfg.get("reinicio_apos_s"), "exit_code_worker_morto": ((D / "especial/exit-worker-1.txt").read_text().strip() if (D / "especial/exit-worker-1.txt").exists() else None),
                   "worker_substituto": "kf-worker-2 (outro id; EVENTOS_CSV do worker e CREATE_NEW)", "evento_resultado": {k: v for k, v in e0.items() if k != "baldes"}}
    else:
        fo = load("especial/evento-failover.json", {}) or {}
        fs = round(fo["t_tomada"] - fo["t0"], 2) if fo.get("t_tomada") else None
        div_txt = (D / "especial/divisao.txt").read_text().strip() if (D / "especial/divisao.txt").exists() else ""
        # Fase 4: no modo transacional ha um unico lease global (o segundo worker fica em espera); a divisao e a tomada se medem nele.
        tx = div_txt.endswith("kf-worker-1,1") and not div_txt.endswith("kf-worker-1,24 kf-worker-2,24")
        if tx:
            criterios.append(crit("lease_global_no_worker_1", div_txt, "kf-worker-1 dono do lease global e kf-worker-2 em espera antes da queda (modo transacional: sem divisao 24/24)", True))
            criterios.append(crit("failover_s", fs, "<=20 (queda do kf-worker-1 ate o kf-worker-2 possuir o lease global valido e registrar nova marca all 48 owned partitions active; relogio do host do banco)", fs is not None and fs <= 20))
            criterios.append(crit("sobrevivente_assumiu", {"leases_globais": fo.get("leases"), "marcas": fo.get("marcas")} if fo else None, "lease global valido do sobrevivente e marca nova no log", bool(fo) and fo.get("leases") == 1))
        else:
            divisao = div_txt.endswith("kf-worker-1,24 kf-worker-2,24")
            criterios.append(crit("divisao_24_24", div_txt or None, "kf-worker-1 e kf-worker-2 com 24 leases cada antes da queda", divisao))
            criterios.append(crit("failover_s", fs, "<=20 (queda do kf-worker-1 ate o kf-worker-2 ter 48 leases validos e nova marca all 48 owned partitions active; relogio do host do banco)", fs is not None and fs <= 20))
            criterios.append(crit("sobrevivente_48", {"leases": fo.get("leases"), "marcas": fo.get("marcas")} if fo else None, "48 leases validos e marca nova no log do sobrevivente", bool(fo) and fo.get("leases") == 48))
        est = g("estavel_apos_normalizar")
        criterios.append({"nome": "estavel_apos_tomada", "valor": est, "alvo": "reportado (baldes completos seguintes com lag <= 5 s)", "veredito": "SEM_CRITERIO" if est is not None else "INVALIDO"})
        criterios.append({"nome": "tempo_normalizar_desde_queda_s", "valor": g("desde_queda_s"), "alvo": "reportado (primeiro balde de 5 s completo com lag <= 5 s)", "veredito": "SEM_CRITERIO" if g("desde_queda_s") is not None else "INVALIDO"})
        criterios.append({"nome": "tempo_normalizar_relativo_ao_regime_s", "valor": rel_, "alvo": "extra descritivo: lag <= max(5 s, maior lag do regime antes da queda)", "veredito": "SEM_CRITERIO" if rel_ is not None else "INVALIDO"})
        medidas.update(p95_ms=p.get("p95_ms"), failover_s=fs, failover_metodo="queda (inicio do docker kill, relogio do infra-server) ate 48 leases validos do sobrevivente na tabela lease_particao e nova marca all 48 owned partitions active",
                       lease_seg=cfg.get("lease_seg"), tempo_normalizar_desde_queda_s=g("desde_queda_s"), tempo_normalizar_relativo_s=rel_, pico_lag_s=g("pico_lag_s"))
        special = {"queda": {k: e0.get(k) for k in ("nome", "t_queda", "t_reinicio", "t_rep")}, "failover_s": fs, "lease_seg": cfg.get("lease_seg"), "divisao": div_txt, "tomada": fo,
                   "segunda_apos_s": cfg.get("segunda_apos_s"), "queda_apos_s": cfg.get("queda_apos_s"), "adaptado": tx, "modo": "transacional" if tx else "atual",
                   "evento_resultado": {k: v for k, v in e0.items() if k != "baldes"}}
    criterios.append({"nome": "eventos_log_x_gerador", "valor": {k2: cmpev.get(k2) for k2 in ("eventos_gerador", "eventos_no_topico", "eventos_worker_csv_bruto", "eventos_worker_csv_dedup", "duplicados_entregues", "eventos_faltantes_total", "eventos_a_mais_total")} if cmpev else None,
                      "alvo": "reportado (re-entregas apos a queda sao registradas; integridade = estado final)", "veredito": "SEM_CRITERIO" if cmpev else "INVALIDO"})
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
    criterios.append(crit("archive_necessario_preservado", {"antes_limpar": nec[0], "depois_limpar": nec[1], "depois_religar": nec[2]} if all(nec) else None, "nenhum archive log necessario (next_change# > scn do offset do conector) apagado", nec_ok))
    criterios.append(crit("recuperado_so_pelo_redo", (c8.get("reparo_ou_reconciliacao_antes_de_drenar") is False) if c8 else None, True, bool(c8) and c8.get("reparo_ou_reconciliacao_antes_de_drenar") is False))
    criterios.append({"nome": "tempo_ate_drenado_s", "valor": c8.get("tempo_ate_drenado_s"), "alvo": "reportado (desde o retorno do resume do conector ate drenar)", "veredito": "SEM_CRITERIO" if c8 else "INVALIDO"})
    medidas.update(linhas_afetadas=c8.get("linhas_afetadas"), linhas_corrigidas=corr, tempo_ate_drenado_s=c8.get("tempo_ate_drenado_s"),
                   ultima_aplicacao_apos_religar_s=c8.get("ultima_aplicacao_apos_religar_s"), divergentes_antes=c8.get("divergentes_antes"), divergentes_depois=c8.get("divergentes_depois"),
                   eventos_no_topico_pos_parada=c8.get("eventos_no_topico_pos_parada"), trocas_de_redo_forcadas=c8.get("trocas_de_redo_forcadas"))
    special = {"trigger_desligado_inicio": c8.get("captura_parada_inicio"), "trigger_desligado_fim": c8.get("captura_parada_fim"), "captura_parada_inicio": c8.get("captura_parada_inicio"), "captura_parada_fim": c8.get("captura_parada_fim"), "captura": "conector Debezium kf-oracle STOPPED (PUT /stop; supplemental logging ativo; workers e broker no ar)",
               "linhas_afetadas": c8.get("linhas_afetadas"), "linhas_afetadas_csv": "especial/linhas-afetadas.csv", "divergencias_antes": c8.get("divergentes_antes"),
               "verificacao_antes": "especial/divergentes-antes.csv", "afetadas_conferem_antes": c8.get("afetadas_conferem_antes"), "log_cobre_afetadas": c8.get("log_cobre_afetadas"),
               "linhas_corrigidas": corr, "parada_s": c8.get("parada_s"), "religar_conector_s": c8.get("religar_conector_s"), "trocas_de_redo_forcadas": c8.get("trocas_de_redo_forcadas"),
               "reconciliacao": False, "reparo": False, "metodo": "conector religado (resume) retoma do offset e le o redo (archive logs); nenhum delta, reenfileiramento ou reconciliacao reparadora"}
elif cen == "c9":
    modos = [json.loads(l) for l in (D / "modos.jsonl").read_text().splitlines()] if (D / "modos.jsonl").exists() else []
    medidas["modos"] = [m["modo"] for m in modos]
    criterios.append({"nome": "comparacao_modos_worker", "valor": "comparacao.csv", "alvo": "vazao e p95 por modo e degrau (descritivo; nao ha alvo de superioridade)", "veredito": "SEM_CRITERIO" if modos else "INVALIDO"})
    special = {"modos": modos, "observacao": "modos do worker Kafka (lote, polls, threads); C2 de 300 s e degrau de 2.000 ev/s de 300 s por modo (GG usou C2 de 600 s); fora do C2/C3 oficiais"}
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
                 "com_metricas": "nao aplicavel (nao ha evento_replicacao nem consulta de metricas no legado do lab Kafka)"}
    ov = custo["sem_metricas"]["overhead_captura"] if custo else None
    criterios.insert(2, crit("overhead_captura", ov, "<=15% E <=5 ms; segundo criterio proposto (Q-T3 pendente)",
                             ov is not None and ov["percentual"] is not None and ov["percentual"] <= 15 and ov["absoluto_ms"] <= 5))
    def cpu_de(nome, sel):
        v = [(p["cpu_docker_stats"].get(nome) or {}).get("media_pct") for p in degraus if sel(p) and p.get("cpu_docker_stats")]
        v = [x for x in v if x is not None]
        return round(statistics.mean(v), 1) if v else None
    infra = {}
    for nome in ("kf-legado", "kf-novo", "kf-kafka", "kf-connect", "kf-worker-1"):
        infra[nome] = {"ligada": cpu_de(nome, lambda p: p.get("captura") is True), "desligada": cpu_de(nome, lambda p: p.get("captura") is False)}
    mem = {}
    for nome in ("kf-legado", "kf-novo", "kf-kafka", "kf-connect", "kf-worker-1"):
        vs = [(p["cpu_docker_stats"].get(nome) or {}).get("mem_media_mib") for p in degraus if p.get("cpu_docker_stats")]
        vs = [x for x in vs if x is not None]
        mem[nome] = round(statistics.mean(vs)) if vs else None
    rep = load("repouso.json")
    medidas.update(custo_legado=custo, cpu_media_por_container_pct=infra, memoria_media_mib=mem, repouso=rep, ordem_das_fases=[(p["dir"], p.get("captura"), p.get("transacao_media_ms")) for p in degraus],
                   comparacao_java={"overhead_percentual": 19.998608792431824, "overhead_absoluto_ms": 0.958333333333333, "observacao": "triggers de captura no legado Java (resultados/c10): captura ligada 5,750 ms x desligada 4,792 ms"},
                   comparacao_gg={"overhead_percentual": 1.742621620467252, "overhead_absoluto_ms": 0.10199999999999942, "observacao": "Extract GG (resultados/gg/c10): ligada 5,955 ms x desligada 5,853 ms; gg-legado 108,7% x 95,0% de CPU"})
    por_rep = {}
    for p in pr:
        if ok_ph(p):
            por_rep.setdefault(p.get("repeticao"), {})[bool(p["captura"])] = p
    pares = [{"repeticao": r, "ligada_ms": d[True]["transacao_media_ms"], "desligada_ms": d[False]["transacao_media_ms"],
              "diferenca_ms": d[True]["transacao_media_ms"] - d[False]["transacao_media_ms"],
              "diferenca_pct": 100 * (d[True]["transacao_media_ms"] - d[False]["transacao_media_ms"]) / d[False]["transacao_media_ms"],
              "ordem": "LD" if d[True].get("posicao", 0) < d[False].get("posicao", 0) else "DL"} for r, d in sorted(por_rep.items()) if len(d) == 2]
    seq = sorted((p for p in pr if ok_ph(p) and p.get("posicao")), key=lambda p: p["posicao"])
    tend = None
    if len(seq) >= 4:
        h = len(seq) // 2
        n = len(seq); xs = list(range(n)); ys = [p["transacao_media_ms"] for p in seq]
        mx, my = statistics.mean(xs), statistics.mean(ys)
        incl = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
        tend = {"sequencia": [(p["posicao"], "L" if p["captura"] else "D", p["transacao_media_ms"]) for p in seq], "media_primeira_metade_ms": statistics.mean(ys[:h]), "media_segunda_metade_ms": statistics.mean(ys[h:]), "inclinacao_ms_por_fase_todas": incl}
    if custo:
        custo["pares"] = pares
        custo["diferenca_media_dos_pares_ms"] = statistics.mean(x["diferenca_ms"] for x in pares) if pares else None
        custo["tendencia_temporal"] = tend
    special = {"aquecimento": cfg.get("aquecimento"), "ordem": cfg.get("ordem"), "carga": {"taxa": cfg.get("taxa"), "duracao_seg_por_fase": cfg.get("dur_s"), "semente": 1, "threads": 8, "pct_insert": 0, "pct_delete": 0, "hot_pct": 10, "tx_max": 5, "mix": "40,40,20 (padrao do gerador)"},
               "criterio": "segundo criterio proposto; Q-T3 pendente", "limite_pct": 15, "limite_ms": 5, "repeticoes": reps,
               "captura_desligada": "conector Debezium STOPPED (PUT /stop) com workers, broker e Connect no ar; FORCE LOGGING e supplemental logging ALL COLUMNS continuam ativos nas duas: o custo do supplemental NAO e isolado (nao foi feita variante sem supplemental: ver notas)",
               "integridade": "aplicavel: o conector retoma pelo redo (offset no Connect); nada fica sem replicacao; drenado com o conector ligado e reconciliado"}
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
    medidas["eventos_da_janela_por_s"] = {str(p["alvo"]): round(p["eventos_medidos"] / cfg["dur_s"], 1) for p in degraus if p["eventos_medidos"]}

# validade (mesmas razoes do relatorio.py: TAXA, RELOGIO, escopo, evidencia)
motivos = []
if cen not in ("c1", "c9") and (not degraus or any(p["taxa"] is None for p in degraus)):
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
        "diretorio": str(D) if str(D).startswith("resultados/") else f"resultados/kf/{cen}/{cfg['execucao']}",
        "ambiente": {"lab": "kafka", "hosts": ["desenv-server", "infra-server"], "containers": cont,
                     "pipeline": cfg["pipeline"], "workers": cfg.get("workers"), "limites_gerador": {"cpu": 2, "memoria": 1073741824},
                     "params_sha256": cfg.get("params_sha256", {}), "jar_sha256": cfg.get("jar_sha256"), "worker_jar_sha256": cfg.get("worker_jar_sha256"),
                     "conector": load("conector-config.json"), "processos": []},
        "validade": validade, "veredito": veredito, "skew_ms": skew, "skew_incerteza_ms": inc, "relogios": relogios,
        "carga_servidores": [], "integridade": integ, "criterios": criterios, "degraus": degraus, "medidas": medidas,
        "operacao_especial": {**special, "archive_antes": load("archive-antes.json"), "archive_depois": load("archive-depois.json")},
        "parametros": {"taxa": cfg.get("taxa"), "duracao_seg": cfg.get("dur_s") if cen != "c3" else None, "degrau_seg": cfg.get("dur_s") if cen == "c3" else None,
                       "c3_taxas": cfg.get("taxas") if cen == "c3" else None, "pipeline": cfg["pipeline"], "workers": cfg.get("workers")}}
if cen == "c3":
    meta["parametros"].pop("duracao_seg")
else:
    meta["parametros"].pop("c3_taxas"); meta["parametros"].pop("degrau_seg")
if cen in ("c4", "c5"):
    meta["parametros"].update(delay_s=cfg.get("delay_s"), hold_s=cfg.get("hold_s"), rajada_linhas=cfg.get("rajada_linhas_param"))
if cen in ("c6", "c7"):
    meta["parametros"].update(queda_apos_s=cfg.get("queda_apos_s"), reinicio_apos_s=cfg.get("reinicio_apos_s"), segunda_apos_s=cfg.get("segunda_apos_s"), lease_seg=cfg.get("lease_seg"))
if cen == "c10":
    meta["parametros"].update(repeticoes=cfg.get("repeticoes"), aquecimento=cfg.get("aquecimento"), ordem=cfg.get("ordem"))
if cen == "c1":
    meta["parametros"] = {"pipeline": cfg["pipeline"], "metodo": "lab do zero; conector Debezium no_data antes da carga; CargaInicial (4 threads) com workers parados; workers aplicam o topico do offset 0"}
(D / "meta.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False) + "\n")
print(json.dumps({"veredito": veredito, "validade": validade, "criterios": {c["nome"]: (c["veredito"], c["valor"]) for c in criterios},
                  "degraus": [(p["alvo"], p["taxa"], p["validade"], p["p95_ms"], p["tendencia_lag"].get("crescimento_sustentado")) for p in degraus]}, ensure_ascii=False))
sys.exit(0 if veredito == "APROVADO" else 1)
