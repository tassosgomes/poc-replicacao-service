"""Four isolated checks of the public report command against synthetic scenario results."""
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
LABELS = ("Latência em regime (C2)", "Teto de vazão (C3)", "Integridade após falha (C6, C7)",
          "Estabilidade de identidade", "Failover (C7)", "Correção da captura desligada (C8)",
          "Custo no legado (C10)", "Rajada (C4)", "Transação longa (C5)", "Validade dos cenários")
SPECIFIC = {
    "c1": {"contagens_iguais": {"legado": 1700000, "novo": 1700000},
           "carga_inicial": {"tempo_total_s": 80, "tabelas": {"TITULAR": {"linhas_s": 10000}}}},
    "c2": {"p95_ms": 4321, "tendencia_lag": {"crescimento_sustentado": False}},
    "c3": {"teto_ev_s": 2000}, "c4": {"recuperacao_rajada_s": 89},
    "c5": {"p95_demais_durante_aberta_ms": 4567, "aplicacao_apos_commit_ms": 789,
           "latencia_transacao_longa_ms": 30789},
    "c6": {"tempo_normalizar_s": 75}, "c7": {"failover_s": 17.25},
    "c8": {"linhas_afetadas": 1234, "linhas_corrigidas": 1234},
    "c9": {"comparacao_threads": "comparacao.csv"},
    "c10": {"overhead_captura": {"absoluto_ms": 2.75, "percentual": 12.5}},
}


def fixture(base):
    for scenario, extra in SPECIFIC.items():
        values = {"divergencias": 0, "uuids_alterados": 0, "pendentes_final": 0} | extra
        criteria = [{"nome": name, "valor": value, "alvo": "alvo sintético",
                     "veredito": "SEM_CRITERIO" if name in ("comparacao_threads", "latencia_transacao_longa_ms") else "APROVADO"}
                    for name, value in values.items()]
        meta = {"cenario": scenario, "execucao": "fixture-1", "inicio": "2026-11-01T00:00:00Z",
                "fim": "2026-11-01T00:10:00Z", "diretorio": f"resultados/{scenario}/fixture-1",
                "validade": {"estado": "VALIDO", "motivos": []}, "veredito": "APROVADO",
                "parametros": {"taxa": 1000, "duracao_seg": 600, "degrau_seg": 300},
                "operacao_especial": {"escopo": {"completo": True}, "rajada_linhas": 50000,
                                      "criado": 1000, "commit": 1030,
                                      "trigger_desligado_inicio": "2026-11-01T00:00:00Z",
                                      "trigger_desligado_fim": "2026-11-01T00:01:00Z"}, "criterios": criteria,
                "skew_ms": {"inicio": 2, "fim": 2}, "skew_incerteza_ms": {"inicio": 1, "fim": 1},
                "relogios": [{"etapa": "inicio", "skew_ms": 2, "incerteza_ms": 1},
                             {"etapa": "fim", "skew_ms": 2, "incerteza_ms": 1}],
                "integridade": {"divergencias": 0, "uuids_alterados": 0},
                "degraus": [{"grupo": scenario, "alvo": 1000, "taxa": 1000, "valido": True,
                             "validade": "VALIDO", "virtual_threads": True}],
                "ambiente": {"hosts": ["desenv-server", "infra-server"], "jar_sha256": "sha-jar-fixture",
                             "imagem_base": "eclipse-temurin:25-jre", "limites_aplicacao": {"cpu": 2, "memoria": 1073741824},
                             "containers": [{"nome": "poc-legado", "cpu": 2000000000, "memoria": 3221225472,
                                             "imagem": "oracle-free-fixture", "imagem_id": "digest-fixture"}]},
                "carga_servidores": [{"etapa": stage, "ts": "2026-11-01T00:00:00Z",
                                      "hosts": [{"host": "infra-server", "carga_media_1m": load, "containers": []}]}
                                     for stage, load in (("antes", 1.25), ("durante", 2), ("durante", 4), ("depois", 1.5))]}
        directory = base / scenario
        directory.mkdir()
        (directory / "meta.json").write_text(json.dumps(meta))
        # A stale historical copy must never override or double count the stable entry point.
        (directory / "old").mkdir()
        (directory / "old/meta.json").write_text('{"veredito":"REPROVADO","valor":999999999}')


def edit(base, scenario, mutate):
    path = base / scenario / "meta.json"
    meta = json.loads(path.read_text())
    mutate(meta)
    path.write_text(json.dumps(meta))


def run(base):
    process = subprocess.run(["bash", str(ROOT / "scripts/relatorio.sh"), "--resultados", str(base)],
                             cwd="/tmp", capture_output=True, text=True, timeout=30)
    assert process.returncode == 0, process.stderr
    return (base / "relatorio.md").read_text()


def row(report, name):
    return next(line for line in report.splitlines() if line.startswith("| " + name + " |"))


