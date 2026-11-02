"""Seven real-environment verifications with short workloads and isolated result directories."""
import csv
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/cenarios"))
from executor import remote, unavailable  # noqa: E402
from analise import capacity, criteria, percentile, regime_band, trend  # noqa: E402

BASE = ROOT / ".tsg-flow" / ("v11-gate-" + str(time.monotonic_ns()))
BASE.mkdir(parents=True)
ENV = os.environ | {"RESULTADOS_BASE": str(BASE), "TAXA": "40", "DURACAO_SEG": "35",
                    "DEGRAU_SEG": "30", "C3_TAXAS": "20,40,100000000,200000000", "VIGILANCIA_SEG": "5",
                    "DRENAGEM_TIMEOUT_SEG": "180", "PCT_INSERT": "0", "PCT_DELETE": "0"}
SCRIPTS = {"c2": "c2-regime.sh", "c3": "c3-teto.sh", "c4": "c4-rajada.sh",
           "c5": "c5-transacao-longa.sh", "c9": "c9-threads.sh"}


def launch(scenario, options=None):
    environment = ENV | (options or {})
    logfile = BASE / f"teste-{scenario}-{time.monotonic_ns()}.log"
    with logfile.open("wb") as output:
        process = subprocess.Popen(["bash", str(ROOT / "scripts/cenarios" / SCRIPTS[scenario])],
                                   cwd=ROOT, env=environment, stdout=output, stderr=subprocess.STDOUT)
    return process, logfile


def complete(process, logfile, expected=None, scenario="c2", timeout=1000):
    try:
        status = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=180)
        except subprocess.TimeoutExpired:
            # Report the timeout; do not continue with another scenario while cleanup is pending.
            raise RuntimeError("runner nao encerrou apos SIGTERM")
        raise
    assert status in (0, 1), f"exit operacional {status}; ver {logfile}"
    if expected is not None:
        assert status == expected, f"exit {status}, esperado {expected}; ver {logfile}"
    meta = json.loads((BASE / scenario / "meta.json").read_text())
    directory = (ROOT / meta["diretorio"]).resolve()
    required = {"cenario", "execucao", "inicio", "fim", "ambiente", "validade", "skew_ms", "skew_incerteza_ms",
                "carga_servidores", "criterios", "integridade"}
    assert required <= meta.keys(), meta.keys()
    assert (directory / "meta.json").is_file()
    assert meta["criterios"]
    if expected is None:
        # The preexisting replica may be incomplete after earlier ITs. C1 is outside V-11:
        # the runner must report its integrity failure, never silently repair or approve it.
        assert meta["validade"]["estado"] == "VALIDO", meta["validade"]
        assert all(c["veredito"] in ("APROVADO", "REPROVADO", "SEM_CRITERIO") for c in meta["criterios"])
        verdict = "REPROVADO" if any(c["veredito"] == "REPROVADO" for c in meta["criterios"]) else "APROVADO"
        assert meta["veredito"] == verdict and status == (0 if verdict == "APROVADO" else 1)
        divergence = meta["integridade"]["divergencias"]
        check = next(c for c in meta["criterios"] if c["nome"] == "divergencias")
        assert check["valor"] == divergence and check["veredito"] == ("APROVADO" if divergence == 0 else "REPROVADO")
        assert (directory / meta["integridade"]["reconciliacao"]).is_file()
    return meta, directory


def run(scenario, options=None, expected=None, timeout=1000):
    return complete(*launch(scenario, options), expected=expected, scenario=scenario, timeout=timeout)


