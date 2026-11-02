"""Three real reduced-volume/duration checks; performance verdict may fail on shared hosts."""
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/cenarios"))
from analise import generator_summary  # noqa: E402
from carga_custo import CAPTURE, UPDATED, cost_check, cost_summary, sample_backlog, trigger_states  # noqa: E402
from executor import remote  # noqa: E402

BASE = ROOT / ".tsg-flow" / ("v13-gate-" + str(time.monotonic_ns()))
ENV = os.environ | {"RESULTADOS_BASE": str(BASE), "TAXA": "40", "DURACAO_SEG": "12", "THREADS": "2",
                    "SEMENTE": "13", "C1_CODIGO_MAX": "2001", "PREPARAR_TIMEOUT_SEG": "600",
                    "C10_REPETICOES": "3", "C10_LIMITE_ATIVO": "true", "C10_LIMITE_PCT": "15", "C10_LIMITE_MS": "5",
                    "VIGILANCIA_SEG": "5", "DRENAGEM_TIMEOUT_SEG": "180"}


def run(scenario, **options):
    script = "c1-carga-inicial.sh" if scenario == "c1" else "c10-custo-legado.sh"
    logfile = BASE / f"teste-{scenario}-{time.monotonic_ns()}.log"
    with logfile.open("wb") as output:
        process = subprocess.Popen(["bash", str(ROOT / "scripts/cenarios" / script)], cwd=ROOT,
                                   env=ENV | options, stdout=output, stderr=subprocess.STDOUT)
    try:
        status = process.wait(timeout=3600)
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=240)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    meta = json.loads((BASE / scenario / "meta.json").read_text())
    assert status in (0, 1), f"exit {status}; ver {logfile}"
    assert meta["validade"]["estado"] == "VALIDO", (meta["validade"], logfile)
    assert status == (0 if meta["veredito"] in ("APROVADO", "SEM_CRITERIO") else 1)
    assert meta["ambiente"]["jar_sha256"]
    assert meta["relogios"] and meta["carga_servidores"]
    assert trigger_states() == {name: "ENABLED" for name in CAPTURE + UPDATED}
    for host in ("desenv-server", "infra-server"):
        names = remote(host, ["docker", "ps", "-a", "--format", "{{.Names}}",
                              "--filter", "name=" + meta["execucao"].lower()]).decode()
        assert not names.strip(), "container da execucao abandonado"
    return meta, ROOT / meta["diretorio"]


def contracts():
    def summary(absolute, pct):
        return {"sem_metricas": {"overhead_captura": {"absoluto_ms": absolute, "percentual": pct}}}
    assert cost_check(summary(5, 15), True, 15, 5)["veredito"] == "APROVADO"
    assert cost_check(summary(5.001, 10), True, 15, 5)["veredito"] == "REPROVADO"
    assert cost_check(summary(1, 15.001), True, 15, 5)["veredito"] == "REPROVADO"
    assert cost_check(summary(100, 500), False, 15, 5)["veredito"] == "SEM_CRITERIO"
    assert cost_check(None, False, 15, 5)["veredito"] == "NAO_EXECUTADO"
    assert cost_summary([]) is None
    # The main coordinator may be blocked in its SSH watchdog when the workload starts.
    with tempfile.TemporaryDirectory() as directory:
        log = Path(directory) / "gerador.log"
        log.write_text("inicio_carga=2026-11-01T00:00:00Z\n")
        sampled, stopped = threading.Event(), threading.Event()
        queries, errors = [], []
        entry = {"log": log, "process": SimpleNamespace(poll=lambda: None)}
        def query(*_args):
            sampled.set()
            return "0,VAZIO"
        with patch("carga_custo.sql", side_effect=query):
            sampler = threading.Thread(target=sample_backlog, args=(entry, stopped, queries, errors))
            sampler.start()
            try:
                assert sampled.wait(5), "amostragem deve avancar enquanto o coordenador espera"
            finally:
                stopped.set()
                sampler.join(timeout=5)
            assert not sampler.is_alive() and not errors and queries


