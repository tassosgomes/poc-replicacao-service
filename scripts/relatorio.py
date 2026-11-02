"""Consolidate scenario evidence; the final recommendation belongs to a human."""
import argparse
import csv
from datetime import datetime
import json
from pathlib import Path
from statistics import mean

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = tuple(f"c{i}" for i in range(1, 11))
VERDICTS = {"APROVADO", "REPROVADO", "INVALIDO", "SEM_CRITERIO", "NAO_EXECUTADO"}
METRICS = (
    ("Latência em regime (C2)", "1.000 ev/s por 600 s; p95 ≤ 5 s; lag sem tendência de alta",
     (("c2", "p95_ms"), ("c2", "tendencia_lag"))),
    ("Teto de vazão (C3)", "≥ 2.000 ev/s; degraus de 300 s",
     (("c3", "teto_ev_s"),)),
    ("Integridade após falha (C6, C7)", "0 divergências em ambos",
     (("c6", "divergencias"), ("c7", "divergencias"))),
    ("Estabilidade de identidade", "0 UUIDs alterados em C1–C9; C10 não aplicável",
     tuple((s, "uuids_alterados") for s in SCENARIOS[:-1])),
    ("Failover (C7)", "≤ 20 s", (("c7", "failover_s"),)),
    ("Correção da captura desligada (C8)", "100% das linhas afetadas corrigidas",
     (("c8", "linhas_afetadas"), ("c8", "linhas_corrigidas"))),
    ("Custo no legado (C10)", "≤ 15% E ≤ 5 ms, segundo critério proposto; Q5/Q-T3 pendentes",
     (("c10", "overhead_captura"),)),
    ("Rajada (C4)", "≤ 120 s após commit de 50.000 linhas; sem perda/divergência",
     (("c4", "recuperacao_rajada_s"), ("c4", "divergencias"), ("c4", "pendentes_final"))),
    ("Transação longa (C5)", "30 s aberta; p95 das demais ≤ 5 s; aplicação após commit ≤ 5 s",
     (("c5", "p95_demais_durante_aberta_ms"), ("c5", "aplicacao_apos_commit_ms"))),
)


def cell(value):
    if value is None:
        return ""
    if isinstance(value, (dict, list, bool)):
        value = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return str(value).replace("|", "\\|").replace("\n", " ")


def table(headers, rows):
    return ["| " + " | ".join(map(cell, row)) + " |" for row in
            (headers, ["---"] * len(headers), *rows)] + [""]


def read_scenario(base, scenario):
    path = base / scenario / "meta.json"
    if not path.exists():
        return {"state": "NAO_EXECUTADO", "reason": "meta.json ausente", "meta": {}}
    try:
        meta = json.loads(path.read_text())
        assert meta["cenario"] == scenario
        assert meta["validade"]["estado"] in ("VALIDO", "INVALIDO")
        assert isinstance(meta["criterios"], list)
        assert all(k in meta for k in ("inicio", "fim", "ambiente", "carga_servidores", "integridade",
                                       "degraus", "skew_ms", "skew_incerteza_ms", "relogios"))
        assert len({c["nome"] for c in meta["criterios"]}) == len(meta["criterios"])
        assert all(c["veredito"] in VERDICTS and "valor" in c and "alvo" in c for c in meta["criterios"])
        state = meta["validade"]["estado"]
        reasons = list(meta["validade"].get("motivos", []))
        clocks = meta["relogios"]
        if not clocks or not meta["skew_ms"] or not meta["skew_incerteza_ms"]:
            reasons.append("EVIDENCIA_INSUFICIENTE: relógios ausentes")
        elif any(abs(c["skew_ms"]) > 100 for c in clocks):
            reasons.append("RELOGIO")
        phases = meta["degraus"]
        if scenario != "c1" and not phases:
            reasons.append("EVIDENCIA_INSUFICIENTE: taxa efetiva ausente")
        for phase in phases:
            if phase["taxa"] < .98 * phase["alvo"] and not (
                    phase["grupo"] == "c3" and phase["validade"] == "GERADOR_SATURADO"):
                reasons.append("TAXA")
        # Reduced gates and partial copies cannot prove the full PRD experiment.
        params = meta.get("parametros", {})
        if scenario in ("c2", "c4", "c5", "c6", "c7", "c9"):
            if params.get("taxa") != 1000 or params.get("duracao_seg", 0) < 600:
                reasons.append("ESCOPO_REDUZIDO: exige regime 1000 ev/s por 600 s")
        if scenario in ("c3", "c9") and params.get("degrau_seg", 0) < 300:
            reasons.append("ESCOPO_REDUZIDO: exige degraus de 300 s")
        if scenario == "c1" and not meta.get("operacao_especial", {}).get("escopo", {}).get("completo"):
            reasons.append("ESCOPO_REDUZIDO: carga inicial parcial")
        if scenario == "c10" and params.get("taxa") != 1000:
            reasons.append("ESCOPO_REDUZIDO: exige carga de 1000 ev/s")
        special = meta.get("operacao_especial", {})
        if scenario == "c4" and special.get("rajada_linhas") != 50000:
            reasons.append("ESCOPO_REDUZIDO: exige rajada de 50000 linhas")
        if scenario == "c5" and special.get("commit", 0) - special.get("criado", 0) < 30:
            reasons.append("ESCOPO_REDUZIDO: exige transação aberta por 30 s")
        if scenario == "c8":
            start = datetime.fromisoformat(special["trigger_desligado_inicio"].replace("Z", "+00:00"))
            end = datetime.fromisoformat(special["trigger_desligado_fim"].replace("Z", "+00:00"))
            if (end - start).total_seconds() < 60:
                reasons.append("ESCOPO_REDUZIDO: exige captura desligada por 60 s")
        return {"state": "INVALIDO" if reasons else state, "reason": "; ".join(sorted(set(reasons))), "meta": meta}
    except (OSError, ValueError, KeyError, TypeError, AssertionError):
        return {"state": "INVALIDO", "reason": "meta.json ilegível ou contrato inválido", "meta": {}}


