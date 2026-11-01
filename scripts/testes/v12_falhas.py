"""Four real fault/recovery verifications; the last must fail integrity on purpose."""
import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/cenarios"))
from analise import criteria  # noqa: E402
from executor import remote, sql  # noqa: E402
from falhas import divergent_keys  # noqa: E402

BASE = ROOT / ".tsg-flow" / ("v12-gate-" + str(time.monotonic_ns()))
ENV = os.environ | {"RESULTADOS_BASE": str(BASE), "TAXA": "40", "DURACAO_SEG": "100", "LEASE_SEG": "12",
                    "QUEDA_APOS_SEG": "45", "REINICIO_APOS_SEG": "60", "SEGUNDA_APOS_SEG": "5",
                    "TRIGGER_DESLIGADO_SEG": "15", "VIGILANCIA_SEG": "5", "DRENAGEM_TIMEOUT_SEG": "180",
                    "PCT_INSERT": "0", "PCT_DELETE": "0", "PREPARAR_REPLICA": "false",
                    "DESDE_MIN": "60", "JANELA_MIN": "5"}
SCRIPTS = {"c6": "c6-queda-worker.sh", "c7": "c7-duas-replicas.sh", "c8": "c8-trigger-desligado.sh"}


def run(scenario, expected=0, **options):
    logfile = BASE / f"teste-{scenario}-{time.monotonic_ns()}.log"
    with logfile.open("wb") as output:
        process = subprocess.Popen(["bash", str(ROOT / "scripts/cenarios" / SCRIPTS[scenario])],
                                   cwd=ROOT, env=ENV | options, stdout=output, stderr=subprocess.STDOUT)
    try:
        status = process.wait(timeout=3600)
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=240)
    assert status == expected, f"exit {status}, esperado {expected}; ver {logfile}"
    meta = json.loads((BASE / scenario / "meta.json").read_text())
    directory = ROOT / meta["diretorio"]
    assert meta["validade"]["estado"] == "VALIDO", meta["validade"]
    assert meta["veredito"] == ("APROVADO" if expected == 0 else "REPROVADO"), meta["criterios"]
    assert meta["integridade"]["pendentes_inicial"] == meta["integridade"]["pendentes_final"] == 0
    assert meta["integridade"]["uuids_alterados"] == 0
    assert all(c["veredito"] in ("APROVADO", "REPROVADO", "SEM_CRITERIO") for c in meta["criterios"])
    assert (directory / "snapshots/antes/obra_musical.csv").is_file()
    assert (directory / "snapshots/depois/obra_musical.csv").is_file()
    assert (directory / "diferencas.csv").read_text().count("\n") == 1
    for host in ("desenv-server", "infra-server"):
        names = remote(host, ["docker", "ps", "-a", "--format", "{{.Names}}",
                              "--filter", "name=" + meta["execucao"].lower()]).decode()
        assert not names.strip(), "container da execucao abandonado"
    return meta, directory


def contracts():
    empty = {"divergencias": 0, "uuids_alterados": 0, "pendentes_final": 0}
    checks = criteria("c6", [], {"tempo_normalizar_s": None}, empty, {})
    assert checks[-1]["veredito"] == "NAO_EXECUTADO"
    checks = criteria("c7", [], {"failover_s": 20.01}, empty, {})
    assert next(c for c in checks if c["nome"] == "failover_s")["veredito"] == "REPROVADO"
    checks = criteria("c8", [], {}, empty, {"linhas_afetadas": 0, "linhas_corrigidas": 0,
                                          "afetadas_conferem_antes": True, "delta_cobre_afetadas": True})
    assert next(c for c in checks if c["nome"] == "linhas_afetadas")["veredito"] == "REPROVADO"


def capture_enabled():
    assert sql("desenv-server", "poc-legado", "bdad",
               "select status from user_triggers where trigger_name='TRG_OBRA_CAPTURA';") == "ENABLED"


def affected_rows(meta, directory):
    special = meta["operacao_especial"]
    with (directory / special["linhas_afetadas_csv"]).open() as stream:
        affected = {(row["tabela"], row["codigo"]) for row in csv.DictReader(stream)}
    generated = set()
    for path in directory.glob("gerador-*/gerador-linhas-*.csv"):
        with path.open() as stream:
            generated.update((row["tabela"], row["codigo"]) for row in csv.DictReader(stream)
                             if row["tabela"] == "OBRA_MUSICAL")
    before = divergent_keys((directory / special["verificacao_antes"]).read_text())
    assert len(affected) == special["linhas_afetadas"] > 0
    assert affected == generated == before
    capture_enabled()
    return affected


def main():
    BASE.mkdir(parents=True)
    print(f"Evidencias: {BASE}", flush=True)
    contracts()
    # Idempotent initial preparation precedes the snapshots and injected faults, never repairs
    # their outcome. This also lets the gate be repeated after its intentional negative case.
    recovered, _ = run("c6", PREPARAR_REPLICA="true", PREPARAR_TIMEOUT_SEG="3000")
    assert recovered["integridade"]["divergencias"] == 0
    assert recovered["medidas"]["tempo_normalizar_s"] > 0
    assert recovered["medidas"]["apos_reinicio_s"] >= 5
    assert recovered["operacao_especial"]["queda"]["exit"] == 137
    assert recovered["operacao_especial"]["lease_seg"] == 12
    print("1/4 C6: SIGKILL, reinicio, zero divergencias/UUIDs alterados e normalizacao medida", flush=True)

    failover, directory = run("c7")
    assert failover["integridade"]["divergencias"] == 0
    assert sorted(failover["operacao_especial"]["divisao"].values()) == [24, 24]
    assert failover["medidas"]["divisao_csv_24_24"] is True
    assert failover["medidas"]["sobrevivente_csv_48"] is True
    assert failover["medidas"]["soma_particoes_csv_max"] <= 48
    assert 0 <= failover["medidas"]["failover_s"] <= 20
    assert (directory / "leases.csv").stat().st_size > 100
    print(f"2/4 C7: divisao 24/24 e tomada real em {failover['medidas']['failover_s']:.3f}s (banco)", flush=True)

    repaired, directory = run("c8")
    affected = affected_rows(repaired, directory)
    assert repaired["integridade"]["divergencias_finais"] == 0
    special = repaired["operacao_especial"]
    assert special["linhas_afetadas"] == special["linhas_reenfileiradas"] == special["linhas_corrigidas"]
    assert divergent_keys((directory / special["delta_log"]).read_text()) == affected
    print(f"3/4 C8: {len(affected)} linhas do log corrigidas pelo delta; verificacao completa sem divergencias", flush=True)

    rejected, directory = run("c8", expected=1, DESDE_MIN="0", JANELA_MIN="0")
    affected = affected_rows(rejected, directory)
    special = rejected["operacao_especial"]
    assert special["linhas_reenfileiradas"] == special["linhas_corrigidas"] == 0
    assert rejected["integridade"]["divergencias_finais"] == len(affected)
    final = (directory / rejected["integridade"]["reconciliacao"]).read_text()
    assert divergent_keys(final) == affected
    assert next(c for c in rejected["criterios"] if c["nome"] == "divergencias")["veredito"] == "REPROVADO"
    print("4/4 C8 janela vazia: REPROVADO no meta.json com divergencias reais listadas", flush=True)
    print(f"4 verificacoes passaram; evidencias: {BASE}", flush=True)


if __name__ == "__main__":
    main()
