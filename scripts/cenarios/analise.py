"""Calculated scenario criteria; event CSVs include *all* latencies (DP-01/08)."""
import csv
import math
import os
import re
from collections import Counter
from datetime import datetime


def instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def percentile(histogram, fraction=.95):
    rank = math.ceil(sum(histogram.values()) * fraction)
    if rank == 0:
        return None
    count = 0
    for value, frequency in sorted(histogram.items()):
        count += frequency
        if count >= rank:
            return value


def trend(samples, start, end):
    # Last 180s for the normal 300s step; proportional window for the short gate.
    window = min(180, (end - start) * .6)
    points = [(instant(r["ts"]), float(r["lag_ms"])) for r in samples
              if end - window <= instant(r["ts"]) <= end]
    if len(points) < 3:
        return {"inclinacao_ms_s": None, "crescimento_sustentado": None,
                "janela_s": window, "amostras": len(points)}
    points.sort()
    x = [p[0] - points[0][0] for p in points]
    y = [p[1] for p in points]
    mx, my = sum(x) / len(x), sum(y) / len(y)
    denominator = sum((v - mx) ** 2 for v in x)
    slope = sum((a - mx) * (b - my) for a, b in zip(x, y)) / denominator if denominator else 0
    thirds = [y[i * len(y) // 3:(i + 1) * len(y) // 3] for i in range(3)]
    means = [sum(part) / len(part) for part in thirds]
    return {"inclinacao_ms_s": slope, "crescimento_sustentado": slope > 0 and means[0] < means[1] < means[2],
            "janela_s": window, "amostras": len(points)}


def criterion(name, value, target, passed=None):
    verdict = "NAO_EXECUTADO" if value is None else (
        "SEM_CRITERIO" if passed is None else "APROVADO" if passed else "REPROVADO")
    return {"nome": name, "valor": value, "alvo": target, "veredito": verdict}


def generator_summary(log):
    match = re.search(r"linhas=(\d+) transacoes=(\d+) taxa=([\d.]+) alvo=(\d+) transacao_media_ms=([\d.]+) (VALIDO|INVALIDO)", log)
    if not match:
        raise ValueError("resumo do gerador ausente")
    return {"linhas": int(match[1]), "transacoes": int(match[2]), "taxa": float(match[3]),
            "alvo": int(match[4]), "transacao_media_ms": float(match[5]),
            "valido": float(match[3]) >= int(match[4]) * .98}


def regime_band(samples, start, commit, window=None, minimum=3):
    """Regime band of pending events: median and maximum of the worker samples taken in the
    window that ends at the burst commit (rows are invisible to the worker before it).
    Only worker/generator clocks are used, never the laptop's wall clock."""
    window = window if window is not None else int(os.environ.get("PATAMAR_JANELA_SEG", 60))
    begin = max(start, commit - window)
    values = sorted(int(r["pendentes"]) for r in samples if begin <= instant(r["ts"]) < commit)
    band = {"metodo": "maximo das amostras do worker na janela que termina no commit da rajada",
            "janela_s": window, "amostras": len(values), "mediana": None, "maximo": None}
    if len(values) >= minimum:
        band.update(mediana=values[len(values) // 2], maximo=values[-1])
    return band


def analyze(directory, phases, special):
    samples = []
    for path in directory.glob("worker-*/*.csv"):
        if path.name.startswith("worker-"):
            with path.open() as stream:
                samples.extend(csv.DictReader(stream))
    samples.sort(key=lambda row: instant(row["ts"]))
    long_id = special.get("evento_longo")
    background = Counter()
    all_events = Counter()
    long_latency = None
    long_after_commit = None
    windows = {}
    histograms = [Counter() for _ in phases]
    for path in directory.glob("worker-*/eventos-*.csv"):
        with path.open() as stream:
            for row in csv.DictReader(stream):
                latency = int(row["latencia_ms"])
                created, confirmed = instant(row["criado_em"]), instant(row["confirmado_em"])
                is_long = row["evento_id"] == long_id
                if is_long:
                    long_latency = latency
                    long_after_commit = max(0, (confirmed - special["commit"]) * 1000)
                else:
                    all_events[latency] += 1
                    if long_id and special["criado"] <= created <= special["commit"]:
                        background[latency] += 1
                windows.setdefault(math.ceil(confirmed / 5) * 5, Counter())[latency] += 1
                for phase, histogram in zip(phases, histograms):
                    if phase["inicio"] <= created <= phase["fim"] and not is_long:
                        histogram[latency] += 1
    for phase, histogram in zip(phases, histograms):
        phase["p95_ms"] = percentile(histogram)
        phase["eventos_medidos"] = sum(histogram.values())
        phase["tendencia_lag"] = trend(samples, phase["inicio"], phase["fim"])
    result = {"p95_ms": percentile(all_events), "amostras": len(samples),
              "relogios_validos": all(r["valida"] == "VALIDA" for r in samples),
              "eventos_medidos": sum(all_events.values()) + (long_latency is not None)}
    if long_id:
        result.update(p95_demais_ms=percentile(background), latencia_longa_ms=long_latency,
                      apos_commit_ms=long_after_commit)
    if "rajada_commit" in special:
        commit = special["rajada_commit"]
        background_end = max((p["fim"] for p in phases if p["grupo"] == "c4"), default=commit)
        background_start = min((p["inicio"] for p in phases if p["grupo"] == "c4"), default=commit)
        baseline = regime_band(samples, background_start, commit)
        recovery = None
        for row in samples:
            ts = instant(row["ts"])
            # Require a complete post-commit window, never mix in the burst's exempt latency.
            if ts - 5 < commit or ts > background_end or ts % 5 != 0:
                continue
            p95 = percentile(windows.get(math.ceil(ts / 5) * 5, Counter()))
            if baseline["maximo"] is not None and int(row["pendentes"]) <= baseline["maximo"] \
                    and p95 is not None and p95 <= 5000:
                recovery = ts - commit
                break
        result["recuperacao_s"] = recovery
        result["janela_recuperacao_s"] = max(0, background_end - commit)
        result["patamar_pendentes"] = baseline["maximo"]
        result["patamar"] = baseline
    return result


def capacity(phases):
    valid = [p for p in phases if p["grupo"] == "c3" and p["valido"]]
    candidates = [p["alvo"] for p in valid if p["tendencia_lag"]["crescimento_sustentado"] is False]
    sufficient = bool(valid) and all(p["tendencia_lag"]["crescimento_sustentado"] is not None for p in valid)
    ceiling = max(candidates, default=0) if sufficient else None
    measured_2000 = any(p["alvo"] == 2000 for p in valid)
    check = criterion("teto_ev_s", ceiling, ">=2000 em degrau valido", ceiling is not None and ceiling >= 2000)
    if not measured_2000:
        check["veredito"] = "INVALIDO"
    elif not sufficient:
        check["veredito"] = "INVALIDO"
    return check


def criteria(scenario, phases, measures, integrity, special):
    checks = [criterion("divergencias", integrity.get("divergencias"), 0, integrity.get("divergencias") == 0),
              criterion("uuids_alterados", integrity.get("uuids_alterados"), 0, integrity.get("uuids_alterados") == 0),
              criterion("pendentes_final", integrity.get("pendentes_final"), 0, integrity.get("pendentes_final") == 0)]
    if scenario == "c2":
        value = phases[0].get("p95_ms") if phases else None
        checks.append(criterion("p95_ms", value, "<=5000", value is not None and value <= 5000))
        trend_value = phases[0].get("tendencia_lag", {}) if phases else {}
        rising = trend_value.get("crescimento_sustentado")
        check = criterion("tendencia_lag", trend_value if rising is not None else None,
                          "sem crescimento sustentado", rising is False)
        checks.append(check)
    elif scenario == "c3":
        checks.append(capacity(phases))
    elif scenario == "c4":
        recovery = measures.get("recuperacao_s")
        # A completed observation with no recovery is a failure, not unexecuted evidence.
        check = criterion("recuperacao_rajada_s", recovery, "<=120 apos commit", recovery is not None and recovery <= 120)
        if recovery is None and "rajada_commit" in special:
            # Without a measured regime band there is no evidence either way.
            measured = measures.get("patamar_pendentes") is not None
            check["veredito"] = "REPROVADO" if measured and measures.get("janela_recuperacao_s", 0) >= 120 else "INVALIDO"
        checks.append(check)
    elif scenario == "c5":
        for name, key in [("p95_demais_durante_aberta_ms", "p95_demais_ms"), ("aplicacao_apos_commit_ms", "apos_commit_ms")]:
            value = measures.get(key)
            check = criterion(name, value, "<=5000", value is not None and value <= 5000)
            if value is None and "evento_longo" in special:
                check["veredito"] = "REPROVADO"
            checks.append(check)
        checks.append(criterion("latencia_transacao_longa_ms", measures.get("latencia_longa_ms"), "reportada separadamente"))
    elif scenario == "c9":
        for mode in (True, False):
            subset = [p for p in phases if p["virtual_threads"] == mode]
            regime = [p for p in subset if p["grupo"] == "c2"]
            for check in criteria("c2", regime, measures, {}, special)[3:]:
                check["nome"] += "_virtual" if mode else "_plataforma"
                checks.append(check)
            check = capacity(subset)
            check["nome"] += "_virtual" if mode else "_plataforma"
            checks.append(check)
        checks.append(criterion("comparacao_threads", "comparacao.csv", "vazao e p95 por modo e degrau"))
    return checks