def check(entry, name):
    if entry["state"] != "VALIDO":
        return None, entry["state"]
    item = next((c for c in entry["meta"]["criterios"] if c["nome"] == name), None)
    if item is None:
        return None, "NAO_EXECUTADO"
    if item["veredito"] in ("INVALIDO", "NAO_EXECUTADO"):
        return None, item["veredito"]
    value = item["valor"]
    if value is None:
        if item["veredito"] == "REPROVADO":
            value = "não atingido na janela observada (sem valor numérico)"
        else:
            return None, "INVALIDO"
    return value, item["veredito"]


def aggregate(states):
    for state in ("INVALIDO", "NAO_EXECUTADO", "REPROVADO", "SEM_CRITERIO"):
        if state in states:
            return state
    return "APROVADO"


def hypothesis(scenario, name):
    if name == "uuids_alterados":
        return "Hipótese: recriação indevida de identidade", "Inspecionar diferencas.csv e UPSERT por codigo; preservar o UUID existente"
    if name in ("divergencias", "pendentes_final", "linhas_corrigidas", "contagens_iguais"):
        return "Hipótese: janela de captura/reconciliação ou aplicação incompleta", "Inspecionar chaves divergentes, drenagem e janela delta; repetir verificação completa"
    if scenario == "c7":
        return "Hipótese: lease, ciclo de claim e contenção/observação atrasam a tomada", "Correlacionar leases e atividade atual; medir com relógio do banco sem afrouxar o limite"
    if scenario == "c10":
        return "Hipótese: custo de trigger/índice e contenção do legado", "Conferir dispersão e carga, otimizar captura e repetir pares; confirmar Q-T3 com o legado"
    return "Hipótese: contenção de CPU, banco, conexões ou rede", "Correlacionar recursos.csv, taxa efetiva, lag e logs; ajustar o gargalo medido e repetir o cenário"


