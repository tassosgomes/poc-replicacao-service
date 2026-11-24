#!/usr/bin/env python3
"""Analise da medicao de atomicidade (Fase 4). Uso: atomicidade_analise.py GERADOR.txt SONDA.txt OFFSET_MS > resultado.json
GERADOR.txt = saida de sql/kf/testes/atom_gerador.sql (ATM_TX,seq,N,commit_epoch no relogio do kf-legado);
SONDA.txt = saida de sql/kf/testes/atom_sonda.sql (relogio do kf-novo); OFFSET_MS = relogio(kf-novo) - relogio(kf-legado) em ms."""
import json, statistics, sys

TIPOS = {1: "fonograma_sem_obra", 2: "obra_com_menos_fonogramas_que_N", 4: "obra_sem_titular_atualizado", 8: "titular_atualizado_sem_obra", 16: "outro"}


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def main():
    ger, son, off = sys.argv[1], sys.argv[2], float(sys.argv[3]) / 1000.0
    commits = {}
    for l in open(ger):
        if l.startswith("ATM_TX,"):
            _, seq, n, ts = l.strip().split(",")
            commits[int(seq)] = (int(n), float(ts))
    sonda = {}
    amostras = None
    dur_sonda = None
    for l in open(son):
        l = l.strip()
        if l.startswith("ATM_SONDA_INICIO,"):
            ini = float(l.split(",")[1])
        elif l.startswith("ATM_SONDA_FIM,"):
            _, fim, am, _lo = l.split(",")
            amostras = int(am)
            dur_sonda = float(fim) - ini
        elif l.startswith("ATM_TXS,"):
            p = l.split(",")
            f = lambda x: float(x) if x else None
            sonda[int(p[1])] = dict(n=int(p[2]) if p[2] else None, vista=f(p[3]), p0=f(p[4]), p1=f(p[5]), comp=f(p[6]), npar=int(p[7]), tipos=int(p[8]))
    intervalo = dur_sonda / amostras if amostras else None
    parciais = {s: v for s, v in sonda.items() if v["npar"] > 0}
    nunca = [s for s in commits if s not in sonda or sonda[s]["comp"] is None]
    dur_parcial = []
    for s, v in parciais.items():
        dur_parcial.append((v["p1"] - v["p0"]) + intervalo)  # de primeira a ultima amostra parcial, mais um intervalo (limite superior da janela)
    por_tipo = {n: sum(1 for v in parciais.values() if v["tipos"] & b) for b, n in TIPOS.items()}
    vis = []  # do commit na origem a transacao inteira visivel no destino
    for s, v in sonda.items():
        if v["comp"] is not None and s in commits:
            vis.append(v["comp"] - (commits[s][1] + off))
    grandes = [s for s, (n, _) in commits.items() if n == 100]
    out = dict(
        transacoes_geradas=len(commits),
        transacoes_vistas_pela_sonda=len(sonda),
        transacoes_nunca_completas=len(nunca),
        amostras=amostras,
        duracao_sonda_s=round(dur_sonda, 1) if dur_sonda else None,
        intervalo_medio_amostra_ms=round(intervalo * 1000, 1) if intervalo else None,
        transacoes_com_estado_parcial=len(parciais),
        pct_transacoes_com_estado_parcial=round(100 * len(parciais) / len(commits), 2) if commits else None,
        amostras_parciais_total=sum(v["npar"] for v in parciais.values()),
        duracao_parcial_ms=dict(n=len(dur_parcial), p50=round(pct(dur_parcial, 50) * 1000) if dur_parcial else None, p95=round(pct(dur_parcial, 95) * 1000) if dur_parcial else None, max=round(max(dur_parcial) * 1000) if dur_parcial else None),
        transacoes_parciais_por_tipo=por_tipo,
        transacoes_grandes_N100=dict(geradas=len(grandes), com_estado_parcial=sum(1 for s in grandes if s in parciais)),
        latencia_ate_transacao_inteira_visivel_s=dict(n=len(vis), p50=round(pct(vis, 50), 2) if vis else None, p95=round(pct(vis, 95), 2) if vis else None, max=round(max(vis), 2) if vis else None),
        nota="parcial = amostra em que a transacao (obra + N fonogramas + titular) esta visivel em parte; duracao = primeira a ultima amostra parcial + 1 intervalo; leitura consistente (uma consulta, um SCN)",
    )
    json.dump(out, sys.stdout, indent=1, ensure_ascii=False)
    print()


main()
