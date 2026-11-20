#!/usr/bin/env python3
"""Analise dos cenarios c4 (rajada) e c5 (transacao longa) do lab GoldenGate. Uso:
  analisar-especial.py c4|c5 EVENTOS_CSV ENTRADA_JSON OFFSET_MS DIR_SAIDA
EVENTOS_CSV vem de scripts/gg/eventos-janela.sh (aplicado_epoch_s,latencia_bruta_s,tabela,codigo,commit_ts_origem).
OFFSET_MS = relogio(infra-server) - relogio(desenv-server) (scripts/medir-relogios.py); subtraido de latencias e instantes de
aplicacao, de modo que tudo fica na linha do tempo do desenv-server (legado e gerador). So relogios dos bancos/gerador.
Saida em DIR_SAIDA: latencia.json (mesmo formato de analisar-latencia.py, so com as transacoes "de fundo": sem a rajada / sem a
transacao longa), especial.json (medidas do cenario) e baldes-5s.csv (evidencia por balde de 5 s).

c4: a rajada e a transacao unica de RAJADA_LINHAS linhas em TITULAR (chaves <= chave_max) cujo commit_ts de origem e o do commit
    da rajada (o commit_ts do GG tem 1 s de resolucao: transacoes de fundo de TITULAR com chave <= chave_max no mesmo segundo
    do commit seriam contadas como rajada; o excesso sobre RAJADA_LINHAS e registrado). Patamar = MAXIMO da latencia por balde de
    5 s (eventos de fundo, por aplicado_em) nos baldes inteiros dos ultimos PATAMAR_JANELA_SEG antes do commit (>= 3 amostras, senao
    patamar None -> INVALIDO). Recuperacao = primeiro balde de 5 s inteiramente apos o commit, dentro do regime ativo e completo
    (com ao menos 1 evento de fundo; a variante exigindo >= 50% da mediana de eventos fica em recuperacao_s_estrita_50pct_mediana, pois a rajada reduz a taxa do gerador), com a rajada inteira ja aplicada (equivale a "pendentes <= patamar" do worker), latencia
    maxima do balde <= patamar e p95 do balde <= 5000 ms; o tempo parte do commit e termina no fim do balde (como o runner Java).
c5: a transacao longa e o evento de TITULAR/1 com origem_updated_at em [t0, t1] (instantes do banco legado antes/depois do UPDATE).
    p95 das demais = eventos de fundo com origem_updated_at entre o UPDATE da longa e seu commit; aplicacao apos commit =
    aplicado_em da longa (corrigido) - commit (relogio do banco legado)."""
import json, math, subprocess, sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI.parents[0] / "cenarios"))
import analise  # noqa: E402

tipo, csv, entrada, off_ms, saida = sys.argv[1:6]
off = float(off_ms) / 1000
cfg = json.loads(Path(entrada).read_text())
saida = Path(saida); saida.mkdir(parents=True, exist_ok=True)
ev = []  # (ap_raw, lat_raw, tabela, codigo, cts)
for l in Path(csv).read_text().splitlines():
    a, b, t, c, s = l.split(",")
    ev.append((float(a), float(b), t, int(c), s))
iso = lambda t: datetime.fromtimestamp(t, timezone.utc).isoformat().replace("+00:00", "Z")


def rank(v, p):
    v = sorted(v)
    return v[max(0, math.ceil(len(v) * p) - 1)] if v else None


def escrever_latencia(eventos):
    """latencia.json no formato de analisar-latencia.py, sobre os eventos de fundo."""
    p = saida / "latencias-fundo.csv"
    p.write_text("".join(f"{a:.6f},{b:.6f}\n" for a, b, *_ in eventos))
    r = subprocess.run([sys.executable, "-I", str(AQUI / "analisar-latencia.py"), str(p), cfg["ini"], cfg["fim"], off_ms],
                       capture_output=True, text=True, check=True)
    (saida / "latencia.json").write_text(r.stdout)
    p.unlink()


