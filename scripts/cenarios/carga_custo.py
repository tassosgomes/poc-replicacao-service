"""Initial-copy evidence and isolated legacy transaction-cost experiments."""
import math
import os
import re
import statistics
import threading
import time

from analise import criterion
from executor import Runner, number, pending, sql

TABLES = ("TITULAR", "OBRA_MUSICAL", "FONOGRAMA")
CAPTURE = ("TRG_TITULAR_CAPTURA", "TRG_OBRA_CAPTURA", "TRG_FONOGRAMA_CAPTURA")
UPDATED = ("TRG_TITULAR_UPDATED", "TRG_OBRA_UPDATED", "TRG_FONOGRAMA_UPDATED")


def boolean(key, default):
    value = os.environ.get(key, str(default)).lower()
    if value not in ("true", "false"):
        raise ValueError(f"{key} deve ser true ou false")
    return value == "true"


def limit(key, default):
    value = float(os.environ.get(key, default))
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"{key} deve ser finito e >= 0")
    return value


def trigger_states():
    names = ",".join("'" + name + "'" for name in CAPTURE + UPDATED)
    output = sql("desenv-server", "poc-legado", "bdad",
                 f"select trigger_name || ',' || status from user_triggers where trigger_name in ({names});")
    return dict(line.split(",") for line in output.splitlines())


def distribution(values):
    return {"repeticoes": len(values), "media_ms": statistics.mean(values),
            "desvio_padrao_ms": statistics.stdev(values) if len(values) > 1 else 0,
            "min_ms": min(values), "max_ms": max(values), "amostras_ms": values}


def overhead(on, off):
    absolute = on - off
    return {"absoluto_ms": absolute, "percentual": 100 * absolute / off if off > 0 else None}


def cost_summary(phases):
    modes = {}
    for metrics in (False, True):
        samples = {}
        for capture in (True, False):
            values = [p["transacao_media_ms"] for p in phases
                      if p["metricas"] == metrics and p["captura"] == capture]
            if len(values) < 3:
                return None
            samples["captura_ligada" if capture else "captura_desligada"] = distribution(values)
        samples["overhead_captura"] = overhead(samples["captura_ligada"]["media_ms"],
                                               samples["captura_desligada"]["media_ms"])
        modes["com_metricas" if metrics else "sem_metricas"] = samples
    modes["custo_metricas"] = {
        mode: overhead(modes["com_metricas"][mode]["media_ms"], modes["sem_metricas"][mode]["media_ms"])
        for mode in ("captura_ligada", "captura_desligada")}
    return modes


def cost_check(summary, active, pct, absolute):
    value = summary["sem_metricas"]["overhead_captura"] if summary else None
    if not active:
        return criterion("overhead_captura", value, "limite desligado; somente reportado")
    passed = value is not None and value["percentual"] is not None and value["percentual"] <= pct and value["absoluto_ms"] <= absolute
    return criterion("overhead_captura", value,
                     f"<={pct:g}% E <={absolute:g} ms; segundo criterio proposto (Q-T3 pendente)", passed)


def sample_backlog(entry, stopped, queries, errors):
    """SSH watchdog rounds can exceed a short workload: sampling owns its own clock/thread."""
    origin = None
    next_sample = 0
    try:
        while not stopped.is_set() and entry["process"].poll() is None:
            log = entry["log"].read_text()
            if "fim_carga=" in log:
                return
            if "inicio_carga=" not in log:
                stopped.wait(.05)
                continue
            if origin is None:
                origin = time.monotonic()
            started = time.monotonic()
            if started >= next_sample:
                value = sql("desenv-server", "poc-legado", "bdad",
                            "select count(*) || ',' || nvl(to_char(min(criado_em), 'YYYY-MM-DD HH24:MI:SS.FF6'), 'VAZIO') from evento_replicacao;")
                queries.append({"apos_inicio_s": started - origin, "duracao_ms": (time.monotonic() - started) * 1000,
                                "resultado": value})
                next_sample = started + 5
            stopped.wait(.05)
    except Exception as error:
        errors.append(type(error).__name__)