def contracts():
    # Slow ordinary events stay in the percentile; no elapsed-time heuristic excludes them.
    assert percentile({10: 90, 9000: 10}) == 9000
    empty = {"divergencias": 0, "uuids_alterados": 0, "pendentes_final": 0}
    phase = {"p95_ms": 9000, "tendencia_lag": {"crescimento_sustentado": False}}
    checks = criteria("c2", [phase], {"p95_ms": 10}, empty, {})
    assert next(c for c in checks if c["nome"] == "p95_ms")["veredito"] == "REPROVADO"
    checks = criteria("c2", [], {}, empty, {})
    assert any(c["veredito"] == "NAO_EXECUTADO" for c in checks)
    steps = [{"grupo": "c3", "valido": True, "alvo": 2000,
              "tendencia_lag": {"crescimento_sustentado": False}}]
    assert capacity(steps)["veredito"] == "APROVADO"
    assert capacity([steps[0] | {"tendencia_lag": {"crescimento_sustentado": True}}])["veredito"] == "REPROVADO"
    assert capacity([])["veredito"] == "INVALIDO"
    compared = []
    for mode in (True, False):
        compared.extend([phase | {"grupo": "c2", "virtual_threads": mode},
                         steps[0] | {"virtual_threads": mode}])
    checks = criteria("c9", compared, {}, empty, {})
    assert next(c for c in checks if c["nome"] == "p95_ms_virtual")["veredito"] == "REPROVADO"
    checks = criteria("c4", [], {"recuperacao_s": 121}, empty, {"rajada_commit": 1})
    assert checks[-1]["veredito"] == "REPROVADO"
    # Regime band: several samples, background load makes a single reading meaningless (B1).
    noisy = [{"ts": f"2026-01-01T00:00:{n:02d}Z", "pendentes": str(v)}
             for n, v in zip((0, 5, 10, 15, 20, 25, 30), (0, 1, 5, 6, 2, 1, 7))]
    from analise import instant as moment
    band = regime_band(noisy, moment("2026-01-01T00:00:05Z"), moment("2026-01-01T00:00:30Z"))
    assert (band["amostras"], band["maximo"], band["mediana"]) == (5, 6, 2), band
    assert regime_band(noisy, moment("2026-01-01T00:00:20Z"), moment("2026-01-01T00:00:30Z"))["maximo"] is None
    checks = criteria("c4", [], {"recuperacao_s": None, "janela_recuperacao_s": 125, "patamar_pendentes": None},
                      empty, {"rajada_commit": 1})
    assert checks[-1]["veredito"] == "INVALIDO"
    checks = criteria("c4", [], {"recuperacao_s": None, "janela_recuperacao_s": 125, "patamar_pendentes": 6},
                      empty, {"rajada_commit": 1})
    assert checks[-1]["veredito"] == "REPROVADO"
    checks = criteria("c5", [], {"p95_demais_ms": 9000, "apos_commit_ms": 1000,
                                "latencia_longa_ms": 31000}, empty, {"evento_longo": "42"})
    assert next(c for c in checks if c["nome"] == "p95_demais_durante_aberta_ms")["veredito"] == "REPROVADO"
    samples = [{"ts": f"2026-01-01T00:00:{n:02d}Z", "lag_ms": n * 100} for n in (0, 5, 10, 15, 20, 25)]
    from analise import instant
    assert trend(samples, instant(samples[0]["ts"]), instant(samples[-1]["ts"]))["crescimento_sustentado"]
    old = {"id": "fixture", "nome": "servico-teste", "running": True,
           "inicio": "a", "reinicios": 0, "saudavel": "healthy"}
    for change in ({"running": False}, {"inicio": "b"}, {"reinicios": 1}, {"saudavel": "unhealthy"}):
        assert unavailable({"containers": [old]}, {"containers": [old | change]}) == "servico-teste"
    assert unavailable({"containers": [old]}, {"containers": [old]}) is None