def render(base):
    entries = {s: read_scenario(base, s) for s in SCENARIOS}
    lines = ["# Relatório da PoC de replicação BDAD", "",
             "Fonte: última execução em cada `<cenario>/meta.json`; históricos não são contados novamente. "
             "Valores INVALIDO/NAO_EXECUTADO ficam vazios. Os CSVs e logs são a evidência oficial; "
             "a aprovação do gerador do relatório não aprova o desenho arquitetural.", "", "## Critérios do PRD", ""]
    rows = []
    for label, target, references in METRICS:
        if label.startswith("Custo") and entries["c10"]["state"] == "VALIDO":
            cost = next((c for c in entries["c10"]["meta"]["criterios"] if c["nome"] == "overhead_captura"), None)
            if cost:
                target = f"Alvo efetivo: {cost['alvo']}; segundo critério configurado, Q5/Q-T3 pendentes"
        readings = [(s, name, *check(entries[s], name)) for s, name in references]
        verdict = aggregate([r[3] for r in readings])
        value = None if verdict in ("INVALIDO", "NAO_EXECUTADO") else "; ".join(
            f"{s.upper()}/{name}: {cell(measured)}" for s, name, measured, _ in readings)
        if label.startswith("Correção") and value is not None:
            affected, corrected = readings[0][2], readings[1][2]
            if isinstance(affected, (int, float)) and affected > 0 and isinstance(corrected, (int, float)):
                value = f"{100 * corrected / affected:.2f}% ({corrected}/{affected}); " + value
        sources = ", ".join(f"[{s.upper()}]({s}/meta.json)" if (base / s / "meta.json").is_file()
                            else f"{s.upper()} (sem meta)" for s in dict.fromkeys(s for s, _ in references))
        rows.append([label, target, value, verdict, sources])
    valid = sum(e["state"] == "VALIDO" for e in entries.values())
    validity = aggregate(["APROVADO" if e["state"] == "VALIDO" else e["state"] for e in entries.values()])
    rows.append(["Validade dos cenários", "100%; taxa ≥ 98% do alvo e diferença de relógios ≤ 100 ms",
                 f"{valid}/10 válidos (100%)" if valid == 10 else None, validity, "Inventário abaixo"])
    lines += table(["Métrica", "Alvo", "Valor medido", "Veredito", "Evidência"], rows)
    lines += ["Resultados parciais válidos aparecem no detalhamento; uma métrica composta exige todas as suas evidências. "
              "C10 usa o limite efetivo registrado pelo runner; o padrão proposto DT-09 é ≤15% E ≤5 ms, "
              "sem substituir o aceite do time do legado. "
              "O teto de C3 preserva degraus válidos anteriores a GERADOR_SATURADO.", "", "## Cenários e lacunas", ""]
    rows = []
    for scenario, entry in entries.items():
        meta = entry["meta"]
        verdict = meta.get("veredito", "INVALIDO") if entry["state"] == "VALIDO" else entry["state"]
        reason = entry["reason"]
        note = base / scenario / "nao-executado.md"
        if entry["state"] == "NAO_EXECUTADO" and note.is_file():
            reason += f"; [justificativa]({scenario}/nao-executado.md)"
        rows.append([scenario.upper(), meta.get("execucao"), verdict, reason,
                     f"[meta]({scenario}/meta.json)" if meta else ""])
    lines += table(["Cenário", "Execução", "Estado", "Motivo / limitação", "Evidência"], rows)
    lines += ["## Medições e diagnóstico por cenário", ""]
    for scenario, entry in entries.items():
        lines += [f"### {scenario.upper()}", ""]
        if entry["state"] != "VALIDO":
            lines += [f"{entry['state']}: {entry['reason']}. Valores não publicados.", ""]
            continue
        meta = entry["meta"]
        lines += [f"Janela UTC: {meta.get('inicio', '')} → {meta.get('fim', '')}. "
                  f"Artefatos: `{meta.get('diretorio', '')}`; caminhos internos relativos à execução.", ""]
        rows = []
        for item in meta["criterios"]:
            value, verdict = check(entry, item["nome"])
            cause, adjustment = hypothesis(scenario, item["nome"]) if verdict == "REPROVADO" else ("", "")
            rows.append([item["nome"], value, item["alvo"], verdict, cause, adjustment])
        lines += table(["Critério", "Valor", "Alvo", "Veredito", "Causa provável (a investigar)", "Ajuste proposto"], rows)
        if scenario == "c9":
            # The source comparison is descriptive; there is no superiority target.
            lines += ["Comparação de threads: SEM_CRITERIO (nenhum alvo de superioridade).", ""]
            lines += table(["Virtual threads", "Grupo", "Alvo ev/s", "Taxa ev/s", "p95 ms", "Validade"], [
                [p.get("virtual_threads"), p.get("grupo"), p.get("alvo"),
                 p.get("taxa") if p.get("valido") else None,
                 p.get("p95_ms") if p.get("valido") else None, p.get("validade")]
                for p in meta.get("degraus", [])])
        if scenario == "c10":
            summary = meta.get("medidas", {}).get("custo_legado")
            if summary:
                lines += ["Médias, dispersão e custo das consultas (ms; transporte SSH incluído nas consultas):", ""]
                lines += table(["Modo", "Medição"], [[k, v] for k, v in summary.items()])
    lines += environment(entries, base)
    lines += open_questions()
    lines += ["## Subsídios à decisão e recomendação humana", "",
              "A tabela de critérios e os diagnósticos acima são evidências, não uma decisão de aceite. "
              "Critérios ausentes ou inválidos impedem concluir sucesso; reprovações exigem investigar as hipóteses e repetir a medição. "
              "Consistência tem prioridade sobre disponibilidade, conforme decisão do usuário; failover A→B é exceção.", ""]
    recommendation = base / "recomendacao-humana.md"
    if recommendation.is_file() and recommendation.read_text().strip():
        lines += ["Texto fornecido em `recomendacao-humana.md` (não gerado pelo consolidador):", "", recommendation.read_text().strip(), ""]
    else:
        lines += ["<!-- RECOMENDACAO_HUMANA_PENDENTE -->", "",
                  "PENDENTE DE REDAÇÃO HUMANA: seguir, ajustar (indicando o quê) ou descartar. "
                  "Registrar autor, data, evidências e riscos em `recomendacao-humana.md` e gerar novamente. "
                  "O fechamento da PoC depende desse texto; o gerador nunca escolhe a recomendação.", ""]
    return "\n".join(lines)