class LoadCostRunner(Runner):
    def __init__(self, scenario):
        super().__init__(scenario)
        self.restore_capture = False
        self.maximum = number("C1_CODIGO_MAX", 9223372036854775807)
        self.repetitions = number("C10_REPETICOES", 3, 3)
        self.active_limit = boolean("C10_LIMITE_ATIVO", True)
        self.pct_limit = limit("C10_LIMITE_PCT", 15)
        self.ms_limit = limit("C10_LIMITE_MS", 5)

    def require_triggers(self, capture=True):
        states = trigger_states()
        expected = {name: "ENABLED" for name in UPDATED}
        expected.update({name: "ENABLED" if capture else "DISABLED" for name in CAPTURE})
        if states != expected:
            raise RuntimeError("estado dos triggers de captura/updated_at inesperado")
        return states

    def capture(self, enabled):
        # Set before any DDL: a lost SSH response must still trigger restoration.
        if not enabled:
            self.restore_capture = True
        for name in CAPTURE:
            sql("desenv-server", "poc-legado", "bdad", f"alter trigger {name} " + ("enable;" if enabled else "disable;"))
        self.require_triggers(enabled)
        self.restore_capture = not enabled

    def reconciliation_options(self):
        return {"CODIGO_MAX": self.maximum} if self.scenario == "c1" else {}

    def prepare_replica(self):
        self.special["triggers_antes"] = self.require_triggers()
        self.special["escopo"] = {"codigo_min_inclusivo": 0, "codigo_max_exclusivo": self.maximum,
                                 "completo": self.maximum == 9223372036854775807}
        for table in TABLES:
            sql("infra-server", "poc-novo", "replica", f"truncate table {table};")
        self.special["contagens_apos_truncate"] = self.counts("infra-server", "poc-novo", "replica")
        entry = self.spawn("carga-inicial", "infra-server", {"THREADS": number("PREPARAR_THREADS", 4),
                                                           "CODIGO_MAX": self.maximum})
        entry["collected"] = True
        self.wait_until(lambda: entry["process"].poll() is not None, number("PREPARAR_TIMEOUT_SEG", 3600),
                        "carga inicial excedeu limite")
        self.finish(entry)
        log = entry["log"].read_text()
        tables = {name: {"linhas": int(rows), "linhas_s": float(rate)} for name, rows, rate in
                  re.findall(r"^tabela=(\w+) linhas=(\d+) linhas_s=([\d.]+)$", log, re.M)}
        total = re.search(r"^tempo_total_s=([\d.]+)$", log, re.M)
        if set(tables) != set(TABLES) or not total:
            raise RuntimeError("evidencia de carga inicial ausente")
        self.special["carga_inicial"] = {"tabelas": tables, "tempo_total_s": float(total[1]), "log": entry["log"].name}
        self.special["triggers_apos_carga"] = self.require_triggers()

    def counts(self, host, container, user):
        query = " union all ".join(f"select '{t}' || ',' || count(*) from {t} where codigo>=0 and codigo<{self.maximum}" for t in TABLES)
        return {name: int(count) for name, count in (line.split(",") for line in sql(host, container, user, query + ";").splitlines())}

    def workload(self):
        # Initial copy already ran before the worker started; no synthetic writes are needed.
        self.integrity["contagens_legado"] = self.counts("desenv-server", "poc-legado", "bdad")
        self.integrity["contagens_novo"] = self.counts("infra-server", "poc-novo", "replica")

    def execute(self):
        if self.scenario == "c1":
            super().execute()
            return
        self.setup()
        self.special.update(triggers_antes=self.require_triggers(), limite_ativo=self.active_limit,
                            limite_pct=self.pct_limit, limite_ms=self.ms_limit, repeticoes=self.repetitions,
                            criterio="segundo criterio proposto; Q-T3 pendente",
                            consulta_metricas="SELECT COUNT(*), MIN(criado_em) FROM evento_replicacao; a cada 5 s; sem worker",
                            duracao_consulta="tempo monotonic inclui SSH, conexao SQLPlus e consulta",
                            carga={"taxa": self.rate, "semente": number("SEMENTE", 1),
                                   "threads": number("THREADS", 8), "pct_insert": 0, "pct_delete": 0,
                                   "hot_pct": number("HOT_PCT", 10, 0), "tx_max": 5, "mix": "40,40,20"})
        self.integrity = {"aplicavel": False, "motivo": "C10 mede somente o legado; captura desligada produz mudancas nao replicadas"}
        for metrics in (False, True):
            for repetition in range(1, self.repetitions + 1):
                for capture in (True, False):
                    self.capture(capture)
                    self.cost_phase(metrics, capture, repetition)
        self.capture(True)
        self.special["triggers_depois"] = self.require_triggers()
        self.watch("depois", force=True)
        self.clock("fim")

    def cost_phase(self, metrics, capture, repetition):
        backlog = pending()  # Outside the timed generator workload, in *both* modes.
        states = self.require_triggers(capture)
        phase = self.phase("c10", self.rate, self.duration, options={"PCT_INSERT": 0, "PCT_DELETE": 0})
        entry = phase["entry"]
        queries = []
        phase.update(metricas=metrics, captura=capture, repeticao=repetition, consultas=queries,
                     pendentes_antes=backlog, triggers=states)
        stopped = threading.Event()
        errors = []
        sampler = threading.Thread(target=sample_backlog, args=(entry, stopped, queries, errors)) if metrics else None
        if sampler:
            sampler.start()
        try:
            self.finish_phase(phase)
        finally:
            stopped.set()
            if sampler:
                sampler.join()  # SQL/SSH has a bounded timeout; never leave a sampler behind.
        if errors:
            raise RuntimeError("consulta de metricas falhou")
        phase["pendentes_depois"] = pending()
        if metrics and not queries:
            self.reasons.append("EVIDENCIA_INSUFICIENTE")

    def evaluate(self):
        if self.scenario == "c1":
            checks = super().evaluate()
            self.measures["carga_inicial"] = self.special.get("carga_inicial")
            source = self.integrity.get("contagens_legado")
            target = self.integrity.get("contagens_novo")
            checks.append(criterion("contagens_iguais", {"legado": source, "novo": target} if source and target else None,
                                    "iguais por tabela no escopo declarado", source == target))
            load = self.measures["carga_inicial"]
            measured = bool(load and load["tempo_total_s"] > 0 and all(v["linhas_s"] > 0 for v in load["tabelas"].values()))
            checks.append(criterion("carga_inicial", load, "linhas/s por tabela e tempo total", measured))
            return checks
        summary = cost_summary(self.phases)
        self.measures = {"custo_legado": summary}
        complete = len(self.phases) == 4 * self.repetitions
        comparable = all(p["valido"] and p["transacoes"] > 0 and p["transacao_media_ms"] > 0 for p in self.phases)
        checks = [criterion("repeticoes_alternadas", len(self.phases), 4 * self.repetitions, complete),
                  criterion("taxa_valida_todos_modos", bool(self.phases) and comparable, True, comparable),
                  cost_check(summary, self.active_limit, self.pct_limit, self.ms_limit)]
        if not complete or not comparable:
            self.reasons.append("EVIDENCIA_INSUFICIENTE")
        return checks

    def cleanup(self):
        # Stop a possibly running generator *before* restoring capture after an interruption.
        super().cleanup()
        if self.restore_capture:
            try:
                self.capture(True)
                self.special["triggers_depois"] = self.require_triggers()
            except Exception:
                self.reasons.append("ERRO_RESTAURACAO_TRIGGER")