def verify_cost(meta, directory, active):
    phases = meta["degraus"]
    assert len(phases) == 12
    assert [p["captura"] for p in phases] == [True, False] * 6
    assert [p["metricas"] for p in phases] == [False] * 6 + [True] * 6
    assert [p["repeticao"] for p in phases] == [1, 1, 2, 2, 3, 3] * 2
    assert meta["operacao_especial"]["carga"]["semente"] == 13
    for phase in phases:
        raw = generator_summary((directory / phase["log"]).read_text())
        assert raw["valido"] and raw["taxa"] >= 40 * .98 and raw["transacoes"] > 0
        assert raw["transacao_media_ms"] == phase["transacao_media_ms"] > 0
        assert bool(phase["consultas"]) == phase["metricas"]
        assert all(q["duracao_ms"] > 0 for q in phase["consultas"])
        assert all(phase["triggers"][name] == "ENABLED" for name in UPDATED)
        assert all(phase["triggers"][name] == ("ENABLED" if phase["captura"] else "DISABLED") for name in CAPTURE)
    measured = meta["medidas"]["custo_legado"]
    for metrics, label in ((False, "sem_metricas"), (True, "com_metricas")):
        for capture, mode in ((False, "captura_desligada"), (True, "captura_ligada")):
            values = [p["transacao_media_ms"] for p in phases if p["metricas"] == metrics and p["captura"] == capture]
            stats = measured[label][mode]
            assert stats["repeticoes"] == 3 and stats["amostras_ms"] == values
            assert math.isclose(stats["media_ms"], statistics.mean(values))
            assert math.isclose(stats["desvio_padrao_ms"], statistics.stdev(values))
        absolute = measured[label]["captura_ligada"]["media_ms"] - measured[label]["captura_desligada"]["media_ms"]
        assert math.isclose(measured[label]["overhead_captura"]["absoluto_ms"], absolute)
        assert math.isclose(measured[label]["overhead_captura"]["percentual"], 100 * absolute / measured[label]["captura_desligada"]["media_ms"])
    value = measured["sem_metricas"]["overhead_captura"]
    expected = "SEM_CRITERIO" if not active else "APROVADO" if value["percentual"] <= 15 and value["absoluto_ms"] <= 5 else "REPROVADO"
    assert meta["veredito"] == expected
    check = next(c for c in meta["criterios"] if c["nome"] == "overhead_captura")
    assert check["veredito"] == expected and check["valor"] == value
    assert set(measured["custo_metricas"]) == {"captura_ligada", "captura_desligada"}


def main():
    BASE.mkdir(parents=True)
    print(f"Evidencias: {BASE}", flush=True)
    contracts()
    initial, _ = run("c1")
    assert initial["veredito"] == "APROVADO", initial["criterios"]
    integrity = initial["integridade"]
    assert integrity["contagens_legado"] == integrity["contagens_novo"]
    assert all(n == 2000 for n in integrity["contagens_novo"].values())
    assert integrity["divergencias"] == integrity["uuids_alterados"] == integrity["pendentes_final"] == 0
    special = initial["operacao_especial"]
    assert all(n == 0 for n in special["contagens_apos_truncate"].values())
    assert special["escopo"]["completo"] is False
    load = initial["medidas"]["carga_inicial"]
    assert load["tempo_total_s"] > 0
    assert all(v["linhas"] == 2000 and v["linhas_s"] > 0 for v in load["tabelas"].values())
    print("1/3 C1: contagens iguais, divergencias=0, linhas/s e tempo total", flush=True)

    cost, directory = run("c10")
    verify_cost(cost, directory, True)
    print(f"2/3 C10: 3 repeticoes alternadas por modo, dispersao, custo das metricas e criterio padrao {cost['veredito']}", flush=True)

    descriptive, directory = run("c10", C10_LIMITE_ATIVO="false")
    verify_cost(descriptive, directory, False)
    assert descriptive["execucao"] != cost["execucao"]
    assert (ROOT / cost["diretorio"] / "meta.json").is_file()
    print("3/3 C10: limite desligado, numeros preservados e SEM_CRITERIO", flush=True)
    print("3 verificacoes passaram", flush=True)


if __name__ == "__main__":
    main()
