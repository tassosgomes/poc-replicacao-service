"""Fault injection over the shared lifecycle; no changes to the replication algorithm."""
import csv
import re
import time

from analise import instant
from executor import Runner, number, pending, remote, sql


def database_time(host="infra-server", container="poc-novo", user="replica"):
    return sql(host, container, user,
               "select to_char(sys_extract_utc(systimestamp), 'YYYY-MM-DD\"T\"HH24:MI:SS.FF6\"Z\"') from dual;")


def divergent_keys(log):
    return set(re.findall(r"^tabela=(\w+) faixa=\d+ codigo=(\d+)$", log, re.M))


class FailureRunner(Runner):
    def __init__(self, scenario):
        super().__init__(scenario)
        self.trigger_disabled = False
        self.lease = number("LEASE_SEG", 15)
        self.special["lease_seg"] = self.lease

    def create_worker(self, virtual=True, options=None):
        return super().create_worker(virtual, {"LEASE_SEG": self.lease} | (options or {}))

    def kill_worker(self, entry):
        # Only an entry created by this runner can reach the destructive operation.
        if entry not in self.workers or not entry["name"].startswith("poc-worker-"):
            raise RuntimeError("worker da queda nao pertence a esta execucao")
        self.workers.remove(entry)
        if self.worker is entry:
            self.worker = next(iter(self.workers), None)
        killed = database_time()
        remote(entry["host"], ["docker", "kill", "-s", "KILL", entry["name"]])
        entry["process"].wait(timeout=30)
        code = remote(entry["host"], ["docker", "inspect", "--format", "{{.State.ExitCode}}", entry["name"]]).decode().strip()
        if code != "137":
            raise RuntimeError("queda nao terminou com SIGKILL (137)")
        self.special["queda"] = {"ts_banco": killed, "container": entry["name"], "sinal": "KILL", "exit": 137}
        return instant(killed)

    def load_started(self, phase):
        entry = phase["entry"]
        self.wait_until(lambda: "inicio_carga=" in entry["log"].read_text(), 90, "gerador nao iniciou carga")
        return time.monotonic()

    def until_offset(self, origin, offset):
        self.pause(max(0, origin + offset - time.monotonic()))

    def workload(self):
        if self.scenario == "c8":
            self.missing_capture()
            return
        death = number("QUEDA_APOS_SEG", 180)
        if self.scenario == "c6":
            restart = number("REINICIO_APOS_SEG", 240)
            if restart <= death:
                raise ValueError("REINICIO_APOS_SEG deve ser maior que QUEDA_APOS_SEG")
            phase = self.phase("c6", self.rate, max(self.duration, restart + 45))
            origin = self.load_started(phase)
            self.until_offset(origin, death)
            self.kill_worker(self.worker)
            self.until_offset(origin, restart)
            self.special["reinicio_ts_banco"] = database_time()
            self.start_worker()
            self.finish_phase(phase)
        else:
            phase = self.phase("c7", self.rate, max(self.duration, death + 45))
            origin = self.load_started(phase)
            self.until_offset(origin, number("SEGUNDA_APOS_SEG", 60, 0))
            first = self.worker
            second = self.create_worker()
            self.special["workers"] = {"A": first["worker_id"], "B": second["worker_id"]}
            self.wait_until(lambda: self.ownership() == {first["worker_id"]: 24, second["worker_id"]: 24},
                            90, "workers nao dividiram 24/24")
            self.special["divisao"] = self.ownership()
            self.until_offset(origin, death)
            marker = "all 48 owned partitions active"
            previous = second["log"].read_text().count(marker)
            killed = self.kill_worker(first)
            self.wait_until(lambda: second["log"].read_text().count(marker) > previous
                            and self.ownership() == {second["worker_id"]: 48},
                            90, "sobrevivente nao ativou as 48 particoes")
            taken = database_time()
            self.special.update(failover_s=instant(taken) - killed, tomada_ts_banco=taken,
                                failover_metodo="relogio do banco; 48 leases e nova marca all 48 owned partitions active",
                                sobrevivente=second["worker_id"])
            self.finish_phase(phase)

    def ownership(self):
        output = sql("infra-server", "poc-novo", "replica",
                     "select to_char(sys_extract_utc(systimestamp), 'YYYY-MM-DD\"T\"HH24:MI:SS.FF6\"Z\"') "
                     "|| ',' || dono || ',' || count(*) from lease_particao "
                     "where dono is not null and expira_em>sys_extract_utc(systimestamp) group by dono;")
        rows = [line.split(",") for line in output.splitlines() if line.strip()]
        owners = {row[1]: int(row[2]) for row in rows}
        path = self.directory / "leases.csv"
        with path.open("a") as stream:
            if path.stat().st_size == 0:
                stream.write("ts_banco,worker,particoes\n")
            for timestamp, worker, count in rows:
                stream.write(f"{timestamp},{worker},{count}\n")
        return owners

    def trigger(self, enabled):
        # Mark before disable: if transport fails after DDL, cleanup must still restore capture.
        if not enabled:
            self.trigger_disabled = True
        sql("desenv-server", "poc-legado", "bdad",
            "alter trigger trg_obra_captura " + ("enable;" if enabled else "disable;"))
        self.trigger_disabled = not enabled

    def reconcile(self, mode, dry_run, **options):
        entry = self.spawn("reconciliacao", "infra-server", {"MODO": mode, "DRY_RUN": dry_run} | options)
        entry["collected"] = True  # stdout is the reconciliation artifact
        self.wait_until(lambda: entry["process"].poll() is not None, 300, "reconciliacao excedeu limite")
        self.finish(entry)
        log = entry["log"].read_text()
        summary = re.search(r"^total=(\d+) tempo_ms=\d+ hash=", log, re.M)
        if not summary:
            raise RuntimeError("resumo da reconciliacao ausente")
        return entry["log"].name, int(summary[1]), divergent_keys(log)

    def missing_capture(self):
        # A drained replica and a stopped worker isolate exactly the uncaptured changes.
        old = self.worker
        self.worker = None
        self.finish(old, stop=True)
        duration = number("TRIGGER_DESLIGADO_SEG", 60)
        self.trigger(False)
        try:
            self.special["trigger_desligado_inicio"] = database_time("desenv-server", "poc-legado", "bdad")
            phase = self.phase("c8", self.rate, duration, options={"MIX": "0,100,0", "PCT_INSERT": 0, "PCT_DELETE": 0})
            entry = phase["entry"]
            self.finish_phase(phase)
            affected = set()
            for path in (self.directory / entry["name"].removeprefix("poc-")).glob("gerador-linhas-*.csv"):
                with path.open() as stream:
                    for row in csv.DictReader(stream):
                        if row["tabela"] == "OBRA_MUSICAL":
                            affected.add((row["tabela"], row["codigo"]))
            self.special["trigger_desligado_fim"] = database_time("desenv-server", "poc-legado", "bdad")
        finally:
            self.trigger(True)
        with (self.directory / "linhas-afetadas.csv").open("w") as stream:
            writer = csv.writer(stream)
            writer.writerow(["tabela", "codigo"])
            writer.writerows(sorted(affected))
        before_log, before_count, before = self.reconcile("completo", True)
        log, count, keys = self.reconcile("delta", False, DESDE_MIN=number("DESDE_MIN", 60, 0),
                                          JANELA_MIN=number("JANELA_MIN", 5, 0))
        self.special.update(linhas_afetadas=len(affected), linhas_afetadas_csv="linhas-afetadas.csv",
                            divergencias_antes=before_count, verificacao_antes=before_log,
                            afetadas_conferem_antes=affected == before,
                            delta_log=log, linhas_reenfileiradas=count,
                            delta_cobre_afetadas=keys == affected,
                            desde_min=number("DESDE_MIN", 60, 0), janela_min=number("JANELA_MIN", 5, 0))
        self.start_worker()

    def cleanup(self):
        try:
            if self.trigger_disabled:
                self.trigger(True)
        except Exception:
            self.reasons.append("ERRO_RESTAURACAO_TRIGGER")
        finally:
            super().cleanup()

    def save(self):
        if self.scenario == "c8" and "reconciliacao" in self.integrity:
            final = divergent_keys((self.directory / self.integrity["reconciliacao"]).read_text())
            before = divergent_keys((self.directory / self.special["verificacao_antes"]).read_text())
            self.special["linhas_corrigidas"] = len(before - final)
            self.integrity["divergencias_finais"] = self.integrity["divergencias"]
        return super().save()