def environment(entries, base):
    lines = ["## Ambiente e carga dos servidores", "",
             "Servidores compartilhados desenv-server e infra-server: 8 vCPU e 15 GiB por host no planejamento, "
             "outras aplicações ativas, rede interna. A PoC usa Oracle Free nos dois lados (DT-01); "
             "os números não transferem para PostgreSQL e não equivalem à produção. "
             "Este ambiente fornece um piso de capacidade e um teto de latência de rede para comparação, "
             "sem garantir esses valores em produção. "
             "O hash da reconciliação é probabilístico, não uma prova de igualdade sem risco de colisão. "
             "Limites efetivos observados abaixo; valores ausentes não são inferidos.", ""]
    limits, loads = [], []
    for scenario, entry in entries.items():
        meta = entry["meta"]
        env = meta.get("ambiente", {})
        for container in env.get("containers", []):
            cpu = container.get("cpu")
            limits.append([scenario.upper(), container.get("nome"),
                           cpu / 1e9 if isinstance(cpu, (int, float)) else None,
                           container.get("memoria"), container.get("imagem"), container.get("imagem_id")])
        for process in env.get("processos", []):
            limits.append([scenario.upper(), process.get("nome"), process.get("cpu"), process.get("memoria"),
                           process.get("imagem"), process.get("imagem_id")])
        app = env.get("limites_aplicacao", {})
        if app:
            limits.append([scenario.upper(), "worker/gerador/reconciliação", app.get("cpu"),
                           app.get("memoria"), env.get("imagem_base"), env.get("jar_sha256")])
        samples = {}
        for observation in meta.get("carga_servidores", []):
            for host in observation.get("hosts", []):
                load = host.get("carga_media_1m")
                if isinstance(load, (int, float)):
                    key = (host["host"], observation["etapa"])
                    samples.setdefault(key, []).append(load)
        for (host, stage), values in samples.items():
            loads.append([scenario.upper(), host, stage, len(values), f"{mean(values):.3f}", f"{max(values):.3f}",
                          f"[{scenario}/meta.json]({scenario}/meta.json)"])
    lines += table(["Cenário", "Container", "CPUs", "Memória (bytes)", "Imagem", "Digest / SHA-256 do jar"], limits)
    lines += ["CPU 0/memória 0 no inventário Docker significam sem limite configurado. "
              "A carga abaixo é load average de 1 minuto do host, não percentual de CPU. "
              "O meta preserva inventários antes/durante/depois, identidade, estado e reinícios dos serviços existentes. "
              "Cada execução também arquiva recursos.csv com CPU, memória e sessões dos bancos quando coletados; "
              "a ausência de amostras não é tratada como carga zero. A carga ambiental é mostrada mesmo para um cenário inválido, "
              "sem validar suas medições de resultado.", ""]
    lines += table(["Cenário", "Host", "Etapa", "Amostras", "Load médio", "Load máximo", "Fonte"], loads)
    resources = []
    for scenario, entry in entries.items():
        execution = entry["meta"].get("execucao", "")
        path = (base / scenario / execution / "recursos.csv").resolve()
        if not path.is_relative_to(base.resolve()) or not path.is_file():
            continue
        sampled = {}
        with path.open() as stream:
            for sample in csv.DictReader(stream):
                try:
                    cpu = float(sample["cpu_percent"].removesuffix("%"))
                    sessions = int(sample["sessoes_ativas"])
                    values = sampled.setdefault(sample["container"], {"cpu": [], "sessions": [], "memory": ""})
                    values["cpu"].append(cpu)
                    values["sessions"].append(sessions)
                    values["memory"] = sample["memoria"]
                except (KeyError, ValueError):
                    continue
        for name, values in sampled.items():
            resources.append([scenario.upper(), name, len(values["cpu"]), f"{mean(values['cpu']):.2f}",
                              f"{max(values['cpu']):.2f}", values["memory"], max(values["sessions"]),
                              "próximo de 2 CPUs em ao menos uma amostra" if max(values["cpu"]) >= 190 else "",
                              f"[CSV]({scenario}/{execution}/recursos.csv)"])
    lines += ["Recursos dos containers: CPU Docker pode chegar a 200% para o limite de 2 CPUs. "
              "A marca de proximidade (pico ≥190%) é uma hipótese de saturação a correlacionar com cada degrau, "
              "não atribuição causal. Memória é a última amostra; sessões são o máximo observado. "
              "As séries e os horários completos ficam nos CSVs.", ""]
    lines += table(["Cenário", "Container", "Amostras", "CPU média %", "CPU máxima %", "Memória", "Sessões máx.", "Indício", "Fonte"], resources)
    lines += ["## Ressalvas aceitas e limites de interpretação", "",
              "Task 8.0, decisão humana de 2026-10-31 (8.0_task_review.md): em 17 medições independentes, "
              "failover min 10,7 s / p50 14,5 s / p90 16,5 s / máx 20,02 s; uma medição acima de 20 s. "
              "O gate é intermitente (~1 em 6): failover marginal e corrida no pareamento dos CSVs. "
              "A tomada por marca histórica e consulta posterior não comprova atividade simultânea dos 48 processadores. "
              "Os testes usaram JVMs locais e sinais POSIX, sem --cpus por worker; não se confundem com estes cenários Docker. "
              "LEASE_SEG=12 nos testes e 15 por padrão. O WSL2 apresenta saltos de relógio de parede; "
              "durações devem usar relógio do banco ou monotônico. Consistência sobre disponibilidade; failover A→B é exceção.", "",
              "Task 15.0: conta Elastic com escrita irrestrita (unrestricted_write) aceita pelo usuário em 2026-11-01; "
              "restrição de namespace por código, sem prova de recusa por permissão do servidor fora da PoC. "
              "CSVs permanecem oficiais. A exportação/visualização no Kibana não é comprovada pelo relatório.", "",
              "Task 11.0: recomendação não bloqueante de conferir resultado.taxa() do gerador no cenário concorrente "
              "(V10CargaInicialIT:129–152) permanece melhoria; a integridade desse teste não comprova a taxa efetiva.", ""]
    return lines