def complete_values():
    with tempfile.TemporaryDirectory() as directory:
        base = Path(directory)
        fixture(base)
        report = run(base)
        for label in LABELS:
            parts = row(report, label).split(" | ")
            assert parts[2] and parts[3] == "APROVADO", row(report, label)
        for number in ("4321", "2000", "17.25", "100.00% (1234/1234)", "12.5", "89", "4567", "789", "10/10"):
            assert number in report, number
        assert "999999999" not in report
        assert "RECOMENDACAO_HUMANA_PENDENTE" in report
        assert "Q1" in report and "NÃO RESPONDIDO pelo legado" in report and "DP-07" in report
        assert "10,7 s / p50 14,5 s / p90 16,5 s / máx 20,02 s" in report
        assert "LEASE_SEG=12" in report and "15 por padrão" in report
        assert "unrestricted_write" in report and "resultado.taxa()" in report
        human = "Autor: Pessoa. Data: 2026-11-01. Recomendação: ajustar após analisar a CPU."
        (base / "recomendacao-humana.md").write_text(human)
        assert human in run(base) and "RECOMENDACAO_HUMANA_PENDENTE" not in run(base)
        edit(base, "c10", lambda m: next(c for c in m["criterios"] if c["nome"] == "overhead_captura").update(veredito="SEM_CRITERIO"))
        assert "SEM_CRITERIO" in row(run(base), "Custo no legado (C10)")
        edit(base, "c10", lambda m: next(c for c in m["criterios"] if c["nome"] == "overhead_captura").update(alvo="<=30% E <=10 ms"))
        assert "<=30% E <=10 ms" in row(run(base), "Custo no legado (C10)")
    print("1/4 todas as métricas do PRD: valores, vereditos, ressalvas e recomendação humana")


def failed_measurements():
    with tempfile.TemporaryDirectory() as directory:
        base = Path(directory)
        fixture(base)
        edit(base, "c2", lambda m: next(c for c in m["criterios"] if c["nome"] == "p95_ms").update(valor=9001, veredito="REPROVADO"))
        edit(base, "c4", lambda m: next(c for c in m["criterios"] if c["nome"] == "recuperacao_rajada_s").update(valor=None, veredito="REPROVADO"))
        report = run(base)
        assert "REPROVADO" in row(report, "Latência em regime (C2)")
        detail = row(report, "p95_ms")
        assert "9001" in detail and "Hipótese:" in detail and "ajustar" in detail
        assert "REPROVADO" in row(report, "Rajada (C4)")
        assert "não atingido na janela observada" in report
    print("2/4 reprovação provocada: causa provável, ajuste e não recuperação sem número inventado")


def missing_invalid():
    with tempfile.TemporaryDirectory() as directory:
        base = Path(directory)
        fixture(base)
        (base / "c7/meta.json").unlink()
        report = run(base)
        assert "NAO_EXECUTADO" in row(report, "C7")
        for label in ("Failover (C7)", "Integridade após falha (C6, C7)", "Estabilidade de identidade", "Validade dos cenários"):
            parts = row(report, label).split(" | ")
            assert parts[2] == "" and parts[3] == "NAO_EXECUTADO", parts
        edit(base, "c8", lambda m: m.update(validade={"estado": "INVALIDO", "motivos": ["RELOGIO"]},
                                           criterios=[{"nome": "linhas_afetadas", "valor": 987654321,
                                                       "alvo": 1, "veredito": "SEM_CRITERIO"}]))
        report = run(base)
        assert "987654321" not in report
        assert "INVALIDO" in row(report, "Correção da captura desligada (C8)")
        assert row(report, "Correção da captura desligada (C8)").split(" | ")[2] == ""
        edit(base, "c2", lambda m: m["parametros"].update(duracao_seg=35))
        assert "INVALIDO" in row(run(base), "Latência em regime (C2)")
        edit(base, "c4", lambda m: m["operacao_especial"].update(rajada_linhas=500))
        edit(base, "c5", lambda m: m["operacao_especial"].update(commit=1001))
        for label in ("Rajada (C4)", "Transação longa (C5)"):
            assert "INVALIDO" in row(run(base), label)
        edit(base, "c6", lambda m: m.pop("skew_ms"))
        assert "INVALIDO" in row(run(base), "C6")
        (base / "c3/meta.json").write_text("{malformed")
        assert "INVALIDO" in row(run(base), "Teto de vazão (C3)")
    print("3/4 cenário ausente/ilegível/inválido/reduzido: sem valor e sem aprovação implícita")


def resource_limits():
    with tempfile.TemporaryDirectory() as directory:
        base = Path(directory)
        fixture(base)
        execution = base / "c2/fixture-1"
        execution.mkdir()
        (execution / "recursos.csv").write_text(
            "ts,container,cpu_percent,memoria,sessoes_ativas\n"
            "2026-11-01T00:00:00Z,poc-legado,180%,2GiB / 3GiB,10\n"
            "2026-11-01T00:00:30Z,poc-legado,200%,2.1GiB / 3GiB,12\n")
        report = run(base)
        assert "| C2 | poc-legado | 2.0 | 3221225472 | oracle-free-fixture | digest-fixture |" in report
        assert "| C2 | worker/gerador/reconciliação | 2 | 1073741824 |" in report
        assert "| C2 | infra-server | durante | 2 | 3.000 | 4.000 |" in report
        assert "| C10 | infra-server | antes | 1 | 1.250 | 1.250 |" in report
        assert "| C2 | poc-legado | 2 | 190.00 | 200.00 | 2.1GiB / 3GiB | 12 |" in report
        assert "c2/fixture-1/recursos.csv" in report
        for required in ("compartilhados", "rede interna", "não equivalem à produção", "PostgreSQL", "probabilístico"):
            assert required in report
    print("4/4 limites de CPU/memória, imagens e carga antes/durante/depois por cenário")


if __name__ == "__main__":
    complete_values()
    failed_measurements()
    missing_invalid()
    resource_limits()
    print("4 verificações passaram")