def main():
    contracts()
    # Start from a consistent replica: earlier ITs leave rows missing in poc-novo. The runner's
    # idempotent initial load fills them (no deletion, UUIDs kept); the first run proves it.
    first, first_directory = run("c2", {"PREPARAR_REPLICA": "true", "PREPARAR_TIMEOUT_SEG": "3000"}, timeout=3600)
    assert first["integridade"]["divergencias"] == 0, first["integridade"]
    assert "preparo_replica" in first["operacao_especial"]
    assert first["validade"]["estado"] == "VALIDO"
    assert first["integridade"]["uuids_alterados"] == 0
    assert first["medidas"]["p95_ms"] is not None and first["degraus"][0]["tendencia_lag"]["amostras"] >= 3
    for label in ("antes", "depois"):
        assert (first_directory / "snapshots" / label / "titular.csv").is_file()
    assert (first_directory / "recursos.csv").stat().st_size > 100
    print("1/7 C2 curto: p95, tendencia, validade e snapshots calculados", flush=True)

    invalid, _ = run("c2", {"TAXA": "100000000", "DURACAO_SEG": "8"}, expected=1)
    assert invalid["veredito"] == "INVALIDO" and "TAXA" in invalid["validade"]["motivos"]
    assert all(c["veredito"] != "APROVADO" for c in invalid["criterios"])
    print("2/7 taxa inalcançavel: INVALIDO, sem aprovacao", flush=True)

    # Inject the remote timestamp at the SSH boundary; the production runner still measures it.
    wrapper = BASE / "clock-bin"
    wrapper.mkdir()
    real_ssh = shutil.which("ssh")
    replacement = "date +%s%N | awk '{printf \"%.0f\\n\", $1+300000000}'"
    (wrapper / "ssh").write_text("#!/usr/bin/env python3\nimport os,sys\nargs=sys.argv[1:]\n"
        "if 'POC_CLOCK' in args[-1] and args[-2]=='desenv-server':\n"
        f" args[-1]=args[-1].replace('date +%s%N',{replacement!r})\n"
        f"os.execv({real_ssh!r},[{real_ssh!r}]+args)\n")
    (wrapper / "ssh").chmod(0o755)
    skewed, _ = run("c2", {"PATH": str(wrapper) + os.pathsep + os.environ["PATH"]}, expected=1)
    assert skewed["veredito"] == "INVALIDO" and "RELOGIO" in skewed["validade"]["motivos"]
    assert abs(skewed["skew_ms"]["inicio"]) > 100
    print("3/7 diferenca de relogio forcada acima de 100ms: INVALIDO", flush=True)

    fixture = f"v11-servico-teste-{os.getpid()}"
    process = None
    remote("desenv-server", ["docker", "run", "-d", "--name", fixture, "--network", "none", "--cpus=.05",
                             "--memory=32m", "eclipse-temurin:25-jre", "sleep", "1800"])
    try:
        process, logfile = launch("c2", {"DURACAO_SEG": "300", "VIGILANCIA_SEG": "2"})
        deadline = time.monotonic() + 240
        while True:
            assert process.poll() is None, f"runner encerrou antes da falha; ver {logfile}"
            names = remote("desenv-server", ["docker", "ps", "--format", "{{.Names}}", "--filter", "name=^/poc-gerador-"]).decode()
            if names.strip():
                break
            assert time.monotonic() < deadline, "gerador nao iniciou"
            time.sleep(1)
        remote("desenv-server", ["docker", "stop", "-t", "1", fixture])
        aborted, _ = complete(process, logfile, expected=1)
        assert "ABORTADO_SERVICO_EXISTENTE" in aborted["validade"]["motivos"]
        assert aborted["veredito"] == "INVALIDO"
        for host in ("desenv-server", "infra-server"):
            names = remote(host, ["docker", "ps", "-a", "--format", "{{.Names}}", "--filter", "name=" + aborted["execucao"].lower()]).decode()
            assert not names.strip(), "container do cenario abandonado"
    finally:
        if process and process.poll() is None:
            process.terminate()
            process.wait(timeout=180)
        remote("desenv-server", ["docker", "rm", "-f", fixture])
    print("4/7 container descartavel fora da PoC parado: gerador abortado e indisponibilidade registrada", flush=True)

    again, again_directory = run("c2")
    assert again["integridade"]["pendentes_inicial"] == 0
    assert again["execucao"] != first["execucao"] and again_directory != first_directory
    assert (again_directory / "snapshots/antes/titular.csv").is_file()
    assert (first_directory / "snapshots/antes/titular.csv").is_file()
    print("5/7 reexecucao: pendentes=0 e snapshot novo, execucao anterior preservada", flush=True)

    stair, _ = run("c3", expected=1)
    assert [p["alvo"] for p in stair["degraus"]] == [20, 40, 100000000]
    assert [p["validade"] for p in stair["degraus"]] == ["VALIDO", "VALIDO", "GERADOR_SATURADO"]
    assert stair["veredito"] == "INVALIDO"  # No valid 2000 step: DT-07 must not claim the ceiling.
    print("6/7 C3: saturacao encerra escada, preserva degraus validos e respeita DT-07", flush=True)

    burst, _ = run("c4", {"DURACAO_SEG": "75", "RAJADA_APOS_SEG": "35", "RAJADA_LINHAS": "100",
                           "OBSERVACAO_RECUPERACAO_SEG": "20", "PATAMAR_JANELA_SEG": "30"})
    assert burst["operacao_especial"]["rajada_linhas"] == 100
    band = burst["medidas"]["patamar"]
    assert band["amostras"] >= 3 and band["maximo"] is not None, band
    assert 0 <= burst["medidas"]["recuperacao_s"] <= 120, burst["medidas"]
    print(f"   C4 curto: patamar max={band['maximo']} mediana={band['mediana']} ({band['amostras']} amostras); "
          f"recuperacao_s={burst['medidas']['recuperacao_s']}", flush=True)
    long_tx, _ = run("c5", {"DURACAO_SEG": "40", "TX_APOS_SEG": "2", "TX_ABERTA_SEG": "8"})
    assert long_tx["medidas"]["latencia_longa_ms"] >= 8000
    assert long_tx["medidas"]["p95_demais_ms"] <= 5000 and long_tx["medidas"]["apos_commit_ms"] <= 5000
    threads, directory = run("c9", {"C3_TAXAS": "20,100000000"}, expected=1)
    with (directory / "comparacao.csv").open() as stream:
        comparison = list(csv.DictReader(stream))
    assert len(comparison) == 6 and {r["virtual_threads"] for r in comparison} == {"True", "False"}
    assert all(r["p95_ms"] for r in comparison)
    assert any(c["nome"] == "comparacao_threads" and c["veredito"] == "SEM_CRITERIO" for c in threads["criterios"])
    print("7/7 C4, C5, C9: DP-07/08 medidos e tabela comparativa sem superioridade inventada", flush=True)
    print(f"7 verificacoes passaram; evidencias: {BASE}", flush=True)


if __name__ == "__main__":
    main()