def open_questions():
    return ["## Pontos em aberto", ""] + table(["Questão", "Estado", "Resposta / pendência"], [
        ["SLA de rajada / transação longa", "RESPONDIDO no PRD", "DP-07: recuperação ≤120 s; DP-08: demais transações ≤5 s e aplicação até 5 s após commit"],
        ["Q1 updated_at", "NÃO RESPONDIDO pelo legado", "A PoC simula trigger e TIMESTAMP; confirmar fonte e precisão reais"],
        ["Q2 DELETE físico", "NÃO RESPONDIDO pelo legado", "A PoC cobre soft delete; confirmar existência de exclusão física"],
        ["Q3 jobs em massa", "NÃO RESPONDIDO pelo legado", "Rajada simulada de 50.000 linhas não estabelece volume/frequência reais"],
        ["Q5 / Q-T3 overhead", "NÃO RESPONDIDO pelo legado", "≤15% E ≤5 ms é apenas critério proposto DT-09"],
        ["Q6 pico real", "NÃO RESPONDIDO pelo legado", "1.000 ev/s é estimativa; confirmar volume de produção"],
        ["Q7 tabelas e ordem", "PENDENTE pós-PoC", "Tasso define o próximo recorte"],
        ["Q8 rollback / sequence", "PENDENTE pós-PoC", "Tasso define plano da virada"],
        ["Aceite do ADR", "PENDENTE de decisão humana", "O relatório não aceita nem move docs/adr-replocacao.md"],
    ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resultados", type=Path, default=ROOT / "resultados")
    args = parser.parse_args()
    args.resultados.mkdir(parents=True, exist_ok=True)
    output = args.resultados / "relatorio.md"
    temporary = output.with_suffix(".md.tmp")
    temporary.write_text(render(args.resultados), encoding="utf-8")
    temporary.replace(output)
    print(f"Relatório: {output}")


if __name__ == "__main__":
    main()
