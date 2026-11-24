#!/usr/bin/env python3
"""Converte os CSV de eventos dos workers Kafka (eventos-<worker>.csv[.gz]: evento_id,tabela,codigo,criado_em,confirmado_em,latencia_ms)
no formato dos analisadores do lab GG (scripts/gg/analisar-*.py), que servem aos dois labs:
  aplicado_epoch_s,latencia_bruta_s,TABELA,codigo,cts (proxy do commit_ts: segundo de criado_em; na rajada, o segundo do commit)
aplicado = confirmado_em (commit no kf-novo, relogio do infra-server); latencia bruta = confirmado_em - criado_em (criado_em = updated_at da
origem, relogio do legado), SEM correcao de relogio (os analisadores subtraem o offset medido por scripts/medir-relogios.py).
Uso: eventos_kf.py DIR_WORKERS INI FIM SAIDA [--dedupe] [--rajada COMMIT_ISO KMAX JANELA_S]
  INI/FIM: inicio_carga/fim_carga do gerador (UTC); so entram eventos com criado_em na janela.
  --dedupe: um evento por (tabela,codigo,criado_em), o de menor confirmado_em (re-entregas do at-least-once do Connect/broker).
  --rajada: marca como 'cts' = commit da rajada (yyyymmddHH24MISS) os eventos de TITULAR com codigo <= KMAX e criado_em em
            [COMMIT-JANELA_S, COMMIT+0.5] (o worker nao ve o commit_ts da origem; o analisador separa a rajada por esse campo). O excesso de
            eventos de fundo na mesma janela e registrado pelo analisador (excesso_ambiguo), como no GG.
Imprime JSON com contagens (lidos, na_janela, duplicados_descartados)."""
import glob, gzip, json, re, sys
from datetime import datetime, timezone
from pathlib import Path


def epoch(s):
    s = s.strip()
    m = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?Z$", s)
    if not m:
        raise ValueError(s)
    frac = (m[2] or "0")[:6].ljust(6, "0")
    return datetime.fromisoformat(m[1] + "+00:00").timestamp() + int(frac) / 1e6


def abrir(p):
    return gzip.open(p, "rt") if p.endswith(".gz") else open(p)


d, ini, fim, saida = sys.argv[1:5]
args = sys.argv[5:]
dedupe = "--dedupe" in args
raj = None
if "--rajada" in args:
    i = args.index("--rajada"); raj = (epoch(args[i + 1]), int(args[i + 2]), float(args[i + 3]))
t_ini, t_fim = epoch(ini), epoch(fim)
arquivos = sorted(glob.glob(f"{d}/**/eventos-*.csv", recursive=True) + glob.glob(f"{d}/**/eventos-*.csv.gz", recursive=True))
lidos = naj = 0
eventos = {}
lista = []
for f in arquivos:
    with abrir(f) as fh:
        next(fh, None)
        for l in fh:
            t = l.rstrip("\n").split(",")
            if len(t) < 6:
                continue
            lidos += 1
            c = epoch(t[3])
            if not (t_ini <= c <= t_fim):
                continue
            naj += 1
            a = epoch(t[4]); tab = t[1]; cod = int(t[2])
            if dedupe:
                k = (tab, cod, t[3])
                if k not in eventos or a < eventos[k][0]:
                    eventos[k] = (a, c, tab, cod)
            else:
                lista.append((a, c, tab, cod))
if dedupe:
    lista = list(eventos.values())
lista.sort()
cts_raj = "0"
if raj:
    cts_raj = datetime.fromtimestamp(raj[0], timezone.utc).strftime("%Y%m%d%H%M%S")
with open(saida, "w") as o:
    for a, c, tab, cod in lista:
        cts = datetime.fromtimestamp(c, timezone.utc).strftime("%Y%m%d%H%M%S")  # proxy do commit_ts (segundo de criado_em)
        if raj and tab == "TITULAR" and cod <= raj[1] and raj[0] - raj[2] <= c <= raj[0] + 0.5:
            cts = cts_raj
        o.write(f"{a:.6f},{a - c:.6f},{tab},{cod},{cts}\n")
print(json.dumps({"arquivos": len(arquivos), "lidos": lidos, "na_janela": naj, "duplicados_descartados": naj - len(lista) if dedupe else 0, "escritos": len(lista)}))