out = {"cenario": tipo, "offset_relogio_ms": off * 1000}
if tipo == "c4":
    commit = analise.instant(cfg["commit"]); n_alvo = cfg["rajada_linhas"]; kmax = cfg["chave_max"]
    jan = cfg.get("patamar_janela_s", 60); ini = analise.instant(cfg["ini"]); fim = analise.instant(cfg["fim"])
    cand = Counter(e[4] for e in ev if e[2] == "TITULAR" and e[3] <= kmax)
    cts_b, n_b = cand.most_common(1)[0] if cand else (None, 0)
    ehb = lambda e: e[2] == "TITULAR" and e[3] <= kmax and e[4] == cts_b and cts_b is not None
    burst = [e for e in ev if ehb(e)]; fundo = [e for e in ev if not ehb(e)]
    rajada = {"linhas_esperadas": n_alvo, "linhas_classificadas": len(burst), "excesso_ambiguo": len(burst) - n_alvo,
              "completa": len(burst) >= n_alvo, "commit_ts_origem": cts_b,
              "commit_ts_confere": cts_b is not None and abs(int(cts_b) - int(datetime.fromtimestamp(commit, timezone.utc).strftime("%Y%m%d%H%M%S"))) <= 1}
    ba = sorted(e[0] - off for e in burst)
    if ba:
        bl = [e[1] - off for e in burst]
        rajada.update(aplicada_ini_apos_commit_s=round(ba[0] - commit, 3), aplicada_fim_apos_commit_s=round(ba[-1] - commit, 3),
                      latencia_s={"p50": round(rank(bl, .5), 3), "p95": round(rank(bl, .95), 3), "max": round(max(bl), 3)})
    # baldes de fundo (grade de 5 s na linha do tempo do desenv-server)
    bk, bb = {}, Counter()
    for e in fundo: bk.setdefault(int((e[0] - off) // 5) * 5, []).append(e[1] - off)
    for e in burst: bb[int((e[0] - off) // 5) * 5] += 1
    regime = [len(v) for b, v in bk.items() if ini <= b and b + 5 <= commit]
    mediana_n = sorted(regime)[len(regime) // 2] if regime else 0
    completo = lambda b: len(bk.get(b, [])) >= 1  # balde inteiro de 5 s com eventos de fundo
    estrito = lambda b: mediana_n > 0 and len(bk.get(b, [])) >= 0.5 * mediana_n  # sensibilidade: >= 50% da mediana de eventos do regime
    amostras = [(b, max(bk[b]) * 1000) for b in sorted(bk) if b >= max(ini, commit - jan) and b + 5 <= commit and completo(b)]
    pat = {"metodo": "maximo da latencia por balde de 5 s (eventos de fundo, por aplicado_em) nos baldes inteiros que terminam no commit da rajada",
           "janela_s": jan, "amostras": len(amostras), "mediana_ms": None, "maximo_ms": None}
    if len(amostras) >= 3:
        v = sorted(x for _, x in amostras); pat.update(mediana_ms=round(v[len(v) // 2], 1), maximo_ms=round(v[-1], 1))
    rec = rec_ini = rec_estrita = None; ultima_aplicada = ba[-1] if ba else None
    if pat["maximo_ms"] is not None and rajada["completa"]:
        for b in sorted(bk):
            if b < commit or b + 5 > fim or not completo(b): continue
            if ultima_aplicada is None or ultima_aplicada > b + 5: continue
            if max(bk[b]) * 1000 <= pat["maximo_ms"] and rank(bk[b], .95) * 1000 <= 5000:
                if rec is None: rec, rec_ini = round(b + 5 - commit, 3), round(b - commit, 3)
                if estrito(b): rec_estrita = round(b + 5 - commit, 3); break
    apos = [(b, max(v) * 1000) for b, v in sorted(bk.items()) if b + 5 > commit and completo(b)]
    pico = max(apos, key=lambda x: x[1]) if apos else None
    with (saida / "baldes-5s.csv").open("w") as f:
        f.write("balde_ini_apos_commit_s,eventos_fundo,lag_max_ms,p95_ms,media_ms,linhas_rajada_aplicadas\n")
        for b in sorted(set(bk) | set(bb)):
            v = bk.get(b, [])
            if abs(b - commit) <= 400:
                f.write(f"{b - commit:.1f},{len(v)},{max(v) * 1000 if v else ''},{(rank(v, .95) * 1000) if v else ''},"
                        f"{(sum(v) / len(v) * 1000) if v else ''},{bb.get(b, 0)}\n")
    out.update(rajada_commit=commit, rajada_commit_iso=iso(commit), rajada=rajada, patamar=pat,
               recuperacao_s=rec, recuperacao_inicio_s=rec_ini, recuperacao_s_estrita_50pct_mediana=rec_estrita, janela_recuperacao_s=max(0, round(fim - commit, 3)),
               pico_lag_fundo_apos_commit_ms=None if pico is None else round(pico[1], 1),
               pico_lag_fundo_apos_commit_s=None if pico is None else round(pico[0] + 2.5 - commit, 1),
               mediana_eventos_por_balde=mediana_n, eventos_fundo=len(fundo),
               metodo_recuperacao="primeiro balde de 5 s inteiro apos o commit, regime ativo e completo, rajada inteira aplicada, lag maximo <= patamar e p95 <= 5000 ms; tempo = fim do balde - commit")
    escrever_latencia(fundo)
else:
    t0 = analise.instant(cfg["t0"]); t1 = analise.instant(cfg["t1"]); commit = analise.instant(cfg["commit"])
    longa = [e for e in ev if e[2] == "TITULAR" and e[3] == 1 and t0 - 0.001 <= e[0] - e[1] <= t1 + 0.001]
    out.update(candidatos_longa=len(longa))
    if len(longa) == 1:
        a, b, *_ = longa[0]
        criado = a - b  # origem_updated_at (relogio do legado)
        fundo = [e for e in ev if e is not longa[0]]
        durante = [e[1] - off for e in fundo if criado <= e[0] - e[1] <= commit]
        out.update(evento_longo=f"TITULAR/1@{iso(criado)}", criado=criado, commit=commit, criado_iso=iso(criado), commit_iso=iso(commit),
                   aberta_s=round(commit - criado, 3), eventos_durante_aberta=len(durante),
                   p95_demais_ms=round(rank(durante, .95) * 1000) if durante else None,
                   p50_demais_ms=round(rank(durante, .5) * 1000) if durante else None,
                   max_demais_ms=round(max(durante) * 1000) if durante else None,
                   apos_commit_ms=round(max(0, (a - off) - commit) * 1000, 1), apos_commit_bruto_ms=round(((a - off) - commit) * 1000, 1),
                   latencia_longa_ms=round((b - off) * 1000), aplicada_em_iso=iso(a - off))
        escrever_latencia(fundo)
    else:
        escrever_latencia(ev)
(saida / "especial.json").write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n")
print(json.dumps(out, ensure_ascii=False)[:1500])
